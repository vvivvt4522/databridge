package com.yesnoi.databridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE_PERM = 1002;
    private static final int REQ_NATIVE_MEDIA = 2001;
    private static final int REQ_NATIVE_FILE = 2002;
    private static final long AUTO_DISCOVER_TIMEOUT = 7000;

    private WebView webView;
    private LinearLayout serverList;
    private TextView statusText;
    private FrameLayout settingsPanel;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;
    private NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String serverUrl = "";
    private String lastLoadedUrl = "";
    private volatile boolean autoConnecting = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    /* ================= 自动连接 ================= */
    private void autoConnect() {
        if (autoConnecting) return;
        autoConnecting = true;
        new Thread(() -> {
            String saved = prefs.getString("server_url", "");
            boolean savedOk = !saved.isEmpty() && pingServer(saved);
            if (savedOk && saved.equals(lastLoadedUrl)) {
                autoConnecting = false;
                return;
            }
            if (savedOk) {
                main.post(() -> { autoConnecting = false; loadApp(saved); });
                return;
            }
            main.post(() -> {
                statusText.setText("正在自动搜索 PC…");
                serverList.removeAllViews();
                settingsPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                startDiscovery(true);
                main.postDelayed(() -> {
                    if (autoConnecting) {
                        autoConnecting = false;
                        stopDiscoverySafe();
                        showServerSelect();
                    }
                }, AUTO_DISCOVER_TIMEOUT);
            });
        }).start();
    }

    private boolean pingServer(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
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
                        String saved = prefs.getString("server_url", "");
                        boolean alive = !saved.isEmpty() && pingServer(saved);
                        if (!alive) autoConnect();
                    }, 2000);
                }
            });
        } catch (Exception ignored) {}
    }

    /* ================= UI ================= */
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        settingsPanel = new FrameLayout(this);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFFF4F6FB);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(40), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("连接服务器");
        title.setTextSize(20); title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);

        statusText = new TextView(this);
        statusText.setText("正在搜索同一 WiFi 下的 PC…");
        statusText.setPadding(0, dp(14), 0, dp(14));
        box.addView(statusText);

        serverList = new LinearLayout(this);
        serverList.setOrientation(LinearLayout.VERTICAL);
        box.addView(serverList);

        Button manual = new Button(this);
        manual.setText("手动输入地址");
        manual.setOnClickListener(v -> manualInput());
        box.addView(manual);

        TextView tip = new TextView(this);
        tip.setText("提示：手机与电脑需在同一 WiFi，且 PC 端已启动并放行防火墙。");
        tip.setTextSize(12); tip.setTextColor(0xFF7A839E);
        tip.setPadding(0, dp(10), 0, 0);
        box.addView(tip);

        sc.addView(box);
        settingsPanel.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setAlpha(0.55f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        glp.setMargins(0, dp(8), dp(8), 0);
        gear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("要重新选择服务器吗？")
                .setPositiveButton("是", (d, w) -> { autoConnecting = false; showServerSelect(); })
                .setNegativeButton("取消", null).show());
        root.addView(gear, glp);

        setContentView(root);
    }

    /* ================= 服务器选择 ================= */
    private void showServerSelect() {
        serverList.removeAllViews();
        statusText.setText("正在搜索同一 WiFi 下的 PC…");
        settingsPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        startDiscovery(false);
    }

    private void addServerButton(String name, String url) {
        if (serverList.findViewWithTag(url) != null) return;
        Button b = new Button(this);
        b.setText(name);
        b.setTag(url);
        b.setOnClickListener(v -> loadApp(url));
        serverList.addView(b);
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
                            final String label = name.replace("DataBridge-", "PC：");
                            main.post(() -> {
                                if (autoConnecting) {
                                    autoConnecting = false;
                                    loadApp(url);
                                } else {
                                    addServerButton(label, url);
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
        settingsPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        stopDiscoverySafe();
        webView.loadUrl(url);
    }

    /* ================= WebView 文件选择（无原生桥时的兜底） ================= */
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

    /* ================= 原生选择器 + 原生上传（主要通道） ================= */
    private boolean nativePickMediaOnly = false;

    private void launchNativePick(boolean mediaOnly) {
        try {
            nativePickMediaOnly = mediaOnly;
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
                    long size = 0;
                    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int si = c.getColumnIndex(OpenableColumns.SIZE);
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                        }
                    } catch (Exception ignored) {}

                    String boundary = "----DataBridge" + System.currentTimeMillis();
                    conn = (HttpURLConnection) new URL(base).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    if (size > 0) conn.setFixedLengthStreamingMode(
                            (int) Math.min(size + 2048 + name.getBytes(StandardCharsets.UTF_8).length, Integer.MAX_VALUE));
                    else conn.setChunkedStreamingMode(65536);

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
                    try { conn.getInputStream().close(); } catch (Exception ignored) {}
                    success = code == 200;
                    if (!success) err = "HTTP " + code;
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
                        Toast.LENGTH_SHORT).show());
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

        /* 原生选择器 + 原生上传（主要发送通道，不受 WebView 限制） */
        @JavascriptInterface
        public void sendFiles(boolean mediaOnly) {
            main.post(() -> launchNativePick(mediaOnly));
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
