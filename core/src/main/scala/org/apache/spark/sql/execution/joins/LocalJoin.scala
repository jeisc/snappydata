/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.joins

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Callable, ExecutionException}

import scala.collection.mutable
import scala.reflect.ClassTag

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.google.common.cache.CacheBuilder

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SnappySession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{AttributeSet, BindReferences, Expression}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.{ClusteredDistribution, Distribution, Partitioning, UnspecifiedDistribution}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.snappy._
import org.apache.spark.sql.types.TypeUtilities
import org.apache.spark.{Partition, ShuffleDependency, TaskContext}

/**
 * :: DeveloperApi ::
 * Performs an local hash join of two child relations. If a relation
 * (out of a datasource) is already replicated accross all nodes then rather
 * than doing a Broadcast join which can be expensive, this join just
 * scans through the single partition of the replicated relation while
 * streaming through the other relation.
 */
@DeveloperApi
case class LocalJoin(leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    buildSide: BuildSide,
    condition: Option[Expression],
    joinType: JoinType,
    left: SparkPlan,
    right: SparkPlan,
    replicatedTableJoin: Boolean)
    extends BinaryExecNode with HashJoin with BatchConsumer {

  override def nodeName: String = "LocalJoin"

  @transient private var mapAccessor: ObjectHashMapAccessor = _
  @transient private var hashMapTerm: String = _
  @transient private var mapDataTerm: String = _
  @transient private var maskTerm: String = _
  @transient private var keyIsUniqueTerm: String = _
  @transient private var numRowsTerm: String = _
  @transient private var dictionaryArrayTerm: String = _

  @transient val (metricAdd, _): (String => String, String => String) =
    Utils.metricMethods(sparkContext)

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "buildDataSize" -> SQLMetrics.createSizeMetric(sparkContext, "data size of build side"),
    "buildTime" -> SQLMetrics.createTimingMetric(sparkContext, "time to build hash map"))

  override def outputPartitioning: Partitioning = streamedPlan.outputPartitioning

  override def requiredChildDistribution: Seq[Distribution] =
    if (replicatedTableJoin) {
      UnspecifiedDistribution :: UnspecifiedDistribution :: Nil
    }
    else {
      ClusteredDistribution(leftKeys) :: ClusteredDistribution(rightKeys) :: Nil
    }

  protected lazy val (buildSideKeys, streamSideKeys) = {
    require(leftKeys.map(_.dataType) == rightKeys.map(_.dataType),
      "Join keys from two sides should have same types")
    buildSide match {
      case BuildLeft => (leftKeys, rightKeys)
      case BuildRight => (rightKeys, leftKeys)
    }
  }

  private lazy val streamRDD = streamedPlan.execute()
  private lazy val buildRDD = buildPlan.execute()


  /**
   * Overridden by concrete implementations of SparkPlan.
   * Produces the result of the query as an RDD[InternalRow]
   */
  override protected def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")
    val buildDataSize = longMetric("buildDataSize")
    val buildTime = longMetric("buildTime")

    // materialize dependencies in the entire buildRDD graph for
    // buildRDD.iterator to work in the compute of mapPartitionsPreserve below
    if (buildRDD.partitions.length == 1) {
      materializeDependencies(buildRDD, new mutable.HashSet[RDD[_]]())
      streamRDD.mapPartitionsPreserveWithPartition { (context, split, itr) =>
        val start = System.nanoTime()
        val hashed = HashedRelation(buildRDD.iterator(split, context),
          buildKeys, taskMemoryManager = context.taskMemoryManager())
        buildTime += (System.nanoTime() - start) / 1000000L
        val estimatedSize = hashed.estimatedSize
        buildDataSize += estimatedSize
        context.taskMetrics().incPeakExecutionMemory(estimatedSize)
        context.addTaskCompletionListener(_ => hashed.close())
        join(itr, hashed, numOutputRows)
      }
    } else {
      streamRDD.zipPartitions(buildRDD) { (streamIter, buildIter) =>
        val hashed = buildHashedRelation(buildIter)
        join(streamIter, hashed, numOutputRows)
      }
    }
  }

  private def buildHashedRelation(iter: Iterator[InternalRow]): HashedRelation = {
    val buildDataSize = longMetric("buildDataSize")
    val buildTime = longMetric("buildTime")
    val start = System.nanoTime()
    val context = TaskContext.get()
    val relation = HashedRelation(iter, buildKeys, taskMemoryManager = context.taskMemoryManager())
    buildTime += (System.nanoTime() - start) / 1000000
    buildDataSize += relation.estimatedSize
    // This relation is usually used until the end of task.
    context.addTaskCompletionListener(_ => relation.close())
    relation
  }

  private[spark] def materializeDependencies[T](rdd: RDD[T],
      visited: mutable.HashSet[RDD[_]]): Unit = {
    rdd.dependencies.foreach(dep =>
      if (visited.add(dep.rdd)) materializeDependencies(dep.rdd, visited))
  }

  // return empty here as code of required variables is explicitly instantiated
  override def usedInputs: AttributeSet = AttributeSet.empty

  lazy val buildCodegenRDDs: Seq[RDD[InternalRow]] =
    buildPlan.asInstanceOf[CodegenSupport].inputRDDs()

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    // If the build side has shuffle dependencies.
    if (buildCodegenRDDs.length == 1 && buildCodegenRDDs(0).dependencies.size >= 1 &&
        buildCodegenRDDs(0).dependencies.exists(x => {
          x.isInstanceOf[ShuffleDependency[_, _, _]]
        })) {
      streamedPlan.asInstanceOf[CodegenSupport].inputRDDs().map(rdd => {
        new DelegateRDD[InternalRow](rdd.sparkContext, rdd,
          (rdd.dependencies ++ buildCodegenRDDs(0).dependencies.filter(
            _.isInstanceOf[ShuffleDependency[_, _, _]])))
      })
    } else {
      streamedPlan.asInstanceOf[CodegenSupport].inputRDDs()
    }
  }

  override def doProduce(ctx: CodegenContext): String = {
    val initMap = ctx.freshName("initMap")
    ctx.addMutableState("boolean", initMap, s"$initMap = false;")

    val createMap = ctx.freshName("createMap")
    val createMapClass = ctx.freshName("CreateMap")
    val getOrCreateMap = ctx.freshName("getOrCreateMap")

    // generate variable name for hash map for use here and in consume
    hashMapTerm = ctx.freshName("hashMap")
    val hashSetClassName = classOf[ObjectHashSet[_]].getName
    ctx.addMutableState(hashSetClassName, hashMapTerm, "")

    // using the expression IDs is enough to ensure uniqueness
    val buildCodeGen = buildPlan.asInstanceOf[CodegenSupport]
    val rdds = buildCodegenRDDs
    val exprIds = buildPlan.output.map(_.exprId.id).toArray
    val cacheKeyTerm = ctx.addReferenceObj("cacheKey",
      new CacheKey(exprIds, rdds.head.id))

    // generate local variables for HashMap data array and mask
    mapDataTerm = ctx.freshName("mapData")
    maskTerm = ctx.freshName("hashMapMask")
    keyIsUniqueTerm = ctx.freshName("keyIsUnique")
    numRowsTerm = ctx.freshName("numRows")

    // generate the map accessor to generate key/value class
    // and get map access methods
    val session = sqlContext.sparkSession.asInstanceOf[SnappySession]
    mapAccessor = ObjectHashMapAccessor(session, ctx, buildSideKeys,
      buildPlan.output, "LocalMap", hashMapTerm, mapDataTerm, maskTerm,
      multiMap = true, this, this.parent, buildPlan)

    val buildRDDs = ctx.addReferenceObj("buildRDDs", rdds.toArray,
      s"${classOf[RDD[_]].getName}[]")
    val buildParts = rdds.map(_.partitions)
    val partitionClass = classOf[Partition].getName
    val buildPartsVar = ctx.addReferenceObj("buildParts", buildParts.toArray,
      s"$partitionClass[][]")
    val allIterators = ctx.freshName("allIterators")
    val indexVar = ctx.freshName("index")
    val contextName = ctx.freshName("context")
    val taskContextClass = classOf[TaskContext].getName
    ctx.addMutableState(taskContextClass, contextName,
      s"this.$contextName = $taskContextClass.get();")


    // switch inputs to use the buildPlan RDD iterators
    ctx.addMutableState("scala.collection.Iterator[]", allIterators,
      s"""
         |$allIterators = inputs;
         |inputs = new scala.collection.Iterator[$buildRDDs.length];
         |$taskContextClass $contextName = $taskContextClass.get();
         |for (int $indexVar = 0; $indexVar < $buildRDDs.length; $indexVar++) {
         |  $partitionClass[] parts = $buildPartsVar[$indexVar];
         |  // check for replicate table
         |  if (parts.length == 1) {
         |    inputs[$indexVar] = $buildRDDs[$indexVar].iterator(
         |      parts[0], $contextName);
         |  } else {
         |    inputs[$indexVar] = $buildRDDs[$indexVar].iterator(
         |      parts[partitionIndex], $contextName);
         |  }
         |}
      """.stripMargin)

    val buildProduce = buildCodeGen.produce(ctx, mapAccessor)
    // switch inputs back to streamPlan iterators
    val numIterators = ctx.freshName("numIterators")
    ctx.addMutableState("int", numIterators, s"inputs = $allIterators;")

    val entryClass = mapAccessor.getClassName
    val numKeyColumns = buildSideKeys.length

    val buildSideCreateMap =
      s"""$hashSetClassName $hashMapTerm = new $hashSetClassName(128, 0.6,
      $numKeyColumns, scala.reflect.ClassTag$$.MODULE$$.apply(
        $entryClass.class));
      this.$hashMapTerm = $hashMapTerm;
      int $maskTerm = $hashMapTerm.mask();
      $entryClass[] $mapDataTerm = ($entryClass[])$hashMapTerm.data();
      $buildProduce"""

    if (replicatedTableJoin) {
      ctx.addNewFunction(getOrCreateMap,
        s"""
        public final void $createMap() throws java.io.IOException {
           $buildSideCreateMap
        }

        public final void $getOrCreateMap() throws java.io.IOException {
          $hashMapTerm = org.apache.spark.sql.execution.joins.HashedObjectCache
            .get($cacheKeyTerm, new $createMapClass(), $contextName, 1,
             scala.reflect.ClassTag$$.MODULE$$.apply($entryClass.class));
        }

        public final class $createMapClass implements java.util.concurrent.Callable {

          public Object call() throws java.io.IOException {
            $createMap();
            return $hashMapTerm;
          }
        }
      """)
    } else {
      ctx.addNewFunction(getOrCreateMap,
        s"""
          public final void $getOrCreateMap() throws java.io.IOException {
            $buildSideCreateMap
          }
      """)
    }

    // clear the parent by reflection if plan is sent by operators like Sort
    TypeUtilities.parentSetter.invoke(buildPlan, null)

    // The child could change `copyResult` to true, but we had already
    // consumed all the rows, so `copyResult` should be reset to `false`.
    ctx.copyResult = false

    // check for possible optimized dictionary code path
    val dictInit = if (DictionaryOptimizedMapAccessor.canHaveSingleKeyCase(
      streamSideKeys)) {
      // this array will be used at batch level for grouping if possible
      dictionaryArrayTerm = ctx.freshName("dictionaryArray")
      s"$entryClass[] $dictionaryArrayTerm = null;"
    } else ""

    val buildTime = metricTerm(ctx, "buildTime")
    val numOutputRows = metricTerm(ctx, "numOutputRows")
    // initialization of min/max for integral keys
    val initMinMaxVars = mapAccessor.integralKeys.zipWithIndex.map {
      case (indexKey, index) =>
        val minVar = mapAccessor.integralKeysMinVars(index)
        val maxVar = mapAccessor.integralKeysMaxVars(index)
        s"""
          final long $minVar = $hashMapTerm.getMinValue($indexKey);
          final long $maxVar = $hashMapTerm.getMaxValue($indexKey);
        """
    }.mkString("\n")

    val produced = streamedPlan.asInstanceOf[CodegenSupport].produce(ctx, this)

    val beforeMap = ctx.freshName("beforeMap")

    s"""
      boolean $keyIsUniqueTerm = true;
      if (!$initMap) {
        final long $beforeMap = System.nanoTime();
        $getOrCreateMap();
        $buildTime.${metricAdd(s"(System.nanoTime() - $beforeMap) / 1000000")};
        $initMap = true;
      }
      $keyIsUniqueTerm = $hashMapTerm.keyIsUnique();
      $initMinMaxVars
      final int $maskTerm = $hashMapTerm.mask();
      final $entryClass[] $mapDataTerm = ($entryClass[])$hashMapTerm.data();
      $dictInit
      long $numRowsTerm = 0L;
      try {
        ${session.evaluateFinallyCode(ctx, produced)}
      } finally {
        $numOutputRows.${metricAdd(numRowsTerm)};
      }
    """

  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode],
      row: ExprCode): String = {
    val evaluatedInputVars = evaluateVariables(input)
    // variable that holds if relation is unique to optimize iteration
    val entryVar = ctx.freshName("entry")
    val localValueVar = ctx.freshName("value")
    val checkNullObj = joinType match {
      case LeftOuter | RightOuter | FullOuter => true
      case _ => false
    }
    val (initCode, keyValueVars, nullMaskVars) = mapAccessor.getColumnVars(
      entryVar, localValueVar, onlyKeyVars = false, onlyValueVars = false,
      checkNullObj)
    val buildKeyVars = keyValueVars.take(buildSideKeys.length)
    val buildVars = keyValueVars.drop(buildSideKeys.length)
    val checkCondition = getJoinCondition(ctx, input, buildVars)

    ctx.INPUT_ROW = null
    ctx.currentVars = input
    val (resultVars, streamKeys) = buildSide match {
      case BuildLeft => (buildVars ++ input,
          streamSideKeys.map(BindReferences.bindReference(_, right.output)))
      case BuildRight => (input ++ buildVars,
          streamSideKeys.map(BindReferences.bindReference(_, left.output)))
    }
    val streamKeyVars = ctx.generateExpressions(streamKeys)

    val mapAccesCode = mapAccessor.generateMapLookup(entryVar, localValueVar, keyIsUniqueTerm,
      numRowsTerm, nullMaskVars, initCode, checkCondition,
      streamSideKeys, streamKeyVars, buildKeyVars, buildVars, input,
      resultVars, dictionaryArrayTerm, joinType)
    s"""
       $evaluatedInputVars
       $mapAccesCode
     """
  }

  override def canConsume(plan: SparkPlan): Boolean = {
    // check the outputs of the plan
    val planOutput = plan.output
    // linear search is enough instead of map create/lookup like in intersect
    streamedPlan.output.forall(a => planOutput.exists(_.semanticEquals(a)))
  }

  override def batchConsume(ctx: CodegenContext,
      plan: SparkPlan, input: Seq[ExprCode]): String = {
    // pluck out the variables from input as per the plan output
    val planOutput = plan.output
    val streamedOutput = streamedPlan.output
    val streamedInput = streamedOutput.map { a =>
      // we expect it to exist as per the check in canConsume
      input(planOutput.indexWhere(_.semanticEquals(a)))
    }
    // check for optimized dictionary code path
    mapAccessor.initDictionaryCodeForSingleKeyCase(
      dictionaryArrayTerm, streamedInput, streamSideKeys, streamedOutput)
  }

  /**
   * Generate the (non-equi) condition used to filter joined rows.
   * This is used in Inner joins.
   */
  private def getJoinCondition(ctx: CodegenContext,
      input: Seq[ExprCode],
      buildVars: Seq[ExprCode]): Option[ExprCode] = condition match {
    case Some(expr) =>
      // evaluate the variables from build side that used by condition
      val eval = evaluateRequiredVariables(buildPlan.output, buildVars,
        expr.references)
      // filter the output via condition
      ctx.currentVars = input ++ buildVars
      val ev = BindReferences.bindReference(expr,
        streamedPlan.output ++ buildPlan.output).genCode(ctx)
      Some(ev.copy(code =
          s"""
            $eval
            ${ev.code}
          """))
    case None => None
  }
}

private[spark] final class CacheKey(private var exprIds: Array[Long],
    private var rddId: Int) extends Serializable with KryoSerializable {

  private[this] var hash: Int = {
    var h = 0
    val numIds = exprIds.length
    var i = 0
    while (i < numIds) {
      val id = exprIds(i)
      h = (h ^ 0x9e3779b9) + (id ^ (id >>> 32)).toInt + (h << 6) + (h >>> 2)
      i += 1
    }
    (h ^ 0x9e3779b9) + rddId + (h << 6) + (h >>> 2)
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    val numIds = exprIds.length
    output.writeVarInt(numIds, true)
    var i = 0
    while (i < numIds) {
      output.writeLong(exprIds(i))
      i += 1
    }
    output.writeInt(rddId)
    output.writeInt(hash)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    val numIds = input.readVarInt(true)
    val exprIds = new Array[Long](numIds)
    var i = 0
    while (i < numIds) {
      exprIds(i) = input.readLong()
      i += 1
    }
    this.exprIds = exprIds
    rddId = input.readInt()
    hash = input.readInt()
  }

  // noinspection HashCodeUsesVar
  override def hashCode(): Int = hash

  override def equals(obj: Any): Boolean = obj match {
    case other: CacheKey =>
      val exprIds = this.exprIds
      val otherExprIds = other.exprIds
      val numIds = exprIds.length
      if (rddId == other.rddId && numIds == otherExprIds.length) {
        var i = 0
        while (i < numIds) {
          if (exprIds(i) != otherExprIds(i)) return false
          i += 1
        }
        true
      } else false
    case _ => false
  }
}

object HashedObjectCache {

  private[this] val mapCache = CacheBuilder.newBuilder()
      .maximumSize(50)
      .build[CacheKey, (ObjectHashSet[_], AtomicInteger)]()

  @throws(classOf[IOException])
  def get[T <: AnyRef](key: CacheKey,
      callable: Callable[ObjectHashSet[T]], context: TaskContext,
      tries: Int)(tag: ClassTag[T]): ObjectHashSet[T] = {
    try {
      val cached = mapCache.get(key,
        new Callable[(ObjectHashSet[_], AtomicInteger)] {
          override def call(): (ObjectHashSet[_], AtomicInteger) = {
            (callable.call(), new AtomicInteger(0))
          }
        })
      // Increment reference and add reference removal at the end of this task.
      val counter = cached._2
      counter.incrementAndGet()
      // Do full removal if reference count goes down to zero. If any later
      // task requires it again after full removal, then it will create again.
      context.addTaskCompletionListener { _ =>
        if (counter.get() > 0 && counter.decrementAndGet() <= 0) {
          mapCache.invalidate(key)
        }
      }
      cached._1.asInstanceOf[ObjectHashSet[T]]
    } catch {
      case e: ExecutionException =>
        // in case of OOME from MemoryManager, try after clearing the cache
        val cause = e.getCause
        cause match {
          case _: OutOfMemoryError =>
            if (tries <= 10 && mapCache.size() > 0) {
              mapCache.invalidateAll()
              get(key, callable, context, tries + 1)(tag)
            } else {
              throw new IOException(cause.getMessage, cause)
            }
          case _ => throw new IOException(cause.getMessage, cause)
        }
      case e: Exception => throw new IOException(e.getMessage, e)
    }
  }

  def close(): Unit = {
    mapCache.invalidateAll()
  }
}
