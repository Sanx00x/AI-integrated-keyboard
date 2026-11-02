package com.nova.keyboard.ime

import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.text.Html
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.inputmethodservice.InputMethodService
import android.icu.text.BreakIterator
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.PopupWindow

import com.nova.keyboard.data.UserLanguageModel
import com.nova.keyboard.input.EditorTextSnapshotCache
import com.nova.keyboard.diagnostics.LatencyWindow
import com.nova.keyboard.prediction.NovaModelRepository
import com.nova.keyboard.prediction.PredictionEngine
import com.nova.keyboard.prediction.SpatialShadowStats
import com.nova.keyboard.prediction.SpatialTouchRanking
import com.nova.keyboard.prediction.SpatialKeyCorrectionPolicy
import com.nova.keyboard.ui.KeyboardView
import com.nova.keyboard.ui.KeyboardLayoutPolicy
import com.nova.keyboard.ui.KeyPreviewView
import com.nova.keyboard.ui.EmojiPickerView
import com.nova.keyboard.ui.SuggestionView
import com.nova.keyboard.ui.ClipboardButtonGesture
import com.nova.keyboard.ui.ClipboardHistoryPopupView
import com.nova.keyboard.ui.ClipboardPopupItem
import com.nova.keyboard.ui.LiquidGlassDrawable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NovaInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "NOVA-Keyboard"
        private val WORD_REGEX = Regex("[\\p{L}\\p{N}']+")

        // A narrow allowlist for browser omniboxes. These editors may set
        // TYPE_TEXT_FLAG_NO_SUGGESTIONS even though users still expect the IME
        // to offer local search/URL-related candidates. This exception applies
        // only to TYPE_TEXT_VARIATION_URI fields in known browser packages.
        private val BROWSER_OMNIBOX_PACKAGES = setOf(
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly",
            "com.android.chrome",
            "org.chromium.chrome",
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android"
        )
    }

    private val serviceCreatedAtNanos = SystemClock.elapsedRealtimeNanos()
    private val keyHandlerLatency = LatencyWindow("KEY_HANDLER")
    private val touchReleaseLatency = LatencyWindow("TOUCH_RELEASE_TO_COMMIT")
    private val suggestionCommitLatency = LatencyWindow("SUGGESTION_COMMIT")
    private val inputConnectionLatency = LatencyWindow("INPUT_CONNECTION_READ")
    private val predictionLatency = LatencyWindow("PREDICTION")
    private val suggestionBindLatency = LatencyWindow("SUGGESTION_VIEW_BIND")
    private val refreshSnapshotLatency = LatencyWindow("REFRESH_SNAPSHOT")
    private val suggestionUpdateLatency = LatencyWindow("SUGGESTION_UPDATE")
    // Stores aggregate spatial-model diagnostics only. It never stores typed
    // characters, touch coordinates, or per-key histories.
    private val spatialShadowStats = SpatialShadowStats()

    // Each refresh gets a generation. Only the newest completed prediction may
    // update the suggestion row, preventing slower older results from appearing.
    private val predictionGeneration = AtomicLong(0L)

    // Main-thread-owned mirror of the text immediately before the cursor. It is
    // updated for NOVA's own edits and invalidated when Android reports cursor
    // movement, a selection, or a new editor. Periodic resync bounds staleness.
    private val editorTextCache = EditorTextSnapshotCache()
    private var selectionStateKnown = false
    private var selectionIsCollapsed = false
    private var inputConnectionReads = 0L
    private var editorCacheHits = 0L
    private var inputViewStartedAtNanos = 0L
    private var blurListenerRegistered = false
    private val crossWindowBlurEnabledListener = Consumer<Boolean> { enabled ->
        applyLiquidGlassWindowEffect(enabled)
    }
    private lateinit var predictionEngine: PredictionEngine
    private var personalModel: UserLanguageModel? = null
    private var suggestionView: SuggestionView? = null
    private var keyboardView: KeyboardView? = null
    private var keyPreviewView: KeyPreviewView? = null
    private var emojiPickerView: EmojiPickerView? = null
    private var emojiPanelVisible = false
    private var modelRequestId: Long? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class ClipboardHistoryEntry(
        val id: Long,
        val fingerprint: String,
        val text: String?,
        val htmlText: String?,
        val originalUri: Uri?,
        val mimeType: String?,
        val mimeTypes: List<String>,
        val descriptionLabel: String,
        val kind: String,
        val previewTitle: String,
        val previewDetail: String,
        var cachedUri: Uri? = null,
        var cachedFile: File? = null
    )

    private val clipboardManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(ClipboardManager::class.java)
    }
    private val clipboardHistory = ArrayList<ClipboardHistoryEntry>(4)
    private val clipboardCacheExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NOVA-clipboard-cache").apply { isDaemon = true }
    }
    private var clipboardPopupView: ClipboardHistoryPopupView? = null
    private var clipboardPopupWindow: PopupWindow? = null
    private var clipboardListenerRegistered = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            captureCurrentClipboard()
        } else {
            mainHandler.post { captureCurrentClipboard() }
        }
    }
    private val maxClipboardHistory = 4
    private val maxClipboardMediaBytes = 12L * 1024L * 1024L
    // The keyboard sits at the bottom of the IME surface; the preview overlay
    // spans the whole surface. Their Y offset is the fixed suggestion-strip
    // height, so no per-tap window-location queries or RectF allocations are needed.
    private var keyPreviewYOffsetPx = 0f
    private val translatedPreviewBounds = android.graphics.RectF()
    private val modelExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NOVA-model-loader").apply { isDaemon = true }
    }
    // Prediction runs away from the IME main thread. A one-item queue bounds
    // stale work while the discard-oldest policy favours the most recent input.
    private val predictionExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "NOVA-prediction").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @Volatile
    private var serviceDestroyed = false

    override fun onCreate() {
        super.onCreate()
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = true
        File(cacheDir, "nova_clipboard").listFiles()?.forEach { it.delete() }
        Log.i(TAG, "SERVICE_CREATED")
        predictionEngine = PredictionEngine()

        // Personal learning remains service-scoped and is loaded off the IME UI thread.
        // It owns mutable user data and is closed when this service instance is destroyed.
        modelExecutor.execute {
            try {
                val loadedPersonalModel = UserLanguageModel(applicationContext)
                mainHandler.post {
                    if (serviceDestroyed) {
                        loadedPersonalModel.close()
                    } else {
                        personalModel = loadedPersonalModel
                        predictionEngine.setPersonalModel(loadedPersonalModel)
                        Log.i(
                            TAG,
                            "Personal language model ready: ${loadedPersonalModel.stats().learnedWords} words " +
                                "SERVICE_TO_PERSONAL_READY_MS=${elapsedMs(serviceCreatedAtNanos)}"
                        )
                        refreshSuggestions()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Personal learning is unavailable; corpus predictions remain active", error)
            }
        }

        // The read-only corpus model is immutable after loading, so share one instance
        // across service recreations for the lifetime of this app process.
        modelRequestId = NovaModelRepository.requestModel(applicationContext) { loadedModel, error ->
            if (serviceDestroyed) return@requestModel

            if (loadedModel != null) {
                predictionEngine.setBinaryModel(loadedModel)
                Log.i(
                    TAG,
                    "Tanglish model ready: ${loadedModel.vocabularySize} words, " +
                        "${loadedModel.bigramContextCount} bigram and " +
                        "${loadedModel.trigramContextCount} trigram contexts " +
                        "SERVICE_TO_CORPUS_READY_MS=${elapsedMs(serviceCreatedAtNanos)}"
                )
                refreshSuggestions()
            } else {
                // Keep the existing built-in English/Tanglish engine available.
                Log.w(TAG, "Tanglish model could not be loaded; using fallback predictions", error)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        captureCurrentClipboard()
        editorTextCache.invalidate()
        predictionGeneration.incrementAndGet()

        // EditorInfo carries the initial selection coordinates. Use them when
        // available so the first prediction request does not assume a caret.
        val initialStart = attribute.initialSelStart
        val initialEnd = attribute.initialSelEnd
        selectionStateKnown = initialStart >= 0 && initialEnd >= 0
        selectionIsCollapsed = selectionStateKnown && initialStart == initialEnd
        dismissEmojiPanel(refresh = false)
        suggestionView?.showSuggestions(emptyList())

        val inputClass = attribute.inputType and InputType.TYPE_MASK_CLASS
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        val noSuggestions = (attribute.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val autoComplete = (attribute.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0
        Log.i(
            TAG,
            "EDITOR_POLICY package=${attribute.packageName} fieldId=${attribute.fieldId} " +
                "inputType=0x${attribute.inputType.toString(16)} class=$inputClass variation=$variation " +
                "noSuggestions=$noSuggestions autoComplete=$autoComplete " +
                "browserOmnibox=${isBrowserOmnibox(attribute)} " +
                "predictionAllowed=${isPredictionAllowed()} " +
                "personalLearningAllowed=${isPersonalLearningAllowed()}"
        )
        Log.d(
            TAG,
            "SELECTION_STATE event=START_INPUT known=$selectionStateKnown " +
                "collapsed=$selectionIsCollapsed initialStartValid=${initialStart >= 0} " +
                "initialEndValid=${initialEnd >= 0} restarting=$restarting"
        )
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        val previouslyHadCollapsedCaret = selectionStateKnown && selectionIsCollapsed
        selectionStateKnown = newSelStart >= 0 && newSelEnd >= 0
        selectionIsCollapsed = selectionStateKnown && newSelStart == newSelEnd
        Log.d(
            TAG,
            "SELECTION_STATE event=UPDATE known=$selectionStateKnown " +
                "collapsed=$selectionIsCollapsed previouslyCollapsed=$previouslyHadCollapsedCaret " +
                "newStartValid=${newSelStart >= 0} newEndValid=${newSelEnd >= 0} " +
                "candidateRangePresent=${candidatesStart >= 0 && candidatesEnd >= 0}"
        )

        if (selectionIsCollapsed) {
            if (previouslyHadCollapsedCaret) {
                val cacheRemainedValid = editorTextCache.onSelectionUpdate(
                    oldSelStart, oldSelEnd, newSelStart, newSelEnd
                )
                if (!cacheRemainedValid) {
                    clearSuggestionsAndInvalidatePredictions()
                    if (suggestionView != null) refreshSuggestions()
                }
            } else {
                // A newly collapsed caret (including the first callback after
                // focus) needs a fresh editor snapshot before predictions resume.
                editorTextCache.invalidate()
                if (suggestionView != null) refreshSuggestions()
            }
        } else {
            editorTextCache.invalidate()
            clearSuggestionsAndInvalidatePredictions()
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        captureCurrentClipboard()
        val now = SystemClock.elapsedRealtimeNanos()
        val startedAt = inputViewStartedAtNanos
        val shownAfterStartMs = if (startedAt > 0L) {
            (now - startedAt) / 1_000_000.0
        } else {
            -1.0
        }
        Log.i(
            TAG,
            "IME_WINDOW_SHOWN AFTER_INPUT_VIEW_START_MS=" +
                String.format(java.util.Locale.US, "%.3f", shownAfterStartMs)
        )
        inputViewStartedAtNanos = 0L
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        clipboardPopupView?.hide()
        dismissEmojiPanel(refresh = false)
        clipboardPopupView?.hide()
        clipboardPopupWindow?.dismiss()
        editorTextCache.invalidate()
        clearSuggestionsAndInvalidatePredictions()
        flushSpatialShadowStats("INPUT_VIEW_FINISHED")
        flushPerformanceMetrics("INPUT_VIEW_FINISHED")
    }

    override fun onDestroy() {
        flushSpatialShadowStats("SERVICE_DESTROYED")
        flushPerformanceMetrics("SERVICE_DESTROYED")
        serviceDestroyed = true
        predictionGeneration.incrementAndGet()
        editorTextCache.invalidate()
        predictionExecutor.shutdownNow()
        modelRequestId?.let { NovaModelRepository.cancelRequest(it) }
        modelRequestId = null
        personalModel?.close()
        modelExecutor.shutdownNow()
        // Queued model-attachment callbacks check serviceDestroyed themselves;
        // keep them queued so a just-created personal model can be closed safely.
        suggestionView = null
        keyboardView = null
        keyPreviewView = null
        emojiPickerView = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurListenerRegistered) {
            getSystemService(WindowManager::class.java)
                ?.removeCrossWindowBlurEnabledListener(crossWindowBlurEnabledListener)
            blurListenerRegistered = false
        }
        clipboardPopupView?.hide()
        if (clipboardListenerRegistered) {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = false
        }
        clipboardCacheExecutor.shutdownNow()
        clipboardPopupWindow?.dismiss()
        clipboardPopupWindow = null
        clipboardPopupView = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val viewBuildStartedAt = SystemClock.elapsedRealtimeNanos()

        emojiPanelVisible = false
        val keyPreview = KeyPreviewView(this)
        val clipboardPopup = ClipboardHistoryPopupView(this)
        var keyboardForPreview: KeyboardView? = null
        val keyboard = KeyboardView(
            context = this,
            onKeyPressed = { key ->
                val handlerStartedAt = SystemClock.elapsedRealtimeNanos()
                handleKey(key)
                keyHandlerLatency.recordNanos(
                    SystemClock.elapsedRealtimeNanos() - handlerStartedAt
                )
            },
            onKeyPreviewChanged = { label, bounds ->
                // Preview overlay spans the bounded input surface so the popup
                // can rise above the first keyboard row into the suggestion strip.
                // Translate from keyboard-local to root-local coordinates.
                val source = keyboardForPreview
                if (source != null && bounds != null) {
                    translatedPreviewBounds.set(bounds)
                    translatedPreviewBounds.offset(0f, keyPreviewYOffsetPx)
                    keyPreview.showKey(label, translatedPreviewBounds)
                } else {
                    keyPreview.showKey(label, null)
                }
            },
            onSpatialShadowSample = { ranking, legacyWinner ->
                spatialShadowStats.record(ranking, legacyWinner)
            },
            onResolveAmbiguousKey = ::resolveAmbiguousKey,
            onTouchReleaseLatency = touchReleaseLatency::recordNanos
        )
        keyboardForPreview = keyboard

        val suggestions = SuggestionView(
            context = this,
            onClipboardTap = ::pasteMostRecentClipboardItem,
            onClipboardGesture = ::handleClipboardGesture
        ) { word ->
            val commitStartedAt = SystemClock.elapsedRealtimeNanos()
            commitSuggestion(word)
            suggestionCommitLatency.recordNanos(
                SystemClock.elapsedRealtimeNanos() - commitStartedAt
            )
        }
        val emojiPicker = EmojiPickerView(
            context = this,
            onEmojiSelected = ::commitEmoji,
            onClose = { dismissEmojiPanel(refresh = true) }
        ).apply {
            visibility = View.GONE
        }

        suggestionView = suggestions
        keyboardView = keyboard
        keyPreviewView = keyPreview
        clipboardPopupView = clipboardPopup
        clipboardPopupWindow?.dismiss()
        clipboardPopupWindow = PopupWindow(
            clipboardPopup,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isFocusable = false
            isTouchable = false
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12f * resources.displayMetrics.density
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        emojiPickerView = emojiPicker
        // Derive the profile from the display's live pixel metrics and density,
        // rather than assuming the activity's available content height is the
        // physical panel height. On the Nord 5 this resolves to 1272x2800px at
        // 560dpi: 363.43x800dp. The portrait profile uses 248dp keys + 48dp
        // suggestions (296dp total); compact profiles remain for smaller panels.
        val configuration = resources.configuration
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density.coerceAtLeast(0.1f)
        val displayWidthDp = (displayMetrics.widthPixels / density).roundToInt()
        val displayHeightDp = (displayMetrics.heightPixels / density).roundToInt()
        val landscape = if (displayWidthDp > 0 && displayHeightDp > 0) {
            displayWidthDp > displayHeightDp
        } else {
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val layoutProfile = KeyboardLayoutPolicy.profileForDisplay(
            screenHeightDp = displayHeightDp.takeIf { it > 0 } ?: configuration.screenHeightDp,
            isLandscape = landscape
        )
        val keyboardHeight = (layoutProfile.keyboardBodyHeightDp * density + 0.5f).toInt()
        val suggestionsHeight = (layoutProfile.suggestionHeightDp * density + 0.5f).toInt()
        keyPreviewYOffsetPx = suggestionsHeight.toFloat()
        val inputSurfaceHeight = keyboardHeight + suggestionsHeight

        val container = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            minimumHeight = inputSurfaceHeight
            setBackground(
                LiquidGlassDrawable(
                    density = density,
                    cornerRadiusPx = 27f * density,
                    insetPx = 0f,
                    tintStrength = 1f
                )
            )
        }
        container.addView(
            keyboard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight,
                android.view.Gravity.BOTTOM
            )
        )
        container.addView(
            suggestions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                suggestionsHeight,
                android.view.Gravity.TOP
            )
        )
        container.addView(
            emojiPicker,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                inputSurfaceHeight
            )
        )
        // Popup remains VISIBLE but draws nothing while idle. Avoid visibility
        // transitions on every tap; popup activity changes only its drawing state.
        container.addView(
            keyPreview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                inputSurfaceHeight
            )
        )

        container.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val rootScreenLocation = IntArray(2)
            root.getLocationOnScreen(rootScreenLocation)
            val visibleFrame = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visibleFrame)
            Log.i(
                TAG,
                "INPUT_LAYOUT rootWidth=${root.width} rootHeight=${root.height} " +
                    "rootScreenX=${rootScreenLocation[0]} rootScreenY=${rootScreenLocation[1]} " +
                    "visibleFrame=${visibleFrame.left},${visibleFrame.top},${visibleFrame.right},${visibleFrame.bottom} " +
                    "expectedHeight=$inputSurfaceHeight density=$density " +
                    "screenPx=${displayMetrics.widthPixels}x${displayMetrics.heightPixels} " +
                    "screenDp=${displayWidthDp}x${displayHeightDp} " +
                    "configDp=${configuration.screenWidthDp}x${configuration.screenHeightDp}dp " +
                    "profile=${layoutProfile.name} bodyDp=${layoutProfile.keyboardBodyHeightDp} " +
                    "suggestionsDp=${layoutProfile.suggestionHeightDp} " +
                    "suggestionsWidth=${suggestions.width} suggestionsHeight=${suggestions.height} " +
                    "suggestionsVisibility=${suggestions.visibility} suggestionsShown=${suggestions.isShown} " +
                    "keyboardWidth=${keyboard.width} keyboardHeight=${keyboard.height} " +
                    "emojiWidth=${emojiPicker.width} emojiHeight=${emojiPicker.height} " +
                    "previewVisibility=${keyPreview.visibility}"
            )
        }

        val viewBuildMs = elapsedMs(viewBuildStartedAt)
        Log.i(
            TAG,
            "INPUT_VIEW_CREATED BUILD_MS=$viewBuildMs " +
                "SERVICE_TO_INPUT_VIEW_MS=${elapsedMs(serviceCreatedAtNanos)} " +
                "EXPECTED_INPUT_HEIGHT=$inputSurfaceHeight " +
                "LAYOUT_PROFILE=${layoutProfile.name} " +
                "DISPLAY_PX=${displayMetrics.widthPixels}x${displayMetrics.heightPixels} " +
                "DISPLAY_DP=${displayWidthDp}x${displayHeightDp}"
        )
        applyLiquidGlassWindowEffect()
        return container
    }

    /**
     * Uses Android's bounded window-background blur for the IME surface.
     * FLAG_BLUR_BEHIND is always cleared, so the rest of the screen is not blurred.
     * The IME is checked at runtime because Android can disable cross-window blur.
     */
    private fun applyLiquidGlassWindowEffect(blurEnabledOverride: Boolean? = null) {
        val imeWindow = getWindow()?.window ?: return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(WindowManager::class.java)
            if (manager != null) {
                val blurEnabled = blurEnabledOverride ?: manager.isCrossWindowBlurEnabled
                val blurRadiusPx = (25f * density).roundToInt()
                val topCornerRadiusPx = 27f * density

                // This drawable defines the blur region's rounded outline. Its tint
                // stays light while blur is active and becomes more opaque as fallback.
                val backgroundAlpha = if (blurEnabled) 34 else 190
                val glassWindowBackground = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.argb(backgroundAlpha, 24, 31, 46))
                    setCornerRadii(floatArrayOf(
                        topCornerRadiusPx, topCornerRadiusPx,
                        topCornerRadiusPx, topCornerRadiusPx,
                        0f, 0f, 0f, 0f
                    ))
                }
                imeWindow.setBackgroundDrawable(glassWindowBackground)
                imeWindow.setBackgroundBlurRadius(if (blurEnabled) blurRadiusPx else 0)

                // Explicitly prevent the separate whole-screen blur/dim effect.
                val attributes = imeWindow.attributes
                attributes.flags = attributes.flags and
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                attributes.setBlurBehindRadius(0)
                attributes.dimAmount = 0f
                imeWindow.attributes = attributes

                if (!blurListenerRegistered) {
                    blurListenerRegistered = true
                    try {
                        manager.addCrossWindowBlurEnabledListener(crossWindowBlurEnabledListener)
                    } catch (error: RuntimeException) {
                        blurListenerRegistered = false
                        Log.w(TAG, "Unable to register cross-window blur listener", error)
                    }
                }

                Log.i(
                    TAG,
                    "NOVA_NATIVE_GLASS enabled=$blurEnabled " +
                        "backgroundBlurRadiusPx=${if (blurEnabled) blurRadiusPx else 0} " +
                        "globalBlur=false"
                )
                return
            }
        }

        // Pre-Android 12 or missing WindowManager: readable dark fallback.
        imeWindow.setBackgroundDrawable(
            ColorDrawable(Color.argb(196, 18, 25, 39))
        )
        Log.i(TAG, "NOVA_NATIVE_GLASS enabled=false globalBlur=false fallback=true")
    }
    private fun updateClipboardControls() {
        val allowed = !isSensitiveField()
        suggestionView?.setClipboardEnabled(allowed)
        suggestionView?.setClipboardHasItems(allowed && clipboardHistory.isNotEmpty())
        if (!allowed) clipboardPopupView?.hide()
    }

    private fun captureCurrentClipboard() {
        if (serviceDestroyed) return
        if (isSensitiveField()) {
            updateClipboardControls()
            return
        }
        updateClipboardControls()
        val manager = clipboardManager ?: return
        try {
            if (!manager.hasPrimaryClip()) return
            val clip = manager.primaryClip ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                updateClipboardControls()
                return
            }
            captureClipboardClip(clip)
        } catch (error: SecurityException) {
            Log.w(TAG, "Clipboard access is unavailable for the current editor", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to read current clipboard item", error)
        }
    }

    private fun captureClipboardClip(clip: ClipData) {
        if (clip.itemCount <= 0) return
        val item = clip.getItemAt(0)
        val declaredTypes = (0 until clip.description.mimeTypeCount)
            .map { clip.description.getMimeType(it) }
            .distinct()
        val sourceUri = item.uri
        val uriMime = sourceUri?.let { uri ->
            runCatching { contentResolver.getType(uri) }.getOrNull()
        }
        val mimeType = uriMime
            ?: declaredTypes.firstOrNull { it.startsWith("image/") }
            ?: declaredTypes.firstOrNull {
                !it.startsWith("text/") && it != ClipDescription.MIMETYPE_TEXT_URILIST
            }
            ?: declaredTypes.firstOrNull()
        val allMimeTypes = (declaredTypes + listOfNotNull(uriMime)).distinct()
        val isRichUri = sourceUri != null && !mimeType.isNullOrBlank() &&
            mimeType != ClipDescription.MIMETYPE_TEXT_PLAIN &&
            mimeType != ClipDescription.MIMETYPE_TEXT_HTML &&
            mimeType != ClipDescription.MIMETYPE_TEXT_URILIST

        val text = item.text?.toString() ?: if (!isRichUri) {
            sourceUri?.toString() ?: item.intent?.toUri(android.content.Intent.URI_INTENT_SCHEME)
        } else null
        val htmlText = item.htmlText
        if (text.isNullOrEmpty() && htmlText.isNullOrEmpty() && sourceUri == null) return

        val kind = when {
            isRichUri && (mimeType?.startsWith("image/") == true || allMimeTypes.any { it.startsWith("image/") }) -> "image"
            isRichUri -> "file"
            text?.startsWith("http://", ignoreCase = true) == true || text?.startsWith("https://", ignoreCase = true) == true -> "link"
            else -> "text"
        }
        val displayText = text ?: htmlText?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString()
            } else {
                Html.fromHtml(it).toString()
            }
        }
        val descriptionLabel = clip.description.label?.toString()?.takeIf { it.isNotBlank() } ?: "Clipboard item"
        val previewTitle = when (kind) {
            "image" -> "Copied image"
            "file" -> descriptionLabel.takeUnless { it == "Clipboard item" } ?: "Copied file"
            else -> displayText?.replace('\n', ' ')?.take(120) ?: "Copied text"
        }
        val previewDetail = when (kind) {
            "image" -> mimeType ?: "Image"
            "file" -> mimeType ?: "File"
            "link" -> "Link"
            else -> "Text Â· ${(displayText?.length ?: 0)} characters"
        }
        val fingerprint = listOf(mimeType.orEmpty(), sourceUri?.toString().orEmpty(), text.orEmpty(), htmlText.orEmpty())
            .joinToString("\u0000").hashCode().toString()
        val duplicate = clipboardHistory.firstOrNull { it.fingerprint == fingerprint }
        if (duplicate != null) {
            if (clipboardHistory.firstOrNull() !== duplicate) {
                clipboardHistory.remove(duplicate)
                clipboardHistory.add(0, duplicate)
            }
            publishClipboardHistory()
            return
        }

        val entry = ClipboardHistoryEntry(
            id = SystemClock.elapsedRealtimeNanos(),
            fingerprint = fingerprint,
            text = text,
            htmlText = htmlText,
            originalUri = sourceUri,
            mimeType = mimeType,
            mimeTypes = allMimeTypes.ifEmpty { listOfNotNull(mimeType) },
            descriptionLabel = descriptionLabel,
            kind = kind,
            previewTitle = previewTitle,
            previewDetail = previewDetail
        )
        clipboardHistory.add(0, entry)
        while (clipboardHistory.size > maxClipboardHistory) {
            clipboardHistory.removeAt(clipboardHistory.lastIndex).cachedFile?.delete()
        }
        publishClipboardHistory()

        if (isRichUri && sourceUri?.scheme == ContentResolver.SCHEME_CONTENT && !mimeType.isNullOrBlank()) {
            cacheClipboardUri(entry.id, sourceUri, mimeType)
        }
    }

    private fun cacheClipboardUri(entryId: Long, sourceUri: Uri, mimeType: String) {
        clipboardCacheExecutor.execute {
            var completedFile: File? = null
            try {
                val directory = File(cacheDir, "nova_clipboard")
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create clipboard cache")
                val extension = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType)?.take(10) ?: "bin"
                val temporary = File(directory, "clip_${entryId}.tmp")
                val destination = File(directory, "clip_${entryId}.${extension}")
                val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("Clipboard URI cannot be opened")
                input.use { source ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxClipboardMediaBytes) throw IOException("Clipboard media exceeds 12 MB cache limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (!temporary.renameTo(destination)) throw IOException("Cannot finalize clipboard cache file")
                completedFile = destination
            } catch (error: Exception) {
                Log.w(TAG, "Clipboard media cache unavailable; original URI will be tried", error)
                File(cacheDir, "nova_clipboard/clip_${entryId}.tmp").delete()
            }

            completedFile?.let { file ->
                mainHandler.post {
                    val entry = clipboardHistory.firstOrNull { it.id == entryId }
                    if (entry != null) {
                        entry.cachedFile = file
                        entry.cachedUri = Uri.Builder()
                            .scheme(ContentResolver.SCHEME_CONTENT)
                            .authority("${packageName}.nova-clipboard")
                            .appendPath("item")
                            .appendPath(file.name)
                            .build()
                        publishClipboardHistory()
                    } else {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun publishClipboardHistory() {
        val previews = clipboardHistory.take(maxClipboardHistory).map { entry ->
            ClipboardPopupItem(entry.id, entry.previewTitle, entry.previewDetail, entry.kind)
        }
        clipboardPopupView?.replaceEntries(previews)
        updateClipboardControls()
    }

    private fun handleClipboardGesture(gesture: ClipboardButtonGesture, rawX: Float, rawY: Float) {
        if (isSensitiveField()) {
            clipboardPopupView?.hide()
            clipboardPopupWindow?.dismiss()
            updateClipboardControls()
            return
        }
        val popup = clipboardPopupView ?: return
        when (gesture) {
            ClipboardButtonGesture.START -> {
                captureCurrentClipboard()
                val previews = clipboardHistory.take(maxClipboardHistory).map { entry ->
                    ClipboardPopupItem(entry.id, entry.previewTitle, entry.previewDetail, entry.kind)
                }
                val anchor = suggestionView?.clipboardAnchorView() ?: return
                val window = clipboardPopupWindow ?: return
                popup.show(previews)
                window.width = popup.desiredWidthPx()
                window.height = popup.desiredHeightPx()
                if (window.isShowing) window.dismiss()
                val margin = (8f * resources.displayMetrics.density + 0.5f).toInt()
                window.showAsDropDown(
                    anchor,
                    0,
                    -window.height - anchor.height - margin,
                    Gravity.END
                )
                popup.refreshScreenOrigin()
                popup.updateSelectionFromScreen(rawX, rawY)
            }
            ClipboardButtonGesture.MOVE -> popup.updateSelectionFromScreen(rawX, rawY)
            ClipboardButtonGesture.END -> {
                popup.updateSelectionFromScreen(rawX, rawY)
                val selectedId = popup.selectedItemId()
                popup.hide()
                clipboardPopupWindow?.dismiss()
                if (selectedId != null) pasteClipboardItem(selectedId)
            }
            ClipboardButtonGesture.CANCEL -> {
                popup.hide()
                clipboardPopupWindow?.dismiss()
            }
        }
    }

    private fun pasteMostRecentClipboardItem() {
        if (isSensitiveField()) return
        captureCurrentClipboard()
        val newest = clipboardHistory.firstOrNull()
        if (newest == null) {
            Toast.makeText(this, "No clipboard items yet", Toast.LENGTH_SHORT).show()
        } else {
            pasteClipboardItem(newest.id)
        }
    }

    private fun pasteClipboardItem(entryId: Long) {
        if (isSensitiveField()) return
        val entry = clipboardHistory.firstOrNull { it.id == entryId } ?: return
        val connection = currentInputConnection ?: return

        val richUri = entry.cachedUri ?: entry.originalUri
        val mimeType = entry.mimeType
        if (richUri != null && !mimeType.isNullOrBlank() && isRichClipboardMime(mimeType)) {
            val acceptedTypes = currentInputEditorInfo?.contentMimeTypes.orEmpty()
            val compatible = acceptedTypes.any { accepted -> mimeTypesMatch(mimeType, accepted) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && compatible) {
                connection.finishComposingText()
                val description = ClipDescription(
                    entry.descriptionLabel,
                    (entry.mimeTypes + mimeType).distinct().toTypedArray()
                )
                val content = InputContentInfo(richUri, description, null)
                val committed = runCatching {
                    connection.commitContent(
                        content,
                        InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        null
                    )
                }.getOrDefault(false)
                if (committed) {
                    editorTextCache.invalidate()
                    clearSuggestionsAndInvalidatePredictions()
                    return
                }
            }
            // Some editors accept only text. Use an accompanying text alternative if one exists.
            val fallbackText = entry.text ?: entry.htmlText?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString()
                else Html.fromHtml(it).toString()
            }
            if (fallbackText.isNullOrEmpty()) {
                Toast.makeText(this, "This app does not accept this image or file type", Toast.LENGTH_SHORT).show()
                return
            }
            commitClipboardText(connection, fallbackText)
            return
        }

        val text = entry.text ?: entry.htmlText?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString()
            else Html.fromHtml(it).toString()
        }
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "This clipboard item cannot be pasted here", Toast.LENGTH_SHORT).show()
            return
        }
        commitClipboardText(connection, text)
    }

    private fun commitClipboardText(connection: InputConnection, text: CharSequence) {
        if (connection.commitText(text, 1)) {
            selectionStateKnown = true
            selectionIsCollapsed = true
            editorTextCache.invalidate()
            refreshSuggestions()
        } else {
            Toast.makeText(this, "Could not paste clipboard item", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isRichClipboardMime(mimeType: String): Boolean =
        !mimeType.startsWith("text/") && mimeType != ClipDescription.MIMETYPE_TEXT_URILIST

    private fun mimeTypesMatch(actual: String, accepted: String): Boolean {
        if (actual == accepted || accepted == "*/*") return true
        if (accepted.endsWith("/*")) return actual.startsWith(accepted.removeSuffix("*"))
        if (actual.endsWith("/*")) return accepted.startsWith(actual.removeSuffix("*"))
        return false
    }
    private fun resolveAmbiguousKey(
        rawKey: String,
        ranking: SpatialTouchRanking
    ): String {
        if (!isPredictionAllowed() || !selectionStateKnown || !selectionIsCollapsed) {
            return rawKey
        }
        // Do not alter URLs, email-like identifiers or browser search syntax.
        val editorInfo = currentInputEditorInfo ?: return rawKey
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) return rawKey

        val snapshot = editorTextCache.getIfFresh(SystemClock.elapsedRealtimeNanos())
            ?: return rawKey
        val currentWord = WORD_REGEX.findAll(snapshot).lastOrNull()
            ?.takeIf { it.range.last == snapshot.lastIndex }
            ?.value
            ?: return rawKey
        // Avoid correcting the first or second character, where several valid
        // English/Tanglish words share prefixes and the context is too weak.
        if (currentWord.length < 2) return rawKey

        val usePersonalization = isPersonalLearningAllowed()
        return SpatialKeyCorrectionPolicy.resolve(rawKey, ranking) { nextCharacter ->
            predictionEngine.hasPrefixCandidate(
                prefix = currentWord + nextCharacter,
                usePersonalization = usePersonalization
            )
        }
    }

    private fun handleKey(key: String) {
        if (key == "<EMOJI>") {
            showEmojiPanel()
            return
        }
        val connection = currentInputConnection ?: return
        val safeCollapsedCaret = selectionStateKnown && selectionIsCollapsed
        val needsEditorContext = isPredictionAllowed() || key == "<BACKSPACE>"
        val beforeCursor = if (safeCollapsedCaret && !isSensitiveField() && needsEditorContext) {
            readTextBeforeCursorSnapshot(connection)
        } else {
            null
        }
        var canDeriveAfterEdit = beforeCursor != null && safeCollapsedCaret &&
            !isSensitiveField()

        var deletedBeforeCursor = 0
        var insertedText = key
        var performedEditorAction = false

        // InputConnection edit operations place the cursor after the committed
        // text. Some editors expose unknown initial selection indices or delay
        // selection callbacks; record the known collapsed post-edit state so
        // prediction does not remain disabled after the first keypress.
        fun markCaretCollapsedAfterEdit() {
            selectionStateKnown = true
            selectionIsCollapsed = true
        }

        when (key) {
            "<BACKSPACE>" -> {
                when {
                    selectionStateKnown && !selectionIsCollapsed -> {
                        // commitText replaces the active selection with empty text.
                        if (connection.commitText("", 1)) markCaretCollapsedAfterEdit()
                        canDeriveAfterEdit = false
                    }
                    selectionStateKnown && selectionIsCollapsed -> {
                        if (isSensitiveField()) {
                            // Never read password contents. Let the editor process DEL
                            // so it can apply its own Unicode/grapheme deletion rules.
                            if (sendEditorBackspace(connection)) markCaretCollapsedAfterEdit()
                            canDeriveAfterEdit = false
                        } else if (beforeCursor == null) {
                            // Some editors do not expose surrounding text. Delegate
                            // deletion to the editor rather than guessing UTF-16 units.
                            if (sendEditorBackspace(connection)) markCaretCollapsedAfterEdit()
                            canDeriveAfterEdit = false
                        } else if (beforeCursor.isEmpty()) {
                            // There is no character before the caret.
                            canDeriveAfterEdit = false
                        } else {
                            val deletion = deletePreviousGrapheme(connection, beforeCursor)
                            if (deletion.deleted) {
                                markCaretCollapsedAfterEdit()
                                if (deletion.deletedUtf16Units != null) {
                                    deletedBeforeCursor = deletion.deletedUtf16Units
                                } else {
                                    // KeyEvent fallback succeeded, but its exact editor
                                    // transformation is not observable. Force a fresh read.
                                    canDeriveAfterEdit = false
                                }
                            } else {
                                canDeriveAfterEdit = false
                            }
                        }
                    }
                    else -> {
                        // Delegate to the editor's native DEL behavior when the
                        // initial selection is unknown. This preserves its own
                        // selection semantics and reports success before marking
                        // the resulting caret as collapsed.
                        if (sendEditorBackspace(connection)) {
                            markCaretCollapsedAfterEdit()
                        } else {
                            editorTextCache.invalidate()
                            clearSuggestionsAndInvalidatePredictions()
                            return
                        }
                        canDeriveAfterEdit = false
                    }
                }
                insertedText = ""
            }
            "<SPACE>" -> {
                beforeCursor?.let(::learnWordImmediatelyBeforeCursor)
                if (connection.commitText(" ", 1)) {
                    markCaretCollapsedAfterEdit()
                    insertedText = " "
                } else {
                    canDeriveAfterEdit = false
                    insertedText = ""
                }
            }
            "<ENTER>" -> {
                beforeCursor?.let(::learnWordImmediatelyBeforeCursor)
                if (performConfiguredEditorAction(connection)) {
                    performedEditorAction = true
                    canDeriveAfterEdit = false
                    insertedText = ""
                } else {
                    if (connection.commitText("\n", 1)) {
                        markCaretCollapsedAfterEdit()
                        insertedText = "\n"
                    } else {
                        canDeriveAfterEdit = false
                        insertedText = ""
                    }
                }
            }
            else -> {
                if (isWordBoundaryKey(key)) {
                    beforeCursor?.let(::learnWordImmediatelyBeforeCursor)
                }
                if (connection.commitText(key, 1)) {
                    markCaretCollapsedAfterEdit()
                    insertedText = key
                } else {
                    canDeriveAfterEdit = false
                    insertedText = ""
                }
            }
        }

        if (performedEditorAction) {
            editorTextCache.invalidate()
            clearSuggestionsAndInvalidatePredictions()
            return
        }

        if (canDeriveAfterEdit) {
            val updatedSnapshot = editorTextCache.applyLocalEdit(
                deletedBeforeCursor = deletedBeforeCursor,
                insertedText = insertedText
            )
            if (updatedSnapshot != null) {
                refreshSuggestions(updatedSnapshot)
                return
            }
        }

        // Selection state is unknown/non-collapsed, or the cache was invalidated.
        // Re-read after the edit rather than risk predicting from a stale cursor.
        editorTextCache.invalidate()
        refreshSuggestions()
    }

    private fun isWordBoundaryKey(key: String): Boolean =
        key.length == 1 && key[0] in ",.;:!?"

    /** Learn the just-finished token from the already captured cursor snapshot. */
    private fun learnWordImmediatelyBeforeCursor(beforeCursor: String) {
        if (!selectionStateKnown || !selectionIsCollapsed) return
        if (!isPersonalLearningAllowed()) return

        val lastCharacter = beforeCursor.lastOrNull() ?: return
        if (!lastCharacter.isLetterOrDigit() && lastCharacter != '\'') return

        val lastMatch = WORD_REGEX.findAll(beforeCursor).lastOrNull() ?: return
        if (lastMatch.range.last != beforeCursor.lastIndex) return

        val precedingText = beforeCursor.substring(0, lastMatch.range.first)
        learnWord(lastMatch.value, precedingText)
    }

    /** Learn an explicitly accepted suggestion using the text before its slot. */
    private fun learnWord(word: String, precedingText: String) {
        if (!isPersonalLearningAllowed()) return

        // Do not carry context across sentence/line boundaries.
        val resetAt = precedingText.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' || it == ';' }
        val localContext = precedingText.substring(resetAt + 1)
        val previousWords = WORD_REGEX.findAll(localContext)
            .map { it.value }
            .toList()
            .takeLast(2)

        predictionEngine.learnCompletedWord(word, previousWords)
    }

    private fun refreshSuggestions(textBeforeCursorOverride: String? = null) {
        if (emojiPanelVisible) {
            suggestionView?.showSuggestions(emptyList())
            return
        }
        val refreshStartedAt = SystemClock.elapsedRealtimeNanos()
        val requestId = predictionGeneration.incrementAndGet()
        val connection = currentInputConnection

        val suppressionReason = when {
            connection == null -> "NO_INPUT_CONNECTION"
            !isPredictionAllowed() -> "EDITOR_POLICY_BLOCKED"
            !selectionStateKnown -> "SELECTION_UNKNOWN"
            !selectionIsCollapsed -> "SELECTION_NOT_COLLAPSED"
            else -> null
        }
        if (suppressionReason != null) {
            editorTextCache.invalidate()
            val bindStartedAt = SystemClock.elapsedRealtimeNanos()
            suggestionView?.showSuggestions(emptyList())
            suggestionBindLatency.recordNanos(
                SystemClock.elapsedRealtimeNanos() - bindStartedAt
            )
            refreshSnapshotLatency.recordNanos(
                SystemClock.elapsedRealtimeNanos() - refreshStartedAt
            )
            return
        }

        // Reuse the locally maintained snapshot when it is known to match the
        // editor. Otherwise perform one bounded InputConnection read and reseed.
        val snapshot = if (textBeforeCursorOverride != null) {
            textBeforeCursorOverride
        } else {
            readTextBeforeCursorSnapshot(connection)
        }
        val textBeforeCursor = snapshot.orEmpty()
        val usePersonalization = isPersonalLearningAllowed()

        try {
            predictionExecutor.execute {
                val predictionStartedAt = SystemClock.elapsedRealtimeNanos()
                val results = try {
                    predictionEngine.predict(
                        textBeforeCursor,
                        3,
                        usePersonalization = usePersonalization
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Prediction failed; keeping keyboard input available", error)
                    emptyList()
                }
                val predictionDuration = SystemClock.elapsedRealtimeNanos() - predictionStartedAt
                predictionLatency.recordNanos(predictionDuration)
                mainHandler.post {
                    val currentGeneration = predictionGeneration.get()
                    if (serviceDestroyed || requestId != currentGeneration) return@post

                    val bindStartedAt = SystemClock.elapsedRealtimeNanos()
                    val row = suggestionView
                    row?.showSuggestions(results)
                    suggestionBindLatency.recordNanos(
                        SystemClock.elapsedRealtimeNanos() - bindStartedAt
                    )
                    suggestionUpdateLatency.recordNanos(
                        SystemClock.elapsedRealtimeNanos() - refreshStartedAt
                    )
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Prediction task could not be scheduled", error)
        }

        refreshSnapshotLatency.recordNanos(
            SystemClock.elapsedRealtimeNanos() - refreshStartedAt
        )
    }

    /** Main-thread-only snapshot access. Cache misses are synchronised with the editor. */
    private fun readTextBeforeCursorSnapshot(connection: android.view.inputmethod.InputConnection): String? {
        if (selectionStateKnown && selectionIsCollapsed) {
            val cached = editorTextCache.getIfFresh(SystemClock.elapsedRealtimeNanos())
            if (cached != null) {
                editorCacheHits++
                return cached
            }
        }

        val readStartedAt = SystemClock.elapsedRealtimeNanos()
        val text = try {
            connection.getTextBeforeCursor(160, 0)?.toString()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read text before cursor", error)
            null
        }
        inputConnectionLatency.recordNanos(
            SystemClock.elapsedRealtimeNanos() - readStartedAt
        )
        inputConnectionReads++

        if (text != null && selectionStateKnown && selectionIsCollapsed) {
            editorTextCache.seed(text, SystemClock.elapsedRealtimeNanos())
        } else {
            editorTextCache.invalidate()
        }
        return text
    }

    private fun commitSuggestion(word: String) {
        // A suggestion is valid only for a known collapsed caret in a field
        // that permits prediction. Never replace a selected range from a stale row.
        if (!selectionStateKnown || !selectionIsCollapsed || !isPredictionAllowed()) {
            clearSuggestionsAndInvalidatePredictions()
            return
        }
        val connection = currentInputConnection ?: return
        val beforeCursor = readTextBeforeCursorSnapshot(connection) ?: run {
            clearSuggestionsAndInvalidatePredictions()
            return
        }
        val lastCharacter = beforeCursor.lastOrNull()
        val endsWithSpace = lastCharacter?.isWhitespace() == true
        val currentWord = if (!endsWithSpace) {
            Regex("[\\p{L}\\p{N}']+$")
                .find(beforeCursor)
                ?.value
                .orEmpty()
        } else {
            ""
        }

        val precedingText = if (currentWord.isNotEmpty()) {
            beforeCursor.dropLast(currentWord.length)
        } else {
            beforeCursor
        }
        if (selectionStateKnown && selectionIsCollapsed) {
            learnWord(word, precedingText)
        }

        val correctedCase = preserveCapitalization(word, currentWord)
        val separator = if (
            lastCharacter == ',' || lastCharacter == ';' || lastCharacter == ':'
        ) " " else ""
        val committedText = "$separator$correctedCase "

        if (currentWord.isNotEmpty() &&
            !connection.deleteSurroundingText(currentWord.length, 0)
        ) {
            editorTextCache.invalidate()
            clearSuggestionsAndInvalidatePredictions()
            return
        }
        if (!connection.commitText(committedText, 1)) {
            editorTextCache.invalidate()
            clearSuggestionsAndInvalidatePredictions()
            return
        }

        val updatedSnapshot = if (selectionStateKnown && selectionIsCollapsed) {
            editorTextCache.applyLocalEdit(
                deletedBeforeCursor = currentWord.length,
                insertedText = committedText
            )
        } else {
            null
        }

        if (updatedSnapshot != null) {
            refreshSuggestions(updatedSnapshot)
        } else {
            editorTextCache.invalidate()
            refreshSuggestions()
        }
    }

    private fun preserveCapitalization(suggestion: String, original: String): String {
        if (original.isEmpty()) return suggestion

        val letters = original.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            return suggestion.uppercase()
        }
        if (original.first().isUpperCase()) {
            return suggestion.replaceFirstChar { it.uppercase() }
        }
        return suggestion
    }

    /**
     * True only for a URI editor owned by a known browser package. Browser
     * omniboxes sometimes set NO_SUGGESTIONS as a request to suppress the
     * system candidate strip, but users may still explicitly want local IME
     * suggestions while searching or entering an address.
     */
    private fun isBrowserOmnibox(info: EditorInfo): Boolean {
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return inputClass == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_URI &&
            BROWSER_OMNIBOX_PACKAGES.contains(info.packageName.orEmpty())
    }

    /**
     * Keep editor opt-outs everywhere except a narrowly identified browser
     * omnibox. Password and email fields remain excluded. URI predictions never
     * enable personal learning (see isPersonalLearningAllowed()).
     */
    private fun isPredictionAllowed(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (isSensitiveField()) return false

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isUriField = variation == InputType.TYPE_TEXT_VARIATION_URI
        val browserOmnibox = isBrowserOmnibox(info)
        val noSuggestions =
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        // Honor NO_SUGGESTIONS everywhere except recognised browser URI bars.
        // Never use this exception for password, email, numeric or webpage fields.
        if (noSuggestions && !browserOmnibox) return false

        // AUTOCOMPLETE editors are normally owned by the app. URI omniboxes are
        // the narrow exception because NOVA's suggestions are local and opt-in
        // through the supported browser allowlist.
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0 && !isUriField) {
            return false
        }

        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> false
            InputType.TYPE_TEXT_VARIATION_URI -> true
            else -> true
        }
    }

    /** Personal learning is narrower than prediction and obeys editor opt-outs. */
    private fun isPersonalLearningAllowed(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (!isPredictionAllowed()) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI) return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        return true
    }

    private fun isSensitiveField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        return (
            inputClass == InputType.TYPE_CLASS_TEXT &&
                (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
        ) || (
            inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
    }

    /**
     * Perform the action requested by the editor (Send/Search/Done/Next/etc.).
     * Multiline editors and IME_FLAG_NO_ENTER_ACTION explicitly require a newline.
     */
    private fun performConfiguredEditorAction(
        connection: android.view.inputmethod.InputConnection
    ): Boolean {
        val info = currentInputEditorInfo ?: return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) return false

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
            return false
        }

        if (!connection.performEditorAction(action)) {
            Log.w(TAG, "Editor did not handle requested IME action: $action")
        }
        // Do not insert a newline as a fallback for an explicit action: doing so
        // can send an unintended message or submit an unintended form.
        return true
    }

    private data class GraphemeDeletionResult(
        val deleted: Boolean,
        val deletedUtf16Units: Int?
    )

    /**
     * Delete the previous extended grapheme cluster. If the UTF-16 deletion API
     * is rejected, retry with the equivalent code-point count, then delegate to
     * the editor's native DEL handling. A null UTF-16 count requires cache resync.
     */
    private fun deletePreviousGrapheme(
        connection: android.view.inputmethod.InputConnection,
        textBeforeCursor: String
    ): GraphemeDeletionResult {
        val utf16Units = previousGraphemeLength(textBeforeCursor)
        if (utf16Units <= 0) return GraphemeDeletionResult(false, null)

        if (connection.deleteSurroundingText(utf16Units, 0)) {
            return GraphemeDeletionResult(true, utf16Units)
        }

        val clusterStart = textBeforeCursor.length - utf16Units
        val codePointCount = Character.codePointCount(
            textBeforeCursor,
            clusterStart,
            textBeforeCursor.length
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && codePointCount > 0 &&
            connection.deleteSurroundingTextInCodePoints(codePointCount, 0)
        ) {
            return GraphemeDeletionResult(true, utf16Units)
        }

        return GraphemeDeletionResult(sendEditorBackspace(connection), null)
    }

    /** Ask the active editor to perform its own backspace implementation. */
    private fun sendEditorBackspace(
        connection: android.view.inputmethod.InputConnection
    ): Boolean {
        val downSent = connection.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        )
        val upSent = connection.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
        )
        return downSent || upSent
    }

    private fun showEmojiPanel() {
        if (emojiPanelVisible) return
        emojiPanelVisible = true
        predictionGeneration.incrementAndGet()
        suggestionView?.showSuggestions(emptyList())
        suggestionView?.visibility = View.GONE
        keyboardView?.visibility = View.GONE
        emojiPickerView?.visibility = View.VISIBLE
        emojiPickerView?.requestFocus()
    }

    private fun dismissEmojiPanel(refresh: Boolean) {
        if (!emojiPanelVisible) return
        emojiPanelVisible = false
        emojiPickerView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        suggestionView?.visibility = View.VISIBLE
        if (refresh) refreshSuggestions()
    }

    /** Emoji insertion is a normal editor commit, never a personal-learning event. */
    private fun commitEmoji(emoji: String) {
        val connection = currentInputConnection ?: return
        if (!connection.commitText(emoji, 1)) return
        editorTextCache.invalidate()
        clearSuggestionsAndInvalidatePredictions()
        // Keep the picker open for consecutive emoji selections.
    }

    /** Return UTF-16 units in the previous grapheme cluster in the supplied text. */
    private fun previousGraphemeLength(text: String): Int {
        if (text.isEmpty()) return 0
        val codePointFallback = Character.charCount(Character.codePointBefore(text, text.length))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return codePointFallback

        return try {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(text)
            val boundary = iterator.preceding(text.length)
            if (boundary == BreakIterator.DONE) codePointFallback else text.length - boundary
        } catch (_: RuntimeException) {
            codePointFallback
        }
    }

    private fun clearSuggestionsAndInvalidatePredictions() {
        predictionGeneration.incrementAndGet()
        suggestionView?.showSuggestions(emptyList())
    }

    private fun flushSpatialShadowStats(reason: String) {
        val snapshot = spatialShadowStats.snapshotAndReset() ?: return
        val disagreementPercent = snapshot.winnerDisagreements * 100.0 / snapshot.taps
        val lowConfidencePercent = snapshot.lowConfidenceTaps * 100.0 / snapshot.taps
        Log.i(
            TAG,
            "SPATIAL_SHADOW REASON=$reason TAPS=${snapshot.taps} " +
                "GAUSSIAN_VS_PHASE_A_DISAGREEMENT_PERCENT=${String.format(Locale.US, "%.2f", disagreementPercent)} " +
                "LOW_CONFIDENCE_LT_0_70_PERCENT=${String.format(Locale.US, "%.2f", lowConfidencePercent)} " +
                "MEAN_ENTROPY_BITS=${String.format(Locale.US, "%.3f", snapshot.meanEntropyBits)}"
        )
    }

    private fun flushPerformanceMetrics(reason: String) {
        val totalCacheRequests = editorCacheHits + inputConnectionReads
        if (totalCacheRequests > 0L) {
            val hitRate = editorCacheHits * 100.0 / totalCacheRequests
            Log.i(
                TAG,
                "EDITOR_CONTEXT_CACHE REASON=$reason HITS=$editorCacheHits " +
                    "INPUT_CONNECTION_READS=$inputConnectionReads " +
                    "HIT_RATE_PERCENT=${String.format(java.util.Locale.US, "%.1f", hitRate)}"
            )
        }
        editorCacheHits = 0L
        inputConnectionReads = 0L
        keyHandlerLatency.flush(reason)
        touchReleaseLatency.flush(reason)
        suggestionCommitLatency.flush(reason)
        inputConnectionLatency.flush(reason)
        predictionLatency.flush(reason)
        suggestionBindLatency.flush(reason)
        refreshSnapshotLatency.flush(reason)
        suggestionUpdateLatency.flush(reason)
    }

    private fun elapsedMs(startedAtNanos: Long): String =
        String.format(
            java.util.Locale.US,
            "%.3f",
            (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000.0
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
