package com.rstarx.hexrays.game;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.joom.paranoid.Obfuscate;
import com.rstarx.hexrays.game.ui.AttachEdit;
import com.rstarx.hexrays.game.ui.CustomKeyboard;
import com.rstarx.hexrays.game.ui.LoadingScreen;
import com.rstarx.hexrays.game.ui.WebViewOverlay;
import androidx.activity.OnBackPressedCallback;
import com.rstarx.hexrays.game.ui.dialog.DialogManager;
import com.rstarx.hexrays.launcher.util.SharedPreferenceCore;
import com.rstarx.hexrays.launcher.util.SignatureChecker;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Obfuscate
public class SAMP extends GTASA implements CustomKeyboard.InputListener, HeightProvider.HeightListener {
    private static final String TAG = "SAMP";
    private static SAMP instance;

    private CustomKeyboard mKeyboard;
    private DialogManager mDialog;
    private HeightProvider mHeightProvider;

    private AttachEdit mAttachEdit;
    private LoadingScreen mLoadingScreen;
    private WebViewOverlay mWebView;

    public static SAMP getInstance() {
        return instance;
    }

    private void showTab() {}
    private void hideTab() {}
    private void setTab(int id, String name, int score, int ping) {}
    private void clearTab() {}

    private void showLoadingScreen() {}

    private void hideLoadingScreen() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLoadingScreen.hide();
            }
        });
    }

    public void setPauseState(boolean pause) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pause) {
                    mDialog.hideWithoutReset();
                    mAttachEdit.hideWithoutReset();
                } else {
                    if(mDialog.isShow) mDialog.showWithOldContent();
                    if(mAttachEdit.isShow) mAttachEdit.showWithoutReset();
                }
            }
        });
    }

    public void exitGame(){
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
        finishAndRemoveTask();
        System.exit(0);
    }

    public void showDialog(int dialogId, int dialogTypeId, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4) {
        final String caption = new String(bArr, StandardCharsets.UTF_8);
        final String content = new String(bArr2, StandardCharsets.UTF_8);
        final String leftBtnText = new String(bArr3, StandardCharsets.UTF_8);
        final String rightBtnText = new String(bArr4, StandardCharsets.UTF_8);
        runOnUiThread(() -> { this.mDialog.show(dialogId, dialogTypeId, caption, content, leftBtnText, rightBtnText); });
    }

    // ===== WebView overlay (called from native via CJavaWrapper) =====

    /** Show the in-game WebView and load the given URL. */
    public void showWebView(byte[] urlBytes) {
        final String url = new String(urlBytes, StandardCharsets.UTF_8);
        if (mWebView != null) mWebView.show(url);
    }

    /** Hide the in-game WebView. */
    public void hideWebView() {
        if (mWebView != null) mWebView.hide();
    }

    /** Push JS into the loaded page (e.g. progress updates). */
    public void evalWebView(byte[] jsBytes) {
        final String js = new String(jsBytes, StandardCharsets.UTF_8);
        if (mWebView != null) mWebView.eval(js);
    }

    /** Fired by the page (via JS bridge) when its download/prep finished OK. */
    private native void onWebViewDownloadComplete();

    /** Fired on a hard WebView load failure. */
    private native void onWebViewFailed(String message);

    private native void onInputEnd(byte[] str);
    @Override
    public void OnInputEnd(String str) {
        byte[] toReturn = null;
        try {
            toReturn = str.getBytes("windows-874");
        } catch(UnsupportedEncodingException e) {}

        try {
            onInputEnd(toReturn);
        } catch (UnsatisfiedLinkError e5) {
            Log.e(TAG, e5.getMessage());
        }
    }

    private void showKeyboard() {
        runOnUiThread(() -> {
            Log.d("AXL", "showKeyboard()");
            mKeyboard.ShowInputLayout();
        });
    }

    private void hideKeyboard() {
        runOnUiThread(() -> mKeyboard.HideInputLayout());
    }

    private void showEditObject() {
        runOnUiThread(() -> mAttachEdit.show());
    }

    private void hideEditObject() {
        runOnUiThread(() -> mAttachEdit.hide());
    }

    // =========================================================================
    // KHU VỰC SỬA LOGIC ONCREATE - SỬ DỤNG SHAREDPREFERENCES ĐỂ LƯU IP/PORT AN TOÀN
    // =========================================================================
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "**** onCreate");
        super.onCreate(savedInstanceState);

        if(!SignatureChecker.isSignatureValid(this, getPackageName())) {
            Toast.makeText(this, "Use original launcher! No remake", Toast.LENGTH_LONG).show();
            return;
        }

        mKeyboard = new CustomKeyboard(this);
        mDialog = new DialogManager(this);
        mAttachEdit = new AttachEdit(this);
        mLoadingScreen = new LoadingScreen(this);
        mWebView = new WebViewOverlay(this);
        mWebView.setListener(new WebViewOverlay.Listener() {
            @Override
            public void onWebViewReady() {
                Log.i(TAG, "WebView ready");
            }
            @Override
            public void onDownloadComplete() {
                Log.i(TAG, "WebView download complete -> notifying native");
                try { onWebViewDownloadComplete(); } catch (UnsatisfiedLinkError ignored) {}
            }
            @Override
            public void onWebViewError(String message) {
                Log.e(TAG, "WebView error: " + message);
                try { onWebViewFailed(message == null ? "" : message); } catch (UnsatisfiedLinkError ignored) {}
            }
        });
        instance = this;

        // 1. Nhận thông tin IP & Port từ HomeFragment truyền sang
        String ip = getIntent().getStringExtra("ip");
        int port = getIntent().getIntExtra("port", 7777);

        if (ip == null || ip.isEmpty()) {
            ip = "127.0.0.1"; // Dự phòng nếu không có dữ liệu dữ liệu truyền sang
        }

        try {
            initAssetManager(getAssets());

            // Đưa thanh trạng thái của LoadingScreen về 0% khi bắt đầu vào game
            runOnUiThread(() -> mLoadingScreen.setProgress(0));

            final String finalIp = ip;
            final int finalPort = port;

            // Chạy luồng phụ đếm tiến trình mượt mà từ 0 đến 100
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i <= 100; i++) {
                        final int currentProgress = i;
                        
                        // Cập nhật giao diện thanh Loading
                        runOnUiThread(() -> mLoadingScreen.setProgress(currentProgress));

                        try {
                            Thread.sleep(30); 
                        } catch (InterruptedException ignored) {}
                    }

                    // Sau khi thanh loading chạy hết 100% -> Thực hiện lưu IP động và kích hoạt game
                    runOnUiThread(() -> {
                        try {
                            // SỬA ĐỔI: Ghi trực tiếp thông số vào SharedPreferences mặc định của ứng dụng
                            SharedPreferences sp = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            sp.edit()
                              .putString("ip", finalIp)
                              .putInt("port", finalPort)
                              .apply();
                            
                            // Nạp native game engine
                            initializeSAMP(getExternalFilesDir(null).getAbsolutePath() + "/");
                        } catch (Exception e) {
                            Log.e(TAG, "Lỗi khi nạp Engine Game: " + e.getMessage());
                        }
                    });
                }
            }).start();

        } catch (UnsatisfiedLinkError e5) {
            Log.e(TAG, e5.getMessage());
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                onEventBackPressed();
                setEnabled(true);
            }
        });
    }

    private native void initializeSAMP(String storagePath);
    public native void initAssetManager(android.content.res.AssetManager assetManager);

    @Override
    public void onStart() {
        Log.i(TAG, "**** onStart");
        super.onStart();
    }

    @Override
    public void onRestart() {
        Log.i(TAG, "**** onRestart");
        super.onRestart();
    }

    @Override
    public void onResume() {
        Log.i(TAG, "**** onResume");
        super.onResume();
    }

    public native void onEventBackPressed();

    @SuppressLint("GestureBackNavigation")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK) {
            onEventBackPressed();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onPause() {
        Log.i(TAG, "**** onPause");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.i(TAG, "**** onStop");
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "**** onDestroy");
        super.onDestroy();
    }

    @Override
    public void onHeightChanged(int orientation, int height) {}
}
