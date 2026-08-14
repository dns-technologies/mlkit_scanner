package com.dns_technologies.mlkit_scanner.models

import com.dns_technologies.mlkit_scanner.PluginError
import com.dns_technologies.mlkit_scanner.scanner.ScannerView
import com.dns_technologies.mlkit_scanner.scanner.models.RecognizeVisorCropRect
import com.dns_technologies.mlkit_scanner.scanner.models.ScanResultSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

internal class ScannerSessionImplTest {
    @Test
    fun `parallel starts share one camera initialization`() = runSessionTest {
        val fixture = Fixture()

        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }

        assertEquals(1, fixture.startCalls)

        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { awaitAll(first, second) }
        Unit
    }

    @Test
    fun `cancelling one waiter keeps shared initialization alive`() = runSessionTest {
        val fixture = Fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }

        first.cancel()
        first.join()

        assertEquals(0, fixture.disposeCalls)
        assertEquals(1, fixture.startCalls)

        fixture.completeInitialization()
        withTimeout(TEST_TIMEOUT_MS) { second.await() }
    }

    @Test
    fun `release fails pending initialization and disposes resources`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }

        fixture.session.release()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        assertEquals(1, fixture.disposeCalls)
        assertEquals(1, fixture.subscriptionCancelCalls)
    }

    @Test
    fun `initialization error disposes camera and is propagated`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }
        val expectedError = IllegalStateException("initialization failed")

        fixture.failInitialization(expectedError)

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(expectedError.message, error?.message)
        assertEquals(1, fixture.disposeCalls)
        assertEquals(1, fixture.subscriptionCancelCalls)
    }

    @Test
    fun `release wins race against ready callback`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }

        fixture.session.release()
        fixture.completeInitialization()

        val error = runCatching {
            withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        }.exceptionOrNull()
        assertSame(PluginError.CameraSessionDisposed, error)
        assertEquals(1, fixture.disposeCalls)
    }

    @Test
    fun `ready callback wins race against release`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }

        fixture.completeInitialization()
        fixture.session.release()

        withTimeout(TEST_TIMEOUT_MS) { initialization.await() }
        assertEquals(1, fixture.disposeCalls)
    }

    @Test
    fun `start after release fails without starting camera`() = runSessionTest {
        val fixture = Fixture()
        fixture.session.release()

        val error = runCatching { fixture.session.startCamera(null) }.exceptionOrNull()

        assertSame(PluginError.CameraSessionDisposed, error)
        assertEquals(0, fixture.startCalls)
    }

    @Test
    fun `start after successful initialization does not restart camera`() = runSessionTest {
        val fixture = Fixture()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.startCamera(null) }
        fixture.completeInitialization()
        initialization.await()

        fixture.session.startCamera(null)

        assertEquals(1, fixture.startCalls)
    }

    private fun runSessionTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking {
            supervisorScope { block() }
        }
    }

    private class Fixture {
        val view: ScannerView = mock(ScannerView::class.java)
        val session: ScannerSessionImpl

        var startCalls = 0
            private set
        var disposeCalls = 0
            private set
        var subscriptionCancelCalls = 0
            private set

        private var isCameraActive = false
        private var onReady: (() -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        init {
            val subscription = ScanResultSubscription { subscriptionCancelCalls += 1 }
            doReturn(subscription).`when`(view).subscribeToScanResults(anyValue())
            doAnswer { isCameraActive }.`when`(view).isActive()
            doAnswer { invocation ->
                startCalls += 1
                onReady = invocation.getArgument(2)
                onError = invocation.getArgument(3)
                null
            }.`when`(view).startCamera(
                nullable(Float::class.javaObjectType),
                nullable(RecognizeVisorCropRect::class.java),
                anyValue(),
                anyValue(),
            )
            doAnswer {
                disposeCalls += 1
                isCameraActive = false
                null
            }.`when`(view).dispose()

            session = ScannerSessionImpl(view) {}
        }

        fun completeInitialization() {
            isCameraActive = true
            onReady?.invoke()
        }

        fun failInitialization(error: Exception) {
            onError?.invoke(error)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 1_000L

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            any<T>()
            return null as T
        }
    }
}
