package de.michelinside.glucodatahandler.common.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that displays a probability as a capsule-shaped "pill" with a battery-fill effect.
 * 
 * The pill shows:
 * - A dark track (unfilled portion) as background
 * - A colored fill from left to right proportional to the probability
 * - Centered text showing the label and percentage
 */
class ProbabilityPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Data
    private var label: String = ""
    private var probability: Float = 0f
    private var fillColor: Int = Color.GREEN
    
    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(100, 40, 40, 40)  // Dark gray track
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    // Text outline for readability on any background
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 255, 255, 255)  // Subtle white border
        strokeWidth = 2f
    }
    
    // Rectangles for drawing
    private val trackRect = RectF()
    private val fillRect = RectF()
    
    /**
     * Set the data for this pill view.
     * @param label The zone label (e.g., "In Range")
     * @param probability The probability as a float 0-1
     * @param color The fill color for this zone
     */
    fun setData(label: String, probability: Float, color: Int) {
        this.label = label
        this.probability = probability.coerceIn(0f, 1f)
        this.fillColor = color
        fillPaint.color = color
        
        // Choose text colors based on fill color brightness AND fill percentage
        // Only use black text if the fill covers most of the pill (>60%) AND the color is bright
        // Otherwise, white text with black outline works best on the dark track
        val useDarkText = isBrightColor(color) && probability > 0.60f
        if (useDarkText) {
            textPaint.color = Color.BLACK
            textOutlinePaint.color = Color.WHITE
        } else {
            textPaint.color = Color.WHITE
            textOutlinePaint.color = Color.BLACK
        }
        
        invalidate()
    }
    
    /**
     * Determines if a color is bright enough to need dark text.
     */
    private fun isBrightColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        // Calculate perceived brightness using standard formula
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        
        return brightness > 150
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = h / 2  // Stadium shape (fully rounded ends)
        
        // Padding
        val padding = 4f
        val left = padding
        val top = padding
        val right = w - padding
        val bottom = h - padding
        
        // 1. Draw the dark track (full pill shape)
        trackRect.set(left, top, right, bottom)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)
        
        // 2. Draw the colored fill (clipped to percentage)
        if (probability > 0.005f) {  // Only draw if > 0.5%
            val fillWidth = (right - left) * probability
            fillRect.set(left, top, left + fillWidth, bottom)
            
            // Save canvas state for clipping
            canvas.save()
            
            // Clip to the pill shape
            canvas.clipRect(fillRect)
            
            // Draw a full rounded rect, but it will be clipped to fillRect
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
            
            canvas.restore()
        }
        
        // 3. Draw subtle border
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, borderPaint)
        
        // 4. Draw centered text with outline for readability
        val percentText = when {
            probability < 0.01f -> "<1%"
            else -> "${(probability * 100).toInt()}%"
        }
        val displayText = "$label: $percentText"
        
        // Center text vertically
        val textX = w / 2
        val textY = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        
        // Draw black outline first, then white text on top
        canvas.drawText(displayText, textX, textY, textOutlinePaint)
        canvas.drawText(displayText, textX, textY, textPaint)
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default size if not specified
        val defaultWidth = 200
        val defaultHeight = 80
        
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(defaultWidth, widthSize)
            else -> defaultWidth
        }
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(defaultHeight, heightSize)
            else -> defaultHeight
        }
        
        setMeasuredDimension(width, height)
    }
}

