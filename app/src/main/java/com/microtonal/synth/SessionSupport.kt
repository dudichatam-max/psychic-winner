package com.microtonal.synth
 
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class SessionUi(
    val tab: String,
    val freqs: List<Float>?,
    val scaleId: String?,
    val wave: Int,
    val vol: Float,
    val attack: Float,
    val decay: Float,
    val sustain: Float,
    val release: Float,
    val drive: Float,
    val reverb: Float,
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
    val cutoff: Float,
    val res: Float,
    val echo: Float,
    val glide: Float,
    val octave: Int,
    val looperProject: String,
    val looperPage: Int,
    val loopNames: List<String>?,
    val drumExtras: Boolean,
    val micProject: String,
    val micPage: Int,
    val micNames: List<String>?,
    val micVols: List<Float>?,
    val micPans: List<Float>?,
    val micMonitor: Boolean,
    val micMonVol: Float,
    val micGain: Float
)

internal fun scalePcsFromId(id: String?): Set<Int>? {
    if (id == null) return null
    val names = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    val parts = id.split(" ")
    if (parts.size < 2) return null
    val root = names.indexOf(parts[0])
    if (root < 0) return null
    val steps = if (parts[1] == "min") intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
    return steps.map { (root + it) % 12 }.toSet()
}

internal fun buildConsoleSessionJson(
    engine: SynthEngine,
    selectedTab: String,
    frequencies: List<Float>,
    activeScaleId: String?,
    currentWave: Int,
    vol: Float,
    attackVal: Float,
    decayVal: Float,
    sustainVal: Float,
    releaseVal: Float,
    driveVal: Float,
    reverbVal: Float,
    detuneOn: Boolean,
    subOn: Boolean,
    warmOn: Boolean,
    vibeOn: Boolean,
    ripOn: Boolean,
    fuzzOn: Boolean,
    phazOn: Boolean,
    pianoOn: Boolean,
    div2On: Boolean,
    div3On: Boolean,
    div4On: Boolean,
    cutoffVal: Float,
    resVal: Float,
    echoVal: Float,
    glideVal: Float,
    currentOctave: Int,
    looperProjectName: String,
    looperPage: Int,
    loopChannelNames: List<String>,
    drumExtrasOpen: Boolean,
    micProjectName: String,
    micPage: Int,
    micNames: List<String>,
    micVols: List<Float>,
    micPans: List<Float>,
    micMonitorOn: Boolean,
    micMonitorVol: Float,
    micGain: Float
): JSONObject {
    val o = JSONObject()
    o.put("v", 1)
    o.put("tab", selectedTab)
    o.put("freqs", frequencies.joinToString(","))
    if (activeScaleId != null) o.put("scaleId", activeScaleId)
    o.put("wave", currentWave)
    o.put("vol", vol.toDouble())
    o.put("attack", attackVal.toDouble())
    o.put("decay", decayVal.toDouble())
    o.put("sustain", sustainVal.toDouble())
    o.put("release", releaseVal.toDouble())
    o.put("drive", driveVal.toDouble())
    o.put("reverb", reverbVal.toDouble())
    o.put("detune", detuneOn)
    o.put("sub", subOn)
    o.put("warm", warmOn)
    o.put("vibe", vibeOn)
    o.put("rip", ripOn)
    o.put("fuzz", fuzzOn)
    o.put("phaz", phazOn)
    o.put("piano", pianoOn)
    o.put("div2", div2On)
    o.put("div3", div3On)
    o.put("div4", div4On)
    o.put("cutoff", cutoffVal.toDouble())
    o.put("res", resVal.toDouble())
    o.put("echo", echoVal.toDouble())
    o.put("glide", glideVal.toDouble())
    o.put("octave", currentOctave)
    o.put("looperProject", looperProjectName)
    o.put("looperPage", looperPage)
    val names = JSONArray()
    for (n in loopChannelNames) names.put(n)
    o.put("loopNames", names)
    o.put("drumExtras", drumExtrasOpen)
    o.put("drumKit", engine.drumEngine.currentKitIndex)
    o.put("drumPattern", engine.drumEngine.currentPatternIndex)
    val reps = JSONArray()
    for (i in 0 until 8) reps.put(engine.drumEngine.patternRepeat[i])
    o.put("drumRepeats", reps)
    o.put("micProject", micProjectName)
    o.put("micPage", micPage)
    val mn = JSONArray()
    for (n in micNames) mn.put(n)
    o.put("micNames", mn)
    val mv = JSONArray(); for (v in micVols) mv.put(v.toDouble()); o.put("micVols", mv)
    val mp = JSONArray(); for (v in micPans) mp.put(v.toDouble()); o.put("micPans", mp)
    o.put("micMonitor", micMonitorOn)
    o.put("micMonVol", micMonitorVol.toDouble())
    o.put("micGain", micGain.toDouble())
    return o
}

internal fun parseSessionUi(o: JSONObject): SessionUi {
    val freqsStr = o.optString("freqs", "")
    val freqs = if (freqsStr.isNotEmpty()) freqsStr.split(",").mapNotNull { it.toFloatOrNull() } else null
    fun strs(key: String): List<String>? {
        val a = o.optJSONArray(key) ?: return null
        return List(a.length()) { a.optString(it, "") }
    }
    fun floats(key: String): List<Float>? {
        val a = o.optJSONArray(key) ?: return null
        return List(a.length()) { a.optDouble(it, 0.0).toFloat() }
    }
    val scale = o.optString("scaleId", "")
    return SessionUi(
        tab = o.optString("tab", "SOUND"),
        freqs = freqs,
        scaleId = scale.ifEmpty { null },
        wave = o.optInt("wave", 3),
        vol = o.optDouble("vol", 0.5).toFloat(),
        attack = o.optDouble("attack", 15.0).toFloat(),
        decay = o.optDouble("decay", 50.0).toFloat(),
        sustain = o.optDouble("sustain", 0.8).toFloat(),
        release = o.optDouble("release", 200.0).toFloat(),
        drive = o.optDouble("drive", 0.35).toFloat(),
        reverb = o.optDouble("reverb", 0.0).toFloat(),
        detune = o.optBoolean("detune", false),
        sub = o.optBoolean("sub", false),
        warm = o.optBoolean("warm", false),
        vibe = o.optBoolean("vibe", false),
        rip = o.optBoolean("rip", false),
        fuzz = o.optBoolean("fuzz", false),
        phaz = o.optBoolean("phaz", false),
        piano = o.optBoolean("piano", false),
        div2 = o.optBoolean("div2", false),
        div3 = o.optBoolean("div3", false),
        div4 = o.optBoolean("div4", false),
        cutoff = o.optDouble("cutoff", 5000.0).toFloat(),
        res = o.optDouble("res", 0.3).toFloat(),
        echo = o.optDouble("echo", 0.25).toFloat(),
        glide = o.optDouble("glide", 30.0).toFloat(),
        octave = o.optInt("octave", 0),
        looperProject = o.optString("looperProject", "פרויקט לופר"),
        looperPage = o.optInt("looperPage", 0),
        loopNames = strs("loopNames"),
        drumExtras = o.optBoolean("drumExtras", false),
        micProject = o.optString("micProject", "פרויקט מיק"),
        micPage = o.optInt("micPage", 0),
        micNames = strs("micNames"),
        micVols = floats("micVols"),
        micPans = floats("micPans"),
        micMonitor = o.optBoolean("micMonitor", false),
        micMonVol = o.optDouble("micMonVol", 1.0).toFloat(),
        micGain = o.optDouble("micGain", 2.0).toFloat()
    )
}

internal fun restoreEngineFromSession(
    engine: SynthEngine,
    context: Context,
    prefs: SharedPreferences,
    s: SessionUi,
    frequencies: MutableList<Float>,
    loopChannelNames: MutableList<String>,
    loopChannelVolStates: MutableList<Float>,
    loopChannelPanStates: MutableList<Float>,
    loopHasContent: MutableList<Boolean>,
    micNames: MutableList<String>,
    micVolStates: MutableList<Float>,
    micPanStates: MutableList<Float>
) {
    s.freqs?.let { list ->
        if (list.size == frequencies.size) list.forEachIndexed { i, f -> frequencies[i] = f }
    }
    engine.setLiveWaveform(s.wave)
    engine.volume = s.vol
    engine.attackMs = s.attack
    engine.decayMs = s.decay
    engine.sustainLevel = s.sustain
    engine.releaseMs = s.release
    engine.driveAmount = s.drive
    engine.reverbMix = s.reverb
    engine.detuneOn = s.detune
    engine.subOn = s.sub
    engine.warmOn = s.warm
    engine.vibeOn = s.vibe
    engine.ripOn = s.rip
    engine.fuzzOn = s.fuzz
    engine.phazOn = s.phaz
    engine.pianoOn = s.piano
    engine.div2On = s.div2
    engine.div3On = s.div3
    engine.div4On = s.div4
    engine.cutoffFreq = s.cutoff
    engine.resonance = s.res
    engine.echoMix = s.echo
    engine.glideMs = s.glide
    engine.octaveShift = s.octave
    s.loopNames?.forEachIndexed { i, n -> if (i < loopChannelNames.size) loopChannelNames[i] = n }
    val meta = engine.loadLooperSession()
    if (meta != null) {
        meta.channelNames.forEachIndexed { i, n -> if (i < loopChannelNames.size) loopChannelNames[i] = n }
        meta.volumes.forEachIndexed { i, v ->
            if (i < loopChannelVolStates.size) {
                loopChannelVolStates[i] = v
                engine.setTrackVolume(i, v)
            }
        }
        meta.pans.forEachIndexed { i, p ->
            if (i < loopChannelPanStates.size) {
                loopChannelPanStates[i] = p
                engine.setTrackPan(i, p)
            }
        }
    }
    for (i in 0 until loopHasContent.size) loopHasContent[i] = engine.trackHasContent(i)
    PresetManager.loadAllDrumKits(prefs, engine)
    engine.drumEngine.loadKit(engine.drumEngine.currentKitIndex, context, engine.drumEngine.currentPatternIndex)
    s.micNames?.forEachIndexed { i, n -> if (i < micNames.size) micNames[i] = n }
    s.micVols?.forEachIndexed { i, v ->
        if (i < micVolStates.size) {
            micVolStates[i] = v
            if (i < engine.micEngine.tracks.size) engine.micEngine.tracks[i].volume = v
            if (i < engine.micEngine.trackFx.size) engine.micEngine.trackFx[i].volume = v
        }
    }
    s.micPans?.forEachIndexed { i, p ->
        if (i < micPanStates.size) {
            micPanStates[i] = p
            if (i < engine.micEngine.trackFx.size) engine.micEngine.trackFx[i].applyPan(p)
        }
    }
    engine.micEngine.monitorEnabled = s.micMonitor
    engine.micEngine.monitorVolume = s.micMonVol
    engine.micEngine.inputGain = s.micGain
}
