#include "jniutil.h"
#include "webview_flow.h"
#include <string>

extern CJavaWrapper* pJavaWrapper;

namespace WebViewFlow
{
    // Default in-game page. Point this at your server's connect page.
    const char* kDefaultUrl = "https://walang-whitelist.vercel.app/ingame";

    // Fallback: if the page never reports completion within this window,
    // proceed with the join anyway so players are never stuck.
    static const unsigned int kFallbackTimeoutMs = 20000;

    static bool                  s_gating      = false;
    static bool                  s_proceeded   = false;
    static unsigned int          s_elapsedMs   = 0;
    static std::function<void()> s_onProceed;

    static void ProceedOnce()
    {
        if (s_proceeded) return;
        s_proceeded = true;
        s_gating    = false;

        if (pJavaWrapper) pJavaWrapper->HideWebView();

        if (s_onProceed) {
            auto cb = s_onProceed;
            s_onProceed = nullptr;
            cb();
        }
    }

    bool IsGating()
    {
        return s_gating;
    }

    void BeginOnConnect(const char* url, std::function<void()> onProceed)
    {
        s_gating     = true;
        s_proceeded  = false;
        s_elapsedMs  = 0;
        s_onProceed  = std::move(onProceed);

        const char* target = (url && *url) ? url : kDefaultUrl;
        if (pJavaWrapper) pJavaWrapper->ShowWebView(target);
    }

    void OnDownloadComplete()
    {
        if (!s_gating) return;
        ProceedOnce();
    }

    void OnFailed(const char* /*message*/)
    {
        // Hard failure: don't trap the player, fall through to join.
        if (!s_gating) return;
        ProceedOnce();
    }

    void Tick(unsigned int dtMs)
    {
        if (!s_gating) return;
        s_elapsedMs += dtMs;
        if (s_elapsedMs >= kFallbackTimeoutMs) {
            ProceedOnce();
        }
    }
}

// C-linkage shims called from JNI exports in main.cpp
extern "C" void WebView_OnDownloadComplete()
{
    WebViewFlow::OnDownloadComplete();
}

extern "C" void WebView_OnFailed(const char* message)
{
    WebViewFlow::OnFailed(message);
}
