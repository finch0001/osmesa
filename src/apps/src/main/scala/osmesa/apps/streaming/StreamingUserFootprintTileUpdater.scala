package osmesa.apps.streaming

import java.io._
import java.net.URI

import cats.implicits._
import com.monovore.decline._
import org.apache.spark.sql._
import org.locationtech.geomesa.spark.jts._
import osmesa.analytics.{Analytics, Footprints}
import vectorpipe.sources.Source

/*
 * Usage example:
 *
 * sbt "project apps" assembly
 *
 * spark-submit \
 *   --class osmesa.apps.streaming.StreamingUserFootprintUpdater \
 *   ingest/target/scala-2.11/osmesa-apps.jar
 */
object StreamingUserFootprintTileUpdater
    extends CommandApp(
      name = "osmesa-user-footprint-updater",
      header = "Consume minutely diffs to update user footprint MVTs",
      main = {
        val changeSourceOpt = Opts
          .option[URI]("change-source",
                       short = "d",
                       metavar = "uri",
                       help = "Location of minutely diffs to process")
          .withDefault(new URI("https://planet.osm.org/replication/minute/"))

        val startSequenceOpt = Opts
          .option[Int](
            "start-sequence",
            short = "s",
            metavar = "sequence",
            help =
              "Minutely diff starting sequence. If absent, the current (remote) sequence will be used.")
          .orNone

        val batchSizeOpt = Opts
          .option[Int]("batch-size",
                       short = "b",
                       metavar = "batch size",
                       help = "Change batch size.")
          .orNone

        val tileSourceOpt = Opts
          .option[URI](
            "tile-source",
            short = "t",
            metavar = "uri",
            help = "URI prefix for vector tiles to update"
          )
          .withDefault(new File("").toURI)

        val concurrentUploadsOpt = Opts
          .option[Int]("concurrent-uploads",
                       short = "c",
                       metavar = "concurrent uploads",
                       help = "Set the number of concurrent uploads.")
          .orNone

        val databaseUrlOpt =
          Opts
            .option[URI](
              "database-url",
              short = "d",
              metavar = "database URL",
              help = "Database URL (default: DATABASE_URL environment variable)"
            )
            .orElse(Opts.env[URI]("DATABASE_URL", help = "The URL of the database"))
            .orNone

        (changeSourceOpt,
         startSequenceOpt,
         batchSizeOpt,
         tileSourceOpt,
         concurrentUploadsOpt,
         databaseUrlOpt).mapN {
          (changeSource, startSequence, batchSize, tileSource, _concurrentUploads, databaseUrl) =>
            val AppName = "UserFootprintUpdater"

            val spark: SparkSession = Analytics.sparkSession(AppName)
            import spark.implicits._
            implicit val concurrentUploads: Option[Int] = _concurrentUploads
            spark.withJTS

            val changeOptions = Map(Source.BaseURI -> changeSource.toString,
                                    Source.ProcessName -> AppName) ++
              databaseUrl
                .map(x => Map(Source.DatabaseURI -> x.toString))
                .getOrElse(Map.empty) ++
              startSequence
                .map(x => Map(Source.StartSequence -> x.toString))
                .getOrElse(Map.empty) ++
              batchSize
                .map(x => Map(Source.BatchSize -> x.toString))
                .getOrElse(Map.empty)

            val changes = spark.readStream
              .format(Source.Changes)
              .options(changeOptions)
              .load

            val changedNodes = changes
              .where('type === "node" and 'lat.isNotNull and 'lon.isNotNull)
              .select('sequence, 'uid as 'key, st_makePoint('lon, 'lat) as 'geom)

            val tiledNodes =
              Footprints.update(changedNodes, tileSource)

            val query = tiledNodes.writeStream
              .queryName("tiled user footprints")
              .format("console")
              .start

            query.awaitTermination()

            spark.stop()
        }
      }
    )
