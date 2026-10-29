package com.rstarx.hexrays.game.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Native Android WebView overlay drawn on top of the GL game surface.
 *
 * Ported concept from aurora-mp/omp-cef (Overlay2D browser) but implemented
 * with a real Android WebView instead of desktop CEF, since CEF/DX9 do not
 * exist on Android (ARM + OpenGL ES).
 *
 * Controlled from native code via CJavaWrapper (showWebView / hideWebView).
 * A JS bridge object "WalangBridge" is exposed to the page so in-game HTML
 * can report lifecycle + download-complete back into the native layer.
 */
public class WebViewOverlay {

    public interface Listener {
        /** Page finished loading successfully. */
        void onWebViewReady();
        /** Page (or JS) signalled that its download/preparation finished OK. */
        void onDownloadComplete();
        /** Hard failure (load error) — native side may fall back to joining. */
        void onWebViewError(String message);
    }

    private final Activity activity;
    private FrameLayout container;
    private WebView webView;
    private Listener listener;
    public boolean isShow = false;

    public WebViewOverlay(Activity activity) {
        this.activity = activity;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureCreated() {
        if (container != null) return;

        container = new FrameLayout(activity);
        container.setBackgroundColor(Color.TRANSPARENT);

        webView = new WebView(activity);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Transparent so the game shows through unless the page paints a bg.
        webView.setBackgroundColor(Color.TRANSPARENT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (listener != null) listener.onWebViewReady();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only report main-frame failures.
                if (request != null && request.isForMainFrame()) {
                    String msg = (error != null) ? String.valueOf(error.getDescription()) : "load error";
                    if (listener != null) listener.onWebViewError(msg);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // File downloads triggered inside the page go to system DownloadManager.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).getLastPathSegment());
                DownloadManager dm = (DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(req);
            } catch (Exception ignored) {}
        });

        // JS <-> native bridge. Pages call window.WalangBridge.*
        webView.addJavascriptInterface(new Bridge(), "WalangBridge");

        container.addView(webView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER));

        container.setVisibility(View.GONE);
        activity.addContentView(container,
                new FrameLayout.LayoutParams(-1, -1));
    }

    /** Show the overlay and load the given URL. Safe to call from any thread. */
    public void show(final String url) {
        activity.runOnUiThread(() -> {
            ensureCreated();
            isShow = true;
            webView.loadUrl(url);
            container.setVisibility(View.VISIBLE);
            container.bringToFront();
            webView.requestFocus();
        });
    }

    /** Hide the overlay (keeps the instance for reuse). */
    public void hide() {
        activity.runOnUiThread(() -> {
            if (container != null) {
                container.setVisibility(View.GONE);
            }
            if (webView != null) {
                webView.loadUrl("about:blank");
                webView.clearFocus();
            }
            isShow = false;
        });
    }

    /** Run arbitrary JS in the loaded page (e.g. to push progress in). */
    public void eval(final String js) {
        activity.runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void downloadComplete() {
            if (listener != null) listener.onDownloadComplete();
        }

        @JavascriptInterface
        public void close() {
            hide();
        }

        @JavascriptInterface
        public void reportError(String message) {
            if (listener != null) listener.onWebViewError(message);
        }
    }
}
