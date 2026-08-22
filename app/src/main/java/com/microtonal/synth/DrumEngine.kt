package com.microtonal.synth

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DrumEngine(private val sampleRate: Int) {
    // 4 מערכים לשמירת ה-PCM של 4 הסאמפלים (KICK, SNARE, HIHAT, PERC וכו')
    val drumSamples = Array<FloatArray?>(4) { null }
    
    // מערך דו-מימדי 4x16 לשמירת הצעדים (Step Sequencer)
    val grid = Array(4) { BooleanArray(16) }
    
    // ווליום נפרד לכל ערוץ
    val volumes = FloatArray(4) { 0.8f }
    
    // ווליום מאסטר תופים ו-BPM
    @Volatile var globalVolume = 1.0f
    @Volatile var bpm = 120f
    @Volatile var isPlaying = false

    private var currentStep = 0
    private var stepPhase = 0.0

    // שמירת אינדקס הנגינה הנוכחי של כל סאמפל (-1 אומר שלא מנגן כרגע)
    private val playIndices = IntArray(4) { -1 }

    fun processSample(): Float {
        if (!isPlaying) return 0f

        // חישוב חלקיק הצעד הנוכחי כדי לאפשר שינוי BPM חלק בזמן אמת
        val stepsPerSecond = (bpm / 60.0) * 4.0 // 4 צעדים בפעימה (16th notes)
        stepPhase += stepsPerSecond / sampleRate

        // כשעברנו לצעד הבא
        if (stepPhase >= 1.0) {
            stepPhase -= 1.0
            currentStep = (currentStep + 1) % 16
            
            // בדיקה האם יש טריגר להפעיל בצעד הנוכחי
            for (i in 0 until 4) {
                if (grid[i][currentStep] && drumSamples[i] != null) {
                    playIndices[i] = 0 // הפעלת הסאמפל מחדש
                }
            }
        }

        var outMix = 0f
        for (i in 0 until 4) {
            val idx = playIndices[i]
            val buffer = drumSamples[i]
            if (idx >= 0 && buffer != null) {
                if (idx < buffer.size) {
                    outMix += buffer[idx] * volumes[i]
                    playIndices[i]++
                } else {
                    // סיום הסאמפל
                    playIndices[i] = -1
                }
            }
        }
        
        return outMix * globalVolume
    }

    // פונקציית פענוח מבודדת לקבצי האודיו של התופים
    suspend fun loadSample(context: Context, trackIndex: Int, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val pcmData = decodeAudio(context, uri)
            if (pcmData != null) {
                drumSamples[trackIndex] = pcmData
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
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
            val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
            val fileSampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            var rawPcmData = FloatArray(1024 * 512) // חצי מגה-בייט לתופים זה די והותר
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
                                if (rawSize >= rawPcmData.size) rawPcmData = rawPcmData.copyOf(rawPcmData.size * 2)
                                val left = shortBuffer.get() / 32768.0f
                                val right = shortBuffer.get() / 32768.0f
                                rawPcmData[rawSize++] = (left + right) / 2.0f
                            }
                        } else {
                            while (shortBuffer.hasRemaining()) {
                                if (rawSize >= rawPcmData.size) rawPcmData = rawPcmData.copyOf(rawPcmData.size * 2)
                                rawPcmData[rawSize++] = shortBuffer.get() / 32768.0f
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
            
            val decodedSamplesCount = rawSize
            if (decodedSamplesCount <= 0) return null

            val ratio = fileSampleRate.toDouble() / sampleRate.toDouble()
            val targetSize = (decodedSamplesCount / ratio).toInt()
            val resampledData = FloatArray(targetSize)
            
            for (i in 0 until targetSize) {
                val srcIdx = i * ratio
                val idxInt = srcIdx.toInt()
                val frac = (srcIdx - idxInt).toFloat()
                if (idxInt + 1 < decodedSamplesCount) {
                    val s0 = rawPcmData[idxInt]
                    val s1 = rawPcmData[idxInt + 1]
                    resampledData[i] = s0 + (s1 - s0) * frac
                } else if (idxInt < decodedSamplesCount) {
                    resampledData[i] = rawPcmData[idxInt]
                }
            }
            return resampledData
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
