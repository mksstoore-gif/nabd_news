package com.waleed.moneyprinter;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class MainActivity extends Activity {
    private EditText endpointInput, apiKeyInput, subjectInput, scriptInput, termsInput;
    private Spinner aspectSpinner, sourceSpinner, voiceSpinner;
    private TextView statusText, progressText;
    private ProgressBar progressBar;
    private Button generateButton, downloadButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastVideoUrl = null;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(23,22,43));
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        TextView title = tv("MoneyPrinter Turbo", 28, true);
        title.setTextColor(Color.rgb(108,77,255));
        root.addView(title);
        TextView sub = tv("واجهة أندرويد عربية لمحرك MoneyPrinterTurbo الأصلي — بدون حد استخدام من التطبيق.", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        endpointInput = field("عنوان المحرك", "http://127.0.0.1:8080");
        root.addView(endpointInput);
        apiKeyInput = field("API Key (اختياري)", "");
        root.addView(apiKeyInput);

        Button testButton = button("اختبار الاتصال");
        root.addView(testButton);
        testButton.setOnClickListener(v -> testConnection());

        subjectInput = field("موضوع الفيديو", "مثال: مستقبل الذكاء الاصطناعي في السعودية");
        root.addView(subjectInput);

        scriptInput = multiline("النص الجاهز (اختياري — اتركه فارغًا ليولده المحرك)");
        root.addView(scriptInput);
        termsInput = multiline("كلمات البحث (اختياري، افصل بينها بفواصل)");
        termsInput.setMinLines(2);
        root.addView(termsInput);

        root.addView(label("مقاس الفيديو"));
        aspectSpinner = spinner(new String[]{"عمودي 9:16","أفقي 16:9","مربع 1:1"});
        root.addView(aspectSpinner);

        root.addView(label("مصدر المقاطع"));
        sourceSpinner = spinner(new String[]{"Pexels","Pixabay","Coverr"});
        root.addView(sourceSpinner);

        root.addView(label("الصوت"));
        voiceSpinner = spinner(new String[]{"سعودي - حامد","سعودي - زارية","إماراتي - حمدان","إماراتي - فاطمة"});
        root.addView(voiceSpinner);

        generateButton = button("إنشاء الفيديو");
        root.addView(generateButton);
        generateButton.setOnClickListener(v -> startGeneration());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(14)));

        progressText = tv("0%", 14, true);
        root.addView(progressText);
        statusText = tv("جاهز", 14, false);
        statusText.setTextColor(Color.DKGRAY);
        root.addView(statusText);

        downloadButton = button("تنزيل الفيديو MP4");
        downloadButton.setEnabled(false);
        root.addView(downloadButton);
        downloadButton.setOnClickListener(v -> downloadVideo());

        TextView note = tv("مهم: التطبيق لا يفرض حصصًا أو اشتراكًا. أي حدود تأتي فقط من مزودات الذكاء الاصطناعي/المواد التي تضبطها داخل MoneyPrinterTurbo. يمكن استخدام محرك محلي أو سيرفرك الخاص.", 13, false);
        note.setTextColor(Color.GRAY);
        root.addView(note);

        setContentView(scroll);
    }

    private void testConnection() {
        setBusy("أختبر الاتصال...");
        new Thread(() -> {
            try {
                String res = request("GET", "/api/v1/tasks?page=1&page_size=1", null);
                runOnUiThread(() -> {
                    generateButton.setEnabled(true);
                    statusText.setText("الاتصال ناجح ✓");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    generateButton.setEnabled(true);
                    statusText.setText("فشل الاتصال: " + clean(e.getMessage()));
                });
            }
        }).start();
    }

    private void startGeneration() {
        String subject = subjectInput.getText().toString().trim();
        if (subject.isEmpty() || subject.startsWith("مثال:")) {
            Toast.makeText(this, "اكتب موضوع الفيديو أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy("أرسل مهمة إنشاء الفيديو...");
        downloadButton.setEnabled(false);
        lastVideoUrl = null;
        progressBar.setProgress(0);
        progressText.setText("0%");

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("video_subject", subject);
                String script = scriptInput.getText().toString().trim();
                if (!script.isEmpty()) body.put("video_script", script);
                String terms = termsInput.getText().toString().trim();
                if (!terms.isEmpty()) body.put("video_terms", terms);
                body.put("video_aspect", aspectValue());
                body.put("video_source", sourceValue());
                body.put("voice_name", voiceValue());
                body.put("video_language", "ar");
                body.put("video_count", 1);
                body.put("paragraph_number", 1);
                body.put("subtitle_enabled", true);
                body.put("subtitle_position", "bottom");
                body.put("subtitle_display_mode", "sentence");
                body.put("bgm_type", "random");
                body.put("bgm_volume", 0.18);
                body.put("video_clip_duration", 5);
                body.put("video_fit_mode", "cover");
                body.put("video_concat_mode", "sequential");

                JSONObject response = new JSONObject(request("POST", "/api/v1/videos", body.toString()));
                JSONObject data = response.optJSONObject("data");
                String taskId = data == null ? "" : data.optString("task_id", "");
                if (taskId.isEmpty()) throw new Exception(response.optString("message", "لم يرجع رقم المهمة"));
                runOnUiThread(() -> statusText.setText("بدأت المهمة: " + taskId.substring(0, Math.min(8, taskId.length()))));
                pollTask(taskId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    generateButton.setEnabled(true);
                    statusText.setText("تعذر بدء التوليد: " + clean(e.getMessage()));
                });
            }
        }).start();
    }

    private void pollTask(String taskId) {
        handler.postDelayed(() -> new Thread(() -> {
            try {
                JSONObject response = new JSONObject(request("GET", "/api/v1/tasks/" + taskId, null));
                JSONObject data = response.optJSONObject("data");
                if (data == null) throw new Exception(response.optString("message", "لا توجد بيانات"));
                int progress = Math.max(0, Math.min(100, data.optInt("progress", 0)));
                String error = data.optString("error", "");
                String stage = data.optString("failed_stage", "");
                runOnUiThread(() -> {
                    progressBar.setProgress(progress);
                    progressText.setText(progress + "%");
                    statusText.setText(progress < 100 ? "جاري الإنشاء..." : "اكتمل");
                });

                if (!error.isEmpty()) {
                    runOnUiThread(() -> {
                        generateButton.setEnabled(true);
                        statusText.setText("فشل" + (stage.isEmpty() ? "" : " في " + stage) + ": " + error);
                    });
                    return;
                }

                String video = firstVideo(data.optJSONArray("combined_videos"));
                if (video == null) video = firstVideo(data.optJSONArray("videos"));
                if (video != null) {
                    final String resolved = resolveVideoUrl(video);
                    runOnUiThread(() -> {
                        lastVideoUrl = resolved;
                        progressBar.setProgress(100);
                        progressText.setText("100%");
                        statusText.setText("تم إنشاء الفيديو ✓");
                        generateButton.setEnabled(true);
                        downloadButton.setEnabled(true);
                    });
                    return;
                }
                pollTask(taskId);
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("أعيد المحاولة: " + clean(e.getMessage())));
                pollTask(taskId);
            }
        }).start(), 3000);
    }

    private String firstVideo(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        String s = arr.optString(0, "");
        return s.isEmpty() ? null : s;
    }

    private void downloadVideo() {
        if (lastVideoUrl == null) return;
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(lastVideoUrl));
            r.setTitle("MoneyPrinterTurbo");
            r.setDescription("تنزيل الفيديو");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MoneyPrinter-" + System.currentTimeMillis() + ".mp4");
            String key = apiKeyInput.getText().toString().trim();
            if (!key.isEmpty()) r.addRequestHeader("x-api-key", key);
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, "بدأ التنزيل إلى مجلد Downloads", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر التنزيل: " + clean(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private String request(String method, String path, String body) throws Exception {
        String base = endpointInput.getText().toString().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        URL url = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        String key = apiKeyInput.getText().toString().trim();
        if (!key.isEmpty()) c.setRequestProperty("x-api-key", key);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + text);
        return text;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private String resolveVideoUrl(String v) {
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        String base = endpointInput.getText().toString().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        return base + (v.startsWith("/") ? v : "/" + v);
    }

    private String aspectValue() {
        int p = aspectSpinner.getSelectedItemPosition();
        return p == 1 ? "16:9" : p == 2 ? "1:1" : "9:16";
    }
    private String sourceValue() {
        int p = sourceSpinner.getSelectedItemPosition();
        return p == 1 ? "pixabay" : p == 2 ? "coverr" : "pexels";
    }
    private String voiceValue() {
        int p = voiceSpinner.getSelectedItemPosition();
        if (p == 1) return "ar-SA-ZariyahNeural";
        if (p == 2) return "ar-AE-HamdanNeural";
        if (p == 3) return "ar-AE-FatimaNeural";
        return "ar-SA-HamedNeural";
    }

    private void setBusy(String s) {
        generateButton.setEnabled(false);
        statusText.setText(s);
    }
    private String clean(String s) {
        if (s == null) return "خطأ غير معروف";
        return s.length() > 350 ? s.substring(0,350) : s;
    }

    private TextView tv(String text, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sp); v.setPadding(0, dp(6), 0, dp(6));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private TextView label(String t) {
        TextView v = tv(t, 14, true); v.setPadding(0, dp(14), 0, dp(4)); return v;
    }
    private EditText field(String hint, String preset) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(preset); e.setTextSize(15); e.setSingleLine(true);
        e.setPadding(dp(10), dp(12), dp(10), dp(12)); return e;
    }
    private EditText multiline(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextSize(15); e.setMinLines(3); e.setGravity(Gravity.TOP | Gravity.RIGHT);
        e.setPadding(dp(10), dp(12), dp(10), dp(12)); return e;
    }
    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a); return s;
    }
    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, dp(10), 0, dp(4)); b.setLayoutParams(lp); return b;
    }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density); }
}
