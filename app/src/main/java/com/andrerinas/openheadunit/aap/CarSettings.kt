package com.andrerinas.openheadunit.aap

import android.app.Application
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * JLY E60 build: cluster display settings, edited from a phone browser on the car Wi-Fi at
 * http://<head unit>:8765/settings and pushed to the cluster as a ClusterLink "settings" message.
 * The cluster keeps its own copy, so it starts with the last settings before this app is up.
 *
 * Only display settings live here; nothing that reaches the car's controls.
 */
object CarSettings {
    private const val MAX_BODY = 16 * 1024

    private lateinit var file: File
    @Volatile private var current: JSONObject = defaults()

    fun init(application: Application) {
        file = File(application.filesDir, "carsettings.json")
        current = try {
            if (file.isFile) sanitize(JSONObject(file.readText())) else defaults()
        } catch (e: Exception) {
            AppLog.w("CarSettings: stored settings unreadable, using defaults (${e.message})")
            defaults()
        }
        ClusterLink.publishSettings(message())
    }

    /** Handles /settings and /settings.json; returns false for any other path. */
    fun handle(method: String, path: String, headers: Map<String, String>, input: InputStream, out: OutputStream): Boolean {
        val route = path.substringBefore('?')
        if (route != "/settings" && route != "/settings.json") return false
        when {
            route == "/settings" -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "GET" -> respond(out, 200, "application/json", current.toString())
            method == "POST" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: -1
                if (length !in 1..MAX_BODY) return respond(out, 400, "text/plain", "bad length").let { true }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                try {
                    current = sanitize(JSONObject(String(body, 0, read)))
                    file.writeText(current.toString())
                    ClusterLink.publishSettings(message())
                    AppLog.i("CarSettings: saved $current")
                    respond(out, 200, "application/json", current.toString())
                } catch (e: Exception) {
                    respond(out, 400, "text/plain", "invalid settings: ${e.message}")
                }
            }
            else -> respond(out, 405, "text/plain", "method not allowed")
        }
        return true
    }

    private fun message(): String = JSONObject(current.toString()).put("type", "settings").toString()

    fun defaults(): JSONObject = JSONObject()
        .put("speedCorrection", 6.0)
        .put("sport", "auto")
        .put("shiftLights", true)
        .put("shiftWindow", 2000)
        .put("shiftMargin", 200)
        .put("redline", JSONArray("[[20,4500],[40,5166],[50,5500],[60,6000],[70,6500],[80,6875],[90,7250]]"))
        .put("perfPopups", "sport")
        .put("speedLimit", true)
        .put("speedLimitMargin", 3)
        .put("defaultPage", 0)

    /** Keeps known keys only, clamped to sane ranges; anything missing comes from the defaults. */
    private fun sanitize(input: JSONObject): JSONObject {
        val d = defaults()
        fun num(key: String, lo: Double, hi: Double): Double =
            input.optDouble(key, d.getDouble(key)).let { if (it.isNaN()) d.getDouble(key) else it.coerceIn(lo, hi) }
        val out = JSONObject()
            .put("speedCorrection", num("speedCorrection", -10.0, 15.0))
            .put("sport", input.optString("sport", "auto").takeIf { it in setOf("auto", "always", "never") } ?: "auto")
            .put("shiftLights", input.optBoolean("shiftLights", true))
            .put("shiftWindow", num("shiftWindow", 500.0, 4000.0).toInt())
            .put("shiftMargin", num("shiftMargin", 0.0, 1000.0).toInt())
            .put("speedLimit", input.optBoolean("speedLimit", true))
            .put("speedLimitMargin", num("speedLimitMargin", 0.0, 20.0).toInt())
            .put("defaultPage", num("defaultPage", 0.0, 3.0).toInt())
            .put("perfPopups", input.optString("perfPopups", "sport").takeIf { it in setOf("sport", "always", "off") } ?: "sport")
        // redline rows [°C, rpm], sorted by temperature
        val rows = (input.optJSONArray("redline") ?: d.getJSONArray("redline"))
        val table = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONArray(i) ?: return@mapNotNull null
            val t = r.optInt(0, Int.MIN_VALUE); val rpm = r.optInt(1, -1)
            if (t in -40..150 && rpm in 2000..8000) t to rpm else null
        }.sortedBy { it.first }.take(10)
        out.put("redline", JSONArray(table.ifEmpty { listOf(30 to 4500, 100 to 7000) }
            .map { JSONArray().put(it.first).put(it.second) }))
        return out
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nCache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    // Self-contained page: the car has no internet, so no external scripts, styles or fonts.
    private val PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>E60 cluster settings</title>
<style>
:root{--bg:#0b0d10;--card:#15191e;--line:#262c33;--text:#eef2f7;--dim:#8b96a7;--accent:#4aa3ff;--ok:#35d07f;--bad:#ff4d4d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.4 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:16px}h1{font-size:20px;margin:8px 0 16px}
section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px;margin-bottom:14px}
h2{font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:var(--dim);margin:0 0 10px}
label{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:8px 0;border-top:1px solid var(--line)}
label:first-of-type{border-top:0}small{display:block;color:var(--dim);font-size:13px}
input[type=number],select{width:110px;padding:8px;border-radius:8px;border:1px solid var(--line);background:#0e1115;color:var(--text);font-size:16px}
input[type=checkbox]{width:22px;height:22px}
table{width:100%;border-collapse:collapse}td{padding:4px 0}td input{width:100%}
button{width:100%;padding:14px;border:0;border-radius:10px;background:var(--accent);color:#fff;font-size:17px;font-weight:600}
#status{text-align:center;margin:10px 0;color:var(--dim);min-height:1.4em}
</style></head><body><main>
<h1>E60 cluster settings</h1>
<div id="versions" style="color:var(--dim);font-size:14px;margin:-8px 0 14px"></div>
<section><h2>Speed</h2>
<label><span>Speed correction %<small>Added to the car's speed (true-speed calibration)</small></span><input id="speedCorrection" type="number" step="0.5"></label>
<label><span>Show speed limit</span><input id="speedLimit" type="checkbox"></label>
<label><span>Over-limit margin (km/h)<small>Sign turns red above limit + margin</small></span><input id="speedLimitMargin" type="number"></label>
</section>
<section><h2>Sport layout &amp; shift lights</h2>
<label><span>Sport layout</span><select id="sport"><option value="auto">In S and M</option><option value="always">Always</option><option value="never">Never</option></select></label>
<label><span>Shift lights</span><input id="shiftLights" type="checkbox"></label>
<label><span>0–60 / 0–100 popups<small>Runs are always timed; this is when the result shows</small></span><select id="perfPopups"><option value="sport">In S and M</option><option value="always">Always</option><option value="off">Off</option></select></label>
<label><span>Light-up window (rpm)<small>Lights start this far below the shift point</small></span><input id="shiftWindow" type="number" step="100"></label>
<label><span>Shift margin (rpm)<small>Flash this far below the redline</small></span><input id="shiftMargin" type="number" step="50"></label>
</section>
<section><h2>Redline by oil temperature</h2>
<small>The redline follows a straight line between rows, and stays flat below the first and above the last.</small>
<table id="redline"></table>
</section>
<section><h2>Display</h2>
<label><span>Default centre page</span><select id="defaultPage"><option value="0">Trip</option><option value="1">Vehicle</option><option value="2">Navigation</option><option value="3">Info</option></select></label>
</section>
<button id="save">Save to cluster</button><div id="status"></div>
</main><script>
const $=id=>document.getElementById(id);let s={};
function rows(){const t=$('redline');t.innerHTML='<tr><td><small>Oil °C</small></td><td><small>Redline rpm</small></td></tr>';
 for(let i=0;i<8;i++){const r=s.redline[i]||['',''];t.insertAdjacentHTML('beforeend',`<tr><td><input type="number" data-r="${'$'}{i}" data-c="0" value="${'$'}{r[0]}"></td><td><input type="number" step="100" data-r="${'$'}{i}" data-c="1" value="${'$'}{r[1]}"></td></tr>`)}}
function show(){['speedCorrection','speedLimitMargin','shiftWindow','shiftMargin'].forEach(k=>$(k).value=s[k]);
 ['speedLimit','shiftLights'].forEach(k=>$(k).checked=s[k]);$('sport').value=s.sport;$('perfPopups').value=s.perfPopups;$('defaultPage').value=s.defaultPage;rows()}
function collect(){const o={};['speedCorrection','speedLimitMargin','shiftWindow','shiftMargin','defaultPage'].forEach(k=>o[k]=Number($(k).value));
 ['speedLimit','shiftLights'].forEach(k=>o[k]=$(k).checked);o.sport=$('sport').value;o.perfPopups=$('perfPopups').value;
 const red=[];for(let i=0;i<8;i++){const a=document.querySelector(`[data-r="${'$'}{i}"][data-c="0"]`).value,b=document.querySelector(`[data-r="${'$'}{i}"][data-c="1"]`).value;if(a!==''&&b!=='')red.push([Number(a),Number(b)])}
 o.redline=red;return o}
function status(t,c){$('status').textContent=t;$('status').style.color=c||''}
fetch('/update/status').then(r=>r.json()).then(v=>{const n=x=>x>0?x:'—';
 $('versions').textContent=`Head unit app ${'$'}{n(v.apkRelease)} · dash ${'$'}{n(v.dashRelease)} · cluster ${'$'}{n(v.clusterDashRelease)} · speed limits ${'$'}{n(v.speedLimitsRelease)}`}).catch(()=>{});
fetch('/settings.json').then(r=>r.json()).then(j=>{s=j;show()}).catch(()=>status('Could not reach the head unit','var(--bad)'));
$('save').onclick=()=>{status('Saving…');fetch('/settings.json',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(collect())})
 .then(r=>r.ok?r.json():Promise.reject(r.status)).then(j=>{s=j;show();status('Saved — the cluster updates now','var(--ok)')})
 .catch(e=>status('Not saved ('+e+')','var(--bad)'))};
</script></body></html>"""
}
