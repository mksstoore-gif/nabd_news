package com.mksstoore.nabdnews;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://nabd-news-personal.floot.app";

    private WebView webView;
    private ProgressBar progressBar;

    private final StringBuilder shareImageBuffer = new StringBuilder();
    private String shareTweetText = "";
    private String shareMimeType = "image/png";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(Color.rgb(6, 24, 33));
        getWindow().setNavigationBarColor(Color.rgb(6, 24, 33));

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new ShareBridge(), "NabdAndroid");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && (host.equals("nabd-news-personal.floot.app") || host.endsWith(".floot.app"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectXShareButton();
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url != null && url.startsWith("data:image/")) {
                    saveDataImage(url);
                } else if (url != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "تعذر فتح رابط التحميل", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void injectXShareButton() {
        String script =
                "(function(){" +
                "if(window.__nabdXShareInstalled)return;" +
                "window.__nabdXShareInstalled=true;" +
                "function addButtons(){" +
                "var articles=document.querySelectorAll('article');" +
                "articles.forEach(function(article){" +
                "var buttons=Array.from(article.querySelectorAll('button'));" +
                "var copy=buttons.find(function(b){return (b.innerText||'').indexOf('نسخ التغريدة')>=0 || (b.innerText||'').indexOf('تم النسخ')>=0;});" +
                "if(!copy)return;" +
                "var actions=copy.parentElement;" +
                "if(!actions || actions.querySelector('[data-nabd-x-share]'))return;" +
                "var section=article.parentElement;" +
                "var img=section ? section.querySelector('img[src^=\"data:image/\"]') : null;" +
                "if(!img)return;" +
                "var btn=document.createElement('button');" +
                "btn.type='button';" +
                "btn.setAttribute('data-nabd-x-share','1');" +
                "btn.style.cssText='display:inline-flex;align-items:center;gap:7px;background:#050505;color:#fff;border:1px solid #050505;border-radius:10px;padding:8px 12px;font:700 13px sans-serif;cursor:pointer;';" +
                "btn.innerHTML='<span style=\"font:bold 16px Arial\">X</span><span>نشر على X</span>';" +
                "btn.onclick=function(){" +
                "try{" +
                "var tweet=(article.children[1]&&article.children[1].innerText?article.children[1].innerText:'').trim();" +
                "var currentImg=section.querySelector('img[src^=\"data:image/\"]');" +
                "if(!tweet||!currentImg||!window.NabdAndroid)return;" +
                "var data=currentImg.src;" +
                "var comma=data.indexOf(',');" +
                "if(comma<0)return;" +
                "var header=data.substring(0,comma);" +
                "var mime=(header.match(/^data:([^;]+)/)||[])[1]||'image/png';" +
                "var payload=data.substring(comma+1);" +
                "window.NabdAndroid.beginXShare(tweet,mime);" +
                "var chunkSize=60000;" +
                "for(var i=0;i<payload.length;i+=chunkSize){" +
                "window.NabdAndroid.appendXShareChunk(payload.substring(i,i+chunkSize));" +
                "}" +
                "window.NabdAndroid.finishXShare();" +
                "}catch(e){}" +
                "};" +
                "actions.appendChild(btn);" +
                "});" +
                "}" +
                "addButtons();" +
                "new MutationObserver(addButtons).observe(document.documentElement,{childList:true,subtree:true});" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private class ShareBridge {
        @JavascriptInterface
        public void beginXShare(String text, String mimeType) {
            synchronized (shareImageBuffer) {
                shareImageBuffer.setLength(0);
                shareTweetText = text == null ? "" : text;
                shareMimeType = mimeType == null || mimeType.isEmpty() ? "image/png" : mimeType;
            }
        }

        @JavascriptInterface
        public void appendXShareChunk(String chunk) {
            if (chunk == null) return;
            synchronized (shareImageBuffer) {
                shareImageBuffer.append(chunk);
            }
        }

        @JavascriptInterface
        public void finishXShare() {
            final String payload;
            final String text;
            final String mime;
            synchronized (shareImageBuffer) {
                payload = shareImageBuffer.toString();
                text = shareTweetText;
                mime = shareMimeType;
                shareImageBuffer.setLength(0);
            }
            runOnUiThread(() -> sharePostToX(text, mime, payload));
        }

        @JavascriptInterface
        public void shareToX(String text, String dataUrl) {
            if (dataUrl == null) return;
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return;
            String header = dataUrl.substring(0, comma);
            String mime = "image/png";
            int colon = header.indexOf(':');
            int semi = header.indexOf(';');
            if (colon >= 0 && semi > colon) {
                mime = header.substring(colon + 1, semi);
            }
            String payload = dataUrl.substring(comma + 1);
            final String finalMime = mime;
            runOnUiThread(() -> sharePostToX(text, finalMime, payload));
        }
    }

    private void sharePostToX(String text, String mimeType, String base64Payload) {
        try {
            byte[] bytes = Base64.decode(base64Payload, Base64.DEFAULT);
            Uri imageUri = createImageUri(bytes, mimeType, "XShare");
            if (imageUri == null) throw new IllegalStateException("Could not create share image");

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_TEXT, text);
            share.putExtra(Intent.EXTRA_STREAM, imageUri);
            share.setClipData(ClipData.newRawUri("نبض الخبر", imageUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setPackage("com.twitter.android");

            try {
                startActivity(share);
            } catch (ActivityNotFoundException noXApp) {
                share.setPackage(null);
                startActivity(Intent.createChooser(share, "نشر الخبر"));
            }
        } catch (Exception e) {
            try {
                Uri compose = Uri.parse("https://x.com/intent/post?text=" + Uri.encode(text));
                startActivity(new Intent(Intent.ACTION_VIEW, compose));
                Toast.makeText(this, "تم فتح X بالنص؛ تعذر إرفاق الصورة تلقائياً", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                Toast.makeText(this, "تعذر فتح X", Toast.LENGTH_LONG).show();
            }
        }
    }

    private Uri createImageUri(byte[] bytes, String mime, String subFolder) throws Exception {
        String safeMime = mime == null || mime.isEmpty() ? "image/png" : mime;
        String ext = safeMime.contains("jpeg") || safeMime.contains("jpg") ? ".jpg" : ".png";
        String fileName = "nabd-news-" + System.currentTimeMillis() + ext;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, safeMime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NabdNews/" + subFolder);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = getContentResolver().insert(collection, values);
        if (item == null) throw new IllegalStateException("Could not create image");

        try (OutputStream out = getContentResolver().openOutputStream(item)) {
            if (out == null) throw new IllegalStateException("Could not open image");
            out.write(bytes);
            out.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(item, done, null, null);
        }

        return item;
    }

    private void saveDataImage(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Invalid data URL");

            String header = dataUrl.substring(0, comma);
            String payload = dataUrl.substring(comma + 1);
            byte[] bytes = header.contains(";base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);

            String mime = "image/png";
            int colon = header.indexOf(':');
            int semi = header.indexOf(';');
            if (colon >= 0 && semi > colon) {
                mime = header.substring(colon + 1, semi);
            }

            createImageUri(bytes, mime, "Saved");
            Toast.makeText(this, "تم حفظ الصورة في الاستديو", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر حفظ الصورة", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
