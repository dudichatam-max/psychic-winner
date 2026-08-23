package com.microtonal.synth

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.microtonal.synth.R

/**
 * Spartan Drum Machine – independent from the live synth / DSP.
 * 4 sample tracks × 16-step sequencer + master BPM / volume.
 * Mixed into the main AudioTrack so WAV recording captures the drums.
 *
 * + 8 Pattern slots (grid + bpm + masterVol + trackVolumes)
 */
class DrumEngine(private val sampleRate: Int) {

    // 4 sample buffers
    val drumSamples = arrayOfNulls<FloatArray>(4)

    // 4 × 16 step grid (working copy)
    val grid = Array(4) { BooleanArray(16) }

    // Per-track volumes
    val trackVolumes = FloatArray(4) { 1.0f }

    // Display names
    val trackNames = arrayOf("Kick", "Snare", "Hi-Hat", "Perc")

    // Global controls
    @Volatile var masterVolume: Float = 0.8f
    @Volatile var bpm: Float = 120f
    @Volatile var isPlaying: Boolean = false

    // Current step (readable by UI for highlighting)
    @Volatile var currentStep: Int = 0
        private set

    private val playIndices = IntArray(4) { -1 }
    private var stepPhase = 0.0

    // ------------------------------------------------------------------
    // 8 Pattern slots
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
        // stepPhase + currentStep ממשיכים לרוץ → מעבר חלק
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
    }

    // ------------------------------------------------------------------
    // Real-time sample generation (called from the audio thread)
    // ------------------------------------------------------------------
    fun processNextSample(): Float {
        if (!isPlaying) return 0f

        // Smooth BPM changes – no clicks when turning the knob live
        val stepsPerSecond = (bpm / 60.0) * 4.0 // 16th notes
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
    // Load a sample from device storage (used by the UI launcher)
    // ------------------------------------------------------------------
    fun setSample(track: Int, data: FloatArray) {
        if (track in 0 until 4) {
            drumSamples[track] = data
            playIndices[track] = -1
        }
    }

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

    // ------------------------------------------------------------------
    // Decode any supported audio file → mono Float PCM at engine sampleRate
    // ------------------------------------------------------------------
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
            val channels = try {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } catch (_: Exception) {
                1
            }
            val fileSampleRate = try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (_: Exception) {
                44100
            }

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
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
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

            // Linear resample to the engine sample rate
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
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun loadDefaultKit(context: Context): Boolean = withContext(Dispatchers.IO) {
        val resourceIds = intArrayOf(
            R.raw.kick,
            R.raw.snare,
            R.raw.high,
            R.raw.perc
        )
        var success = true

        for (i in 0 until 4) {
            try {
                val afd = context.resources.openRawResourceFd(resourceIds[i])
                val pcm = decodeAudioFromFd(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                if (pcm != null) {
                    setSample(i, pcm)
                } else {
                    success = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }
        }
        success
    }

    private fun decodeAudioFromFd(
        fd: java.io.FileDescriptor,
        offset: Long,
        length: Long
    ): FloatArray? {
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
