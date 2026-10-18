package com.aman.photosbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://" + "www.icloud.com" + "/photos/";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String ACTION_HOME = "com.aman.photosbridge.action.HOME";
    private static final String ACTION_CREATE = "com.aman.photosbridge.action.CREATE";

    private static final String PREFS = "settings";
    private static final String KEY_DESKTOP = "desktop_mode";
    private static final String KEY_LOCK = "app_lock";
    private static final long RELOCK_MS = 5 * 60 * 1000L;

    // Static holder for photos/videos handed to us via the share sheet.
    private static final List<Uri> sharedStash = new ArrayList<>();

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private boolean desktopMode = false;
    private boolean keepScreenOn = true;
    private Dialog popupDialog;
    private WebView popupView;
    private FrameLayout drawerRoot;
    private TextView desktopRow;
    private TextView keepScreenRow;
    private TextView lockRow;

    private SharedPreferences prefs;
    private boolean lockEnabled = false;
    private boolean locked = false;
    private boolean unlockedThisSession = false;
    private boolean wentBackground = false;
    private long backgroundedAtMs = 0L;
    private FrameLayout lockOverlay;
    private CancellationSignal cancelSignal;
    private String pendingStartUrl = null;
    private boolean startLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lockEnabled = prefs.getBoolean(KEY_LOCK, false);
        if (prefs.contains(KEY_DESKTOP)) {
            desktopMode = prefs.getBoolean(KEY_DESKTOP, false);
        } else {
            // First launch: tablets get the desktop layout by default.
            desktopMode = isTablet();
        }

        Intent intent = getIntent();
        if (intent != null && ACTION_CREATE.equals(intent.getAction())) {
            // Long-press shortcut: desktop mode for this launch only.
            desktopMode = true;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);
        content.addView(buildTopBar());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        content.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        buildDrawer(root);
        buildLockOverlay(root);

        setContentView(root);
        configureWebView(webView, true);

        // Work out where to start: an incoming link wins, otherwise the
        // standard Photos home. The cookie session keeps him logged in.
        String startUrl = HOME_URL;
        if (intent != null) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())
                    && intent.getData() != null && isIcloudHost(intent.getData())) {
                startUrl = intent.getData().toString();
            }
            stashSharedContent(intent);
        }

        if (lockEnabled) {
            // Defer loading until the lock is cleared in onResume.
            pendingStartUrl = startUrl;
        } else {
            unlockedThisSession = true;
            startLoaded = true;
            webView.loadUrl(startUrl);
        }
    }

    private boolean isTablet() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private boolean isIcloudHost(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost();
        return host.equals("icloud.com") || host.endsWith(".icloud.com");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null
                && isIcloudHost(intent.getData())) {
            String url = intent.getData().toString();
            if (locked) {
                pendingStartUrl = url;
            } else {
                webView.loadUrl(url);
            }
        } else if (Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            stashSharedContent(intent);
        } else if (ACTION_CREATE.equals(action)) {
            desktopMode = true;
            updateToggleLabels();
            webView.getSettings().setUserAgentString(DESKTOP_UA);
            if (locked) {
                pendingStartUrl = HOME_URL;
            } else {
                webView.loadUrl(HOME_URL);
            }
        } else if (ACTION_HOME.equals(action)) {
            if (locked) {
                pendingStartUrl = HOME_URL;
            } else {
                webView.loadUrl(HOME_URL);
            }
        }
    }

    // ---- Share-sheet stash ----

    private void stashSharedContent(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list =
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
            ClipData clip = intent.getClipData();
            if (uris.isEmpty() && clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            }
        }
        if (uris.isEmpty()) return;
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Most share grants aren't persistable; the live grant
                // still covers this session.
            }
            try {
                grantUriPermission(getPackageName(), u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        synchronized (sharedStash) {
            sharedStash.clear();
            sharedStash.addAll(uris);
        }
        Toast.makeText(this,
            uris.size() + " photo(s) received - open an album and tap the "
                + "upload button; they'll be attached automatically.",
            Toast.LENGTH_LONG).show();
    }

    private List<Uri> drainStash(boolean multiple) {
        synchronized (sharedStash) {
            if (sharedStash.isEmpty()) return null;
            List<Uri> out = new ArrayList<>();
            if (multiple) {
                out.addAll(sharedStash);
            } else {
                out.add(sharedStash.get(0));
            }
            sharedStash.clear();
            return out;
        }
    }

    private boolean stashHasItems() {
        synchronized (sharedStash) {
            return !sharedStash.isEmpty();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- Slim top bar + hamburger drawer ----

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(24, 24, 27));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        TextView burger = new TextView(this);
        burger.setText("☰");
        burger.setTextColor(Color.WHITE);
        burger.setTextSize(20);
        burger.setGravity(Gravity.CENTER);
        burger.setPadding(dp(14), 0, dp(12), 0);
        burger.setOnClickListener(v -> openDrawer());
        bar.addView(burger);

        TextView title = new TextView(this);
        title.setText("PhotosBridge");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        bar.addView(title);
        return bar;
    }

    private TextView makeRow(String label) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(Color.WHITE);
        row.setTextSize(16);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void buildDrawer(FrameLayout root) {
        drawerRoot = new FrameLayout(this);
        drawerRoot.setVisibility(View.GONE);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(140, 0, 0, 0));
        scrim.setOnClickListener(v -> closeDrawer());
        drawerRoot.addView(scrim, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(32, 32, 34));
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            dp(280), ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.LEFT;
        panel.setLayoutParams(panelParams);

        TextView header = new TextView(this);
        header.setText("PhotosBridge");
        header.setTextColor(Color.WHITE);
        header.setTextSize(18);
        header.setPadding(dp(18), dp(18), dp(18), dp(10));
        panel.addView(header);

        TextView refreshRow = makeRow("Refresh");
        refreshRow.setOnClickListener(v -> {
            webView.reload();
            closeDrawer();
        });
        panel.addView(refreshRow);

        TextView homeRow = makeRow("Photos home");
        homeRow.setOnClickListener(v -> {
            webView.loadUrl(HOME_URL);
            closeDrawer();
        });
        panel.addView(homeRow);

        TextView goToHeader = new TextView(this);
        goToHeader.setText("Go to");
        goToHeader.setTextColor(Color.rgb(150, 150, 155));
        goToHeader.setTextSize(13);
        goToHeader.setPadding(dp(18), dp(16), dp(18), dp(2));
        panel.addView(goToHeader);

        TextView libraryRow = makeRow("Library home");
        libraryRow.setOnClickListener(v -> {
            webView.loadUrl(HOME_URL);
            closeDrawer();
        });
        panel.addView(libraryRow);

        TextView albumsRow = makeRow("Albums");
        albumsRow.setOnClickListener(v -> {
            webView.loadUrl(HOME_URL);
            closeDrawer();
        });
        panel.addView(albumsRow);

        TextView createRow = makeRow("Create / manage albums (desktop layout)");
        createRow.setOnClickListener(v -> {
            if (!desktopMode) {
                desktopMode = true;
                persistDesktopMode();
                updateToggleLabels();
                webView.getSettings().setUserAgentString(DESKTOP_UA);
            }
            webView.loadUrl(HOME_URL);
            closeDrawer();
        });
        panel.addView(createRow);

        desktopRow = makeRow("");
        desktopRow.setOnClickListener(v -> toggleDesktop());
        panel.addView(desktopRow);

        keepScreenRow = makeRow("");
        keepScreenRow.setOnClickListener(v -> toggleKeepScreenOn());
        panel.addView(keepScreenRow);

        lockRow = makeRow("");
        lockRow.setOnClickListener(v -> toggleLock());
        panel.addView(lockRow);

        TextView chromeRow = makeRow("Open in Chrome");
        chromeRow.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(webView.getUrl())));
            } catch (Exception ignored) {}
            closeDrawer();
        });
        panel.addView(chromeRow);

        TextView signOutRow = makeRow("Sign out / clear session");
        signOutRow.setOnClickListener(v -> confirmSignOut());
        panel.addView(signOutRow);

        updateToggleLabels();
        drawerRoot.addView(panel);
        root.addView(drawerRoot, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openDrawer() {
        if (drawerRoot != null) drawerRoot.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        if (drawerRoot != null) drawerRoot.setVisibility(View.GONE);
    }

    private boolean isDrawerOpen() {
        return drawerRoot != null && drawerRoot.getVisibility() == View.VISIBLE;
    }

    private void updateToggleLabels() {
        if (desktopRow != null) {
            desktopRow.setText("Desktop site: " + (desktopMode ? "ON" : "OFF"));
        }
        if (keepScreenRow != null) {
            keepScreenRow.setText("Keep screen on: " + (keepScreenOn ? "ON" : "OFF"));
        }
        if (lockRow != null) {
            lockRow.setText("App lock (fingerprint): " + (lockEnabled ? "ON" : "OFF"));
        }
    }

    private void persistDesktopMode() {
        prefs.edit().putBoolean(KEY_DESKTOP, desktopMode).apply();
    }

    private void toggleDesktop() {
        desktopMode = !desktopMode;
        persistDesktopMode();
        updateToggleLabels();
        webView.getSettings().setUserAgentString(
            desktopMode ? DESKTOP_UA : null);
        webView.reload();
        closeDrawer();
    }

    private void toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn;
        updateToggleLabels();
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        closeDrawer();
    }

    private void toggleLock() {
        lockEnabled = !lockEnabled;
        prefs.edit().putBoolean(KEY_LOCK, lockEnabled).apply();
        updateToggleLabels();
        Toast.makeText(this,
            "App lock " + (lockEnabled ? "ON - it will ask next time you return"
                                        : "OFF"),
            Toast.LENGTH_SHORT).show();
        closeDrawer();
    }

    private void confirmSignOut() {
        closeDrawer();
        new AlertDialog.Builder(this)
            .setMessage("Sign out of iCloud in this app and clear its session?")
            .setPositiveButton("Clear", (d, w) -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                webView.clearHistory();
                webView.loadUrl(HOME_URL);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- Biometric app lock ----

    private void buildLockOverlay(FrameLayout root) {
        lockOverlay = new FrameLayout(this);
        lockOverlay.setBackgroundColor(Color.rgb(18, 18, 20));
        lockOverlay.setVisibility(View.GONE);
        lockOverlay.setClickable(true);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        centerParams.gravity = Gravity.CENTER;
        center.setLayoutParams(centerParams);

        TextView label = new TextView(this);
        label.setText("iCloud Photos is locked");
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setGravity(Gravity.CENTER);
        center.addView(label);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setOnClickListener(v -> showBiometricPrompt());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(16);
        unlock.setLayoutParams(btnParams);
        center.addView(unlock);

        lockOverlay.addView(center);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void requireLock() {
        locked = true;
        if (lockOverlay != null) lockOverlay.setVisibility(View.VISIBLE);
        closeDrawer();
        showBiometricPrompt();
    }

    private void unlockGranted() {
        locked = false;
        unlockedThisSession = true;
        if (lockOverlay != null) lockOverlay.setVisibility(View.GONE);
        if (!startLoaded && pendingStartUrl != null) {
            startLoaded = true;
            webView.loadUrl(pendingStartUrl);
            pendingStartUrl = null;
        } else if (pendingStartUrl != null) {
            webView.loadUrl(pendingStartUrl);
            pendingStartUrl = null;
        }
    }

    private void unlockFailOpen(String why) {
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        unlockGranted();
    }

    private void showBiometricPrompt() {
        if (!locked) return;
        if (Build.VERSION.SDK_INT < 28) {
            unlockFailOpen("App lock needs Android 9+ - opening without it.");
            return;
        }
        try {
            cancelSignal = new CancellationSignal();
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Unlock iCloud Photos")
                .setNegativeButton("Cancel", getMainExecutor(),
                    (dialog, which) -> Toast.makeText(MainActivity.this,
                        "Still locked - tap Unlock to try again.",
                        Toast.LENGTH_SHORT).show())
                .build();
            prompt.authenticate(cancelSignal, getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        runOnUiThread(() -> unlockGranted());
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Fingerprint not recognised - try again.",
                            Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                            CharSequence errString) {
                        runOnUiThread(() -> {
                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                    || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                    || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                                unlockFailOpen("No fingerprint enrolled on "
                                    + "this device - opening without app lock.");
                            } else {
                                Toast.makeText(MainActivity.this,
                                    "Still locked - tap Unlock to try again.",
                                    Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
        } catch (Exception e) {
            unlockFailOpen("Fingerprint unavailable - opening without app lock.");
        }
    }

    // ---- Blob download bridge ----

    public class BlobBridge {
        private File tempFile;
        private FileOutputStream tempOut;
        private String fileName;
        private String mimeType;

        @JavascriptInterface
        public void begin(String name, String mime, String size) {
            try {
                closeTemp();
                fileName = (name == null || name.isEmpty()) ? "download" : name;
                mimeType = mime;
                tempFile = new File(getCacheDir(), "blob-download.bin");
                tempOut = new FileOutputStream(tempFile);
            } catch (Exception e) {
                fail("Could not start download");
            }
        }

        @JavascriptInterface
        public void chunk(String base64) {
            try {
                if (tempOut != null && base64 != null) {
                    tempOut.write(Base64.decode(base64, Base64.DEFAULT));
                }
            } catch (Exception e) {
                fail("Download failed while receiving data");
            }
        }

        @JavascriptInterface
        public void finish() {
            try {
                closeTemp();
                if (tempFile == null || !tempFile.exists()) {
                    fail("Download failed - no data received");
                    return;
                }
                saveDownloadedFile();
            } catch (Exception e) {
                fail("Download failed while saving");
            }
        }

        @JavascriptInterface
        public void fail(String message) {
            try { closeTemp(); } catch (Exception ignored) {}
            if (tempFile != null) tempFile.delete();
            final String msg = (message == null || message.isEmpty())
                ? "Download failed" : message;
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                msg, Toast.LENGTH_LONG).show());
        }

        private void closeTemp() throws Exception {
            if (tempOut != null) {
                tempOut.flush();
                tempOut.close();
                tempOut = null;
            }
        }

        private void saveDownloadedFile() throws Exception {
            final String name = fileName;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                if (mimeType != null && !mimeType.isEmpty()) {
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                }
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);
                Uri dest = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (dest == null) throw new Exception("MediaStore insert failed");
                InputStream in = new FileInputStream(tempFile);
                OutputStream out = getContentResolver().openOutputStream(dest);
                copyStream(in, out);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Saved " + name + " to Downloads",
                    Toast.LENGTH_LONG).show());
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                File outFile = new File(dir, name);
                InputStream in = new FileInputStream(tempFile);
                OutputStream out = new FileOutputStream(outFile);
                copyStream(in, out);
                final String path = outFile.getAbsolutePath();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Saved to " + path, Toast.LENGTH_LONG).show());
            }
            tempFile.delete();
        }

        private void copyStream(InputStream in, OutputStream out)
                throws Exception {
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void startBlobDownload(WebView wv, String blobUrl,
            String fileName, String mimeType) {
        String name = (fileName == null || fileName.isEmpty())
            ? "download" : fileName;
        String mime = mimeType == null ? "" : mimeType;
        String js = "(function(){"
            + "var url=" + jsString(blobUrl) + ";"
            + "fetch(url).then(function(r){return r.blob();})"
            + ".then(function(b){"
            + "var reader=new FileReader();"
            + "reader.onload=function(){"
            + "var dataUrl=reader.result;"
            + "var b64=dataUrl.substring(dataUrl.indexOf(',')+1);"
            + "BlobBridge.begin(" + jsString(name) + ","
            + jsString(mime.isEmpty() ? "application/octet-stream" : mime)
            + ",String(b.size));"
            + "var CH=1048576;"
            + "for(var i=0;i<b64.length;i+=CH){"
            + "BlobBridge.chunk(b64.substring(i,i+CH));}"
            + "BlobBridge.finish();"
            + "};"
            + "reader.onerror=function(){BlobBridge.fail('Download failed');};"
            + "reader.readAsDataURL(b);"
            + "}).catch(function(e){BlobBridge.fail('Download failed: '+e);});"
            + "})();";
        wv.evaluateJavascript(js, null);
        Toast.makeText(this, "Downloading " + name + "...",
            Toast.LENGTH_SHORT).show();
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ---- WebView setup ----

    private void configureWebView(final WebView wv, boolean isMain) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        if (desktopMode) {
            s.setUserAgentString(DESKTOP_UA);
        }

        wv.setHorizontalScrollBarEnabled(false);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        if (isMain) {
            wv.addJavascriptInterface(new BlobBridge(), "BlobBridge");
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("icloud.com") || host.endsWith("apple.com")
                        || host.endsWith("mzstatic.com")) {
                    return false;
                }
                if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (isMain) {
                    progress.setProgress(newProgress);
                    progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                boolean multiple = fileChooserParams.getMode()
                    == FileChooserParams.MODE_OPEN_MULTIPLE;
                if (isMain && stashHasItems()) {
                    List<Uri> stashed = drainStash(multiple);
                    if (stashed != null && !stashed.isEmpty()) {
                        filePathCallback.onReceiveValue(
                            stashed.toArray(new Uri[0]));
                        Toast.makeText(MainActivity.this,
                            "Attached " + stashed.size() + " shared photo(s)",
                            Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = filePathCallback;
                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this,
                        "No file picker available", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, Message resultMsg) {
                popupView = new WebView(MainActivity.this);
                configureWebView(popupView, false);
                popupDialog = new Dialog(MainActivity.this,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                FrameLayout holder = new FrameLayout(MainActivity.this);
                holder.addView(popupView);
                popupDialog.setContentView(holder);
                popupDialog.setOnDismissListener(d -> {
                    if (popupView != null) {
                        popupView.destroy();
                        popupView = null;
                    }
                    popupDialog = null;
                });
                popupDialog.show();
                WebView.WebViewTransport transport =
                    (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (popupDialog != null && window == popupView) {
                    popupDialog.dismiss();
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                    final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                        (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                    final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                        (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel,
                        (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                    String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                if (defaultValue != null) input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok,
                        (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel,
                        (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }
        });

        if (isMain) {
            wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                if (url == null) return;
                if (url.startsWith("blob:")) {
                    String fileName = android.webkit.URLUtil.guessFileName(
                        url, contentDisposition, mimeType);
                    startBlobDownload(wv, url, fileName, mimeType);
                    return;
                }
                if (url.startsWith("data:")) {
                    Toast.makeText(MainActivity.this,
                        "This download type is not supported yet - tell Anton",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    String fileName = android.webkit.URLUtil.guessFileName(
                        url, contentDisposition, mimeType);
                    DownloadManager.Request req =
                        new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        req.addRequestHeader("Cookie", cookies);
                    }
                    req.setTitle(fileName);
                    req.setDescription("Downloads");
                    req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this,
                        "Downloading " + fileName + " to Downloads",
                        Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Download failed to start: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                List<Uri> list = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        list.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    list.add(data.getData());
                }
                if (!list.isEmpty()) {
                    results = list.toArray(new Uri[0]);
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (locked) {
            // No bypass via Back while the app lock is showing.
            return;
        }
        if (isDrawerOpen()) {
            closeDrawer();
            return;
        }
        if (popupDialog != null && popupDialog.isShowing()) {
            popupDialog.dismiss();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockEnabled && !locked) {
            if (!unlockedThisSession) {
                requireLock();
            } else if (wentBackground
                    && System.currentTimeMillis() - backgroundedAtMs >= RELOCK_MS) {
                unlockedThisSession = false;
                requireLock();
            }
        }
        wentBackground = false;
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (lockEnabled && unlockedThisSession && !locked) {
            wentBackground = true;
            backgroundedAtMs = System.currentTimeMillis();
        }
    }

    @Override
    protected void onDestroy() {
        if (cancelSignal != null && !cancelSignal.isCanceled()) {
            cancelSignal.cancel();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
