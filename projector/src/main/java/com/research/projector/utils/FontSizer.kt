package com.research.projector.utils

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import com.research.projector.models.RuntimeConfig
import kotlin.math.min

/**
 * UPDATED: FontSizer now calculates optimal size for each word individually
 * Each word uses the maximum possible font size that fits within screen constraints
 */
class FontSizer(private val context: Context) {

    private val displayMetrics: DisplayMetrics = context.resources.displayMetrics

    companion object {
        // Font sizing constraints (in sp)
        private const val MIN_FONT_SIZE_SP = 24f
        private const val MAX_FONT_SIZE_SP = 300f // Increased max for short words

        // Margin in dp (approximately 1cm as specified)
        private const val HORIZONTAL_MARGIN_DP = 38f
        private const val VERTICAL_MARGIN_DP = 20f

        // Safety margins to ensure text doesn't touch edges
        private const val SAFETY_MARGIN_DP = 8f

        // Font family for consistent rendering
        private const val FONT_FAMILY = "sans-serif"
    }

    /**
     * Calculate optimal font size for a specific word
     * Each word gets the maximum size that fits within available space
     */
    fun calculateOptimalFontSizeForWord(
        word: String,
        availableWidth: Int,
        availableHeight: Int
    ): WordFontSizingResult {

        if (word.isEmpty()) {
            return WordFontSizingResult.Error("Empty word provided")
        }

        // Calculate available display area with margins
        val horizontalMarginPx = dpToPx(HORIZONTAL_MARGIN_DP * 2) // left + right
        val verticalMarginPx = dpToPx(VERTICAL_MARGIN_DP * 2) // top + bottom
        val safetyMarginPx = dpToPx(SAFETY_MARGIN_DP * 2) // additional safety

        val maxTextWidth = availableWidth - horizontalMarginPx - safetyMarginPx
        val maxTextHeight = availableHeight - verticalMarginPx - safetyMarginPx

        if (maxTextWidth <= 0 || maxTextHeight <= 0) {
            return WordFontSizingResult.Error("Insufficient display area after margins")
        }

        // Binary search for optimal font size for this specific word
        val optimalSize = findOptimalFontSizeForWord(
            word = word,
            maxWidth = maxTextWidth,
            maxHeight = maxTextHeight
        )

        val actualWidth = calculateTextWidth(word, spToPx(optimalSize))
        val actualHeight = calculateTextHeight(word, spToPx(optimalSize))

        return WordFontSizingResult.Success(
            word = word,
            fontSize = optimalSize,
            textWidth = actualWidth,
            textHeight = actualHeight,
            availableWidth = maxTextWidth,
            availableHeight = maxTextHeight,
            utilizationWidth = actualWidth / maxTextWidth,
            utilizationHeight = actualHeight / maxTextHeight
        )
    }

    /**
     * Calculate optimal font sizes for all possible words in configuration
     * Returns a map of word -> font size for efficient lookup during display
     */
    fun calculateFontSizesForAllWords(
        runtimeConfig: RuntimeConfig,
        availableWidth: Int,
        availableHeight: Int
    ): AllWordsFontSizingResult {

        val colorWords = runtimeConfig.baseConfig.getColorWords()

        if (colorWords.isEmpty()) {
            return AllWordsFontSizingResult.Error("No color words available for sizing")
        }

        val wordFontSizes = mutableMapOf<String, Float>()
        val sizingResults = mutableListOf<WordFontSizingResult.Success>()

        for (word in colorWords) {
            when (val result = calculateOptimalFontSizeForWord(word, availableWidth, availableHeight)) {
                is WordFontSizingResult.Success -> {
                    wordFontSizes[word] = result.fontSize
                    sizingResults.add(result)
                }
                is WordFontSizingResult.Error -> {
                    // Use fallback size for problematic words
                    wordFontSizes[word] = 72f
                }
            }
        }

        // Calculate statistics
        val fontSizes = wordFontSizes.values.toList()
        val minSize = fontSizes.minOrNull() ?: 72f
        val maxSize = fontSizes.maxOrNull() ?: 72f
        val avgSize = fontSizes.average().toFloat()

        return AllWordsFontSizingResult.Success(
            wordFontSizes = wordFontSizes,
            sizingResults = sizingResults,
            minFontSize = minSize,
            maxFontSize = maxSize,
            averageFontSize = avgSize,
            fontSizeVariation = maxSize - minSize
        )
    }

    /**
     * Get optimal font size for a specific word during display
     * This is the main method called during Stroop display
     */
    fun getFontSizeForWord(
        word: String,
        preCalculatedSizes: Map<String, Float>
    ): Float {
        return preCalculatedSizes[word] ?: run {
            // Fallback: calculate on-the-fly if not pre-calculated
            val result = calculateOptimalFontSizeForWord(
                word,
                getCurrentDisplayWidth(),
                getCurrentDisplayHeight()
            )
            when (result) {
                is WordFontSizingResult.Success -> result.fontSize
                is WordFontSizingResult.Error -> 72f // Safe fallback
            }
        }
    }

    /**
     * Binary search to find the largest font size that fits within constraints for specific word
     */
    private fun findOptimalFontSizeForWord(word: String, maxWidth: Int, maxHeight: Int): Float {
        var minSize = MIN_FONT_SIZE_SP
        var maxSize = MAX_FONT_SIZE_SP
        var optimalSize = minSize

        // Binary search with precision of 0.5sp
        while (maxSize - minSize > 0.5f) {
            val testSize = (minSize + maxSize) / 2f
            val testSizePx = spToPx(testSize)

            val textWidth = calculateTextWidth(word, testSizePx)
            val textHeight = calculateTextHeight(word, testSizePx)

            if (textWidth <= maxWidth && textHeight <= maxHeight) {
                // Size fits, try larger
                optimalSize = testSize
                minSize = testSize
            } else {
                // Size too large, try smaller
                maxSize = testSize
            }
        }

        return optimalSize
    }

    /**
     * Calculate text width for given text and font size
     */
    private fun calculateTextWidth(text: String, fontSizePx: Float): Float {
        val paint = createPaint(fontSizePx)
        return paint.measureText(text)
    }

    /**
     * Calculate text height for given text and font size
     */
    private fun calculateTextHeight(text: String, fontSizePx: Float): Float {
        val paint = createPaint(fontSizePx)
        val rect = Rect()
        paint.getTextBounds(text, 0, text.length, rect)
        return rect.height().toFloat()
    }

    /**
     * Create Paint object with specified font size and styling
     */
    private fun createPaint(fontSizePx: Float): Paint {
        return Paint().apply {
            textSize = fontSizePx
            typeface = Typeface.create(FONT_FAMILY, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
    }

    /**
     * Calculate countdown font size (should be consistent, not word-dependent)
     */
    fun calculateCountdownFontSize(
        availableWidth: Int,
        availableHeight: Int
    ): Float {
        // Countdown uses single digits, optimize for "3" (typically widest)
        val result = calculateOptimalFontSizeForWord("3", availableWidth, availableHeight)
        return when (result) {
            is WordFontSizingResult.Success -> result.fontSize
            is WordFontSizingResult.Error -> 120f // Safe fallback
        }
    }

    /**
     * Validate font size for research requirements
     */
    fun validateFontSize(fontSize: Float, screenSizePx: Int): FontValidationResult {
        if (fontSize < MIN_FONT_SIZE_SP) {
            return FontValidationResult.Error("Font size too small for readability: ${fontSize}sp")
        }

        if (fontSize > MAX_FONT_SIZE_SP) {
            return FontValidationResult.Error("Font size too large: ${fontSize}sp")
        }

        // Check if font is reasonable for screen size
        val fontSizePx = spToPx(fontSize)
        val screenSizeInches = pxToInches(screenSizePx)
        val fontSizeInches = pxToInches(fontSizePx.toInt())

        // Font should be between 0.2" and 3" for good readability (increased max for short words)
        if (fontSizeInches < 0.2f) {
            return FontValidationResult.Error("Font too small for peripheral vision reading")
        }

        if (fontSizeInches > 3.0f) {
            return FontValidationResult.Error("Font unnecessarily large")
        }

        return FontValidationResult.Valid(fontSize)
    }

    /**
     * Utility functions for unit conversion
     */
    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics
        ).toInt()
    }

    private fun spToPx(sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, displayMetrics
        )
    }

    private fun pxToInches(px: Int): Float {
        return px / displayMetrics.densityDpi.toFloat()
    }

    // Temporary methods - should be injected from display
    private fun getCurrentDisplayWidth(): Int = 1920  // TODO: Get from actual display
    private fun getCurrentDisplayHeight(): Int = 1080  // TODO: Get from actual display
}

/**
 * Result of font size calculation for a single word
 */
sealed class WordFontSizingResult {
    data class Success(
        val word: String,
        val fontSize: Float,
        val textWidth: Float,
        val textHeight: Float,
        val availableWidth: Int,
        val availableHeight: Int,
        val utilizationWidth: Float,
        val utilizationHeight: Float
    ) : WordFontSizingResult() {

        fun getMaxUtilization(): Float = maxOf(utilizationWidth, utilizationHeight)

        fun getSummary(): String {
            return "Word: '$word', Font: ${fontSize}sp, Size: ${textWidth.toInt()}x${textHeight.toInt()}px, Util: ${(getMaxUtilization() * 100).toInt()}%"
        }
    }

    data class Error(val message: String) : WordFontSizingResult()
}

/**
 * Result of font size calculation for all words
 */
sealed class AllWordsFontSizingResult {
    data class Success(
        val wordFontSizes: Map<String, Float>,
        val sizingResults: List<WordFontSizingResult.Success>,
        val minFontSize: Float,
        val maxFontSize: Float,
        val averageFontSize: Float,
        val fontSizeVariation: Float
    ) : AllWordsFontSizingResult() {

        fun getConsistencyRating(): String {
            val variationRatio = if (averageFontSize > 0) fontSizeVariation / averageFontSize else 0f
            return when {
                variationRatio < 0.2f -> "Very Consistent"
                variationRatio < 0.4f -> "Moderately Consistent"
                variationRatio < 0.6f -> "Somewhat Variable"
                else -> "Highly Variable"
            }
        }

        fun getSummary(): String {
            return "Font range: ${minFontSize.toInt()}-${maxFontSize.toInt()}sp (avg: ${averageFontSize.toInt()}sp), ${getConsistencyRating()}"
        }

        fun getWordsWithSmallFonts(threshold: Float = 50f): List<String> {
            return wordFontSizes.filter { it.value < threshold }.keys.toList()
        }

        fun getWordsWithLargeFonts(threshold: Float = 120f): List<String> {
            return wordFontSizes.filter { it.value > threshold }.keys.toList()
        }
    }

    data class Error(val message: String) : AllWordsFontSizingResult()
}

/**
 * Font validation result (unchanged)
 */
sealed class FontValidationResult {
    data class Valid(val fontSize: Float) : FontValidationResult()
    data class Error(val message: String) : FontValidationResult()
}