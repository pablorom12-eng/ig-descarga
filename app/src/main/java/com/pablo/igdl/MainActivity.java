package com.pablo.igdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IG Descarga: navegador de Instagram con tu sesión que detecta vídeos y fotos
 * y los guarda en Películas/Instagram y Imágenes/Instagram.
 */
public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final int BG = 0xFF121212;
    private static final int CARD = 0xFF242424;
    private static final int ACCENT = 0xFF7C4DFF;
    private static final int GREY = 0xFF3A3A3A;
    private static final int TEXT = 0xFFFFFFFF;

    private WebView web;
    private EditText urlBox;
    private Button fab;
    private Button histBtn;
    private TextView status;
    private TextView emptyView;
    private ListView histList;

    private final List<String> cands = new ArrayList<>();
    private boolean autoMode;
    private boolean autoStarted;
    private boolean fallbackScheduled;
    private boolean historyShown;
    private boolean fabWanted;
    private boolean busy;
    private Runnable lastAction;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<JSONObject> hist = new ArrayList<>();
    private final List<String> histLabels = new ArrayList<>();
    private ArrayAdapter<String> histAdapter;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        loadHistory();
        if (!handleIntent(getIntent())) {
            web.loadUrl("https://www.instagram.com/");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (historyShown) {
            showHistory(false);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ interfaz

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button makeBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(TEXT);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        b.setBackground(gd);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(2), dp(6), dp(2), dp(6));
        return lp;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        urlBox = new EditText(this);
        urlBox.setHint("Pega el enlace de Instagram");
        urlBox.setHintTextColor(0xFF888888);
        urlBox.setTextColor(TEXT);
        urlBox.setSingleLine(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(CARD);
        ebg.setCornerRadius(dp(12));
        urlBox.setBackground(ebg);
        urlBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            openFromBox(false);
            return true;
        });
        root.addView(urlBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = makeBtn("Pegar", GREY);
        Button go = makeBtn("Abrir", GREY);
        Button photos = makeBtn("Fotos", GREY);
        histBtn = makeBtn("Historial", GREY);
        row.addView(paste, weighted());
        row.addView(go, weighted());
        row.addView(photos, weighted());
        row.addView(histBtn, weighted());
        root.addView(row);

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        content.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        histList = new ListView(this);
        histList.setBackgroundColor(BG);
        histList.setVisibility(View.GONE);
        histAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, histLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(TEXT);
                t.setTextSize(14);
                return t;
            }
        };
        histList.setAdapter(histAdapter);
        content.addView(histList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setText("Aún no has descargado nada.");
        emptyView.setTextColor(0xFF999999);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fab = makeBtn("⬇  Descargar vídeo", ACCENT);
        fab.setTextSize(15);
        fab.setElevation(dp(8));
        fab.setPadding(dp(20), 0, dp(20), 0);
        fab.setVisibility(View.GONE);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fl.bottomMargin = dp(24);
        content.addView(fab, fl);

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(0xFFBBBBBB);
        status.setTextSize(13);
        status.setText("Abre un reel o foto. Si es vídeo, dale al play.");
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
                    openFromBox(false);
                }
            }
        });
        go.setOnClickListener(v -> openFromBox(false));
        photos.setOnClickListener(v -> {
            showHistory(false);
            downloadPhotos(false);
        });
        histBtn.setOnClickListener(v -> showHistory(!historyShown));
        fab.setOnClickListener(v -> {
            if (lastAction != null) lastAction.run();
        });

        histList.setOnItemClickListener((parent, view, pos, id) -> openItem(pos));
        histList.setOnItemLongClickListener((parent, view, pos, id) -> {
            confirmDelete(pos);
            return true;
        });

        setupWebView();
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void ui(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void showFab(String text, Runnable action) {
        fab.setText(text);
        lastAction = action;
        fabWanted = true;
        if (!historyShown) fab.setVisibility(View.VISIBLE);
    }

    private void hideFab() {
        fabWanted = false;
        fab.setVisibility(View.GONE);
    }

    private void showHistory(boolean show) {
        historyShown = show;
        web.setVisibility(show ? View.GONE : View.VISIBLE);
        histList.setVisibility(show ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(show && hist.isEmpty() ? View.VISIBLE : View.GONE);
        fab.setVisibility(!show && fabWanted ? View.VISIBLE : View.GONE);
        histBtn.setText(show ? "Navegador" : "Historial");
    }

    // ------------------------------------------------------------------ WebView

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
                // instagram://reels_share/?shortcode=XXXX  ->  volver a la web
                String code = request.getUrl().getQueryParameter("shortcode");
                String cur = view.getUrl();
                if (code != null && (cur == null || !cur.contains(code))) {
                    view.loadUrl("https://www.instagram.com/reel/" + code + "/");
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                consider(request.getUrl().toString());
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(readAsset("clean.js"), null);
                String js = "(function(){var v=document.querySelector('video');"
                        + "if(v&&v.src&&v.src.indexOf('http')==0)return v.src;"
                        + "var m=document.querySelector('meta[property=\"og:video\"]');"
                        + "return m?m.content:'';})()";
                view.evaluateJavascript(js, value -> {
                    if (value == null) return;
                    String s2 = value.replace("\"", "")
                            .replace("\\u0026", "&")
                            .replace("\\/", "/");
                    if (s2.startsWith("http")) consider(s2);
                });
                if (autoMode && !fallbackScheduled) {
                    fallbackScheduled = true;
                    h.postDelayed(() -> {
                        if (!autoStarted) {
                            autoStarted = true;
                            downloadPhotos(true);
                        }
                    }, 6000);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    setStatus("No se pudo cargar la página: " + error.getDescription());
                }
            }
        });
    }

    private boolean isAudioUrl(Uri u) {
        String efg = u.getQueryParameter("efg");
        if (efg == null) return false;
        try {
            String d = new String(Base64.decode(efg,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            return d.toLowerCase().contains("audio");
        } catch (Exception e) {
            return false;
        }
    }

    /** Guarda la URL si parece el mp4 (vídeo o audio) de una publicación. */
    private void consider(String url) {
        if (url == null || !url.startsWith("http")) return;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        String path = u.getPath();
        if (host == null || path == null) return;
        if (!(host.contains("cdninstagram.com") || host.contains("fbcdn.net"))) return;
        if (!path.contains(".mp4")) return;

        final String full = url.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        final boolean audio = isAudioUrl(u);
        synchronized (cands) {
            if (!cands.contains(full)) {
                cands.add(full);
                if (cands.size() > 6) cands.remove(0);
            }
        }
        runOnUiThread(() -> onMediaDetected(!audio));
    }

    private void onMediaDetected(boolean video) {
        if (!video) return;
        showFab("⬇  Descargar vídeo", this::downloadVideo);
        setStatus("Vídeo detectado.");
        if (autoMode && !autoStarted) {
            autoStarted = true;
            setStatus("Descargando automáticamente…");
            h.postDelayed(this::downloadVideo, 2500);
        }
    }

    // ------------------------------------------------------------------ entrada de enlaces

    private boolean handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                urlBox.setText(extractUrl(text));
                openFromBox(true);
                return true;
            }
        }
        return false;
    }

    private String extractUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private void openFromBox(boolean auto) {
        String url = urlBox.getText().toString().trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http")) url = "https://" + url;
        autoMode = auto;
        autoStarted = false;
        fallbackScheduled = false;
        synchronized (cands) {
            cands.clear();
        }
        hideFab();
        showHistory(false);
        setStatus(auto ? "Abriendo y descargando…" : "Cargando… si es vídeo, dale al play.");
        web.loadUrl(url);
    }

    // ------------------------------------------------------------------ descarga de vídeo

    private void downloadVideo() {
        final List<String> list;
        synchronized (cands) {
            list = new ArrayList<>(cands);
        }
        final String pageUrl = web.getUrl();
        if (list.isEmpty()) {
            setStatus("A\u00FAn no hay v\u00EDdeo detectado. Dale al play.");
            return;
        }
        if (busy) return;
        busy = true;
        setStatus("Descargando v\u00EDdeo\u2026");
        exec.execute(() -> {
            String msg;
            boolean ok = false;
            try {
                File best = null;
                File vOnly = null;
                File aOnly = null;
                int n = 0;
                for (String u : list) {
                    File f = new File(getCacheDir(), "c" + (n++) + ".mp4");
                    try {
                        fetch(u, f);
                    } catch (Exception e) {
                        continue;
                    }
                    boolean a = hasAudio(f);
                    boolean v = hasVideo(f);
                    if (a && v) {
                        if (best == null || f.length() > best.length()) best = f;
                    } else if (v) {
                        if (vOnly == null || f.length() > vOnly.length()) vOnly = f;
                    } else if (a) {
                        if (aOnly == null || f.length() > aOnly.length()) aOnly = f;
                    }
                }
                String note = "";
                if (best == null && vOnly != null && aOnly != null) {
                    try {
                        ui("Uniendo v\u00EDdeo y audio\u2026");
                        File mf = new File(getCacheDir(), "m.mp4");
                        mux(vOnly, aOnly, mf);
                        best = mf;
                    } catch (Exception e) {
                        note = " (no se pudo unir el audio)";
                    }
                }
                if (best == null) {
                    ui("Probando otro m\u00E9todo\u2026");
                    File emb = tryEmbed(pageUrl);
                    if (emb != null && hasAudio(emb)) best = emb;
                }
                if (best == null) best = vOnly;
                if (best == null) throw new IOException("no se pudo bajar el v\u00EDdeo");

                boolean sound = hasAudio(best);
                if (!sound) {
                    note += " [archivos: " + list.size() + ", con audio: "
                            + (aOnly != null ? "s\u00ED" : "no") + "]";
                }
                String name = "ig_" + System.currentTimeMillis() + ".mp4";
                Uri uri = saveToGallery(best, name, "video/mp4", true);
                addHistory(uri, name, "video/mp4", true);
                msg = "Guardado en Pel\u00EDculas/Instagram" + (sound ? "" : " (sin audio)") + note;
                ok = true;
            } catch (Exception e) {
                msg = "Error: " + e.getMessage();
            }
            final String fmsg = msg;
            final boolean fok = ok;
            runOnUiThread(() -> {
                busy = false;
                setStatus(fmsg);
                Toast.makeText(this, fmsg, Toast.LENGTH_LONG).show();
                if (!fok) showFab("\u21BB  Reintentar", this::downloadVideo);
            });
        });
    }

    /** Plan B: la p\u00E1gina embed suele traer el mp4 completo (con audio). */
    private File tryEmbed(String pageUrl) {
        try {
            if (pageUrl == null) return null;
            Matcher m = Pattern.compile(
                    "instagram\\.com/(?:[^/]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").matcher(pageUrl);
            if (!m.find()) return null;
            String html = fetchText("https://www.instagram.com/p/" + m.group(1) + "/embed/captioned/");
            html = html.replace("\\\"", "\"")
                    .replace("\\\\/", "/")
                    .replace("\\/", "/")
                    .replace("\\u0026", "&");
            Matcher v = Pattern.compile("\"video_url\":\"([^\"]+)\"").matcher(html);
            if (!v.find()) return null;
            File f = new File(getCacheDir(), "embed.mp4");
            fetch(v.group(1), f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream is = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            copy(is, bo);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ descarga de fotos

    private void downloadPhotos(boolean auto) {
        web.evaluateJavascript(readAsset("photos.js"), value -> {
            final List<String> urls = parseUrls(value);
            if (urls.isEmpty()) {
                setStatus(auto ? "No encontré nada que descargar."
                        : "No veo fotos grandes en esta página.");
                return;
            }
            if (busy) return;
            busy = true;
            setStatus("Descargando " + urls.size() + " foto(s)…");
            exec.execute(() -> {
                int n = 0;
                String err = null;
                long ts = System.currentTimeMillis();
                for (int i = 0; i < urls.size(); i++) {
                    try {
                        String u = urls.get(i);
                        String lower = u.toLowerCase();
                        String mime = lower.contains(".webp") ? "image/webp"
                                : lower.contains(".png") ? "image/png" : "image/jpeg";
                        String ext = lower.contains(".webp") ? "webp"
                                : lower.contains(".png") ? "png" : "jpg";
                        File f = new File(getCacheDir(), "p" + i + ".img");
                        fetch(u, f);
                        String name = "ig_" + ts + "_" + (i + 1) + "." + ext;
                        Uri uri = saveToGallery(f, name, mime, false);
                        addHistory(uri, name, mime, false);
                        n++;
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                final int fn = n;
                final String ferr = err;
                runOnUiThread(() -> {
                    busy = false;
                    String m = fn > 0
                            ? "Guardadas " + fn + " foto(s) en Imágenes/Instagram"
                            : "Error: " + ferr;
                    setStatus(m);
                    Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    if (fn == 0) showFab("↻  Reintentar", () -> downloadPhotos(false));
                });
            });
        });
    }

    private List<String> parseUrls(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        try {
            Object o = new JSONTokener(value).nextValue();
            if (o instanceof String) {
                JSONArray a = new JSONArray((String) o);
                for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ red, ficheros, mux

    private void fetch(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.instagram.com/");
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        try (InputStream is = c.getInputStream(); OutputStream os = new FileOutputStream(dest)) {
            copy(is, os);
        } finally {
            c.disconnect();
        }
    }

    private void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name);
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            copy(is, bo);
            return bo.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private Uri saveToGallery(File src, String name, String mime, boolean video) throws IOException {
        ContentResolver r = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Instagram");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri col = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = r.insert(col, cv);
        if (uri == null) throw new IOException("No se pudo crear el archivo");
        try (OutputStream os = r.openOutputStream(uri); InputStream is = new FileInputStream(src)) {
            if (os == null) throw new IOException("No se pudo escribir el archivo");
            copy(is, os);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        r.update(uri, done, null, null);
        return uri;
    }

    private boolean hasAudio(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            return selectTrack(ex, "audio/") >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            ex.release();
        }
    }

    private boolean hasVideo(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            return selectTrack(ex, "video/") >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            ex.release();
        }
    }

    private int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Une un mp4 sin audio con un mp4 solo de audio. */
    private void mux(File video, File audio, File out) throws IOException {
        if (out.exists()) out.delete();
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vt = selectTrack(ve, "video/");
            int at = selectTrack(ae, "audio/");
            if (vt < 0 || at < 0) throw new IOException("Faltan pistas");
            ve.selectTrack(vt);
            ae.selectTrack(at);

            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(video.getAbsolutePath());
                String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) muxer.setOrientationHint(Integer.parseInt(rot));
            } catch (Exception ignored) {
            } finally {
                r.release();
            }

            int outV = muxer.addTrack(ve.getTrackFormat(vt));
            int outA = muxer.addTrack(ae.getTrackFormat(at));
            muxer.start();
            copySamples(ve, muxer, outV);
            copySamples(ae, muxer, outA);
            muxer.stop();
        } finally {
            ve.release();
            ae.release();
            muxer.release();
        }
    }

    private void copySamples(MediaExtractor ex, MediaMuxer muxer, int track) {
        ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buf.clear();
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(track, buf, info);
            ex.advance();
        }
    }

    // ------------------------------------------------------------------ historial

    private void addHistory(Uri uri, String name, String mime, boolean video) {
        try {
            final JSONObject o = new JSONObject();
            o.put("uri", uri.toString());
            o.put("name", name);
            o.put("mime", mime);
            o.put("video", video);
            o.put("time", System.currentTimeMillis());
            runOnUiThread(() -> {
                hist.add(0, o);
                saveHistory();
                refreshHist();
            });
        } catch (Exception ignored) {
        }
    }

    private void loadHistory() {
        try {
            String s = getSharedPreferences("igdl", MODE_PRIVATE).getString("hist", "[]");
            JSONArray ja = new JSONArray(s);
            for (int i = 0; i < ja.length(); i++) hist.add(ja.getJSONObject(i));
        } catch (Exception ignored) {
        }
        refreshHist();
    }

    private void saveHistory() {
        JSONArray ja = new JSONArray();
        for (JSONObject o : hist) ja.put(o);
        getSharedPreferences("igdl", MODE_PRIVATE).edit().putString("hist", ja.toString()).apply();
    }

    private void refreshHist() {
        histLabels.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (JSONObject o : hist) {
            String icon = o.optBoolean("video") ? "🎬 " : "🖼 ";
            histLabels.add(icon + o.optString("name") + "\n"
                    + fmt.format(new Date(o.optLong("time"))));
        }
        histAdapter.notifyDataSetChanged();
        emptyView.setVisibility(historyShown && hist.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openItem(int pos) {
        if (pos < 0 || pos >= hist.size()) return;
        JSONObject o = hist.get(pos);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(o.optString("uri")), o.optString("mime"));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir (¿lo borraste?)", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(int pos) {
        if (pos < 0 || pos >= hist.size()) return;
        final JSONObject o = hist.get(pos);
        new AlertDialog.Builder(this)
                .setMessage("¿Borrar " + o.optString("name") + " del móvil?")
                .setPositiveButton("Borrar", (d, w) -> {
                    try {
                        getContentResolver().delete(Uri.parse(o.optString("uri")), null, null);
                    } catch (Exception ignored) {
                    }
                    hist.remove(o);
                    saveHistory();
                    refreshHist();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
