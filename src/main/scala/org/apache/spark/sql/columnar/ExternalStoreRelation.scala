package org.apache.spark.sql.columnar

import org.apache.spark.sql.collection.UUIDRegionKey

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.Accumulable
import org.apache.spark.rdd.{RDD, UnionRDD}
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Statistics}
import org.apache.spark.sql.columnar.InMemoryAppendableRelation.CachedBatchHolder
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.row.JDBCUpdatableSource
import org.apache.spark.sql.store.ExternalStore
import org.apache.spark.sql.store.impl.JDBCSourceAsStore
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel

private[sql] final class ExternalStoreRelation(
    override val output: Seq[Attribute],
    override val useCompression: Boolean,
    override val batchSize: Int,
    override val storageLevel: StorageLevel,
    override val child: SparkPlan,
    override val tableName: Option[String],
    val isSampledTable: Boolean,
    val jdbcSource: Map[String, String])(
    private var _ccb: RDD[CachedBatch] = null,
    private var _stats: Statistics = null,
    private var _bstats: Accumulable[ArrayBuffer[Row], Row] = null,
    private var _uuidList: ArrayBuffer[RDD[UUIDRegionKey]]
     = new ArrayBuffer[RDD[UUIDRegionKey]]())
    extends InMemoryAppendableRelation(
     output, useCompression, batchSize, storageLevel, child, tableName,
     isSampledTable)(_ccb: RDD[CachedBatch],
        _stats: Statistics,
        _bstats: Accumulable[ArrayBuffer[Row], Row]) {

  private lazy val externalStore: ExternalStore = {
    new JDBCSourceAsStore(jdbcSource)
  }

  override def appendBatch(batch: RDD[CachedBatch]) = writeLock {
    throw new IllegalStateException(
      s"did not expect appendBatch of ExternalStoreRelation to be called")
  }

  def appendUUIDBatch(batch: RDD[UUIDRegionKey]) = writeLock {
    _uuidList += batch
  }

  override def truncate() = writeLock {
    for (batch <- _uuidList) {
      // TODO: Go to GemXD and remove
    }
    _uuidList.clear()
  }

  override def recache(): Unit = {
    sys.error(
      s"ExternalStoreRelation: unexpected call to recache for $tableName")
  }

  override def withOutput(newOutput: Seq[Attribute]) = {
    new ExternalStoreRelation(newOutput, useCompression, batchSize,
      storageLevel, child, tableName, isSampledTable, jdbcSource)(
          cachedColumnBuffers, super.statisticsToBePropagated, batchStats, _uuidList)
  }

  override def children: Seq[LogicalPlan] = Seq.empty

  override def newInstance(): this.type = {
    new ExternalStoreRelation(
      output.map(_.newInstance()),
      useCompression,
      batchSize,
      storageLevel,
      child,
      tableName,
      isSampledTable,
      jdbcSource)(cachedColumnBuffers, super.statisticsToBePropagated,
          batchStats, _uuidList).asInstanceOf[this.type]
  }

  private def getCachedBatchIteratorFromUUIDItr(itr: Iterator[UUIDRegionKey]) = {
    externalStore.getCachedBatchIterator(tableName.get, itr, getAll = false)
  }

  // If the cached column buffers were not passed in, we calculate them
  // in the constructor. As in Spark, the actual work of caching is lazy.
  if (super.getInMemoryRelationCachedColumnBuffers != null) writeLock {
    if (_uuidList.isEmpty) _uuidList += super.getInMemoryRelationCachedColumnBuffers mapPartitions ( { cachedIter =>
      new Iterator[UUIDRegionKey] {
        override def hasNext: Boolean = {
          cachedIter.hasNext
        }

        override def next() = {
          externalStore.storeCachedBatch(cachedIter.next(), tableName.get)
        }
      }
    })
  }


  // TODO: Check if this is correct
  override def cachedColumnBuffers: RDD[CachedBatch] = readLock {
    var rddList = new ArrayBuffer[RDD[CachedBatch]]()
      _uuidList.foreach(x => {
        val y = x.mapPartitions { uuidItr =>
          getCachedBatchIteratorFromUUIDItr(uuidItr)
        }
        rddList += y
      })
    new UnionRDD[CachedBatch](this.child.sqlContext.sparkContext, rddList)
  }

  // TODO: Do this later...understand whats the need of this function
  //  override protected def otherCopyArgs: Seq[AnyRef] =
  //    Seq(super.cachedColumnBuffers, super.statisticsToBePropagated,
  //      batchStats, _cachedBufferList)

  override private[sql] def uncache(blocking: Boolean): Unit = {
    super.uncache(blocking)
    writeLock {
      // TODO: Go to GemXD and truncate or drop
    }
  }

  def getUUIDList = {
    _uuidList
  }

  def uuidBatchAggregate(accumulated: ArrayBuffer[UUIDRegionKey],
      batch: CachedBatch): ArrayBuffer[UUIDRegionKey] = {
    val uuid = externalStore.storeCachedBatch(batch,
      tableName.getOrElse(throw new IllegalStateException("missing tableName")))
    accumulated += uuid
  }
}

private[sql] object ExternalStoreRelation {

  def apply(useCompression: Boolean,
      batchSize: Int,
      storageLevel: StorageLevel,
      child: SparkPlan,
      tableName: Option[String],
      isSampledTable: Boolean,
      jdbcSource: Map[String, String]): ExternalStoreRelation =
    new ExternalStoreRelation(child.output, useCompression, batchSize,
      storageLevel, child, tableName, isSampledTable, jdbcSource)()

  def apply(useCompression: Boolean,
      batchSize: Int,
      tableName: String,
      schema: StructType,
      relation: InMemoryRelation,
      output: Seq[Attribute]): CachedBatchHolder[ArrayBuffer[Serializable]] = {
    def columnBuilders = output.map { attribute =>
      val columnType = ColumnType(attribute.dataType)
      val initialBufferSize = columnType.defaultSize * batchSize
      ColumnBuilder(attribute.dataType, initialBufferSize,
        attribute.name, useCompression)
    }.toArray

    val holder = relation match {
      case esr: ExternalStoreRelation =>
        new CachedBatchHolder(columnBuilders, 0, batchSize, schema,
          new ArrayBuffer[UUIDRegionKey](1), esr.uuidBatchAggregate)
      case imar: InMemoryAppendableRelation =>
        new CachedBatchHolder(columnBuilders, 0, batchSize, schema,
          new ArrayBuffer[CachedBatch](1), imar.batchAggregate)
      case _ => throw new IllegalStateException("ExternalStoreRelation:" +
          s" unknown relation $relation for table $tableName")
    }
    holder.asInstanceOf[CachedBatchHolder[ArrayBuffer[Serializable]]]
  }
}

private[sql] class ExternalStoreTableScan(
    override val attributes: Seq[Attribute],
    override val predicates: Seq[Expression],
    override val relation: InMemoryAppendableRelation)
    extends InMemoryAppendableColumnarTableScan(attributes, predicates,
      relation) {
}

private[sql] object ExternalStoreTableScan {
  def apply(attributes: Seq[Attribute], predicates: Seq[Expression],
      relation: InMemoryAppendableRelation): SparkPlan = {
    new ExternalStoreTableScan(attributes, predicates, relation)
  }
}
