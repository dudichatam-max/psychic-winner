package com.microtonal.synth

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BenchmarkScreen(
    engine: SynthEngine,
    context: Context,
    onClose: () -> Unit
) {
    val gold = Color(0xFFD4AF37)
    val panelBg = Color(0xFF141414)
    val panelBg2 = Color(0xFF1A1A1A)
    val bench = engine.audioBenchmark
    val scope = rememberCoroutineScope()

    var includeLooper by remember { mutableStateOf(true) }
    var includeDrums by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var phaseLabel by remember { mutableStateOf("READY") }
    var remainSec by remember { mutableStateOf(0L) }
    var liveBuffers by remember { mutableStateOf(0) }
    var liveMisses by remember { mutableStateOf(0) }
    var liveCpu by remember { mutableStateOf(0.0) }
    var report by remember { mutableStateOf(bench.lastReport) }
    var statusMsg by remember { mutableStateOf("") }
    var compareText by remember { mutableStateOf("") }
    var referenceSession by remember { mutableStateOf(BenchmarkReferenceIO.loadInternal(context)) }
    // Recording belongs to SynthEngine and must survive closing this Dialog.
    // The local state is only a UI mirror; the engine is the source of truth.
    var recordingReference by remember {
        mutableStateOf(engine.benchmarkReferenceRecorder.isRecording())
    }

    val exportReferenceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val session = referenceSession
        if (uri != null && session != null) {
            statusMsg = if (BenchmarkReferenceIO.exportToUri(context, uri, session)) "Reference ZIP saved" else "Reference export failed"
        }
    }
    val importReferenceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val loaded = BenchmarkReferenceIO.importFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    referenceSession = loaded
                    loaded?.let { BenchmarkReferenceIO.saveInternal(context, it) }
                    statusMsg = if (loaded != null) "Reference loaded: ${loaded.events.size} events" else "Reference import failed"
                }
            }
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            phaseLabel = when (bench.phase) {
                BenchPhase.IDLE -> "READY"
                BenchPhase.WARMUP -> "WARM-UP"
                BenchPhase.MEASURE -> "RUNNING"
                BenchPhase.COMPLETED -> "COMPLETED"
                BenchPhase.ERROR -> "ERROR"
            }
            remainSec = (bench.remainingMs + 999L) / 1000L
            liveBuffers = bench.liveBuffers
            liveMisses = bench.liveMisses
            liveCpu = bench.liveCpuPct
            delay(250)
        }
    }
    
    // Keep the dialog state synchronized with the engine while this screen is visible.
    // Closing the dialog destroys this Composable, but it does not stop the engine recorder.
    LaunchedEffect(Unit) {
        while (true) {
            recordingReference = engine.benchmarkReferenceRecorder.isRecording()
            delay(100)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .background(panelBg, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BENCHMARK", color = gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = {
                    // Never stop the reference recorder here. It lives in SynthEngine,
                    // so the user can close this window, play physically, then reopen it
                    // and press STOP RECORDING.
                    if (running) bench.requestCancel()
                    onClose()
                },
                modifier = Modifier.height(26.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("Close", color = gold, fontSize = 9.sp) }
        }

        Text("Status: $phaseLabel", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

        Text("REFERENCE SESSION", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            if (recordingReference) "Recording your real performance… play normally, then press STOP RECORDING"
            else referenceSession?.let { "Loaded: ${it.events.size} events · ${it.durationUs / 1_000_000.0f}s · loops ${it.loops.size}" } ?: "No reference session loaded",
            color = Color.LightGray, fontSize = 10.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (!recordingReference) {
                        bench.requestCancel()
                        engine.benchmarkReferenceRecorder.start()
                        recordingReference = true
                        statusMsg = "Recording started"
                    } else {
                        referenceSession = engine.benchmarkReferenceRecorder.stop()
                        referenceSession?.let { BenchmarkReferenceIO.saveInternal(context, it) }
                        recordingReference = engine.benchmarkReferenceRecorder.isRecording()
                        statusMsg = referenceSession?.let {
                            "Reference captured: ${it.events.size} events"
                        } ?: "Reference recording failed"
                    }
                },
                enabled = !running,
                colors = ButtonDefaults.buttonColors(containerColor = if (recordingReference) Color(0xFFB71C1C) else gold),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text(if (recordingReference) "STOP RECORDING" else "RECORD SESSION", color = if (recordingReference) Color.White else Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            OutlinedButton(
                onClick = { exportReferenceLauncher.launch("LStudio_Reference_${System.currentTimeMillis()}.zip") },
                enabled = referenceSession != null && !recordingReference && !running,
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("EXPORT ZIP", color = gold, fontSize = 9.sp) }
            OutlinedButton(
                onClick = { importReferenceLauncher.launch("application/zip") },
                enabled = !recordingReference && !running,
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("IMPORT ZIP", color = gold, fontSize = 9.sp) }
        }
        Button(
            onClick = {
                val session = referenceSession ?: return@Button
                running = true
                statusMsg = ""
                compareText = ""
                report = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        bench.runReferenceBlocking(context, session, includeLooper, includeDrums)
                    }
                    report = result
                    phaseLabel = if (result.verdict == BenchVerdict.ERROR) "ERROR" else "REFERENCE COMPLETED"
                    statusMsg = result.errorMessage ?: ""
                    running = false
                }
            },
            enabled = referenceSession != null && !recordingReference && !running,
            colors = ButtonDefaults.buttonColors(
                containerColor = gold,
                contentColor = Color.Black,
                disabledContainerColor = panelBg2,
                disabledContentColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth().height(38.dp)
        ) {
            Text(
                if (running) "RUNNING RECORDED BENCHMARK…" else "RUN RECORDED BENCHMARK",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }

        Text("EXTREME STRESS", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeLooper,
                onCheckedChange = { if (!running) includeLooper = it },
                enabled = !running,
                colors = CheckboxDefaults.colors(checkedColor = gold)
            )
            Text("Include Looper", color = Color.White, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeDrums,
                onCheckedChange = { if (!running) includeDrums = it },
                enabled = !running,
                colors = CheckboxDefaults.colors(checkedColor = gold)
            )
            Text("Include Drums", color = Color.White, fontSize = 11.sp)
        }

        Button(
            onClick = {
                if (running) return@Button
                running = true
                statusMsg = ""
                compareText = ""
                report = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        bench.runBlockingWorkload(context, includeLooper, includeDrums)
                    }
                    report = result
                    phaseLabel = if (result.verdict == BenchVerdict.ERROR) "ERROR" else "COMPLETED"
                    statusMsg = result.errorMessage ?: ""
                    running = false
                }
            },
            enabled = !running,
            colors = ButtonDefaults.buttonColors(containerColor = gold),
            modifier = Modifier.fillMaxWidth().height(36.dp)
        ) {
            Text(if (running) "RUNNING…" else "START STRESS TEST", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        if (running) {
            val total = AudioBenchmark.TOTAL_MS.toFloat().coerceAtLeast(1f)
            val done = 1f - (bench.remainingMs / total)
            LinearProgressIndicator(
                progress = done.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = gold,
                trackColor = panelBg2
            )
            Text("Remaining: ${remainSec}s   Buffers: $liveBuffers   Misses: $liveMisses   CPU: ${String.format("%.1f", liveCpu)}%", color = Color.LightGray, fontSize = 10.sp)
        }

        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, color = Color(0xFFFF8A80), fontSize = 10.sp)
        }

        val r = report
        if (r != null) {
            Text("BENCHMARK COMPLETE", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Result: ${r.verdict.name}", color = verdictColor(r.verdict), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            ReportBlock(r)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        statusMsg = if (bench.saveAsBefore(context)) "Saved as BEFORE" else "Save BEFORE failed"
                    },
                    enabled = r.verdict != BenchVerdict.ERROR,
                    colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                    modifier = Modifier.weight(1f).height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) { Text("Save as Before", color = gold, fontSize = 9.sp) }
                Button(
                    onClick = {
                        val before = bench.loadBefore(context)
                        compareText = if (before == null) {
                            "No BEFORE saved"
                        } else if (!bench.comparisonAllowed(before, r)) {
                            "Workload mismatch — comparison unavailable"
                        } else {
                            formatCompare(before, r)
                        }
                    },
                    enabled = r.verdict != BenchVerdict.ERROR,
                    colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                    modifier = Modifier.weight(1f).height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) { Text("Compare", color = gold, fontSize = 9.sp) }
            }
            if (compareText.isNotEmpty()) {
                Text(compareText, color = Color.White, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ReportBlock(r: BenchReport) {
    val gray = Color(0xFFCCCCCC)
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Device: ${r.device}", color = gray, fontSize = 10.sp)
        Text("Android: ${r.androidVersion}", color = gray, fontSize = 10.sp)
        Text("App version: ${r.appVersion}", color = gray, fontSize = 10.sp)
        Text("Sample Rate: ${r.sampleRate}", color = gray, fontSize = 10.sp)
        Text("Buffer: ${r.bufferFrames}", color = gray, fontSize = 10.sp)
        Text("Deadline: ${nsToMs(r.deadlineNs)}", color = gray, fontSize = 10.sp)
        Text("Polyphony: Dynamic 1–8", color = gray, fontSize = 10.sp)
        Text("Maximum simultaneous voices: 8", color = gray, fontSize = 10.sp)
        Text("Detune: ON   Warm: ON   Reverb: 100%   Pad/LFO: ON", color = gray, fontSize = 10.sp)
        Text("Looper: ${onOff(r.includeLooper)}${if (r.includeLooper) "   Tracks: 6" else ""}", color = gray, fontSize = 10.sp)
        Text("Drums: ${onOff(r.includeDrums)}   BPM: ${r.bpm}", color = gray, fontSize = 10.sp)
        Text("Warm-up: 3s   Measurement: 30s   Wave: ${r.waveformType}", color = gray, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text("Processing Time", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Average: ${nsToMs(r.avgNs)}", color = gray, fontSize = 10.sp)
        Text("P50: ${nsToMs(r.p50Ns)}", color = gray, fontSize = 10.sp)
        Text("P95: ${nsToMs(r.p95Ns)}", color = gray, fontSize = 10.sp)
        Text("P99: ${nsToMs(r.p99Ns)}", color = gray, fontSize = 10.sp)
        Text("Max: ${nsToMs(r.maxNs)}", color = gray, fontSize = 10.sp)
        Text("Deadline misses: ${r.misses}", color = gray, fontSize = 10.sp)
        Text("Miss rate: ${String.format("%.4f", r.missRate * 100.0)}%", color = gray, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text("Realtime", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            if (r.underrunsAvailable) "Underruns delta: ${r.deltaUnderruns}" else "Underruns: n/a",
            color = gray,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(4.dp))
        Text("CPU (process-level estimate)", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Average: ${String.format("%.2f", r.cpuAvgPct)}%", color = gray, fontSize = 10.sp)
        Text("Peak: ${String.format("%.2f", r.cpuPeakPct)}%", color = gray, fontSize = 10.sp)
    }
}

private fun onOff(v: Boolean) = if (v) "ON" else "OFF"

private fun verdictColor(v: BenchVerdict): Color = when (v) {
    BenchVerdict.PASS -> Color(0xFFB8E986)
    BenchVerdict.WARNING -> Color(0xFFFFE082)
    BenchVerdict.FAIL -> Color(0xFFFF8A80)
    BenchVerdict.ERROR -> Color(0xFFFF8A80)
}

private fun formatCompare(before: BenchReport, after: BenchReport): String {
    return buildString {
        append("BEFORE vs AFTER\n")
        append("Avg  ${nsToMs(before.avgNs)}  →  ${nsToMs(after.avgNs)}\n")
        append("P50  ${nsToMs(before.p50Ns)}  →  ${nsToMs(after.p50Ns)}\n")
        append("P95  ${nsToMs(before.p95Ns)}  →  ${nsToMs(after.p95Ns)}\n")
        append("P99  ${nsToMs(before.p99Ns)}  →  ${nsToMs(after.p99Ns)}\n")
        append("Max  ${nsToMs(before.maxNs)}  →  ${nsToMs(after.maxNs)}\n")
        append("CPU  ${String.format("%.2f", before.cpuAvgPct)}%  →  ${String.format("%.2f", after.cpuAvgPct)}%\n")
        append("Misses  ${before.misses}  →  ${after.misses}\n")
        append("Underruns  ${if (before.underrunsAvailable) before.deltaUnderruns.toString() else "n/a"}  →  ${if (after.underrunsAvailable) after.deltaUnderruns.toString() else "n/a"}\n")
        append("Result  ${before.verdict}  →  ${after.verdict}")
    }
}
