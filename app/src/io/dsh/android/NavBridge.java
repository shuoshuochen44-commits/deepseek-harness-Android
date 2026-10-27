package io.dsh.android;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Navigation reports use a page-specific port delivered only to the app origin. */
final class NavBridge {
    private static String installScript;
    private final Host host;
    private WebView expectedView;
    private WebMessagePort nativePort;
    private long generation;

    public interface Host {
        void onCanBack(boolean canBack);
    }

    NavBridge(Host host) {
        this.host = host;
    }

    /** Call on the UI thread after a trusted local page finishes loading. */
    void install(WebView web, Uri appOrigin) {
        close();
        if (web == null || appOrigin == null || !sameOrigin(web.getUrl(), appOrigin)) return;
        expectedView = web;
        final long installation = generation;
        final Uri target = new Uri.Builder().scheme(appOrigin.getScheme())
                .encodedAuthority(appOrigin.getEncodedAuthority()).build();
        final String connectionKey = "__dsh_nav_connect__:" + UUID.randomUUID();
        try {
            web.evaluateJavascript(script(web).replace("__DSH_NAV_CONNECTION__", connectionKey), ignored -> {
                if (generation != installation || expectedView != web ||
                        !sameOrigin(web.getUrl(), target)) return;
                WebMessagePort[] channel = null;
                try {
                    channel = web.createWebMessageChannel();
                    final WebMessagePort incoming = channel[0];
                    nativePort = incoming;
                    incoming.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                        @Override
                        public void onMessage(WebMessagePort port, WebMessage message) {
                            if (generation != installation || expectedView != web ||
                                    nativePort != port || !sameOrigin(web.getUrl(), target)) return;
                            String value = message.getData();
                            if ("true".equals(value)) host.onCanBack(true);
                            else if ("false".equals(value)) host.onCanBack(false);
                        }
                    });
                    web.postWebMessage(new WebMessage(connectionKey,
                            new WebMessagePort[]{channel[1]}), target);
                } catch (RuntimeException error) {
                    if (channel != null) {
                        closePort(channel[0]);
                        closePort(channel[1]);
                    }
                    if (generation == installation) nativePort = null;
                    Log.w("DSH", "Cannot connect navigation channel", error);
                }
            });
        } catch (IOException | RuntimeException error) {
            if (generation == installation) close();
            Log.w("DSH", "Cannot install navigation bridge", error);
        }
    }

    /** Invalidates pending installs and any reports from a previous document. */
    void close() {
        generation++;
        expectedView = null;
        closePort(nativePort);
        nativePort = null;
    }

    private static void closePort(WebMessagePort port) {
        if (port == null) return;
        try {
            port.close();
        } catch (RuntimeException ignored) {
            // A renderer loss or transferred endpoint may already have closed it.
        }
    }

    private static boolean sameOrigin(String url, Uri origin) {
        if (url == null || origin.getHost() == null || origin.getScheme() == null) return false;
        Uri actual = Uri.parse(url);
        String scheme = origin.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        return scheme.equalsIgnoreCase(actual.getScheme()) &&
                origin.getHost().equalsIgnoreCase(actual.getHost()) &&
                effectivePort(origin) == effectivePort(actual);
    }

    private static int effectivePort(Uri uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static synchronized String script(WebView web) throws IOException {
        if (installScript == null) {
            try (InputStream input = web.getContext().getAssets().open("mobile/navigation.js");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                installScript = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return installScript;
    }
}
