package de.michelinside.glucodatahandler.common.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import de.michelinside.glucodatahandler.common.prediction.TrendCategory
import de.michelinside.glucodatahandler.common.prediction.TrendProbabilities

/**
 * Custom view that displays trend probabilities as vertical bars.
 * 
 * Shows 7 vertical bars (one per trend category) with:
 * - Percentage above each bar
 * - Bar height proportional to probability
 * - Arrow symbol, delta range, and projected glucose below each bar
 */
class TrendProbabilityChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Single bar color (Dodger Blue)
    private val barColor = Color.rgb(30, 144, 255)
    
    // Paints
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.argb(240, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 255, 255, 255)
    }
    
    // Data
    private var trendProbabilities: TrendProbabilities? = null
    private var selectedHorizon: Int = 15
    private var currentGlucose: Int = 0
    
    // Trend order (left to right: double down to double up)
    private val trendOrder = listOf(
        TrendCategory.DOUBLE_DOWN,
        TrendCategory.DOWN,
        TrendCategory.SLIGHTLY_DOWN,
        TrendCategory.FLAT,
        TrendCategory.SLIGHTLY_UP,
        TrendCategory.UP,
        TrendCategory.DOUBLE_UP
    )
    
    /**
     * Set the trend probabilities to display.
     */
    fun setTrendProbabilities(probabilities: TrendProbabilities?, glucose: Int = 0) {
        this.trendProbabilities = probabilities
        this.selectedHorizon = probabilities?.horizon ?: 15
        this.currentGlucose = glucose
        invalidate()
    }
    
    /**
     * Get the delta range (min, max) for a trend category at the given horizon.
     * Thresholds are rate * horizon where rates are:
     * - Double down: < -3 mg/dL/min
     * - Down: -3 to -2
     * - Slightly down: -2 to -1
     * - Flat: -1 to +1
     * - Slightly up: +1 to +2
     * - Up: +2 to +3
     * - Double up: > +3
     */
    private fun getDeltaRange(category: TrendCategory, horizon: Int): Pair<Int, Int> {
        return when (category) {
            TrendCategory.DOUBLE_DOWN -> Pair(Int.MIN_VALUE, -3 * horizon)
            TrendCategory.DOWN -> Pair(-3 * horizon, -2 * horizon)
            TrendCategory.SLIGHTLY_DOWN -> Pair(-2 * horizon, -1 * horizon)
            TrendCategory.FLAT -> Pair(-1 * horizon, 1 * horizon)
            TrendCategory.SLIGHTLY_UP -> Pair(1 * horizon, 2 * horizon)
            TrendCategory.UP -> Pair(2 * horizon, 3 * horizon)
            TrendCategory.DOUBLE_UP -> Pair(3 * horizon, Int.MAX_VALUE)
        }
    }
    
    /**
     * Format a number with + prefix for positive values.
     */
    private fun formatWithSign(n: Int): String {
        return if (n > 0) "+$n" else "$n"
    }
    
    /**
     * Format delta range as string.
     */
    private fun formatDeltaRange(range: Pair<Int, Int>): String {
        return when {
            range.first == Int.MIN_VALUE -> "<${range.second}"
            range.second == Int.MAX_VALUE -> ">${formatWithSign(range.first)}"
            else -> "${formatWithSign(range.first)} to ${formatWithSign(range.second)}"
        }
    }
    
    /**
     * Calculate projected glucose range.
     */
    private fun getProjectedGlucose(glucose: Int, deltaRange: Pair<Int, Int>): Pair<Int, Int> {
        val minDelta = if (deltaRange.first == Int.MIN_VALUE) -100 else deltaRange.first
        val maxDelta = if (deltaRange.second == Int.MAX_VALUE) 100 else deltaRange.second
        return Pair(
            maxOf(40, glucose + minDelta),
            minOf(400, glucose + maxDelta)
        )
    }
    
    /**
     * Format projected glucose range as string.
     */
    private fun formatGlucoseRange(range: Pair<Int, Int>, deltaRange: Pair<Int, Int>): String {
        return when {
            deltaRange.first == Int.MIN_VALUE -> "<${range.second}"
            deltaRange.second == Int.MAX_VALUE -> ">${range.first}"
            else -> "${range.first}-${range.second}"
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = 280  // Minimum height
        
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredWidth, MeasureSpec.getSize(widthMeasureSpec))
            else -> desiredWidth
        }
        
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> maxOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        
        setMeasuredDimension(width, height)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val probs = trendProbabilities
        
        // Check if we have valid data
        if (probs == null) {
            arrowPaint.textSize = 28f
            canvas.drawText("Loading predictions...", width / 2f, height / 2f, arrowPaint)
            return
        }
        
        // Layout constants
        val horizontalPadding = 2f
        val verticalPadding = 8f
        val barSpacing = 2f
        val percentHeight = 44f
        val arrowHeight = 40f
        val deltaHeight = 30f
        val glucoseHeight = 30f
        val labelAreaHeight = arrowHeight + deltaHeight + glucoseHeight + 16f
        
        // Calculate bar dimensions
        val numBars = trendOrder.size
        val totalSpacing = (numBars - 1) * barSpacing + 2 * horizontalPadding
        val barWidth = (width - totalSpacing) / numBars
        val maxBarHeight = height - verticalPadding * 2 - percentHeight - labelAreaHeight - 20f
        
        // Scale text sizes based on bar width (with larger minimums)
        percentPaint.textSize = maxOf(32f, minOf(42f, barWidth * 0.70f))
        arrowPaint.textSize = maxOf(38f, minOf(48f, barWidth * 0.85f))
        labelPaint.textSize = maxOf(20f, minOf(28f, barWidth * 0.45f))
        
        for ((index, category) in trendOrder.withIndex()) {
            val probability = probs.getProbability(category)
            val barHeight = probability * maxBarHeight
            
            // Calculate bar position
            val barX = horizontalPadding + index * (barWidth + barSpacing)
            val barCenterX = barX + barWidth / 2
            val barBottom = height - verticalPadding - labelAreaHeight
            val barTop = barBottom - barHeight
            
            // Draw background bar (full height)
            val bgRect = RectF(barX, barBottom - maxBarHeight, barX + barWidth, barBottom)
            canvas.drawRoundRect(bgRect, 6f, 6f, backgroundPaint)
            
            // Draw probability bar (all bars are blue)
            if (barHeight > 0) {
                barPaint.color = barColor
                val barRect = RectF(barX, barTop, barX + barWidth, barBottom)
                canvas.drawRoundRect(barRect, 6f, 6f, barPaint)
            }
            
            // Draw percentage above bar
            val percentText = if (probability > 0 && probability < 0.01f) {
                "<1%"
            } else {
                "${(probability * 100).toInt()}%"
            }
            val percentY = barBottom - maxBarHeight - 10f
            canvas.drawText(percentText, barCenterX, percentY, percentPaint)
            
            // Draw labels below bar
            val labelStartY = barBottom + 10f
            
            // Arrow symbol
            canvas.drawText(category.symbol, barCenterX, labelStartY + arrowHeight * 0.8f, arrowPaint)
            
            // Delta range with Δ symbol
            val deltaRange = getDeltaRange(category, selectedHorizon)
            val deltaText = formatDeltaRange(deltaRange)
            canvas.drawText(deltaText, barCenterX, labelStartY + arrowHeight + deltaHeight, labelPaint)
            
            // Projected glucose (only if we have current glucose) - no arrow
            if (currentGlucose > 0) {
                val glucoseRange = getProjectedGlucose(currentGlucose, deltaRange)
                val glucoseText = formatGlucoseRange(glucoseRange, deltaRange)
                canvas.drawText(glucoseText, barCenterX, labelStartY + arrowHeight + deltaHeight + glucoseHeight + 6f, labelPaint)
            }
        }
    }
    
    /**
     * Set text color for labels and percentages.
     */
    fun setTextColor(color: Int) {
        percentPaint.color = color
        arrowPaint.color = color
        labelPaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }
}
