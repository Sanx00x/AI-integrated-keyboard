package com.nova.keyboard.prediction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NovaModelRepositoryLifecycleTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun laterServiceRequestReusesTheAlreadyLoadedModel() {
        val first = awaitModel()
        val second = awaitModel()

        assertNotNull(first)
        assertSame(
            "A later service instance should receive the existing process-wide model",
            first,
            second
        )
        assertTrue(NovaModelRepository.isModelReady())
    }

    @Test
    fun cancelledServiceRequestDoesNotReceiveCallback() {
        // Ensure the repository is warm so requestModel posts its callback to the main thread.
        awaitModel()

        val callbackInvoked = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val requestId = NovaModelRepository.requestModel(context) { _, _ ->
                callbackInvoked.countDown()
            }
            // Mirrors NovaInputMethodService.onDestroy(): remove the pending callback.
            NovaModelRepository.cancelRequest(requestId)
        }

        assertFalse(
            "A destroyed service must not receive a queued model callback",
            callbackInvoked.await(300, TimeUnit.MILLISECONDS)
        )
    }

    private fun awaitModel(): NovaBinaryModel {
        val completed = CountDownLatch(1)
        var model: NovaBinaryModel? = null
        var error: Exception? = null

        NovaModelRepository.requestModel(context) { loaded, failure ->
            model = loaded
            error = failure
            completed.countDown()
        }

        assertTrue("Timed out waiting for the repository model", completed.await(15, TimeUnit.SECONDS))
        assertTrue("Repository model load failed: $error", error == null)
        return requireNotNull(model) { "Repository returned no model" }
    }
}
