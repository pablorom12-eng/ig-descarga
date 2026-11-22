package com.pablo.ytdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
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
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YT Descargas: pega un enlace de YouTube, elige calidad de vídeo o solo audio y descarga.
 */
public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final int BG = 0xFF0F0F10;
    private static final int CARD = 0xFF1C1C1F;
    private static final int CHIP = 0xFF2A2A2E;
    private static final int ACCENT = 0xFFFF4D4D;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFF9A9AA0;

    private static final String[] LEVELS = {"hd1080", "hd720", "large", "medium", "small", "tiny"};
    private static final String[] LABELS = {"1080p  Full HD", "720p  HD", "480p", "360p", "240p", "144p"};
    private static final int[] AUDIO_ITAGS = {141, 140, 139};

    private interface UrlCb {
        void done(String url);
    }

    private interface Progress {
        void add(long n);
    }

    private WebView web;
    private EditText urlBox;
    private TextView titleView;
    private TextView authorView;
    private TextView status;
    private TextView overlay;
    private TextView emptyView;
    private Button dlBtn;
    private Button histBtn;
    private ProgressBar bar;
    private LinearLayout videoRow;
    private LinearLayout audioRow;
    private ScrollView scroll;
    private ListView histList;

    private final Map<Integer, String> urls = new HashMap<>();
    private final Map<String, TextView> chips = new LinkedHashMap<>();
    private String selKey = "hd720";
    private String videoId;
    private String videoTitle = "";
    private boolean busy;
    private boolean levelsReady;
    private boolean historyShown;
    private int pollCount;
    private long lastUi;

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
        setupWebView();
        loadHistory();
        handleIntent(getIntent());
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
        if (historyShown) showHistory(false);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ interfaz

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(SUB);
        t.setTextSize(13);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // cabecera
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(12), dp(4));
        TextView title = new TextView(this);
        title.setText("▶  YT Descargas");
        title.setTextColor(TEXT);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        histBtn = new Button(this);
        histBtn.setText("Historial");
        histBtn.setAllCaps(false);
        histBtn.setTextSize(13);
        histBtn.setTextColor(TEXT);
        histBtn.setBackground(round(CHIP, 20));
        header.addView(histBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        root.addView(header);

        FrameLayout body = new FrameLayout(this);

        // contenido con scroll
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(col);

        // tarjeta de enlace
        LinearLayout urlCard = new LinearLayout(this);
        urlCard.setOrientation(LinearLayout.HORIZONTAL);
        urlCard.setGravity(Gravity.CENTER_VERTICAL);
        urlCard.setBackground(round(CARD, 16));
        urlCard.setPadding(dp(14), dp(4), dp(6), dp(4));
        urlBox = new EditText(this);
        urlBox.setHint("Pega un enlace de YouTube");
        urlBox.setHintTextColor(0xFF777777);
        urlBox.setTextColor(TEXT);
        urlBox.setTextSize(15);
        urlBox.setSingleLine(true);
        urlBox.setBackground(null);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            loadVideo();
            return true;
        });
        urlCard.addView(urlBox, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button paste = new Button(this);
        paste.setText("Pegar");
        paste.setAllCaps(false);
        paste.setTextColor(TEXT);
        paste.setBackground(round(ACCENT, 14));
        urlCard.addView(paste, new LinearLayout.LayoutParams(dp(84), dp(40)));
        col.addView(urlCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // tarjeta del reproductor (vista previa en directo)
        int w = getResources().getDisplayMetrics().widthPixels - dp(24);
        FrameLayout player = new FrameLayout(this);
        player.setBackgroundColor(0xFF000000);
        player.setClipToOutline(true);
        player.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        web = new WebView(this);
        player.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new TextView(this);
        overlay.setText("Pega un enlace de YouTube\ny elige la calidad");
        overlay.setTextColor(SUB);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(CARD);
        player.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(w, w * 9 / 16);
        plp.topMargin = dp(12);
        col.addView(player, plp);

        titleView = new TextView(this);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dp(4), dp(12), dp(4), 0);
        col.addView(titleView);
        authorView = new TextView(this);
        authorView.setTextColor(SUB);
        authorView.setTextSize(13);
        authorView.setPadding(dp(4), dp(2), dp(4), 0);
        col.addView(authorView);

        // calidades
        col.addView(label("🎬  VÍDEO CON SONIDO  ·  MP4"));
        HorizontalScrollView vs = new HorizontalScrollView(this);
        vs.setHorizontalScrollBarEnabled(false);
        videoRow = new LinearLayout(this);
        videoRow.setOrientation(LinearLayout.HORIZONTAL);
        vs.addView(videoRow);
        col.addView(vs);
        TextView hint = new TextView(this);
        hint.setText("Las calidades aparecen cuando carga el vídeo.");
        hint.setTextColor(0xFF6E6E74);
        hint.setTextSize(12);
        hint.setPadding(dp(4), dp(6), 0, 0);
        col.addView(hint);

        col.addView(label("🎧  SOLO AUDIO"));
        HorizontalScrollView as = new HorizontalScrollView(this);
        as.setHorizontalScrollBarEnabled(false);
        audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        as.addView(audioRow);
        col.addView(as);
        addChip(audioRow, "audio", "M4A  ·  mejor calidad");

        body.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // historial
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
        body.addView(histList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyView = new TextView(this);
        emptyView.setText("Aún no has descargado nada.");
        emptyView.setTextColor(SUB);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // barra inferior
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(12));
        bottom.setBackgroundColor(BG);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setVisibility(View.GONE);
        bottom.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        status = new TextView(this);
        status.setTextColor(SUB);
        status.setTextSize(13);
        status.setText("Pega un enlace para empezar.");
        status.setPadding(dp(2), dp(6), dp(2), dp(8));
        bottom.addView(status);
        dlBtn = new Button(this);
        dlBtn.setAllCaps(false);
        dlBtn.setTextColor(TEXT);
        dlBtn.setTextSize(16);
        dlBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        dlBtn.setBackground(round(ACCENT, 18));
        dlBtn.setEnabled(false);
        dlBtn.setAlpha(0.45f);
        dlBtn.setText("⬇  Descargar");
        bottom.addView(dlBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(bottom);

        setContentView(root);

        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null) {
                    urlBox.setText(extractUrl(t.toString()));
                    loadVideo();
                }
            }
        });
        dlBtn.setOnClickListener(v -> startDownload());
        histBtn.setOnClickListener(v -> showHistory(!historyShown));
        histList.setOnItemClickListener((parent, view, pos, id) -> openItem(pos));
        histList.setOnItemLongClickListener((parent, view, pos, id) -> {
            confirmDelete(pos);
            return true;
        });
    }

    private TextView addChip(LinearLayout row, final String key, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        row.addView(t, lp);
        chips.put(key, t);
        t.setOnClickListener(v -> {
            selKey = key;
            refreshChips();
        });
        styleChip(t, false);
        return t;
    }

    private void styleChip(TextView t, boolean selected) {
        t.setBackground(round(selected ? ACCENT : CHIP, 22));
        t.setTextColor(selected ? TEXT : 0xFFCCCCCC);
    }

    private void refreshChips() {
        for (Map.Entry<String, TextView> e : chips.entrySet()) {
            styleChip(e.getValue(), e.getKey().equals(selKey));
        }
        if ("audio".equals(selKey)) {
            dlBtn.setText("⬇  Descargar audio");
        } else {
            dlBtn.setText("⬇  Descargar " + labelFor(selKey).split(" ")[0]);
        }
    }

    private String labelFor(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return LABELS[i];
        return level;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void setBusy(boolean b) {
        busy = b;
        boolean enabled = !b && levelsReady;
        dlBtn.setEnabled(enabled);
        dlBtn.setAlpha(enabled ? 1f : 0.45f);
        bar.setVisibility(b ? View.VISIBLE : View.GONE);
        if (!b) bar.setProgress(0);
    }

    private void showHistory(boolean show) {
        historyShown = show;
        scroll.setVisibility(show ? View.GONE : View.VISIBLE);
        histList.setVisibility(show ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(show && hist.isEmpty() ? View.VISIBLE : View.GONE);
        histBtn.setText(show ? "Volver" : "Historial");
    }

    // ------------------------------------------------------------------ WebView (reproductor)

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, readAsset("codec.js"),
                    Collections.singleton("*"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(readAsset("codec.js"), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(readAsset("clean.js"), null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u.getHost() != null && u.getHost().endsWith("googlevideo.com")) {
                    considerYoutube(u);
                }
                return null;
            }
        });
    }

    /** Guarda la URL (sin rango) de cada stream mp4 que pide el reproductor, por itag. */
    private void considerYoutube(Uri u) {
        String path = u.getPath();
        if (path == null || !path.contains("videoplayback")) return;
        String mime = u.getQueryParameter("mime");
        String clen = u.getQueryParameter("clen");
        String itag = u.getQueryParameter("itag");
        if (mime == null || clen == null || itag == null) return;
        if (!(mime.equals("video/mp4") || mime.equals("audio/mp4"))) return;
        int tag;
        try {
            tag = Integer.parseInt(itag);
        } catch (NumberFormatException e) {
            return;
        }
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String k : u.getQueryParameterNames()) {
            if (k.equals("range") || k.equals("rn") || k.equals("rbuf")) continue;
            for (String val : u.getQueryParameters(k)) b.appendQueryParameter(k, val);
        }
        synchronized (urls) {
            urls.put(tag, b.build().toString());
        }
    }

    // ------------------------------------------------------------------ cargar vídeo

    private boolean handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                urlBox.setText(extractUrl(text));
                loadVideo();
                return true;
            }
        }
        return false;
    }

    private String extractUrl(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private String parseId(String url) {
        Matcher m = Pattern.compile(
                "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})")
                .matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private void loadVideo() {
        if (busy) return;
        String id = parseId(urlBox.getText().toString().trim());
        if (id == null) {
            setStatus("Ese enlace no parece de YouTube.");
            return;
        }
        showHistory(false);
        videoId = id;
        levelsReady = false;
        pollCount = 0;
        videoTitle = "";
        synchronized (urls) {
            urls.clear();
        }
        List<String> rm = new ArrayList<>();
        for (String k : chips.keySet()) if (!k.equals("audio")) rm.add(k);
        for (String k : rm) chips.remove(k);
        videoRow.removeAllViews();
        titleView.setText("Cargando vídeo…");
        authorView.setText("");
        overlay.setVisibility(View.GONE);
        setBusy(false);
        setStatus("Cargando…");
        web.loadUrl("https://m.youtube.com/watch?v=" + id);
        h.removeCallbacks(pollTask);
        h.postDelayed(pollTask, 2000);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (videoId == null || levelsReady) return;
            pollCount++;
            web.evaluateJavascript(readAsset("state.js"), value -> {
                JSONObject o = parseObj(value);
                if (o != null && o.optBoolean("ready")) {
                    if (o.optBoolean("ad")) {
                        setStatus("Esperando a que termine el anuncio…");
                    } else {
                        JSONArray lv = o.optJSONArray("levels");
                        if (lv != null && lv.length() > 0) {
                            onLevels(o, lv);
                            return;
                        }
                    }
                }
                if (pollCount > 45) {
                    titleView.setText("No se pudo cargar el vídeo");
                    setStatus("Puede ser privado, con edad restringida o pedir inicio de sesión.");
                    return;
                }
                h.postDelayed(pollTask, 1000);
            });
        }
    };

    private JSONObject parseObj(String value) {
        if (value == null) return null;
        try {
            Object o = new JSONTokener(value).nextValue();
            if (o instanceof String) return new JSONObject((String) o);
        } catch (Exception ignored) {
        }
        return null;
    }

    private void onLevels(JSONObject o, JSONArray lv) {
        levelsReady = true;
        videoTitle = o.optString("title");
        titleView.setText(videoTitle.isEmpty() ? "Vídeo de YouTube" : videoTitle);
        authorView.setText(o.optString("author"));

        List<String> present = new ArrayList<>();
        for (int i = 0; i < lv.length(); i++) present.add(lv.optString(i));
        boolean any = false;
        for (int i = 0; i < LEVELS.length; i++) {
            if (present.contains(LEVELS[i])) {
                addChip(videoRow, LEVELS[i], LABELS[i]);
                any = true;
            }
        }
        if (!any) addChip(videoRow, "medium", LABELS[3]);
        if (!"audio".equals(selKey) && !chips.containsKey(selKey)) {
            selKey = chips.containsKey("hd720") ? "hd720" : firstVideoKey();
        }
        refreshChips();
        setBusy(false);
        setStatus("Elige la calidad y pulsa Descargar.");
    }

    private String firstVideoKey() {
        for (String k : chips.keySet()) if (!k.equals("audio")) return k;
        return "audio";
    }

    // ------------------------------------------------------------------ descarga

    private int[] itagsFor(String level) {
        switch (level) {
            case "hd1080":
                return new int[]{137, 299};
            case "hd720":
                return new int[]{136, 298};
            case "large":
                return new int[]{135};
            case "medium":
                return new int[]{134, 18};
            case "small":
                return new int[]{133};
            default:
                return new int[]{160};
        }
    }

    private String find(int[] itags) {
        synchronized (urls) {
            for (int t : itags) {
                String u = urls.get(t);
                if (u != null) return u;
            }
        }
        return null;
    }

    private void waitFor(final int[] itags, long timeoutMs, final UrlCb cb) {
        final long end = SystemClock.uptimeMillis() + timeoutMs;
        new Runnable() {
            @Override
            public void run() {
                String u = find(itags);
                if (u != null) cb.done(u);
                else if (SystemClock.uptimeMillis() > end) cb.done(null);
                else h.postDelayed(this, 500);
            }
        }.run();
    }

    private void fail(String msg) {
        setBusy(false);
        setStatus(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void startDownload() {
        if (busy || !levelsReady) return;
        setBusy(true);
        final String key = selKey;
        final String title = videoTitle;
        if ("audio".equals(key)) {
            setStatus("Preparando el audio…");
            waitFor(AUDIO_ITAGS, 15000, a -> {
                if (a == null) {
                    fail("No se detectó el audio. Deja que el vídeo cargue unos segundos y reinténtalo.");
                } else {
                    runDownload(null, a, title, "audio");
                }
            });
        } else {
            setStatus("Preparando " + labelFor(key).split(" ")[0] + "…");
            web.evaluateJavascript(readAsset("setq.js").replace("__Q__", key), null);
            waitFor(itagsFor(key), 20000, v -> {
                if (v == null) {
                    fail("No se pudo obtener esa calidad. Prueba con otra o reinténtalo.");
                } else {
                    waitFor(AUDIO_ITAGS, 8000, a -> runDownload(v, a, title, key));
                }
            });
        }
    }

    private long clenOf(String url) {
        if (url == null) return 0;
        try {
            return Long.parseLong(Uri.parse(url).getQueryParameter("clen"));
        } catch (Exception e) {
            return 0;
        }
    }

    private void runDownload(final String vUrl, final String aUrl, final String title,
                             final String key) {
        exec.execute(() -> {
            String msg;
            boolean ok = false;
            try {
                final long total = Math.max(1, clenOf(vUrl) + clenOf(aUrl));
                final long[] done = {0};
                Progress pr = n -> {
                    done[0] += n;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastUi < 150 && done[0] < total) return;
                    lastUi = now;
                    final int p = (int) Math.min(1000, done[0] * 1000 / total);
                    runOnUiThread(() -> {
                        bar.setProgress(p);
                        setStatus("Descargando… " + (p / 10) + "%");
                    });
                };

                String base = safeName(title);
                if (base == null) base = "youtube";
                Uri uri;
                String name;
                String note = "";

                if (vUrl == null) {
                    File af = new File(getCacheDir(), "a_raw.mp4");
                    fetchChunked(aUrl, af, pr);
                    File out = af;
                    try {
                        ui("Guardando…");
                        File mf = new File(getCacheDir(), "audio_out.m4a");
                        muxSingle(af, mf);
                        out = mf;
                    } catch (Throwable e) {
                        out = af;
                    }
                    name = base + ".m4a";
                    uri = saveToGallery(out, name, "audio/mp4", 1);
                    addHistory(uri, name, "audio/mp4", 1);
                    msg = "Audio guardado en Música/YouTube";
                } else {
                    File vf = new File(getCacheDir(), "v_raw.mp4");
                    fetchChunked(vUrl, vf, pr);
                    File out = vf;
                    if (!hasTrack(vf, "audio/")) {
                        if (aUrl != null) {
                            File af = new File(getCacheDir(), "a_raw.mp4");
                            fetchChunked(aUrl, af, pr);
                            try {
                                ui("Uniendo vídeo y audio…");
                                File mf = new File(getCacheDir(), "merged.mp4");
                                mux(vf, af, mf);
                                out = mf;
                            } catch (Throwable e) {
                                note = " (sin audio: " + e.getMessage() + ")";
                            }
                        } else {
                            note = " (sin audio)";
                        }
                    }
                    name = base + " [" + labelFor(key).split(" ")[0] + "].mp4";
                    uri = saveToGallery(out, name, "video/mp4", 0);
                    addHistory(uri, name, "video/mp4", 0);
                    msg = "Vídeo guardado en Películas/YouTube" + note;
                }
                ok = true;
            } catch (Exception e) {
                msg = "Error: " + e.getMessage();
            }
            final String fmsg = msg;
            final boolean fok = ok;
            runOnUiThread(() -> {
                setBusy(false);
                if (fok) bar.setProgress(0);
                setStatus(fmsg);
                Toast.makeText(this, fmsg, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void ui(String s) {
        runOnUiThread(() -> setStatus(s));
    }

    private String safeName(String title) {
        if (title == null) return null;
        String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------------ red y ficheros

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.youtube.com/");
        c.setRequestProperty("Origin", "https://www.youtube.com");
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    /** YouTube limita las descargas largas: se pide a trozos de 8 MB. */
    private void fetchChunked(String url, File dest, Progress p) throws IOException {
        long total = clenOf(url);
        if (total <= 0) throw new IOException("no se conoce el tamaño");
        final long chunk = 8L * 1024 * 1024;
        byte[] buf = new byte[65536];
        try (OutputStream os = new FileOutputStream(dest)) {
            long pos = 0;
            while (pos < total) {
                long end = Math.min(pos + chunk, total) - 1;
                HttpURLConnection c = open(url + "&range=" + pos + "-" + end);
                try {
                    int code = c.getResponseCode();
                    if (code >= 400) throw new IOException("HTTP " + code);
                    try (InputStream is = c.getInputStream()) {
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            p.add(n);
                        }
                    }
                } finally {
                    c.disconnect();
                }
                pos = end + 1;
            }
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

    /** kind: 0 = vídeo (Películas/YouTube), 1 = audio (Música/YouTube). */
    private Uri saveToGallery(File src, String name, String mime, int kind) throws IOException {
        ContentResolver r = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (kind == 0 ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/YouTube");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri col = kind == 0
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
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

    // ------------------------------------------------------------------ mux

    private int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private boolean hasTrack(File f, String prefix) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            return selectTrack(ex, prefix) >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            ex.release();
        }
    }

    /** Deja un m4a normal (no fragmentado) a partir del audio de YouTube. */
    private void muxSingle(File src, File out) throws IOException {
        if (out.exists()) out.delete();
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(src.getAbsolutePath());
            int t = selectTrack(ex, "audio/");
            if (t < 0) throw new IOException("sin pista de audio");
            ex.selectTrack(t);
            int outT = muxer.addTrack(ex.getTrackFormat(t));
            muxer.start();
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
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
                muxer.writeSampleData(outT, buf, info);
                ex.advance();
            }
            muxer.stop();
        } finally {
            ex.release();
            muxer.release();
        }
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
            if (vt < 0 || at < 0) throw new IOException("faltan pistas");
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
            interleave(ve, outV, ae, outA, muxer);
            muxer.stop();
        } finally {
            ve.release();
            ae.release();
            muxer.release();
        }
    }

    private void interleave(MediaExtractor ve, int outV, MediaExtractor ae, int outA,
                            MediaMuxer muxer) {
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean vDone = false;
        boolean aDone = false;
        while (!vDone || !aDone) {
            long vt = vDone ? Long.MAX_VALUE : ve.getSampleTime();
            long at = aDone ? Long.MAX_VALUE : ae.getSampleTime();
            if (vt < 0) {
                vDone = true;
                continue;
            }
            if (at < 0) {
                aDone = true;
                continue;
            }
            boolean useV = !vDone && (aDone || vt <= at);
            MediaExtractor ex = useV ? ve : ae;
            int track = useV ? outV : outA;
            buf.clear();
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
                if (useV) vDone = true;
                else aDone = true;
                continue;
            }
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

    private void addHistory(Uri uri, String name, String mime, int kind) {
        try {
            final JSONObject o = new JSONObject();
            o.put("uri", uri.toString());
            o.put("name", name);
            o.put("mime", mime);
            o.put("kind", kind);
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
            String s = getSharedPreferences("ytdl", MODE_PRIVATE).getString("hist", "[]");
            JSONArray ja = new JSONArray(s);
            for (int i = 0; i < ja.length(); i++) hist.add(ja.getJSONObject(i));
        } catch (Exception ignored) {
        }
        refreshHist();
    }

    private void saveHistory() {
        JSONArray ja = new JSONArray();
        for (JSONObject o : hist) ja.put(o);
        getSharedPreferences("ytdl", MODE_PRIVATE).edit().putString("hist", ja.toString()).apply();
    }

    private void refreshHist() {
        histLabels.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (JSONObject o : hist) {
            String icon = o.optInt("kind") == 1 ? "🎧 " : "🎬 ";
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
