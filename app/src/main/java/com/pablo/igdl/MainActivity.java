package com.pablo.igdl;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abre Instagram en un WebView (con tu sesión), detecta la URL del vídeo
 * que se reproduce y la descarga a Películas/Instagram.
 */
public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private WebView web;
    private EditText urlBox;
    private Button dlBtn;
    private TextView status;
    private volatile String videoUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (!handleIntent(getIntent())) {
            web.loadUrl("https://www.instagram.com/");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        urlBox = new EditText(this);
        urlBox.setHint("Pega el enlace de Instagram");
        urlBox.setSingleLine(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = new Button(this);
        paste.setText("Pegar");
        Button go = new Button(this);
        go.setText("Abrir");
        row.addView(paste, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(go, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dlBtn = new Button(this);
        dlBtn.setText("Descargar vídeo");
        dlBtn.setEnabled(false);
        root.addView(dlBtn);

        status = new TextView(this);
        status.setText("Abre un reel o vídeo y dale al play.");
        status.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(status);

        setContentView(root);

        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null) {
                    urlBox.setText(extractUrl(t.toString()));
                    openFromBox();
                }
            }
        });
        go.setOnClickListener(v -> openFromBox());
        dlBtn.setOnClickListener(v -> download());

        setupWebView();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                // Enlaces tipo instagram://reels_share/?shortcode=XXXX -> volver a la web
                String code = request.getUrl().getQueryParameter("shortcode");
                String cur = view.getUrl();
                if (code != null && (cur == null || !cur.contains(code))) {
                    view.loadUrl("https://www.instagram.com/reel/" + code + "/");
                }
                return true; // nunca intentar abrir esquemas desconocidos
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                consider(request.getUrl().toString());
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                String js = "(function(){var v=document.querySelector('video');"
                        + "if(v&&v.src&&v.src.indexOf('http')==0)return v.src;"
                        + "var m=document.querySelector('meta[property=\"og:video\"]');"
                        + "return m?m.content:'';})()";
                view.evaluateJavascript(js, value -> {
                    if (value == null) return;
                    String s = value.replace("\"", "")
                            .replace("\\u0026", "&")
                            .replace("\\/", "/");
                    if (s.startsWith("http")) consider(s);
                });
            }
        });
    }

    /** Guarda la URL si parece el mp4 de un vídeo (no solo audio). */
    private void consider(String url) {
        if (url == null || !url.startsWith("http")) return;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        String path = u.getPath();
        if (host == null || path == null) return;
        if (!(host.contains("cdninstagram.com") || host.contains("fbcdn.net"))) return;
        if (!path.contains(".mp4")) return;

        String efg = u.getQueryParameter("efg");
        if (efg != null) {
            try {
                String d = new String(Base64.decode(efg,
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
                if (d.toLowerCase().contains("audio")) return;
            } catch (Exception ignored) {
            }
        }

        // Quitar el rango de bytes para bajar el fichero completo
        String full = url.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        videoUrl = full;
        runOnUiThread(() -> {
            dlBtn.setEnabled(true);
            status.setText("Vídeo detectado. Pulsa Descargar.");
        });
    }

    private boolean handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                String link = extractUrl(text);
                urlBox.setText(link);
                openFromBox();
                return true;
            }
        }
        return false;
    }

    private String extractUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private void openFromBox() {
        String url = urlBox.getText().toString().trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http")) url = "https://" + url;
        videoUrl = null;
        dlBtn.setEnabled(false);
        status.setText("Cargando… dale al play al vídeo.");
        web.loadUrl(url);
    }

    private void download() {
        String url = videoUrl;
        if (url == null) return;
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.addRequestHeader("User-Agent", UA);
            req.addRequestHeader("Referer", "https://www.instagram.com/");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setTitle("Instagram");
            req.setMimeType("video/mp4");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
                    "Instagram/ig_" + System.currentTimeMillis() + ".mp4");
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "Descargando en Películas/Instagram", Toast.LENGTH_LONG).show();
            status.setText("Descarga iniciada.");
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
