package org.apache.spark.graph.cypher.io

import java.net.URI

import org.apache.hadoop.fs.FileSystem
import org.apache.spark.graph.api.{NodeFrame, RelationshipFrame}
import org.apache.spark.graph.cypher.SparkGraphDirectoryStructure
import org.apache.spark.graph.cypher.SparkGraphDirectoryStructure._
import org.apache.spark.graph.cypher.SparkTable.DataFrameTable
import org.apache.spark.graph.cypher.conversions.StringEncodingUtilities._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.opencypher.okapi.api.graph.{SourceEndNodeKey, SourceIdKey, SourceStartNodeKey}
import org.opencypher.okapi.api.types.{CTNode, CTRelationship}
import org.opencypher.okapi.ir.api.expr.{Property, Var}
import org.opencypher.okapi.relational.api.graph.RelationalCypherGraph

object ReadWriteGraph {

  case class GraphImporter(sparkSession: SparkSession, path: String, format: String) {

    val directoryStructure: SparkGraphDirectoryStructure = SparkGraphDirectoryStructure(path)
    val (labelCombos, relTypes): (Seq[Set[String]], Seq[String]) = {
      val fs = FileSystem.get(new URI(path), sparkSession.sparkContext.hadoopConfiguration)
      try {
        import org.apache.spark.graph.cypher.util.HadoopFSUtils._
        val combos = fs.listDirectories(directoryStructure.pathToNodeDirectory).map(_.toLabelCombo)
        val types = fs.listDirectories(directoryStructure.pathToRelationshipDirectory).map(_.toRelationshipType)
        combos -> types
      } finally {
        fs.close()
      }
    }

    def nodeFrames: Seq[NodeFrame] = {
      labelCombos.map { combo =>
        val df = sparkSession.read.format(format).load(directoryStructure.pathToNodeTable(combo))
        val propertyMappings = df.columns.collect {
          case colName if colName.isPropertyColumnName => colName.toProperty -> colName
        }.toMap
        NodeFrame(
          df,
          SourceIdKey.name,
          combo,
          propertyMappings)
      }
    }

    def relationshipFrames: Seq[RelationshipFrame] = {
      relTypes.map { relType =>
        val df = sparkSession.read.format(format).load(directoryStructure.pathToRelationshipTable(relType))
        val propertyMappings = df.columns.collect {
          case colName if colName.isPropertyColumnName => colName.toProperty -> colName
        }.toMap
        RelationshipFrame(
          df,
          SourceIdKey.name,
          SourceStartNodeKey.name,
          SourceEndNodeKey.name,
          relType,
          propertyMappings)
      }
    }

  }

  implicit class GraphExport(graph: RelationalCypherGraph[DataFrameTable]) {

    def canonicalNodeTable(labels: Set[String]): DataFrame = {
      val ct = CTNode(labels)
      val v = Var("n")(ct)
      val nodeRecords = graph.nodes(v.name, ct, exactLabelMatch = true)
      val header = nodeRecords.header

      val idRenaming = header.column(v) -> SourceIdKey.name
      val properties: Set[Property] = header.propertiesFor(v)
      val propertyRenames = properties.map { p => header.column(p) -> p.key.name.toPropertyColumnName }

      val selectColumns = (idRenaming :: propertyRenames.toList.sortBy { case (_, newName) => newName }).map {
        case (oldName, newName) => nodeRecords.table.df.col(oldName).as(newName)
      }

      nodeRecords.table.df.select(selectColumns: _*)
    }

    def canonicalRelationshipTable(relType: String): DataFrame = {
      val ct = CTRelationship(relType)
      val v = Var("r")(ct)
      val relRecords = graph.relationships(v.name, ct)
      val header = relRecords.header

      val idRenaming = header.column(v) -> SourceIdKey.name
      val sourceIdRenaming = header.column(header.startNodeFor(v)) -> SourceStartNodeKey.name
      val targetIdRenaming = header.column(header.endNodeFor(v)) -> SourceEndNodeKey.name
      val properties: Set[Property] = relRecords.header.propertiesFor(v)
      val propertyRenames = properties.map { p => relRecords.header.column(p) -> p.key.name.toPropertyColumnName }

      val selectColumns = (idRenaming :: sourceIdRenaming :: targetIdRenaming :: propertyRenames.toList.sortBy { case (_, newName) => newName }).map {
        case (oldName, newName) => relRecords.table.df.col(oldName).as(newName)
      }

      relRecords.table.df.select(selectColumns: _*)
    }

  }

}
