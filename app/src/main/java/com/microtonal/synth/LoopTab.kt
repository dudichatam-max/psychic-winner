package com.microtonal.synth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun LoopTab(
    engine: SynthEngine,
    gold: Color,
    panelBg: Color,
    panelBg2: Color,
    looperProjectName: String,
    setLooperProjectName: (String) -> Unit,
    looperPage: Int,
    setLooperPage: (Int) -> Unit,
    pendingLoopLoadTrack: Int,
    setPendingLoopLoadTrack: (Int) -> Unit,
    pendingLoopSaveTrack: Int,
    setPendingLoopSaveTrack: (Int) -> Unit,
    loopRecStates: SnapshotStateList<Boolean>,
    loopPlayStates: SnapshotStateList<Boolean>,
    loopHasContent: SnapshotStateList<Boolean>,
    loopChannelVolStates: SnapshotStateList<Float>,
    loopChannelPanStates: SnapshotStateList<Float>,
    loopChannelNames: SnapshotStateList<String>,
    scope: CoroutineScope,
    loadAudio: (String) -> Unit,
    saveLoopTrack: (String) -> Unit,
    importLooperProject: (String) -> Unit,
    exportLooperProject: (String) -> Unit
) {
Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    val scopeTick = 0L
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicTextField(
            value = looperProjectName,
            onValueChange = { setLooperProjectName(it) },
            singleLine = true,
            textStyle = TextStyle(color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f).fillMaxHeight().background(panelBg, RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(gold)
        )
        for (p in 0 until 4) {
            val selected = looperPage == p
            Button(
                onClick = { setLooperPage(p) },
                colors = ButtonDefaults.buttonColors(containerColor = if (selected) gold else panelBg2),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(26.dp).fillMaxHeight(),
                shape = RoundedCornerShape(4.dp)
            ) { Text("${p + 1}", color = if (selected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until 4) {
            val track = looperPage * 5 + row
            val rec = loopRecStates[track]
            val playing = loopPlayStates[track]
            val accent = gold
            key(track) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).background(panelBg, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                BasicTextField(
                    value = loopChannelNames[track],
                    onValueChange = { value -> loopChannelNames[track] = value },
                    singleLine = true,
                    textStyle = TextStyle(color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(gold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                Button(
                    onClick = {
                        if (rec) {
                            engine.stopTrackRecording(track)
                            loopRecStates[track] = false
                            loopHasContent[track] = engine.trackHasContent(track)
                            scope.launch(Dispatchers.IO) {
                                engine.saveLooperSession(looperProjectName, loopChannelNames.toList(), looperPage)
                            }
                        }
                        else { engine.setTrackPlaying(track, false); loopPlayStates[track] = false; engine.startTrackRecording(track); loopRecStates[track] = true }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (rec) gold else panelBg2),
                    modifier = Modifier.width(28.dp).fillMaxHeight(0.92f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (rec) "עצור" else "הקלט", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = if (rec) Color.Black else Color.White, textAlign = TextAlign.Center) }
                Button(
                    onClick = {
                        if (rec) {
                            engine.stopTrackRecording(track)
                            loopRecStates[track] = false
                            loopHasContent[track] = engine.trackHasContent(track)
                        }
                        loopPlayStates[track] = engine.toggleTrackPlayback(track)
                    },
                    enabled = playing || rec || loopHasContent[track],
                    colors = ButtonDefaults.buttonColors(containerColor = if (playing) gold else panelBg2),
                    modifier = Modifier.width(28.dp).fillMaxHeight(0.92f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (playing) "עצור" else "נגן", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = if (playing) Color.Black else Color.White, textAlign = TextAlign.Center) }
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(if (rec) "מקליט…" else if (playing) "מנגן" else if (loopHasContent[track]) "מוכן" else "ריק", color = Color.Gray, fontSize = 7.sp, maxLines = 1)
                    MiniWaveMeter(engine.looperTracks[track].visualizerBuffer, if (rec) gold else accent, Modifier.fillMaxWidth().weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                setPendingLoopLoadTrack(track)
                                loadAudio("audio/*")
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(0.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("טען", color = gold, fontSize = 7.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        OutlinedButton(
                            onClick = {
                                setPendingLoopSaveTrack(track)
                                saveLoopTrack("Siren_Loop_P${looperPage + 1}_T${row + 1}_${System.currentTimeMillis()}.wav")
                            },
                            enabled = loopHasContent[track],
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(0.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("שמור", color = gold, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = {
                                engine.clearTrack(track)
                                loopRecStates[track] = false
                                loopPlayStates[track] = false
                                loopHasContent[track] = false
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(0.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray))
                        ) { Text("נקה", color = Color.Gray, fontSize = 7.sp) }
                    }
                }
                SynthKnob("Vol", "${(loopChannelVolStates[track] * 100).toInt()}%", loopChannelVolStates[track], 0f..1f, accent, 22.dp) {
                    loopChannelVolStates[track] = it
                    engine.setTrackVolume(track, it)
                }
                LrPanKnob(loopChannelPanStates[track], accent, 22.dp) { p ->
                    loopChannelPanStates[track] = p
                    engine.setTrackPan(track, p)
                }
                }
            }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { importLooperProject("application/zip") },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(2.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
        ) { Text("ייבוא", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = { exportLooperProject("Siren_Looper_${System.currentTimeMillis()}.zip") },
            colors = ButtonDefaults.buttonColors(containerColor = gold),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(2.dp)
        ) { Text("ייצוא", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
    if (scopeTick < 0L) Text("")
}
}
