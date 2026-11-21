package io.dsh.android;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/**
 * 单 Activity 外壳：内嵌 Node 内核 → 本机回环起 dsh web → WebView 加载同一份官方 Web GUI。
 * 界面不重画，因此功能与桌面端同源；本类只负责启动引导与安卓侧适配。
 */
public class MainActivity extends Activity {
    private static final String TAG = "DshMain";
    private static final int REQ_FILE_CHOOSER = 0x1001;
    /** 启动页深海蓝配色（与蓝色鲸鱼娘主视觉保持一致）。 */
    private static final int SPLASH_BG_TOP = 0xFF0E1B3D;
    private static final int SPLASH_BG_MID = 0xFF081024;
    private static final int SPLASH_BG_BOTTOM = 0xFF050914;
    private static final int SURFACE_LIGHT = 0xFFFFFFFF;
    private static final int SURFACE_DARK = 0xFF18181B;
    private static final int ACCENT = 0xFF4D6BFE;
    private static final int ACCENT_DEEP = 0xFF3655E6;

    private WebView web;
    private FrameLayout root;
    private FrameLayout content;
    private LinearLayout bootPanel;
    private LinearLayout bootStatusPill;
    private TextView bootTitle;
    private TextView bootSubtitle;
    private TextView bootDetail;
    private ImageView splashLogo;
    private AnimatorSet splashPulse;
    private AnimatorSet revealAnimator;
    private ProgressBar bootProgress;
    private Button retryButton;
    private String lastBootDetail;
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
    private WebMessagePort themePort;
    private long themeGeneration;
    private boolean pageThemeDark;
    private int pageSurfaceColor;
    private boolean hasSyncedPageTheme;

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

    private boolean isSystemDarkTheme() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int initialWebSurfaceColor() {
        return isSystemDarkTheme() ? SURFACE_DARK : SURFACE_LIGHT;
    }

    private Drawable buildStatusPillBackground(boolean error) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(error ? 0x26FF6B6B : 0xCC132040);
        bg.setStroke(dp(1), error ? 0x55FF8A80 : 0x384D6BFE);
        return bg;
    }

    private Drawable buildRetryButtonBackground() {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { ACCENT, ACCENT_DEEP });
        normal.setCornerRadius(dp(26));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(26));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), normal, mask);
    }

    /** 取消原生黑色顶栏，全屏承载 WebView，并由状态栏颜色随页面主题动态融合。 */
    private void buildUi() {
        pageThemeDark = isSystemDarkTheme();
        pageSurfaceColor = initialWebSurfaceColor();

        root = new FrameLayout(this);
        root.setBackgroundColor(SPLASH_BG_TOP);

        content = new FrameLayout(this);
        content.setBackgroundColor(pageSurfaceColor);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(pageSurfaceColor);
        web.setVisibility(View.INVISIBLE);
        content.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildSplash();
        content.addView(bootPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        configureWebView();
    }

    /** 启动页：深海蓝微光渐变 + 悬浮鲸鱼娘 + 圆角状态胶囊。 */
    private void buildSplash() {
        bootPanel = new LinearLayout(this);
        bootPanel.setOrientation(LinearLayout.VERTICAL);
        bootPanel.setGravity(Gravity.CENTER);
        bootPanel.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { SPLASH_BG_TOP, SPLASH_BG_MID, SPLASH_BG_BOTTOM }));
        bootPanel.setPadding(dp(28), dp(28), dp(28), dp(36));

        splashLogo = new ImageView(this);
        splashLogo.setImageResource(drawable("ic_whale_blue"));
        try (java.io.InputStream image = getAssets().open("mobile/welcome.webp")) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeStream(image, null, opts);
            if (decoded != null) splashLogo.setImageBitmap(decoded);
        } catch (IOException error) {
            Log.w(TAG, "Welcome mascot unavailable; using brand mark");
        }
        splashLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splashLogo.setContentDescription("蓝色鲸鱼娘");
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(228), dp(164));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(splashLogo, logoLp);

        bootTitle = new TextView(this);
        bootTitle.setText("DeepSeek");
        bootTitle.setTextColor(0xFFF5F8FF);
        bootTitle.setTextSize(23f);
        bootTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bootTitle.setLetterSpacing(0.02f);
        bootTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(16);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bootTitle, titleLp);

        bootSubtitle = new TextView(this);
        bootSubtitle.setText("探索未至之境");
        bootSubtitle.setTextColor(0xFF7D95BD);
        bootSubtitle.setTextSize(13f);
        bootSubtitle.setLetterSpacing(0.06f);
        bootSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bootSubtitle, subLp);

        bootStatusPill = new LinearLayout(this);
        bootStatusPill.setOrientation(LinearLayout.HORIZONTAL);
        bootStatusPill.setGravity(Gravity.CENTER);
        bootStatusPill.setBackground(buildStatusPillBackground(false));
        bootStatusPill.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.topMargin = dp(24);
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(bootStatusPill, pillLp);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bootProgress = bar;
        bar.setIndeterminateTintList(ColorStateList.valueOf(0xFF7DA2FF));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        barLp.rightMargin = dp(10);
        bootStatusPill.addView(bar, barLp);

        bootDetail = new TextView(this);
        bootDetail.setText("正在启动…");
        bootDetail.setTextColor(0xFFB7C7E0);
        bootDetail.setTextSize(12.5f);
        bootDetail.setGravity(Gravity.CENTER);
        bootDetail.setLineSpacing(dp(2), 1f);
        bootStatusPill.addView(bootDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = new Button(this);
        retryButton.setText("重新加载");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setTextSize(14.5f);
        retryButton.setTypeface(Typeface.DEFAULT_BOLD);
        retryButton.setAllCaps(false);
        retryButton.setBackground(buildRetryButtonBackground());
        retryButton.setElevation(dp(4));
        retryButton.setMinHeight(dp(48));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> retryLoad());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(dp(168), dp(48));
        retryLp.topMargin = dp(22);
        retryLp.gravity = Gravity.CENTER_HORIZONTAL;
        bootPanel.addView(retryButton, retryLp);

        startSplashPulse();
    }

    /** 轻盈悬浮 + 柔和呼吸动效，使用硬件层保证 60fps 流畅度。 */
    private void startSplashPulse() {
        if (splashLogo == null || !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        stopSplashPulse();
        splashLogo.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(splashLogo, View.TRANSLATION_Y, 0f, -dp(7));
        floatAnim.setDuration(1650);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator breatheAnim = ObjectAnimator.ofFloat(splashLogo, View.ALPHA, 1f, 0.90f);
        breatheAnim.setDuration(1650);
        breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
        breatheAnim.setRepeatMode(ValueAnimator.REVERSE);
        breatheAnim.setInterpolator(new AccelerateDecelerateInterpolator());

        splashPulse = new AnimatorSet();
        splashPulse.playTogether(floatAnim, breatheAnim);
        splashPulse.start();
    }

    private void stopSplashPulse() {
        if (splashPulse != null) {
            splashPulse.cancel();
            splashPulse = null;
        }
        if (splashLogo != null) {
            splashLogo.setTranslationY(0f);
            splashLogo.setAlpha(1f);
            splashLogo.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private void cancelRevealAnimation() {
        if (revealAnimator != null) {
            revealAnimator.cancel();
            revealAnimator = null;
        }
    }

    /** 启动页到 Web 界面的丝滑交叉淡入转场，并同步状态栏明暗样式。 */
    private void revealWebView(final WebView view) {
        cancelRevealAnimation();
        retryButton.setVisibility(View.GONE);
        applySystemBarTheme(!pageThemeDark, pageSurfaceColor);
        if (!ValueAnimator.areAnimatorsEnabled() || bootPanel.getVisibility() != View.VISIBLE) {
            view.setAlpha(1f);
            view.setVisibility(View.VISIBLE);
            bootPanel.setVisibility(View.GONE);
            bootPanel.setAlpha(1f);
            bootPanel.setScaleX(1f);
            bootPanel.setScaleY(1f);
            return;
        }
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        bootPanel.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        ObjectAnimator webIn = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        ObjectAnimator splashOut = ObjectAnimator.ofFloat(bootPanel, View.ALPHA, bootPanel.getAlpha(), 0f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(bootPanel, View.SCALE_X, 1f, 1.03f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(bootPanel, View.SCALE_Y, 1f, 1.03f);

        revealAnimator = new AnimatorSet();
        revealAnimator.setDuration(220);
        revealAnimator.setInterpolator(new DecelerateInterpolator());
        revealAnimator.playTogether(webIn, splashOut, scaleX, scaleY);
        revealAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (revealAnimator == animation) revealAnimator = null;
                bootPanel.setLayerType(View.LAYER_TYPE_NONE, null);
                if (destroyed || cancelled) return;
                bootPanel.setVisibility(View.GONE);
                bootPanel.setAlpha(1f);
                bootPanel.setScaleX(1f);
                bootPanel.setScaleY(1f);
                view.setAlpha(1f);
            }
        });
        revealAnimator.start();
    }

    /** 不走 R.java：资源按名字取，省掉生成 R 的构建步骤。 */
    private int drawable(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    /** 根据当前显示内容（启动页 / 浅色网页 / 深色网页）同步系统状态栏与导航栏颜色及图标明暗。 */
    private void applySystemBarTheme(boolean lightSurface, int surfaceColor) {
        if (destroyed) return;
        if (root != null) root.setBackgroundColor(surfaceColor);
        if (content != null && pageState.isReady()) content.setBackgroundColor(surfaceColor);
        Window window = getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(lightSurface ? mask : 0, mask);
            }
        } else {
            int flags = 0;
            if (lightSurface) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void closeThemeBridge() {
        themeGeneration++;
        if (themePort != null) {
            try {
                themePort.close();
            } catch (RuntimeException ignored) {
                // 渲染进程销毁后端口可能已关闭
            }
            themePort = null;
        }
    }

    /** 建立受一次性密钥和同源约束保护的主题同步端口，使无顶栏状态栏与页面明暗实时一致。 */
    private void installThemeBridge(final WebView view, final Uri origin) {
        closeThemeBridge();
        if (destroyed || view == null || origin == null || !isAppUrl(view.getUrl())) return;
        final long gen = themeGeneration;
        final Uri target = new Uri.Builder().scheme(origin.getScheme())
                .encodedAuthority(origin.getEncodedAuthority()).build();
        final String key = "__dsh_theme_connect__:" + UUID.randomUUID();
        view.evaluateJavascript(
                "(function(){if(window.__dshThemeRekey)window.__dshThemeRekey('" + key + "');})()",
                ignored -> {
                    if (destroyed || gen != themeGeneration || view != web || !isAppUrl(view.getUrl())) return;
                    WebMessagePort[] channel = null;
                    try {
                        channel = view.createWebMessageChannel();
                        final WebMessagePort incoming = channel[0];
                        themePort = incoming;
                        incoming.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                            @Override
                            public void onMessage(WebMessagePort port, WebMessage message) {
                                if (destroyed || gen != themeGeneration || themePort != port) return;
                                handleThemeMessage(message.getData());
                            }
                        });
                        view.postWebMessage(new WebMessage(key, new WebMessagePort[] { channel[1] }), target);
                    } catch (RuntimeException error) {
                        if (channel != null) {
                            try { channel[0].close(); } catch (RuntimeException ignored2) {}
                            try { channel[1].close(); } catch (RuntimeException ignored2) {}
                        }
                        if (gen == themeGeneration) themePort = null;
                        Log.w(TAG, "Cannot connect theme channel", error);
                    }
                });
    }

    private void handleThemeMessage(String data) {
        if (data == null || destroyed) return;
        final boolean dark;
        final String hex;
        if (data.startsWith("dark:")) {
            dark = true;
            hex = data.substring(5);
        } else if (data.startsWith("light:")) {
            dark = false;
            hex = data.substring(6);
        } else {
            return;
        }
        int parsed = dark ? SURFACE_DARK : SURFACE_LIGHT;
        if (hex.matches("#[0-9a-fA-F]{6}")) {
            try {
                parsed = Color.parseColor(hex);
            } catch (IllegalArgumentException ignored) {
                // 使用默认兜底色
            }
        }
        final int bgColor = parsed;
        runOnUiThread(() -> {
            if (destroyed) return;
            pageThemeDark = dark;
            pageSurfaceColor = bgColor;
            hasSyncedPageTheme = true;
            if (pageState.isReady()) {
                applySystemBarTheme(!dark, bgColor);
            }
        });
    }

    private void applyEdgeToEdge() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // 启动页是深海蓝底色，初始状态栏图标使用白色；页面就绪后随主题自动切换。
        applySystemBarTheme(false, SPLASH_BG_TOP);
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
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
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
                closeThemeBridge();
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
                closeThemeBridge();
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
                    installThemeBridge(view, appOrigin);
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
                revealWebView(view);
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
            String text = bootPhase + " · " + secs + "s";
            if (!text.equals(lastBootDetail)) {
                lastBootDetail = text;
                bootDetail.setText(text);
            }
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
        cancelRevealAnimation();
        applySystemBarTheme(false, SPLASH_BG_TOP);
        bootPanel.setAlpha(1f);
        bootPanel.setScaleX(1f);
        bootPanel.setScaleY(1f);
        retryButton.setVisibility(View.GONE);
        bootProgress.setVisibility(View.VISIBLE);
        bootTitle.setText("正在重新连接");
        bootSubtitle.setVisibility(View.VISIBLE);
        bootStatusPill.setBackground(buildStatusPillBackground(false));
        bootDetail.setTextColor(0xFFB7C7E0);
        bootDetail.setTextSize(12.5f);
        bootDetail.setMaxLines(Integer.MAX_VALUE);
        bootDetail.setTextIsSelectable(false);
        lastBootDetail = null;
        startSplashPulse();
        if (rendererGone || web == null) {
            web = new WebView(this);
            web.setBackgroundColor(initialWebSurfaceColor());
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
            cancelRevealAnimation();
            stopSplashPulse();
            applySystemBarTheme(false, SPLASH_BG_TOP);
            bootPanel.setAlpha(1f);
            bootPanel.setScaleX(1f);
            bootPanel.setScaleY(1f);
            bootProgress.setVisibility(View.GONE);
            bootPanel.setVisibility(View.VISIBLE);
            if (web != null) web.setVisibility(View.INVISIBLE);
            retryButton.setVisibility(View.VISIBLE);
            bootTitle.setText("暂时无法加载");
            bootSubtitle.setVisibility(View.GONE);
            bootStatusPill.setBackground(buildStatusPillBackground(true));
            StringBuilder sb = new StringBuilder(reason);
            String tail = tailOf(logFile, 2400);
            if (tail != null && !tail.isEmpty()) {
                sb.append("\n\n").append(tail);
            }
            lastBootDetail = null;
            bootDetail.setText(sb.toString());
            bootDetail.setTextSize(11.5f);
            bootDetail.setMaxLines(14);
            bootDetail.setTextColor(Color.parseColor("#ff9b93"));
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

    /** 页面上报：当前是否还有上一级（弹窗 / 菜单 / 设置 / 面板 / 侧栏 / 会话）。 */
    private void onPageCanBack(boolean canBack) {
        if (destroyed) return;
        inAppCanBack = canBack;
    }

    /**
     * 统一返回入口（硬件返回键与系统手势返回共用）。
     *
     * 顺序：输入法 → 弹出菜单 → 模态弹窗（选择工作目录/新建文件夹等）→ 设置 → 右栏 → 全局面板 → 左侧栏 → 会话 → 主界面。
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
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
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
        closeThemeBridge();
        if (bootThread != null) bootThread.interrupt();
        ui.removeCallbacksAndMessages(null);
        cancelRevealAnimation();
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
