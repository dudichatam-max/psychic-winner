package com.microtonal.synth
 

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private fun syncLiveToLooper(
    engine: SynthEngine,
    context: Context,
    vol: Float,
    cutoffVal: Float,
    resVal: Float,
    attackVal: Float,
    decayVal: Float,
    sustainVal: Float,
    releaseVal: Float,
    echoVal: Float,
    glideVal: Float,
    setLooperVolState: (Float) -> Unit,
    setLooperCutoffState: (Float) -> Unit,
    setLooperResState: (Float) -> Unit,
    setLooperAttackState: (Float) -> Unit,
    setLooperDecayState: (Float) -> Unit,
    setLooperSustainState: (Float) -> Unit,
    setLooperReleaseState: (Float) -> Unit,
    setLooperEchoState: (Float) -> Unit,
    setLooperGlideState: (Float) -> Unit
) {
    setLooperVolState(vol)
    engine.looperVolume = vol
    engine.setLooperVol(vol)
    setLooperCutoffState(cutoffVal)
    engine.looperCutoff = cutoffVal
    setLooperResState(resVal)
    engine.looperResonance = resVal
    setLooperAttackState(attackVal)
    engine.looperAttack = attackVal
    setLooperDecayState(decayVal)
    engine.looperDecay = decayVal
    setLooperSustainState(sustainVal)
    engine.looperSustain = sustainVal
    setLooperReleaseState(releaseVal)
    engine.looperRelease = releaseVal
    setLooperEchoState(echoVal)
    engine.looperEcho = echoVal
    setLooperGlideState(glideVal)
    engine.looperGlide = glideVal
    Toast.makeText(context, "Synch: Live → Looper", Toast.LENGTH_SHORT).show()
}

@Composable
private fun MiniWaveMeter(
    buffer: FloatArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .background(Color(0xFF0D0D0D), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .padding(2.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        if (buffer.isEmpty() || w <= 0f) return@Canvas
        val path = Path()
        val step = w / buffer.size.coerceAtLeast(1)
        buffer.forEachIndexed { i, sample ->
            val x = i * step
            val y = mid + (sample.coerceIn(-1f, 1f) * mid * 0.9f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Color(0xFF2A2A2A), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
        drawPath(path, color, style = Stroke(width = 1.6f))
    }
}


@Composable
private fun VoiceActivityMeter(
    activeVoices: Int,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val n = activeVoices.coerceIn(0, 8)
    val shade = if (n <= 0) 1f else (1f - (n / 8f)).coerceIn(0f, 1f)
    val onColor = Color(shade, shade, shade)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("VOICES", color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        repeat(8) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(13.dp)
                    .background(if (i < n) onColor else Color(0xFF1A1A1A), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(2.dp))
            )
        }
    }
}


@Composable
private fun QuickBrowsePanel(
    caption: String,
    value: String,
    textColor: Color,
    accent: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(caption, color = textColor, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = onPrev,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(22.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(accent)
                )
            ) { Text("‹", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Text(
                text = value,
                color = textColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            OutlinedButton(
                onClick = onNext,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(22.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(accent)
                )
            ) { Text("›", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("synth_presets", Context.MODE_PRIVATE) }

    val defaultFrequencies = remember {
        listOf(222.00f, 299.00f, 333.00f, 355.00f, 396.00f, 444.00f, 463.00f, 477.00f)
    }
    val frequencies = remember {
        mutableStateListOf(222.00f, 299.00f, 333.00f, 355.00f, 396.00f, 444.00f, 463.00f, 477.00f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var showTuningDialog by remember { mutableStateOf(false) }
    var showPresetWindow by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("SOUND") }

    var currentWave by remember { mutableStateOf(3) }
    var vol by remember { mutableStateOf(0.5f) }
    var attackVal by remember { mutableStateOf(15f) }
    var decayVal by remember { mutableStateOf(50f) }
    var sustainVal by remember { mutableStateOf(0.8f) }
    var releaseVal by remember { mutableStateOf(200f) }
    var driveVal by remember { mutableStateOf(0.35f) }
    var detuneOn by remember { mutableStateOf(false) }
    var subOn by remember { mutableStateOf(false) }
    var warmOn by remember { mutableStateOf(false) }
    var vibeOn by remember { mutableStateOf(false) }

    var cutoffVal by remember { mutableStateOf(5000f) }
    var resVal by remember { mutableStateOf(0.3f) }
    var echoVal by remember { mutableStateOf(0.25f) }
    var glideVal by remember { mutableStateOf(30f) }

    var currentOctave by remember { mutableStateOf(0) }
    var isRec by remember { mutableStateOf(false) }
    var isMidiRec by remember { mutableStateOf(false) }

    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    val loopRecStates = remember { mutableStateListOf(*Array(20) { false }) }
    val loopPlayStates = remember { mutableStateListOf(*Array(20) { false }) }
    val loopChannelVolStates = remember { mutableStateListOf(*Array(20) { 1.0f }) }
    val loopChannelNames = remember { mutableStateListOf(*Array(20) { "ערוץ ${(it % 5) + 1}" }) }
    var looperProjectName by remember { mutableStateOf("פרויקט לופר") }
    var looperPage by remember { mutableStateOf(0) }
    var pendingLoopLoadTrack by remember { mutableStateOf(0) }
    var pendingLoopSaveTrack by remember { mutableStateOf(0) }
    var pendingMicLoadTrack by remember { mutableStateOf(0) }
    var pendingMicSaveTrack by remember { mutableStateOf(0) }
    val micRecStates = remember { mutableStateListOf(*Array(6) { false }) }
    val micPlayStates = remember { mutableStateListOf(*Array(6) { false }) }
    val micVolStates = remember { mutableStateListOf(*Array(6) { 1.0f }) }
    val micNames = remember { mutableStateListOf("ווקאל 1", "ווקאל 2", "ווקאל 3", "גיטרה", "באס", "אינסט 3") }
    val micHpfCh = remember { mutableStateListOf(*Array(6) { 80f }) }
    val micGateCh = remember { mutableStateListOf(*Array(6) { 0.012f }) }
    val micPresCh = remember { mutableStateListOf(*Array(6) { 1.1f }) }
    val micCompCh = remember { mutableStateListOf(*Array(6) { 0.25f }) }
    var micPage by remember { mutableStateOf(0) }
    var micProjectName by remember { mutableStateOf("פרויקט מיק") }
    var padFlipOn by remember { mutableStateOf(false) }
    var micMonitorOn by remember { mutableStateOf(false) }
    var micMonitorVol by remember { mutableStateOf(1.0f) }
    var micGain by remember { mutableStateOf(2.0f) }
    var micHpf by remember { mutableStateOf(50f) }
    var micGate by remember { mutableStateOf(0.002f) }
    var micLow by remember { mutableStateOf(1.0f) }
    var micPresence by remember { mutableStateOf(1.0f) }
    var micComp by remember { mutableStateOf(0.0f) }
    var extVolState by remember { mutableStateOf(1.0f) }
    var extPlayState by remember { mutableStateOf(false) }
    var extLoadedState by remember { mutableStateOf(false) }

    var looperVolState by remember { mutableStateOf(1.0f) }
    var looperCutoffState by remember { mutableStateOf(5000f) }
    var looperResState by remember { mutableStateOf(0.3f) }
    var looperAttackState by remember { mutableStateOf(15f) }
    var looperDecayState by remember { mutableStateOf(50f) }
    var looperSustainState by remember { mutableStateOf(0.8f) }
    var looperReleaseState by remember { mutableStateOf(200f) }
    var looperEchoState by remember { mutableStateOf(0.25f) }
    var looperGlideState by remember { mutableStateOf(30f) }

    var drumBpmState by remember { mutableStateOf(120f) }
    var drumVolState by remember { mutableStateOf(0.8f) }
    var drumSwingState by remember { mutableStateOf(0f) }
    var drumPlayingState by remember { mutableStateOf(false) }
    val trackVolStates = remember { List(4) { mutableStateOf(1.0f) } }
    var activeLoadingTrack by remember { mutableStateOf(0) }
    var gridRefreshTrigger by remember { mutableStateOf(0L) }
    var useDefaultKit by remember { mutableStateOf(true) }
    var defaultKitLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val meta = engine.loadLooperSession()
        if (meta != null) {
            looperProjectName = meta.projectName
            looperPage = meta.currentPage.coerceIn(0, 3)
            for (i in 0 until 20) {
                loopChannelNames[i] = meta.channelNames.getOrElse(i) { "ערוץ ${(i % 5) + 1}" }
                loopChannelVolStates[i] = meta.volumes.getOrElse(i) { 1.0f }
                loopPlayStates[i] = engine.isTrackPlaying(i)
                loopRecStates[i] = engine.isTrackRecording(i)
            }
        }
    }
    var selectedDrumPattern by remember { mutableStateOf(0) }
    var drumTabInitialized by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var editingKitIndex by remember { mutableStateOf(-1) }
    var tempKitName by remember { mutableStateOf("") }

    var selectedPresetPage by remember { mutableStateOf(1) }
    var activeLoadedPage by remember { mutableStateOf(-1) }
    var activeLoadedSlot by remember { mutableStateOf(-1) }

    val pageNames = remember {
        mutableStateMapOf<Int, String>().apply {
            for (p in 1..8) {
                put(p, prefs.getString("page_${p}_name", "עמוד $p") ?: "עמוד $p")
            }
        }
    }

    val presetNames = remember {
        mutableStateMapOf<String, String>().apply {
            for (p in 1..8) {
                for (s in 1..8) {
                    val key = "p_${p}_s_${s}"
                    put(key, prefs.getString("${key}_name", "פריסט $s") ?: "פריסט $s")
                }
            }
        }
    }

    var editingPageId by remember { mutableStateOf<Int?>(null) }
    var tempPageNameInput by remember { mutableStateOf("") }
    var editingPresetKey by remember { mutableStateOf<String?>(null) }
    var tempPresetNameInput by remember { mutableStateOf("") }

    val gold = Color(0xFFD4AF37)
    val darkBg = Color(0xFF0A0A0A)
    val panelBg = Color(0xFF141414)
    val panelBg2 = Color(0xFF1A1A1A)

    val createWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            val success = engine.exportRecordingToUri(context, it)
            Toast.makeText(context, if (success) "ההקלטה נשמרה בהצלחה!" else "שגיאה בשמירת הקובץ", if (success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    val createMidiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        uri?.let {
            val success = MidiExporter.exportMidiToUri(context, engine.recordedMidiNotes.toList(), it)
            Toast.makeText(context, if (success) "קובץ ה-MIDI נשמר בהצלחה!" else "שגיאה בשמירת קובץ ה-MIDI", if (success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    val loadAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val trackIdx = pendingLoopLoadTrack
            scope.launch(Dispatchers.IO) {
                val ok = engine.loadAudioIntoTrack(context, trackIdx, it)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        loopRecStates[trackIdx] = false
                        loopPlayStates[trackIdx] = false
                        engine.saveLooperSession(looperProjectName, loopChannelNames.toList(), looperPage)
                        Toast.makeText(context, "קובץ נטען לערוץ ${(trackIdx % 5) + 1}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "שגיאה בטעינת קובץ האודיו", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val saveLoopTrackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            val trackIdx = pendingLoopSaveTrack
            val ok = engine.exportTrackWavToUri(context, trackIdx, it)
            Toast.makeText(context, if (ok) "הערוץ נשמר כ-WAV" else "אין הקלטה לשמירה או שגיאה", if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    val loadMicAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val trackIdx = pendingMicLoadTrack
            scope.launch(Dispatchers.IO) {
                val ok = engine.loadAudioIntoMicTrack(context, trackIdx, it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "קובץ נטען לערוץ מיק ${trackIdx + 1}" else "שגיאה בטעינה", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val saveMicTrackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            val ok = engine.exportMicTrackWavToUri(context, pendingMicSaveTrack, it)
            Toast.makeText(context, if (ok) "ערוץ המיק נשמר כ-WAV" else "אין הקלטה לשמירה", Toast.LENGTH_SHORT).show()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(context, "נדרשת הרשאת מיקרופון להקלטה", Toast.LENGTH_LONG).show()
    }

    val exportMicProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val ok = engine.exportMicProjectToZip(context, it, micProjectName, micNames.toList(), micPage)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "פרויקט המיק יוצא בהצלחה" else "שגיאה בייצוא פרויקט המיק", if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importMicProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val meta = engine.importMicProjectFromZip(context, it)
                withContext(Dispatchers.Main) {
                    if (meta != null) {
                        micProjectName = meta.projectName
                        micPage = meta.currentPage.coerceIn(0, 1)
                        for (i in 0 until 6) {
                            micNames[i] = meta.channelNames.getOrElse(i) { micNames[i] }
                            micVolStates[i] = meta.volumes.getOrElse(i) { 1.0f }
                            micRecStates[i] = engine.micEngine.tracks[i].isRecording
                            micPlayStates[i] = engine.micEngine.tracks[i].isPlaying
                        }
                        Toast.makeText(context, "כל ששת ערוצי המיק נטענו", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "שגיאה בייבוא פרויקט המיק", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val exportLooperProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val ok = engine.exportLooperProjectToZip(context, it, looperProjectName, loopChannelNames.toList(), looperPage)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "פרויקט הלופר יוצא בהצלחה" else "שגיאה בייצוא פרויקט הלופר", if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLooperProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val meta = engine.importLooperProjectFromZip(context, it)
                withContext(Dispatchers.Main) {
                    if (meta != null) {
                        looperProjectName = meta.projectName
                        looperPage = meta.currentPage.coerceIn(0, 3)
                        for (i in 0 until 20) {
                            loopChannelNames[i] = meta.channelNames.getOrElse(i) { "ערוץ ${(i % 5) + 1}" }
                            loopChannelVolStates[i] = meta.volumes.getOrElse(i) { 1.0f }
                            loopRecStates[i] = engine.isTrackRecording(i)
                            loopPlayStates[i] = engine.isTrackPlaying(i)
                        }
                        Toast.makeText(context, "פרויקט הלופר נטען", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "שגיאה בייבוא פרויקט הלופר", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val loadDrumSampleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val trackIdx = activeLoadingTrack
            CoroutineScope(Dispatchers.IO).launch {
                val ok = engine.drumEngine.loadSample(context, trackIdx, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (ok) "סאמפל נטען לערוץ ${engine.drumEngine.trackNames[trackIdx]}"
                        else "שגיאה בטעינת קובץ האודיו (פורמט לא נתמך או קובץ פגום)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val exportDrumKitsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                engine.drumEngine.saveCurrentKit(engine.drumEngine.currentKitIndex, context)
                PresetManager.saveAllDrumKits(prefs, engine)
                val ok = engine.drumEngine.exportAllKitsToZip(context, it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "כל 8 ערכות התופים יוצאו בהצלחה!" else "שגיאה בייצוא ערכות התופים", if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importDrumKitsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val ok = engine.drumEngine.importAllKitsFromZip(context, it)
                if (ok) PresetManager.saveAllDrumKits(prefs, engine)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        selectedDrumPattern = engine.drumEngine.currentPatternIndex
                        drumBpmState = engine.drumEngine.bpm
                        drumVolState = engine.drumEngine.masterVolume
                        drumSwingState = engine.drumEngine.swing
                        for (t in 0 until 4) trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
                        gridRefreshTrigger = System.currentTimeMillis()
                        Toast.makeText(context, "כל 8 ערכות התופים יובאו בהצלחה!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "שגיאה בייבוא ערכות התופים", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun loadPresetFromSlot(page: Int, slot: Int, showToast: Boolean = true) {
        val key = "p_${page}_s_${slot}"
        if (!prefs.getBoolean("${key}_exists", false)) {
            if (showToast) Toast.makeText(context, "פריסט $slot בעמוד $page עדיין ריק", Toast.LENGTH_SHORT).show()
            return
        }
        vol = prefs.getFloat("${key}_vol", 0.5f); engine.volume = vol
        attackVal = prefs.getFloat("${key}_attack", 15f); engine.attackMs = attackVal
        decayVal = prefs.getFloat("${key}_decay", 50f); engine.decayMs = decayVal
        sustainVal = prefs.getFloat("${key}_sustain", 0.8f); engine.sustainLevel = sustainVal
        releaseVal = prefs.getFloat("${key}_release", 200f); engine.releaseMs = releaseVal
        cutoffVal = prefs.getFloat("${key}_cutoff", 5000f); engine.cutoffFreq = cutoffVal
        resVal = prefs.getFloat("${key}_res", 0.3f); engine.resonance = resVal
        echoVal = prefs.getFloat("${key}_echo", 0.25f); engine.echoMix = echoVal
        glideVal = prefs.getFloat("${key}_glide", 30f); engine.glideMs = glideVal
        currentWave = prefs.getInt("${key}_wave", 3); engine.setLiveWaveform(currentWave)
        currentOctave = prefs.getInt("${key}_octave", 0); engine.octaveShift = currentOctave
        val freqsStr = prefs.getString("${key}_freqs", null)
        if (freqsStr != null) {
            val list = freqsStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == frequencies.size) list.forEachIndexed { i, f -> frequencies[i] = f }
        }
        activeLoadedPage = page
        activeLoadedSlot = slot
        if (showToast) Toast.makeText(context, "פריסט $slot בעמוד $page נטען", Toast.LENGTH_SHORT).show()
    }

    fun savePresetToSlot(page: Int, slot: Int) {
        val key = "p_${page}_s_${slot}"
        val currentName = presetNames[key] ?: "פריסט $slot"
        prefs.edit().apply {
            putString("${key}_name", currentName)
            putFloat("${key}_vol", vol)
            putFloat("${key}_attack", attackVal)
            putFloat("${key}_decay", decayVal)
            putFloat("${key}_sustain", sustainVal)
            putFloat("${key}_release", releaseVal)
            putFloat("${key}_cutoff", cutoffVal)
            putFloat("${key}_res", resVal)
            putFloat("${key}_echo", echoVal)
            putFloat("${key}_glide", glideVal)
            putInt("${key}_wave", currentWave)
            putInt("${key}_octave", currentOctave)
            putString("${key}_freqs", frequencies.joinToString(","))
            putBoolean("${key}_exists", true)
            apply()
        }
        activeLoadedPage = page
        activeLoadedSlot = slot
        Toast.makeText(context, "פריסט $slot בעמוד $page נשמר בהצלחה!", Toast.LENGTH_SHORT).show()
    }

    fun listExistingPresetSlots(): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        for (p in 1..8) for (s in 1..8) {
            if (prefs.getBoolean("p_${p}_s_${s}_exists", false)) out.add(p to s)
        }
        return out
    }

    fun browsePreset(step: Int) {
        val slots = listExistingPresetSlots()
        if (slots.isEmpty()) {
            Toast.makeText(context, "אין פריסטים שמורים", Toast.LENGTH_SHORT).show()
            return
        }
        val current = activeLoadedPage to activeLoadedSlot
        val idx = slots.indexOf(current)
        val nextIdx = if (idx < 0) {
            if (step >= 0) 0 else slots.lastIndex
        } else {
            (idx + step + slots.size) % slots.size
        }
        val pair = slots[nextIdx]
        loadPresetFromSlot(pair.first, pair.second)
    }

    fun browseDrumKit(step: Int) {
        val next = (engine.drumEngine.currentKitIndex + step + 8) % 8
        engine.drumEngine.loadKit(next, context)
        selectedDrumPattern = engine.drumEngine.currentPatternIndex
        drumBpmState = engine.drumEngine.bpm
        drumVolState = engine.drumEngine.masterVolume
        drumSwingState = engine.drumEngine.swing
        for (t in 0 until 4) trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
        gridRefreshTrigger = System.currentTimeMillis()
        PresetManager.saveCurrentDrumSelection(prefs, engine)
        Toast.makeText(context, "סגנון ${engine.drumEngine.kits[next].name} נטען", Toast.LENGTH_SHORT).show()
    }

    val exportPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val sb = StringBuilder()
                val page = selectedPresetPage
                val pNameHeader = pageNames[page] ?: "עמוד $page"
                sb.append("page:$page|$pNameHeader\n")
                for (s in 1..8) {
                    val key = "p_${page}_s_${s}"
                    val pName = presetNames[key] ?: "פריסט $s"
                    sb.append("preset:$s|$pName|")
                    sb.append("${prefs.getFloat("${key}_vol", 0.5f)},")
                    sb.append("${prefs.getFloat("${key}_attack", 15f)},")
                    sb.append("${prefs.getFloat("${key}_decay", 50f)},")
                    sb.append("${prefs.getFloat("${key}_sustain", 0.8f)},")
                    sb.append("${prefs.getFloat("${key}_release", 200f)},")
                    sb.append("${prefs.getFloat("${key}_cutoff", 5000f)},")
                    sb.append("${prefs.getFloat("${key}_res", 0.3f)},")
                    sb.append("${prefs.getFloat("${key}_echo", 0.25f)},")
                    sb.append("${prefs.getFloat("${key}_glide", 30f)},")
                    sb.append("${prefs.getInt("${key}_wave", 3)},")
                    sb.append("${prefs.getInt("${key}_octave", 0)}|")
                    sb.append("${prefs.getString("${key}_freqs", "")}\n")
                }
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(sb.toString().toByteArray())
                }
                Toast.makeText(context, "פריסטים של עמוד $page יוצאו בהצלחה!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "שגיאה בייצוא פריסטים", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = stream.bufferedReader().readLines()
                    val editor = prefs.edit()
                    val page = selectedPresetPage
                    for (line in lines) {
                        if (line.startsWith("page:")) {
                            val parts = line.split("|")
                            if (parts.size >= 2) {
                                val name = parts[1]
                                pageNames[page] = name
                                editor.putString("page_${page}_name", name)
                            }
                        } else if (line.startsWith("preset:")) {
                            val parts = line.split("|")
                            if (parts.size >= 4) {
                                val slot = parts[0].removePrefix("preset:").toIntOrNull() ?: continue
                                val name = parts[1]
                                val key = "p_${page}_s_${slot}"
                                presetNames[key] = name
                                editor.putString("${key}_name", name)
                                val values = parts[2].split(",")
                                if (values.size >= 11) {
                                    editor.putFloat("${key}_vol", values[0].toFloatOrNull() ?: 0.5f)
                                    editor.putFloat("${key}_attack", values[1].toFloatOrNull() ?: 15f)
                                    editor.putFloat("${key}_decay", values[2].toFloatOrNull() ?: 50f)
                                    editor.putFloat("${key}_sustain", values[3].toFloatOrNull() ?: 0.8f)
                                    editor.putFloat("${key}_release", values[4].toFloatOrNull() ?: 200f)
                                    editor.putFloat("${key}_cutoff", values[5].toFloatOrNull() ?: 5000f)
                                    editor.putFloat("${key}_res", values[6].toFloatOrNull() ?: 0.3f)
                                    editor.putFloat("${key}_echo", values[7].toFloatOrNull() ?: 0.25f)
                                    editor.putFloat("${key}_glide", values[8].toFloatOrNull() ?: 30f)
                                    editor.putInt("${key}_wave", values[9].toIntOrNull() ?: 3)
                                    editor.putInt("${key}_octave", values[10].toIntOrNull() ?: 0)
                                }
                                editor.putString("${key}_freqs", parts[3])
                                editor.putBoolean("${key}_exists", true)
                            }
                        }
                    }
                    editor.apply()
                }
                loadPresetFromSlot(selectedPresetPage, 1, showToast = false)
                Toast.makeText(context, "פריסטים יובאו בהצלחה לעמוד $selectedPresetPage!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "שגיאה בייבוא פריסטים", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (prefs.getBoolean("p_1_exists", false) && !prefs.getBoolean("p_1_s_1_exists", false)) {
            val editor = prefs.edit()
            for (i in 1..8) {
                val oldKey = "p_$i"
                val newKey = "p_1_s_$i"
                if (prefs.getBoolean("${oldKey}_exists", false)) {
                    editor.putString("${newKey}_name", prefs.getString("${oldKey}_name", "Preset $i"))
                    editor.putFloat("${newKey}_vol", prefs.getFloat("${oldKey}_vol", 0.5f))
                    editor.putFloat("${newKey}_attack", prefs.getFloat("${oldKey}_attack", 15f))
                    editor.putFloat("${newKey}_decay", prefs.getFloat("${oldKey}_decay", 50f))
                    editor.putFloat("${newKey}_sustain", prefs.getFloat("${oldKey}_sustain", 0.8f))
                    editor.putFloat("${newKey}_release", prefs.getFloat("${oldKey}_release", 200f))
                    editor.putFloat("${newKey}_cutoff", prefs.getFloat("${oldKey}_cutoff", 5000f))
                    editor.putFloat("${newKey}_res", prefs.getFloat("${oldKey}_res", 0.3f))
                    editor.putFloat("${newKey}_echo", prefs.getFloat("${oldKey}_echo", 0.25f))
                    editor.putFloat("${newKey}_glide", prefs.getFloat("${oldKey}_glide", 30f))
                    editor.putInt("${newKey}_wave", prefs.getInt("${oldKey}_wave", 3))
                    editor.putInt("${newKey}_octave", prefs.getInt("${oldKey}_octave", 0))
                    editor.putString("${newKey}_freqs", prefs.getString("${oldKey}_freqs", ""))
                    editor.putBoolean("${newKey}_exists", true)
                }
            }
            editor.apply()
        }
        loadPresetFromSlot(1, 1, showToast = false)
    }

    var renderTrigger by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            renderTrigger = System.currentTimeMillis()
            gridRefreshTrigger = engine.drumEngine.currentStep.toLong()
        }
    }

    if (editingPageId != null) {
        AlertDialog(
            onDismissRequest = { editingPageId = null },
            title = { Text("ערוך שם עמוד $editingPageId", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempPageNameInput,
                    onValueChange = { tempPageNameInput = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    editingPageId?.let { id ->
                        pageNames[id] = tempPageNameInput
                        prefs.edit().putString("page_${id}_name", tempPageNameInput).apply()
                    }
                    editingPageId = null
                }) { Text("שמור") }
            },
            dismissButton = { OutlinedButton(onClick = { editingPageId = null }) { Text("ביטול") } },
            containerColor = panelBg2
        )
    }

    if (editingPresetKey != null) {
        AlertDialog(
            onDismissRequest = { editingPresetKey = null },
            title = { Text("ערוך שם פריסט", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempPresetNameInput,
                    onValueChange = { tempPresetNameInput = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    editingPresetKey?.let { key ->
                        presetNames[key] = tempPresetNameInput
                        prefs.edit().putString("${key}_name", tempPresetNameInput).apply()
                    }
                    editingPresetKey = null
                }) { Text("שמור") }
            },
            dismissButton = { OutlinedButton(onClick = { editingPresetKey = null }) { Text("ביטול") } },
            containerColor = panelBg2
        )
    }

    if (showTuningDialog) {
        val freqTexts = remember { mutableStateListOf(*frequencies.map { it.toString() }.toTypedArray()) }
        AlertDialog(
            onDismissRequest = { showTuningDialog = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("כיוון תדרים (Hz)", fontSize = 14.sp, color = gold, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = {
                            defaultFrequencies.forEachIndexed { i, f ->
                                frequencies[i] = f
                                freqTexts[i] = f.toString()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("איפוס", color = gold, fontSize = 9.sp) }
                }
            },
            text = {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    itemsIndexed(frequencies) { index, _ ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(noteNames[index], color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = freqTexts[index],
                                onValueChange = { newValue ->
                                    freqTexts[index] = newValue
                                    newValue.toFloatOrNull()?.let { frequencies[index] = it }
                                },
                                modifier = Modifier.width(68.dp),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, color = Color.White)
                            )
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showTuningDialog = false }) { Text("סגור") } },
            containerColor = panelBg2
        )
    }

    if (showStyleDialog) {
        AlertDialog(
            onDismissRequest = { showStyleDialog = false },
            title = { Text("ניהול סגנונות תופים (8 סגנונות)", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { importDrumKitsLauncher.launch("application/zip") },
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = PaddingValues(4.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("ייבוא ערכות", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { exportDrumKitsLauncher.launch("Siren_DrumKits_${System.currentTimeMillis()}.zip") },
                            colors = ButtonDefaults.buttonColors(containerColor = gold),
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = PaddingValues(4.dp)
                        ) { Text("ייצוא כל הערכות", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(8) { kitIdx ->
                            val kit = engine.drumEngine.kits[kitIdx]
                            val isCurrentKit = engine.drumEngine.currentKitIndex == kitIdx
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isCurrentKit) gold.copy(alpha = 0.2f) else panelBg2, RoundedCornerShape(6.dp))
                                    .border(1.dp, if (isCurrentKit) gold else Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${kitIdx + 1}. ${kit.name}", color = if (isCurrentKit) gold else Color.White, fontSize = 11.sp, fontWeight = if (isCurrentKit) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        onClick = {
                                            engine.drumEngine.loadKit(kitIdx, context)
                                            selectedDrumPattern = engine.drumEngine.currentPatternIndex
                                            drumBpmState = engine.drumEngine.bpm
                                            drumVolState = engine.drumEngine.masterVolume
                                            drumSwingState = engine.drumEngine.swing
                                            for (t in 0 until 4) trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
                                            gridRefreshTrigger = System.currentTimeMillis()
                                            PresetManager.saveCurrentDrumSelection(prefs, engine)
                                            Toast.makeText(context, "סגנון ${kit.name} נטען", Toast.LENGTH_SHORT).show()
                                            showStyleDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = gold),
                                        contentPadding = PaddingValues(4.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) { Text("טען", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                                    Button(
                                        onClick = {
                                            engine.drumEngine.saveCurrentKit(kitIdx, context)
                                            PresetManager.saveAllDrumKits(prefs, engine)
                                            Toast.makeText(context, "סגנון ${kit.name} נשמר בהצלחה", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                                        contentPadding = PaddingValues(4.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) { Text("שמור", fontSize = 9.sp, color = Color.White) }
                                    Button(
                                        onClick = {
                                            editingKitIndex = kitIdx
                                            tempKitName = kit.name
                                            showStyleDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                                        contentPadding = PaddingValues(4.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) { Text("ערוך", fontSize = 9.sp, color = Color.White) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showStyleDialog = false }) { Text("סגור") } },
            containerColor = panelBg2
        )
    }

    if (showPresetWindow) {
        Dialog(onDismissRequest = { showPresetWindow = false }) {
            Surface(color = panelBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("פריסטים 8 חריצים", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { showPresetWindow = false }, modifier = Modifier.height(22.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("סגור", color = gold, fontSize = 8.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (p in 1..8) {
                            val isPageSelected = selectedPresetPage == p
                            Button(
                                onClick = { selectedPresetPage = p },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isPageSelected) gold else panelBg2),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.weight(1f).height(22.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) { Text("$p", color = if (isPageSelected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    val currentPName = pageNames[selectedPresetPage] ?: "עמוד $selectedPresetPage"
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("עמוד $selectedPresetPage: $currentPName", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { editingPageId = selectedPresetPage; tempPageNameInput = currentPName },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(20.dp)
                        ) { Text("ערוך שם עמוד", fontSize = 8.sp, color = gold) }
                    }
                    val rows = (1..8).chunked(2)
                    rows.forEach { rowSlots ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowSlots.forEach { slot ->
                                val key = "p_${selectedPresetPage}_s_${slot}"
                                val pName = presetNames[key] ?: "פריסט $slot"
                                val isThisSlotLoaded = (activeLoadedPage == selectedPresetPage && activeLoadedSlot == slot)
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .background(if (isThisSlotLoaded) gold.copy(alpha = 0.2f) else panelBg2, RoundedCornerShape(4.dp))
                                        .border(1.dp, if (isThisSlotLoaded) gold else Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("$slot. $pName", color = if (isThisSlotLoaded) gold else Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f), fontWeight = if (isThisSlotLoaded) FontWeight.Bold else FontWeight.Normal)
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Button(onClick = { loadPresetFromSlot(selectedPresetPage, slot) }, colors = ButtonDefaults.buttonColors(containerColor = gold), contentPadding = PaddingValues(2.dp), modifier = Modifier.height(20.dp).width(28.dp)) { Text("טען", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                                            Button(onClick = { savePresetToSlot(selectedPresetPage, slot) }, colors = ButtonDefaults.buttonColors(containerColor = panelBg2), contentPadding = PaddingValues(2.dp), modifier = Modifier.height(20.dp).width(30.dp)) { Text("שמור", fontSize = 8.sp, color = Color.White) }
                                            Button(onClick = { editingPresetKey = key; tempPresetNameInput = pName }, colors = ButtonDefaults.buttonColors(containerColor = panelBg2), contentPadding = PaddingValues(2.dp), modifier = Modifier.height(20.dp).width(28.dp)) { Text("ערוך", fontSize = 8.sp, color = Color.White) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { importPresetLauncher.launch("application/json") },
                            modifier = Modifier.weight(1f).height(26.dp),
                            contentPadding = PaddingValues(2.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("ייבוא עמוד מ-JSON", color = gold, fontSize = 9.sp) }
                        Button(
                            onClick = { exportPresetLauncher.launch("Siren_Page${selectedPresetPage}_${System.currentTimeMillis()}.json") },
                            colors = ButtonDefaults.buttonColors(containerColor = gold),
                            modifier = Modifier.weight(1f).height(26.dp),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("ייצוא עמוד ל-JSON", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (editingKitIndex != -1) {
        AlertDialog(
            onDismissRequest = { editingKitIndex = -1 },
            title = { Text("ערוך שם סגנון", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempKitName,
                    onValueChange = { tempKitName = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (editingKitIndex in 0 until 8) {
                        engine.drumEngine.kits[editingKitIndex].name = tempKitName
                        prefs.edit().putString("drum_kit" + editingKitIndex + "_name", tempKitName).apply()
                    }
                    editingKitIndex = -1
                    showStyleDialog = true
                }) { Text("שמור") }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingKitIndex = -1; showStyleDialog = true }) { Text("ביטול") }
            },
            containerColor = panelBg2
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(darkBg).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SIREN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { showTuningDialog = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                ) { Text("⚙️ תדרים", color = gold, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        if (isMidiRec) {
                            isMidiRec = false
                            engine.stopMidiRecording()
                            createMidiLauncher.launch("Siren_MIDI_${System.currentTimeMillis()}.mid")
                        } else {
                            engine.startMidiRecording()
                            isMidiRec = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isMidiRec) Color(0xFF2979FF) else panelBg2),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(modifier = Modifier.size(5.dp).background(if (isMidiRec) Color.Red else Color.White, shape = CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text(if (isMidiRec) "שמור MIDI" else "MIDI", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (isRec) {
                            engine.stopAndSaveRecordingAsync { file ->
                                isRec = false
                                file?.let { createWavLauncher.launch("Siren_Recording_${System.currentTimeMillis()}.wav") }
                            }
                        } else {
                            engine.startRecording()
                            isRec = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRec) Color(0xFFFF1744) else panelBg2),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(modifier = Modifier.size(5.dp).background(if (isRec) Color.Red else gold, shape = CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text(if (isRec) "שמור WAV" else "WAV", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(panelBg, shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF2A2A2A), shape = RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dummy = renderTrigger
                val w = size.width
                val h = size.height
                val halfH = h / 2f
                val gridColor = Color(0xFF1F1F1F)
                for (i in 1 until 8) drawLine(gridColor, Offset(w * (i / 8f), 0f), Offset(w * (i / 8f), h), 1f)
                for (i in 1 until 4) drawLine(gridColor, Offset(0f, h * (i / 4f)), Offset(w, h * (i / 4f)), 1f)
                drawLine(Color(0xFF2A2A2A), Offset(0f, halfH), Offset(w, halfH), 1.5f)
                val livePath = Path()
                val liveCenterY = halfH / 2f
                val liveStep = w / engine.liveVisualizerBuffer.size
                engine.liveVisualizerBuffer.forEachIndexed { i, sample ->
                    val x = i * liveStep
                    val y = liveCenterY + (sample * (halfH / 2f) * 0.85f)
                    if (i == 0) livePath.moveTo(x, y) else livePath.lineTo(x, y)
                }
                drawPath(livePath, gold, style = Stroke(width = 2f))
                val drumPath = Path()
                val drumCenterY = halfH + (halfH / 2f)
                val drumStep = w / engine.drumVisualizerBuffer.size
                engine.drumVisualizerBuffer.forEachIndexed { i, sample ->
                    val x = i * drumStep
                    val y = drumCenterY + (sample * (halfH / 2f) * 0.95f)
                    if (i == 0) drumPath.moveTo(x, y) else drumPath.lineTo(x, y)
                }
                drawPath(drumPath, Color.White.copy(alpha = 0.75f), style = Stroke(width = 2f))
            }
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE SCOPE", color = gold.copy(alpha = 0.8f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("DRUM SCOPE", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth().background(panelBg, shape = RoundedCornerShape(8.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LaunchedEffect(selectedTab, looperPage) {
                if (selectedTab == "LOOP") {
                    for (i in 0 until 20) {
                        loopPlayStates[i] = engine.isTrackPlaying(i)
                        loopRecStates[i] = engine.isTrackRecording(i)
                        loopChannelVolStates[i] = engine.looperTracks[i].volume
                    }
                } else {
                    engine.saveLooperSession(looperProjectName, loopChannelNames.toList(), looperPage)
                }
            }
            val tabs = listOf("SOUND", "MIC", "LOOP", "PAD", "DRUM")
            tabs.forEach { title ->
                val isSelected = selectedTab == title
                Button(
                    onClick = { selectedTab = title },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) panelBg2 else Color.Transparent),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(title, color = if (isSelected) gold else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(panelBg, shape = RoundedCornerShape(10.dp)).padding(6.dp)
        ) {
            when (selectedTab) {
                "SOUND" -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val loadedPresetName = if (activeLoadedPage >= 1 && activeLoadedSlot >= 1) {
                        presetNames["p_${activeLoadedPage}_s_${activeLoadedSlot}"] ?: "פריסט $activeLoadedSlot"
                    } else "אין פריסט"
                    val loadedKitName = engine.drumEngine.kits[engine.drumEngine.currentKitIndex.coerceIn(0, 7)].name
                    val liveVoices = remember(renderTrigger) { engine.activeVoiceCount() }

                    Column(
                        modifier = Modifier.fillMaxWidth().background(panelBg2, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { if (currentOctave > -2) { currentOctave--; engine.octaveShift = currentOctave } },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(17.dp)
                            ) { Text("-1", fontSize = 7.sp, color = Color.White) }
                            Spacer(Modifier.width(6.dp))
                            Text("Oct: $currentOctave", color = gold, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = { if (currentOctave < 2) { currentOctave++; engine.octaveShift = currentOctave } },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(17.dp)
                            ) { Text("+1", fontSize = 7.sp, color = Color.White) }
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = { showPresetWindow = true },
                                colors = ButtonDefaults.buttonColors(containerColor = gold),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(17.dp)
                            ) { Text("Prest", fontSize = 7.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            val waves = listOf("Sine", "Square", "Triangle", "Saw", "Noise")
                            waves.forEachIndexed { index, name ->
                                FilterChip(
                                    selected = currentWave == index,
                                    onClick = { currentWave = index; engine.setLiveWaveform(index) },
                                    label = { Text(name, fontSize = 6.sp, maxLines = 1) },
                                    modifier = Modifier.weight(1f).height(22.dp).padding(horizontal = 1.dp),
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = gold, selectedLabelColor = Color.Black)
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QuickBrowsePanel("PRESET", loadedPresetName, Color.White, gold, { browsePreset(-1) }, { browsePreset(1) }, Modifier.weight(1f))
                            QuickBrowsePanel("DRUM KIT", loadedKitName, Color.White, gold, { browseDrumKit(-1) }, { browseDrumKit(1) }, Modifier.weight(1f))
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).background(panelBg2, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Drive", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                            Slider(
                                value = driveVal,
                                onValueChange = { driveVal = it; engine.driveAmount = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f).height(20.dp),
                                colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold, inactiveTrackColor = Color(0xFF333333))
                            )
                            Text("${(driveVal * 100).toInt()}%", color = gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                            Button(
                                onClick = { detuneOn = !detuneOn; engine.detuneOn = detuneOn },
                                colors = ButtonDefaults.buttonColors(containerColor = if (detuneOn) gold else panelBg2),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("Detune", fontSize = 9.sp, color = if (detuneOn) Color.Black else gold, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                SynthKnob("Volume", "${(vol * 100).toInt()}%", vol, 0f..1f, gold, 40.dp) { vol = it; engine.volume = it }
                                SynthKnob("Attack", "${attackVal.toInt()}ms", attackVal, 5f..500f, gold, 40.dp) { attackVal = it; engine.attackMs = it }
                                SynthKnob("Decay", "${decayVal.toInt()}ms", decayVal, 5f..1000f, gold, 40.dp) { decayVal = it; engine.decayMs = it }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                SynthKnob("Sustain", "${(sustainVal * 100).toInt()}%", sustainVal, 0f..1f, gold, 40.dp) { sustainVal = it; engine.sustainLevel = it }
                                SynthKnob("Release", "${releaseVal.toInt()}ms", releaseVal, 20f..2000f, gold, 40.dp) { releaseVal = it; engine.releaseMs = it }
                                SynthKnob("Cutoff", "${cutoffVal.toInt()}Hz", cutoffVal, 200f..12000f, gold, 40.dp) { cutoffVal = it; engine.cutoffFreq = it }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                SynthKnob("Resonance", "${(resVal * 100).toInt()}%", resVal, 0f..1f, gold, 40.dp) { resVal = it; engine.resonance = it }
                                SynthKnob("Echo Mix", "${(echoVal * 100).toInt()}%", echoVal, 0f..1f, gold, 40.dp) { echoVal = it; engine.echoMix = it }
                                SynthKnob("Glide", "${glideVal.toInt()}ms", glideVal, 0f..200f, gold, 40.dp) { glideVal = it; engine.glideMs = it }
                            }
                        }
                    }
                    VoiceActivityMeter(liveVoices, Color.White, Modifier.padding(vertical = 4.dp))
                }

                "MIC" -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val scopeTick = renderTrigger
                    engine.refreshHeadphoneState()
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
                            onClick = { micPage = 0 },
                            colors = ButtonDefaults.buttonColors(containerColor = if (micPage == 0) gold else panelBg2),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(28.dp).height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("1", color = if (micPage == 0) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { micPage = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = if (micPage == 1) gold else panelBg2),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(28.dp).height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("2", color = if (micPage == 1) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!granted) { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return@Button }
                                engine.refreshHeadphoneState()
                                val next = !micMonitorOn
                                if (engine.micEngine.setMonitor(next)) {
                                    micMonitorOn = next
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
                        if (micMonitorOn) engine.micEngine.monitorVisualizer else FloatArray(128),
                        gold,
                        Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF0D0D0D), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        SynthKnob("Gain", String.format("%.1fx", micGain), (micGain - 1f) / 3f, 0f..1f, gold, 30.dp) { micGain = 1f + it * 3f; engine.micEngine.inputGain = micGain }
                        SynthKnob("Mon", "${(micMonitorVol * 100).toInt()}%", micMonitorVol, 0f..1.5f, gold, 30.dp) { micMonitorVol = it; engine.micEngine.monitorVolume = it }
                        SynthKnob("HPF", "${micHpf.toInt()}Hz", (micHpf - 40f) / 200f, 0f..1f, gold, 30.dp) { micHpf = 40f + it * 200f; engine.micEngine.hpfHz = micHpf }
                        SynthKnob("Gate", "${(micGate * 100).toInt()}", micGate, 0f..0.12f, gold, 30.dp) { micGate = it; engine.micEngine.gateThresh = it }
                        SynthKnob("Low", "${(micLow * 100).toInt()}%", micLow, 0.4f..1.6f, gold, 30.dp) { micLow = it; engine.micEngine.lowGain = it }
                        SynthKnob("Pres", "${(micPresence * 100).toInt()}%", micPresence, 0.4f..2f, gold, 30.dp) { micPresence = it; engine.micEngine.presenceGain = it }
                        SynthKnob("Comp", "${(micComp * 100).toInt()}%", micComp, 0f..1f, gold, 30.dp) { micComp = it; engine.micEngine.compAmount = it }
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
                                    micMonitorVol = engine.micEngine.monitorVolume
                                    micGain = engine.micEngine.inputGain
                                    micHpf = engine.micEngine.hpfHz
                                    micGate = engine.micEngine.gateThresh
                                    micLow = engine.micEngine.lowGain
                                    micPresence = engine.micEngine.presenceGain
                                    micComp = engine.micEngine.compAmount
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
                                            if (!granted) { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return@Button }
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
                                            pendingMicSaveTrack = track
                                            saveMicTrackLauncher.launch("Siren_Mic_T${track + 1}_${System.currentTimeMillis()}.wav")
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
                                    SynthKnob("Vol", "${(micVolStates[track] * 100).toInt()}%", micVolStates[track], 0f..1.5f, gold, 28.dp) {
                                        micVolStates[track] = it
                                        engine.micEngine.trackFx[track].volume = it
                                        engine.micEngine.tracks[track].volume = it
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
                            onClick = { importMicProjectLauncher.launch("application/zip") },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(2.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("ייבוא", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { exportMicProjectLauncher.launch("Siren_Mic_${System.currentTimeMillis()}.zip") },
                            colors = ButtonDefaults.buttonColors(containerColor = gold),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("ייצוא", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (scopeTick < 0L) Text("")
                }

                "LOOP" -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val scopeTick = renderTrigger
                    Row(
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BasicTextField(
                            value = looperProjectName,
                            onValueChange = { looperProjectName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                            modifier = Modifier.weight(1f).fillMaxHeight().background(panelBg, RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(gold)
                        )
                        for (p in 0 until 4) {
                            val selected = looperPage == p
                            Button(
                                onClick = { looperPage = p },
                                colors = ButtonDefaults.buttonColors(containerColor = if (selected) gold else panelBg2),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(26.dp).fillMaxHeight(),
                                shape = RoundedCornerShape(4.dp)
                            ) { Text("${p + 1}", color = if (selected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (row in 0 until 5) {
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
                                    onValueChange = { loopChannelNames[track] = it },
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
                                            engine.saveLooperSession(looperProjectName, loopChannelNames.toList(), looperPage)
                                        }
                                        else { engine.setTrackPlaying(track, false); loopPlayStates[track] = false; engine.startTrackRecording(track); loopRecStates[track] = true }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (rec) gold else panelBg2),
                                    modifier = Modifier.width(28.dp).fillMaxHeight(0.92f),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text(if (rec) "עצור" else "הקלט", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = if (rec) Color.Black else Color.White, textAlign = TextAlign.Center) }
                                Button(
                                    onClick = {
                                        if (rec) { engine.stopTrackRecording(track); loopRecStates[track] = false }
                                        loopPlayStates[track] = engine.toggleTrackPlayback(track)
                                    },
                                    enabled = playing || engine.trackHasContent(track) || rec,
                                    colors = ButtonDefaults.buttonColors(containerColor = if (playing) gold else panelBg2),
                                    modifier = Modifier.width(28.dp).fillMaxHeight(0.92f),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text(if (playing) "עצור" else "נגן", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = if (playing) Color.Black else Color.White, textAlign = TextAlign.Center) }
                                OutlinedButton(
                                    onClick = {
                                        pendingLoopLoadTrack = track
                                        loadAudioLauncher.launch("audio/*")
                                    },
                                    modifier = Modifier.width(28.dp).fillMaxHeight(0.92f),
                                    contentPadding = PaddingValues(0.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                                ) { Text("טען", color = gold, fontSize = 7.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(if (rec) "מקליט…" else if (playing) "מנגן" else if (engine.trackHasContent(track)) "מוכן" else "ריק", color = Color.Gray, fontSize = 7.sp, maxLines = 1)
                                    MiniWaveMeter(engine.looperTracks[track].visualizerBuffer, if (rec) gold else accent, Modifier.fillMaxWidth().weight(1f))
                                }
                                SynthKnob("Vol", "${(loopChannelVolStates[track] * 100).toInt()}%", loopChannelVolStates[track], 0f..1f, accent, 24.dp) {
                                    loopChannelVolStates[track] = it
                                    engine.setTrackVolume(track, it)
                                }
                                Column(
                                    modifier = Modifier.width(20.dp).fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    OutlinedButton(
                                        onClick = { engine.clearTrack(track); loopRecStates[track] = false; loopPlayStates[track] = false },
                                        modifier = Modifier.width(20.dp).weight(1f),
                                        contentPadding = PaddingValues(0.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray))
                                    ) { Text("נקה", color = Color.Gray, fontSize = 6.sp) }
                                    OutlinedButton(
                                        onClick = {
                                            pendingLoopSaveTrack = track
                                            saveLoopTrackLauncher.launch("Siren_Loop_P${looperPage + 1}_T${row + 1}_${System.currentTimeMillis()}.wav")
                                        },
                                        enabled = engine.trackHasContent(track),
                                        modifier = Modifier.width(20.dp).weight(1f),
                                        contentPadding = PaddingValues(0.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                                    ) { Text("שמור", color = gold, fontSize = 6.sp) }
                                }
                                }
                            }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { importLooperProjectLauncher.launch("application/zip") },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(2.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) { Text("ייבוא", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { exportLooperProjectLauncher.launch("Siren_Looper_${System.currentTimeMillis()}.zip") },
                            colors = ButtonDefaults.buttonColors(containerColor = gold),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("ייצוא", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (scopeTick < 0L) Text("")
                }

                "PAD" -> Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("LIVE PERFORMANCE PAD", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                padFlipOn = !padFlipOn
                                engine.padFlipOn = padFlipOn
                                engine.performanceX = 0f
                                engine.performanceY = 0f
                                engine.busPadX = 0f
                                engine.busPadY = 0f
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (padFlipOn) gold else panelBg2),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) { Text(if (padFlipOn) "FLIP • BUS" else "FLIP • LIVE", fontSize = 9.sp, color = if (padFlipOn) Color.Black else gold, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (padFlipOn) "הפאד משפיע על לופר / מיק / תופים. SUB WARM VIBE נשארים ללייב מקלדת."
                        else "LFO + Resonance • SUB / WARM / VIBE ללייב מקלדת בלבד. שחרר לחזרה למרכז.",
                        color = Color.Gray,
                        fontSize = 8.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(2f).background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).pointerInput(padFlipOn) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val x = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val y = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    if (engine.padFlipOn) { engine.busPadX = x; engine.busPadY = y } else { engine.performanceX = x; engine.performanceY = y }
                                },
                                onDragEnd = {
                                    engine.performanceX = 0f; engine.performanceY = 0f
                                    engine.busPadX = 0f; engine.busPadY = 0f
                                },
                                onDragCancel = {
                                    engine.performanceX = 0f; engine.performanceY = 0f
                                    engine.busPadX = 0f; engine.busPadY = 0f
                                },
                                onDrag = { change, _ ->
                                    val x = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val y = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    if (engine.padFlipOn) { engine.busPadX = x; engine.busPadY = y } else { engine.performanceX = x; engine.performanceY = y }
                                }
                            )
                        }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dummy = renderTrigger
                            val w = size.width
                            val h = size.height
                            val gridColor = Color(0xFF1F1F1F)
                            for (i in 1..4) {
                                drawLine(gridColor, Offset(w * (i / 5f), 0f), Offset(w * (i / 5f), h))
                                drawLine(gridColor, Offset(0f, h * (i / 5f)), Offset(w, h * (i / 5f)))
                            }
                            drawLine(Color(0xFF2A2A2A), Offset(0f, h), Offset(w, h), 2f)
                            drawLine(Color(0xFF2A2A2A), Offset(0f, 0f), Offset(0f, h), 2f)
                            val cursorX = engine.performanceX * w
                            val cursorY = (1f - engine.performanceY) * h
                            drawCircle(gold.copy(alpha = 0.2f), 40f, Offset(cursorX, cursorY))
                            drawCircle(gold, 16f, Offset(cursorX, cursorY), style = Stroke(width = 3f))
                            drawCircle(Color.White, 3f, Offset(cursorX, cursorY))
                        }
                        Text("LFO RATE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                        Text("Resonance", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).rotate(-90f))
                    }
                    Row(modifier = Modifier.fillMaxWidth().weight(0.85f).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            Triple("SUB", subOn) { subOn = !subOn; engine.subOn = subOn },
                            Triple("WARM", warmOn) { warmOn = !warmOn; engine.warmOn = warmOn },
                            Triple("VIBE", vibeOn) { vibeOn = !vibeOn; engine.vibeOn = vibeOn }
                        ).forEach { (label, enabled, toggle) ->
                            Button(
                                onClick = toggle,
                                colors = ButtonDefaults.buttonColors(containerColor = if (enabled) gold else panelBg2),
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color.Black else gold)
                                    Text(
                                        when (label) { "SUB" -> "באס −1 אוקטבה"; "WARM" -> "חום אנלוגי"; else -> "ויברטו עדין" },
                                        fontSize = 8.sp,
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

                "DRUM" -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    LaunchedEffect(Unit) {
                        val livePattern = engine.drumEngine.currentPatternIndex.coerceIn(0, 7)
                        val liveKit = engine.drumEngine.currentKitIndex.coerceIn(0, 7)
                        if (!drumTabInitialized) {
                            PresetManager.loadAllDrumKits(prefs, engine)
                            val curKit = engine.drumEngine.currentKitIndex
                            val patternToUse = engine.drumEngine.currentPatternIndex.coerceIn(0, 7)
                            engine.drumEngine.loadKit(curKit, context, startPattern = patternToUse)
                            drumTabInitialized = true
                            val hasSamples = engine.drumEngine.drumSamples.any { it != null }
                            if (!hasSamples) {
                                val ok = engine.drumEngine.loadDefaultKit(context)
                                if (ok) { defaultKitLoaded = true; useDefaultKit = true }
                            } else {
                                useDefaultKit = false
                                defaultKitLoaded = true
                            }
                        } else {
                            engine.drumEngine.currentKitIndex = liveKit
                            engine.drumEngine.currentPatternIndex = livePattern
                        }
                        selectedDrumPattern = engine.drumEngine.currentPatternIndex
                        drumBpmState = engine.drumEngine.bpm
                        drumVolState = engine.drumEngine.masterVolume
                        drumSwingState = engine.drumEngine.swing
                        for (t in 0 until 4) trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
                        gridRefreshTrigger = System.currentTimeMillis()
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
                                    if (drumPlayingState) { engine.drumEngine.stopAndRewind(); drumPlayingState = false }
                                    else { engine.drumEngine.startFromBeginning(); drumPlayingState = true }
                                    gridRefreshTrigger = System.currentTimeMillis()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) { Text(if (drumPlayingState) "עצור תופים" else "נגן תופים", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = {
                                    if (useDefaultKit) {
                                        for (i in 0 until 4) engine.drumEngine.drumSamples[i] = null
                                        useDefaultKit = false
                                    } else {
                                        scope.launch {
                                            val ok = engine.drumEngine.loadDefaultKit(context)
                                            if (ok) { useDefaultKit = true; Toast.makeText(context, "ערכת ברירת מחדל נטענה", Toast.LENGTH_SHORT).show() }
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
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().background(panelBg2, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        SynthKnob("Drum Vol", "${(drumVolState * 100).toInt()}%", drumVolState, 0f..1f, gold, 40.dp) { drumVolState = it; engine.drumEngine.masterVolume = it }
                        SynthKnob("Swing", "${(drumSwingState * 100).toInt()}%", drumSwingState, 0f..1f, gold, 40.dp) { drumSwingState = it; engine.drumEngine.swing = it }
                        SynthKnob("BPM", "${drumBpmState.toInt()}", drumBpmState, 60f..200f, gold, 40.dp) { drumBpmState = it; engine.drumEngine.bpm = it }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        for (t in 0 until 4) {
                            val name = engine.drumEngine.trackNames[t].take(4)
                            SynthKnob(name, "${(trackVolStates[t].value * 100).toInt()}%", trackVolStates[t].value, 0f..1f, gold, 36.dp) {
                                trackVolStates[t].value = it
                                engine.drumEngine.trackVolumes[t] = it
                            }
                        }
                    }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (i in 0 until 8) {
                            val isSelected = selectedDrumPattern == i
                            Button(
                                onClick = {
                                    engine.drumEngine.loadPattern(i)
                                    selectedDrumPattern = i
                                    drumBpmState = engine.drumEngine.bpm
                                    drumVolState = engine.drumEngine.masterVolume
                                    drumSwingState = engine.drumEngine.swing
                                    for (t in 0 until 4) trackVolStates[t].value = engine.drumEngine.trackVolumes[t]
                                    gridRefreshTrigger = System.currentTimeMillis()
                                    PresetManager.saveCurrentDrumSelection(prefs, engine)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) gold else panelBg2),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.weight(1f).height(24.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) { Text("${i + 1}", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                        Button(
                            onClick = {
                                engine.drumEngine.saveCurrentToPattern(selectedDrumPattern)
                                PresetManager.saveAllDrumKits(prefs, engine)
                                Toast.makeText(context, "מקצב נשמר בחריץ ${selectedDrumPattern + 1}", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("שמור", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                engine.drumEngine.generateRandomLogicalPattern()
                                gridRefreshTrigger = System.currentTimeMillis()
                                Toast.makeText(context, "מקצב אקראי הגיוני נוצר", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = panelBg2),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("Random", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        val currentActiveStep = remember(gridRefreshTrigger) { engine.drumEngine.currentStep }
                        for (t in 0 until 4) {
                            val trackName = engine.drumEngine.trackNames[t]
                            val isSampleLoaded = engine.drumEngine.drumSamples[t] != null
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${t + 1}. $trackName", color = if (isSampleLoaded) gold else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        OutlinedButton(
                                            onClick = { activeLoadingTrack = t; loadDrumSampleLauncher.launch("audio/*") },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(18.dp)
                                        ) { Text(if (isSampleLoaded) "החלף" else "טעון סאמפל", fontSize = 7.sp, color = gold) }
                                    }
                                }
                                Spacer(Modifier.height(1.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (s in 0 until 16) {
                                        val isActive = engine.drumEngine.grid[t][s]
                                        val isCurrentStep = drumPlayingState && s == currentActiveStep
                                        Box(
                                            modifier = Modifier.weight(1f).height(22.dp)
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
                                                    gridRefreshTrigger = System.currentTimeMillis()
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        val keyCount = frequencies.size
        val pressedKeys = remember { mutableStateListOf(*Array(8) { false }) }
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (selectedTab == "MIC") 0.dp else 130.dp)
                .pointerInput(frequencies.toList(), isRtl) {
                    val pointerToKey = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Int>()
                    fun keyAt(x: Float): Int {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val fromLeft = (x / w * keyCount).toInt().coerceIn(0, keyCount - 1)
                        return if (isRtl) keyCount - 1 - fromLeft else fromLeft
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val heldBefore = pointerToKey.values.toSet()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    val idx = keyAt(change.position.x)
                                    pointerToKey[change.id] = idx
                                    change.consume()
                                } else {
                                    pointerToKey.remove(change.id)
                                    change.consume()
                                }
                            }
                            val heldNow = pointerToKey.values.toSet()
                            for (i in 0 until keyCount) {
                                val on = i in heldNow
                                if (on && i !in heldBefore) {
                                    pressedKeys[i] = true
                                    engine.noteOn(frequencies[i])
                                } else if (!on && i in heldBefore) {
                                    pressedKeys[i] = false
                                    engine.noteOff(frequencies[i])
                                }
                            }
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            frequencies.forEachIndexed { index, freq ->
                val isPressed = pressedKeys.getOrNull(index) == true
                Card(
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 4.dp, topEnd = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isPressed) gold else Color(0xFFE8E8E8)),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(noteNames[index], color = if (isPressed) Color.Black else Color(0xFF1A1A1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${engine.getEffectiveFrequency(freq).toInt()}Hz", color = if (isPressed) Color.Black.copy(alpha = 0.7f) else Color(0xFF555555), fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}
