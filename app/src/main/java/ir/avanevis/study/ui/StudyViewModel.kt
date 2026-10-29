package ir.avanevis.study.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.avanevis.study.AvaNevisApp
import ir.avanevis.study.data.local.NoteSentenceEntity
import ir.avanevis.study.data.local.NotebookEntity
import ir.avanevis.study.data.local.StudySessionEntity
import ir.avanevis.study.data.preferences.StudySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AvaNevisApp
    private val dao = app.database.studyDao()
    private val settingsRepo = app.settingsRepository

    val settings: StateFlow<StudySettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudySettings())

    val notebooks: StateFlow<List<NotebookEntity>> = dao.observeNotebooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSessions: StateFlow<List<StudySessionEntity>> = dao.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedNotebookId = MutableStateFlow<Long?>(null)
    val selectedNotebookId: StateFlow<Long?> = _selectedNotebookId.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val activeSessionSentences: StateFlow<List<NoteSentenceEntity>> = _activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList())
            else dao.observeSentencesForSession(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchResults: StateFlow<List<NoteSentenceEntity>> = _searchQuery
        .debounce(250L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else dao.searchSentences(query.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val existing = dao.observeNotebooks().first()
            if (existing.isEmpty()) {
                val defaultId = dao.insertNotebook(
                    NotebookEntity(
                        title = "دفتر مطالعه عمومی",
                        authorOrSource = "کتاب‌ها و مقالات من"
                    )
                )
                _selectedNotebookId.value = defaultId
            } else {
                _selectedNotebookId.value = existing.first().id
            }
        }
    }

    fun selectNotebook(id: Long) {
        _selectedNotebookId.value = id
    }

    fun openExistingSession(sessionId: Long) {
        allSessions.value.firstOrNull { it.id == sessionId }?.let { session ->
            _selectedNotebookId.value = session.notebookId
        }
        _activeSessionId.value = sessionId
    }

    fun returnToHome() {
        _activeSessionId.value = null
    }

    fun createNotebook(title: String, source: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = dao.insertNotebook(
                NotebookEntity(title = title.trim(), authorOrSource = source.trim())
            )
            _selectedNotebookId.value = id
        }
    }

    suspend fun createNewStudySession(customTitle: String? = null): Pair<Long, Long> {
        val notebookId = _selectedNotebookId.value ?: run {
            dao.insertNotebook(NotebookEntity(title = "دفتر مطالعه عمومی"))
        }
        val title = customTitle?.takeIf { it.isNotBlank() }
            ?: "جلسه مطالعه ${java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        val sessionId = dao.insertSession(
            StudySessionEntity(
                notebookId = notebookId,
                title = title
            )
        )
        _activeSessionId.value = sessionId
        return sessionId to notebookId
    }

    /** Manual fallback when the phone has no working Persian speech provider. */
    fun addManualSentence(text: String) {
        val clean = text.trim().take(5000)
        val sessionId = _activeSessionId.value ?: return
        if (clean.isBlank()) return
        viewModelScope.launch {
            val session = dao.getSessionById(sessionId) ?: return@launch
            dao.appendSentenceAutomatically(
                sessionId = sessionId,
                notebookId = session.notebookId,
                processedText = clean,
                rawText = clean,
                confidence = 1f,
                isManual = true
            )
        }
    }

    fun updateSentenceContent(sentence: NoteSentenceEntity, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            dao.updateSentence(
                sentence.copy(
                    content = newContent.trim(),
                    isManuallyEdited = true
                )
            )
        }
    }

    fun deleteSentence(sentence: NoteSentenceEntity) {
        viewModelScope.launch {
            dao.deleteSentenceAndRefreshCount(sentence)
        }
    }

    fun moveSentence(sentence: NoteSentenceEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val list = dao.getSentencesForSessionOnce(sentence.sessionId)
            val idx = list.indexOfFirst { it.id == sentence.id }
            if (idx == -1) return@launch
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in list.indices) return@launch

            val neighbor = list[targetIdx]
            dao.updateSentence(sentence.copy(orderIndex = neighbor.orderIndex))
            dao.updateSentence(neighbor.copy(orderIndex = sentence.orderIndex))
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSettings(transform: (StudySettings) -> StudySettings) {
        viewModelScope.launch {
            settingsRepo.updateSettings(transform)
        }
    }
}
