package org.apache.spark.sql.arangodb.commons

import com.arangodb.entity.ErrorEntity
import com.arangodb.internal.{ArangoRequestParam, ArangoResponseField}
import com.arangodb.internal.util.ArangoSerializationFactory.Serializer
import com.arangodb.mapping.ArangoJack
import com.arangodb.model.{AqlQueryOptions, CollectionCreateOptions, OverwriteMode}
import com.arangodb.velocypack.VPackSlice
import com.arangodb.velocystream.{Request, RequestType}
import com.arangodb.{ArangoCursor, ArangoDB, ArangoDBException}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.spark.internal.Logging
import org.apache.spark.sql.arangodb.commons.exceptions.ArangoDBMultiException
import org.apache.spark.sql.arangodb.commons.utils.PushDownCtx

import java.util
import scala.collection.JavaConverters.{asScalaIteratorConverter, mapAsJavaMapConverter}

class ArangoClient(options: ArangoOptions) extends Logging {

  private def aqlOptions(): AqlQueryOptions = {
    val opt = new AqlQueryOptions().stream(options.readOptions.stream)
    options.readOptions.fillBlockCache.foreach(opt.fillBlockCache(_))
    options.readOptions.batchSize.foreach(opt.batchSize(_))
    opt
  }

  lazy val arangoDB: ArangoDB = options.driverOptions
    .builder()
    .serializer(new ArangoJack() {
      //noinspection ConvertExpressionToSAM
      configure(new ArangoJack.ConfigureFunction {
        override def configure(mapper: ObjectMapper): Unit = mapper.registerModule(DefaultScalaModule)
      })
    })
    .build()

  def shutdown(): Unit = arangoDB.shutdown()

  def readCollectionPartition(shardId: String, ctx: PushDownCtx): ArangoCursor[VPackSlice] = {
    val query =
      s"""
         |FOR d IN @@col
         |${PushdownUtils.generateFilterClause(ctx.filters)}
         |RETURN ${PushdownUtils.generateColumnsFilter(ctx.requiredSchema, "d")}"""
        .stripMargin
        .replaceAll("\n", " ")
    val params = Map[String, AnyRef]("@col" -> options.readOptions.collection.get)
    val opts = aqlOptions().shardIds(shardId)
    logDebug(s"""Executing AQL query: \n\t$query ${if (params.nonEmpty) s"\n\t with params: $params" else ""}""")
    arangoDB
      .db(options.readOptions.db)
      .query(query, params.asJava, opts, classOf[VPackSlice])
  }

  def readQuery(): ArangoCursor[VPackSlice] = {
    val query = options.readOptions.query.get
    logDebug(s"Executing AQL query: \n\t$query")
    arangoDB
      .db(options.readOptions.db)
      .query(
        query,
        aqlOptions(),
        classOf[VPackSlice])
  }

  def readCollectionSample(): util.List[String] = {
    val query = "FOR d IN @@col LIMIT @size RETURN d"
    val params = Map(
      "@col" -> options.readOptions.collection.get,
      "size" -> options.readOptions.sampleSize
    )
      .asInstanceOf[Map[String, AnyRef]]
    val opts = aqlOptions()
    logDebug(s"""Executing AQL query: \n\t$query ${if (params.nonEmpty) s"\n\t with params: $params" else ""}""")
    arangoDB
      .db(options.readOptions.db)
      .query(query, params.asJava, opts, classOf[String])
      .asListRemaining()
  }

  def readQuerySample(): util.List[String] = {
    val query = options.readOptions.query.get
    logDebug(s"Executing AQL query: \n\t$query")
    arangoDB
      .db(options.readOptions.db)
      .query(
        query,
        aqlOptions(),
        classOf[String])
      .asListRemaining()
  }

  def collectionExists(): Boolean = arangoDB
    .db(options.writeOptions.db)
    .collection(options.writeOptions.collection)
    .exists()

  def createCollection(): Unit = {
    val opts = new CollectionCreateOptions()
    options.writeOptions.numberOfShards.foreach(opts.numberOfShards(_))
    options.writeOptions.collectionType.foreach(ct => opts.`type`(ct.get()))

    arangoDB
      .db(options.writeOptions.db)
      .collection(options.writeOptions.collection)
      .create(opts)
  }

  def truncate(): Unit = arangoDB
    .db(options.writeOptions.db)
    .collection(options.writeOptions.collection)
    .truncate()

  def drop(): Unit = arangoDB
    .db(options.writeOptions.db)
    .collection(options.writeOptions.collection)
    .drop()

  def saveDocuments(data: VPackSlice): Unit = {
    val request = new Request(
      options.writeOptions.db,
      RequestType.POST,
      s"/_api/document/${options.writeOptions.collection}")

    request.putQueryParam("silent", true)
    options.writeOptions.waitForSync.foreach(request.putQueryParam("waitForSync", _))
    options.writeOptions.overwriteMode.foreach(it => {
      request.putQueryParam("overwriteMode", it)
      if (it == OverwriteMode.update) {
        request.putQueryParam("keepNull", options.writeOptions.keepNull)
        options.writeOptions.mergeObjects.foreach(request.putQueryParam("mergeObjects", _))
      }
    })

    request.setBody(data)
    val response = arangoDB.execute(request)

    // FIXME
    // in case there are no errors, response body is an empty object
    // In cluster 3.8.1 this is not true due to: https://arangodb.atlassian.net/browse/BTS-592
    if (response.getBody.isArray) {
      val errors = response.getBody.arrayIterator.asScala
        .filter(it => it.get(ArangoResponseField.ERROR).isTrue)
        .map(arangoDB.util().deserialize(_, classOf[ErrorEntity]).asInstanceOf[ErrorEntity])
        .toIterable
      if (errors.nonEmpty) {
        throw new ArangoDBMultiException(errors)
      }
    }
  }
}


object ArangoClient {

  def apply(options: ArangoOptions): ArangoClient = new ArangoClient(options)

  def getCollectionShardIds(options: ArangoOptions): Array[String] = {
    try {
      val client = ArangoClient(options).arangoDB
      val res = client.execute(new Request(
        options.readOptions.db,
        RequestType.GET,
        s"/_api/collection/${options.readOptions.collection.get}/shards"))
      val shardIds: Array[String] = client.util().deserialize(res.getBody.get("shards"), classOf[Array[String]])
      client.shutdown()
      shardIds
    } catch {
      case e: ArangoDBException =>
        // single server < 3.8  returns Response: 500, Error: 4 - internal error
        // single server >= 3.8 returns Response: 501, Error: 9 - shards API is only available in a cluster
        if (e.getErrorNum == 9 || e.getErrorNum == 4) Array("")
        else throw e
    }
  }

  def acquireHostList(options: ArangoOptions): Iterable[String] = {
    val client = ArangoClient(options).arangoDB
    val response = client.execute(new Request(ArangoRequestParam.SYSTEM, RequestType.GET, "/_api/cluster/endpoints"))
    val field = response.getBody.get("endpoints")
    val res = client.util(Serializer.CUSTOM)
      .deserialize(field, classOf[Seq[Map[String, String]]])
      .asInstanceOf[Seq[Map[String, String]]]
      .map(it => it("endpoint").replaceFirst(".*://", ""))
    client.shutdown()
    res
  }

}