// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where a file job waits when a name it needs is taken. The job's thread blocks; the question goes to the File
 * Explorer window that is open, or waits until one opens. A window that goes away before the user answers hands its
 * question back, so the UI being rebuilt for a theme change never answers for the user. Cancelling the job from its
 * progress row ends the wait.
 *
 * Holding no window means a running job keeps no destroyed window's views alive either.
 */
object ConflictDesk : ConflictResolver {
    /** The open window's way of asking. Called on the main thread; returns how to take the question away again. */
    fun interface Asker {
        fun ask(conflict: Conflict, answer: (ConflictChoice, Boolean) -> Unit): () -> Unit
    }

    private class Question(val conflict: Conflict) {
        val latch = CountDownLatch(1)
        @Volatile var answer: Pair<ConflictChoice, Boolean>? = null
        /** Main thread only: the window showing it, and how to close what it shows. */
        var shownBy: Asker? = null
        var close: (() -> Unit)? = null
    }

    private const val POLL_MS = 250L
    private val main = Handler(Looper.getMainLooper())
    /** Main thread only. */
    private val open = ArrayList<Question>()
    private var asker: Asker? = null

    /** Main thread. [a] asks from now on, starting with every question no window is showing. */
    fun attach(a: Asker) {
        asker = a
        for (q in open.toList()) if (q.shownBy == null) show(q, a)
    }

    /** Main thread. [a] is going away; whatever it was showing waits for the next window. */
    fun detach(a: Asker) {
        if (asker === a) asker = null
        for (q in open) if (q.shownBy === a) {
            q.shownBy = null
            q.close = null
        }
    }

    override fun resolve(conflict: Conflict): Pair<ConflictChoice, Boolean> = resolve(conflict, null)

    /** Job thread. Blocks until a window answers. CANCEL once [cancel] is set. */
    override fun resolve(conflict: Conflict, cancel: Cancel?): Pair<ConflictChoice, Boolean> {
        val q = Question(conflict)
        main.post {
            open += q
            asker?.let { show(q, it) }
        }
        while (!q.latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancel?.cancelled == true) {
                main.post { withdraw(q) }
                return ConflictChoice.CANCEL to false
            }
        }
        return q.answer ?: (ConflictChoice.CANCEL to false)
    }

    private fun show(q: Question, a: Asker) {
        q.shownBy = a
        q.close = a.ask(q.conflict) { choice, all ->
            // A sheet left over from a window that has since handed the question on does not count.
            if (q.shownBy === a && q.answer == null) {
                q.answer = choice to all
                open.remove(q)
                q.latch.countDown()
            }
        }
    }

    private fun withdraw(q: Question) {
        open.remove(q)
        q.close?.invoke()
        q.shownBy = null
        q.close = null
    }
}
