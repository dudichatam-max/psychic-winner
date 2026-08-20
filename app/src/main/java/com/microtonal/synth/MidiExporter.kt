package com.microtonal.synth

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object MidiExporter {

    private val baseFreqs = floatArrayOf(222.00f, 299.00f, 333.00f, 355.00f, 396.00f, 444.00f, 463.00f, 477.00f)
    private val baseNotes = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72)

    fun exportMidiToUri(context: Context, notes: List<MidiNoteEvent>, uri: Uri): Boolean {
        if (notes.isEmpty()) return false
        return try {
            val tempFile = File(context.cacheDir, "temp_siren.mid")
            createMidiFile(notes, tempFile)

            // תיקון: שימוש ב-uri הנכון שהתקבל בפרמטרים
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // תיקון: שינוי ל-MidiNoteEvent כדי להתאים למה שמועבר מ-MainActivity
    private fun createMidiFile(notes: List<MidiNoteEvent>, outputFile: File) {
        val midiFile = FileOutputStream(outputFile)
        val dos = DataOutputStream(midiFile)

        dos.writeBytes("MThd")
        dos.writeInt(6)       
        dos.writeShort(0)     
        dos.writeShort(1)     
        dos.writeShort(480)   

        val trackData = ByteArrayOutputStream()
        
        writeVLQ(0, trackData)
        trackData.write(byteArrayOf(0xFF.toByte(), 0x51.toByte(), 0x03.toByte(), 0x07.toByte(), 0xA1.toByte(), 0x20.toByte()))

        var lastTimeMs = 0L
        val ticksPerMs = 0.96
        
        val sortedEvents = notes.sortedBy { it.timestampMs }

        for (event in sortedEvents) {
            val deltaMs = event.timestampMs - lastTimeMs
            val deltaTicks = (deltaMs * ticksPerMs).toLong()
            writeVLQ(deltaTicks, trackData)

            val noteNumber = getMidiNote(event.freq, event.octave)
            val velocity = 100 
            
            if (event.isNoteOn) {
                trackData.write(0x90 or 0)
                trackData.write(noteNumber)
                trackData.write(velocity)
            } else {
                trackData.write(0x80 or 0)
                trackData.write(noteNumber)
                trackData.write(0)
            }
            lastTimeMs = event.timestampMs
        }

        writeVLQ(0, trackData)
        trackData.write(byteArrayOf(0xFF.toByte(), 0x2F.toByte(), 0x00.toByte()))

        val trackBytes = trackData.toByteArray()
        dos.writeBytes("MTrk")
        dos.writeInt(trackBytes.size)
        dos.write(trackBytes)
        dos.close()
    }

    private fun getMidiNote(freq: Float, octaveShift: Int): Int {
        var closestIndex = 0
        var minDiff = Float.MAX_VALUE
        for (i in baseFreqs.indices) {
            val diff = abs(freq - baseFreqs[i])
            if (diff < minDiff) {
                minDiff = diff
                closestIndex = i
            }
        }
        return (baseNotes[closestIndex] + (octaveShift * 12)).coerceIn(0, 127)
    }

    private fun writeVLQ(value: Long, out: ByteArrayOutputStream) {
        var temp = value
        val bytes = ByteArray(4)
        var count = 0
        
        bytes[count++] = (temp and 0x7F).toByte()
        temp = temp shr 7
        while (temp > 0) {
            bytes[count++] = ((temp and 0x7F) or 0x80).toByte()
            temp = temp shr 7
        }
        
        for (i in count - 1 downTo 0) {
            out.write(bytes[i].toInt() and 0xFF)
        }
    }
}
