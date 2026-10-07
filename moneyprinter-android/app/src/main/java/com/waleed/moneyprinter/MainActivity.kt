package com.waleed.moneyprinter

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var topicInput: EditText
    private lateinit var scriptInput: EditText
    private lateinit var aspectSpinner: Spinner
    private lateinit var createButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(23, 22, 43)
        initTts()
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)

        root.addView(text("MoneyPrinter Safe", 28, true).apply {
            setTextColor(Color.rgb(108, 77, 255))
        })
        root.addView(text(
            "نسخة مستقرة بدون محرك llama.cpp. إنشاء الفيديو يتم محليًا على الجوال بدون سيرفر.",
            14,
            false
        ))

        topicInput = EditText(this).apply {
            hint = "موضوع الفيديو"
            textSize = 17f
            setSingleLine(true)
        }
        root.addView(topicInput)

        scriptInput = EditText(this).apply {
            hint = "النص اختياري — إذا تركته فارغًا ينشئ التطبيق نصًا محليًا من الموضوع"
            minLines = 5
            gravity = Gravity.TOP or Gravity.RIGHT
            textSize = 15f
        }
        root.addView(scriptInput)

        root.addView(text("مقاس الفيديو", 14, true))
        aspectSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("عمودي 9:16", "أفقي 16:9", "مربع 1:1")
            )
        }
        root.addView(aspectSpinner)

        createButton = button("إنشاء الفيديو")
        root.addView(createButton)
        createButton.setOnClickListener { startCreate() }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(14)))

        statusText = text("جاهز", 14, false)
        root.addView(statusText)

        root.addView(text(
            "إذا كان النص جاهزًا عندك، الصقه في خانة النص وسيستخدمه التطبيق كما هو.",
            13,
            false
        ).apply { setTextColor(Color.DKGRAY) })

        setContentView(scroll)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ar", "SA"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun startCreate() {
        val topic = topicInput.text.toString().trim()
        if (topic.isEmpty()) {
            Toast.makeText(this, "اكتب موضوع الفيديو أولًا", Toast.LENGTH_SHORT).show()
            return
        }

        createButton.isEnabled = false
        progressBar.progress = 5
        statusText.text = "تجهيز النص..."

        scope.launch {
            try {
                val supplied = scriptInput.text.toString().trim()
                val script = if (supplied.isNotEmpty()) supplied else localScript(topic)
                scriptInput.setText(script)

                progressBar.progress = 25
                statusText.text = "إنشاء الصوت..."
                val audio = synthesize(script)

                progressBar.progress = 50
                statusText.text = "إنشاء المشاهد..."
                val cards = withContext(Dispatchers.Default) {
                    makeCards(topic, script)
                }

                progressBar.progress = 70
                statusText.text = "تركيب الفيديو..."
                val out = withContext(Dispatchers.IO) {
                    composeVideo(cards, audio)
                }

                progressBar.progress = 95
                val uri = withContext(Dispatchers.IO) {
                    saveToDownloads(out)
                }

                progressBar.progress = 100
                statusText.text = "تم إنشاء الفيديو وحفظه في Movies/MoneyPrinter ✓"
                Toast.makeText(this@MainActivity, "تم إنشاء الفيديو", Toast.LENGTH_LONG).show()

                if (uri != null) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                }
            } catch (t: Throwable) {
                progressBar.progress = 0
                statusText.text = "فشل: ${short(t.message)}"
            } finally {
                createButton.isEnabled = true
            }
        }
    }

    private fun localScript(topic: String): String {
        return "في هذا الفيديو نتحدث عن $topic. " +
            "سنبدأ بأهم فكرة يجب معرفتها عن هذا الموضوع. " +
            "ثم ننتقل إلى أبرز التفاصيل التي تساعد على فهم الصورة بشكل أوضح. " +
            "بعد ذلك نستعرض أهم نقطة عملية أو نتيجة مرتبطة بالموضوع. " +
            "وفي النهاية نلخص الفكرة الرئيسية بشكل سريع وواضح."
    }

    private suspend fun synthesize(script: String): File {
        if (!ttsReady) {
            throw IOException("الصوت العربي غير جاهز في إعدادات تحويل النص إلى كلام")
        }

        val file = File(cacheDir, "speech.wav")
        if (file.exists()) file.delete()
        val done = CompletableDeferred<Unit>()
        val id = "mpt-safe-${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (!done.isCompleted) done.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!done.isCompleted) done.completeExceptionally(IOException("فشل توليد الصوت"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!done.isCompleted) {
                    done.completeExceptionally(IOException("فشل توليد الصوت ($errorCode)"))
                }
            }
        })

        val result = tts?.synthesizeToFile(script, Bundle(), file, id) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            throw IOException("تعذر بدء توليد الصوت")
        }

        done.await()

        if (!file.exists() || file.length() < 1000L) {
            throw IOException("ملف الصوت غير صالح")
        }
        return file
    }

    private fun makeCards(topic: String, script: String): List<File> {
        val chunks = splitScript(script, 3)
        val (w, h) = when (aspectSpinner.selectedItemPosition) {
            1 -> 768 to 432
            2 -> 512 to 512
            else -> 432 to 768
        }

        return chunks.mapIndexed { index, chunk ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            canvas.drawColor(Color.rgb(20 + index * 10, 18 + index * 8, 44 + index * 10))
            paint.color = Color.argb(55, 255, 255, 255)
            canvas.drawCircle(w * 0.82f, h * 0.22f, w * 0.34f, paint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 183, 3)
                textSize = max(26f, w / 17f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(topic.take(42), w / 2f, h * 0.18f, titlePaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(30f, w / 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            drawWrapped(
                canvas,
                chunk,
                bodyPaint,
                w * 0.84f,
                w / 2f,
                h * 0.44f,
                bodyPaint.textSize * 1.5f
            )

            val file = File(cacheDir, "safe-card-$index.png")
            FileOutputStream(file).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 92, it)
            }
            bmp.recycle()
            file
        }
    }

    private fun splitScript(script: String, count: Int): List<String> {
        val parts = script
            .split(Regex("[.!؟!?\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (parts.isEmpty()) return listOf(script)

        val groups = MutableList(minOf(count, parts.size)) { StringBuilder() }
        parts.forEachIndexed { index, part ->
            val group = groups[index % groups.size]
            if (group.isNotEmpty()) group.append(" ")
            group.append(part)
        }
        return groups.map { it.toString() }
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        maxWidth: Float,
        x: Float,
        startY: Float,
        lineHeight: Float
    ) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var line = ""

        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) <= maxWidth) {
                line = test
            } else {
                if (line.isNotEmpty()) lines.add(line)
                line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)

        var y = startY - ((lines.size - 1) * lineHeight / 2f)
        lines.take(8).forEach {
            canvas.drawText(it, x, y, paint)
            y += lineHeight
        }
    }

    private fun composeVideo(cards: List<File>, audio: File): File {
        val probe = FFprobeKit.getMediaInformation(audio.absolutePath)
        val duration = probe.mediaInformation?.duration?.toDoubleOrNull()?.coerceAtLeast(8.0) ?: 25.0
        val per = duration / cards.size

        val (w, h) = when (aspectSpinner.selectedItemPosition) {
            1 -> 768 to 432
            2 -> 512 to 512
            else -> 432 to 768
        }

        val inputs = StringBuilder()
        cards.forEach {
            inputs.append(" -loop 1 -framerate 20 -t $per -i \"${it.absolutePath}\"")
        }
        inputs.append(" -i \"${audio.absolutePath}\"")

        val filters =
            cards.indices.joinToString(";") { i -> "[$i:v]scale=$w:$h,setsar=1[v$i]" } +
                ";" +
                cards.indices.joinToString("") { "[v$it]" } +
                "concat=n=${cards.size}:v=1:a=0[v]"

        val out = File(cacheDir, "MoneyPrinter-Safe-${System.currentTimeMillis()}.mp4")
        val cmd =
            "$inputs -filter_complex \"$filters\" -map \"[v]\" -map ${cards.size}:a " +
                "-r 20 -threads 2 -c:v mpeg4 -q:v 7 -pix_fmt yuv420p " +
                "-c:a aac -b:a 96k -shortest -y \"${out.absolutePath}\""

        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val detail = session.failStackTrace ?: session.allLogsAsString.takeLast(350)
            throw IOException("FFmpeg: $detail")
        }

        if (!out.exists() || out.length() < 10_000L) {
            throw IOException("لم ينتج ملف فيديو صالح")
        }
        return out
    }

    private fun saveToDownloads(src: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MoneyPrinter"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        contentResolver.openOutputStream(uri)!!.use { out ->
            FileInputStream(src).use { input ->
                input.copyTo(out)
            }
        }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(0, dp(8), 0, dp(5))
        }
    }

    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun short(value: String?) = (value ?: "خطأ غير معروف").take(260)

    override fun onDestroy() {
        scope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }
}
