package com.abrah.nightmare

import androidx.test.espresso.IdlingPolicies
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

/**
 * ⭐ A Compose test that waits for idle fails in [SECONDS], not Espresso's default 60.
 *
 * ⚠ Why it exists (2026-10-14): something left running by an earlier test kept the main looper busy,
 * and every later test that waits for idle sat out the full 60 s before `AppNotIdleException`. 22 of
 * them turned a 6-minute suite into 28. A leak must stay a failure, so the timeout is short, not gone:
 * a test that needs longer than this to settle is itself the bug.
 */
class FastIdle : ExternalResource() {
    override fun before() {
        IdlingPolicies.setMasterPolicyTimeout(SECONDS, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(SECONDS, TimeUnit.SECONDS)
    }

    override fun after() {
        IdlingPolicies.setMasterPolicyTimeout(60, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(26, TimeUnit.SECONDS)
    }

    companion object {
        const val SECONDS = 5L
    }
}
