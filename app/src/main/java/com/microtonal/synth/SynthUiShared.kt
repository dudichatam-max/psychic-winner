package com.microtonal.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MiniWaveMeter(
    buffer: FloatArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            tick++
        }
    }
    val meterPath = remember { Path() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .background(Color(0xFF0D0D0D), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .padding(2.dp)
    ) {
        val pulse = tick
        val w = size.width
        val h = size.height
        val mid = h / 2f
        if (buffer.isEmpty() || w <= 0f) return@Canvas
        meterPath.reset()
        val n = buffer.size
        val step = w / n.coerceAtLeast(1)
        var i = 0
        while (i < n) {
            val sample = buffer[i]
            val x = i * step
            val y = mid + (sample.coerceIn(-1f, 1f) * mid * 0.9f)
            if (i == 0) meterPath.moveTo(x, y) else meterPath.lineTo(x, y)
            i++
        }
        drawLine(Color(0xFF2A2A2A), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
        drawPath(meterPath, color, style = Stroke(width = 1.6f))
    }
}

@Composable
internal fun PadLiveFxRow(
    gold: Color,
    wah: Float,
    oct: Float,
    cho: Float,
    onWah: (Float) -> Unit,
    onOct: (Float) -> Unit,
    onCho: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SynthKnob("WAH", "${(wah * 100).toInt()}%", wah, 0f..1f, gold, 34.dp, onValueChange = onWah)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SynthKnob("OCT", "${(oct * 100).toInt()}%", oct, 0f..1f, gold, 34.dp, onValueChange = onOct)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SynthKnob("CHO", "${(cho * 100).toInt()}%", cho, 0f..1f, gold, 34.dp, onValueChange = onCho)
        }
    }
}

@Composable
internal fun PadXyCursor(
    engine: SynthEngine,
    gold: Color,
    modifier: Modifier = Modifier
) {
    var padTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            padTick++
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val dummy = padTick
        val w = size.width
        val h = size.height
        val gridColor = Color(0xFF1F1F1F)
        for (i in 1..4) {
            drawLine(gridColor, Offset(w * (i / 5f), 0f), Offset(w * (i / 5f), h))
            drawLine(gridColor, Offset(0f, h * (i / 5f)), Offset(w, h * (i / 5f)))
        }
        drawLine(Color(0xFF2A2A2A), Offset(0f, h), Offset(w, h), 2f)
        drawLine(Color(0xFF2A2A2A), Offset(0f, 0f), Offset(0f, h), 2f)
        val cx = engine.busPadX
        val cy = engine.busPadY
        val cursorX = cx * w
        val cursorY = (1f - cy) * h
        drawCircle(gold.copy(alpha = 0.2f), 40f, Offset(cursorX, cursorY))
        drawCircle(gold, 16f, Offset(cursorX, cursorY), style = Stroke(width = 3f))
        drawCircle(Color.White, 3f, Offset(cursorX, cursorY))
    }
}

@Composable
internal fun QuickBrowsePanel(
    caption: String,
    value: String,
    textColor: Color,
    accent: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = if (compact) 1.dp else 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(caption, color = textColor, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = onPrev,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(accent)
                )
            ) { Text("‹", color = accent, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold) }
            Text(
                text = value,
                color = textColor,
                fontSize = if (compact) 8.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            OutlinedButton(
                onClick = onNext,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(accent)
                )
            ) { Text("›", color = accent, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

internal fun panCaption(p: Float): String {
    return when {
        p < -0.02f -> "L${((-p) * 100f).toInt()}"
        p > 0.02f -> "R${((p) * 100f).toInt()}"
        else -> "C"
    }
}

@Composable
internal fun LrPanKnob(
    pan: Float,
    gold: Color,
    knobSize: Dp,
    onChange: (Float) -> Unit
) {
    val valueRef = remember { mutableStateOf(pan) }
    valueRef.value = pan
    var initialValue by remember { mutableStateOf(pan) }
    var totalDragY by remember { mutableStateOf(0f) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("L", color = Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            initialValue = valueRef.value
                            totalDragY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount.y
                            onChange((initialValue - totalDragY / 180f).coerceIn(-1f, 1f))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val c = center
                drawCircle(color = Color(0xFF1F1F1F), radius = radius, center = c)
                drawCircle(color = Color(0xFF2A2A2A), radius = radius, center = c, style = Stroke(width = 1.5f))
                val fraction = ((pan + 1f) / 2f).coerceIn(0f, 1f)
                val angle = Math.toRadians((135f + fraction * 270f).toDouble())
                val line = radius * 0.65f
                drawLine(
                    color = gold,
                    start = c,
                    end = Offset(
                        c.x + line * kotlin.math.cos(angle).toFloat(),
                        c.y + line * kotlin.math.sin(angle).toFloat()
                    ),
                    strokeWidth = 2.5f
                )
            }
        }
        Text("R", color = Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
    }
}
