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
        String script = """
                (function(){
                  if(window.__nabdNewsEnhancementsV15)return;
                  window.__nabdNewsEnhancementsV15=true;

                  function classify(text){
                    var t=(text||'').toLowerCase();
                    var isMap=/خريطة|خارطة|معارك|معركة|اشتباك|اشتباكات|جبهة|جبهات|سيطرة|مناطق السيطرة|مناطق النفوذ|خطوط التماس|تقدم القوات|تطورات ميدانية|عملية عسكرية/.test(t);
                    if(/عاجل|قصف|قصفوا|ضرب|استهدف|استهداف|غارة|غارات|انفجار|انفجارات|هجوم|هجمات|صاروخ|صواريخ|إطلاق نار|اطلاق نار/.test(t))
                      return {emoji:'🔴',label:'عاجل',map:isMap};
                    if(/حادث|حريق|اصطدام|سقوط|انهيار|إصابة|اصابة/.test(t))
                      return {emoji:'⚠️',label:'حادث',map:false};
                    if(isMap)
                      return {emoji:'🟣',label:'تطورات',map:true};
                    if(/مباراة|هدف|الدوري|كأس|لاعب|فريق|نادي|الهلال|الاتحاد|النصر|الأهلي/.test(t))
                      return {emoji:'⚽',label:'رياضة',map:false};
                    if(/اقتصاد|أسهم|سوق|بورصة|نفط|ذهب|ريال|دولار|شركة|أسعار/.test(t))
                      return {emoji:'📊',label:'اقتصاد',map:false};
                    if(/رسمي|بيان|وزارة|رئاسة|أعلن|اعلن|تصريح رسمي/.test(t))
                      return {emoji:'🟢',label:'رسمي',map:false};
                    if(/غريب|طريف|نادر|مفاجئ|غير مألوف/.test(t))
                      return {emoji:'👀',label:'غريب',map:false};
                    return {emoji:'🌍',label:'خبر',map:false};
                  }

                  function prefixed(text,kind){
                    var t=(text||'').trim();
                    if(!t)return t;
                    if(/^[🔴⚠️🟣🟢⚽📊👀🌍]/u.test(t))return t;
                    if(kind.label==='عاجل' && !/^عاجل\s*[:：-]/.test(t)) return kind.emoji+' عاجل: '+t;
                    return kind.emoji+' '+t;
                  }

                  function drawCover(ctx,img,x,y,w,h,zoom){
                    var iw=img.naturalWidth||img.width, ih=img.naturalHeight||img.height;
                    var z=zoom||1;
                    var s=Math.max(w/iw,h/ih)*z;
                    var sw=w/s, sh=h/s;
                    var sx=(iw-sw)/2, sy=(ih-sh)/2;
                    sx=Math.max(0,Math.min(iw-sw,sx));
                    sy=Math.max(0,Math.min(ih-sh,sy));
                    ctx.drawImage(img,sx,sy,sw,sh,x,y,w,h);
                  }

                  function enhanceSingleImage(img,kind,section){
                    if(img.dataset.nabdEnhancedV15==='1')return;
                    img.dataset.nabdEnhancedV15='1';

                    var original=img.src;
                    var work=new Image();
                    work.onload=function(){
                      try{
                        var canvas=document.createElement('canvas');
                        canvas.width=1600;
                        canvas.height=900;
                        var ctx=canvas.getContext('2d');

                        ctx.fillStyle='#eef2f3';
                        ctx.fillRect(0,0,1600,900);

                        ctx.save();
                        if(kind.map){
                          // Keep one full-frame map. A mild centered zoom trims neighboring
                          // countries/empty margins while preserving the complete Yemen outline.
                          ctx.filter='saturate(1.08) contrast(1.05) brightness(1.01)';
                          drawCover(ctx,work,0,0,1600,900,1.10);
                        }else{
                          // For all other news keep the generated visual as one uninterrupted image.
                          ctx.filter='saturate(1.12) contrast(1.08) brightness(0.99)';
                          drawCover(ctx,work,0,0,1600,900,1.00);
                        }
                        ctx.restore();

                        var data=canvas.toDataURL('image/png',0.97);
                        img.src=data;
                        img.style.filter='none';
                        img.style.objectFit='cover';

                        if(section){
                          var dl=section.querySelector('a[download]');
                          if(dl)dl.href=data;
                        }
                      }catch(e){
                        img.src=original;
                      }
                    };
                    work.src=original;
                  }

                  function enhance(){
                    var articles=document.querySelectorAll('article');
                    articles.forEach(function(article){
                      var tweetNode=article.children[1];
                      if(!tweetNode)return;

                      var raw=(tweetNode.dataset.nabdOriginalTweet||tweetNode.innerText||'').trim();
                      if(!raw)return;
                      if(!tweetNode.dataset.nabdOriginalTweet)tweetNode.dataset.nabdOriginalTweet=raw;

                      var kind=classify(raw);
                      var finalText=prefixed(raw,kind);
                      if(tweetNode.innerText!==finalText)tweetNode.innerText=finalText;

                      var buttons=Array.from(article.querySelectorAll('button'));
                      var copy=buttons.find(function(b){
                        var tx=b.innerText||'';
                        return tx.indexOf('نسخ التغريدة')>=0 || tx.indexOf('تم النسخ')>=0;
                      });

                      if(copy && copy.dataset.nabdCopyHookV15!=='1'){
                        copy.dataset.nabdCopyHookV15='1';
                        copy.addEventListener('click',function(){
                          setTimeout(function(){
                            try{navigator.clipboard.writeText((tweetNode.innerText||finalText).trim());}catch(e){}
                          },80);
                        });
                      }

                      var section=article.parentElement;
                      var img=section ? section.querySelector('img[src^="data:image/"]') : null;
                      if(img)enhanceSingleImage(img,kind,section);

                      if(!copy)return;
                      var actions=copy.parentElement;
                      if(!actions || actions.querySelector('[data-nabd-x-share]'))return;

                      var btn=document.createElement('button');
                      btn.type='button';
                      btn.setAttribute('data-nabd-x-share','1');
                      btn.style.cssText='display:inline-flex;align-items:center;gap:7px;background:#050505;color:#fff;border:1px solid #050505;border-radius:10px;padding:8px 12px;font:700 13px sans-serif;cursor:pointer;';
                      btn.innerHTML='<span style="font:bold 16px Arial">X</span><span>نشر على X</span>';
                      btn.onclick=function(){
                        try{
                          var currentImg=section.querySelector('img[src^="data:image/"]');
                          var tweet=(tweetNode.innerText||finalText).trim();
                          if(!tweet||!currentImg||!window.NabdAndroid)return;
                          var data=currentImg.src;
                          var comma=data.indexOf(',');
                          if(comma<0)return;
                          var header=data.substring(0,comma);
                          var mime=(header.match(/^data:([^;]+)/)||[])[1]||'image/png';
                          var payload=data.substring(comma+1);
                          window.NabdAndroid.beginXShare(tweet,mime);
                          var chunkSize=60000;
                          for(var i=0;i<payload.length;i+=chunkSize){
                            window.NabdAndroid.appendXShareChunk(payload.substring(i,i+chunkSize));
                          }
                          window.NabdAndroid.finishXShare();
                        }catch(e){}
                      };
                      actions.appendChild(btn);
                    });
                  }

                  enhance();
                  new MutationObserver(enhance).observe(document.documentElement,{childList:true,subtree:true});
                })();
                """;

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
