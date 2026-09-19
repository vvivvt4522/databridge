package com.yesnoi.databridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE_PERM = 1002;
    private static final int REQ_NATIVE_MEDIA = 2001;
    private static final int REQ_NATIVE_FILE = 2002;
    private static final long AUTO_DISCOVER_TIMEOUT = 6500;

    private static final int COLOR_BRAND = 0xFF2B6CFF;
    private static final int COLOR_BRAND_DARK = 0xFF1E46B8;
    private static final int COLOR_PANEL = 0xFFF4F6FB;

    private WebView webView;
    private LinearLayout splash;
    private TextView splashStatus;
    private LinearLayout serverList;
    private TextView statusText;
    private FrameLayout settingsPanel;
    private TextView gear;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;
    private NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String serverUrl = "";
    private String lastLoadedUrl = "";
    private volatile boolean autoConnecting = false;
    private volatile boolean discoverySilent = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.setProperty("http.keepAlive", "false");
        prefs = getPreferences(Context.MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "");

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new Bridge(), "NativeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri server = Uri.parse(serverUrl);
                Uri target = req.getUrl();
                if (server.getHost() != null && !server.getHost().equals(target.getHost())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, target)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (settingsPanel.getVisibility() != View.VISIBLE) hideSplash();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    showSplash("连接已断开，正在重连…");
                    main.postDelayed(() -> { if (!autoConnecting) autoConnect(); }, 2500);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(buildPickIntent(params), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        webView.setDownloadListener((url, ua, contentDisposition, mime, size) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });

        buildUi();

        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        }
        registerNetworkCallback();
        autoConnect();
    }

    /* ================= 自动连接：ping 与发现并行，谁快用谁 ================= */
    private void autoConnect() {
        if (autoConnecting) return;
        autoConnecting = true;
        discoverySilent = true;
        showSplash("正在自动连接…");
        new Thread(() -> {
            String saved = prefs.getString("server_url", "");
            if (!saved.isEmpty() && pingServer(saved)) {
                main.post(() -> { if (autoConnecting) { autoConnecting = false; loadApp(saved); } });
            }
        }).start();
        startDiscovery(true);
        main.postDelayed(() -> {
            if (autoConnecting) {
                autoConnecting = false;
                stopDiscoverySafe();
                showServerSelect();
            }
        }, AUTO_DISCOVER_TIMEOUT);
    }

    private boolean pingServer(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            try { c.getInputStream().close(); } catch (Exception ignored) {}
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    main.postDelayed(() -> {
                        if (autoConnecting) return;
                        String saved = prefs.getString("server_url", "");
                        boolean alive = !saved.isEmpty() && pingServer(saved);
                        if (!alive) autoConnect();
                    }, 2000);
                }
            });
        } catch (Exception ignored) {}
    }

    /* ================= 界面骨架 ================= */
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable brandGradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_BRAND, 0xFF6A3CFF});
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_PANEL);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* ---- 启动页（替代白屏） ---- */
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackground(brandGradient());
        splash.setClickable(true);

        TextView logo = new TextView(this);
        logo.setText("⇄");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(40);
        logo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(84), dp(84));
        logoLp.setMargins(0, 0, 0, dp(18));
        logo.setBackground(rounded(0x33FFFFFF, 24));
        splash.addView(logo, logoLp);

        TextView brand = new TextView(this);
        brand.setText("三端互通");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(24);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        splash.addView(brand);

        splashStatus = new TextView(this);
        splashStatus.setText("正在启动…");
        splashStatus.setTextColor(0xFFD8E2FF);
        splashStatus.setTextSize(13);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(0, dp(10), 0, dp(22));
        splash.addView(splashStatus, stLp);

        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        splash.addView(bar, new LinearLayout.LayoutParams(dp(32), dp(32)));

        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* ---- 服务器选择页 ---- */
        settingsPanel = new FrameLayout(this);
        settingsPanel.setBackgroundColor(COLOR_PANEL);
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.addView(buildSettingsContent(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* ---- 右上角连接按钮（52dp 圆形） ---- */
        gear = new TextView(this);
        gear.setText("⇄");
        gear.setTextColor(Color.WHITE);
        gear.setTextSize(20);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(rounded(0x66000000, 26));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.END);
        glp.setMargins(0, dp(14), dp(14), 0);
        gear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("要重新选择连接的 PC 吗？")
                .setPositiveButton("是", (d, w) -> { autoConnecting = false; stopDiscoverySafe(); showServerSelect(); })
                .setNegativeButton("取消", null).show());
        root.addView(gear, glp);

        setContentView(root);
    }

    /* ================= 服务器选择页 ================= */
    private View buildSettingsContent() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(COLOR_PANEL);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        /* 品牌头部 */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(brandGradient());
        header.setPadding(dp(24), dp(40), dp(24), dp(48));
        TextView hTitle = new TextView(this);
        hTitle.setText("三端互通");
        hTitle.setTextColor(Color.WHITE);
        hTitle.setTextSize(24);
        hTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(hTitle);
        TextView hSub = new TextView(this);
        hSub.setText("连接到局域网中的 PC");
        hSub.setTextColor(0xFFD8E2FF);
        hSub.setTextSize(13);
        hSub.setPadding(0, dp(6), 0, 0);
        header.addView(hSub);
        box.addView(header);

        /* 状态卡片（上浮压住头部） */
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackground(rounded(Color.WHITE, 14));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.setMargins(dp(16), dp(-26), dp(16), dp(18));
        statusCard.setElevation(dp(3));
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView sIcon = new TextView(this);
        sIcon.setText("🔍");
        sIcon.setTextSize(20);
        statusCard.addView(sIcon);
        LinearLayout sCol = new LinearLayout(this);
        sCol.setOrientation(LinearLayout.VERTICAL);
        sCol.setPadding(dp(12), 0, 0, 0);
        statusText = new TextView(this);
        statusText.setText("正在搜索同一 WiFi 下的 PC…");
        statusText.setTextColor(0xFF1C2333);
        statusText.setTextSize(15);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        sCol.addView(statusText);
        TextView sSub = new TextView(this);
        sSub.setText("确保 PC 端已启动，且手机与电脑在同一 WiFi");
        sSub.setTextColor(0xFF7A839E);
        sSub.setTextSize(11);
        sSub.setPadding(0, dp(2), 0, 0);
        sCol.addView(sSub);
        statusCard.addView(sCol);
        box.addView(statusCard);

        /* 发现到的 PC 列表 */
        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(dp(16), 0, dp(16), 0);
        serverList = listWrap;
        box.addView(serverList);

        /* 手动输入按钮（描边样式） */
        TextView manual = new TextView(this);
        manual.setText("＋ 手动输入地址");
        manual.setTextColor(COLOR_BRAND);
        manual.setTextSize(15);
        manual.setTypeface(null, android.graphics.Typeface.BOLD);
        manual.setGravity(Gravity.CENTER);
        manual.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.WHITE);
        outline.setCornerRadius(dp(12));
        outline.setStroke(dp(1), 0xFFB9C8F0);
        manual.setBackground(outline);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(16), dp(14), dp(16), 0);
        manual.setOnClickListener(v -> manualInput());
        box.addView(manual, mlp);

        TextView tip = new TextView(this);
        tip.setText("连接一次后会自动记住，之后打开 App 将自动连接。搜不到时请检查 PC 端是否启动、防火墙是否放行。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF7A839E);
        tip.setPadding(dp(18), dp(14), dp(18), dp(20));
        tip.setLineSpacing(0, 1.3f);
        box.addView(tip);

        sc.addView(box);
        return sc;
    }

    private void showSplash(String msg) {
        splashStatus.setText(msg);
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.GONE);
        gear.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void hideSplash() {
        splash.animate().alpha(0f).setDuration(180).withEndAction(() -> splash.setVisibility(View.GONE)).start();
        gear.setVisibility(View.VISIBLE);
    }

    /* ================= 服务器选择 ================= */
    private void showServerSelect() {
        discoverySilent = false;
        serverList.removeAllViews();
        statusText.setText("正在搜索同一 WiFi 下的 PC…");
        settingsPanel.setVisibility(View.VISIBLE);
        splash.setVisibility(View.GONE);
        gear.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        startDiscovery(false);
    }

    private void addServerCard(String name, String url) {
        if (serverList.findViewWithTag(url) != null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(Color.WHITE, 12));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(10));
        card.setElevation(dp(2));

        TextView icon = new TextView(this);
        icon.setText("🖥️");
        icon.setTextSize(18);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xFFEAF0FF, 10));
        card.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(0xFF1C2333);
        n.setTextSize(15);
        n.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(n);
        TextView sub = new TextView(this);
        sub.setText("点击连接");
        sub.setTextColor(0xFF7A839E);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFFB6BECE);
        arrow.setTextSize(20);
        card.addView(arrow);

        card.setTag(url);
        card.setOnClickListener(v -> loadApp(url));
        serverList.addView(card, clp);
    }

    private void startDiscovery(boolean silent) {
        try {
            if (nsd == null) nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onStartDiscoveryFailed(String t, int e) {
                    if (!silent) statusText.setText("搜索失败，请手动输入地址");
                }
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onServiceLost(NsdServiceInfo i) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    String name = info.getServiceName();
                    if (name == null || !name.startsWith("DataBridge")) return;
                    try { nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) {}
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress host = i.getHost();
                            int port = i.getPort();
                            String token = "";
                            Map<String, byte[]> attrs = i.getAttributes();
                            if (attrs != null && attrs.get("token") != null) {
                                token = new String(attrs.get("token"), StandardCharsets.UTF_8);
                            }
                            if (host == null || token.isEmpty()) return;
                            final String url = "http://" + host.getHostAddress() + ":" + port + "/?t=" + token;
                            final String label = name.replace("DataBridge-", "");
                            main.post(() -> {
                                if (discoverySilent) {
                                    if (autoConnecting) { autoConnecting = false; loadApp(url); }
                                } else {
                                    addServerCard(label, url);
                                }
                            });
                        }
                    }); } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            if (!silent) statusText.setText("此设备不支持自动发现，请手动输入地址");
        }
    }

    private void stopDiscoverySafe() {
        try { if (nsd != null) nsd.stopServiceDiscovery(new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo i) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}
        }); } catch (Exception ignored) {}
    }

    private void manualInput() {
        final EditText input = new EditText(this);
        input.setHint("http://192.168.x.x:8322/?t=xxxx");
        input.setText(serverUrl.isEmpty() ? "" : serverUrl);
        new AlertDialog.Builder(this)
                .setTitle("手动输入 PC 端显示的完整地址")
                .setView(input)
                .setPositiveButton("连接", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (!u.isEmpty()) {
                        if (!u.startsWith("http")) u = "http://" + u;
                        loadApp(u);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void loadApp(String url) {
        serverUrl = url;
        lastLoadedUrl = url;
        prefs.edit().putString("server_url", url).apply();
        stopDiscoverySafe();
        settingsPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        showSplash("连接成功，加载页面…");
        webView.loadUrl(url);
    }

    /* ================= WebView 文件选择（兜底） ================= */
    private Intent buildPickIntent(WebChromeClient.FileChooserParams params) {
        String[] types = params.getAcceptTypes();
        boolean mediaOnly = types != null && types.length > 0;
        if (types != null) {
            for (String t : types) {
                if (t == null || t.isEmpty()) continue;
                if (!(t.startsWith("image/") || t.startsWith("video/"))) { mediaOnly = false; break; }
            }
        }
        if (mediaOnly && Build.VERSION.SDK_INT >= 33) {
            try {
                Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                i.setType("*/*");
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
                return i;
            } catch (Exception ignored) {}
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (mediaOnly) {
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        } else {
            i.setType("*/*");
        }
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return Intent.createChooser(i, mediaOnly ? "选择照片/视频" : "选择要发送的文件");
    }

    /* ================= 原生选择器 + 原生上传 ================= */
    private void launchNativePick(boolean mediaOnly) {
        try {
            Intent i;
            if (mediaOnly && Build.VERSION.SDK_INT >= 33) {
                i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                i.setType("*/*");
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
            } else if (mediaOnly) {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i = Intent.createChooser(i, "选择照片/视频");
            } else {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i = Intent.createChooser(i, "选择要发送的文件");
            }
            startActivityForResult(i, mediaOnly ? REQ_NATIVE_MEDIA : REQ_NATIVE_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private ArrayList<Uri> urisFrom(Intent data) {
        ArrayList<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    private String serverBase() {
        try {
            Uri u = Uri.parse(prefs.getString("server_url", ""));
            String token = u.getQueryParameter("t");
            if (u.getScheme() == null || token == null) return null;
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
                    + "/api/file?t=" + token;
        } catch (Exception e) { return null; }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {}
        return "file_" + System.currentTimeMillis();
    }

    private void uploadNative(final ArrayList<Uri> uris) {
        final String base = serverBase();
        if (base == null) {
            Toast.makeText(this, "尚未连接服务器，无法发送", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始发送 " + uris.size() + " 个文件…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            for (Uri uri : uris) {
                String name = queryName(uri);
                String mime = getContentResolver().getType(uri);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                boolean success = false;
                String err = "";
                HttpURLConnection conn = null;
                try {
                    String boundary = "----DataBridge" + System.currentTimeMillis();
                    conn = (HttpURLConnection) new URL(base).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Connection", "close");
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    conn.setChunkedStreamingMode(65536);

                    OutputStream out = conn.getOutputStream();
                    out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"from\"\r\n\r\n"
                            + Build.MODEL + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"kind\"\r\n\r\nandroid\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "_") + "\"\r\n"
                            + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("无法读取所选内容");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.close();

                    int code = conn.getResponseCode();
                    success = code == 200;
                    if (!success) {
                        err = "HTTP " + code;
                        try {
                            InputStream es = conn.getErrorStream();
                            if (es != null) {
                                byte[] b2 = new byte[300];
                                int n2 = es.read(b2);
                                if (n2 > 0) err += " " + new String(b2, 0, n2, StandardCharsets.UTF_8);
                                es.close();
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                } finally {
                    try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
                }
                final boolean okFlag = success;
                final String errStr = err;
                final String nameStr = name;
                main.post(() -> Toast.makeText(MainActivity.this,
                        okFlag ? "已发送 " + nameStr : "发送失败 " + nameStr + "：" + errStr,
                        Toast.LENGTH_LONG).show());
                if (success) ok++;
            }
            final int okCount = ok;
            final int total = uris.size();
            main.postDelayed(() -> Toast.makeText(MainActivity.this,
                    okCount == total ? "全部发送完成（" + okCount + " 个）" : "完成：成功 " + okCount + "/" + total,
                    Toast.LENGTH_SHORT).show(), 300);
        }).start();
    }

    /* ================= 原生桥 ================= */
    private class Bridge {
        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                    return cd.getItemAt(0).getText().toString();
                }
            } catch (Exception ignored) {}
            return "";
        }

        @JavascriptInterface
        public boolean setClipboard(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("text", text == null ? "" : text));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void sendFiles(boolean mediaOnly) {
            main.post(() -> launchNativePick(mediaOnly));
        }

        @JavascriptInterface
        public void openSettings() {
            main.post(() -> {
                autoConnecting = false;
                stopDiscoverySafe();
                showServerSelect();
            });
        }

        /* 流式下载保存：任意大小不占内存 */
        @JavascriptInterface
        public boolean saveFromUrl(String url, String name, String mime) {
            String safe = sanitize(name);
            boolean isImage = mime != null && mime.startsWith("image/");
            boolean isVideo = mime != null && mime.startsWith("video/");
            Uri mediaUri = null;
            File legacy = null;
            OutputStream os = null;
            HttpURLConnection conn = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                    Uri collection = isImage ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    mediaUri = getContentResolver().insert(collection, cv);
                    if (mediaUri == null) throw new Exception("insert failed");
                    os = getContentResolver().openOutputStream(mediaUri);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            isImage ? Environment.DIRECTORY_PICTURES
                            : isVideo ? Environment.DIRECTORY_MOVIES
                            : Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    legacy = new File(dir, System.currentTimeMillis() + "_" + safe);
                    os = new FileOutputStream(legacy);
                }

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Connection", "close");
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
                in.close();
                if (legacy != null) {
                    try {
                        android.media.MediaScannerConnection.scanFile(MainActivity.this,
                                new String[]{legacy.getAbsolutePath()}, null, null);
                    } catch (Exception ignored) {}
                }
                main.post(() -> Toast.makeText(MainActivity.this,
                        "已保存：" + (isImage || isVideo ? "相册" : "下载目录") + " " + safe,
                        Toast.LENGTH_SHORT).show());
                return true;
            } catch (Exception e) {
                if (mediaUri != null) {
                    try { getContentResolver().delete(mediaUri, null, null); } catch (Exception ignored) {}
                }
                if (legacy != null) legacy.delete();
                main.post(() -> Toast.makeText(MainActivity.this,
                        "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                return false;
            } finally {
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        /* 兼容保留：base64 保存小文件 */
        @JavascriptInterface
        public boolean saveFile(String name, String b64, String mime) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = sanitize(name);
                boolean isImage = mime != null && mime.startsWith("image/");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                    Uri collection = isImage ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri uri = getContentResolver().insert(collection, cv);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return false;
                        os.write(data);
                    }
                    return true;
                }
                File dir = Environment.getExternalStoragePublicDirectory(
                        isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(new File(dir, safe))) { fos.write(data); }
                return true;
            } catch (Exception e) { return false; }
        }
    }

    private String sanitize(String name) {
        return (name == null || name.isEmpty() ? "file" : name)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /* ================= 生命周期 ================= */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_NATIVE_MEDIA || requestCode == REQ_NATIVE_FILE) {
            ArrayList<Uri> uris = urisFrom(data);
            if (!uris.isEmpty()) uploadNative(uris);
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel.getVisibility() == View.VISIBLE && !serverUrl.isEmpty()) {
            settingsPanel.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            gear.setVisibility(View.VISIBLE);
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        stopDiscoverySafe();
        super.onDestroy();
    }
}
