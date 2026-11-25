// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.lxlauncher.R
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.embed.ActivityEnd
import com.lxlauncher.embed.EmbedBinderProvider
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.embed.EmbedKeys
import com.lxlauncher.embed.EmbedProtocol
import com.lxlauncher.embed.EmbeddedAppTool
import com.lxlauncher.embed.IEmbedService
import com.lxlauncher.embed.ShellHelper

/**
 * Checks for Android apps in LX windows that need no lxdb running: which keys reach the app, how its input is
 * copied for the display, where it opens and what LX says when it cannot, and who may hand LX a daemon's
 * binder. [onMain] runs a
 * block on the main thread, where views are built.
 */
internal fun embedChecks(ctx: Context, check: (String, () -> String?) -> Unit, onMain: (() -> String?) -> String?) {
    check("an app window gets typed keys only while no LX control of its own has focus") {
        onMain {
            val root = FrameLayout(ctx)
            val desktopField = EditText(ctx)
            val barButton = Button(ctx)
            val layer = FrameLayout(ctx)
            val behind = FrameLayout(ctx)
            val behindField = EditText(ctx)
            val frame = FrameLayout(ctx)
            val closeButton = Button(ctx)
            val content = FrameLayout(ctx)
            val appSurface = View(ctx)
            root.addView(desktopField)
            root.addView(barButton)
            root.addView(layer)
            layer.addView(behind)
            behind.addView(behindField)
            layer.addView(frame)
            frame.addView(closeButton)
            frame.addView(content)
            content.addView(appSurface)
            fun toApp(focus: View?) = EmbeddedAppTool.keysGoToApp(focus, layer, frame, content)
            when {
                !toApp(null) -> "with nothing focused the app got no keys"
                !toApp(appSurface) -> "the app's own view lost the keys"
                !toApp(frame) -> "the app window's frame lost the keys"
                toApp(desktopField) -> "a text field on the desktop lost its keys to the app"
                toApp(barButton) -> "keyboard navigation in the bar went to the app"
                toApp(closeButton) -> "the window's close button lost Enter to the app"
                !toApp(behindField) -> "focus left in a window behind kept the keys from the app"
                else -> null
            }
        }
    }

    check("keys that act on the whole phone are neither forwarded nor injected") {
        val down = KeyEvent.ACTION_DOWN
        val up = KeyEvent.ACTION_UP
        val local = listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_PASTE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK)
        val global = listOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP,
            KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_SCREENSHOT,
            KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT, KeyEvent.KEYCODE_ALL_APPS,
            KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_EXPLORER, KeyEvent.KEYCODE_CALCULATOR, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MENU)
        val refused = local.filter { !EmbedKeys.allowed(it, 0, down) || !EmbedKeys.allowed(it, 0, up) }
        val passed = global.filter { EmbedKeys.allowed(it, 0, down) || EmbedKeys.allowed(it, 0, up) }
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // Deprecated, and still what a hostile caller could send.
        @Suppress("DEPRECATION") val multiple = KeyEvent.ACTION_MULTIPLE
        when {
            refused.isNotEmpty() -> "keys that stay in the app were refused: ${refused.map(KeyEvent::keyCodeToString)}"
            passed.isNotEmpty() -> "keys for the whole phone passed: ${passed.map(KeyEvent::keyCodeToString)}"
            EmbedKeys.allowed(KeyEvent.KEYCODE_A, meta, down) -> "a Meta shortcut passed"
            EmbedKeys.allowed(KeyEvent.KEYCODE_TAB, alt, down) -> "Alt+Tab passed"
            !EmbedKeys.allowed(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, down) -> "Shift+Tab was refused"
            !EmbedKeys.allowed(KeyEvent.KEYCODE_C, ctrl, down) -> "Ctrl+C was refused"
            !EmbedKeys.allowed(KeyEvent.KEYCODE_A, meta, up) -> "the release of a key held under Meta was refused"
            EmbedKeys.allowed(KeyEvent.KEYCODE_A, 0, multiple) -> "ACTION_MULTIPLE passed"
            else -> null
        }
    }

    check("a forwarded touch keeps its history and lands at display scale after the binder copy") {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        fun at(x: Float, y: Float) = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        // Screen coordinates, moved into a view at (100, 200) as a parent does before the view's listener sees them.
        val move = MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 1, props, at(110f, 220f), 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        move.addBatch(t + 24, at(130f, 240f), 0)
        move.offsetLocation(-100f, -200f)
        val copy = EmbeddedAppTool.displayEvent(move, 2f, 0.5f)
        val parcel = Parcel.obtain()
        copy.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val sent = MotionEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        val problem = when {
            sent.historySize != 1 -> "history has ${sent.historySize} samples"
            sent.getHistoricalX(0) != 20f || sent.getHistoricalY(0) != 10f -> "first sample at ${sent.getHistoricalX(0)},${sent.getHistoricalY(0)}"
            sent.x != 60f || sent.y != 20f -> "last sample at ${sent.x},${sent.y}"
            sent.getHistoricalEventTime(0) != t + 16 || sent.eventTime != t + 24 -> "sample times changed"
            sent.source != InputDevice.SOURCE_TOUCHSCREEN || sent.action != MotionEvent.ACTION_MOVE -> "source or action changed"
            move.x != 30f -> "the original event was changed"
            else -> null
        }
        listOf(move, copy, sent).forEach { it.recycle() }
        problem
    }

    check("a tap lands on the app where the window draws it: fitted, held during a resize, or showing the top") {
        // A 720x1600 display in a view 600x1000. Each transform is one the window sets on its view; a point of the
        // display drawn through it must come back to the same point of the display.
        val vw = 600
        val vh = 1000
        val dw = 720
        val dh = 1600
        val k = minOf(vw.toFloat() / dw, vh.toFloat() / dh)
        val states = listOf(
            "fitted" to android.graphics.Matrix(),
            "held in shape" to android.graphics.Matrix().apply { setScale(dw * k / vw, dh * k / vh) },
            "showing the top" to android.graphics.Matrix().apply { setScale(1f, dh.toFloat() * vw / dw / vh) },
        )
        val problems = mutableListOf<String>()
        for ((name, drawn) in states) {
            val toDisplay = android.graphics.Matrix()
            if (!EmbeddedAppTool.viewToDisplay(drawn, vw, vh, dw, dh, toDisplay)) {
                problems += "$name: no inverse"
                continue
            }
            for ((x, y) in listOf(360f to 1500f, 10f to 1590f, 700f to 40f)) {
                // Where the view draws display point (x, y): stretched over the view, then the transform.
                val p = floatArrayOf(x * vw / dw, y * vh / dh)
                drawn.mapPoints(p)
                val t = SystemClock.uptimeMillis()
                val tap = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p[0], p[1], 0)
                val sent = EmbeddedAppTool.displayEvent(tap, toDisplay)
                if (Math.abs(sent.x - x) > 0.5f || Math.abs(sent.y - y) > 0.5f) problems += "$name: ($x, $y) came to (${sent.x}, ${sent.y})"
                tap.recycle()
                sent.recycle()
            }
        }
        problems.firstOrNull()
    }

    check("a forwarded mouse button press keeps its button") {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = 150f
            y = 260f
        })
        val press = MotionEvent.obtain(t, t, MotionEvent.ACTION_BUTTON_PRESS, 1, props, coords, 0, MotionEvent.BUTTON_SECONDARY,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        // No public API sets the button behind a press; Android's input pipeline does. The setter is a hidden
        // test API that Android 15 lets apps reach ("unsupported,test-api"); a later release may block it.
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(press, MotionEvent.BUTTON_SECONDARY)
        val copy = EmbeddedAppTool.displayEvent(press, 2f, 2f)
        val parcel = Parcel.obtain()
        copy.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val sent = MotionEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        val problem = when {
            sent.actionButton != MotionEvent.BUTTON_SECONDARY -> "button behind the press is ${sent.actionButton}"
            sent.buttonState != MotionEvent.BUTTON_SECONDARY -> "button state is ${sent.buttonState}"
            sent.x != 300f || sent.y != 520f -> "press at ${sent.x},${sent.y}"
            else -> null
        }
        listOf(press, copy, sent).forEach { it.recycle() }
        problem
    }

    check("apps keep Android windows when they are games, on the user's list or have a key too long to keep") {
        val key = "com.example.notes/com.example.notes.Main"
        val ready = EmbedClient.Status.READY
        val lx = EmbedClient.Route.LX_WINDOW
        fun route(frame: String = "lx", status: EmbedClient.Status = ready, game: Boolean = false, always: Boolean = false,
                  k: String = key) = EmbedClient.route(frame, status, game, always, k)
        val longest = "a/" + "b".repeat(126)
        when {
            route() != lx -> "an ordinary app did not get an LX window"
            route(frame = "system") == lx -> "the Android frame setting was ignored"
            route(status = EmbedClient.Status.NOT_SET_UP) != lx -> "an app not yet set up never reached the window that sets it up"
            route(status = EmbedClient.Status.NEEDS_WIFI) != lx -> "waiting for Wi-Fi sent the app past the LX frame"
            route(status = EmbedClient.Status.UNSUPPORTED) == lx -> "an LX window on an Android too old for it"
            route(game = true) == lx -> "a game got an LX window"
            route(always = true) == lx -> "an app on the Android list got an LX window"
            route(k = longest) != lx -> "a key of 128 bytes was refused"
            route(k = longest + "c") == lx -> "a key of 129 bytes got an LX window"
            else -> null
        }
    }

    check("a window too small for a phone layout shows the whole app, scaled down") {
        val fit = EmbeddedAppTool.displaySize(656, 1084, 320)
        val small = EmbeddedAppTool.displaySize(400, 600, 320)
        val wide = EmbeddedAppTool.displaySize(800, 400, 320)
        when {
            fit != (656 to 1084) -> "a window big enough was resized: $fit"
            small.first < 640 || small.second < 960 -> "a small portrait window got no phone layout: $small"
            kotlin.math.abs(small.first * 600 - small.second * 400) > 600 -> "the scaled display changed shape: $small"
            wide.second < 640 -> "a short wide window got no phone layout: $wide"
            else -> null
        }
    }

    check("an app that cannot turn with its display is drawn whole in any window") {
        val portrait = AppRepository.WindowShape.PORTRAIT
        val landscape = AppRepository.WindowShape.LANDSCAPE
        val any = AppRepository.WindowShape.ANY
        val wide = EmbeddedAppTool.fit(800, 600, 320, portrait)
        val tall = EmbeddedAppTool.fit(680, 1100, 320, landscape)
        when {
            EmbeddedAppTool.fit(800, 600, 320, any) != EmbeddedAppTool.Fit(853, 640, 800, 600) -> "a free app did not fill the window"
            EmbeddedAppTool.fit(680, 1100, 320, portrait).drawH != 1100 -> "a tall window cut a portrait app short"
            wide.displayH <= wide.displayW -> "a portrait app got a wide display: $wide"
            wide.drawH != 600 || wide.drawW > 800 -> "a portrait app does not fit the window: $wide"
            tall.displayW <= tall.displayH -> "a landscape app got a tall display: $tall"
            tall.drawW != 680 || tall.drawH > 1100 -> "a landscape app does not fit the window: $tall"
            else -> null
        }
    }

    check("a window that cannot use the LX frame names the real cause") {
        val notice = EmbedClient.routeNotice(EmbedClient.Status.UNSUPPORTED)
        when {
            EmbeddedAppTool.failureMessage(EmbedProtocol.ERR_LIMIT, 0) != R.string.embed_display_limit -> "LX's display limit blamed on Android"
            EmbeddedAppTool.failureMessage(EmbedProtocol.ERR_DISPLAY, 0) != R.string.embed_display_failed -> "refused display not reported"
            EmbeddedAppTool.failureMessage(EmbedProtocol.ERR_FAILED, 0) != R.string.embed_display_failed -> "failed create not reported"
            EmbeddedAppTool.failureMessage(EmbedProtocol.ERR_UNSUPPORTED, 0) != R.string.embed_display_failed -> "an unsupported Android not reported"
            EmbeddedAppTool.failureMessage(3, EmbedProtocol.ERR_USER) != R.string.embed_start_failed -> "refused start not reported"
            EmbeddedAppTool.failureMessage(3, -91) != R.string.embed_start_failed -> "Android's start error not reported"
            notice != R.string.embed_route_old_android -> "Android older than 14 not named"
            EmbedClient.routeNotice(EmbedClient.Status.NOT_SET_UP) != null -> "a notice although the window offers the setup"
            EmbedClient.routeNotice(EmbedClient.Status.READY) != null -> "a notice although LX frames work"
            else -> null
        }
    }

    check("an app window ends once with its own activity and not with another") {
        onMain {
            val app = ctx.applicationContext as Application
            val mine = Activity()
            val other = Activity()
            var ended = 0
            val end = ActivityEnd(app, mine) { ended++ }
            end.onActivityDestroyed(other)
            val afterOther = ended
            end.onActivityDestroyed(mine)
            end.onActivityDestroyed(mine)
            val afterMine = ended
            var cancelledEnded = 0
            val cancelled = ActivityEnd(app, mine) { cancelledEnded++ }
            cancelled.cancel()
            cancelled.onActivityDestroyed(mine)
            when {
                afterOther != 0 -> "ended with another activity"
                afterMine != 1 -> "ended $afterMine times with its own activity"
                cancelledEnded != 0 -> "ended after it was cancelled"
                else -> null
            }
        }
    }

    check("the shell line for lxdb names the code in LX's own APK") {
        val token = ShellHelper.newToken()
        val apk = "/data/app/~~a==/com.lxlauncher-b==/base.apk"
        val library = ShellHelper.library(apk, "arm64-v8a", null)
        val line = ShellHelper.command(apk, library, token, 10)
        val quoted = runCatching { ShellHelper.command("/data/app/it's/base.apk", library, token, 10) }.isFailure
        when {
            !line.startsWith("/system/bin/linker64 '$apk!/lib/arm64-v8a/liblxdb.so' daemon ") -> "line $line"
            !line.endsWith(" daemon $token 10 ${ctx.packageName} '$apk'") -> "token, user or package missing: $line"
            !ShellHelper.isToken(token) || token.length != 32 -> "token $token"
            ShellHelper.isToken("0123456789ABCDEF0123456789ABCDEF") || ShellHelper.isToken("12ab") -> "a malformed token passed"
            ShellHelper.newToken() == token -> "two starts got the same token"
            !quoted -> "an APK path with a quote reached the shell"
            else -> null
        }
    }

    check("the library lxdb starts from is one the system linker can open on this phone") {
        // Every start of lxdb failed on 12 November 2026: the build stored the library compressed in the APK, and the
        // linker opens a library in an APK only when it is stored as is. LX now starts the unpacked copy when there is one.
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: return@check "no 64-bit ABI on this phone"
        val library = ShellHelper.library(info.sourceDir, abi, info.nativeLibraryDir)
        if (!library.contains("!/")) {
            if (java.io.File(library).isFile) null else "$library is missing"
        } else java.util.zip.ZipFile(info.sourceDir).use { zip ->
            val entry = zip.getEntry(library.substringAfter("!/"))
            when {
                entry == null -> "the APK has no ${library.substringAfter("!/")}"
                entry.method != java.util.zip.ZipEntry.STORED -> "${entry.name} is compressed in the APK and nothing is unpacked"
                else -> null
            }
        }
    }

    check("lxdb's parcels are written by this phone's framework and keep what lxdb needs") {
        val target = ComponentName("com.example", "com.example.Main")
        val intent = Parcel.obtain().run {
            val bytes = EmbedProtocol.launchIntent(target)
            unmarshall(bytes, 0, bytes.size)
            setDataPosition(0)
            Intent.CREATOR.createFromParcel(this).also { recycle() }
        }
        val options = Parcel.obtain().run {
            val bytes = EmbedProtocol.launchOptions(7)
            unmarshall(bytes, 0, bytes.size)
            setDataPosition(0)
            Bundle.CREATOR.createFromParcel(this).also { recycle() }
        }
        val flags = EmbedProtocol.flagChoices()
        val result = EmbedProtocol.shellResult(byteArrayOf(0, 0, 1, 2, 'o'.code.toByte(), 'k'.code.toByte()))
        when {
            intent.component != target || intent.action != Intent.ACTION_MAIN -> "intent $intent"
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0 -> "the app would not start a task"
            options.getInt("android.activity.launchDisplayId", -1) != 7 -> "options name no display"
            options.getInt("android.activity.windowingMode", 0) != 1 -> "options leave the windowing mode open"
            flags.size != 3 || flags != flags.sortedDescending() -> "flags do not fall back: $flags"
            EmbedProtocol.displayLabel("app 1/../x") != "lx-app1x" -> "label ${EmbedProtocol.displayLabel("app 1/../x")}"
            EmbedProtocol.displayLabel("") != "lx-window" -> "an empty name"
            result?.exit != 258 || result.text != "ok" -> "shell result ${result?.exit} ${result?.text}"
            EmbedProtocol.shellResult(byteArrayOf(1)) != null -> "a short reply read as a result"
            else -> null
        }
    }

    check("LX Bridge takes lxdb only from the shell or root, with this boot's token") {
        val client = EmbedClient.get(ctx)
        val apk = ctx.applicationInfo.sourceDir
        val binder = Binder().apply { attachInterface(null, IEmbedService.DESCRIPTOR) }
        when {
            client.helperAttach(10_123, ShellHelper.newToken(), binder, apk) != null -> "an app uid got in"
            client.helperAttach(2_000, ShellHelper.newToken(), binder, apk) != null -> "a wrong token got in"
            client.helperAttach(2_000, null, binder, apk) != null -> "a missing token got in"
            else -> null
        }
    }

    check("the provider lxdb reports to is guarded and answers LX itself nothing") {
        val info = ctx.packageManager.getProviderInfo(ComponentName(ctx, EmbedBinderProvider::class.java), 0)
        val guard = "android.permission.INTERACT_ACROSS_USERS_FULL"
        val uri = Uri.parse("content://" + ShellHelper.authority())
        val extras = Bundle().apply {
            putString(ShellHelper.KEY_TOKEN, ShellHelper.newToken())
            putBinder(ShellHelper.KEY_SERVICE, Binder())
        }
        when {
            info.authority != ShellHelper.authority() -> "authority ${info.authority}"
            !info.exported || info.readPermission != guard || info.writePermission != guard -> "provider not guarded"
            ctx.contentResolver.call(uri, ShellHelper.METHOD_ATTACH, null, extras) != null -> "LX's own uid got an answer"
            ctx.contentResolver.call(uri, "query", null, extras) != null -> "an unknown method got an answer"
            ctx.contentResolver.query(uri, null, null, null, null) != null -> "the provider answered a query"
            else -> null
        }
    }
}
