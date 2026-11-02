package com.nova.keyboard.prediction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NovaModelRepositoryInstrumentedTest {

    @Test
    fun concurrentRequestsShareOneModelInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(2)
        val results = Collections.synchronizedList(mutableListOf<NovaBinaryModel>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        NovaModelRepository.requestModel(context) { model, error ->
            if (model != null) results.add(model)
            if (error != null) failures.add(error)
            latch.countDown()
        }
        NovaModelRepository.requestModel(context) { model, error ->
            if (model != null) results.add(model)
            if (error != null) failures.add(error)
            latch.countDown()
        }

        assertTrue("Timed out waiting for shared model load", latch.await(15, TimeUnit.SECONDS))
        assertTrue("Model loading failed: $failures", failures.isEmpty())
        assertNotNull("First request did not receive a model", results.getOrNull(0))
        assertNotNull("Second request did not receive a model", results.getOrNull(1))
        assertSame("Requests should receive the exact same model instance", results[0], results[1])
        assertTrue("Repository should report the model as ready", NovaModelRepository.isModelReady())
    }
}
