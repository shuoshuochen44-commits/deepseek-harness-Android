package io.dsh.android;

import android.util.Log;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small, reversible Android presentation layer over the official client. */
final class MobileSkin {
    private static String installScript;

    private MobileSkin() {
    }

    /** Runs after each document load; the page-side installer is idempotent. */
    static void apply(WebView web) {
        if (web == null) return;
        try {
            web.evaluateJavascript(script(web), null);
        } catch (IOException error) {
            Log.e("DSH", "Could not load local mobile presentation", error);
        }
    }

    private static synchronized String script(WebView web) throws IOException {
        if (installScript == null) {
            String css = readAsset(web, "mobile/skin.css");
            String presentation = readAsset(web, "mobile/presentation.js");
            installScript = "(function(){"
                    + "document.documentElement.setAttribute('data-dsh-android','1');"
                    + "if(!document.getElementById('dsh-android-skin')){"
                    + "var s=document.createElement('style');s.id='dsh-android-skin';"
                    + "s.textContent=" + jsString(css) + ";"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + presentation + "})();";
        }
        return installScript;
    }

    private static String readAsset(WebView web, String path) throws IOException {
        try (InputStream input = web.getContext().getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String jsString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        result.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'': result.append("\\'"); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': break;
                case '\u2028': result.append("\\u2028"); break;
                case '\u2029': result.append("\\u2029"); break;
                default: result.append(c);
            }
        }
        return result.append('\'').toString();
    }
}
