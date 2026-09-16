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
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE_PERM = 1002;

    private WebView webView;
    private LinearLayout serverList;      // 发现页动态容器
    private TextView statusText;
    private FrameLayout settingsPanel;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;
    private NsdManager nsd;
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String serverUrl = "";

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
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择要发送的文件"), REQ_FILE_CHOOSER);
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

        if (serverUrl.isEmpty()) showServerSelect();
        else webView.loadUrl(serverUrl);
    }

    /* ---------------- UI ---------------- */
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
        tip.setText("提示：确保手机与电脑在同一 WiFi，且 PC 端已启动并放行防火墙。");
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
                .setPositiveButton("是", (d, w) -> showServerSelect())
                .setNegativeButton("取消", null).show());
        root.addView(gear, glp);

        setContentView(root);
    }

    /* ---------------- 服务器选择（NSD 自动发现 + 手动） ---------------- */
    private void showServerSelect() {
        found.clear();
        serverList.removeAllViews();
        statusText.setText("正在搜索同一 WiFi 下的 PC…");
        settingsPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        startDiscovery();
        main.postDelayed(() -> {
            if (settingsPanel.getVisibility() == View.VISIBLE && found.isEmpty()) {
                statusText.setText("没搜到 PC。请确认 PC 端已启动、双方在同一 WiFi；也可手动输入。");
            }
        }, 8000);
    }

    private void addServerButton(String name, String url) {
        if (serverList.findViewWithTag(url) != null) return;
        Button b = new Button(this);
        b.setText(name);
        b.setTag(url);
        b.setOnClickListener(v -> loadApp(url));
        serverList.addView(b);
    }

    private void startDiscovery() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onStartDiscoveryFailed(String t, int e) {
                    statusText.setText("搜索失败，请手动输入地址");
                }
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onServiceLost(NsdServiceInfo i) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    String name = info.getServiceName();
                    if (name == null || !name.startsWith("DataBridge")) return;
                    runOnUiThread(() -> statusText.setText("发现 PC，正在连接…"));
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
                            main.post(() -> { found.put(label, url); addServerButton(label, url); });
                        }
                    }); } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            statusText.setText("此设备不支持自动发现，请手动输入地址");
        }
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
        prefs.edit().putString("server_url", url).apply();
        settingsPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        try { if (nsd != null) nsd.stopServiceDiscovery(new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo i) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}
        }); } catch (Exception ignored) {}
        webView.loadUrl(url);
    }

    /* ---------------- 原生桥 ---------------- */
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
        public boolean saveFile(String name, String b64, String mime) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                boolean isImage = mime != null && mime.startsWith("image/");
                String safe = (name == null || name.isEmpty() ? "file" : name)
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
                boolean ok;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                    Uri collection = isImage
                            ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri uri = getContentResolver().insert(collection, cv);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return false;
                        os.write(data);
                    }
                    ok = true;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, safe);
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
                    ok = true;
                }
                final boolean done = ok;
                main.post(() -> Toast.makeText(MainActivity.this,
                        done ? "已保存：" + (isImage ? "相册" : "下载目录") : "保存失败",
                        Toast.LENGTH_SHORT).show());
                return done;
            } catch (Exception e) {
                main.post(() -> Toast.makeText(MainActivity.this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                return false;
            }
        }
    }

    /* ---------------- 文件选择 / 返回键 ---------------- */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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
        try { if (nsd != null) nsd.stopServiceDiscovery(new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo i) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}
        }); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
