package com.microtonal.synth

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Device-independent description of the user's real performance.
 * The reference contains semantic input events and the audio assets needed
 * to reproduce the same workload on another APK/device.
 */
data class BenchmarkReferenceSettings(
    val waveform: Int,
    val volume: Float,
    val attack: Float,
    val decay: Float,
    val sustain: Float,
    val release: Float,
    val drive: Float,
    val reverb: Float,
    val cutoff: Float,
    val resonance: Float,
    val echo: Float,
    val glide: Float,
    val octave: Int,
    val detune: Boolean,
    val sub: Boolean,
    val warm: Boolean,
    val vibe: Boolean,
    val rip: Boolean,
    val fuzz: Boolean,
    val phaz: Boolean,
    val piano: Boolean,
    val div2: Boolean,
    val div3: Boolean,
    val div4: Boolean,
    val padTargetKey: Boolean,
    val padTargetMic: Boolean,
    val padTargetLoop: Boolean,
    val padTargetDrum: Boolean,
    val padWah: Float,
    val padOct: Float,
    val padCho: Float,
    val busStutterDiv: Int,
    val busHoldStop: Boolean,
    val drumStutterDiv: Int,
    val drumHoldStop: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("waveform", waveform); put("volume", volume.toDouble()); put("attack", attack.toDouble())
        put("decay", decay.toDouble()); put("sustain", sustain.toDouble()); put("release", release.toDouble())
        put("drive", drive.toDouble()); put("reverb", reverb.toDouble()); put("cutoff", cutoff.toDouble())
        put("resonance", resonance.toDouble()); put("echo", echo.toDouble()); put("glide", glide.toDouble())
        put("octave", octave); put("detune", detune); put("sub", sub); put("warm", warm)
        put("vibe", vibe); put("rip", rip); put("fuzz", fuzz); put("phaz", phaz); put("piano", piano)
        put("div2", div2); put("div3", div3); put("div4", div4)
        put("padTargetKey", padTargetKey); put("padTargetMic", padTargetMic)
        put("padTargetLoop", padTargetLoop); put("padTargetDrum", padTargetDrum)
        put("padWah", padWah.toDouble()); put("padOct", padOct.toDouble()); put("padCho", padCho.toDouble())
        put("busStutterDiv", busStutterDiv); put("busHoldStop", busHoldStop); put("drumStutterDiv", drumStutterDiv); put("drumHoldStop", drumHoldStop)
    }

    companion object {
        fun fromJson(o: JSONObject) = BenchmarkReferenceSettings(
            o.optInt("waveform", 3), o.optDouble("volume", .5).toFloat(),
            o.optDouble("attack", 15.0).toFloat(), o.optDouble("decay", 50.0).toFloat(),
            o.optDouble("sustain", .8).toFloat(), o.optDouble("release", 200.0).toFloat(),
            o.optDouble("drive", .35).toFloat(), o.optDouble("reverb", 0.0).toFloat(),
            o.optDouble("cutoff", 5000.0).toFloat(), o.optDouble("resonance", .3).toFloat(),
            o.optDouble("echo", .25).toFloat(), o.optDouble("glide", 30.0).toFloat(),
            o.optInt("octave", 0), o.optBoolean("detune"), o.optBoolean("sub"), o.optBoolean("warm"),
            o.optBoolean("vibe"), o.optBoolean("rip"), o.optBoolean("fuzz"), o.optBoolean("phaz"),
            o.optBoolean("piano"), o.optBoolean("div2"), o.optBoolean("div3"), o.optBoolean("div4"),
            o.optBoolean("padTargetKey", true), o.optBoolean("padTargetMic"),
            o.optBoolean("padTargetLoop"), o.optBoolean("padTargetDrum"),
            o.optDouble("padWah").toFloat(), o.optDouble("padOct").toFloat(), o.optDouble("padCho").toFloat(),
            o.optInt("busStutterDiv", 0), o.optBoolean("busHoldStop"), o.optInt("drumStutterDiv", 0), o.optBoolean("drumHoldStop")
        )
    }
}

enum class BenchmarkReferenceEventType { NOTE_ON, NOTE_OFF, PAD, LOOPER, DRUM_STATE, STATE }

data class BenchmarkReferenceEvent(
    val timeUs: Long,
    val type: BenchmarkReferenceEventType,
    val valueA: Float = 0f,
    val valueB: Float = 0f,
    val intA: Int = 0,
    val intB: Int = 0,
    val touched: Boolean = false,
    val payload: String? = null,
    val settings: BenchmarkReferenceSettings
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeUs", timeUs); put("type", type.name); put("a", valueA.toDouble()); put("b", valueB.toDouble())
        put("ia", intA); put("ib", intB); put("touched", touched); if (payload != null) put("payload", payload); put("settings", settings.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = BenchmarkReferenceEvent(
            o.optLong("timeUs", 0L),
            BenchmarkReferenceEventType.valueOf(o.optString("type")),
            o.optDouble("a", 0.0).toFloat(), o.optDouble("b", 0.0).toFloat(),
            o.optInt("ia", 0), o.optInt("ib", 0), o.optBoolean("touched", false), o.optString("payload", "").ifEmpty { null },
            BenchmarkReferenceSettings.fromJson(o.optJSONObject("settings") ?: JSONObject())
        )
    }
}

data class BenchmarkDrumState(
    val isPlaying: Boolean,
    val pattern: Int,
    val bpm: Float,
    val masterVolume: Float,
    val swing: Float,
    val grid: Array<BooleanArray>,
    val volumes: FloatArray,
    val pans: FloatArray,
    val samples: Array<FloatArray?>
)

data class BenchmarkLooperAsset(
    val track: Int,
    val samples: FloatArray,
    val volume: Float,
    val pan: Float,
    val wasPlaying: Boolean,
    val playPos: Int
)

data class BenchmarkLooperRecordingSegment(
    val track: Int,
    val startUs: Long,
    val samples: FloatArray
)

data class BenchmarkReferenceSession(
    val referenceId: String,
    val sampleRate: Int,
    val durationUs: Long,
    val events: List<BenchmarkReferenceEvent>,
    val initialLoops: List<BenchmarkLooperAsset>,
    val loops: List<BenchmarkLooperAsset>,
    val recordings: List<BenchmarkLooperRecordingSegment>,
    val drums: BenchmarkDrumState?
)

/** Captures real user actions without touching the audio callback. */
class BenchmarkReferenceRecorder(private val engine: SynthEngine) {
    @Volatile private var active = false
    private var startNs = 0L
    private val events = ArrayList<BenchmarkReferenceEvent>(4096)
    private val touchedLoops = LinkedHashSet<Int>()
    private val recordingStartsUs = HashMap<Int, Long>()
    private val recordings = ArrayList<BenchmarkLooperRecordingSegment>()
    private var initialLoops: List<BenchmarkLooperAsset> = emptyList()
    private var watcher: Thread? = null

    fun isRecording(): Boolean = active

    fun start() {
        synchronized(this) {
            events.clear()
            touchedLoops.clear()
            recordingStartsUs.clear()
            recordings.clear()
            initialLoops = captureLooperAssets()
            startNs = SystemClock.elapsedRealtimeNanos()
            active = true
            add(BenchmarkReferenceEventType.DRUM_STATE, payload = captureDrumStateJson())
            val initialSettings = snapshotSettings()
            watcher = Thread {
                var previous = initialSettings
                while (active) {
                    try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    if (!active) break
                    val now = snapshotSettings()
                    if (now != previous) {
                        add(BenchmarkReferenceEventType.STATE)
                        previous = now
                    }
                }
            }.apply { name = "BenchmarkReferenceStateWatcher" }.also { it.start() }
            for (i in 0 until minOf(6, engine.looperTracks.size)) {
                val t = engine.looperTracks[i]
                if (t.copySamples().isNotEmpty()) {
                    touchedLoops += i
                    add(BenchmarkReferenceEventType.LOOPER, intA = i, intB = if (t.isPlaying) 3 else 4)
                }
            }
        }
    }

    fun stop(): BenchmarkReferenceSession? {
        synchronized(this) {
            if (!active) return null
            active = false
            watcher?.interrupt()
            watcher = null
            val durationUs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L).coerceAtLeast(0L)
            val loops = captureLooperAssets()
            for ((track, startUs) in recordingStartsUs) {
                val samples = engine.looperTracks.getOrNull(track)?.copySamples() ?: FloatArray(0)
                if (samples.isNotEmpty()) recordings += BenchmarkLooperRecordingSegment(track, startUs, samples)
            }
            recordingStartsUs.clear()
            val drums = if (events.any { it.type == BenchmarkReferenceEventType.DRUM_STATE }) captureDrums() else null
            return BenchmarkReferenceSession(
                referenceId = "ref-${System.currentTimeMillis()}",
                sampleRate = engine.sampleRate,
                durationUs = durationUs,
                events = events.sortedBy { it.timeUs }.toList(),
                initialLoops = initialLoops,
                loops = loops,
                recordings = recordings.toList(),
                drums = drums
            )
        }
    }

    fun recordNote(on: Boolean, freq: Float) {
        if (!active) return
        add(BenchmarkReferenceEventType.values()[if (on) 0 else 1], valueA = freq)
    }

    fun recordPad(x: Float, y: Float, touched: Boolean) {
        if (!active) return
        add(BenchmarkReferenceEventType.PAD, valueA = x, valueB = y, touched = touched)
    }

    fun recordLooper(track: Int, action: Int) {
        if (!active) return
        touchedLoops += track
        val timeUs = currentTimeUs()
        when (action) {
            1 -> recordingStartsUs[track] = timeUs
            2 -> {
                val startUs = recordingStartsUs.remove(track)
                if (startUs != null) {
                    val samples = engine.looperTracks.getOrNull(track)?.copySamples() ?: FloatArray(0)
                    if (samples.isNotEmpty()) recordings += BenchmarkLooperRecordingSegment(track, startUs, samples)
                }
            }
        }
        addAt(BenchmarkReferenceEventType.LOOPER, timeUs, intA = track, intB = action)
    }

    fun recordDrumState() {
        if (!active) return
        add(BenchmarkReferenceEventType.DRUM_STATE, payload = captureDrumStateJson())
    }

    private fun currentTimeUs(): Long = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L).coerceAtLeast(0L)

    private fun add(type: BenchmarkReferenceEventType, valueA: Float = 0f, valueB: Float = 0f, intA: Int = 0, intB: Int = 0, touched: Boolean = false, payload: String? = null) {
        addAt(type, currentTimeUs(), valueA, valueB, intA, intB, touched, payload)
    }

    private fun addAt(type: BenchmarkReferenceEventType, timeUs: Long, valueA: Float = 0f, valueB: Float = 0f, intA: Int = 0, intB: Int = 0, touched: Boolean = false, payload: String? = null) {
        synchronized(this) {
            if (!active) return
            events += BenchmarkReferenceEvent(timeUs, type, valueA, valueB, intA, intB, touched, payload, snapshotSettings())
        }
    }

    private fun captureLooperAssets(): List<BenchmarkLooperAsset> = buildList {
        for (track in 0 until minOf(6, engine.looperTracks.size)) {
            val t = engine.looperTracks.getOrNull(track) ?: continue
            val samples = t.copySamples()
            if (samples.isNotEmpty()) add(BenchmarkLooperAsset(track, samples, t.volume, t.pan, t.isPlaying, t.playPos))
        }
    }

    private fun snapshotSettings() = BenchmarkReferenceSettings(
        engine.waveformType, engine.volume, engine.attackMs, engine.decayMs, engine.sustainLevel,
        engine.releaseMs, engine.driveAmount, engine.reverbMix, engine.cutoffFreq, engine.resonance,
        engine.echoMix, engine.glideMs, engine.octaveShift, engine.detuneOn, engine.subOn, engine.warmOn,
        engine.vibeOn, engine.ripOn, engine.fuzzOn, engine.phazOn, engine.pianoOn,
        engine.div2On, engine.div3On, engine.div4On, engine.padTargetKey, engine.padTargetMic,
        engine.padTargetLoop, engine.padTargetDrum, engine.padWah, engine.padOct, engine.padCho,
        engine.busStutterDiv, engine.busHoldStop, engine.drumStutterDiv, engine.drumHoldStop
    )

    private fun captureDrumStateJson(): String {
        val d = engine.drumEngine
        return JSONObject().apply {
            put("isPlaying", d.isPlaying); put("pattern", d.currentPatternIndex); put("bpm", d.bpm.toDouble())
            put("masterVolume", d.masterVolume.toDouble()); put("swing", d.swing.toDouble())
            val g = JSONArray(); for (t in 0 until 8) { val row = JSONArray(); for (v in d.grid[t]) row.put(v); g.put(row) }; put("grid", g)
            val v = JSONArray(); d.trackVolumes.forEach { v.put(it.toDouble()) }; put("volumes", v)
            val p = JSONArray(); for (i in 0 until 8) p.put(d.trackPans[i].toDouble()); put("pans", p)
        }.toString()
    }

    private fun captureDrums(): BenchmarkDrumState {
        val d = engine.drumEngine
        return BenchmarkDrumState(
            d.isPlaying, d.currentPatternIndex, d.bpm, d.masterVolume, d.swing,
            Array(8) { d.grid[it].copyOf() }, d.trackVolumes.copyOf(),
            FloatArray(8) { d.trackPans[it] }, Array(8) { d.drumSamples[it] }
        )
    }
}

object BenchmarkReferenceIO {
    private const val FORMAT = 1

    fun exportToUri(context: Context, uri: Uri, session: BenchmarkReferenceSession): Boolean {
        return try {
            val output = context.contentResolver.openOutputStream(uri) ?: return false
            output.use { writeZip(it, session) }
            true
        } catch (_: Throwable) { false }
    }

    fun importFromUri(context: Context, uri: Uri): BenchmarkReferenceSession? = try {
        context.contentResolver.openInputStream(uri)?.use { readZip(it) }
    } catch (_: Throwable) { null }

    fun saveInternal(context: Context, session: BenchmarkReferenceSession): Boolean = try {
        val dir = File(context.filesDir, "benchmark")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "reference.zip").outputStream().use { writeZip(it, session) }
        true
    } catch (_: Throwable) { false }

    fun loadInternal(context: Context): BenchmarkReferenceSession? = try {
        val file = File(context.filesDir, "benchmark/reference.zip")
        if (!file.exists()) null else file.inputStream().use { readZip(it) }
    } catch (_: Throwable) { null }

    private fun writeZip(output: OutputStream, s: BenchmarkReferenceSession) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val manifest = JSONObject().apply {
                put("format", FORMAT); put("referenceId", s.referenceId); put("sampleRate", s.sampleRate)
                put("durationUs", s.durationUs); put("eventCount", s.events.size); put("initialLoopCount", s.initialLoops.size); put("loopCount", s.loops.size); put("recordingCount", s.recordings.size)
                put("hasDrums", s.drums != null)
            }
            putText(zip, "manifest.json", manifest.toString())
            val events = JSONArray(); s.events.forEach { events.put(it.toJson()) }
            putText(zip, "events.json", events.toString())
            s.initialLoops.forEach { putFloatArray(zip, "initial-loops/track${it.track}.f32", it.samples) }
            val initialLoopsMeta = JSONArray()
            s.initialLoops.forEach { initialLoopsMeta.put(JSONObject().apply {
                put("track", it.track); put("volume", it.volume.toDouble()); put("pan", it.pan.toDouble())
                put("wasPlaying", it.wasPlaying); put("playPos", it.playPos); put("samples", it.samples.size)
            }) }
            putText(zip, "initial-loops.json", initialLoopsMeta.toString())
            s.loops.forEach { putFloatArray(zip, "loops/track${it.track}.f32", it.samples) }
            val loopsMeta = JSONArray()
            s.loops.forEach { loopsMeta.put(JSONObject().apply {
                put("track", it.track); put("volume", it.volume.toDouble()); put("pan", it.pan.toDouble())
                put("wasPlaying", it.wasPlaying); put("playPos", it.playPos); put("samples", it.samples.size)
            }) }
            putText(zip, "loops.json", loopsMeta.toString())
            val recordingsMeta = JSONArray()
            s.recordings.forEachIndexed { index, r ->
                recordingsMeta.put(JSONObject().apply { put("index", index); put("track", r.track); put("startUs", r.startUs); put("samples", r.samples.size) })
                putFloatArray(zip, "recordings/${index}.f32", r.samples)
            }
            putText(zip, "recordings.json", recordingsMeta.toString())
            s.drums?.let { putDrums(zip, it) }
        }
    }

    private fun putDrums(zip: ZipOutputStream, d: BenchmarkDrumState) {
        val meta = JSONObject().apply {
            put("isPlaying", d.isPlaying); put("pattern", d.pattern); put("bpm", d.bpm.toDouble())
            put("masterVolume", d.masterVolume.toDouble()); put("swing", d.swing.toDouble())
            val g = JSONArray(); for (t in 0 until 8) { val row = JSONArray(); for (v in d.grid[t]) row.put(v); g.put(row) }; put("grid", g)
            val v = JSONArray(); d.volumes.forEach { v.put(it.toDouble()) }; put("volumes", v)
            val p = JSONArray(); d.pans.forEach { p.put(it.toDouble()) }; put("pans", p)
        }
        putText(zip, "drums.json", meta.toString())
        for (i in d.samples.indices) d.samples[i]?.let { putFloatArray(zip, "drums/track$i.f32", it) }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }

    private fun putFloatArray(zip: ZipOutputStream, name: String, data: FloatArray) {
        zip.putNextEntry(ZipEntry(name))
        val out = DataOutputStream(zip)
        out.writeInt(Integer.reverseBytes(data.size))
        for (v in data) out.writeInt(Integer.reverseBytes(v.toRawBits()))
        out.flush()
        zip.closeEntry()
    }

    private fun readZip(input: InputStream): BenchmarkReferenceSession {
        var manifest: JSONObject? = null
        var events: List<BenchmarkReferenceEvent> = emptyList()
        var initialLoopsMeta = JSONArray()
        var loopsMeta = JSONArray()
        var recordingsMeta = JSONArray()
        val initialLoopData = HashMap<Int, FloatArray>()
        val loopData = HashMap<Int, FloatArray>()
        val recordingData = HashMap<Int, FloatArray>()
        var drumsMeta: JSONObject? = null
        val drumData = HashMap<Int, FloatArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == "events.json" -> {
                        val a = JSONArray(zip.readBytes().toString(Charsets.UTF_8)); events = List(a.length()) { BenchmarkReferenceEvent.fromJson(a.getJSONObject(it)) }
                    }
                    e.name == "initial-loops.json" -> initialLoopsMeta = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == "loops.json" -> loopsMeta = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == "recordings.json" -> recordingsMeta = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                    e.name == "drums.json" -> drumsMeta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    e.name.startsWith("initial-loops/track") && e.name.endsWith(".f32") -> initialLoopData[trackFromName(e.name, "initial-loops/track")] = readFloatArray(zip)
                    e.name.startsWith("loops/track") && e.name.endsWith(".f32") -> loopData[trackFromName(e.name, "loops/track")] = readFloatArray(zip)
                    e.name.startsWith("recordings/") && e.name.endsWith(".f32") -> recordingData[e.name.removePrefix("recordings/").removeSuffix(".f32").toInt()] = readFloatArray(zip)
                    e.name.startsWith("drums/track") && e.name.endsWith(".f32") -> drumData[trackFromName(e.name, "drums/track")] = readFloatArray(zip)
                }
                zip.closeEntry()
            }
        }
        val m = manifest ?: error("Missing manifest")
        require(m.optInt("format", -1) == FORMAT) { "Unsupported reference format" }
        fun readLoopAssets(meta: JSONArray, data: Map<Int, FloatArray>): List<BenchmarkLooperAsset> = buildList {
            for (i in 0 until meta.length()) {
                val o = meta.getJSONObject(i); val track = o.getInt("track")
                add(BenchmarkLooperAsset(track, data[track] ?: FloatArray(0), o.optDouble("volume", 1.0).toFloat(), o.optDouble("pan", 0.0).toFloat(), o.optBoolean("wasPlaying"), o.optInt("playPos")))
            }
        }
        val initialLoops = readLoopAssets(initialLoopsMeta, initialLoopData)
        val loops = readLoopAssets(loopsMeta, loopData)
        val recordings = buildList {
            for (i in 0 until recordingsMeta.length()) {
                val o = recordingsMeta.getJSONObject(i); val index = o.getInt("index")
                add(BenchmarkLooperRecordingSegment(o.getInt("track"), o.getLong("startUs"), recordingData[index] ?: FloatArray(0)))
            }
        }
        val drums = drumsMeta?.let { o ->
            val gJson = o.optJSONArray("grid")
            val grid = Array(8) { t -> BooleanArray(16) { s -> gJson?.optJSONArray(t)?.optBoolean(s, false) ?: false } }
            val v = FloatArray(8) { o.optJSONArray("volumes")?.optDouble(it, 1.0)?.toFloat() ?: 1.0f }
            val p = FloatArray(8) { o.optJSONArray("pans")?.optDouble(it, 0.0)?.toFloat() ?: 0.0f }
            BenchmarkDrumState(o.optBoolean("isPlaying"), o.optInt("pattern"), o.optDouble("bpm", 120.0).toFloat(), o.optDouble("masterVolume", .8).toFloat(), o.optDouble("swing").toFloat(), grid, v, p, Array(8) { drumData[it] })
        }
        return BenchmarkReferenceSession(m.getString("referenceId"), m.optInt("sampleRate", 0), m.optLong("durationUs", 0L), events.sortedBy { it.timeUs }, initialLoops, loops, recordings, drums)
    }

    private fun trackFromName(name: String, prefix: String): Int = name.removePrefix(prefix).removeSuffix(".f32").toInt()
    private fun readFloatArray(input: InputStream): FloatArray {
        val data = DataInputStream(input)
        val n = Integer.reverseBytes(data.readInt()).coerceIn(0, 50_000_000)
        val out = FloatArray(n)
        for (i in out.indices) out[i] = Float.fromBits(Integer.reverseBytes(data.readInt()))
        return out
    }
}

/** Replays a reference without running sleeps/busy loops on the audio thread. */
class BenchmarkReferencePlayer(private val engine: SynthEngine) {
    private val running = AtomicBoolean(false)
    private val recordingFeeds = HashMap<Int, FloatArray>()
    private val recordingFeedPos = HashMap<Int, Int>()

    fun cancel() { running.set(false); synchronized(recordingFeeds) { recordingFeeds.clear(); recordingFeedPos.clear() } }

    fun consumeLooperRecordingSample(track: Int, fallback: Float): Float {
        synchronized(recordingFeeds) {
            val data = recordingFeeds[track] ?: return fallback
            val pos = recordingFeedPos[track] ?: 0
            if (pos >= data.size) return 0f
            val sample = data[pos]
            recordingFeedPos[track] = pos + 1
            return sample
        }
    }

    fun play(session: BenchmarkReferenceSession, includeLooper: Boolean, includeDrums: Boolean) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try { playBlockingInternal(session, includeLooper, includeDrums) } finally { running.set(false) }
        }.apply { name = "BenchmarkReferenceReplay" }.start()
    }

    fun playBlocking(session: BenchmarkReferenceSession, includeLooper: Boolean, includeDrums: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try { playBlockingInternal(session, includeLooper, includeDrums) } finally { running.set(false) }
    }

    private fun playBlockingInternal(session: BenchmarkReferenceSession, includeLooper: Boolean, includeDrums: Boolean) {
        prepare(session, includeLooper, includeDrums)
        val start = SystemClock.elapsedRealtimeNanos()
        for (event in session.events) {
            if (!running.get()) break
            val deadline = start + event.timeUs * 1_000L
            while (running.get()) {
                val left = deadline - SystemClock.elapsedRealtimeNanos()
                if (left <= 0L) break
                LockSupport.parkNanos(left.coerceAtMost(5_000_000L))
            }
            if (!running.get()) break
            applySettings(event.settings)
            apply(event, includeLooper, includeDrums)
        }
        engine.busPadTouched = false
    }

    private fun prepare(s: BenchmarkReferenceSession, includeLooper: Boolean, includeDrums: Boolean) {
        synchronized(recordingFeeds) { recordingFeeds.clear(); recordingFeedPos.clear() }
        if (includeLooper) {
            for (i in 0 until 6) engine.looperTracks[i].clear()
            for (asset in s.initialLoops) {
                val t = engine.looperTracks.getOrNull(asset.track) ?: continue
                t.loadFromSamples(resample(asset.samples, s.sampleRate, engine.sampleRate))
                t.volume = asset.volume
                t.applyPan(asset.pan)
                t.playPos = asset.playPos.coerceIn(0, (t.length - 1).coerceAtLeast(0))
                if (asset.wasPlaying) t.startPlayback()
            }
        }
        if (includeDrums && s.drums != null) applyDrums(s.drums, s.sampleRate)
    }

    private fun apply(event: BenchmarkReferenceEvent, includeLooper: Boolean, includeDrums: Boolean) {
        when (event.type) {
            BenchmarkReferenceEventType.NOTE_ON -> engine.noteOn(event.valueA)
            BenchmarkReferenceEventType.NOTE_OFF -> engine.noteOff(event.valueA)
            BenchmarkReferenceEventType.PAD -> {
                engine.busPadX = event.valueA; engine.busPadY = event.valueB; engine.busPadTouched = event.touched
            }
            BenchmarkReferenceEventType.LOOPER -> if (includeLooper) {
                when (event.intB) {
                    1 -> {
                        synchronized(recordingFeeds) {
                            val segment = s.recordings
                                .filter { it.track == event.intA && it.startUs <= event.timeUs }
                                .minByOrNull { kotlin.math.abs(it.startUs - event.timeUs) }
                            recordingFeeds[event.intA] = segment?.let { resample(it.samples, s.sampleRate, engine.sampleRate) } ?: FloatArray(0)
                            recordingFeedPos[event.intA] = 0
                        }
                        engine.startTrackRecording(event.intA)
                    }
                    2 -> {
                        engine.stopTrackRecording(event.intA)
                        synchronized(recordingFeeds) { recordingFeeds.remove(event.intA); recordingFeedPos.remove(event.intA) }
                    }
                    3 -> engine.setTrackPlaying(event.intA, true)
                    4 -> engine.setTrackPlaying(event.intA, false)
                    5 -> engine.clearTrack(event.intA)
                }
            }
            BenchmarkReferenceEventType.DRUM_STATE -> if (includeDrums) event.payload?.let { applyDrumJson(JSONObject(it)) }
            BenchmarkReferenceEventType.STATE -> Unit
        }
    }

    private fun applySettings(s: BenchmarkReferenceSettings) {
        engine.waveformType = s.waveform; engine.volume = s.volume; engine.attackMs = s.attack; engine.decayMs = s.decay
        engine.sustainLevel = s.sustain; engine.releaseMs = s.release; engine.driveAmount = s.drive; engine.reverbMix = s.reverb
        engine.cutoffFreq = s.cutoff; engine.resonance = s.resonance; engine.echoMix = s.echo; engine.glideMs = s.glide
        engine.octaveShift = s.octave; engine.detuneOn = s.detune; engine.subOn = s.sub; engine.warmOn = s.warm
        engine.vibeOn = s.vibe; engine.ripOn = s.rip; engine.fuzzOn = s.fuzz; engine.phazOn = s.phaz; engine.pianoOn = s.piano
        engine.div2On = s.div2; engine.div3On = s.div3; engine.div4On = s.div4
        engine.padTargetKey = s.padTargetKey; engine.padTargetMic = s.padTargetMic; engine.padTargetLoop = s.padTargetLoop; engine.padTargetDrum = s.padTargetDrum
        engine.padWah = s.padWah; engine.padOct = s.padOct; engine.padCho = s.padCho
        engine.busStutterDiv = s.busStutterDiv; engine.busHoldStop = s.busHoldStop; engine.drumStutterDiv = s.drumStutterDiv; engine.drumHoldStop = s.drumHoldStop
    }

    private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty() || sourceRate <= 0 || sourceRate == targetRate) return input
        val outN = ((input.size.toLong() * targetRate.toLong()) / sourceRate.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (outN <= 1) return floatArrayOf(input[0])
        val out = FloatArray(outN)
        val scale = (input.size - 1).toDouble() / (outN - 1).toDouble()
        for (i in out.indices) {
            val pos = i * scale
            val a = pos.toInt().coerceIn(0, input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val f = (pos - a).toFloat()
            out[i] = input[a] + (input[b] - input[a]) * f
        }
        return out
    }

    private fun applyDrumJson(o: JSONObject) {
        val de = engine.drumEngine
        de.stopAndRewind(); de.bpm = o.optDouble("bpm", 120.0).toFloat(); de.masterVolume = o.optDouble("masterVolume", .8).toFloat()
        de.swing = o.optDouble("swing", 0.0).toFloat(); de.currentPatternIndex = o.optInt("pattern", 0)
        val g = o.optJSONArray("grid"); val v = o.optJSONArray("volumes"); val p = o.optJSONArray("pans")
        for (t in 0 until 8) {
            val row = g?.optJSONArray(t); for (s in 0 until 16) de.grid[t][s] = row?.optBoolean(s, false) ?: false
            de.trackVolumes[t] = v?.optDouble(t, 1.0)?.toFloat() ?: 1.0f
            de.setTrackPan(t, p?.optDouble(t, 0.0)?.toFloat() ?: 0.0f)
        }
        if (o.optBoolean("isPlaying", false)) de.startFromBeginning()
    }

    private fun applyDrums(d: BenchmarkDrumState, sourceRate: Int) {
        val de = engine.drumEngine
        de.stopAndRewind(); de.bpm = d.bpm; de.masterVolume = d.masterVolume; de.swing = d.swing; de.currentPatternIndex = d.pattern
        for (t in 0 until 8) {
            d.samples[t]?.let { de.drumSamples[t] = resample(it, sourceRate, engine.sampleRate) }
            System.arraycopy(d.grid[t], 0, de.grid[t], 0, 16); de.trackVolumes[t] = d.volumes[t]; de.setTrackPan(t, d.pans[t])
        }
        if (d.isPlaying) de.startFromBeginning()
    }
}
