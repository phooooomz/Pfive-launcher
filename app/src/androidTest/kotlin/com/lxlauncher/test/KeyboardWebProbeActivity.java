// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * The keyboard probe's screen drawn by a web page, for scripts/ui_test_keyboard_web.py. Browsers built on Chromium
 * (Chrome, Brave, and the WebView here) handle the phone's keyboard in their own code: the owner's Brave in an LX window
 * put its keyboard away after the first key. The page has the same three flat colours as {@link KeyboardProbeActivity}
 * and logs, under the same tag, what the app sees: its window focus, the keyboard covering it, and from the page itself
 * the field's focus and blur, each text change and each press of the button.
 *
 * The window asks for the keyboard the way Chrome's does (always hidden until a field asks, resized above it). Nothing is
 * loaded from the network.
 */
public class KeyboardWebProbeActivity extends Activity {
    private static final String PAGE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
        + "<style>html,body{margin:0;height:100%;background:#2962ff}"
        + "textarea{display:block;box-sizing:border-box;width:100%;height:35vh;margin:0;border:0;background:#ff00ff;color:#000;font-size:18px}"
        + "button{display:block;width:100%;height:25vh;border:0;background:#00c853;font-size:18px}</style>"
        // No corrections or suggestions: the test types runs of letters, which the keyboard need not learn.
        + "<textarea id=f autocomplete=off autocorrect=off autocapitalize=off spellcheck=false></textarea><button id=b>Send</button>"
        + "<script>"
        + "f.addEventListener('input',function(){console.log('text '+f.value)});"
        + "f.addEventListener('focus',function(){console.log('field focus')});"
        + "f.addEventListener('blur',function(){console.log('field blur')});"
        + "b.addEventListener('click',function(){console.log('pressed')});"
        + "</script>";

    private Boolean keyboard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(KeyboardProbeActivity.TAG, m.message());
                return true;
            }
        });
        web.loadDataWithBaseURL("about:blank", PAGE, "text/html", "utf-8", null);
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean shown = insets.isVisible(WindowInsets.Type.ime());
            if (keyboard == null || keyboard != shown) {
                keyboard = shown;
                Log.i(KeyboardProbeActivity.TAG, "keyboard " + shown);
            }
            return v.onApplyWindowInsets(insets);
        });
        setContentView(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.i(KeyboardProbeActivity.TAG, "focus " + hasFocus);
    }
}
