package com.microtonal.synth


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun SynthKnob(
    label: String,
    valueDisplay: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = Color(0xFFD4AF37),
    knobSize: Dp = 52.dp,
    valueBeside: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    var showInputDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf(value.toString()) }
    var initialValue by remember { mutableStateOf(value) }
    var totalDragY by remember { mutableStateOf(0f) }

    // Always read the live value on drag-start (avoids jumps from a stale pointerInput capture).
    val valueRef = remember { mutableStateOf(value) }
    valueRef.value = value


    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = {
                Text("הזנת ערך עבור $label", fontSize = 13.sp, color = accentColor, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("הכנס ערך בין ${valueRange.start} ל-${valueRange.endInclusive}:", color = Color.White, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 11.sp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    textInput.toFloatOrNull()?.let { inputVal ->
                        onValueChange(inputVal.coerceIn(valueRange))
                    }
                    showInputDialog = false
                }) { Text("אישור") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInputDialog = false }) { Text("ביטול") }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }


    val valueText: @Composable () -> Unit = {
        Text(
            text = valueDisplay,
            color = accentColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .heightIn(min = 12.dp)
                .padding(horizontal = 1.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        textInput = String.format("%.2f", valueRef.value)
                        showInputDialog = true
                    })
                }
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(2.dp)
            .wrapContentSize()
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))


        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(knobSize)
                    .pointerInput(valueRange) {
                        detectDragGestures(
                            onDragStart = {
                                // Relative drag from the CURRENT value — no snap to finger position.
                                initialValue = valueRef.value
                                totalDragY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDragY += dragAmount.y
                                val span = valueRange.endInclusive - valueRange.start
                                // ~360px of vertical travel covers the full range (finer live control).
                                val sensitivity = span / 360f
                                val newValue = (initialValue - totalDragY * sensitivity).coerceIn(valueRange)
                                onValueChange(newValue)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    val center = center


                    drawCircle(color = Color(0xFF1F1F1F), radius = radius, center = center)
                    drawCircle(color = Color(0xFF2A2A2A), radius = radius, center = center, style = Stroke(width = 1.5f))


                    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                    val angleDegrees = 135f + fraction * 270f
                    val angleRadians = Math.toRadians(angleDegrees.toDouble())


                    val lineLength = radius * 0.65f
                    val endX = center.x + (lineLength * kotlin.math.cos(angleRadians)).toFloat()
                    val endY = center.y + (lineLength * kotlin.math.sin(angleRadians)).toFloat()


                    drawLine(
                        color = accentColor,
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 2.5f
                    )
                }
            }

            if (valueBeside) {
                Spacer(Modifier.width(4.dp))
                valueText()
            }
        }


        if (!valueBeside) {
            Spacer(Modifier.height(1.dp))
            valueText()
        }
    }
}
