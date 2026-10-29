#pragma once

#include <functional>

/**
 * WebView on-connect flow controller.
 *
 * Ported concept from aurora-mp/omp-cef (browser UI shown to the player),
 * adapted to Android native WebView. On a successful server connection the
 * game shows an in-game WebView loading the server's HTML. The normal
 * "join" step is deferred until the page signals download/prep complete
 * (via JS bridge -> onWebViewDownloadComplete), or a timeout/error falls
 * back to joining anyway so the player is never hard-stuck.
 */
namespace WebViewFlow
{
    // The URL loaded in-game when connected. Overridable per server later.
    extern const char* kDefaultUrl;

    // true while the overlay is gating the join step.
    bool IsGating();

    // Begin the gated flow: show the WebView and hold the join.
    // onProceed is invoked exactly once when it is safe to continue joining.
    void BeginOnConnect(const char* url, std::function<void()> onProceed);

    // Fired from JNI when the page reports its download finished OK.
    void OnDownloadComplete();

    // Fired from JNI on a hard WebView load failure.
    void OnFailed(const char* message);

    // Must be polled each frame/tick to honour the fallback timeout.
    // dtMs = elapsed milliseconds since last call.
    void Tick(unsigned int dtMs);
}
