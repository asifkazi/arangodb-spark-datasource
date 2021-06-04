package org.apache.spark.sql.arangodb.commons

import org.apache.spark.sql.arangodb.commons.PushdownUtils.getStructField
import org.apache.spark.sql.sources.{And, EqualTo, Filter, Or}
import org.apache.spark.sql.types.{ArrayType, DateType, StructType, TimestampType}

sealed trait PushableFilter {
  def support(): FilterSupport

  def aql(documentVariable: String): String
}

object PushableFilter {
  def apply(filter: Filter, schema: StructType): PushableFilter = filter match {
    case and: And => new AndFilter(and, schema)
    case equalTo: EqualTo => new EqualToFilter(equalTo, schema)
    case _ => NotSupportedFilter
  }
}

object NotSupportedFilter extends PushableFilter {
  override def support(): FilterSupport = FilterSupport.NONE

  override def aql(documentVariable: String): String = ""
}

class OrFilter(or: Or, schema: StructType) extends PushableFilter {
  private val parts = Seq(
    PushableFilter(or.left, schema),
    PushableFilter(or.right, schema)
  )

  /**
   * +---------++---------+---------+------+
   * |   OR    ||  FULL   | PARTIAL | NONE |
   * +---------++---------+---------+------+
   * | FULL    || FULL    | PARTIAL | NONE |
   * | PARTIAL || PARTIAL | PARTIAL | NONE |
   * | NONE    || NONE    | NONE    | NONE |
   * +---------++---------+---------+------+
   */
  override def support(): FilterSupport =
    if (parts.exists(_.support == FilterSupport.NONE)) FilterSupport.NONE
    else if (parts.forall(_.support == FilterSupport.FULL)) FilterSupport.FULL
    else FilterSupport.PARTIAL

  override def aql(v: String): String = s"(${parts(0).aql(v)} OR ${parts(1).aql(v)})"
}

class AndFilter(and: And, schema: StructType) extends PushableFilter {
  private val parts = Seq(
    PushableFilter(and.left, schema),
    PushableFilter(and.right, schema)
  )

  /**
   * +---------++---------+---------+---------+
   * |   AND   ||  FULL   | PARTIAL |  NONE   |
   * +---------++---------+---------+---------+
   * | FULL    || FULL    | PARTIAL | PARTIAL |
   * | PARTIAL || PARTIAL | PARTIAL | PARTIAL |
   * | NONE    || PARTIAL | PARTIAL | NONE    |
   * +---------++---------+---------+---------+
   */
  override def support(): FilterSupport =
    if (parts.forall(_.support == FilterSupport.NONE)) FilterSupport.NONE
    else if (parts.forall(_.support == FilterSupport.FULL)) FilterSupport.FULL
    else FilterSupport.PARTIAL

  override def aql(v: String): String = s"(${parts(0).aql(v)} AND ${parts(1).aql(v)})"
}

class EqualToFilter(filter: EqualTo, schema: StructType) extends PushableFilter {

  private val fieldNameParts = splitAttributeNameParts(filter.attribute)
  private val schemaField = getStructField(fieldNameParts.tail, schema(fieldNameParts.head))
  private val escapedFieldNameParts = fieldNameParts.map(v => s"`$v`").mkString(".")

  override def support(): FilterSupport = schemaField.dataType match {
    case _: ArrayType => FilterSupport.NONE
    case _ => FilterSupport.FULL
  }

  override def aql(v: String): String =
    schemaField.dataType match {
      case _: DateType => s"""DATE_COMPARE(`$v`.$escapedFieldNameParts, "${filter.value}", "years", "days")"""
      case _: TimestampType => s"""DATE_COMPARE(`$v`.$escapedFieldNameParts, "${filter.value}", "years", "milliseconds")"""
      case _ => s"""`$v`.$escapedFieldNameParts == "${filter.value}""""
    }
}

sealed trait FilterSupport

object FilterSupport {

  /**
   * the filter can be applied and does not need to be evaluated again after scanning
   */
  case object FULL extends FilterSupport

  /**
   * the filter can be partially applied and it needs to be evaluated again after scanning
   */
  case object PARTIAL extends FilterSupport

  /**
   * the filter cannot be applied and it needs to be evaluated again after scanning
   */
  case object NONE extends FilterSupport
}
