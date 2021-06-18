import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types._
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test}

import java.sql.{Date, Timestamp}
import scala.collection.JavaConverters._

class EqualToTest extends BaseSparkTest {
  private val df = EqualToTest.df

  @Test
  def bool(): Unit = {
    val fieldName = "bool"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def double(): Unit = {
    val fieldName = "double"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def float(): Unit = {
    val fieldName = "float"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def integer(): Unit = {
    val fieldName = "integer"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def long(): Unit = {
    val fieldName = "long"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def date(): Unit = {
    val fieldName = "date"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def timestampString(): Unit = {
    val fieldName = "timestampString"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def timestampMillis(): Unit = {
    val fieldName = "timestampMillis"
    val value = Timestamp.valueOf("2021-01-01 01:01:01.111")
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def short(): Unit = {
    val fieldName = "short"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

  @Test
  def string(): Unit = {
    val fieldName = "string"
    val value = EqualToTest.data.head(fieldName)
    val res = df.filter(col(fieldName).equalTo(value)).collect()
      .map(_.getValuesMap[Any](EqualToTest.schema.fieldNames))
    assertThat(res).hasSize(1)
    assertThat(res.head(fieldName)).isEqualTo(value)
  }

}

object EqualToTest {
  private var df: DataFrame = _
  private val data: Seq[Map[String, Any]] = Seq(
    Map(
      "bool" -> false,
      "double" -> 1.1,
      "float" -> 0.09375f,
      "integer" -> 1,
      "long" -> 1L,
      "date" -> Date.valueOf("2021-01-01"),
      "timestampString" -> Timestamp.valueOf("2021-01-01 01:01:01.111"),
      "timestampMillis" -> Timestamp.valueOf("2021-01-01 01:01:01.111").getTime,
      "short" -> 1.toShort,
      "string" -> "one"
    ),
    Map(
      "bool" -> true,
      "double" -> 2.2,
      "float" -> 2.2f,
      "integer" -> 2,
      "long" -> 2L,
      "date" -> Date.valueOf("2022-02-02"),
      "timestampString" -> Timestamp.valueOf("2022-02-02 02:02:02.222"),
      "timestampMillis" -> Timestamp.valueOf("2022-02-02 02:02:02.222").getTime,
      "short" -> 2.toShort,
      "string" -> "two"
    )
  )

  private val schema = StructType(Array(
    // atomic types
    StructField("bool", BooleanType, nullable = false),
    StructField("double", DoubleType, nullable = false),
    StructField("float", FloatType, nullable = false),
    StructField("integer", IntegerType, nullable = false),
    StructField("long", LongType, nullable = false),
    StructField("date", DateType, nullable = false),
    StructField("timestampString", TimestampType, nullable = false),
    StructField("timestampMillis", TimestampType, nullable = false),
    StructField("short", ShortType, nullable = false),
    StructField("string", StringType, nullable = false),

    // TODO
    // complex types
    StructField("array", ArrayType(StringType)),
    StructField("null", NullType),
    StructField("struct", StructType(Array(
      StructField("a", StringType),
      StructField("b", IntegerType)
    )))
  ))

  @BeforeAll
  def init(): Unit = {
    df = BaseSparkTest.createDF("equalTo", data.asInstanceOf[Seq[Any]].asJava, schema)
  }

  @AfterAll
  def cleanup(): Unit = {
    BaseSparkTest.dropTable("equalTo")
  }
}