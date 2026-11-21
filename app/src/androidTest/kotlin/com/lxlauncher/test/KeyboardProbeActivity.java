// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A text field, a button and an empty area in three flat colours, for scripts/ui_test_keyboard.py to find on a screenshot
 * and tap through an LX window. It logs what the app itself sees: its window focus, whether the keyboard covers it, each
 * press of the button and the text typed. Launched from LX's Applications menu, as the owner opens an app.
 *
 * Java, unlike the rest of the test APK: this one runs in a process of its own, where LX's Kotlin library is not loaded.
 */
public class KeyboardProbeActivity extends Activity {
    static final String TAG = "LxKeyProbe";
    static final int FIELD = 0xFFFF00FF;
    static final int BUTTON = 0xFF00C853;
    static final int EMPTY = 0xFF2962FF;

    private Boolean keyboard;
    protected EditText field;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        field = new EditText(this);
        field.setBackgroundColor(FIELD);
        field.setTextColor(Color.BLACK);
        field.setGravity(Gravity.TOP);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Log.i(TAG, "text " + s);
            }
        });
        Button button = new Button(this);
        button.setBackgroundColor(BUTTON);
        button.setText("Send");
        button.setOnClickListener(v -> Log.i(TAG, "pressed"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(EMPTY);
        root.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 35f));
        root.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 25f));
        root.addView(new LinearLayout(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 40f));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean shown = insets.isVisible(WindowInsets.Type.ime());
            if (keyboard == null || keyboard != shown) {
                keyboard = shown;
                Log.i(TAG, "keyboard " + shown);
            }
            return insets;
        });
        setContentView(root);
    }

    /** Every Back the app gets, with its flags: the navigation bar's carries FLAG_FROM_SYSTEM, one LX forwards does not. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "back " + (event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + " flags 0x" + Integer.toHexString(event.getFlags()));
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.i(TAG, "focus " + hasFocus);
    }

    /** The same screen opening with its field focused and the keyboard up, as a search page does. */
    public static class Auto extends KeyboardProbeActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            field.requestFocus();
        }
    }
}
