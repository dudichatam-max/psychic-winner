package com.microtonal.synth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MicTab(
    engine: SynthEngine,
    context: Context,
    gold: Color,
    panelBg: Color,
    panelBg2: Color,
    silentVis: FloatArray,
    micMonitorOn: Boolean,
    setMicMonitorOn: (Boolean) -> Unit,
    micMonitorVol: Float,
    setMicMonitorVol: (Float) -> Unit,
    micGain: Float,
    setMicGain: (Float) -> Unit,
    micHpf: Float,
    setMicHpf: (Float) -> Unit,
    micGate: Float,
    setMicGate: (Float) -> Unit,
    micLow: Float,
    setMicLow: (Float) -> Unit,
    micPresence: Float,
    setMicPresence: (Float) -> Unit,
    micComp: Float,
    setMicComp: (Float) -> Unit,
    micPage: Int,
    setMicPage: (Int) -> Unit,
    pendingMicSaveTrack: Int,
    setPendingMicSaveTrack: (Int) -> Unit,
    micRecStates: SnapshotStateList<Boolean>,
    micPlayStates: SnapshotStateList<Boolean>,
    micVolStates: SnapshotStateList<Float>,
    micPanStates: SnapshotStateList<Float>,
    micNames: SnapshotStateList<String>,
    micHpfCh: SnapshotStateList<Float>,
    micGateCh: SnapshotStateList<Float>,
    micPresCh: SnapshotStateList<Float>,
    micCompCh: SnapshotStateList<Float>,
    saveMicTrack: (String) -> Unit,
    importMicProject: (String) -> Unit,
    exportMicProject: (String) -> Unit,
    requestMicPermission: (String) -> Unit
) {
Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val scopeTick = 0L
    Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("MIC", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            when {
                !micMonitorOn -> "מוניטור כבוי"
                engine.micEngine.inputLevel > 0.01f -> "אות נכנס"
                else -> if (engine.micEngine.headphonesConnected) "אוזניות • מחכה לאות" else "רמקול • זהירות מפידבק"
            },
            color = Color.Gray,
            fontSize = 8.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Button(
            onClick = { setMicPage(0) },
            colors = ButtonDefaults.buttonColors(containerColor = if (micPage == 0) gold else panelBg2),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(28.dp).height(26.dp),
            shape = RoundedCornerShape(4.dp)
        ) { Text("1", color = if (micPage == 0) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = { setMicPage(1) },
            colors = ButtonDefaults.buttonColors(containerColor = if (micPage == 1) gold else panelBg2),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(28.dp).height(26.dp),
            shape = RoundedCornerShape(4.dp)
        ) { Text("2", color = if (micPage == 1) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = {
                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!granted) { requestMicPermission(Manifest.permission.RECORD_AUDIO); return@Button }
                engine.refreshHeadphoneState()
                val next = !micMonitorOn
                if (engine.micEngine.setMonitor(next)) {
                    setMicMonitorOn(next)
                    engine.micEngine.monitorVolume = micMonitorVol
                    if (next && !engine.micEngine.headphonesConnected) {
                        Toast.makeText(context, "מוניטור ברמקול עלול ליצור פידבק", Toast.LENGTH_SHORT).show()
                    }
                } else Toast.makeText(context, engine.micEngine.lastError.ifEmpty { "לא ניתן לפתוח את המיקרופון" }, Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = if (micMonitorOn) gold else panelBg2),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(26.dp)
        ) { Text(if (micMonitorOn) "מוניטור פועל" else "מוניטור", fontSize = 10.sp, color = if (micMonitorOn) Color.Black else gold, fontWeight = FontWeight.Bold) }
    }
    Text(
        if (micPage == 0) "עמוד 1 • ווקלים • ללא הגבלת אורך" else "עמוד 2 • גיטרה / באס / אינסטרומנטלי",
        color = Color.Gray,
        fontSize = 8.sp
    )
    MiniWaveMeter(
        if (micMonitorOn) engine.micEngine.monitorVisualizer else silentVis,
        gold,
        Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        SynthKnob("Gain", String.format("%.1fx", micGain), (micGain - 1f) / 3f, 0f..1f, gold, 30.dp) { val newGain = 1f + it * 3f; setMicGain(newGain); engine.micEngine.inputGain = newGain }
        SynthKnob("Mon", "${(micMonitorVol * 100).toInt()}%", micMonitorVol, 0f..1.5f, gold, 30.dp) { setMicMonitorVol(it); engine.micEngine.monitorVolume = it }
        SynthKnob("HPF", "${micHpf.toInt()}Hz", (micHpf - 40f) / 200f, 0f..1f, gold, 30.dp) { val newHpf = 40f + it * 200f; setMicHpf(newHpf); engine.micEngine.hpfHz = newHpf }
        SynthKnob("Gate", "${(micGate * 100).toInt()}", micGate, 0f..0.12f, gold, 30.dp) { setMicGate(it); engine.micEngine.gateThresh = it }
        SynthKnob("Low", "${(micLow * 100).toInt()}%", micLow, 0.4f..1.6f, gold, 30.dp) { setMicLow(it); engine.micEngine.lowGain = it }
        SynthKnob("Pres", "${(micPresence * 100).toInt()}%", micPresence, 0.4f..2f, gold, 30.dp) { setMicPresence(it); engine.micEngine.presenceGain = it }
        SynthKnob("Comp", "${(micComp * 100).toInt()}%", micComp, 0f..1f, gold, 30.dp) { setMicComp(it); engine.micEngine.compAmount = it }
    }
    Row(modifier = Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (slot in 0 until 3) {
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight().background(panelBg2, RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${slot + 1}", color = gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("טען", color = gold, fontSize = 8.sp, modifier = Modifier.clickable {
                    engine.micEngine.loadMonitorPreset(slot)
                    setMicMonitorVol(engine.micEngine.monitorVolume)
                    setMicGain(engine.micEngine.inputGain)
                    setMicHpf(engine.micEngine.hpfHz)
                    setMicGate(engine.micEngine.gateThresh)
                    setMicLow(engine.micEngine.lowGain)
                    setMicPresence(engine.micEngine.presenceGain)
                    setMicComp(engine.micEngine.compAmount)
                })
                Text("שמור", color = Color.Gray, fontSize = 8.sp, modifier = Modifier.clickable {
                    engine.micEngine.saveMonitorPreset(slot)
                    Toast.makeText(context, "פריסט ${slot + 1} נשמר", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 3) {
            val track = micPage * 3 + row
            val rec = micRecStates[track]
            val playing = micPlayStates[track]
            key(track) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).background(panelBg, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                BasicTextField(
                    value = micNames[track],
                    onValueChange = { micNames[track] = it },
                    singleLine = true,
                    textStyle = TextStyle(color = gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(gold)
                )
                Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (!granted) { requestMicPermission(Manifest.permission.RECORD_AUDIO); return@Button }
                            if (rec) {
                                engine.micEngine.stopTrackRecording(track)
                                micRecStates[track] = false
                            } else {
                                engine.micEngine.tracks[track].stopPlayback()
                                micPlayStates[track] = false
                                if (engine.micEngine.startTrackRecording(track)) micRecStates[track] = true
                                else Toast.makeText(context, engine.micEngine.lastError.ifEmpty { "שגיאה בפתיחת המיקרופון" }, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (rec) gold else panelBg2),
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(if (rec) "עצור" else "הקלט", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (rec) Color.Black else Color.White, textAlign = TextAlign.Center) }
                    Button(
                        onClick = {
                            val t = engine.micEngine.tracks[track]
                            if (t.isRecording) { engine.micEngine.stopTrackRecording(track); micRecStates[track] = false }
                            if (t.isPlaying) { t.stopPlayback(); micPlayStates[track] = false }
                            else { t.startPlayback(); micPlayStates[track] = t.isPlaying }
                        },
                        enabled = playing || engine.micEngine.tracks[track].hasContent() || rec,
                        colors = ButtonDefaults.buttonColors(containerColor = if (playing) gold else panelBg2),
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(if (playing) "עצור" else "נגן", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (playing) Color.Black else Color.White, textAlign = TextAlign.Center) }
                    MiniWaveMeter(engine.micEngine.tracks[track].visualizerBuffer, gold, Modifier.weight(1f).fillMaxHeight())
                    OutlinedButton(
                        onClick = {
                            engine.micEngine.tracks[track].clear()
                            micRecStates[track] = false
                            micPlayStates[track] = false
                        },
                        modifier = Modifier.width(44.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray))
                    ) { Text("נקה", color = Color.Gray, fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {
                            setPendingMicSaveTrack(track)
                            saveMicTrack("Siren_Mic_T${track + 1}_${System.currentTimeMillis()}.wav")
                        },
                        enabled = engine.micEngine.tracks[track].hasContent(),
                        modifier = Modifier.width(44.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(0.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                    ) { Text("שמור", color = gold, fontSize = 10.sp) }
                }
                Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    SynthKnob("HPF", "${micHpfCh[track].toInt()}", (micHpfCh[track] - 40f) / 200f, 0f..1f, gold, 28.dp) {
                        micHpfCh[track] = 40f + it * 200f
                        engine.micEngine.trackFx[track].hpfHz = micHpfCh[track]
                    }
                    SynthKnob("Gate", "${(micGateCh[track] * 100).toInt()}", micGateCh[track], 0f..0.12f, gold, 28.dp) {
                        micGateCh[track] = it
                        engine.micEngine.trackFx[track].gateThresh = it
                    }
                    SynthKnob("Pres", "${(micPresCh[track] * 100).toInt()}%", micPresCh[track], 0.4f..2f, gold, 28.dp) {
                        micPresCh[track] = it
                        engine.micEngine.trackFx[track].presenceGain = it
                    }
                    SynthKnob("Comp", "${(micCompCh[track] * 100).toInt()}%", micCompCh[track], 0f..1f, gold, 28.dp) {
                        micCompCh[track] = it
                        engine.micEngine.trackFx[track].compAmount = it
                    }
                    SynthKnob("Vol", "${(micVolStates[track] * 100).toInt()}%", micVolStates[track], 0f..1.5f, gold, 26.dp) {
                        micVolStates[track] = it
                        engine.micEngine.trackFx[track].volume = it
                        engine.micEngine.tracks[track].volume = it
                    }
                    SynthKnob("L/R", panCaption(micPanStates[track]), (micPanStates[track] + 1f) * 0.5f, 0f..1f, gold, 26.dp) {
                        val p = it * 2f - 1f
                        micPanStates[track] = p
                        engine.micEngine.trackFx[track].applyPan(p)
                    }
                }
            }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { importMicProject("application/zip") },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(2.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
        ) { Text("ייבוא", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = { exportMicProject("Siren_Mic_${System.currentTimeMillis()}.zip") },
            colors = ButtonDefaults.buttonColors(containerColor = gold),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(2.dp)
        ) { Text("ייצוא", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
    if (scopeTick < 0L) Text("")
}
}
