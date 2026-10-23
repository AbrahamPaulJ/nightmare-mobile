package com.abrah.nightmare

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.espresso.IdlingPolicies
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⭐ A Compose test that waits for idle fails in [SECONDS], not Espresso's default 60.
 *
 * ⚠ Why it exists (2026-10-14): something left running by an earlier test kept the main looper busy,
 * and every later test that waits for idle sat out the full 60 s before `AppNotIdleException`. 22 of
 * them turned a 6-minute suite into 28. A leak must stay a failure, so the timeout is short, not gone:
 * a test that needs longer than this to settle is itself the bug.
 *
 * ⚠⚠ It also APPLIES the global snapshot's writes (2026-10-23). Compose's own applier (`GlobalSnapshotManager`)
 * starts once per JVM and, once an earlier test killed it, nothing applied a write made outside composition: the
 * idle check saw `Snapshot.current.hasPendingChanges()` for ever. 11 to 18 tests of DescribeDialog, PromptToolbar,
 * ErrorDetails, ModelMenu, FaceEditSheet, SwipeTabs failed at 5 s, a different set each run (whichever classes
 * shared that JVM), and each passed alone. Measured by [dump]: pending = true, one apply = false, no writer thread.
 */
class FastIdle : ExternalResource() {
    private var applier: ObserverHandle? = null

    /** ⭐ On an idle timeout, say WHAT kept the app busy: recomposers, the global snapshot, the main looper, our threads. */
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        super.apply(object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    if ("did not get idle" in e.message.orEmpty()) dump(description)
                    throw e
                }
            }
        }, description)

    private fun dump(description: org.junit.runner.Description) {
        val out = StringBuilder("FastIdle: ${description.displayName} never went idle\n")
        runCatching {
            for (r in androidx.compose.runtime.Recomposer.runningRecomposers.value) {
                out.append("  recomposer pending=${r.hasPendingWork} changes=${r.changeCount}\n")
            }
            out.append("  snapshot pending=${Snapshot.current.hasPendingChanges()}\n")
            android.os.Looper.getMainLooper().dump({ out.append("  ").append(it).append('\n') }, "")
        }
        // ⚠ A thread an earlier test left running, writing state: the app's frames of every live thread.
        for ((t, stack) in Thread.getAllStackTraces()) {
            val ours = stack.filter { "com.abrah.nightmare" in it.className }
            if (ours.isNotEmpty() && t != Thread.currentThread()) {
                out.append("  thread ${t.name}: ").append(ours.take(4).joinToString(" < ")).append('\n')
            }
        }
        System.err.println(out.toString().lines().take(80).joinToString("\n"))
    }

    override fun before() {
        IdlingPolicies.setMasterPolicyTimeout(SECONDS, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(SECONDS, TimeUnit.SECONDS)
        // ⭐ What `GlobalSnapshotManager` does, for this test only: a write queues one apply on the main looper.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val queued = AtomicBoolean(false)
        applier = Snapshot.registerGlobalWriteObserver {
            if (queued.compareAndSet(false, true)) main.post { queued.set(false); Snapshot.sendApplyNotifications() }
        }
        Snapshot.sendApplyNotifications()
    }

    override fun after() {
        applier?.dispose()
        applier = null
        IdlingPolicies.setMasterPolicyTimeout(60, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(26, TimeUnit.SECONDS)
    }

    companion object {
        const val SECONDS = 5L
    }
}
