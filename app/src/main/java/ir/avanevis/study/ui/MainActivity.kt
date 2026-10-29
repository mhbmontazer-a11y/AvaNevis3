package ir.avanevis.study.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.avanevis.study.data.local.NoteSentenceEntity
import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.data.preferences.RecognitionEngineMode
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import ir.avanevis.study.export.ExportManager
import ir.avanevis.study.service.StudyListeningService
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

            val colorScheme = if (settings.darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF34D399),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF0284C7),
                    secondary = Color(0xFF059669),
                    background = Color(0xFFF8FAFC),
                    surface = Color(0xFFFFFFFF)
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AvaNevisStudyAppScreen(
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

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
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
                            text = "آوانویس هوشمند مطالعه",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
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
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(8.dp))

        // کارت اصلی شروع مطالعه بدون دخالت دست
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "یادداشت‌برداری صوتی بدون لمس گوشی",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "فقط یک بار دکمه زیر را بزنید، گوشی را کنار بگذارید و جملات مهم کتاب را بلند بخوانید تا خودکار در خطوط مجزا ثبت شوند.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStartStudyClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "شروع مطالعه",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // انتخاب دفتر مطالعه
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("دفترهای مطالعه (کتاب‌ها)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(searchResults) { _, item ->
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filtered = sessions.filter {
                    selectedNotebookId == null || it.notebookId == selectedNotebookId
                }
                itemsIndexed(filtered) { _, session ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(session.id) },
                        shape = RoundedCornerShape(16.dp)
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
    onBackToHome: () -> Unit,
    onEditSentence: (NoteSentenceEntity) -> Unit,
    onDeleteSentence: (NoteSentenceEntity) -> Unit,
    onMoveSentence: (NoteSentenceEntity, Boolean) -> Unit,
    onExportAction: (String) -> Unit
) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

        Spacer(modifier = Modifier.height(10.dp))

        // نوار ابزار خروجی گرفتن سریع (کپی، اشتراک، TXT، PDF)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { onExportAction("COPY") },
                label = { Text("کپی همه") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = { onExportAction("TXT") },
                label = { Text("خروجی TXT") },
                leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = { onExportAction("PDF") },
                label = { Text("خروجی PDF") },
                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = { onExportAction("SHARE") },
                label = { Text("اشتراک") },
                leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(16.dp)) }
            )
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
        title = { Text("تنظیمات هوشمند یادداشت‌برداری") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Text("ترجیح حالت آفلاین (اگر سرویس گوشی پشتیبانی کند)", fontSize = 14.sp)
                    Switch(
                        checked = settings.engineMode == RecognitionEngineMode.OFFLINE_ONLY,
                        onCheckedChange = { offline ->
                            onUpdate {
                                it.copy(
                                    engineMode = if (offline) RecognitionEngineMode.OFFLINE_ONLY
                                    else RecognitionEngineMode.HYBRID_SMART
                                )
                            }
                        }
                    )
                }

                Text(
                    "این گزینه فقط ترجیح آفلاین را به سرویس گفتار اندروید اعلام می‌کند؛ پشتیبانی و کیفیت به گوشی و بسته زبان نصب‌شده بستگی دارد.",
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
