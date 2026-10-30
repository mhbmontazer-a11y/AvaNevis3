package ir.avanevis.study.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.avanevis.study.data.local.NoteSentenceEntity
import ir.avanevis.study.data.preferences.AccentPalette
import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.data.preferences.RecognitionEngineMode
import ir.avanevis.study.data.preferences.SpeechQualityProfile
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import ir.avanevis.study.export.ExportManager
import ir.avanevis.study.service.StudyListeningService
import ir.avanevis.study.speech.WhisperModelManager
import ir.avanevis.study.speech.ListeningState
import ir.avanevis.study.speech.SpeechEngineTelemetry
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: StudyViewModel by viewModels()
    private var studyService by mutableStateOf<StudyListeningService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StudyListeningService.LocalBinder
            studyService = binder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            studyService = null
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, StudyListeningService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settings.collectAsState()

            LaunchedEffect(settings.keepScreenOnDuringStudy) {
                if (settings.keepScreenOnDuringStudy) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val colorScheme = avanevisColorScheme(settings.darkTheme, settings.accentPalette)

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var modelReady by remember { mutableStateOf(WhisperModelManager.isInstalled(this@MainActivity)) }
                        if (!modelReady) WhisperModelSetup(onReady = { modelReady = true }, onSkip = { modelReady = true })
                        else AvaNevisStudyAppScreen(
                            viewModel = viewModel,
                            studyService = studyService,
                            onStartStudyService = { sessionId, notebookId ->
                                val intent = Intent(this, StudyListeningService::class.java).apply {
                                    action = StudyListeningService.ACTION_START_STUDY
                                    putExtra(StudyListeningService.EXTRA_SESSION_ID, sessionId)
                                    putExtra(StudyListeningService.EXTRA_NOTEBOOK_ID, notebookId)
                                    // Pass current settings directly: do not delay mic startup
                                    // while DataStore performs its first asynchronous read.
                                    putExtra(StudyListeningService.EXTRA_QUIET_MS, settings.silenceThresholdMs)
                                    putExtra(StudyListeningService.EXTRA_ENGINE_MODE, settings.engineMode.name)
                                    putExtra(StudyListeningService.EXTRA_SPEECH_PROFILE, settings.speechQualityProfile.name)
                                    putExtra(StudyListeningService.EXTRA_VOICE_COMMANDS, settings.voiceCommandsEnabled)
                                    putExtra(StudyListeningService.EXTRA_AUTO_PUNCTUATION, settings.autoPunctuation)
                                    putExtra(StudyListeningService.EXTRA_FIX_HALF_SPACE, settings.fixHalfSpace)
                                    putExtra(StudyListeningService.EXTRA_DIGIT_MODE, settings.digitMode.name)
                                }
                                ContextCompat.startForegroundService(this, intent)
                            },
                            onStopStudyService = {
                                studyService?.stopForegroundStudySession()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun avanevisColorScheme(dark: Boolean, accent: AccentPalette): ColorScheme {
    val primary = when (accent) {
        AccentPalette.OCEAN -> if (dark) Color(0xFF79C6FF) else Color(0xFF1766C2)
        AccentPalette.VIOLET -> if (dark) Color(0xFFC5B4FF) else Color(0xFF7554C9)
        AccentPalette.MINT -> if (dark) Color(0xFF72E5CA) else Color(0xFF087D70)
        AccentPalette.SUNSET -> if (dark) Color(0xFFFFB995) else Color(0xFFC4593C)
        AccentPalette.ROSE -> if (dark) Color(0xFFFFA6C9) else Color(0xFFB52D71)
        AccentPalette.AMBER -> if (dark) Color(0xFFFFD080) else Color(0xFF9C6200)
    }
    return if (dark) darkColorScheme(
        primary = primary, secondary = Color(0xFF73D8CB),
        background = Color(0xFF0B1220), surface = Color(0xFF151F30),
        surfaceVariant = Color(0xFF243247),
        primaryContainer = Color(0xFF263C5D),
        onPrimaryContainer = Color(0xFFE8F2FF)
    ) else lightColorScheme(
        primary = primary, secondary = Color(0xFF128C80),
        background = Color(0xFFF5F7FB), surface = Color.White,
        surfaceVariant = Color(0xFFE9EEF6),
        primaryContainer = Color(0xFFE0EBFF),
        onPrimaryContainer = Color(0xFF15335E)
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DashboardMetric(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvaNevisStudyAppScreen(
    viewModel: StudyViewModel,
    studyService: StudyListeningService?,
    onStartStudyService: (Long, Long) -> Unit,
    onStopStudyService: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val notebooks by viewModel.notebooks.collectAsState()
    val sessions by viewModel.allSessions.collectAsState()
    val selectedNotebookId by viewModel.selectedNotebookId.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val sentences by viewModel.activeSessionSentences.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val telemetry by studyService?.telemetry?.collectAsState()
        ?: remember { mutableStateOf(SpeechEngineTelemetry()) }
    val elapsedSec by studyService?.elapsedSeconds?.collectAsState()
        ?: remember { mutableStateOf(0L) }

    val isStudying = telemetry.state != ListeningState.IDLE
    val serviceSessionId by studyService?.activeSessionIdFlow?.collectAsState()
        ?: remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(serviceSessionId) {
        val serviceId = serviceSessionId
        if (serviceId != null && serviceId != activeSessionId) {
            viewModel.openExistingSession(serviceId)
        }
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showNewNotebookDialog by remember { mutableStateOf(false) }
    var editingSentence by remember { mutableStateOf<NoteSentenceEntity?>(null) }
    var showManualNoteDialog by remember { mutableStateOf(false) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.RECORD_AUDIO] == true
        hasMicPermission = granted
        if (granted) {
            scope.launch {
                val (sessionId, notebookId) = viewModel.createNewStudySession()
                onStartStudyService(sessionId, notebookId)
            }
        } else {
            Toast.makeText(
                context,
                "برای یادداشت‌برداری صوتی، مجوز میکروفون الزامی است.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "آوانویس  |  فضای مطالعه",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = when {
                                !hasMicPermission -> "نیازمند مجوز میکروفون"
                                telemetry.state == ListeningState.LISTENING_READY -> "میکروفون آماده • می‌توانید صحبت کنید"
                                telemetry.state == ListeningState.STARTING -> "در حال راه‌اندازی میکروفون..."
                                isStudying -> "جلسه مطالعه فعال • وضعیت گفتار را پایین ببینید"
                                else -> "مجوز میکروفون فعال • مطالعه را شروع کنید"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.updateSettings { it.copy(darkTheme = !it.darkTheme) }
                        }
                    ) {
                        Icon(
                            imageVector = if (settings.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "تغییر پوسته"
                        )
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات هوشمند")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (isStudying || activeSessionId != null) {
                ActiveStudyView(
                    isStudying = isStudying,
                    telemetry = telemetry,
                    elapsedSeconds = elapsedSec,
                    sentences = sentences,
                    settings = settings,
                    onStartOrResume = {
                        val currentId = activeSessionId
                        val nbId = sessions.firstOrNull { it.id == currentId }?.notebookId
                            ?: selectedNotebookId ?: 1L
                        if (currentId != null && currentId > 0L) {
                            onStartStudyService(currentId, nbId)
                        }
                    },
                    onStopStudy = onStopStudyService,
                    onRetrySpeech = { studyService?.retrySpeechRecognition() },
                    onManualNote = { showManualNoteDialog = true },
                    onBackToHome = {
                        if (isStudying) onStopStudyService()
                        viewModel.returnToHome()
                    },
                    onEditSentence = { editingSentence = it },
                    onDeleteSentence = { viewModel.deleteSentence(it) },
                    onMoveSentence = { item, up -> viewModel.moveSentence(item, up) },
                    onExportAction = { exportType ->
                        val activeNotebook = notebooks.find { it.id == selectedNotebookId }?.title ?: "دفتر مطالعه"
                        val activeSessionTitle = sessions.find { it.id == activeSessionId }?.title ?: "یادداشت مطالعه"
                        when (exportType) {
                            "COPY" -> {
                                val txt = ExportManager.formatSessionAsPlainText(
                                    activeSessionTitle, activeNotebook, sentences, settings
                                )
                                ExportManager.copyToClipboard(context, activeSessionTitle, txt)
                                Toast.makeText(context, "تمام یادداشت‌ها کپی شد", Toast.LENGTH_SHORT).show()
                            }
                            "SHARE" -> {
                                val txt = ExportManager.formatSessionAsPlainText(
                                    activeSessionTitle, activeNotebook, sentences, settings
                                )
                                ExportManager.shareText(context, activeSessionTitle, txt)
                            }
                            "TXT" -> {
                                val file = ExportManager.exportToTxtFile(
                                    context, activeSessionTitle, activeNotebook, sentences, settings
                                )
                                ExportManager.shareExportedFile(context, file, "text/plain")
                            }
                            "PDF" -> {
                                val file = ExportManager.exportToRtlPdfFile(
                                    context, activeSessionTitle, activeNotebook, sentences, settings
                                )
                                ExportManager.shareExportedFile(context, file, "application/pdf")
                            }
                        }
                    }
                )
            } else {
                HomeDashboardView(
                    notebooks = notebooks,
                    selectedNotebookId = selectedNotebookId,
                    sessions = sessions,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    settings = settings,
                    onSelectNotebook = { viewModel.selectNotebook(it) },
                    onNewNotebookClick = { showNewNotebookDialog = true },
                    onStartStudyClick = {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (hasMicPermission) {
                            scope.launch {
                                val (sessionId, notebookId) = viewModel.createNewStudySession()
                                onStartStudyService(sessionId, notebookId)
                            }
                        } else {
                            permissionLauncher.launch(perms.toTypedArray())
                        }
                    },
                    onOpenSession = { viewModel.openExistingSession(it) },
                    onSearchChange = { viewModel.updateSearchQuery(it) }
                )
            }
        }
    }

    if (showSettingsSheet) {
        SettingsDialog(
            settings = settings,
            onDismiss = { showSettingsSheet = false },
            onUpdate = { transform -> viewModel.updateSettings(transform) }
        )
    }

    if (showNewNotebookDialog) {
        NewNotebookDialog(
            onDismiss = { showNewNotebookDialog = false },
            onCreate = { title, author ->
                viewModel.createNotebook(title, author)
                showNewNotebookDialog = false
            }
        )
    }

    if (showManualNoteDialog) {
        ManualNoteDialog(
            onDismiss = { showManualNoteDialog = false },
            onSave = { text ->
                viewModel.addManualSentence(text)
                showManualNoteDialog = false
            }
        )
    }

    editingSentence?.let { target ->
        EditSentenceDialog(
            sentence = target,
            onDismiss = { editingSentence = null },
            onSave = { updatedText ->
                viewModel.updateSentenceContent(target, updatedText)
                editingSentence = null
            }
        )
    }
}

@Composable
fun HomeDashboardView(
    notebooks: List<ir.avanevis.study.data.local.NotebookEntity>,
    selectedNotebookId: Long?,
    sessions: List<ir.avanevis.study.data.local.StudySessionEntity>,
    searchQuery: String,
    searchResults: List<NoteSentenceEntity>,
    settings: StudySettings,
    onSelectNotebook: (Long) -> Unit,
    onNewNotebookClick: () -> Unit,
    onStartStudyClick: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onSearchChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            val heroBrush = Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
                MaterialTheme.colorScheme.primaryContainer
            ))
            Column(modifier = Modifier.background(heroBrush).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("آوانویس • ${when (settings.speechQualityProfile) {
                            SpeechQualityProfile.QUICK -> "حالت سریع"
                            SpeechQualityProfile.BALANCED -> "حالت متعادل"
                            SpeechQualityProfile.CAREFUL -> "حالت دقیق‌تر"
                        }}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text("ایده‌ها را بگو،\nآوانویس ثبت می‌کند.",
                    fontSize = 25.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("مطالعه کن و با صدای خودت نکته‌ها را ذخیره کن؛ هر جمله به‌صورت مستقل ثبت می‌شود.",
                    fontSize = 13.sp, lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.83f))
                Button(onClick = onStartStudyClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.Mic, null)
                    Spacer(Modifier.width(9.dp))
                    Text("شروع جلسهٔ جدید", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardMetric(
                PersianTextProcessor.convertDigits(notebooks.size.toString(), settings.digitMode),
                "دفتر مطالعه", Icons.Default.MenuBook, Modifier.weight(1f)
            )
            DashboardMetric(
                PersianTextProcessor.convertDigits(sessions.size.toString(), settings.digitMode),
                "جلسه ثبت‌شده", Icons.Default.History, Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(18.dp))

        // انتخاب دفتر مطالعه
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("کتابخانهٔ من", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            TextButton(onClick = onNewNotebookClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("دفتر جدید")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notebooks.size) { index ->
                val nb = notebooks[index]
                FilterChip(
                    selected = nb.id == selectedNotebookId,
                    onClick = { onSelectNotebook(nb.id) },
                    label = { Text(nb.title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // جستجوی فارسی در یادداشت‌ها
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("جست‌وجو در متن یادداشت‌ها...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (searchQuery.isNotBlank()) {
            Text(
                text = "نتایج جست‌وجو (${PersianTextProcessor.convertDigits(searchResults.size.toString(), settings.digitMode)})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                searchResults.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(item.sessionId) }
                    ) {
                        Text(
                            text = item.content,
                            modifier = Modifier.padding(14.dp),
                            fontSize = settings.fontSizeSp.sp
                        )
                    }
                }
            }
        } else {
            Text(
                text = "جلسات مطالعاتی اخیر",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val filtered = sessions.filter {
                    selectedNotebookId == null || it.notebookId == selectedNotebookId
                }
                filtered.forEach { session ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(session.id) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val countStr = PersianTextProcessor.convertDigits(
                                    session.sentenceCount.toString(),
                                    settings.digitMode
                                )
                                Text(
                                    text = "$countStr نکته ثبت‌شده",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.ChevronLeft, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveStudyView(
    isStudying: Boolean,
    telemetry: SpeechEngineTelemetry,
    elapsedSeconds: Long,
    sentences: List<NoteSentenceEntity>,
    settings: StudySettings,
    onStartOrResume: () -> Unit,
    onStopStudy: () -> Unit,
    onRetrySpeech: () -> Unit,
    onManualNote: () -> Unit,
    onBackToHome: () -> Unit,
    onEditSentence: (NoteSentenceEntity) -> Unit,
    onDeleteSentence: (NoteSentenceEntity) -> Unit,
    onMoveSentence: (NoteSentenceEntity, Boolean) -> Unit,
    onExportAction: (String) -> Unit
) {
    val context = LocalContext.current
    var showMicrophoneHelp by remember { mutableStateOf(false) }
    if (showMicrophoneHelp) {
        AlertDialog(
            onDismissRequest = { showMicrophoneHelp = false },
            title = { Text("عیب‌یابی میکروفون و گفتار") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("تشخیص گفتار فقط با مدل محلی Whisper انجام می‌شود.")
                    Text("۱. مجوز میکروفون آوانویس را فعال کنید.\n۲. در تنظیمات گوشی، سرویس تشخیص گفتار Google را نصب یا فعال و زبان فارسی را بررسی کنید.\n۳. برای سرویس‌های آنلاین، اینترنت را فعال کنید.\n۴. برنامه‌های دیگری که میکروفون را اشغال کرده‌اند ببندید.\n۵. جلسه را پایان دهید و دوباره شروع کنید.")
                    Text("تشخیص گفتار فقط روی دستگاه و با مدل Whisper انجام می‌شود.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicrophoneHelp = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) { Text("تنظیمات مجوز برنامه") }
            },
            dismissButton = { TextButton(onClick = { showMicrophoneHelp = false }) { Text("بستن") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // نوار وضعیت زنده شنیدن پیوسته و نشانگر صوتی
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isStudying) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isStudying) Color(0xFF10B981) else Color(0xFF94A3B8)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = telemetry.statusMessage,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    val mins = (elapsedSeconds / 60).toString().padStart(2, '0')
                    val secs = (elapsedSeconds % 60).toString().padStart(2, '0')
                    Text(
                        text = PersianTextProcessor.convertDigits("$mins:$secs", settings.digitMode),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // نشانگر بصری سطح صدا (Voice Activity Indicator)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rms = telemetry.currentRmsDb
                    repeat(18) { barIdx ->
                        val ratio = ((rms / 10f) * (1f - (barIdx % 5) * 0.12f)).coerceIn(0.15f, 1f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(if (isStudying) ratio else 0.15f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                // نمایش زنده متن در حال تشخیص (متفاوت از متن‌های نهایی ثبت‌شده)
                AnimatedVisibility(visible = telemetry.livePartialText.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "در حال دریافت جمله (موقت):",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = telemetry.livePartialText,
                                fontSize = settings.fontSizeSp.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countText = PersianTextProcessor.convertDigits(
                        sentences.size.toString(),
                        settings.digitMode
                    )
                    Text(
                        text = "جملات ذخیره‌شده: $countText",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isStudying) {
                            FilledTonalButton(
                                onClick = onStopStudy,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFEF4444),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("پایان مطالعه")
                            }
                        } else {
                            Button(onClick = onStartOrResume) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ادامه شنیدن")
                            }
                            OutlinedButton(onClick = onBackToHome) {
                                Text("بازگشت")
                            }
                        }
                    }
                }
            }
        }

        if (telemetry.state == ListeningState.RECOVERING_ERROR) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (telemetry.isRecoveryRequired) "سرویس گفتار متوقف شده؛ اتصال مجدد را امتحان کنید" else "اتصال گفتار نیاز به بررسی دارد",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (telemetry.isRecoveryRequired) {
                        TextButton(onClick = onRetrySpeech) { Text("اتصال مجدد") }
                    }
                    TextButton(onClick = { showMicrophoneHelp = true }) {
                        Text("راهنمای رفع مشکل")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Always offer manual notes: users must not lose their study when ASR is unavailable.
        OutlinedButton(
            onClick = onManualNote,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.NoteAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("افزودن یادداشت دستی")
        }
        Spacer(Modifier.height(6.dp))

        // نوار ابزار خروجی گرفتن سریع (کپی، اشتراک، TXT، PDF)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { AssistChip(
                onClick = { onExportAction("COPY") },
                label = { Text("کپی همه") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) }
            ) }
            item { AssistChip(
                onClick = { onExportAction("TXT") },
                label = { Text("خروجی TXT") },
                leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) }
            ) }
            item { AssistChip(
                onClick = { onExportAction("PDF") },
                label = { Text("خروجی PDF") },
                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(16.dp)) }
            ) }
            item { AssistChip(
                onClick = { onExportAction("SHARE") },
                label = { Text("اشتراک") },
                leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(16.dp)) }
            ) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // فهرست نکات ثبت‌شده
        if (sentences.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ابتدا صبر کنید تا وضعیت «میکروفون آماده» نمایش داده شود؛ سپس جملات مهم کتاب را بلند بخوانید.\nهر جمله پس از مکث کوتاه، خودکار در یک خط جدید ثبت می‌شود.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(sentences, key = { _, item -> item.id }) { idx, item ->
                    val numberPrefix = if (settings.autoNumbering) {
                        "${PersianTextProcessor.convertDigits((idx + 1).toString(), settings.digitMode)}. "
                    } else ""

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "$numberPrefix${item.content}",
                                fontSize = settings.fontSizeSp.sp,
                                lineHeight = (settings.fontSizeSp + 10).sp
                            )
                            if (settings.showRecognitionConfidence && !item.isManuallyEdited && item.confidence >= 0f) {
                                val percent = (item.confidence.coerceIn(0f, 1f) * 100).toInt()
                                Text("اطمینان تقریبی موتور گفتار: $percent٪", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = { onMoveSentence(item, true) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, "بالا", Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onMoveSentence(item, false) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, "پایین", Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onEditSentence(item) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "ویرایش", Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onDeleteSentence(item) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "حذف", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    settings: StudySettings,
    onDismiss: () -> Unit,
    onUpdate: ((StudySettings) -> StudySettings) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات و شخصی‌سازی", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("شنیدن و تشخیص گفتار")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("تشخیص گفتار با Whisper", fontWeight = FontWeight.SemiBold)
                        Text("Whisper موتور انحصاری تبدیل گفتار به متن است و مدل آن جداگانه دانلود می‌شود.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("فعال", color = MaterialTheme.colorScheme.primary)
                }
                Text("پروفایل پردازش گفتار", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpeechQualityProfile.values().forEach { profile ->
                        val (name, detail) = when (profile) {
                            SpeechQualityProfile.QUICK -> "سریع" to "تنظیم قدیمی؛ مسیر پردازش فقط Whisper است"
                            SpeechQualityProfile.BALANCED -> "متعادل" to "تنظیم قدیمی؛ مسیر پردازش فقط Whisper است"
                            SpeechQualityProfile.CAREFUL -> "دقیق‌تر (در صورت پشتیبانی موتور)" to "تنظیم قدیمی؛ مسیر پردازش فقط Whisper است"
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onUpdate { it.copy(speechQualityProfile = profile) }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (settings.speechQualityProfile == profile)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = settings.speechQualityProfile == profile,
                                    onClick = { onUpdate { it.copy(speechQualityProfile = profile) } }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(detail, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Text("مدل Whisper روی خود گوشی اجرا می‌شود؛ سرعت به توان پردازشی و حافظه دستگاه بستگی دارد.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("حداکثر تلاش راه‌اندازی میکروفون: ${settings.maxStartupRetries}", fontSize = 13.sp)
                Slider(
                    value = settings.maxStartupRetries.toFloat(),
                    onValueChange = { v -> onUpdate { it.copy(maxStartupRetries = v.toInt().coerceIn(1, 6)) } },
                    valueRange = 1f..6f,
                    steps = 4
                )
                Text("در صورت بروز خطا، فایل‌های صوتی پردازش‌نشده برای بازیابی نگهداری می‌شوند.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("نمایش اطمینان تقریبی تشخیص", fontSize = 13.sp)
                    Switch(checked = settings.showRecognitionConfidence,
                        onCheckedChange = { enabled -> onUpdate { it.copy(showRecognitionConfidence = enabled) } })
                }
                Text(
                    text = "مدت مکث مجاز در یک جمله: ${PersianTextProcessor.convertDigits(settings.silenceThresholdMs.toString(), settings.digitMode)} میلی‌ثانیه",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = settings.silenceThresholdMs.toFloat(),
                    onValueChange = { v -> onUpdate { it.copy(silenceThresholdMs = v.toLong()) } },
                    valueRange = 1500f..8000f
                )
                Text("در طول مکث متن موقت ذخیره نمی‌شود؛ فقط بعد از پایان این بازه یک یادداشت نهایی ساخته می‌شود.", fontSize = 11.sp)
                Text("راهنمای سرعت: برای یادداشت سریع‌تر، پروفایل «سریع» و مکث کوتاه‌تر را انتخاب کنید. برای کاهش شکست جمله‌ها، مکث طولانی‌تر و پروفایل «دقیق‌تر» را امتحان کنید.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                SectionHeading("نمایش و خوانایی")
                Text("رنگ رابط کاربری", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(AccentPalette.values().size) { index ->
                        val palette = AccentPalette.values()[index]
                        val label = when (palette) {
                            AccentPalette.OCEAN -> "آبی"
                            AccentPalette.VIOLET -> "بنفش"
                            AccentPalette.MINT -> "سبز"
                            AccentPalette.SUNSET -> "مرجانی"
                            AccentPalette.ROSE -> "رز"
                            AccentPalette.AMBER -> "کهربایی"
                        }
                        FilterChip(selected = settings.accentPalette == palette,
                            onClick = { onUpdate { it.copy(accentPalette = palette) } },
                            label = { Text(label) })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("حالت تاریک", fontSize = 14.sp)
                    Switch(checked = settings.darkTheme,
                        onCheckedChange = { value -> onUpdate { it.copy(darkTheme = value) } })
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("روشن ماندن صفحه در مطالعه", fontSize = 14.sp)
                    Switch(checked = settings.keepScreenOnDuringStudy,
                        onCheckedChange = { value -> onUpdate { it.copy(keepScreenOnDuringStudy = value) } })
                }
                Text(
                    text = "اندازه فونت یادداشت‌ها: ${PersianTextProcessor.convertDigits(settings.fontSizeSp.toString(), settings.digitMode)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = settings.fontSizeSp.toFloat(),
                    onValueChange = { v -> onUpdate { it.copy(fontSizeSp = v.toInt()) } },
                    valueRange = 14f..26f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("شماره‌گذاری خودکار جملات", fontSize = 14.sp)
                    Switch(
                        checked = settings.autoNumbering,
                        onCheckedChange = { v -> onUpdate { it.copy(autoNumbering = v) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("علائم نگارشی و نیم‌فاصله خودکار", fontSize = 14.sp)
                    Switch(
                        checked = settings.autoPunctuation,
                        onCheckedChange = { v ->
                            onUpdate { it.copy(autoPunctuation = v, fixHalfSpace = v) }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("پشتیبانی از دستورات صوتی اختیاری", fontSize = 14.sp)
                    Switch(
                        checked = settings.voiceCommandsEnabled,
                        onCheckedChange = { v -> onUpdate { it.copy(voiceCommandsEnabled = v) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("تشخیص کاملاً آفلاین Whisper Large-v3 Turbo", fontSize = 14.sp)
                    Switch(
                        checked = true,
                        enabled = false,
                        onCheckedChange = { offline ->
                            onUpdate {
                                it.copy(
                                    engineMode = if (offline) RecognitionEngineMode.OFFLINE_ONLY
                                    else RecognitionEngineMode.ONLINE_HIGH_ACCURACY
                                )
                            }
                        }
                    )
                }

                Text(
                    "پردازش گفتار فقط با مدل Whisper Large-v3 Turbo Q5_0 انجام می‌شود. مدل جدا از APK دانلود می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("نمایش اعداد به صورت فارسی", fontSize = 14.sp)
                    Switch(
                        checked = settings.digitMode == DigitDisplayMode.PERSIAN,
                        onCheckedChange = { persian ->
                            onUpdate {
                                it.copy(
                                    digitMode = if (persian) DigitDisplayMode.PERSIAN
                                    else DigitDisplayMode.LATIN
                                )
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}

@Composable
fun ManualNoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("یادداشت جدید", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(5000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                label = { Text("متن نکتهٔ مطالعه") },
                placeholder = { Text("در صورت مشکل میکروفون، نکته را اینجا بنویسید...") }
            )
        },
        confirmButton = {
            Button(onClick = { onSave(content.trim()) }, enabled = content.isNotBlank()) {
                Text("ذخیره یادداشت")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ایجاد دفتر مطالعه جدید") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("نام کتاب یا مبحث") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("نویسنده یا منبع (اختیاری)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title, author) }) {
                Text("ایجاد دفتر")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
fun EditSentenceDialog(
    sentence: NoteSentenceEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(sentence.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش نکته ثبت‌شده") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text) }) { Text("ذخیره تغییرات") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
