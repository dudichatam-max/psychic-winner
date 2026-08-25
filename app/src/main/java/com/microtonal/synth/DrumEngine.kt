package com.microtonal.synth


import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.microtonal.synth.R
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


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


    // ------------------------------------------------------------------
    // Kit management
    // ------------------------------------------------------------------
    fun loadKit(index: Int, context: Context) {
        if (index !in 0 until 8) return
        currentKitIndex = index
        val kit = kits[index]
        for (i in 0 until 8) {
            patterns[i] = kit.patterns[i].deepCopy()
        }
        currentPatternIndex = 0
        loadPattern(0)
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


        val stepsPerSecond = (bpm / 60.0) * 4.0
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
}
