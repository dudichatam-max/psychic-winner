package com.microtonal.synth




import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.microtonal.synth.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream




/**
 * Spartan Drum Machine + 8 Style/Kit system
 * Clean version – no redeclarations
 */
class DrumEngine(private val sampleRate: Int) {




    // ------------------------------------------------------------------
    // Core playback state
    // ------------------------------------------------------------------
    val drumSamples = arrayOfNulls<FloatArray>(4)
    val grid = Array(4) { BooleanArray(16) }
    val trackVolumes = FloatArray(4) { 1.0f }
    val trackNames = arrayOf("Kick", "Snare", "Hi-Hat", "Perc")




    @Volatile var masterVolume: Float = 0.8f
    @Volatile var bpm: Float = 120f
    /** Swing amount 0.0 (straight) .. 1.0 (max swing, ~66% feel). Affects timing of off-beats in real-time. */
    @Volatile var swing: Float = 0f
    @Volatile var isPlaying: Boolean = false




    @Volatile var currentStep: Int = 0
        private set




    private val playIndices = IntArray(4) { -1 }
    private var stepPhase = 0.0




    // ------------------------------------------------------------------
    // Pattern
    // ------------------------------------------------------------------
    data class DrumPattern(
        val grid: Array<BooleanArray> = Array(4) { BooleanArray(16) },
        val bpm: Float = 120f,
        val masterVolume: Float = 0.8f,
        val trackVolumes: FloatArray = FloatArray(4) { 1.0f }
    ) {
        fun deepCopy(): DrumPattern = DrumPattern(
            grid = Array(4) { t -> this.grid[t].copyOf() },
            bpm = this.bpm,
            masterVolume = this.masterVolume,
            trackVolumes = this.trackVolumes.copyOf()
        )
    }




    val patterns = Array(8) { DrumPattern() }
    @Volatile var currentPatternIndex: Int = 0




    // ------------------------------------------------------------------
    // 8 Kits / Styles
    // ------------------------------------------------------------------
    data class DrumKit(
        var name: String = "סגנון 1",
        val patterns: Array<DrumPattern> = Array(8) { DrumPattern() }
    )




    val kits = Array(8) { i -> DrumKit("סגנון " + (i + 1)) }
    @Volatile var currentKitIndex: Int = 0




    // ------------------------------------------------------------------
    // Pattern management
    // ------------------------------------------------------------------
    fun loadPattern(index: Int) {
        if (index !in 0 until 8) return
        val p = patterns[index]
        for (t in 0 until 4) {
            System.arraycopy(p.grid[t], 0, grid[t], 0, 16)
            trackVolumes[t] = p.trackVolumes[t]
        }
        bpm = p.bpm
        masterVolume = p.masterVolume
        currentPatternIndex = index
    }




    fun saveCurrentToPattern(index: Int = currentPatternIndex) {
        if (index !in 0 until 8) return
        val g = Array(4) { t -> grid[t].copyOf() }
        patterns[index] = DrumPattern(
            grid = g,
            bpm = bpm,
            masterVolume = masterVolume,
            trackVolumes = trackVolumes.copyOf()
        )
        currentPatternIndex = index




        // keep current kit in sync
        if (currentKitIndex in 0 until 8) {
            kits[currentKitIndex].patterns[index] = patterns[index].deepCopy()
        }
    }

    /**
     * Generates a musically coherent random 16-step pattern.
     * Uses constrained rules so the result always feels like a real drum groove
     * (kick on downbeats, snare on backbeats, hi-hat with sensible density, sparse perc).
     * Does NOT change BPM / volumes / swing – only the grid.
     */
    fun generateRandomLogicalPattern() {
        // Clear
        for (t in 0 until 4) {
            for (s in 0 until 16) grid[t][s] = false
        }

        val rnd = kotlin.random.Random.Default

        // ---- Kick (track 0) – solid foundation ----
        // Always hit on 1 and 5 (steps 0 & 8)
        grid[0][0] = true
        grid[0][8] = true
        // Occasional extra kicks on logical positions
        val kickExtras = listOf(2, 4, 6, 10, 12, 14)
        for (pos in kickExtras) {
            if (rnd.nextFloat() < 0.28f) grid[0][pos] = true
        }
        // Rare double on the "and" of 1
        if (rnd.nextFloat() < 0.18f) grid[0][1] = true

        // ---- Snare (track 1) – classic backbeat ----
        // Always on 2 and 4 (steps 4 & 12)
        grid[1][4] = true
        grid[1][12] = true
        // Occasional ghost notes / extra snares
        val snareGhosts = listOf(2, 6, 10, 14, 7, 15)
        for (pos in snareGhosts) {
            if (rnd.nextFloat() < 0.22f) grid[1][pos] = true
        }
        // Rare flam-ish on 3
        if (rnd.nextFloat() < 0.12f) grid[1][8] = true

        // ---- Hi-Hat (track 2) – density with musical variety ----
        val hatStyle = rnd.nextInt(4)
        when (hatStyle) {
            0 -> { // Straight 8ths
                for (s in 0 until 16 step 2) grid[2][s] = true
            }
            1 -> { // All 16ths with a few gaps
                for (s in 0 until 16) {
                    if (rnd.nextFloat() < 0.78f) grid[2][s] = true
                }
            }
            2 -> { // Off-beat focused (classic disco/house)
                for (s in 1 until 16 step 2) grid[2][s] = true
                // + some on-beats
                if (rnd.nextFloat() < 0.5f) grid[2][0] = true
                if (rnd.nextFloat() < 0.4f) grid[2][8] = true
            }
            else -> { // Sparse / open hats
                val sparse = listOf(0, 2, 4, 6, 8, 10, 12, 14)
                for (pos in sparse) {
                    if (rnd.nextFloat() < 0.65f) grid[2][pos] = true
                }
                // occasional open on off-beats
                if (rnd.nextFloat() < 0.35f) grid[2][3] = true
                if (rnd.nextFloat() < 0.35f) grid[2][11] = true
            }
        }

        // ---- Perc (track 3) – sparse accents only ----
        val percPositions = listOf(3, 6, 7, 10, 11, 14, 15, 1, 9)
        var percCount = 0
        for (pos in percPositions.shuffled(rnd)) {
            if (percCount >= 4) break
            if (rnd.nextFloat() < 0.45f) {
                grid[3][pos] = true
                percCount++
            }
        }

        // Safety: never leave a completely empty track (except perc can stay sparse)
        if (!grid[0].any { it }) { grid[0][0] = true; grid[0][8] = true }
        if (!grid[1].any { it }) { grid[1][4] = true; grid[1][12] = true }
        if (!grid[2].any { it }) { for (s in 0 until 16 step 2) grid[2][s] = true }
    }




    // ------------------------------------------------------------------
    // Kit management
    // ------------------------------------------------------------------
    fun loadKit(index: Int, context: Context, startPattern: Int = 0) {
        if (index !in 0 until 8) return
        currentKitIndex = index
        val kit = kits[index]
        for (i in 0 until 8) {
            patterns[i] = kit.patterns[i].deepCopy()
        }
        val patternToLoad = startPattern.coerceIn(0, 7)
        currentPatternIndex = patternToLoad
        loadPattern(patternToLoad)
        loadKitSamples(context, index)
    }




    fun saveCurrentKit(index: Int = currentKitIndex, context: Context) {
        if (index !in 0 until 8) return
        // first flush current working pattern
        saveCurrentToPattern(currentPatternIndex)




        val kit = kits[index]
        for (i in 0 until 8) {
            kit.patterns[i] = patterns[i].deepCopy()
        }
        currentKitIndex = index
        saveKitSamples(context, index)
    }




    private fun saveKitSamples(context: Context, kitIndex: Int) {
        val dir = File(context.filesDir, "drum_kits")
        if (!dir.exists()) dir.mkdirs()
        for (t in 0 until 4) {
            val sample = drumSamples[t]
            val file = File(dir, "kit" + kitIndex + "_t" + t + ".pcm")
            if (sample != null && sample.isNotEmpty()) {
                try {
                    FileOutputStream(file).use { fos ->
                        val bb = ByteBuffer.allocate(4 + sample.size * 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        bb.putInt(sample.size)
                        for (f in sample) bb.putFloat(f)
                        fos.write(bb.array())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                if (file.exists()) file.delete()
            }
        }
    }




    private fun loadKitSamples(context: Context, kitIndex: Int): Boolean {
        val dir = File(context.filesDir, "drum_kits")
        var anyLoaded = false
        for (t in 0 until 4) {
            val file = File(dir, "kit" + kitIndex + "_t" + t + ".pcm")
            if (file.exists()) {
                try {
                    val bytes = file.readBytes()
                    if (bytes.size < 4) continue
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val size = bb.int
                    if (size > 0 && size * 4 + 4 == bytes.size) {
                        val floats = FloatArray(size)
                        for (i in 0 until size) floats[i] = bb.float
                        setSample(t, floats)
                        anyLoaded = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    drumSamples[t] = null
                    playIndices[t] = -1
                }
            } else {
                drumSamples[t] = null
                playIndices[t] = -1
            }
        }
        return anyLoaded
    }




    // ------------------------------------------------------------------
    // Sample management
    // ------------------------------------------------------------------
    fun setSample(trackIndex: Int, pcm: FloatArray) {
        if (trackIndex in 0 until 4) {
            drumSamples[trackIndex] = pcm
            playIndices[trackIndex] = -1
        }
    }




    // ------------------------------------------------------------------
    // Real-time processing
    // ------------------------------------------------------------------
    fun processNextSample(): Float {
        if (!isPlaying) return 0f

        // Base 16th-note rate
        val baseStepsPerSecond = (bpm / 60.0) * 4.0

        // Swing: even steps (0,2,4...) last longer, odd steps (1,3,5...) are shortened.
        // swing 0 → equal, swing 1 → ~1.5 : 0.5 ratio (classic heavy swing / triplet feel)
        val swingAmt = swing.coerceIn(0f, 1f) * 0.5
        val stretch = if (currentStep % 2 == 0) (1.0 + swingAmt) else (1.0 - swingAmt)
        val stepsPerSecond = baseStepsPerSecond / stretch

        stepPhase += stepsPerSecond / sampleRate

        if (stepPhase >= 1.0) {
            stepPhase -= 1.0
            currentStep = (currentStep + 1) % 16
            for (t in 0 until 4) {
                if (grid[t][currentStep] && drumSamples[t] != null) {
                    playIndices[t] = 0
                }
            }
        }

        var mixed = 0f
        for (t in 0 until 4) {
            val idx = playIndices[t]
            val sample = drumSamples[t]
            if (idx >= 0 && sample != null) {
                if (idx < sample.size) {
                    mixed += sample[idx] * trackVolumes[t]
                    playIndices[t] = idx + 1
                } else {
                    playIndices[t] = -1
                }
            }
        }
        return (mixed * masterVolume).coerceIn(-1f, 1f)
    }




    // ------------------------------------------------------------------
    // Load sample from URI
    // ------------------------------------------------------------------
    suspend fun loadSample(context: Context, trackIndex: Int, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val pcm = decodeAudio(context, uri)
                if (pcm != null) {
                    setSample(trackIndex, pcm)
                    true
                } else false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }




    private fun decodeAudio(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)




            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 1 }
            val fileSampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }




            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()




            var raw = FloatArray(1024 * 512)
            var rawSize = 0
            val info = MediaCodec.BufferInfo()
            var isEOS = false




            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }




                var outIndex = codec.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        if (channels == 2) {
                            while (shortBuffer.remaining() >= 2) {
                                if (rawSize >= raw.size) raw = raw.copyOf(raw.size * 2)
                                val left = shortBuffer.get() / 32768f
                                val right = shortBuffer.get() / 32768f
                                raw[rawSize++] = (left + right) * 0.5f
                            }
                        } else {
                            while (shortBuffer.hasRemaining()) {
                                if (rawSize >= raw.size) raw = raw.copyOf(raw.size * 2)
                                raw[rawSize++] = shortBuffer.get() / 32768f
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }




            if (rawSize <= 0) return null




            val ratio = fileSampleRate.toDouble() / sampleRate
            val targetSize = (rawSize / ratio).toInt().coerceAtLeast(1)
            val resampled = FloatArray(targetSize)
            for (i in 0 until targetSize) {
                val src = i * ratio
                val idx = src.toInt()
                val frac = (src - idx).toFloat()
                resampled[i] = when {
                    idx + 1 < rawSize -> raw[idx] + (raw[idx + 1] - raw[idx]) * frac
                    idx < rawSize -> raw[idx]
                    else -> 0f
                }
            }
            return resampled
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }




    // ------------------------------------------------------------------
    // Default kit (raw resources)
    // ------------------------------------------------------------------
    suspend fun loadDefaultKit(context: Context): Boolean = withContext(Dispatchers.IO) {
        val resourceIds = intArrayOf(R.raw.kick, R.raw.snare, R.raw.high, R.raw.perc)
        var success = true
        for (i in 0 until 4) {
            try {
                val afd = context.resources.openRawResourceFd(resourceIds[i])
                val pcm = decodeAudioFromFd(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                if (pcm != null) setSample(i, pcm) else success = false
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }
        }
        success
    }




    private fun decodeAudioFromFd(fd: java.io.FileDescriptor, offset: Long, length: Long): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(fd, offset, length)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)




            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 1 }
            val fileSampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }




            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()




            var raw = FloatArray(1024 * 256)
            var rawSize = 0
            val info = MediaCodec.BufferInfo()
            var isEOS = false




            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }




                var outIndex = codec.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        if (channels == 2) {
                            while (shortBuffer.remaining() >= 2) {
                                if (rawSize >= raw.size) raw = raw.copyOf(raw.size * 2)
                                val left = shortBuffer.get() / 32768f
                                val right = shortBuffer.get() / 32768f
                                raw[rawSize++] = (left + right) * 0.5f
                            }
                        } else {
                            while (shortBuffer.hasRemaining()) {
                                if (rawSize >= raw.size) raw = raw.copyOf(raw.size * 2)
                                raw[rawSize++] = shortBuffer.get() / 32768f
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }




            if (rawSize <= 0) return null




            val ratio = fileSampleRate.toDouble() / sampleRate
            val targetSize = (rawSize / ratio).toInt().coerceAtLeast(1)
            val resampled = FloatArray(targetSize)
            for (i in 0 until targetSize) {
                val src = i * ratio
                val idx = src.toInt()
                val frac = (src - idx).toFloat()
                resampled[i] = when {
                    idx + 1 < rawSize -> raw[idx] + (raw[idx + 1] - raw[idx]) * frac
                    idx < rawSize -> raw[idx]
                    else -> 0f
                }
            }
            return resampled
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }


    // ------------------------------------------------------------------
    // Export / Import all 8 kits (metadata + WAV samples) as a single Zip
    // Survives uninstall + reinstall and allows sharing between users
    // ------------------------------------------------------------------

    /**
     * Export every kit (names, patterns, BPM, volumes) + all samples as WAV
     * into a single Zip file the user chooses via SAF.
     */
    fun exportAllKitsToZip(context: Context, uri: Uri): Boolean {
        // Make sure the currently loaded kit is flushed to disk first
        try {
            saveCurrentKit(currentKitIndex, context)
        } catch (_: Exception) {}

        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    // 1. Metadata JSON
                    val json = buildKitsMetadataJson()
                    zos.putNextEntry(ZipEntry("kits.json"))
                    zos.write(json.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 2. Samples – read the already-persisted PCM files and convert to WAV
                    val dir = File(context.filesDir, "drum_kits")
                    for (k in 0 until 8) {
                        for (t in 0 until 4) {
                            val pcmFile = File(dir, "kit${k}_t${t}.pcm")
                            if (!pcmFile.exists()) continue
                            val floats = readPcmFile(pcmFile) ?: continue
                            if (floats.isEmpty()) continue
                            zos.putNextEntry(ZipEntry("kit${k}_t${t}.wav"))
                            writeWav16Mono(zos, floats, sampleRate)
                            zos.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import a Zip previously created by exportAllKitsToZip.
     * Restores names, patterns, volumes, BPM and all WAV samples for all 8 kits.
     */
    fun importAllKitsFromZip(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    val samplesToSave = mutableMapOf<Pair<Int, Int>, FloatArray>() // (kit,track) -> floats
                    var metaJson: JSONObject? = null

                    while (entry != null) {
                        val name = entry.name
                        if (name == "kits.json") {
                            val bytes = zis.readBytes()
                            metaJson = JSONObject(String(bytes, Charsets.UTF_8))
                        } else if (name.startsWith("kit") && name.endsWith(".wav")) {
                            // kitN_tM.wav
                            val parts = name.removeSuffix(".wav").split("_")
                            if (parts.size == 2) {
                                val k = parts[0].removePrefix("kit").toIntOrNull()
                                val t = parts[1].removePrefix("t").toIntOrNull()
                                if (k != null && t != null && k in 0..7 && t in 0..3) {
                                    val floats = readWav16Mono(zis)
                                    if (floats != null && floats.isNotEmpty()) {
                                        samplesToSave[k to t] = floats
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    // Apply metadata
                    if (metaJson != null) {
                        applyKitsMetadataJson(metaJson)
                    }

                    // Write all samples to internal storage
                    for ((key, floats) in samplesToSave) {
                        val (k, t) = key
                        // Temporarily set so we keep playIndices consistent, then restore
                        val previous = drumSamples[t]
                        setSample(t, floats)
                        writeSinglePcmFile(context, k, t, floats)
                        // Restore previous live sample so we don't mess the current kit
                        if (previous != null) {
                            setSample(t, previous)
                        } else {
                            drumSamples[t] = null
                            playIndices[t] = -1
                        }
                    }

                    // Finally load the (possibly updated) current kit so UI and playback are in sync
                    // שומרים על המקצב הנוכחי ששוחזר מה-JSON
                    val kitToLoad = currentKitIndex.coerceIn(0, 7)
                    val patternToLoad = currentPatternIndex.coerceIn(0, 7)
                    loadKit(kitToLoad, context, startPattern = patternToLoad)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ---------- helpers for export / import ----------

    private fun buildKitsMetadataJson(): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("currentKitIndex", currentKitIndex)
        root.put("currentPatternIndex", currentPatternIndex)

        val kitsArr = JSONArray()
        for (k in 0 until 8) {
            val kitObj = JSONObject()
            kitObj.put("name", kits[k].name)
            val patternsArr = JSONArray()
            for (p in 0 until 8) {
                val pat = kits[k].patterns[p]
                val pObj = JSONObject()
                // grid as 4 strings of 16 chars ('1'/'0')
                val gridArr = JSONArray()
                for (t in 0 until 4) {
                    val sb = StringBuilder(16)
                    for (s in 0 until 16) {
                        sb.append(if (pat.grid[t][s]) '1' else '0')
                    }
                    gridArr.put(sb.toString())
                }
                pObj.put("grid", gridArr)
                pObj.put("bpm", pat.bpm.toDouble())
                pObj.put("masterVolume", pat.masterVolume.toDouble())
                val tvArr = JSONArray()
                for (t in 0 until 4) tvArr.put(pat.trackVolumes[t].toDouble())
                pObj.put("trackVolumes", tvArr)
                patternsArr.put(pObj)
            }
            kitObj.put("patterns", patternsArr)
            kitsArr.put(kitObj)
        }
        root.put("kits", kitsArr)
        return root
    }

    private fun applyKitsMetadataJson(root: JSONObject) {
        currentKitIndex = root.optInt("currentKitIndex", 0).coerceIn(0, 7)
        currentPatternIndex = root.optInt("currentPatternIndex", 0).coerceIn(0, 7)

        val kitsArr = root.optJSONArray("kits") ?: return
        for (k in 0 until minOf(8, kitsArr.length())) {
            val kitObj = kitsArr.getJSONObject(k)
            kits[k].name = kitObj.optString("name", "סגנון ${k + 1}")
            val patternsArr = kitObj.optJSONArray("patterns") ?: continue
            for (p in 0 until minOf(8, patternsArr.length())) {
                val pObj = patternsArr.getJSONObject(p)
                val gridArr = pObj.optJSONArray("grid")
                val g = Array(4) { BooleanArray(16) }
                if (gridArr != null) {
                    for (t in 0 until minOf(4, gridArr.length())) {
                        val row = gridArr.optString(t, "")
                        for (s in 0 until minOf(16, row.length)) {
                            g[t][s] = row[s] == '1'
                        }
                    }
                }
                val bpm = pObj.optDouble("bpm", 120.0).toFloat()
                val master = pObj.optDouble("masterVolume", 0.8).toFloat()
                val tvArr = pObj.optJSONArray("trackVolumes")
                val tvs = FloatArray(4) { 1.0f }
                if (tvArr != null) {
                    for (t in 0 until minOf(4, tvArr.length())) {
                        tvs[t] = tvArr.optDouble(t, 1.0).toFloat()
                    }
                }
                kits[k].patterns[p] = DrumPattern(g, bpm, master, tvs)
            }
        }
    }

    private fun readPcmFile(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 4) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val size = bb.int
            if (size <= 0 || size * 4 + 4 != bytes.size) return null
            val floats = FloatArray(size)
            for (i in 0 until size) floats[i] = bb.float
            floats
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun writeSinglePcmFile(context: Context, kitIndex: Int, trackIndex: Int, sample: FloatArray) {
        val dir = File(context.filesDir, "drum_kits")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "kit${kitIndex}_t${trackIndex}.pcm")
        try {
            FileOutputStream(file).use { fos ->
                val bb = ByteBuffer.allocate(4 + sample.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(sample.size)
                for (f in sample) bb.putFloat(f)
                fos.write(bb.array())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Write a standard 16-bit mono little-endian WAV header + data. */
    private fun writeWav16Mono(out: OutputStream, samples: FloatArray, sr: Int) {
        val numSamples = samples.size
        val dataSize = numSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)          // PCM chunk size
        header.putShort(1)         // audio format = PCM
        header.putShort(1)         // channels = mono
        header.putInt(sr)          // sample rate
        header.putInt(sr * 2)      // byte rate
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        // data chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        // samples as 16-bit
        val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            data.putShort(s)
        }
        out.write(data.array())
    }

    /** Read a 16-bit mono WAV (the format we ourselves write). Returns null on failure. */
    private fun readWav16Mono(input: InputStream): FloatArray? {
        return try {
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val r = input.read(header, read, 44 - read)
                if (r < 0) return null
                read += r
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            // basic sanity checks
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val audioFormat = bb.getShort(20).toInt()
            val channels = bb.getShort(22).toInt()
            val bits = bb.getShort(34).toInt()
            if (audioFormat != 1 || channels != 1 || bits != 16) return null
            val dataSize = bb.getInt(40)
            if (dataSize <= 0 || dataSize % 2 != 0) return null

            val data = ByteArray(dataSize)
            read = 0
            while (read < dataSize) {
                val r = input.read(data, read, dataSize - read)
                if (r < 0) break
                read += r
            }
            val numSamples = read / 2
            val floats = FloatArray(numSamples)
            val dbb = ByteBuffer.wrap(data, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floats[i] = dbb.short / 32768f
            }
            floats
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
