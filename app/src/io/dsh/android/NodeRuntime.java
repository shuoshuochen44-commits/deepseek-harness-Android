package io.dsh.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 持有内嵌 Node 运行时进程：挑选空闲回环端口、拉起 dsh web、转发日志、健康探测。
 *
 * 进程可执行文件来自 APK 的 nativeLibraryDir（Android 10+ 只允许从该目录执行自带二进制），
 * 因此 Node 二进制在打包时被重命名为 libdshnode.so。
 */
public final class NodeRuntime {
    private static final String TAG = "DshNodeRuntime";
    private static final String NODE_BINARY = "libdshnode.so";

    private static NodeRuntime instance;

    private final Context appContext;
    private Process process;
    private int port;
    private Thread stdoutPump;
    private Thread stderrPump;
    private volatile boolean stopping;
    /** dsh 启动后打印的带 token 的访问地址；token 由内核随机生成，不能自行拼接。 */
    private volatile String webUrl;
    /** URL 就绪或进程停止时立即唤醒 waitReady，避免固定轮询空等。 */
    private final Object readyLock = new Object();
    private static final java.util.regex.Pattern URL_LINE =
            java.util.regex.Pattern.compile("dsh web:\\s*(http://\\S+)");

    private NodeRuntime(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static synchronized NodeRuntime get(Context ctx) {
        if (instance == null) {
            instance = new NodeRuntime(ctx);
        }
        return instance;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized int port() {
        return port;
    }

    public synchronized String url() {
        String u = webUrl;
        if (u != null) {
            return u;
        }
        return "http://127.0.0.1:" + port + "/";
    }

    /** 内核重启后 URL（含 token）会变，交给 Activity 重新加载。 */
    public interface UrlListener {
        void onUrlChanged(String url);
    }

    private volatile UrlListener urlListener;

    public synchronized void setUrlListener(UrlListener listener) {
        this.urlListener = listener;
    }

    /** An old Activity must not clear a listener installed by its replacement. */
    public synchronized void clearUrlListener(UrlListener listener) {
        if (urlListener == listener) urlListener = null;
    }

    private void publishUrl(String url) {
        webUrl = url;
        synchronized (readyLock) {
            readyLock.notifyAll();
        }
        UrlListener l = urlListener;
        if (l != null) {
            l.onUrlChanged(url);
        }
    }

    /** 从已发布的 URL 里取回监听端口，供进程重启时复用。 */
    public int currentPort() {
        String u = webUrl;
        if (u == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\d+)/").matcher(u);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public String nativeDir() {
        ApplicationInfo info = appContext.getApplicationInfo();
        return info.nativeLibraryDir;
    }

    public File nativeBinary() {
        return new File(nativeDir(), NODE_BINARY);
    }

    /**
     * 拉起进程；已运行则直接返回。
     */
    public synchronized void start(int listenPort) throws IOException {
        if (isRunning()) {
            return;
        }
        RuntimeInstaller installer = new RuntimeInstaller(appContext);
        File runtime = installer.runtimeRoot();
        File entry = new File(runtime, "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!entry.isFile()) {
            throw new IOException("运行时缺失: " + entry);
        }
        File binary = nativeBinary();
        if (!binary.isFile()) {
            throw new IOException("Node 运行时缺失: " + binary);
        }

        port = listenPort;
        webUrl = null;
        List<String> argv = new ArrayList<>();
        argv.add(binary.getAbsolutePath());
        // dsh 的 profile 解析要拿 internal/modules/*，替身 shim 依赖这个 flag
        argv.add("--expose-internals");
        argv.add("--max-old-space-size=2048");
        argv.add(entry.getAbsolutePath());
        argv.addAll(launchArgs(installer, listenPort));

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(installer.workspace());
        pb.redirectErrorStream(false);

        // 工具链：bash / rg 作为 lib*.so 放在 nativeLibraryDir（那里可执行），
        // 在 filesDir/bin 下建同名软链，再把该目录放进 PATH。
        File toolDir = new File(appContext.getFilesDir(), "bin");
        linkTools(toolDir, new String[][] {
                { "bash", "libdshbash.so" },
                { "rg", "libdshrg.so" },
                { "node", NODE_BINARY },
        });

        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", installer.home().getAbsolutePath());
        env.put("TMPDIR", installer.tmp().getAbsolutePath());
        env.put("TMP", installer.tmp().getAbsolutePath());
        env.put("PATH", toolDir.getAbsolutePath() + ":" + nativeDir()
                + ":/system/bin:/system/xbin:/vendor/bin");
        // 共享库随 payload 解压到私有目录（避免依赖 APK lib/ 对 lib*.so.N 命名的处理）
        File libDir = new File(runtime, "native");
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + nativeDir());
        env.put("DSH_RUNTIME_ROOT", runtime.getAbsolutePath());
        env.put("DSH_ANDROID", "1");
        env.put("DSH_ANDROID_PORT", String.valueOf(listenPort));
        // DSH 的家庭目录由 DSH_HOME 指定（不依赖 HOME/homedir）
        env.put("DSH_HOME", new File(installer.home(), ".dsh").getAbsolutePath());
        env.put("DSH_AGENTS_HOME", new File(installer.home(), ".agents").getAbsolutePath());
        // DSH 的 glob/grep 工具走 @vscode/ripgrep，安卓没这个平台包，用自带的 rg
        env.put("DSH_RG_PATH", new File(toolDir, "rg").getAbsolutePath());
        // Termux 版 Node 链接自带 OpenSSL，默认信任库路径是 Termux 前缀下的
        // etc/tls/cert.pem —— 手机上并不存在。不显式指过去，所有 https 请求都会
        // 因「拿不到签发者」失败（内核表现为 DeepSeek TRANSPORT / Messages transport failed）。
        File caFile = new File(runtime, "certs/cert.pem");
        if (caFile.isFile()) {
            env.put("SSL_CERT_FILE", caFile.getAbsolutePath());
            env.put("NODE_EXTRA_CA_CERTS", caFile.getAbsolutePath());
        } else {
            Log.w(TAG, "CA bundle missing: " + caFile);
        }
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("NODE_OPTIONS", "--no-warnings");
        String tz = System.getProperty("user.timezone");
        if (tz != null && !tz.isEmpty()) {
            env.put("TZ", tz);
        }

        File logFile = new File(installer.logs(), "node.log");
        Log.i(TAG, "launching: " + String.join(" ", argv));
        process = pb.start();
        stopping = false;
        pump(process.getInputStream(), logFile, "OUT");
        pump(process.getErrorStream(), logFile, "ERR");
    }

    /**
     * 在私有目录建软链指向 nativeLibraryDir 里的可执行文件。
     * Android 10+ 禁止直接执行私有目录中的文件，但软链的**最终目标**在 lib 目录里，
     * 内核按最终 inode 校验，因此可以正常 execve。
     */
    private void linkTools(File toolDir, String[][] tools) {
        if (!toolDir.exists() && !toolDir.mkdirs()) {
            Log.w(TAG, "cannot create tool dir " + toolDir);
            return;
        }
        for (String[] pair : tools) {
            File target = new File(nativeDir(), pair[1]);
            File link = new File(toolDir, pair[0]);
            try {
                if (!target.isFile()) {
                    continue;
                }
                java.nio.file.Path linkPath = link.toPath();
                java.nio.file.Path targetPath = target.toPath();
                boolean stale = true;
                if (java.nio.file.Files.isSymbolicLink(linkPath)) {
                    // 覆盖安装后 /data/app/~~<hash>/ 会变，旧软链指向已不存在的目录，
                    // 表现为 exec "No such file or directory"。所以必须校验并重建。
                    try {
                        stale = !java.nio.file.Files.readSymbolicLink(linkPath).equals(targetPath)
                                || !link.exists();
                    } catch (Exception e) {
                        stale = true;
                    }
                    if (stale) {
                        java.nio.file.Files.deleteIfExists(linkPath);
                    }
                }
                if (java.nio.file.Files.isSymbolicLink(linkPath)) {
                    continue; // 已存在且指向正确
                }
                if (link.exists()) {
                    continue; // 真实文件，别覆盖
                }
                java.nio.file.Files.createSymbolicLink(linkPath, targetPath);
                Log.i(TAG, "linked " + link + " -> " + target);
            } catch (Exception e) {
                Log.w(TAG, "link failed for " + pair[0] + ": " + e);
            }
        }
    }

    private List<String> launchArgs(RuntimeInstaller installer, int listenPort) {
        List<String> args = new ArrayList<>();
        args.add("web");
        // 不打开系统浏览器（Android 上会变成"跳出去"）
        args.add("--no-open");
        args.add("--host");
        args.add("127.0.0.1");
        args.add("--port");
        args.add(String.valueOf(listenPort));
        return args;
    }

    private void pump(final InputStream stream, final File logFile, final String tag) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                 OutputStream out = new FileOutputStream(logFile, true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, tag + " " + line);
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if ("OUT".equals(tag)) {
                        java.util.regex.Matcher m = URL_LINE.matcher(line);
                        if (m.find()) {
                            publishUrl(m.group(1));
                            Log.i(TAG, "web url ready: " + m.group(1));
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程退出或日志写入失败都不该拖垮宿主
            }
        }, "dsh-node-" + tag.toLowerCase());
        t.setDaemon(true);
        t.start();
        if ("OUT".equals(tag)) {
            stdoutPump = t;
        } else {
            stderrPump = t;
        }
    }

    public synchronized void stop() {
        stopping = true;
        if (process != null) {
            process.destroy();
            process = null;
        }
        synchronized (readyLock) {
            readyLock.notifyAll();
        }
    }

    public static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 本机固定端口候选。
     *
     * 登录 cookie 的 audience 是 `127.0.0.1:<端口>`，端口一变旧 cookie 立即失效，
     * 于是每次内核重启都要重新登录。所以优先复用上次成功过的端口，
     * 其次在候选里挑空闲的，实在不行才让内核自己挑（--port 0）。
     */
    private static final int[] PORT_CANDIDATES = { 61132, 61133, 61134, 61135 };

    public static int preferredPort(Context ctx) {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("dsh", 0);
        int saved = prefs.getInt("webPort", 0);
        if (saved > 0 && isPortFree(saved)) {
            android.util.Log.i(TAG, "reusing saved port " + saved);
            return saved;
        }
        for (int candidate : PORT_CANDIDATES) {
            if (isPortFree(candidate)) {
                return candidate;
            }
        }
        android.util.Log.w(TAG, "no candidate port free, letting the kernel choose");
        return 0;
    }

    public static void rememberPort(Context ctx, int port) {
        if (port <= 0) {
            return;
        }
        ctx.getSharedPreferences("dsh", 0).edit().putInt("webPort", port).apply();
    }

    /**
     * 探一次根路径：只要拿到任意 &lt; 500 的 HTTP 响应就认为 web 服务已经能接受连接。
     */
    public boolean probe(int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            String base = url().replaceAll("\\?.*$", "");
            URL u = new URL(base);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code > 0 && code < 500;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public boolean waitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                return false;
            }
            if (webUrl != null && probe(1500)) {
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            long waitMs = Math.min(remaining, webUrl == null ? 200L : 120L);
            synchronized (readyLock) {
                if (webUrl == null && isRunning()) {
                    try {
                        readyLock.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    continue;
                }
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
