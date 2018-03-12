package osmesa.bm

import geotrellis.proj4.{LatLng, WebMercator, Transform}
import geotrellis.spark._
import geotrellis.spark.join._
import geotrellis.spark.tiling._
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.vectortile._

import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import vectorpipe._
import vectorpipe.osm._

import cats.implicits._
import com.monovore.decline._

import osmesa.GenerateVT

import com.vividsolutions.jts.algorithm.Centroid

import scala.collection.mutable


object Util {

  def sparkSession(appName: String): SparkSession = {
    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName(s"OSMesa Analytics - ${appName}")
      .set("spark.sql.orc.filterPushdown", "true")
      .set("spark.hadoop.parquet.enable.summary-metadata", "false")
      .set("spark.sql.parquet.mergeSchema", "false")
      .set("spark.sql.parquet.filterPushdown", "true")
      .set("spark.sql.hive.metastorePartitionPruning", "true")

    SparkSession.builder
      .config(conf)
      .enableHiveSupport
      .getOrCreate
  }

  def filterfn1(clipGeometry: Option[Geometry])(feature: OSMFeature): Boolean = {
    clipGeometry match {
      case Some(g) => feature.geom.intersects(g)
      case None => true
    }
  }

  def filterfn2(clipGeometry: Option[Geometry])(pair: (Long, Node)): Boolean = {
    clipGeometry match {
      case Some(g) =>
        val node = pair._2
        Point(node.lon, node.lat).intersects(g)
      case None => true
    }
  }

}

object BuildingMatching extends CommandApp(
  name = "Building-Matching",
  header = "Match Buildings",
  main = {
    val ss = Util.sparkSession("Building-Matching")

    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    val clipGeometry =
      Opts.option[String]("clipGeometry", help = "GeoJSON file containing clip geometry").orNone
    val dataset1 =
      Opts.option[String]("dataset1", help = "Where to find dataset 1")
    val dataset2 =
      Opts.option[String]("dataset2", help = "Where to find dataset 2")
    val nomatch =
      Opts.flag("nomatch", help = "Disable building-matching (useful for saving clipped datasets)").orFalse
    val saveDataset1 =
      Opts.option[String]("saveDataset1", help = "Where to save dataset 1").orNone
    val saveDataset2 =
      Opts.option[String]("saveDataset2", help = "Where to save dataset 2").orNone

    (clipGeometry, dataset1, dataset2, nomatch, saveDataset1, saveDataset2)
      .mapN({ (_clipGeometry, dataset1, dataset2, nomatch, saveDataset1, saveDataset2) =>

        val clipGeometry: Option[Geometry] = _clipGeometry
          .flatMap({ str => Some(scala.io.Source.fromFile(str).mkString.parseGeoJson[Geometry]) })

        val dataset1Features = { // most-likely OSM
          if (dataset1.endsWith(".orc")) {
            val (ns1, ws1, rs1) = vectorpipe.osm.fromORC(dataset1)(ss).get
            vectorpipe
              .osm.features(ns1.filter(Util.filterfn2(clipGeometry)), ws1, rs1).geometries
              .filter({ feature => feature.data.tags.contains("building") })
              .filter(Util.filterfn1(clipGeometry))
          }
          else ss.sparkContext.objectFile[OSMFeature](dataset1)
        }.filter({ f: OSMFeature =>
          (f.geom, clipGeometry) match {
            case (p: Polygon, Some(g)) => (p.vertices.length > 4) && (p.intersects(g))
            case (p: Polygon, None) => (p.vertices.length > 4)
            case _ => false
          }
        })

        val dataset2Features = {
          if (dataset2.endsWith(".orc")) {
            val (ns1, ws1, rs1) = vectorpipe.osm.fromORC(dataset2)(ss).get
            vectorpipe
              .osm.features(ns1.filter(Util.filterfn2(clipGeometry)), ws1, rs1).geometries
              .filter({ feature => feature.data.tags.contains("building") })
              .filter(Util.filterfn1(clipGeometry))
          }
          else ss.sparkContext.objectFile[OSMFeature](dataset2)
        }.filter({ f: OSMFeature =>
          (f.geom, clipGeometry) match {
            case (p: Polygon, Some(g)) => (p.vertices.length > 4) && (p.intersects(g))
            case (p: Polygon, None) => (p.vertices.length > 4)
            case _ => false
          }
        })

        saveDataset1 match {
          case Some(filename) => dataset1Features.saveAsObjectFile(filename)
          case None =>
        }

        saveDataset2 match {
          case Some(filename) => dataset2Features.saveAsObjectFile(filename)
          case None =>
        }

        if (!nomatch) {

          val features: RDD[GenerateVT.VTF[Geometry]] = dataset1Features.map({ f => (f, 0) })
            .union(dataset2Features.map({ f => (f, 1) }))
            .partitionBy(new QuadTreePartitioner(Range(0,24).toSet, 4099)) // 4099 is the smallest prime larger than 2**12 = 4096
            .mapPartitions({ (it: Iterator[(OSMFeature, Int)]) =>
              val array = it.toArray

              // Venn diagram of data
              val (left, intersection, right) = {
                val middle = mutable.ArrayBuffer.empty[OSMFeature]
                val middleFromLeft = mutable.Set.empty[Long]
                val middleFromRight = mutable.Set.empty[Long]

                var i = 0; while (i < array.length) {
                  val a = array(i)._1
                  var j = i+1; while (j < array.length) {
                    val b = array(i)._1
                    if (a.geom == b.geom) {
                      middle += a
                      middleFromLeft += a.data.uid
                      middleFromRight += b.data.uid
                    }
                    j = j + 1
                  }
                  i = i + 1
                }

                val leftMinusMiddle = array
                  .filter(_._2 == 0)
                  .filter({ t => !middleFromLeft.contains(t._1.data.uid) })
                  .map(_._1)
                val rightMinusMiddle = array
                  .filter(_._2 == 1)
                  .filter({ t => !middleFromRight.contains(t._1.data.uid) })
                  .map(_._1)

                (leftMinusMiddle, middle.toArray, rightMinusMiddle)
              }

              // Compute Relative Similarities
              val r = {
                val data = Array.ofDim[Double](left.length, right.length, intersection.length)
                var k = 0; while (k < intersection.length) {
                  val c = intersection(k)
                  val cCentroid = Centroid.getCentroid(c.jtsGeom)
                  var i = 0; while (i < left.length) {
                    val a = left(i)
                    val aCentroid = Centroid.getCentroid(a.jtsGeom)
                    val dist1 = a.distance(c)
                    val (vx, vy) = (aCentroid.x - cCentroid.x, aCentroid.y - cCentroid.y)
                    val absv = math.sqrt(vx*vx + vy*vy)
                    var j = 0; while (j < right.length) {
                      val b = right(j)
                      val bCentroid = Centroid.getCentroid(b.jtsGeom)
                      val dist2 = b.distance(c)
                      val (ux, uy) = (bCentroid.x - cCentroid.x, bCentroid.y - cCentroid.y)
                      val absu = math.sqrt(ux*ux + uy*uy)
                      val dot = (vx*ux + vy*uy)/(absv*absu)
                      data(i)(j)(k) = dot*math.min(dist1/dist2,dist2/dist1)
                      j = j + 1
                    }
                    i = i + 1
                  }
                  k = k + 1
                }
                data
              }

              // Compute Support
              val q = {
                val data = Array.ofDim[Double](left.length, right.length)
                var i = 0; while (i < left.length) {
                  var j = 0; while (j < right.length) {
                    data(i)(j) = r(i)(j).sum
                    j = j + 1
                  }
                  i = i + 1
                }
                val totals = data.map(_.sum)
                data.zip(totals).map({ case (a, total) => a.map({ x => x/total }) })
              }

              // Compute Probabilities
              val p = {
                val data = Array.ofDim[Double](left.length, right.length)
                var i = 0; while (i < left.length) {
                  val a = left(i).geom.asInstanceOf[Polygon]
                  var j = 0; while (j < right.length) {
                    val b = right(j).geom.asInstanceOf[Polygon]
                    val p1 = 1.0 - VertexMatching.score(a, b)
                    val (p2, p3) = VolumeMatching.data(a, b)
                    data(i)(j) = (math.max(p1, math.max(p2, p3)) + q(i)(j))/2
                    j = j + 1
                  }
                  i = i + 1
                }
                data
              }

              Array.empty[(OSMFeature, (String, Double, OSMFeature))].toIterator // XXX
            }) // XXX , preservesPartitioning = true)
            .groupByKey // XXX can be optimized away by changing inner loop
            .map({ case (b, itr) =>
              // val best = itr.toArray.sortBy({ _._1 }).head
              Feature(
                b.geom.reproject(LatLng, WebMercator),
                Map(
                  "__id" -> VInt64(b.data.uid),
                  "matches" -> VInt64(itr.toArray.length)
                )
              )
            })

          val layoutScheme = ZoomedLayoutScheme(WebMercator, 512)
          val maxLayoutLevel = layoutScheme.levelForZoom(19)
          val LayoutLevel(zoom, layout) = maxLayoutLevel
          val keyed = GenerateVT.keyToLayout(features, layout)

          GenerateVT.saveHadoop(
            GenerateVT.makeVectorTiles(keyed, layout, "name"),
            zoom,
            "/tmp/tiles"
          )

        }
      })
  }
)
