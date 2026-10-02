package com.nivara.app.testing

import com.nivara.app.data.session.InMemorySessionManager
import com.nivara.app.domain.credential.TimeProvider
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.domain.security.SessionTimeoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** One minute, so the expiry instants in a test are easy to read. */
internal const val TEST_SESSION_TIMEOUT_MILLIS: Long = 60_000L

/** The policy the JVM suites use unless a test is specifically about the schedule. */
internal fun testSessionPolicy(
    timeoutMillis: Long = TEST_SESSION_TIMEOUT_MILLIS,
): SessionTimeoutPolicy = SessionTimeoutPolicy(timeoutMillis = timeoutMillis)

/**
 * The real session manager, with a clock the test moves and a timer that cannot fire.
 *
 * The manager is the production implementation — nothing about the class under test is replaced.
 * What the test supplies is its two outside-world dependencies: the clock, and a coroutine scope
 * for the expiry timer. The scope here uses its own virtual scheduler, which no test advances, so
 * the manager behaves exactly as it does in a screen test: reads apply the deadline, and the timer
 * waits quietly.
 *
 * Suites that verify the timer itself build the manager directly with the test's scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun testSessionManager(
    timeProvider: TimeProvider,
    policy: SessionTimeoutPolicy = testSessionPolicy(),
): SessionManager = InMemorySessionManager(
    timeProvider = timeProvider,
    policy = policy,
    scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
)
