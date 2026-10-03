package com.andrerinas.openheadunit.aap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONObject
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * JLY E60 build: the speed limit of the road the car is on, for the cluster.
 *
 * The road data (OpenStreetMap, south-east Queensland; built by updater/speedlimits.py and
 * delivered as a signed "speedlimits" update) is memory-mapped. Each GPS fix is matched to the
 * nearest road segment within [MATCH_M] metres, preferring segments that run the way the car is
 * heading, and the limit goes to the cluster as a ClusterLink "limit" message.
 */
@SuppressLint("StaticFieldLeak")
object SpeedLimits : LocationListener {
    private const val MATCH_M = 20.0          // a fix further than this from any road matches nothing
    private const val HEADING_DEG = 45.0      // segment must run within this of the car's bearing
    private const val HOLD_MS = 10_000L       // keep the last limit this long when nothing matches
    private const val M_PER_UDEG = 0.111195   // metres per microdegree of latitude

    private lateinit var context: Context
    private val thread = HandlerThread("SpeedLimits").apply { start() }
    private val handler by lazy { Handler(thread.looper) }
    private var toldNoPermission = false

    @Volatile private var data: Data? = null
    private var shown = 0                      // kph on the cluster, 0 = unknown
    private var candidate = 0
    private var candidateSeen = 0
    private var lastMatchAt = 0L

    private class Data(val buf: MappedByteBuffer) {
        val minLat = buf.getInt(8); val minLon = buf.getInt(12); val cell = buf.getInt(16)
        val cols = buf.getInt(20); val rows = buf.getInt(24); val count = buf.getInt(28)
        val offsetsAt = 32
        val refsAt = offsetsAt + 4 * (cols * rows + 1)
        val segsAt = refsAt + 4 * buf.getInt(offsetsAt + 4 * cols * rows)
        val kphAt = segsAt + 16 * count
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
        reload()
        startGps()
    }

    /** (Re)opens the data file; called at start-up and when a new release is installed. */
    fun reload() {
        val file = CarUpdate.speedLimitFile()
        data = try {
            if (!file.isFile) null else RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { buf.get(it) }
                if (String(magic) != "E6SL" || buf.getInt(4) != 1) throw IllegalStateException("not a v1 E6SL file")
                Data(buf)
            }
        } catch (e: Exception) {
            AppLog.e("SpeedLimits: could not load ${file.name}", e)
            null
        }
        data?.let { AppLog.i("SpeedLimits: ${it.count} road segments loaded") }
    }

    /** Subscribes to GPS; until location permission is granted (first run), retries every 30 s. */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!toldNoPermission) AppLog.w("SpeedLimits: waiting for location permission")
            toldNoPermission = true
            handler.postDelayed({ startGps() }, 30_000L)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.looper)
            AppLog.i("SpeedLimits: GPS updates requested")
        } catch (e: Exception) {
            AppLog.e("SpeedLimits: GPS updates unavailable", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        val d = data ?: return
        val kph = match(d, location)
        val now = SystemClock.elapsedRealtime()
        if (kph > 0) {
            lastMatchAt = now
            // a different limit has to be seen twice in a row, so one stray fix near a side
            // street does not flick the sign
            if (kph == candidate) candidateSeen++ else { candidate = kph; candidateSeen = 1 }
            if (kph != shown && (candidateSeen >= 2 || shown == 0)) publish(kph)
        } else if (shown != 0 && now - lastMatchAt > HOLD_MS) {
            publish(0)
        }
    }

    private fun publish(kph: Int) {
        shown = kph
        ClusterLink.publishLimit(JSONObject().put("type", "limit").put("kph", kph).toString())
    }

    /** Limit of the best-matching segment, or 0. */
    private fun match(d: Data, loc: Location): Int {
        val lat = (loc.latitude * 1e6).toInt()
        val lon = (loc.longitude * 1e6).toInt()
        val row = Math.floor((lat - d.minLat).toDouble() / d.cell).toInt()
        val col = Math.floor((lon - d.minLon).toDouble() / d.cell).toInt()
        if (row !in 0 until d.rows || col !in 0 until d.cols) return 0
        val kx = M_PER_UDEG * cos(Math.toRadians(loc.latitude))   // metres per microdegree east
        val useHeading = loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f
        var best = Double.MAX_VALUE; var bestKph = 0
        var bestAny = Double.MAX_VALUE; var bestAnyKph = 0
        for (r in maxOf(0, row - 1)..minOf(d.rows - 1, row + 1)) {
            for (c in maxOf(0, col - 1)..minOf(d.cols - 1, col + 1)) {
                val cellIx = r * d.cols + c
                val from = d.buf.getInt(d.offsetsAt + 4 * cellIx)
                val to = d.buf.getInt(d.offsetsAt + 4 * (cellIx + 1))
                for (k in from until to) {
                    val s = d.buf.getInt(d.refsAt + 4 * k)
                    val p = d.segsAt + 16 * s
                    // segment in metres relative to the fix
                    val ax = (d.buf.getInt(p + 4) - lon) * kx; val ay = (d.buf.getInt(p) - lat) * M_PER_UDEG
                    val bx = (d.buf.getInt(p + 12) - lon) * kx; val by = (d.buf.getInt(p + 8) - lat) * M_PER_UDEG
                    val dist = distanceToSegment(ax, ay, bx, by)
                    if (dist > MATCH_M) continue
                    val kph = d.buf.get(d.kphAt + s).toInt() and 0xff
                    if (dist < bestAny) { bestAny = dist; bestAnyKph = kph }
                    if (useHeading) {
                        val segDeg = Math.toDegrees(atan2(bx - ax, by - ay))      // 0 = north
                        var diff = abs(((segDeg - loc.bearing) % 180 + 180) % 180)   // roads run both ways
                        if (diff > 90) diff = 180 - diff
                        if (diff > HEADING_DEG) continue
                    }
                    if (dist < best) { best = dist; bestKph = kph }
                }
            }
        }
        // heading filters out crossing streets; if it rules out everything, take the nearest
        return if (bestKph > 0) bestKph else bestAnyKph
    }

    /** Distance from the origin (the fix) to segment A-B, all in metres. */
    private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
