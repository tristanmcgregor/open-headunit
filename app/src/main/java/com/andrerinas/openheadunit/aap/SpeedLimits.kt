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
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * JLY E60 build: everything the cluster gets from the head unit's GPS.
 *
 * - **Speed limit** of the road the car is on. The road data (OpenStreetMap, south-east Queensland;
 *   built by updater/speedlimits.py and delivered as a signed "speedlimits" update) is
 *   memory-mapped. Each fix is matched to the nearest road segment within [MATCH_M] metres,
 *   preferring segments that run the way the car is heading. ClusterLink "limit" message.
 * - **School zones**: a segment's school-hours limit (OSM maxspeed:conditional) applies on
 *   weekdays in its hours, outside Queensland public holidays and inside school terms (both
 *   lists are in the data file). `school: true` in the "limit" message while one applies.
 * - **Cameras** (fixed speed, red-light, average-speed start) ahead of the car: ClusterLink
 *   "camera" messages counting down the distance, then distM -1 once passed.
 * - **GPS speed** every fix ("gps" message), for checking the dash's speed correction.
 */
@SuppressLint("StaticFieldLeak")
object SpeedLimits : LocationListener {
    private const val MATCH_M = 20.0          // a fix further than this from any road matches nothing
    private const val HEADING_DEG = 45.0      // segment must run within this of the car's bearing
    private const val HOLD_MS = 10_000L       // keep the last limit this long when nothing matches
    private const val M_PER_UDEG = 0.111195   // metres per microdegree of latitude

    private const val CAMERA_AHEAD_DEG = 30.0     // camera must lie within this of the car's heading
    private const val CAMERA_MIN_M = 350.0        // warn at least this far out ...
    private const val CAMERA_LEAD_S = 25.0        // ... or this many seconds out at the current speed
    private const val CAMERA_MAX_M = 800.0
    private const val CAMERA_REPEAT_MS = 180_000L // the same camera is not announced again for this long

    private lateinit var context: Context
    private val thread = HandlerThread("SpeedLimits").apply { start() }
    private val handler by lazy { Handler(thread.looper) }
    private var toldNoPermission = false

    @Volatile private var data: Data? = null
    private var shown = 0                      // limit code on the cluster (see [code]), 0 = unknown
    private var candidate = 0
    private var candidateSeen = 0
    private var lastMatchAt = 0L

    private var camera = -1                    // camera being counted down, -1 = none
    private val cameraDoneAt = HashMap<Int, Long>()

    /** School-hours limit: [days] bit 0 = Monday .. bit 6 = Sunday; [ranges] minutes of the day. */
    private class Schedule(val kph: Int, val days: Int, val phOff: Boolean, val shOff: Boolean, val ranges: List<IntArray>)
    private class Camera(val lat: Int, val lon: Int, val kind: String, val kph: Int)

    private class Data(val buf: MappedByteBuffer) {
        val minLat = buf.getInt(8); val minLon = buf.getInt(12); val cell = buf.getInt(16)
        val cols = buf.getInt(20); val rows = buf.getInt(24); val count = buf.getInt(28)
        val offsetsAt = 32
        val refsAt = offsetsAt + 4 * (cols * rows + 1)
        val segsAt = refsAt + 4 * buf.getInt(offsetsAt + 4 * cols * rows)
        val kphAt = segsAt + 16 * count

        // optional "E6X1" extension after the v1 body (older head unit builds ignore it)
        var schedules: List<Schedule> = emptyList()
        var schedIdxAt = -1
        var cameras: List<Camera> = emptyList()
        var holidays: Set<Int> = emptySet()        // yyyymmdd
        var terms: List<IntArray> = emptyList()    // [fromYmd, toYmd] inclusive
        var termsUntil = 0

        init {
            var p = align4(kphAt + count)
            if (buf.limit() >= p + 8 && String(ByteArray(4) { buf.get(p + it) }) == "E6X1") {
                p += 4
                val nSched = buf.getInt(p); p += 4
                schedules = (0 until nSched).map { i ->
                    val q = p + 20 * i
                    val n = buf.get(q + 3).toInt() and 0xff
                    val flags = buf.get(q + 2).toInt()
                    Schedule(buf.get(q).toInt() and 0xff, buf.get(q + 1).toInt() and 0x7f, flags and 1 != 0, flags and 2 != 0,
                        (0 until minOf(n, 4)).map { r ->
                            intArrayOf(buf.getShort(q + 4 + 4 * r).toInt(), buf.getShort(q + 6 + 4 * r).toInt())
                        })
                }
                p += 20 * nSched
                schedIdxAt = p; p = align4(p + count)
                val nCam = buf.getInt(p); p += 4
                cameras = (0 until nCam).map { i ->
                    val q = p + 12 * i
                    val kind = when (buf.get(q + 8).toInt()) { 2 -> "redlight"; 3 -> "average"; else -> "speed" }
                    Camera(buf.getInt(q), buf.getInt(q + 4), kind, buf.get(q + 9).toInt() and 0xff)
                }
                p += 12 * nCam
                val nPh = buf.getInt(p); p += 4
                holidays = (0 until nPh).map { buf.getInt(p + 4 * it) }.toSet(); p += 4 * nPh
                val nTerms = buf.getInt(p); p += 4
                terms = (0 until nTerms).map { intArrayOf(buf.getInt(p + 8 * it), buf.getInt(p + 8 * it + 4)) }
                termsUntil = terms.maxOfOrNull { it[1] } ?: 0
            }
        }

        private fun align4(n: Int) = (n + 3) and 3.inv()
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
        data?.let {
            AppLog.i("SpeedLimits: ${it.count} road segments, ${it.schedules.size} school-zone schedules, " +
                "${it.cameras.size} cameras, school terms to ${it.termsUntil}")
        }
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
        if (location.hasSpeed()) {
            val gps = JSONObject().put("type", "gps").put("kph", (location.speed * 36).roundToInt() / 10.0)
            if (location.hasAccuracy()) gps.put("acc", location.accuracy.roundToInt())
            ClusterLink.publishLive(gps.toString())
        }
        val d = data ?: return
        checkLimit(d, location)
        checkCameras(d, location)
    }

    // ---- speed limit and school zones ----

    /** Limit code: kph, plus 1000 while a school-zone limit applies (so a change of either counts). */
    private fun code(kph: Int, school: Boolean) = kph + if (school) 1000 else 0

    private fun checkLimit(d: Data, location: Location) {
        val seg = match(d, location)
        val now = SystemClock.elapsedRealtime()
        if (seg >= 0) {
            var kph = d.buf.get(d.kphAt + seg).toInt() and 0xff
            var school = false
            if (d.schedIdxAt >= 0 && CarSettings.schoolZones) {
                val ix = d.buf.get(d.schedIdxAt + seg).toInt() and 0xff
                if (ix > 0 && ix <= d.schedules.size && applies(d, d.schedules[ix - 1])) {
                    kph = d.schedules[ix - 1].kph
                    // other timed limits (e.g. night-time wildlife zones) apply without the label
                    school = d.schedules[ix - 1].shOff
                }
            }
            val c = code(kph, school)
            lastMatchAt = now
            // a different limit has to be seen twice in a row, so one stray fix near a side
            // street does not flick the sign
            if (c == candidate) candidateSeen++ else { candidate = c; candidateSeen = 1 }
            if (c != shown && (candidateSeen >= 2 || shown == 0)) publish(c)
        } else if (shown != 0 && now - lastMatchAt > HOLD_MS) {
            publish(0)
        }
    }

    private fun publish(code: Int) {
        shown = code
        ClusterLink.publishLimit(JSONObject().put("type", "limit").put("kph", code % 1000)
            .put("school", code >= 1000).toString())
    }

    /** Whether a school-hours limit is in force right now (head unit clock, local time). */
    private fun applies(d: Data, s: Schedule): Boolean {
        val cal = Calendar.getInstance()
        val ymd = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7           // Monday = 0
        if (s.days and (1 shl dow) == 0) return false
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (s.ranges.isNotEmpty() && s.ranges.none { (a, b) -> if (a < b) m in a until b else m >= a || m < b }) return false
        if (s.phOff && ymd in d.holidays) return false
        // beyond the known terms, assume a school day: better a 40 sign too many than one missing
        if (s.shOff && ymd <= d.termsUntil && d.terms.none { ymd in it[0]..it[1] }) return false
        return true
    }

    /** Best-matching segment number, or -1. */
    private fun match(d: Data, loc: Location): Int {
        val lat = (loc.latitude * 1e6).toInt()
        val lon = (loc.longitude * 1e6).toInt()
        val row = Math.floor((lat - d.minLat).toDouble() / d.cell).toInt()
        val col = Math.floor((lon - d.minLon).toDouble() / d.cell).toInt()
        if (row !in 0 until d.rows || col !in 0 until d.cols) return -1
        val kx = M_PER_UDEG * cos(Math.toRadians(loc.latitude))   // metres per microdegree east
        val useHeading = loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f
        var best = Double.MAX_VALUE; var bestSeg = -1
        var bestAny = Double.MAX_VALUE; var bestAnySeg = -1
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
                    if (dist < bestAny) { bestAny = dist; bestAnySeg = s }
                    if (useHeading) {
                        val segDeg = Math.toDegrees(atan2(bx - ax, by - ay))      // 0 = north
                        var diff = abs(((segDeg - loc.bearing) % 180 + 180) % 180)   // roads run both ways
                        if (diff > 90) diff = 180 - diff
                        if (diff > HEADING_DEG) continue
                    }
                    if (dist < best) { best = dist; bestSeg = s }
                }
            }
        }
        // heading filters out crossing streets; if it rules out everything, take the nearest
        return if (bestSeg >= 0) bestSeg else bestAnySeg
    }

    /** Distance from the origin (the fix) to segment A-B, all in metres. */
    private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    // ---- cameras ----

    /** Distance (m) and bearing difference from the car's heading (deg, 0..180) to camera [c]. */
    private fun relative(c: Camera, loc: Location): Pair<Double, Double> {
        val kx = M_PER_UDEG * cos(Math.toRadians(loc.latitude))
        val x = (c.lon - loc.longitude * 1e6) * kx
        val y = (c.lat - loc.latitude * 1e6) * M_PER_UDEG
        val deg = Math.toDegrees(atan2(x, y))
        var diff = abs(((deg - loc.bearing) % 360 + 360) % 360)
        if (diff > 180) diff = 360 - diff
        return sqrt(x * x + y * y) to diff
    }

    private fun checkCameras(d: Data, loc: Location) {
        if (!CarSettings.cameraAlerts || d.cameras.isEmpty()) { clearCamera(); return }
        val now = SystemClock.elapsedRealtime()
        if (camera >= 0) {
            val (dist, diff) = relative(d.cameras[camera], loc)
            // passed: now behind the car, or clearly further away than when first announced
            if (diff > 90 || dist > CAMERA_MAX_M + 150) clearCamera() else { sendCamera(d.cameras[camera], dist); return }
        }
        if (!loc.hasBearing() || !loc.hasSpeed() || loc.speed < 4f) return
        val range = (loc.speed * CAMERA_LEAD_S).coerceIn(CAMERA_MIN_M, CAMERA_MAX_M)
        var bestIx = -1; var bestDist = Double.MAX_VALUE
        d.cameras.forEachIndexed { i, c ->
            if (abs(c.lat - loc.latitude * 1e6) > 10_000) return@forEachIndexed   // ~1.1 km: skip the maths
            val (dist, diff) = relative(c, loc)
            if (dist <= range && diff <= CAMERA_AHEAD_DEG && dist < bestDist &&
                now - (cameraDoneAt[i] ?: -CAMERA_REPEAT_MS) >= CAMERA_REPEAT_MS) { bestIx = i; bestDist = dist }
        }
        if (bestIx >= 0) {
            camera = bestIx
            AppLog.i("SpeedLimits: ${d.cameras[bestIx].kind} camera ${bestDist.roundToInt()} m ahead")
            sendCamera(d.cameras[bestIx], bestDist)
        }
    }

    private fun sendCamera(c: Camera, dist: Double) {
        ClusterLink.publishLive(JSONObject().put("type", "camera").put("kind", c.kind)
            .put("kph", c.kph).put("distM", (dist / 10).roundToInt() * 10).toString())
    }

    private fun clearCamera() {
        if (camera < 0) return
        cameraDoneAt[camera] = SystemClock.elapsedRealtime()
        camera = -1
        ClusterLink.publishLive(JSONObject().put("type", "camera").put("distM", -1).toString())
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
