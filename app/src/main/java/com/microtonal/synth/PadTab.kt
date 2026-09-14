package com.microtonal.synth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
internal fun PadTab(
    engine: SynthEngine,
    gold: Color,
    panelBg2: Color,
    padTargetKey: Boolean,
    setPadTargetKey: (Boolean) -> Unit,
    padTargetMic: Boolean,
    setPadTargetMic: (Boolean) -> Unit,
    padTargetLoop: Boolean,
    setPadTargetLoop: (Boolean) -> Unit,
    padTargetDrum: Boolean,
    setPadTargetDrum: (Boolean) -> Unit,
    padWahVal: Float,
    setPadWahVal: (Float) -> Unit,
    padOctVal: Float,
    setPadOctVal: (Float) -> Unit,
    padChoVal: Float,
    setPadChoVal: (Float) -> Unit,
    subOn: Boolean,
    setSubOn: (Boolean) -> Unit,
    warmOn: Boolean,
    setWarmOn: (Boolean) -> Unit,
    vibeOn: Boolean,
    setVibeOn: (Boolean) -> Unit
) {
Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("LIVE PERFORMANCE PAD", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple("KEY", padTargetKey) { val next = !padTargetKey; setPadTargetKey(next); engine.padTargetKey = next },
            Triple("MIC", padTargetMic) { val next = !padTargetMic; setPadTargetMic(next); engine.padTargetMic = next },
            Triple("LOOP", padTargetLoop) { val next = !padTargetLoop; setPadTargetLoop(next); engine.padTargetLoop = next },
            Triple("DRUM", padTargetDrum) { val next = !padTargetDrum; setPadTargetDrum(next); engine.padTargetDrum = next }
        ).forEach { (label, on, toggle) ->
            Button(
                onClick = toggle,
                colors = ButtonDefaults.buttonColors(containerColor = if (on) gold else panelBg2),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).height(24.dp),
                shape = RoundedCornerShape(4.dp)
            ) { Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (on) Color.Black else gold) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier.fillMaxWidth().height(148.dp).background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun applyAt(px: Float, py: Float, first: Boolean) {
                    val x = (px / size.width.toFloat()).coerceIn(0f, 1f)
                    val y = 1f - (py / size.height.toFloat()).coerceIn(0f, 1f)
                    engine.busPadX = x
                    engine.busPadY = y
                    engine.busPadTouched = true
                    engine.benchmarkReferenceRecorder.recordPad(x, y, true)
                    if (first) {
                        engine.padOriginX = x
                        engine.padOriginY = y
                    }
                    val mx = kotlin.math.abs(x - engine.padOriginX) * 2f
                    val my = kotlin.math.abs(y - engine.padOriginY) * 2f
                    engine.padModX = mx.coerceIn(0f, 1f)
                    engine.padModY = my.coerceIn(0f, 1f)
                    if (engine.padTargetKey) {
                        engine.performanceX = engine.padModX
                        engine.performanceY = engine.padModY
                    } else {
                        engine.performanceX = 0f
                        engine.performanceY = 0f
                    }
                }
                applyAt(down.position.x, down.position.y, first = true)
                drag(down.id) { change ->
                    applyAt(change.position.x, change.position.y, first = false)
                }
                engine.benchmarkReferenceRecorder.recordPad(engine.busPadX, engine.busPadY, false)
                engine.busPadTouched = false
            }
        }
    ) {
        PadXyCursor(engine, gold)
        Text("L  ·  R", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        Text("HIGH", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp))
        Text("BASS", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp))
    }
    PadLiveFxRow(
        gold = gold,
        wah = padWahVal,
        oct = padOctVal,
        cho = padChoVal,
        onWah = { setPadWahVal(it); engine.padWah = it },
        onOct = { setPadOctVal(it); engine.padOct = it },
        onCho = { setPadChoVal(it); engine.padCho = it }
    )
    Row(modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            Triple("SUB", subOn) { val next = !subOn; setSubOn(next); engine.subOn = next },
            Triple("WARM", warmOn) { val next = !warmOn; setWarmOn(next); engine.warmOn = next },
            Triple("VIBE", vibeOn) { val next = !vibeOn; setVibeOn(next); engine.vibeOn = next }
        ).forEach { (label, enabled, toggle) ->
            Button(
                onClick = toggle,
                colors = ButtonDefaults.buttonColors(containerColor = if (enabled) gold else panelBg2),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color.Black else gold)
                    Text(
                        when (label) { "SUB" -> "באס −1 אוקטבה"; "WARM" -> "חום אנלוגי"; else -> "ויברטו עדין" },
                        fontSize = 7.sp,
                        color = if (enabled) Color.Black.copy(alpha = 0.7f) else Color.Gray
                    )
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(4 to "1/4", 8 to "1/8", 16 to "1/16", 32 to "1/32").forEach { (div, label) ->
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            LaunchedEffect(pressed, div) {
                if (pressed) engine.busStutterDiv = div else if (engine.busStutterDiv == div) engine.busStutterDiv = 0
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
        val stopSrc = remember { MutableInteractionSource() }
        val stopPressed by stopSrc.collectIsPressedAsState()
        LaunchedEffect(stopPressed) { engine.busHoldStop = stopPressed }
        Button(
            onClick = {},
            interactionSource = stopSrc,
            colors = ButtonDefaults.buttonColors(containerColor = if (stopPressed) Color(0xFFFF1744) else panelBg2),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(6.dp)
        ) { Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (stopPressed) Color.White else Color(0xFFFF8A80)) }
    }
}
}
