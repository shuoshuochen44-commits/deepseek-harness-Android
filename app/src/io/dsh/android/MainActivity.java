package io.dsh.android;

import android.annotation.SuppressLint;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * 单 Activity 外壳：内嵌 Node 内核 → 本机回环起 dsh web → WebView 加载同一份官方 Web GUI。
 * 界面不重画，因此功能与桌面端同源；本类只负责启动引导与安卓侧适配。
 */
public class MainActivity extends Activity {
    private static final String TAG = "DshMain";
    private static final int REQ_FILE_CHOOSER = 0x1001;
    /** 顶栏 / 启动页底色（近黑，衬托蓝色鲸鱼）。 */
    private static final int CHROME_BG = 0xFF05070C;
    private static final int ACCENT = 0xFF4D6BFE;

    private WebView web;
    private LinearLayout root;
    private LinearLayout header;
    private ImageButton backButton;
    private FrameLayout content;
    private LinearLayout bootPanel;
    private TextView bootTitle;
    private TextView bootDetail;
    private ImageView splashLogo;
    private ObjectAnimator splashPulse;
    private ProgressBar bootProgress;
    private Button retryButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> pendingFileChooser;
    private boolean pageLoaded;
    private Dialog popupDialog;
    private final PageLoadState pageState = new PageLoadState();
    private volatile boolean destroyed;
    private boolean bootInProgress;
    private boolean rendererGone;
    private Thread bootThread;
    private NodeRuntime.UrlListener runtimeUrlListener;
    private volatile Uri appOrigin;
    private NavBridge navigation;

    /** 页面内是否还有上一级（设置弹窗 / 侧栏面板 / 会话）。 */
    private volatile boolean inAppCanBack;
    /** 连按两次返回才退回桌面。 */
    private long lastBackAt;
    /** 一次返回评估尚未完成时，忽略重复触发。 */
    private boolean backPending;

    // 启动阶段计时（用于回答"为什么慢"）
    private long tCreate;
    private long tInstalled = -1;
    private long tNodeReady = -1;
    private long tPageDone = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tCreate = System.currentTimeMillis();
        buildUi();
        applyEdgeToEdge();
        requestNotificationPermission();
        requestBatteryExemption();
        PhoneBridge.ensureStarted(this);
        startBoot();
        // Shizuku：先请求 binder（异步），到手后自动绑定 shell 用户服务；
        // 没授权则在后台持续尝试弹授权框（binder 送达需要时间）。
        ShizukuShell.ensureBinderRequested(this);
        ShizukuShell.requestPermissionEventually(this, 3000, 12);
        ShizukuShell.ensureBound(this);
    }

    /**
     * 申请忽略电池优化。国产 ROM（OriginOS 等）默认会清后台，
     * 内核一被杀，下次打开就得重新冷启动 —— 这是"启动慢"最主要的人为原因。
     */
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            @SuppressLint("BatteryLife")
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "battery exemption request unavailable: " + e);
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CHROME_BG);

        buildHeader();

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.WHITE);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setVisibility(View.INVISIBLE);
        content.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildSplash();
        content.addView(bootPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        configureWebView();
    }

    /** 自绘顶栏：{返回} + 蓝鲸 + DeepSeek。返回键只在非主界面出现。 */
    private void buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(CHROME_BG);
        header.setPadding(dp(4), 0, dp(8), 0);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        backButton = new ImageButton(this);
        backButton.setImageResource(drawable("ic_back"));
        backButton.setBackgroundColor(Color.TRANSPARENT);
        backButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        backButton.setPadding(dp(13), dp(13), dp(13), dp(13));
        backButton.setContentDescription("返回");
        backButton.setVisibility(View.GONE);
        backButton.setOnClickListener(v -> handleBack());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(drawable("ic_whale_blue"));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        logoLp.leftMargin = dp(6);
        header.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText("DeepSeek");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            title.setLetterSpacing(0.01f);
        }
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(8);
        header.addView(title, titleLp);
    }

    /** 启动页：脉动鲸鱼 + 转圈 + 阶段文字。 */
    private void buildSplash() {
        bootPanel = new LinearLayout(this);
        bootPanel.setOrientation(LinearLayout.VERTICAL);
        bootPanel.setGravity(Gravity.CENTER);
        bootPanel.setBackgroundColor(CHROME_BG);
        bootPanel.setPadding(dp(28), dp(28), dp(28), dp(28));

        splashLogo = new ImageView(this);
        splashLogo.setImageResource(drawable("ic_whale_blue"));
        try (java.io.InputStream image = getAssets().open("mobile/welcome.webp")) {
            splashLogo.setImageBitmap(BitmapFactory.decodeStream(image));
        } catch (IOException error) {
            Log.w(TAG, "Welcome mascot unavailable; using brand mark");
        }
        splashLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splashLogo.setContentDescription("蓝色鲸鱼娘");
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(220), dp(160));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(splashLogo, logoLp);

        bootTitle = new TextView(this);
        bootTitle.setText("DeepSeek");
        bootTitle.setTextColor(Color.WHITE);
        bootTitle.setTextSize(22f);
        bootTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bootTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(18);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bootTitle, titleLp);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bootProgress = bar;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        barLp.topMargin = dp(20);
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bar, barLp);

        bootDetail = new TextView(this);
        bootDetail.setText("正在启动…");
        bootDetail.setTextColor(Color.parseColor("#8b98a9"));
        bootDetail.setTextSize(12.5f);
        bootDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(14);
        detailLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bootDetail, detailLp);

        retryButton = new Button(this);
        retryButton.setText("重新加载");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        retryButton.setMinHeight(dp(48));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> retryLoad());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(dp(160), dp(52));
        retryLp.topMargin = dp(20);
        bootPanel.addView(retryButton, retryLp);

        startSplashPulse();
    }

    private void startSplashPulse() {
        if (splashLogo == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        splashPulse = ObjectAnimator.ofFloat(splashLogo, "alpha", 1f, 0.42f);
        splashPulse.setDuration(1150);
        splashPulse.setRepeatCount(ObjectAnimator.INFINITE);
        splashPulse.setRepeatMode(ObjectAnimator.REVERSE);
        splashPulse.setInterpolator(new LinearInterpolator());
        splashPulse.start();
    }

    private void stopSplashPulse() {
        if (splashPulse != null) {
            splashPulse.cancel();
            splashPulse = null;
        }
    }

    /** 不走 R.java：资源按名字取，省掉生成 R 的构建步骤。 */
    private int drawable(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private void applyEdgeToEdge() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // 顶栏是深色的，系统栏图标必须用浅色，否则在浅色系统主题下看不见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
            window.getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                v.onApplyWindowInsets(insets);
                return insets;
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 0x2001);
            }
        }
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);

        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        // A message channel is delivered only to the exact local app origin.
        if (navigation != null) navigation.close();
        navigation = new NavBridge(this::onPageCanBack);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                if (!isAppUrl(uri.toString()) || path == null
                        || !path.startsWith("/__dsh_mobile/")) return null;
                String name = path.substring("/__dsh_mobile/".length());
                if (!"GET".equals(request.getMethod())
                        || !(name.equals("welcome.webp") || name.equals("empty.webp"))) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                try {
                    return new WebResourceResponse("image/webp", null, 200, "OK",
                            Collections.singletonMap("Cache-Control", "private, max-age=86400"),
                            getAssets().open("mobile/" + name));
                } catch (IOException e) {
                    Log.w(TAG, "Mobile artwork unavailable: " + name);
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.US);
                // http/https 一律留在 App 内：登录 / OAuth 回调必须与主界面共享同一个
                // WebView 和 cookie 罐，甩给系统浏览器会白屏并且登录态回不来。
                if (scheme.equals("http") || scheme.equals("https")) {
                    return false;
                }
                // window.open 产生的空窗口，忽略
                if (scheme.isEmpty() || scheme.equals("about")) {
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(MainActivity.this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (navigation != null) navigation.close();
                if (!destroyed && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    pageState.begin();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showFailure("页面暂时无法加载，请稍后重试。", null);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showFailure(response.getStatusCode() == 401 || response.getStatusCode() == 403
                            ? "登录状态需要更新，请重新加载。" : "服务暂时未能响应，请稍后重试。", null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (navigation != null) navigation.close();
                rendererGone = true;
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                if (web == view) web = null;
                showFailure("页面已暂停，点击重新加载即可恢复。", null);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (destroyed || pageState.isFailed()) return;
                if (isAppUrl(url) && isAppUrl(view.getUrl())) {
                    MobileSkin.apply(view);
                    navigation.install(view, appOrigin);
                } else {
                    onPageCanBack(view.canGoBack());
                }
                if (!pageState.complete()) return;
                boolean firstLoad = !pageLoaded;
                pageLoaded = true;
                tPageDone = System.currentTimeMillis();
                ui.removeCallbacks(bootTickerRunnable);
                stopSplashPulse();
                view.setVisibility(View.VISIBLE);
                bootPanel.setVisibility(View.GONE);
                retryButton.setVisibility(View.GONE);
                if (firstLoad) reportBootTimings();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                return openLoginPopup(resultMsg);
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (popupDialog != null) {
                    popupDialog.dismiss();
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "console: " + message.message() + " @" + message.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFileChooser = null;
                    return false;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setDestinationInExternalFilesDir(MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this,
                                "开始下载：" + (dir == null ? name : new File(dir, name).getAbsolutePath()),
                                Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private volatile String bootPhase = "准备运行时";

    private final Runnable bootTickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || pageState.isReady() || pageState.isFailed()) {
                return;
            }
            long secs = (System.currentTimeMillis() - tCreate) / 1000;
            bootDetail.setText(bootPhase + "\n已用 " + secs + "s");
            ui.postDelayed(this, 500);
        }
    };

    /**
     * `window.open` / `target=_blank` 的承载窗口。
     *
     * DSH 的账号登录会开新窗口；之前没实现 onCreateWindow，请求被丢弃、退化成 about:blank
     * 再被甩给系统浏览器，于是"点了登录是一片白"。
     * 这里用一个 Dialog 里的 WebView 承接（与主界面共享 CookieManager），
     * 一旦它回到本机 127.0.0.1 就关掉弹窗、把地址交回主界面。
     */
    private boolean openLoginPopup(android.os.Message resultMsg) {
        if (destroyed) return false;
        if (popupDialog != null) popupDialog.dismiss();
        final WebView popup = new WebView(this);
        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setUseWideViewPort(true);
        ps.setLoadWithOverviewMode(false);
        CookieManager.getInstance().setAcceptCookie(true);

        final Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.setContentView(popup, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> {
            if (popupDialog == dialog) popupDialog = null;
            popup.destroy();
        });
        popupDialog = dialog;

        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                dialog.dismiss();
                if (!destroyed) Toast.makeText(MainActivity.this,
                        "登录页面已关闭，请重新打开登录。", Toast.LENGTH_LONG).show();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (request.isForMainFrame() && isAppUrl(u.toString())) {
                    final String target = u.toString();
                    ui.post(() -> {
                        dialog.dismiss();
                        if (!destroyed && web != null) loadApp(target);
                    });
                    return true;
                }
                return false;
            }
        });
        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) { dialog.dismiss(); }
        });

        dialog.show();
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    private void startBoot() {
        if (destroyed || bootInProgress) return;
        bootInProgress = true;
        pageState.begin();
        final NodeRuntime runtime = NodeRuntime.get(this);
        runtimeUrlListener = url -> ui.post(() -> {
            // 首次加载由 boot 流程负责；这里只处理内核重启后 URL/token 变化
            if (!destroyed && pageLoaded && web != null && !bootInProgress) {
                Log.i(TAG, "runtime restarted; reloading app");
                loadApp(url);
            }
        });
        runtime.setUrlListener(runtimeUrlListener);
        ui.removeCallbacks(bootTickerRunnable);
        bootTickerRunnable.run();
        bootThread = new Thread(() -> {
            try {
                // Even a warm-start probe can block on a socket. Keep it off the UI thread.
                if (runtime.isRunning() && runtime.waitReady(1500)) {
                    tInstalled = System.currentTimeMillis();
                    tNodeReady = tInstalled;
                    ui.post(() -> {
                        if (destroyed) return;
                        bootInProgress = false;
                        loadApp(runtime.url());
                        startService(new Intent(this, NodeService.class));
                    });
                    return;
                }
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                RuntimeInstaller installer = new RuntimeInstaller(this);
                // Activity replacement must not run two payload installations at once.
                synchronized (RuntimeInstaller.class) {
                    if (destroyed || Thread.currentThread().isInterrupted()) return;
                    installer.ensureInstalled((stage, percent) -> {
                        bootPhase = percent > 0 ? stage + " " + percent + "%" : stage;
                    });
                }
                tInstalled = System.currentTimeMillis();
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                bootPhase = "启动 DSH 内核";
                // 固定端口：登录 cookie 绑定 127.0.0.1:<端口>，端口一变就得重新登录
                int port = NodeRuntime.preferredPort(MainActivity.this);
                runtime.start(port);
                startService(new Intent(this, NodeService.class));
                boolean ready = runtime.waitReady(180_000);
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                if (!ready) {
                    Log.w(TAG, "boot on port " + port + " failed, retrying with kernel-chosen port");
                    runtime.stop();
                    runtime.start(0);
                    ready = runtime.waitReady(180_000);
                }
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                if (ready) {
                    tNodeReady = System.currentTimeMillis();
                    NodeRuntime.rememberPort(MainActivity.this, runtime.currentPort());
                    bootPhase = "加载界面";
                    final String url = runtime.url();
                    ui.post(() -> {
                        if (destroyed) return;
                        bootInProgress = false;
                        loadApp(url);
                    });
                } else {
                    showFailure("内核启动超时", new File(installer.logs(), "node.log"));
                }
            } catch (Exception e) {
                Log.e(TAG, "boot failed", e);
                if (!destroyed) showFailure("启动遇到问题，请重试。\n" + e.getClass().getSimpleName(), null);
            }
        }, "dsh-boot");
        bootThread.start();
    }

    private void loadApp(String url) {
        if (destroyed || web == null) return;
        // The origin comes from the runtime, never from external page navigation.
        appOrigin = Uri.parse(url);
        Log.i(TAG, "loading local app");
        pageState.begin();
        bootPhase = "加载界面";
        web.loadUrl(url);
    }

    private boolean isAppUrl(String url) {
        Uri expected = appOrigin;
        if (url == null || expected == null) return false;
        Uri actual = Uri.parse(url);
        return "http".equals(expected.getScheme())
                && "127.0.0.1".equals(expected.getHost())
                && expected.getScheme().equals(actual.getScheme())
                && expected.getHost().equals(actual.getHost())
                && expected.getPort() == actual.getPort();
    }

    private void retryLoad() {
        if (destroyed || bootInProgress) return;
        retryButton.setVisibility(View.GONE);
        bootProgress.setVisibility(View.VISIBLE);
        bootTitle.setText("正在重新连接");
        bootDetail.setTextColor(Color.parseColor("#8b98a9"));
        bootDetail.setTextSize(12.5f);
        bootDetail.setTextIsSelectable(false);
        if (rendererGone || web == null) {
            web = new WebView(this);
            web.setBackgroundColor(Color.WHITE);
            web.setVisibility(View.INVISIBLE);
            content.addView(web, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            configureWebView();
            rendererGone = false;
        }
        startBoot();
    }

    /** 把启动各阶段耗时写进日志并提示一次，便于判断慢在解压、内核还是界面。 */
    private void reportBootTimings() {
        if (tPageDone <= 0 || tCreate <= 0) {
            return;
        }
        long install = tInstalled > 0 ? tInstalled - tCreate : -1;
        long node = (tInstalled > 0 && tNodeReady > tInstalled) ? tNodeReady - tInstalled : -1;
        long page = tNodeReady > 0 ? tPageDone - tNodeReady : -1;
        long total = tPageDone - tCreate;
        String line = String.format(java.util.Locale.US,
                "boot total=%dms prepare=%dms kernel=%dms render=%dms",
                total, install, node, page);
        Log.i(TAG, line);
        try {
            RuntimeInstaller installer = new RuntimeInstaller(this);
            File f = new File(installer.logs(), "boot.log");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((System.currentTimeMillis() + "  " + line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
            // 计时日志写失败不影响使用
        }
        String msg = String.format(java.util.Locale.US, "启动 %.1fs（准备 %.1fs · 内核 %.1fs · 界面 %.1fs）",
                total / 1000.0,
                Math.max(0, install) / 1000.0,
                Math.max(0, node) / 1000.0,
                Math.max(0, page) / 1000.0);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showFailure(String reason, File logFile) {
        ui.post(() -> {
            if (destroyed || !pageState.fail()) return;
            bootInProgress = false;
            ui.removeCallbacks(bootTickerRunnable);
            stopSplashPulse();
            bootProgress.setVisibility(View.GONE);
            bootPanel.setVisibility(View.VISIBLE);
            if (web != null) web.setVisibility(View.INVISIBLE);
            retryButton.setVisibility(View.VISIBLE);
            bootTitle.setText("暂时无法加载");
            StringBuilder sb = new StringBuilder(reason);
            String tail = tailOf(logFile, 2400);
            if (tail != null && !tail.isEmpty()) {
                sb.append("\n\n").append(tail);
            }
            bootDetail.setText(sb.toString());
            bootDetail.setTextSize(11f);
            bootDetail.setTextColor(Color.parseColor("#ff8a80"));
            bootDetail.setTextIsSelectable(true);
        });
    }

    private static String tailOf(File f, int maxChars) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - maxChars);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileChooser != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
                pendingFileChooser.onReceiveValue(result);
                pendingFileChooser = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 页面上报：当前是否还有上一级（设置弹窗 / 侧栏面板 / 会话）。 */
    private void onPageCanBack(boolean canBack) {
        if (destroyed) return;
        if (canBack == inAppCanBack) {
            return;
        }
        inAppCanBack = canBack;
        runOnUiThread(() -> {
            if (destroyed || backButton == null) {
                return;
            }
            backButton.setVisibility(canBack ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * 统一返回入口（硬件返回键与顶栏返回键共用）。
     *
     * 顺序：输入法 → 设置弹窗 → 侧栏面板 → 侧栏 → 会话 → 主界面。
     * 交给页面处理时由 __dshBack() 返回 'handled'；回到主界面后返回 'root'，
     * 此时要求连按两次才退回桌面，避免误触退出。
     */
    private void handleBack() {
        // 键盘弹着的时候，返回先收键盘（否则输入法永远关不掉）
        if (hideImeIfVisible()) {
            return;
        }
        if (web == null) {
            exitOrHint();
            return;
        }
        if (!isAppUrl(web.getUrl())) {
            if (web.canGoBack()) web.goBack();
            else exitOrHint();
            return;
        }
        if (backPending) {
            return;
        }
        backPending = true;
        try {
            web.evaluateJavascript(
                    "(function(){try{return window.__dshBack?window.__dshBack():'root';}catch(e){return 'root';}})()",
                    value -> {
                        if (destroyed) return;
                        backPending = false;
                        boolean handled = value != null && value.contains("handled");
                        if (!handled) {
                            exitOrHint();
                        }
                    });
        } catch (Exception e) {
            backPending = false;
            exitOrHint();
        }
    }

    private boolean hideImeIfVisible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || web == null) {
            return false;
        }
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) {
            return false;
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) {
            return false;
        }
        imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        return true;
    }

    private void exitOrHint() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            lastBackAt = 0;
            moveTaskToBack(true);
        } else {
            lastBackAt = now;
            Toast.makeText(this, "再按一次返回键回到桌面", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        stopSplashPulse();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (!pageState.isReady() && !pageState.isFailed() && splashPulse == null) startSplashPulse();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pageState.destroy();
        NodeRuntime.get(this).clearUrlListener(runtimeUrlListener);
        if (navigation != null) navigation.close();
        if (bootThread != null) bootThread.interrupt();
        ui.removeCallbacksAndMessages(null);
        stopSplashPulse();
        if (pendingFileChooser != null) {
            pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = null;
        }
        if (popupDialog != null) popupDialog.dismiss();
        if (web != null) {
            if (content != null) {
                content.removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
