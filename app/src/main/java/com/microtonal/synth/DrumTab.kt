package com.microtonal.synth

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun DrumTab(
    engine: SynthEngine,
    context: Context,
    prefs: android.content.SharedPreferences,
    gold: Color,
    panelBg2: Color,
    selectedDrumPattern: Int,
    setSelectedDrumPattern: (Int) -> Unit,
    drumTabInitialized: Boolean,
    setDrumTabInitialized: (Boolean) -> Unit,
    drumBpmState: Float,
    setDrumBpmState: (Float) -> Unit,
    drumVolState: Float,
    setDrumVolState: (Float) -> Unit,
    drumSwingState: Float,
    setDrumSwingState: (Float) -> Unit,
    drumPlayingState: Boolean,
    setDrumPlayingState: (Boolean) -> Unit,
    drumExtrasOpen: Boolean,
    setDrumExtrasOpen: (Boolean) -> Unit,
    useDefaultKit: Boolean,
    setUseDefaultKit: (Boolean) -> Unit,
    defaultKitLoaded: Boolean,
    setDefaultKitLoaded: (Boolean) -> Unit,
    activeLoadingTrack: Int,
    setActiveLoadingTrack: (Int) -> Unit,
    gridRefreshTrigger: Long,
    setGridRefreshTrigger: (Long) -> Unit,
    patternRepeatEdit: Int,
    setPatternRepeatEdit: (Int) -> Unit,
    trackVolStates: List<MutableState<Float>>,
    trackPanStates: List<MutableState<Float>>,
    scope: CoroutineScope,
    loadDrumSample: (String) -> Unit,
    onBrowseDrumKit: (Int) -> Unit
) {
Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
    LaunchedEffect(Unit) {
        if (!drumTabInitialized) {
            PresetManager.loadAllDrumKits(prefs, engine)
            val curKit = engine.drumEngine.currentKitIndex
            val patternToUse = engine.drumEngine.currentPatternIndex.coerceIn(0, 7)
            engine.drumEngine.loadKit(curKit, context, startPattern = patternToUse)
            setDrumTabInitialized(true)
            val hasSamples = engine.drumEngine.drumSamples.any { it != null }
            if (!hasSamples) {
                val ok = engine.drumEngine.loadDefaultKit(context)
                if (ok) { setDefaultKitLoaded(true); setUseDefaultKit(true) }
            } else {
                setUseDefaultKit(false)
                setDefaultKitLoaded(true)
            }
        }
        setSelectedDrumPattern(engine.drumEngine.currentPatternIndex)
        setDrumBpmState(engine.drumEngine.bpm)
        setDrumVolState(engine.drumEngine.masterVolume)
        setDrumSwingState(engine.drumEngine.swing)
        for (t in 0 until 8) {
            trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
            trackPanStates[t].value = engine.drumEngine.trackPans[t]
        }
        setGridRefreshTrigger(System.currentTimeMillis())
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(panelBg2, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("DRUM MACHINE", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (drumPlayingState) { engine.drumEngine.stopAndRewind(); engine.benchmarkReferenceRecorder.recordDrumState(); drumPlayingState = false }
                    else { engine.drumEngine.startFromBeginning(); engine.benchmarkReferenceRecorder.recordDrumState(); drumPlayingState = true }
                    setGridRefreshTrigger(System.currentTimeMillis())
                },
                colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(26.dp)
            ) { Text(if (drumPlayingState) "עצור תופים" else "נגן תופים", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { setDrumExtrasOpen(!drumExtrasOpen) },
                colors = ButtonDefaults.buttonColors(containerColor = if (drumExtrasOpen) gold else panelBg2),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(26.dp).height(26.dp)
            ) { Text(if (drumExtrasOpen) "–" else "+", fontSize = 14.sp, color = if (drumExtrasOpen) Color.Black else gold, fontWeight = FontWeight.Bold) }
            Button(
                onClick = {
                    if (useDefaultKit) {
                        for (i in 0 until 4) engine.drumEngine.drumSamples[i] = null
                        setUseDefaultKit(false)
                    } else {
                        scope.launch {
                            val ok = engine.drumEngine.loadDefaultKit(context)
                            if (ok) { setUseDefaultKit(true); Toast.makeText(context, "ערכת ברירת מחדל נטענה", Toast.LENGTH_SHORT).show() }
                            else Toast.makeText(context, "שגיאה בטעינת הערכה", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (useDefaultKit) gold else panelBg2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(26.dp)
            ) { Text(if (useDefaultKit) "ערכת ברירת מחדל" else "טעינה ידנית", fontSize = 9.sp, color = if (useDefaultKit) Color.Black else gold, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { showStyleDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(26.dp)
            ) { Text("סגנון", fontSize = 9.sp, color = gold, fontWeight = FontWeight.Bold) }
        }
    }
    Spacer(Modifier.height(3.dp))
    val drumKitName = engine.drumEngine.kits[engine.drumEngine.currentKitIndex.coerceIn(0, 7)].name
    QuickBrowsePanel("DRUM KIT", drumKitName, Color.White, gold, { onBrowseDrumKit(-1) }, { onBrowseDrumKit(1) }, Modifier.fillMaxWidth(), compact = true)
    Spacer(Modifier.height(3.dp))
    Column(
        modifier = Modifier.fillMaxWidth().background(panelBg2, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        SynthKnob("Drum Vol", "${(drumVolState * 100).toInt()}%", drumVolState, 0f..1f, gold, 32.dp) { setDrumVolState(it); engine.drumEngine.masterVolume = it }
        SynthKnob("Swing", "${(drumSwingState * 100).toInt()}%", drumSwingState, 0f..1f, gold, 32.dp) { setDrumSwingState(it); engine.drumEngine.swing = it }
        SynthKnob("BPM", "${drumBpmState.toInt()}", drumBpmState, 60f..200f, gold, 32.dp) { setDrumBpmState(it); engine.drumEngine.bpm = it }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(4 to "1/4", 8 to "1/8", 16 to "1/16", 32 to "1/32").forEach { (div, label) ->
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            LaunchedEffect(pressed, div) {
                if (pressed) engine.drumStutterDiv = div else if (engine.drumStutterDiv == div) engine.drumStutterDiv = 0
            }
            Button(
                onClick = {},
                interactionSource = src,
                colors = ButtonDefaults.buttonColors(containerColor = if (pressed) gold else panelBg2),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(6.dp)
            ) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (pressed) Color.Black else gold) }
        }
        val drumStopSrc = remember { MutableInteractionSource() }
        val drumStopPressed by drumStopSrc.collectIsPressedAsState()
        LaunchedEffect(drumStopPressed) { engine.drumHoldStop = drumStopPressed }
        Button(
            onClick = {},
            interactionSource = drumStopSrc,
            colors = ButtonDefaults.buttonColors(containerColor = if (drumStopPressed) Color(0xFFFF1744) else panelBg2),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(6.dp)
        ) { Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (drumStopPressed) Color.White else Color(0xFFFF8A80)) }
    }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 8) {
            val liveIdx = engine.drumEngine.currentPatternIndex
            val isSelected = selectedDrumPattern == i || liveIdx == i
            val programmed = engine.drumEngine.patternRepeat[i] != 0
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .background(if (isSelected) gold else panelBg2, RoundedCornerShape(4.dp))
                    .pointerInput(i) {
                        detectTapGestures(
                            onLongPress = { setPatternRepeatEdit(i) },
                            onTap = {
                                engine.drumEngine.loadPattern(i)
                                engine.benchmarkReferenceRecorder.recordDrumState()
                                setSelectedDrumPattern(i)
                                setDrumBpmState(engine.drumEngine.bpm)
                                setDrumVolState(engine.drumEngine.masterVolume)
                                setDrumSwingState(engine.drumEngine.swing)
                                for (t in 0 until 8) {
                                    trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
                                    trackPanStates[t].value = engine.drumEngine.trackPans[t]
                                }
                                setGridRefreshTrigger(System.currentTimeMillis())
                                PresetManager.saveCurrentDrumSelection(prefs, engine)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (programmed) "${i + 1}•" else "${i + 1}",
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Button(
            onClick = {
                engine.drumEngine.saveCurrentToPattern(selectedDrumPattern)
                PresetManager.saveAllDrumKits(prefs, engine)
                Toast.makeText(context, "מקצב נשמר בחריץ ${selectedDrumPattern + 1}", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(20.dp),
            shape = RoundedCornerShape(4.dp)
        ) { Text("שמור", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        Button(
            onClick = {
                engine.drumEngine.generateRandomLogicalPattern()
                engine.benchmarkReferenceRecorder.recordDrumState()
                setGridRefreshTrigger(System.currentTimeMillis())
                Toast.makeText(context, "מקצב אקראי הגיוני נוצר", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(20.dp),
            shape = RoundedCornerShape(4.dp)
        ) { Text("Random", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    }
    Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 0.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        val currentActiveStep = remember(gridRefreshTrigger) { engine.drumEngine.currentStep }
        val visibleDrumTracks = if (drumExtrasOpen) 8 else 4
        for (t in 0 until visibleDrumTracks) {
            val trackName = engine.drumEngine.trackNames[t]
            val isSampleLoaded = engine.drumEngine.drumSamples[t] != null
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${t + 1}. $trackName", color = if (isSampleLoaded) gold else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp), maxLines = 1)
                    OutlinedButton(
                        onClick = { setActiveLoadingTrack(t); loadDrumSample("audio/*") },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(16.dp)
                    ) { Text(if (isSampleLoaded) "החלף" else "טעון סאמפל", fontSize = 7.sp, color = gold) }
                    Slider(
                        value = trackVolStates[t].value,
                        onValueChange = {
                            trackVolStates[t].value = it
                            engine.drumEngine.trackVolumes[t] = it
                            engine.benchmarkReferenceRecorder.recordDrumState()
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(16.dp),
                        colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold, inactiveTrackColor = Color(0xFF333333))
                    )
                    Text("${(trackVolStates[t].value * 100).toInt()}", color = gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                    LrPanKnob(trackPanStates[t].value, gold, 20.dp) { p ->
                        trackPanStates[t].value = p
                        engine.drumEngine.setTrackPan(t, p)
                        engine.benchmarkReferenceRecorder.recordDrumState()
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (s in 0 until 16) {
                        val isActive = engine.drumEngine.grid[t][s]
                        val isCurrentStep = drumPlayingState && s == currentActiveStep
                        Box(
                            modifier = Modifier.weight(1f).height(18.dp)
                                .background(
                                    when {
                                        isActive && isCurrentStep -> Color.White
                                        isActive -> gold
                                        isCurrentStep -> Color(0xFF333333)
                                        else -> panelBg2
                                    },
                                    RoundedCornerShape(3.dp)
                                )
                                .border(1.dp, if (isCurrentStep) gold else Color(0xFF2A2A2A), RoundedCornerShape(3.dp))
                                .clickable {
                                    engine.drumEngine.grid[t][s] = !isActive
                                    engine.benchmarkReferenceRecorder.recordDrumState()
                                    setGridRefreshTrigger(System.currentTimeMillis())
                                }
                        )
                    }
                }
            }
        }
    }
}
}
