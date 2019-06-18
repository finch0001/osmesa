package osmesa.common

import com.vividsolutions.jts.geom.{Geometry, Point}
import org.apache.spark.sql.Column
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DoubleType, FloatType}
import org.locationtech.geomesa.spark.jts.{st_envelope, st_exteriorRing, st_geometryType, st_isEmpty, st_pointN,
st_x, st_y}
import osmesa.common.util._

package object functions {
  // A brief note about style
  // Spark functions are typically defined using snake_case, therefore so are the UDFs
  // internal helper functions use standard Scala naming conventions

  lazy val merge_counts: UserDefinedFunction = udf(_mergeCounts)

  lazy val sum_counts: UserDefinedFunction = udf { counts: Iterable[Map[String, Int]] =>
    counts.reduce(_mergeCounts(_, _))
  }

  // Convert BigDecimals to doubles
  // Reduces size taken for representation at the expense of some precision loss.
  def asDouble(value: Column): Column =
    when(value.isNotNull, value.cast(DoubleType))
      .otherwise(lit(Double.NaN)) as s"asDouble($value)"

  // Convert BigDecimals to floats
  // Reduces size taken for representation at the expense of more precision loss.
  def asFloat(value: Column): Column =
    when(value.isNotNull, value.cast(FloatType))
      .otherwise(lit(Float.NaN)) as s"asFloat($value)"

  val count_values: UserDefinedFunction = udf {
    (_: Seq[String]).groupBy(identity).mapValues(_.size)
  }

  val flatten: UserDefinedFunction = udf {
    (_: Seq[Seq[String]]).flatten
  }

  val flatten_set: UserDefinedFunction = udf {
    (_: Seq[Seq[String]]).flatten.distinct
  }

  val merge_sets: UserDefinedFunction = udf { (a: Iterable[String], b: Iterable[String]) =>
    (Option(a).getOrElse(Set.empty).toSet ++ Option(b).getOrElse(Set.empty).toSet).toArray
  }

  val without: UserDefinedFunction = udf { (list: Seq[String], without: String) =>
    list.filterNot(x => x == without)
  }

  private val _mergeCounts = (a: Map[String, Int], b: Map[String, Int]) =>
    mergeMaps(Option(a).getOrElse(Map.empty[String, Int]),
              Option(b).getOrElse(Map.empty[String, Int]))(_ + _)

  val array_intersects: UserDefinedFunction = udf { (a: Seq[_], b: Seq[_]) =>
    a.intersect(b).nonEmpty}

  // this was added to GeoMesa in 2.2.0; in its absence, we have a simpler implementation that only handles Points
  val st_castToGeometry: UserDefinedFunction = udf { pt: Point => pt.asInstanceOf[Geometry] }

  def box2d(geom: Column): Column =
    when(
      geom.isNotNull and not(st_isEmpty(geom)),
      when(st_geometryType(geom) === "Point",
        struct(st_x(geom) as 'minX,
          st_y(geom) as 'minY,
          st_x(geom) as 'maxX,
          st_y(geom) as 'maxY)).otherwise(struct(
        st_x(st_pointN(st_exteriorRing(st_envelope(geom)), lit(1))) as 'minX,
        st_y(st_pointN(st_exteriorRing(st_envelope(geom)), lit(1))) as 'minY,
        st_x(st_pointN(st_exteriorRing(st_envelope(geom)), lit(3))) as 'maxX,
        st_y(st_pointN(st_exteriorRing(st_envelope(geom)), lit(3))) as 'maxY
      ))
    )
}
