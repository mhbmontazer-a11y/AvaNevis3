package ir.avanevis.study.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.avanevis.study.speech.WhisperModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Setup gate prevents a silent recording when the required model is missing. */
@Composable
fun WhisperModelSetup(onReady: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var received by remember { mutableLongStateOf(WhisperModelManager.downloadedBytes(context)) }
    var total by remember { mutableLongStateOf(-1L) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("راه‌اندازی موتور آفلاین آوانویس", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Whisper Large-v3 Turbo Q5_0 • حدود ۵۷۴ مگابایت")
        Text("مدل یک‌بار دانلود می‌شود. سپس پردازش گفتار کاملاً روی گوشی است.")
        Spacer(Modifier.height(16.dp))
        if (total > 0) LinearProgressIndicator(progress = { (received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("دریافت‌شده: ${received / 1_000_000} مگابایت" + if (total > 0) " از ${total / 1_000_000}" else "")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (job?.isActive == true) job?.cancel()
            else {
                error = ""
                job = scope.launch {
                    try {
                        WhisperModelManager.download(context) { n, t -> received = n; total = t }
                        onReady()
                    } catch (_: CancellationException) {
                        error = "دانلود متوقف شد؛ برای ادامه دوباره بزنید"
                    } catch (e: Exception) { error = e.message ?: "دانلود ناموفق بود" }
                }
            }
        }) { Text(if (job?.isActive == true) "توقف دانلود" else "دانلود / ادامه دانلود") }
        TextButton(onClick = onSkip) { Text("ورود به یادداشت‌های قبلی بدون ضبط") }
    }
}
