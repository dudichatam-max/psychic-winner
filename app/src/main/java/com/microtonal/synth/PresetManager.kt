package com.microtonal.synth


import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


/**
 * Preset & Drum Kit persistence helpers
 * Extracted from the original MainActivity for cleaner structure.
 * All original logic is preserved exactly.
 */
object PresetManager {


    fun saveAllDrumKits(prefs: SharedPreferences, engine: SynthEngine) {
        val edit = prefs.edit()
        val n = engine.drumEngine.drumTrackCount
        for (k in 0 until 8) {
            val kit = engine.drumEngine.kits[k]
            edit.putString("drum_kit" + k + "_name", kit.name)
            for (i in 0 until 8) {
                val p = kit.patterns[i]
                val gridStr = buildString {
                    for (t in 0 until n) {
                        val row = if (t < p.grid.size) p.grid[t] else BooleanArray(16)
                        for (s in 0..15) {
                            append(if (row[s]) '1' else '0')
                        }
                    }
                }
                edit.putString("drum_kit" + k + "_p" + i + "_grid", gridStr)
                edit.putFloat("drum_kit" + k + "_p" + i + "_bpm", p.bpm)
                edit.putFloat("drum_kit" + k + "_p" + i + "_master", p.masterVolume)
                for (t in 0 until n) {
                    val vol = if (t < p.trackVolumes.size) p.trackVolumes[t] else 1.0f
                    val pan = if (t < p.trackPans.size) p.trackPans[t] else 0f
                    edit.putFloat("drum_kit" + k + "_p" + i + "_tv" + t, vol)
                    edit.putFloat("drum_kit" + k + "_p" + i + "_tp" + t, pan)
                }
                edit.putInt("drum_kit" + k + "_p" + i + "_rep", p.repeatCount)
            }
        }
        edit.putInt("drum_current_kit", engine.drumEngine.currentKitIndex)
        edit.putInt("drum_current_pattern", engine.drumEngine.currentPatternIndex)
        edit.apply()
    }


    /**
     * Persist only which kit + pattern slot is active.
     * Does not overwrite pattern/grid content — used after "טען סגנון"
     * so leaving the DRUM tab does not revert to a previously saved style.
     */
    fun saveCurrentDrumSelection(prefs: SharedPreferences, engine: SynthEngine) {
        prefs.edit()
            .putInt("drum_current_kit", engine.drumEngine.currentKitIndex)
            .putInt("drum_current_pattern", engine.drumEngine.currentPatternIndex)
            .apply()
    }


    fun loadAllDrumKits(prefs: SharedPreferences, engine: SynthEngine) {
        val n = engine.drumEngine.drumTrackCount
        for (k in 0 until 8) {
            val name = prefs.getString("drum_kit" + k + "_name", "סגנון " + (k + 1)) ?: ("סגנון " + (k + 1))
            engine.drumEngine.kits[k].name = name
            for (i in 0 until 8) {
                val gridStr = prefs.getString("drum_kit" + k + "_p" + i + "_grid", null)
                if (gridStr != null && (gridStr.length == 64 || gridStr.length == n * 16)) {
                    val storedTracks = gridStr.length / 16
                    val g = Array(n) { BooleanArray(16) }
                    var idx = 0
                    for (t in 0 until storedTracks) {
                        for (s in 0..15) {
                            g[t][s] = gridStr[idx++] == '1'
                        }
                    }
                    val bpm = prefs.getFloat("drum_kit" + k + "_p" + i + "_bpm", 120f)
                    val master = prefs.getFloat("drum_kit" + k + "_p" + i + "_master", 0.8f)
                    val tvs = FloatArray(n) { t ->
                        prefs.getFloat("drum_kit" + k + "_p" + i + "_tv" + t, 1.0f)
                    }
                    val tps = FloatArray(n) { t ->
                        prefs.getFloat("drum_kit" + k + "_p" + i + "_tp" + t, 0f)
                    }
                    val rep = prefs.getInt("drum_kit" + k + "_p" + i + "_rep", 0).let { r ->
                        when {
                            r < 0 -> -1
                            r > 10 -> 10
                            else -> r
                        }
                    }
                    engine.drumEngine.kits[k].patterns[i] = DrumEngine.DrumPattern(g, bpm, master, tvs, tps, rep)
                }
            }
        }
        val curKit = prefs.getInt("drum_current_kit", 0).coerceIn(0, 7)
        engine.drumEngine.currentKitIndex = curKit
        val curPattern = prefs.getInt("drum_current_pattern", 0).coerceIn(0, 7)
        engine.drumEngine.currentPatternIndex = curPattern
        for (i in 0 until 8) {
            engine.drumEngine.patternRepeat[i] = engine.drumEngine.kits[curKit].patterns[i].repeatCount
        }
    }

    private fun sessionDir(context: Context): File {
        val dir = File(context.filesDir, "console_sessions")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun safeSessionName(name: String): String =
        name.trim().ifEmpty { "סשן" }.replace('/', '_').replace('\\', '_')

    fun sessionFolder(context: Context, name: String): File =
        File(sessionDir(context), safeSessionName(name))

    fun listSessions(context: Context): List<String> {
        val dir = sessionDir(context)
        val names = linkedSetOf<String>()
        val files = dir.listFiles() ?: return emptyList()
        for (f in files) {
            if (f.isFile && f.name.endsWith(".json")) {
                names.add(f.name.removeSuffix(".json"))
            } else if (f.isDirectory && File(f, "console.json").exists()) {
                names.add(f.name)
            }
        }
        return names.sorted()
    }

    fun saveSession(context: Context, name: String, json: JSONObject): Boolean {
        return try {
            val safe = safeSessionName(name)
            val folder = File(sessionDir(context), safe)
            if (!folder.exists()) folder.mkdirs()
            File(folder, "console.json").writeText(json.toString())
            File(sessionDir(context), "$safe.json").writeText(json.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadSessionJson(context: Context, name: String): JSONObject? {
        return try {
            val safe = safeSessionName(name)
            val bundled = File(sessionDir(context), "$safe/console.json")
            val legacy = File(sessionDir(context), "$safe.json")
            val f = when {
                bundled.exists() -> bundled
                legacy.exists() -> legacy
                else -> return null
            }
            JSONObject(f.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun exportAllSessions(context: Context, uri: Uri): Boolean {
        return try {
            val root = sessionDir(context)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    zipSessionTree(root, root, zos)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importAllSessions(context: Context, uri: Uri): Boolean {
        return try {
            val root = sessionDir(context)
            val rootCanon = root.canonicalFile
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val raw = entry.name.replace('\\', '/').trimStart('/')
                            if (raw.isNotEmpty() && !raw.contains("..")) {
                                val out = File(root, raw).canonicalFile
                                if (out.path.startsWith(rootCanon.path)) {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun zipSessionTree(root: File, current: File, zos: ZipOutputStream) {
        val children = current.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                zipSessionTree(root, child, zos)
            } else if (child.isFile) {
                val rel = child.relativeTo(root).path.replace('\\', '/')
                if (rel.isEmpty()) continue
                zos.putNextEntry(ZipEntry(rel))
                child.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
