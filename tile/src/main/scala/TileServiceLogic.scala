package org.opentreemap.modeling

import org.apache.avro._

import org.apache.spark._
import geotrellis.raster._
import geotrellis.vector._
import geotrellis.services._
import geotrellis.spark._
import geotrellis.spark.io.Intersects
import geotrellis.spark.io.index._
import geotrellis.spark.io.s3._
import geotrellis.spark.io.json._
import geotrellis.spark.tiling._ //kill?
import geotrellis.raster.resample._ //kill?
import geotrellis.raster.render._
import geotrellis.spark.op.local._

trait TileServiceLogic
{
  val DATA_TILE_MAX_ZOOM = 11
  val BREAKS_ZOOM = 8

  def addTiles(t1: Tile, t2: Tile): Tile = {
    t1.combine(t2)(_ + _)
  }

  def weightedOverlay(implicit sc: SparkContext,
                      tileReader: S3TileReader[SpatialKey, Tile],
                      layers:Seq[String],
                      weights:Seq[Int],
                      z:Int,
                      x:Int,
                      y:Int): Tile = {
    // TODO: Handle layers with different pyramids instead of using DATA_TILE_MAX_ZOOM
    layers
      .zip(weights)
      .map { case (layer, weight) =>
        val tile = TileGetter.getTileWithZoom(sc, tileReader, layer, z, x, y, DATA_TILE_MAX_ZOOM)
        tile.convert(TypeInt).map(_ * weight)
    }
      .reduce(addTiles)
  }

  def weightedOverlay(implicit sc: SparkContext,
                      catalog: S3LayerReader[SpatialKey, Tile, RasterRDD[SpatialKey]],
                      layers:Seq[String],
                      weights:Seq[Int],
                      bounds:Extent): RasterRDD[SpatialKey] = {
    layers
      .zip(weights)
      .map { case (layer, weight) =>
        val base = catalog.query((layer, BREAKS_ZOOM))
        val intersected = base.where(Intersects(bounds))
        val rdd = intersected.toRDD
        // Convert Byte RDD to Int so that math operations do not overflow
        rdd.convert(TypeInt).localMultiply(weight)
      }
      .localAdd
  }

  def renderTile(tile: Tile, breaks: Seq[Int], colorRamp: String): Png = {
    val ramp = {
      val cr = ColorRampMap.getOrElse(colorRamp, ColorRamps.BlueToRed)
      cr.interpolate(breaks.length)
    }
    tile.renderPng(ramp.toArray, breaks.toArray)
  }

}
