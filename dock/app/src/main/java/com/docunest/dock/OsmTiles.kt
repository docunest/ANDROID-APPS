package com.docunest.dock

import android.content.Context
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver

/**
 * OSM tiles for every map in the app: tiles cached on the unit (SQLite, works
 * offline), then downloaded, with lower-zoom tiles scaled up while loading.
 * Same as osmdroid's default minus offline archive files (.zip/.sqlite/
 * .mbtiles/.gemf) we never use, so their readers stay out of the APK.
 */
object OsmTiles {
    fun provider(ctx: Context): MapTileProviderArray {
        val src = TileSourceFactory.MAPNIK
        val receiver = SimpleRegisterReceiver(ctx)
        val writer = SqlTileWriter()
        val cache = MapTileSqlCacheProvider(receiver, src)
        val scaled = MapTileApproximater().apply { addProvider(cache) }
        val download = MapTileDownloader(src, writer, NetworkAvailabliltyCheck(ctx))
        return object : MapTileProviderArray(src, receiver, arrayOf(cache, scaled, download)) {
            override fun detach() {
                super.detach()
                writer.onDetach()
            }
        }
    }
}
