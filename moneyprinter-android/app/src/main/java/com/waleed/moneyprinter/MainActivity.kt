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
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var engine: InferenceEngine
    private lateinit var modelStatus: TextView
    private lateinit var jobStatus: TextView
    private lateinit var topicInput: EditText
    private lateinit var scriptInput: EditText
    private lateinit var aspectSpinner: Spinner
    private lateinit var createButton: Button
    private lateinit var progressBar: ProgressBar
    private var modelReady = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val modelDir by lazy { File(filesDir, "models").apply { mkdirs() } }
    private val defaultModel by lazy { File(modelDir, "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf") }

    companion object {
        private const val PICK_MODEL = 202
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(23, 22, 43)
        engine = AiChat.getInferenceEngine(applicationContext)
        initTts()
        buildUi()
        if (defaultModel.exists() && defaultModel.length() > 300_000_000L) loadModel(defaultModel)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)

        root.addView(text("MoneyPrinter محلي", 28, true).apply { setTextColor(Color.rgb(108, 77, 255)) })
        root.addView(text("التوليد يعمل داخل الجوال. لا سيرفر، لا 127.0.0.1، ولا حد استخدام من التطبيق.", 14, false))

        modelStatus = text("النموذج: غير محمّل", 14, true)
        root.addView(modelStatus)

        val downloadModel = button("تنزيل نموذج الذكاء المحلي (~400MB)")
        root.addView(downloadModel)
        downloadModel.setOnClickListener { downloadDefaultModel(downloadModel) }

        val chooseModel = button("اختيار ملف GGUF موجود")
        root.addView(chooseModel)
        chooseModel.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, PICK_MODEL)
        }

        topicInput = EditText(this).apply {
            hint = "موضوع الفيديو"
            textSize = 17f
            setSingleLine(true)
        }
        root.addView(topicInput)

        scriptInput = EditText(this).apply {
            hint = "النص اختياري — إذا تركته فارغًا يولده النموذج المحلي"
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

        createButton = button("إنشاء الفيديو محليًا")
        root.addView(createButton)
        createButton.setOnClickListener { createLocalVideo() }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(14)))

        jobStatus = text("جاهز", 14, false)
        root.addView(jobStatus)

        root.addView(
            text(
                "ملاحظة: إذا كان صوت العربية غير مثبت على الجهاز، نزّل صوت العربية من إعدادات تحويل النص إلى كلام. النموذج يُنزّل مرة واحدة فقط.",
                13,
                false
            ).apply { setTextColor(Color.DKGRAY) }
        )

        setContentView(scroll)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar", "SA"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun downloadDefaultModel(button: Button) {
        if (modelReady) return
        button.isEnabled = false
        scope.launch {
            modelStatus.text = "جاري تنزيل النموذج..."
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(modelDir, "model.tmp")
                    var current = 0L
                    val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 60_000
                    conn.connect()
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(1024 * 256)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                current += n
                                if (total > 0) {
                                    val p = (current * 100 / total).toInt()
                                    runOnUiThread { modelStatus.text = "تنزيل النموذج: $p%" }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (tmp.length() < 300_000_000L) throw IOException("الملف ناقص")
                    if (defaultModel.exists()) defaultModel.delete()
                    tmp.renameTo(defaultModel)
                } catch (_: Exception) {
                    false
                }
            }
            button.isEnabled = true
            if (ok) loadModel(defaultModel) else modelStatus.text = "فشل تنزيل النموذج. جرّب اختيار GGUF يدويًا."
        }
    }

    private fun loadModel(file: File) {
        modelReady = false
        modelStatus.text = "جاري تحميل النموذج في الذاكرة..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    engine.loadModel(file.absolutePath)
                    engine.setSystemPrompt(
                        "أنت كاتب فيديوهات عربية قصيرة. اكتب نصًا عربيًا واضحًا ومباشرًا مناسبًا لفيديو قصير. لا تستخدم عناوين أو نقاط أو Markdown، فقط النص المنطوق."
                    )
                }
                modelReady = true
                modelStatus.text = "النموذج المحلي جاهز ✓"
            } catch (e: Exception) {
                modelStatus.text = "تعذر تحميل النموذج: ${short(e.message)}"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            modelStatus.text = "جاري نسخ النموذج..."
            scope.launch {
                try {
                    val copied = withContext(Dispatchers.IO) {
                        val f = File(modelDir, "custom-model.gguf")
                        contentResolver.openInputStream(uri)!!.use { input ->
                            FileOutputStream(f).use { output -> input.copyTo(output, 1024 * 1024) }
                        }
                        f
                    }
                    loadModel(copied)
                } catch (e: Exception) {
                    modelStatus.text = "تعذر نسخ النموذج: ${short(e.message)}"
                }
            }
        }
    }

    private fun createLocalVideo() {
        val topic = topicInput.text.toString().trim()
        if (topic.isEmpty()) {
            Toast.makeText(this, "اكتب موضوع الفيديو", Toast.LENGTH_SHORT).show()
            return
        }

        val supplied = scriptInput.text.toString().trim()
        if (supplied.isEmpty() && !modelReady) {
            Toast.makeText(this, "نزّل/اختر النموذج المحلي أولاً، أو اكتب النص يدويًا", Toast.LENGTH_LONG).show()
            return
        }

        createButton.isEnabled = false
        progressBar.progress = 2
        jobStatus.text = "بدأ التوليد المحلي..."

        scope.launch {
            try {
                val script = if (supplied.isNotEmpty()) supplied else generateScript(topic)
                scriptInput.setText(script)

                progressBar.progress = 28
                jobStatus.text = "إنشاء الصوت على الجهاز..."
                val audio = withContext(Dispatchers.IO) { synthesize(script) }

                progressBar.progress = 50
                jobStatus.text = "إنشاء المشاهد..."
                val cards = withContext(Dispatchers.IO) { makeCards(topic, script) }

                progressBar.progress = 65
                jobStatus.text = "تركيب الفيديو محليًا..."
                val out = withContext(Dispatchers.IO) { composeVideo(cards, audio) }

                progressBar.progress = 92
                val uri = withContext(Dispatchers.IO) { saveToDownloads(out) }

                progressBar.progress = 100
                jobStatus.text = "تم إنشاء الفيديو وحفظه في Downloads ✓"
                Toast.makeText(this@MainActivity, "تم الحفظ: Downloads/MoneyPrinter", Toast.LENGTH_LONG).show()

                if (uri != null) {
                    val play = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(play) }
                }
            } catch (e: Exception) {
                jobStatus.text = "فشل: ${short(e.message)}"
            } finally {
                createButton.isEnabled = true
            }
        }
    }

    private suspend fun generateScript(topic: String): String = withContext(Dispatchers.Default) {
        val prompt =
            "اكتب نص فيديو عربي قصير مدته نحو 35 ثانية عن: $topic. استخدم 5 إلى 7 جمل فقط، معلومات مفهومة، بدون عناوين وبدون نقاط."
        val tokens = engine.sendUserPrompt(prompt, 320).toList()
        tokens.joinToString("").trim().ifEmpty { throw IOException("النموذج لم يولد نصًا") }
    }

    private fun synthesize(script: String): File {
        if (!ttsReady) throw IOException("صوت العربية غير جاهز في إعدادات تحويل النص إلى كلام")

        val file = File(cacheDir, "speech.wav")
        if (file.exists()) file.delete()

        val latch = CountDownLatch(1)
        val id = "mpt-${System.currentTimeMillis()}"
        var error: String? = null

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                error = "فشل توليد الصوت"
                latch.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                error = "فشل توليد الصوت ($errorCode)"
                latch.countDown()
            }
        })

        val result = tts?.synthesizeToFile(script, Bundle(), file, id) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) throw IOException("تعذر بدء توليد الصوت")
        if (!latch.await(120, TimeUnit.SECONDS)) throw IOException("انتهت مهلة توليد الصوت")
        if (error != null || !file.exists() || file.length() < 1000L) {
            throw IOException(error ?: "ملف الصوت غير صالح")
        }
        return file
    }

    private fun makeCards(topic: String, script: String): List<File> {
        val chunks = splitScript(script, 5)
        val (w, h) = when (aspectSpinner.selectedItemPosition) {
            1 -> 1280 to 720
            2 -> 720 to 720
            else -> 720 to 1280
        }

        return chunks.mapIndexed { index, chunk ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            c.drawColor(Color.rgb(20 + index * 9, 18 + index * 6, 42 + index * 8))
            p.color = Color.argb(60, 255, 255, 255)
            c.drawCircle(w * 0.78f, h * 0.22f, w * 0.34f, p)
            p.color = Color.argb(45, 108, 77, 255)
            c.drawCircle(w * 0.20f, h * 0.78f, w * 0.42f, p)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 183, 3)
                textSize = max(30f, w / 18f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(topic.take(45), w / 2f, h * 0.18f, titlePaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(34f, w / 15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            drawWrapped(c, chunk, bodyPaint, w * 0.84f, w / 2f, h * 0.42f, bodyPaint.textSize * 1.55f)

            val f = File(cacheDir, "card-$index.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 96, it) }
            bmp.recycle()
            f
        }
    }

    private fun splitScript(script: String, count: Int): List<String> {
        val parts = script.split(Regex("[.!؟!?\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(script)

        val groups = MutableList(minOf(count, parts.size)) { StringBuilder() }
        parts.forEachIndexed { i, s ->
            val g = groups[i % groups.size]
            if (g.isNotEmpty()) g.append(" ")
            g.append(s)
        }
        return groups.map { it.toString() }
    }

    private fun drawWrapped(
        c: Canvas,
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
            c.drawText(it, x, y, paint)
            y += lineHeight
        }
    }

    private fun composeVideo(cards: List<File>, audio: File): File {
        val probe = FFprobeKit.getMediaInformation(audio.absolutePath)
        val duration = probe.mediaInformation?.duration?.toDoubleOrNull()?.coerceAtLeast(8.0) ?: 30.0
        val per = duration / cards.size

        val (w, h) = when (aspectSpinner.selectedItemPosition) {
            1 -> 1280 to 720
            2 -> 720 to 720
            else -> 720 to 1280
        }

        val inputs = StringBuilder()
        cards.forEach {
            inputs.append(" -loop 1 -framerate 25 -t $per -i \"${it.absolutePath}\"")
        }
        inputs.append(" -i \"${audio.absolutePath}\"")

        val filters =
            cards.indices.joinToString(";") { i -> "[$i:v]scale=$w:$h,setsar=1[v$i]" } +
                ";" +
                cards.indices.joinToString("") { "[v$it]" } +
                "concat=n=${cards.size}:v=1:a=0[v]"

        val out = File(cacheDir, "MoneyPrinter-${System.currentTimeMillis()}.mp4")
        val cmd =
            "$inputs -filter_complex \"$filters\" -map \"[v]\" -map ${cards.size}:a -r 25 " +
                "-c:v mpeg4 -q:v 5 -pix_fmt yuv420p -c:a aac -b:a 128k -shortest -y \"${out.absolutePath}\""

        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val detail = session.failStackTrace ?: session.allLogsAsString.takeLast(350)
            throw IOException("FFmpeg: $detail")
        }
        if (!out.exists() || out.length() < 10_000L) throw IOException("لم ينتج ملف فيديو صالح")
        return out
    }

    private fun saveToDownloads(src: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MoneyPrinter")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)!!.use { out ->
            FileInputStream(src).use { input -> input.copyTo(out) }
        }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun button(t: String) = Button(this).apply {
        text = t
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(0, dp(8), 0, dp(5))
        }
    }

    private fun text(t: String, size: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size.toFloat()
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun short(s: String?) = (s ?: "خطأ غير معروف").take(300)

    override fun onDestroy() {
        scope.cancel()
        runCatching { engine.destroy() }
        tts?.shutdown()
        super.onDestroy()
    }
}
