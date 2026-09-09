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
    var referenceSession by remember { mutableStateOf<BenchmarkReferenceSession?>(null) }
    var loadingReference by remember { mutableStateOf(true) }
    var preparingReference by remember { mutableStateOf(false) }
    var referenceGeneration by remember { mutableStateOf(0) }
    // Recording belongs to SynthEngine and must survive closing this Dialog.
    // The local state is only a UI mirror; the engine is the source of truth.
    var recordingReference by remember {
        mutableStateOf(engine.benchmarkReferenceRecorder.isRecording())
    }
    var finalizingReference by remember { mutableStateOf(false) }
    var deletingReference by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        loadingReference = true
        val generationAtStart = ++referenceGeneration
        val loaded = withContext(Dispatchers.IO) {
            BenchmarkReferenceIO.loadInternal(context)
        }
        // Only the newest load operation may publish a disk result. If a newer
        // record/import operation started meanwhile, its own completion handler
        // owns the UI state.
        if (referenceGeneration == generationAtStart) {
            referenceSession = loaded
            loadingReference = false
        }
    }

    val exportReferenceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val session = referenceSession
        if (uri != null && session != null) {
            scope.launch(Dispatchers.IO) {
                val saved = BenchmarkReferenceIO.exportToUri(context, uri, session)
                withContext(Dispatchers.Main) {
                    statusMsg = if (saved) "Reference ZIP saved" else "Reference export failed"
                }
            }
        }
    }
    val importReferenceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val importGeneration = ++referenceGeneration
            loadingReference = true
            scope.launch(Dispatchers.IO) {
                val loaded = BenchmarkReferenceIO.importFromUri(context, uri)
                val saved = loaded?.let { BenchmarkReferenceIO.saveInternal(context, it) } == true
                withContext(Dispatchers.Main) {
                    if (referenceGeneration == importGeneration) {
                        referenceSession = loaded
                        loadingReference = false
                        statusMsg = when {
                            loaded == null -> "Reference import failed"
                            saved -> "Reference loaded: ${loaded.events.size} events"
                            else -> "Reference loaded, but saving failed"
                        }
                    }
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
            else if (loadingReference) "Loading saved reference…"
            else referenceSession?.let { "Loaded: ${it.events.size} events · ${it.durationUs / 1_000_000.0f}s · loops ${it.loops.size}" } ?: "No reference session loaded",
            color = Color.LightGray, fontSize = 10.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (!recordingReference) {
                        bench.requestCancel()
                        referenceGeneration++
                        loadingReference = false
                        preparingReference = true
                        statusMsg = "Preparing reference…"
                        scope.launch(Dispatchers.IO) {
                            try {
                                // start() performs the initial PCM snapshot before it marks
                                // the recorder active. Running it here keeps the UI responsive
                                // while making the beginning of the reference deterministic.
                                engine.benchmarkReferenceRecorder.start()
                                withContext(Dispatchers.Main) {
                                    preparingReference = false
                                    recordingReference = engine.benchmarkReferenceRecorder.isRecording()
                                    statusMsg = if (recordingReference) "Recording started" else "Reference recording failed"
                                }
                            } catch (_: Throwable) {
                                withContext(Dispatchers.Main) {
                                    preparingReference = false
                                    recordingReference = engine.benchmarkReferenceRecorder.isRecording()
                                    statusMsg = "Reference recording failed"
                                }
                            }
                        }
                    } else {
                        // STOP can copy several seconds of PCM from the looper and then
                        // write a ZIP. Never do that work on the Compose/UI thread:
                        // a long recording can otherwise make the whole app appear frozen.
                        finalizingReference = true
                        referenceGeneration++
                        statusMsg = "Finishing recording…"
                        scope.launch(Dispatchers.IO) {
                            val session = engine.benchmarkReferenceRecorder.stop()
                            val saved = session?.let { BenchmarkReferenceIO.saveInternal(context, it) } == true
                            withContext(Dispatchers.Main) {
                                referenceSession = session
                                recordingReference = engine.benchmarkReferenceRecorder.isRecording()
                                finalizingReference = false
                                statusMsg = when {
                                    session == null -> "Reference recording failed"
                                    saved -> "Reference captured: ${session.events.size} events"
                                    else -> "Reference captured, but saving failed"
                                }
                            }
                        }
                    }
                },
                enabled = !running && !finalizingReference && !preparingReference && !loadingReference && !deletingReference,
                colors = ButtonDefaults.buttonColors(containerColor = if (recordingReference) Color(0xFFB71C1C) else gold),
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text(
                if (finalizingReference) "FINISHING…" else if (preparingReference) "PREPARING…" else if (recordingReference) "STOP RECORDING" else "RECORD SESSION", color = if (recordingReference) Color.White else Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            OutlinedButton(
                onClick = { exportReferenceLauncher.launch("LStudio_Reference_${System.currentTimeMillis()}.zip") },
                enabled = referenceSession != null && !recordingReference && !running && !loadingReference && !deletingReference,
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("EXPORT ZIP", color = gold, fontSize = 9.sp) }
            OutlinedButton(
                onClick = { importReferenceLauncher.launch("application/zip") },
                enabled = !recordingReference && !running && !loadingReference && !deletingReference,
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("IMPORT ZIP", color = gold, fontSize = 9.sp) }
        }
        OutlinedButton(
            onClick = {
                if (deletingReference) return@OutlinedButton
                deletingReference = true
                referenceGeneration++
                statusMsg = "Deleting reference…"
                scope.launch(Dispatchers.IO) {
                    val deleted = BenchmarkReferenceIO.deleteInternal(context)
                    withContext(Dispatchers.Main) {
                        deletingReference = false
                        loadingReference = false
                        if (deleted) {
                            referenceSession = null
                            report = null
                            compareText = ""
                            statusMsg = "Reference deleted. You can record a new session."
                        } else {
                            statusMsg = "Reference delete failed"
                        }
                    }
                }
            },
            enabled = referenceSession != null &&
                !recordingReference &&
                !running &&
                !loadingReference &&
                !finalizingReference &&
                !preparingReference &&
                !deletingReference,
            modifier = Modifier.fillMaxWidth().height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text(
                if (deletingReference) "DELETING…" else "DELETE REFERENCE / RECORD NEW",
                color = gold,
                fontSize = 9.sp
            )
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
            enabled = referenceSession != null && !recordingReference && !running && !loadingReference,
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

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val session = referenceSession ?: return@Button
                running = true
                statusMsg = ""
                compareText = ""
                report = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        bench.runReferenceBlocking(
                            context, session, includeLooper, includeDrums, deepProfile = true
                        )
                    }
                    report = result
                    phaseLabel = if (result.verdict == BenchVerdict.ERROR) "ERROR" else "DSP PROFILE COMPLETED"
                    statusMsg = result.errorMessage ?: ""
                    running = false
                }
            },
            enabled = referenceSession != null && !recordingReference && !running && !loadingReference,
            colors = ButtonDefaults.buttonColors(
                containerColor = panelBg2,
                contentColor = gold,
                disabledContainerColor = panelBg2,
                disabledContentColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth().height(36.dp)
        ) {
            Text(
                if (running) "PROFILING RECORDED DSP…" else "PROFILE RECORDED DSP",
                color = gold,
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
            Text("SCORE: ${r.score}/100", color = verdictColor(r.verdict), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
        if (r.referenceDurationMs > 0L) {
            Text("Reference replay: recorded settings/events", color = gray, fontSize = 10.sp)
            Text("Looper replay: ${onOff(r.includeLooper)}   Drums replay: ${onOff(r.includeDrums)}", color = gray, fontSize = 10.sp)
            Text("Reference BPM: ${String.format("%.2f", r.bpm)}   Wave: ${r.waveformType}", color = gray, fontSize = 10.sp)
            Text("Warm-up: none   Measurement: ${String.format("%.2fs", r.referenceDurationMs / 1000.0)}", color = gray, fontSize = 10.sp)
        } else {
            Text("Stress workload: generated benchmark", color = gray, fontSize = 10.sp)
            Text("Warm-up: 3s   Measurement: ${AudioBenchmark.MEASURE_MS / 1000}s", color = gray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("Processing Time", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Average: ${nsToMs(r.avgNs)}", color = gray, fontSize = 10.sp)
        Text("P50: ${nsToMs(r.p50Ns)}", color = gray, fontSize = 10.sp)
        Text("P95: ${nsToMs(r.p95Ns)}", color = gray, fontSize = 10.sp)
        Text("P99: ${nsToMs(r.p99Ns)}", color = gray, fontSize = 10.sp)
        Text("Max: ${nsToMs(r.maxNs)}", color = gray, fontSize = 10.sp)
        Text("Deadline misses: ${r.misses}", color = gray, fontSize = 10.sp)
        Text("Miss rate: ${String.format("%.4f", r.missRate * 100.0)}%", color = gray, fontSize = 10.sp)
        Text("Score: ${r.score}/100", color = gray, fontSize = 10.sp)
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
        if (r.deepProfiled) {
            Spacer(Modifier.height(4.dp))
            Text("DSP DIAGNOSTIC — sampled execution estimate", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Sampled DSP points: ${r.profileSamples} (1/128 audio samples)", color = gray, fontSize = 9.sp)
            Text("Voice: ${nsToMs(r.profileVoiceAvgNs)}   Osc: ${nsToMs(r.profileOscillatorAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Main Osc: ${nsToMs(r.profileOscMainAvgNs)}   Piano: ${nsToMs(r.profileOscPianoAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Sub: ${nsToMs(r.profileOscSubAvgNs)}   Detune: ${nsToMs(r.profileOscDetuneAvgNs)}   Vibe: ${nsToMs(r.profileVibeAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Div core: ${nsToMs(r.profileOscDividersAvgNs)}   Div2 contrib: ${nsToMs(r.profileOscDiv2AvgNs)}   Div3 contrib: ${nsToMs(r.profileOscDiv3AvgNs)}", color = gray, fontSize = 10.sp)
            Text("Div4 contrib: ${nsToMs(r.profileOscDiv4AvgNs)}", color = gray, fontSize = 10.sp)
            Text("Warm: ${nsToMs(r.profileWarmAvgNs)}   Rip: ${nsToMs(r.profileRipAvgNs)}   Fuzz: ${nsToMs(r.profileFuzzAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Phaz: ${nsToMs(r.profilePhazAvgNs)}   Key-bus Wah: ${nsToMs(r.profileWahAvgNs)}   Key-bus Oct: ${nsToMs(r.profileOctAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Key-bus Cho: ${nsToMs(r.profileChoAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Pad Wah: ${nsToMs(r.profilePadWahAvgNs)}   Pad Oct: ${nsToMs(r.profilePadOctAvgNs)}   Pad Cho: ${nsToMs(r.profilePadChoAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Reverb: ${nsToMs(r.profileReverbAvgNs)}   Delay: ${nsToMs(r.profileDelayAvgNs)}   Drive: ${nsToMs(r.profileDriveAvgNs)}", color = gray, fontSize = 10.sp)
            Text("Method: sampled execution timing, 1/128 audio samples; Pad effects are extrapolated from observed active stems; not CPU-cycle measurement", color = Color.DarkGray, fontSize = 9.sp)
        }
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
