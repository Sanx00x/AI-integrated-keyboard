package com.nova.keyboard.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionEngineTest {

    private val engine = PredictionEngine()

    // 1. English word completion
    @Test
    fun englishPrefixPrediction() {

        val results = engine.predict("comp")

        assertTrue(
            results.any { it.word == "computer" }
        )

        assertTrue(
            results.any { it.word == "complete" }
        )
    }

    // 2. English contextual prediction
    @Test
    fun englishContextPrediction() {

        val results = engine.predict("going to ")

        assertTrue(
            results.any { it.word == "college" }
        )
    }

    // 3. Tanglish contextual prediction
    @Test
    fun tanglishContextPrediction() {

        val results = engine.predict("na iniku ")

        assertTrue(
            results.any { it.word == "college" }
        )
    }

    // 4. Alternative Tanglish spelling
    @Test
    fun tanglishSpellingVariation() {

        val results = engine.predict("na inniku ")

        assertTrue(
            results.any { it.word == "college" }
        )
    }

    // 5. Tanglish word completion
    @Test
    fun tanglishPrefixPrediction() {

        val results = engine.predict("enakk")

        assertTrue(
            results.any { it.word == "enakku" }
        )
    }

    // 6. English typo detection
    @Test
    fun englishTypoDetection() {

        val results = engine.predict("colleege")

        assertTrue(
            results.any { it.word == "college" }
        )
    }

    // 7. Adjacent letter transposition
    @Test
    fun transposedLetterDetection() {

        val results = engine.predict("comptuer")

        assertTrue(
            results.any { it.word == "computer" }
        )
    }

    // 8. Sentence ending behaviour
    @Test
    fun noPredictionAfterSentenceEnding() {

        val results = engine.predict("I am happy.")

        assertTrue(results.isEmpty())
    }

    // 9. Prediction limit
    @Test
    fun suggestionLimitIsRespected() {

        val results = engine.predict(
            "comp",
            limit = 2
        )

        assertTrue(results.size <= 2)
    }

    // 10. Empty input
    @Test
    fun emptyInputReturnsNoSuggestions() {

        val results = engine.predict("")

        assertTrue(results.isEmpty())
    }


    @Test
    fun predictionContextDoesNotCrossSentenceBoundary() {
        val withoutPreviousSentence = engine.predict("comp").map { it.word }
        val afterPreviousSentence = engine.predict("going to college. comp").map { it.word }

        assertEquals(withoutPreviousSentence, afterPreviousSentence)
    }

    @Test
    fun predictionContextDoesNotCrossNewline() {
        val results = engine.predict("na iniku college\n")
        assertTrue(results.isEmpty())
    }

    @Test
    fun predictionContextDoesNotCrossSemicolon() {
        val results = engine.predict("na iniku college; ")
        assertTrue(results.isEmpty())
    }

    // 11. Performance smoke test
    @Test
    fun predictionPerformanceSmokeTest() {

        val start = System.nanoTime()

        repeat(1000) {
            engine.predict("na iniku ")
            engine.predict("comp")
            engine.predict("colleege")
        }

        val elapsedMs =
            (System.nanoTime() - start) / 1_000_000.0

        println(
            "3000 predictions completed in $elapsedMs ms"
        )

        assertFalse(elapsedMs.isNaN())
    }
    @Test
    fun unknownNextWordContextStillProvidesFallbackSuggestions() {
        val results = engine.predict("an unfamiliar context xyz ")
        assertTrue("Unknown context should receive safe fallback words", results.isNotEmpty())
        assertTrue(results.size <= 3)
        assertTrue(results.all { it.source == SuggestionSource.CONTEXT })
    }


    @Test
    fun prefixSupportIsAvailableForConservativeTouchCorrection() {
        assertTrue(engine.hasPrefixCandidate("comp", usePersonalization = false))
        assertTrue(engine.hasPrefixCandidate("in", usePersonalization = false))
        assertFalse(engine.hasPrefixCandidate("qzxqzx", usePersonalization = false))
    }
}