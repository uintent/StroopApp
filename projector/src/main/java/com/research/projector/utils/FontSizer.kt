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
 * Utility class for automatically sizing fonts to fit Stroop stimuli optimally.
 * Calculates font sizes based on the longest color word and available display area.
 */
class FontSizer(private val context: Context) {
    
    private val displayMetrics: DisplayMetrics = context.resources.displayMetrics
    
    companion object {
        // Font sizing constraints (in sp)
        private const val MIN_FONT_SIZE_SP = 24f
        private const val MAX_FONT_SIZE_SP = 200f
        private const val DEFAULT_FONT_SIZE_SP = 72f
        
        // Margin in dp (approximately 1cm as specified)
        private const val HORIZONTAL_MARGIN_DP = 38f
        private const val VERTICAL_MARGIN_DP = 20f
        
        // Safety margins to ensure text doesn't touch edges
        private const val SAFETY_MARGIN_DP = 8f
        
        // Font family for consistent rendering
        private const val FONT_FAMILY = "sans-serif"
    }
    
    /**
     * Calculate optimal font size for Stroop display based on available words
     * and screen dimensions with specified margins
     */
    fun calculateOptimalFontSize(
        runtimeConfig: RuntimeConfig,
        availableWidth: Int,
        availableHeight: Int
    ): FontSizingResult {
        
        val colorWords = runtimeConfig.baseConfig.getColorWords()
        
        if (colorWords.isEmpty()) {
            return FontSizingResult.Error("No color words available for sizing")
        }
        
        // Find the longest word (by rendered width)
        val longestWord = findLongestWord(colorWords)
        
        // Calculate available display area with margins
        val horizontalMarginPx = dpToPx(HORIZONTAL_MARGIN_DP * 2) // left + right
        val verticalMarginPx = dpToPx(VERTICAL_MARGIN_DP * 2) // top + bottom
        val safetyMarginPx = dpToPx(SAFETY_MARGIN_DP * 2) // additional safety
        
        val maxTextWidth = availableWidth - horizontalMarginPx - safetyMarginPx
        val maxTextHeight = availableHeight - verticalMarginPx - safetyMarginPx
        
        if (maxTextWidth <= 0 || maxTextHeight <= 0) {
            return FontSizingResult.Error("Insufficient display area after margins")
        }
        
        // Binary search for optimal font size
        val optimalSize = findOptimalFontSize(
            text = longestWord,
            maxWidth = maxTextWidth,
            maxHeight = maxTextHeight
        )
        
        return FontSizingResult.Success(
            fontSize = optimalSize,
            longestWord = longestWord,
            textWidth = calculateTextWidth(longestWord, optimalSize),
            textHeight = calculateTextHeight(longestWord, optimalSize),
            availableWidth = maxTextWidth,
            availableHeight = maxTextHeight
        )
    }
    
    /**
     * Find the word that will render with the largest width
     */
    private fun findLongestWord(words: List<String>): String {
        var longestWord = words.first()
        var maxWidth = 0f
        
        // Use a base font size for comparison
        val baseFontSize = spToPx(DEFAULT_FONT_SIZE_SP)
        
        for (word in words) {
            val width = calculateTextWidth(word, baseFontSize)
            if (width > maxWidth) {
                maxWidth = width
                longestWord = word
            }
        }
        
        return longestWord
    }
    
    /**
     * Binary search to find the largest font size that fits within constraints
     */
    private fun findOptimalFontSize(text: String, maxWidth: Int, maxHeight: Int): Float {
        var minSize = MIN_FONT_SIZE_SP
        var maxSize = MAX_FONT_SIZE_SP
        var optimalSize = DEFAULT_FONT_SIZE_SP
        
        // Binary search with precision of 0.5sp
        while (maxSize - minSize > 0.5f) {
            val testSize = (minSize + maxSize) / 2f
            val testSizePx = spToPx(testSize)
            
            val textWidth = calculateTextWidth(text, testSizePx)
            val textHeight = calculateTextHeight(text, testSizePx)
            
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
            // Remove font padding for accurate measurements
            isSubpixelText = true
        }
    }
    
    /**
     * Calculate countdown font size (larger than Stroop text)
     */
    fun calculateCountdownFontSize(
        stroopFontSize: Float,
        availableWidth: Int,
        availableHeight: Int
    ): Float {
        // Countdown should be larger than Stroop text but fit "3", "2", "1", "0"
        val baseCountdownSize = stroopFontSize * 1.5f
        
        // Test with "3" (typical widest single digit)
        val maxCountdownSize = findOptimalFontSize(
            text = "3",
            maxWidth = availableWidth - dpToPx(SAFETY_MARGIN_DP * 2),
            maxHeight = availableHeight - dpToPx(SAFETY_MARGIN_DP * 2)
        )
        
        // Use the smaller of the calculated sizes
        return min(baseCountdownSize, maxCountdownSize)
    }
    
    /**
     * Get font sizing information for all words
     */
    fun analyzeFontSizing(
        runtimeConfig: RuntimeConfig,
        fontSize: Float
    ): FontAnalysis {
        val colorWords = runtimeConfig.baseConfig.getColorWords()
        val fontSizePx = spToPx(fontSize)
        
        val wordMeasurements = colorWords.map { word ->
            WordMeasurement(
                word = word,
                width = calculateTextWidth(word, fontSizePx),
                height = calculateTextHeight(word, fontSizePx)
            )
        }
        
        val maxWidth = wordMeasurements.maxOfOrNull { it.width } ?: 0f
        val minWidth = wordMeasurements.minOfOrNull { it.width } ?: 0f
        val avgWidth = wordMeasurements.map { it.width }.average().toFloat()
        
        return FontAnalysis(
            fontSize = fontSize,
            wordMeasurements = wordMeasurements,
            maxWidth = maxWidth,
            minWidth = minWidth,
            averageWidth = avgWidth,
            widthVariation = maxWidth - minWidth
        )
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
        
        // Font should be between 0.2" and 2" for good readability
        if (fontSizeInches < 0.2f) {
            return FontValidationResult.Error("Font too small for peripheral vision reading")
        }
        
        if (fontSizeInches > 2.0f) {
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
    
    /**
     * Get font sizing recommendations for different screen sizes
     */
    fun getFontSizeRecommendations(): FontRecommendations {
        return FontRecommendations(
            phonePortrait = FontSizeRange(32f, 48f),
            phoneLandscape = FontSizeRange(48f, 72f),
            tabletPortrait = FontSizeRange(56f, 84f),
            tabletLandscape = FontSizeRange(72f, 120f)
        )
    }
}

/**
 * Result of font size calculation
 */
sealed class FontSizingResult {
    data class Success(
        val fontSize: Float,
        val longestWord: String,
        val textWidth: Float,
        val textHeight: Float,
        val availableWidth: Int,
        val availableHeight: Int
    ) : FontSizingResult() {
        
        fun getUtilization(): Float {
            val widthUtil = textWidth / availableWidth
            val heightUtil = textHeight / availableHeight
            return maxOf(widthUtil, heightUtil)
        }
        
        fun getSummary(): String {
            return "Font: ${fontSize}sp, Word: '$longestWord', Size: ${textWidth.toInt()}x${textHeight.toInt()}px, Utilization: ${(getUtilization() * 100).toInt()}%"
        }
    }
    
    data class Error(val message: String) : FontSizingResult()
}

/**
 * Font validation result
 */
sealed class FontValidationResult {
    data class Valid(val fontSize: Float) : FontValidationResult()
    data class Error(val message: String) : FontValidationResult()
}

/**
 * Analysis of font sizing for all words
 */
data class FontAnalysis(
    val fontSize: Float,
    val wordMeasurements: List<WordMeasurement>,
    val maxWidth: Float,
    val minWidth: Float,
    val averageWidth: Float,
    val widthVariation: Float
) {
    fun getConsistencyRating(): String {
        val variationRatio = widthVariation / averageWidth
        return when {
            variationRatio < 0.3f -> "Excellent"
            variationRatio < 0.5f -> "Good"
            variationRatio < 0.8f -> "Fair"
            else -> "Poor"
        }
    }
}

/**
 * Measurement data for individual words
 */
data class WordMeasurement(
    val word: String,
    val width: Float,
    val height: Float
)

/**
 * Font size recommendations for different screen configurations
 */
data class FontRecommendations(
    val phonePortrait: FontSizeRange,
    val phoneLandscape: FontSizeRange,
    val tabletPortrait: FontSizeRange,
    val tabletLandscape: FontSizeRange
)

/**
 * Font size range with min and max values
 */
data class FontSizeRange(
    val minSize: Float,
    val maxSize: Float
) {
    fun contains(size: Float): Boolean = size in minSize..maxSize
    
    fun getRecommended(): Float = (minSize + maxSize) / 2f
}