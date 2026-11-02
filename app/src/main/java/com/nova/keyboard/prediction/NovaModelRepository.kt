package com.nova.keyboard.prediction

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide owner of the read-only corpus model.
 *
 * The model is loaded once per app process and shared with each IME service
 * instance. Callbacks are always delivered on the main thread. Callers should
 * cancel their request from onDestroy() to avoid retaining a dead service.
 */
object NovaModelRepository {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextRequestId = AtomicLong(1L)
    private val callbacks = mutableMapOf<Long, (NovaBinaryModel?, Exception?) -> Unit>()
    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NOVA-shared-model-loader").apply { isDaemon = true }
    }

    @Volatile
    private var cachedModel: NovaBinaryModel? = null

    private var isLoading = false

    /**
     * Request the shared model. Multiple callers during the first load are
     * coalesced into one asset load and receive the same model instance.
     */
    fun requestModel(
        context: Context,
        callback: (NovaBinaryModel?, Exception?) -> Unit
    ): Long {
        val requestId = nextRequestId.getAndIncrement()
        synchronized(lock) {
            callbacks[requestId] = callback

            val readyModel = cachedModel
            if (readyModel != null) {
                mainHandler.post {
                    deliver(requestId, readyModel, null)
                }
            } else if (!isLoading) {
                isLoading = true
                val appContext = context.applicationContext
                loader.execute { loadAndPublish(appContext) }
            }
        }
        return requestId
    }

    /** Remove a pending callback when its service instance is destroyed. */
    fun cancelRequest(requestId: Long) {
        synchronized(lock) {
            callbacks.remove(requestId)
        }
    }

    /** True only after a complete model instance has been published. */
    fun isModelReady(): Boolean = cachedModel != null

    private fun loadAndPublish(context: Context) {
        var loadedModel: NovaBinaryModel? = null
        var loadError: Exception? = null
        try {
            loadedModel = NovaBinaryModel.load(context)
        } catch (error: Exception) {
            loadError = error
        }

        val requestsToNotify = synchronized(lock) {
            loadedModel?.let { cachedModel = it }
            isLoading = false
            callbacks.keys.toList()
        }

        mainHandler.post {
            for (requestId in requestsToNotify) {
                deliver(requestId, loadedModel, loadError)
            }
        }
    }

    private fun deliver(
        requestId: Long,
        model: NovaBinaryModel?,
        error: Exception?
    ) {
        val callback = synchronized(lock) {
            callbacks.remove(requestId)
        } ?: return

        callback(model, error)
    }
}
