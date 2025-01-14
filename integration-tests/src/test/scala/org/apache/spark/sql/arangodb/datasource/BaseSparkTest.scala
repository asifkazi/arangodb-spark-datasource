package org.apache.spark.sql.arangodb.datasource

import com.arangodb.entity.{License, ServerRole}
import com.arangodb.model.CollectionCreateOptions
import com.arangodb.serde.jackson.JacksonSerde
import com.arangodb.spark.DefaultSource
import com.arangodb.{ArangoDB, ArangoDBException, ArangoDatabase}
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{JsonSerializer, ObjectMapper, SerializerProvider}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.spark.sql.arangodb.commons.ArangoDBConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.provider.Arguments

import java.sql.Date
import java.time.LocalDate
import java.util
import java.util.function.Consumer
import java.util.stream
import scala.collection.JavaConverters.asJavaIterableConverter

class BaseSparkTest {

  protected val arangoDB: ArangoDB = BaseSparkTest.arangoDB
  protected val db: ArangoDatabase = BaseSparkTest.db

  protected val spark: SparkSession = BaseSparkTest.spark
  protected val options: Map[String, String] = BaseSparkTest.options
  protected val usersDF: DataFrame = BaseSparkTest.usersDF

  def isSingle: Boolean = BaseSparkTest.isSingle

  def isCluster: Boolean = BaseSparkTest.isCluster

  def isEnterprise: Boolean = BaseSparkTest.isEnterprise
}

object BaseSparkTest {

  def provideProtocolAndContentType(): stream.Stream[Arguments] = java.util.stream.Stream.of(
    Arguments.of("vst", "vpack"),
    Arguments.of("http", "vpack"),
    Arguments.of("http", "json")
  )

  val arangoDatasource: String = classOf[DefaultSource].getName
  private val database = "sparkConnectorTest"
  private val user = "sparkUser"
  private val password = "sparkTest"
  private val rootUser = "root"
  private val rootPassword = "test"
  val endpoints = "172.28.0.1:8529,172.28.0.1:8539,172.28.0.1:8549"
  private val singleEndpoint = endpoints.split(',').head
  private val arangoDB: ArangoDB = {
    val serde = JacksonSerde.of(com.arangodb.ContentType.JSON)
    //noinspection ConvertExpressionToSAM
    serde.configure(new Consumer[ObjectMapper] {
      override def accept(mapper: ObjectMapper): Unit = mapper
        .registerModule(DefaultScalaModule)
        .registerModule(new SimpleModule()
          .addSerializer(classOf[Date], new JsonSerializer[Date] {
            override def serialize(value: Date, gen: JsonGenerator, serializers: SerializerProvider): Unit =
              gen.writeString(value.toString)
          })
          .addSerializer(classOf[LocalDate], new JsonSerializer[LocalDate] {
            override def serialize(value: LocalDate, gen: JsonGenerator, serializers: SerializerProvider): Unit =
              gen.writeString(value.toString)
          })
        )
        .configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    })

    new ArangoDB.Builder()
      .user(rootUser)
      .password(rootPassword)
      .host(singleEndpoint.split(':').head, singleEndpoint.split(':')(1).toInt)
      .serde(serde)
      .build()
  }
  val db: ArangoDatabase = arangoDB.db(database)
  val isSingle: Boolean = arangoDB.getRole == ServerRole.SINGLE
  val isCluster: Boolean = !isSingle
  val isEnterprise: Boolean = arangoDB.getVersion.getLicense == License.ENTERPRISE
  private val options = Map(
    "database" -> database,
    "user" -> user,
    "password" -> password,
    "endpoints" -> {
      if (isSingle) {
        singleEndpoint
      } else {
        endpoints
      }
    }
  )

  val spark: SparkSession = SparkSession.builder()
    .appName("ArangoDBSparkTest")
    .master("local[*]")
    .config("spark.driver.host", "127.0.0.1")
    .getOrCreate()

  val usersSchema = new StructType(
    Array(
      StructField("likes", ArrayType(StringType, containsNull = false)),
      StructField("birthday", DateType, nullable = true),
      StructField("gender", StringType, nullable = false),
      StructField("name", StructType(
        Array(
          StructField("first", StringType, nullable = true),
          StructField("last", StringType, nullable = false)
        )
      ), nullable = true)
    )
  )

  private lazy val usersDF: DataFrame = createDF("users",
    Seq(
      Map(
        "name" -> Map(
          "first" -> "Prudence",
          "last" -> "Litalien"
        ),
        "gender" -> "female",
        "birthday" -> "1944-06-19",
        "likes" -> Seq(
          "swimming",
          "chess"
        )
      ),
      Map(
        "name" -> Map(
          "first" -> "Ernie",
          "last" -> "Levinson"
        ),
        "gender" -> "male",
        "birthday" -> "1955-07-25",
        "likes" -> Seq()
      ),
      Map(
        "name" -> Map(
          "first" -> "Malinda",
          "last" -> "Siemon"
        ),
        "gender" -> "female",
        "birthday" -> "1993-04-10",
        "likes" -> Seq(
          "climbing"
        )
      )
    ),
    usersSchema
  )

  def createDF(name: String, docs: Iterable[Any], schema: StructType, additionalOptions: Map[String, String] = Map.empty): DataFrame = {
    val col = db.collection(name)
    if (col.exists()) {
      col.truncate()
    } else {
      db.createCollection(name, new CollectionCreateOptions().numberOfShards(6))
    }
    col.insertDocuments(docs.asJava.asInstanceOf[util.Collection[Any]])

    val df = spark.read
      .format(arangoDatasource)
      .options(options ++ additionalOptions + (ArangoDBConf.COLLECTION -> name))
      .schema(schema)
      .load()
    df.createOrReplaceTempView(name)
    df
  }

  def createQueryDF(query: String, schema: StructType, additionalOptions: Map[String, String] = Map.empty): DataFrame =
    spark.read
      .format(arangoDatasource)
      .options(options ++ additionalOptions + (ArangoDBConf.QUERY -> query))
      .schema(schema)
      .load()

  def dropTable(name: String): Unit = {
    db.collection(name).drop()
  }

  @BeforeAll
  def beforeAll(): Unit = {
    try {
      arangoDB.getUser(user)
    } catch {
      case e: ArangoDBException =>
        if (e.getResponseCode.toInt == 404 && e.getErrorNum.toInt == 1703)
          arangoDB.createUser(user, password)
        else throw e
    }
    if (!db.exists()) {
      db.create()
    }
    db.grantAccess(user)
  }
}
