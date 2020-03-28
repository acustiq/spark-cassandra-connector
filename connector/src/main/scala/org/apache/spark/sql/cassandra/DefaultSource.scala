package org.apache.spark.sql.cassandra

import com.datastax.spark.connector.TableRef
import org.apache.spark.sql.SaveMode._
import org.apache.spark.sql.cassandra.DefaultSource._
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, RelationProvider, SchemaRelationProvider, StreamSinkProvider}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}
import com.datastax.spark.connector.util.Logging
import org.apache.spark.sql.execution.streaming.Sink
import org.apache.spark.sql.streaming.OutputMode

/**
 * Cassandra data source extends [[RelationProvider]], [[SchemaRelationProvider]] and [[CreatableRelationProvider]].
 * It's used internally by Spark SQL to create Relation for a table which specifies the Cassandra data source
 * e.g.
 *
 *      CREATE TEMPORARY TABLE tmpTable
 *      USING org.apache.spark.sql.cassandra
 *      OPTIONS (
 *       table "table",
 *       keyspace "keyspace",
 *       cluster "test_cluster",
 *       pushdown "true",
 *       spark.cassandra.input.fetch.sizeInRows "10",
 *       spark.cassandra.output.consistency.level "ONE",
 *       spark.cassandra.connection.timeoutMS "1000"
 *      )
 */
class DefaultSource
  extends RelationProvider
  with SchemaRelationProvider
  with CreatableRelationProvider
  with StreamSinkProvider
  with Logging {

  /**
   * Creates a new relation for a cassandra table.
   * The parameters map stores table level data. User can specify vale for following keys
   *
   *    table        -- table name, required
   *    keyspace     -- keyspace name, required
   *    cluster      -- cluster name, optional, default name is "default"
   *    pushdown     -- true/false, optional, default is true
   *    Cassandra connection settings  -- optional, e.g. spark.cassandra.connection.timeoutMS
   *    Cassandra Read Settings        -- optional, e.g. spark.cassandra.input.fetch.sizeInRows
   *    Cassandra Write settings       -- optional, e.g. spark.cassandra.output.consistency.level
   *
   * When push_down is true, some filters are pushed down to CQL.
   *
   */
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String]): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    CassandraSourceRelation(tableRef, sqlContext, options, None)
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster and push_down
   * as parameters and explicitly pass schema [[StructType]] as a parameter
   */
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    schema: StructType): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    CassandraSourceRelation(tableRef, sqlContext, options, Some(schema))
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster, push_down and schema
   * as parameters. It saves the data to the Cassandra table depends on [[SaveMode]]
   */
  override def createRelation(
    sqlContext: SQLContext,
    mode: SaveMode,
    parameters: Map[String, String],
    data: DataFrame): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    val table = CassandraSourceRelation(tableRef, sqlContext, options, None)

    mode match {
      case Append => table.insert(data, overwrite = false)
      case Overwrite => table.insert(data, overwrite = true)
      case ErrorIfExists =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        } else {
          throw new UnsupportedOperationException(
            s"""'SaveMode is set to ErrorIfExists and Table
               |${tableRef.keyspace + "." + tableRef.table} already exists and contains data.
               |Perhaps you meant to set the DataFrame write mode to Append?
               |Example: df.write.format.options.mode(SaveMode.Append).save()" '""".stripMargin)
        }
      case Ignore =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        }
    }

    CassandraSourceRelation(tableRef, sqlContext, options, None)
  }

  override def createSink(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    partitionColumns: Seq[String],
    outputMode: OutputMode): Sink = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    CassandraStreamingSinkRelation(tableRef, sqlContext, options, outputMode)
  }
}

/** Store data source options */
case class CassandraSourceOptions(pushdown: Boolean = true, confirmTruncate: Boolean = false, cassandraConfs: Map[String, String] = Map.empty)

object DefaultSource {
  val CassandraDataSourceTableNameProperty = "table"
  val CassandraDataSourceKeyspaceNameProperty = "keyspace"
  val CassandraDataSourceClusterNameProperty = "cluster"
  val CassandraDataSourceUserDefinedSchemaNameProperty = "schema"
  val CassandraDataSourcePushdownEnableProperty = "pushdown"
  val CassandraDataSourceConfirmTruncateProperty = "confirm.truncate"
  val CassandraDataSourceProviderPackageName = "org.apache.spark.sql.cassandra"
  val CassandraDataSourceProviderClassName = CassandraDataSourceProviderPackageName + ".DefaultSource"


  /** Parse parameters into CassandraDataSourceOptions and TableRef object */
  def TableRefAndOptions(parameters: Map[String, String]) : (TableRef, CassandraSourceOptions) = {
    val tableName = parameters(CassandraDataSourceTableNameProperty)
    val keyspaceName = parameters(CassandraDataSourceKeyspaceNameProperty)
    val clusterName = parameters.get(CassandraDataSourceClusterNameProperty)
    val pushdown : Boolean = parameters.getOrElse(CassandraDataSourcePushdownEnableProperty, "true").toBoolean
    val confirmTruncate : Boolean = parameters.getOrElse(CassandraDataSourceConfirmTruncateProperty, "false").toBoolean
    val cassandraConfs = parameters

    (TableRef(tableName, keyspaceName, clusterName), CassandraSourceOptions(pushdown, confirmTruncate, cassandraConfs))
  }

  /** Check whether the provider is Cassandra datasource or not */
  def cassandraSource(provider: String) : Boolean = {
    provider == CassandraDataSourceProviderPackageName || provider == CassandraDataSourceProviderClassName
  }
}

