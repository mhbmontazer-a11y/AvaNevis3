package ir.avanevis.study.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import ir.avanevis.study.data.local.NoteSentenceEntity
import ir.avanevis.study.data.preferences.DigitDisplayMode
import ir.avanevis.study.data.preferences.StudySettings
import ir.avanevis.study.domain.PersianTextProcessor
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مدیریت خروجی گرفتن از یادداشت‌های جلسه مطالعه:
 * ۱. کپی در حافظه موقت (Clipboard)
 * ۲. اشتراک‌گذاری مستقیم متنی
 * ۳. تولید فایل متنی استاندارد UTF-8 (.txt)
 * ۴. تولید فایل PDF راست‌به‌چپ فارسی با شماره‌گذاری و صفحه‌بندی خودکار
 */
object ExportManager {

    fun formatSessionAsPlainText(
        sessionTitle: String,
        notebookTitle: String,
        sentences: List<NoteSentenceEntity>,
        settings: StudySettings
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("دفتر مطالعه: $notebookTitle")
        sb.appendLine("عنوان جلسه: $sessionTitle")
        val dateStr = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date())
        sb.appendLine("تاریخ خروجی: ${PersianTextProcessor.convertDigits(dateStr, settings.digitMode)}")
        sb.appendLine("تعداد نکات ثبت‌شده: ${PersianTextProcessor.convertDigits(sentences.size.toString(), settings.digitMode)}")
        sb.appendLine("========================================")
        sb.appendLine()

        sentences.forEachIndexed { idx, item ->
            val num = idx + 1
            val prefix = if (settings.autoNumbering) {
                "${PersianTextProcessor.convertDigits(num.toString(), settings.digitMode)}. "
            } else {
                "• "
            }
            sb.appendLine("$prefix${item.content}")
        }
        return sb.toString().trimEnd()
    }

    fun copyToClipboard(context: Context, title: String, formattedText: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(title, formattedText))
    }

    fun shareText(context: Context, title: String, formattedText: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, formattedText)
        }
        context.startActivity(Intent.createChooser(sendIntent, "اشتراک‌گذاری یادداشت‌های مطالعه"))
    }

    fun exportToTxtFile(
        context: Context,
        sessionTitle: String,
        notebookTitle: String,
        sentences: List<NoteSentenceEntity>,
        settings: StudySettings
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = sessionTitle.replace(Regex("[^آ-یa-zA-Z0-9_-]"), "_").ifBlank { "study_notes" }
        val file = File(exportDir, "${safeName}.txt")
        val text = formatSessionAsPlainText(sessionTitle, notebookTitle, sentences, settings)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    fun exportToRtlPdfFile(
        context: Context,
        sessionTitle: String,
        notebookTitle: String,
        sentences: List<NoteSentenceEntity>,
        settings: StudySettings
    ): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 width in points
        val pageHeight = 842 // A4 height in points
        val margin = 48f
        val rightEdge = pageWidth - margin
        val maxContentWidth = pageWidth - (margin * 2)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(71, 85, 105)
            textSize = 12f
            textAlign = Paint.Align.RIGHT
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 13.5f
            textAlign = Paint.Align.RIGHT
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var currentY = margin + 20f

        // سرصفحه راست‌به‌چپ فارسی
        canvas.drawText("دفتر مطالعه: $notebookTitle", rightEdge, currentY, titlePaint)
        currentY += 26f
        canvas.drawText("جلسه: $sessionTitle", rightEdge, currentY, subtitlePaint)
        currentY += 22f
        canvas.drawLine(margin, currentY, rightEdge, currentY, subtitlePaint)
        currentY += 28f

        sentences.forEachIndexed { idx, item ->
            val numStr = PersianTextProcessor.convertDigits((idx + 1).toString(), settings.digitMode)
            val fullLine = if (settings.autoNumbering) "$numStr. ${item.content}" else "• ${item.content}"
            val wrappedLines = wrapRtlText(fullLine, bodyPaint, maxContentWidth)

            val blockHeight = wrappedLines.size * 22f + 10f
            if (currentY + blockHeight > pageHeight - margin) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                currentY = margin + 20f
            }

            for (line in wrappedLines) {
                canvas.drawText("‏$line", rightEdge, currentY, bodyPaint)
                currentY += 22f
            }
            currentY += 8f
        }

        pdfDocument.finishPage(page)

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = sessionTitle.replace(Regex("[^آ-یa-zA-Z0-9_-]"), "_").ifBlank { "study_notes" }
        val pdfFile = File(exportDir, "${safeName}.pdf")
        FileOutputStream(pdfFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        return pdfFile
    }

    fun shareExportedFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "ارسال فایل یادداشت مطالعه"))
    }

    private fun wrapRtlText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
