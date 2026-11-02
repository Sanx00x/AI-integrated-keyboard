package com.nova.keyboard.prediction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.keyboard.data.UserLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side smoke tests for the NOVA Tanglish binary asset.
 * These tests load the packaged asset from the installed APK, not from the
 * project directory on the development machine.
 */
@RunWith(AndroidJUnit4::class)
class NovaBinaryModelInstrumentedTest {

    private fun loadModel(): NovaBinaryModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NovaBinaryModel.load(context)
    }

    @Test
    fun bundledAssetLoadsAndMetadataIsValid() {
        val model = loadModel()

        assertEquals("NOVA Tanglish Language Model", model.metadata.modelName)
        assertEquals(1, model.metadata.formatVersion)
        assertTrue("Dataset metadata should identify the Tanglish corpus", model.metadata.dataset.contains("Tanglish"))
        assertTrue("License metadata should retain CC BY 4.0", model.metadata.license.contains("CC BY 4.0"))
        assertTrue("Attribution metadata must not be empty", model.metadata.attribution.isNotBlank())

        assertEquals(21_851, model.vocabularySize)
        assertEquals(19_173, model.bigramContextCount)
        assertEquals(42_929, model.trigramContextCount)
    }

    @Test
    fun knownBigramAndTrigramContextsReturnCandidates() {
        val model = loadModel()

        val afterSemma = model.bigramSuggestions("semma", limit = 5).map { it.word }
        assertFalse("Expected candidates after 'semma'", afterSemma.isEmpty())
        assertTrue("Expected the trained 'mass' continuation", afterSemma.contains("mass"))

        val afterNaIniku = model.trigramSuggestions("na", "iniku", limit = 5).map { it.word }
        assertFalse("Expected candidates for 'na iniku'", afterNaIniku.isEmpty())
        assertTrue("Expected the exported 'tha' continuation", afterNaIniku.contains("tha"))
    }

    @Test
    fun predictionEngineUsesTrainedTanglishContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = NovaBinaryModel.load(context)
        val engine = PredictionEngine()
        engine.setBinaryModel(model)

        val afterNaIniku = engine.predict("na iniku ", limit = 3, usePersonalization = false)
            .map { it.word }
        assertTrue("Expected trained continuation 'tha' after 'na iniku'", afterNaIniku.contains("tha"))

        val afterSemma = engine.predict("semma ", limit = 5, usePersonalization = false)
            .map { it.word }
        assertTrue("Expected trained continuation 'mass' after 'semma'", afterSemma.contains("mass"))
    }

    @Test
    fun binaryModelAddsPrefixSuggestionsWithoutReplacingBuiltInWords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = NovaBinaryModel.load(context)
        val engine = PredictionEngine()
        engine.setBinaryModel(model)

        val suggestions = engine.predict("vid", limit = 5, usePersonalization = false)
        assertTrue("Expected corpus-only word 'video' from the binary prefix index", suggestions.any { it.word == "video" })
    }


    @Test
    fun prefixLookupIncludesContextOnlyVocabularyEntries() {
        val model = loadModel()

        // "mouna" is part of the exported vocabulary but has no exact unigram
        // entry. It must still be discoverable through prefix completion.
        val matches = model.prefixSuggestions("mou", limit = 16).map { it.word }
        assertTrue("Expected context-only word 'mouna' in prefix results", matches.contains("mouna"))

        val contextOnlyCompletion = PredictionEngine().also { it.setBinaryModel(model) }
            .predict("mou", limit = 5, usePersonalization = false)
            .map { it.word }
        assertTrue(
            "Expected PredictionEngine to surface context-only word 'mouna'",
            contextOnlyCompletion.contains("mouna")
        )
    }

    @Test
    fun trigramAndBigramCandidatesAreMergedToFillContextSlots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = PredictionEngine()
        engine.setBinaryModel(NovaBinaryModel.load(context))

        // The exact "na iniku" trigram contains only "tha". The "iniku"
        // bigram contains additional alternatives, including "video".
        val suggestions = engine.predict(
            "na iniku ",
            limit = 8,
            usePersonalization = false
        ).map { it.word }

        assertTrue("Expected the exact trigram continuation 'tha'", suggestions.contains("tha"))
        assertTrue("Expected a supplementary bigram continuation 'video'", suggestions.contains("video"))
    }


    @Test
    fun unknownNextWordContextUsesGlobalCorpusFallback() {
        val engine = PredictionEngine()
        engine.setBinaryModel(loadModel())
        val results = engine.predict(
            "totally unknown sequence xyz ",
            limit = 3,
            usePersonalization = false
        )
        assertEquals("Expected fallback suggestions for an unseen context", 3, results.size)
    }

    @Test
    fun prefixLookupAndUnknownContextFallbackAreSafe() {
        val model = loadModel()

        val prefixMatches = model.prefixSuggestions("bro", limit = 5)
        assertFalse("Expected vocabulary matches for prefix 'bro'", prefixMatches.isEmpty())
        assertTrue(prefixMatches.all { it.word.startsWith("bro") })

        val expectedFallback = model.topUnigrams(limit = 3)
        val actualFallback = model.suggestNext(
            previousWord = "nova_unknown_context_token",
            limit = 3
        )
        assertEquals(expectedFallback, actualFallback)

        assertTrue("Unknown token should not be reported as present", !model.containsToken("nova_unknown_token"))
    }
    @Test
    fun personalLearningAdaptsToRepeatedRomanTanglishWithoutTakingOverImmediately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "nova_personal_language_instrumented_test",
            android.content.Context.MODE_PRIVATE
        )
        preferences.edit().clear().commit()

        val personalModel = UserLanguageModel(
            context,
            "nova_personal_language_instrumented_test"
        )

        try {
            // A single occurrence is deliberately not enough to promote a word.
            personalModel.learn("v2ku", listOf("naan", "poitu"))
            assertTrue(personalModel.prefixMatches("v2").isEmpty())

            // Repeated committed use creates a personal prefix and context signal.
            personalModel.learn("v2ku", listOf("naan", "poitu"))
            assertTrue(personalModel.prefixMatches("v2").any { it.first == "v2ku" })
            assertTrue(
                personalModel.contextSuggestions(listOf("naan", "poitu")).containsKey("v2ku")
            )

            // The debounced save must survive constructing a fresh model instance.
            Thread.sleep(1_500)
            val reloadedModel = UserLanguageModel(
                context,
                "nova_personal_language_instrumented_test"
            )
            try {
                assertTrue(reloadedModel.prefixMatches("v2").any { it.first == "v2ku" })
                assertTrue(
                    reloadedModel.contextSuggestions(listOf("naan", "poitu")).containsKey("v2ku")
                )
            } finally {
                reloadedModel.close()
            }

            val engine = PredictionEngine(personalModel = personalModel)
            val prefixSuggestions = engine.predict(
                "naan poitu v2",
                limit = 3,
                usePersonalization = true
            ).map { it.word }
            assertTrue(
                "Expected the repeatedly used personal spelling 'v2ku'",
                prefixSuggestions.contains("v2ku")
            )

            // Numeric-only entries should not become personal words.
            personalModel.learn("12345", listOf("naan"))
            assertTrue(personalModel.prefixMatches("123").isEmpty())
        } finally {
            personalModel.close()
        }
    }


    @Test
    fun prefixExistenceUsesFullExportedVocabulary() {
        val model = loadModel()
        assertTrue("Common prefix should be indexed", model.hasPrefixCandidate("in"))
        assertTrue("Context-only word prefixes remain eligible", model.hasPrefixCandidate("mou"))
        assertFalse("Unknown prefix must not match", model.hasPrefixCandidate("qzxqzx"))
    }
}
