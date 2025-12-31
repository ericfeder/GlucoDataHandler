package de.michelinside.glucodatahandler.common.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.format.DateUtils
import android.util.Log
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import de.michelinside.glucodatahandler.common.R
import de.michelinside.glucodatahandler.common.ReceiveData
import java.text.DateFormat
import java.time.Duration
import java.util.Date
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class CustomBubbleMarker(context: Context, private val showDate: Boolean = false) : MarkerView(context, R.layout.marker_layout) {
    private val LOG_ID = "GDH.Chart.BubbleMarker"
    private val arrowSize = 35
    private val arrowCircleOffset = 0f
    private var showContent = true
    
    // Dataset labels for predictions
    companion object {
        const val LABEL_PREDICTED = "Predicted"
        const val LABEL_LOWER = "Lower"
        const val LABEL_UPPER = "Upper"
    }

    override fun refreshContent(e: Entry?, highlight: Highlight) {
        Log.v(LOG_ID, "refreshContent - index ${highlight.dataSetIndex}, x=${e?.x}")
        try {
            val chart = chartView as? LineChart
            
            showContent = true
            
            e?.let { entry ->
                val date: TextView = this.findViewById(R.id.date)
                val time: TextView = this.findViewById(R.id.time)
                val glucose: TextView = this.findViewById(R.id.glucose)
                val delta: TextView = this.findViewById(R.id.delta)
                val prediction: TextView = this.findViewById(R.id.prediction)
                val predictionInterval: TextView = this.findViewById(R.id.prediction_interval)
                val layout: LinearLayoutCompat = this.findViewById(R.id.marker_layout)
                
                val timeValue = TimeValueFormatter.from_chart_x(entry.x)
                val dateValue = Date(timeValue)
                val currentTime = ReceiveData.time
                val isInFuture = timeValue > currentTime
                
                // Show date if needed
                date.visibility = if(showDate && !DateUtils.isToday(timeValue)) VISIBLE else GONE
                date.text = DateFormat.getDateInstance(DateFormat.SHORT).format(dateValue)
                
                // Show time
                time.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(dateValue)
                
                if (isInFuture) {
                    // Future time - show prediction info
                    val minutesAhead = Duration.ofMillis(timeValue - currentTime).toMinutes()
                    
                    // Show time with minutes ahead on a single line
                    time.text = "${DateFormat.getTimeInstance(DateFormat.SHORT).format(dateValue)} [+${minutesAhead} min]"
                    
                    // Hide actual glucose fields
                    glucose.visibility = GONE
                    delta.visibility = GONE
                    
                    // Find prediction values for this time
                    val predictionValues = findPredictionValuesAtX(chart, entry.x)
                    if (predictionValues != null) {
                        prediction.visibility = VISIBLE
                        prediction.text = "Pred: ${GlucoseFormatter.getValueAsString(predictionValues.q50)}"
                        
                        predictionInterval.visibility = VISIBLE
                        predictionInterval.text = "Range: ${GlucoseFormatter.getValueAsString(predictionValues.q10)} - ${GlucoseFormatter.getValueAsString(predictionValues.q90)}"
                    } else {
                        prediction.visibility = GONE
                        predictionInterval.visibility = GONE
                    }
                } else {
                    // Past/present time - show actual glucose
                    val glucoseValue = findGlucoseValueAtX(chart, entry.x)
                    if (glucoseValue != null) {
                        glucose.visibility = VISIBLE
                        glucose.text = GlucoseFormatter.getValueAsString(glucoseValue)
                        
                        val timeDiff = Duration.ofMillis(currentTime - timeValue)
                        if(timeDiff.toMinutes() > 0) {
                            delta.visibility = VISIBLE
                            "Δ ${GlucoseFormatter.getValueAsString(ReceiveData.rawValue - glucoseValue)} (${resources.getString(R.string.elapsed_time, timeDiff.toMinutes())})".also { delta.text = it }
                        } else {
                            delta.visibility = GONE
                        }
                    } else {
                        glucose.visibility = VISIBLE
                        glucose.text = GlucoseFormatter.getValueAsString(entry.y)
                        delta.visibility = GONE
                    }
                    
                    // Hide prediction fields for past time
                    prediction.visibility = GONE
                    predictionInterval.visibility = GONE
                }
                
                layout.visibility = VISIBLE
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "Exception in refreshContent", exc)
        }
        super.refreshContent(e, highlight)
    }
    
    /**
     * Find prediction values (Q50, Q10, Q90) at the given x position.
     */
    private fun findPredictionValuesAtX(chart: LineChart?, x: Float): PredictionValues? {
        if (chart?.data == null) return null
        
        var q50: Float? = null
        var q10: Float? = null
        var q90: Float? = null
        
        for (i in 0 until chart.data.dataSetCount) {
            val dataSet = chart.data.getDataSetByIndex(i) as? LineDataSet ?: continue
            val entry = findClosestEntry(dataSet, x)
            
            when (dataSet.label) {
                LABEL_PREDICTED -> q50 = entry?.y
                LABEL_LOWER -> q10 = entry?.y
                LABEL_UPPER -> q90 = entry?.y
            }
        }
        
        return if (q50 != null && q10 != null && q90 != null) {
            PredictionValues(q50, q10, q90)
        } else null
    }
    
    /**
     * Find the glucose value at the given x position from the main dataset.
     */
    private fun findGlucoseValueAtX(chart: LineChart?, x: Float): Float? {
        if (chart?.data == null || chart.data.dataSetCount == 0) return null
        
        val glucoseDataSet = chart.data.getDataSetByIndex(0) as? LineDataSet ?: return null
        val entry = findClosestEntry(glucoseDataSet, x)
        return entry?.y
    }
    
    /**
     * Find the entry closest to the given x value.
     */
    private fun findClosestEntry(dataSet: LineDataSet, x: Float): Entry? {
        if (dataSet.entryCount == 0) return null
        
        var closest: Entry? = null
        var minDist = Float.MAX_VALUE
        
        for (i in 0 until dataSet.entryCount) {
            val entry = dataSet.getEntryForIndex(i)
            val dist = abs(entry.x - x)
            if (dist < minDist) {
                minDist = dist
                closest = entry
            }
        }
        
        // Only return if within reasonable distance (5 minutes - x-axis is in minutes)
        val tolerance = 5f
        return if (minDist <= tolerance) closest else null
    }
    
    data class PredictionValues(val q50: Float, val q10: Float, val q90: Float)

    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        Log.v(LOG_ID, "getOffsetForDrawingAtPoint - showContent: $showContent")
        val offset = offset
        try {
            val chart = chartView
            val width = width.toFloat()
            val height = height.toFloat()

            if (posY <= height + arrowSize) {
                offset.y = arrowSize.toFloat()
            } else {
                offset.y = -height - arrowSize
            }

            if (posX > chart.width - width) {
                offset.x = -width
            } else {
                offset.x = 0f
                if (posX > width / 2) {
                    offset.x = -(width / 2)
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "Exception in getOffsetForDrawingAtPoint", exc)
        }

        return offset
    }

    override fun draw(canvas: Canvas, posX: Float, posY: Float) {
        Log.v(LOG_ID, "draw - showContent: $showContent")
        if (!showContent) return
        
        try {
            val paint = Paint().apply {
                style = Paint.Style.FILL
                strokeJoin = Paint.Join.ROUND
                color = ContextCompat.getColor(context, R.color.transparent_marker_background)
            }

            val chart = chartView
            val width = width.toFloat()
            val height = height.toFloat()
            val offset = getOffsetForDrawingAtPoint(posX, posY)
            val saveId: Int = canvas.save()
            val path = Path()

            if (posY < height + arrowSize) {
                if (posX > chart.width - width) {
                    path.moveTo(width - (2 * arrowSize), 2f)
                    path.lineTo(width, -arrowSize + arrowCircleOffset)
                    path.lineTo(width - arrowSize, 2f)
                } else {
                    if (posX > width / 2) {
                        path.moveTo(width / 2 - arrowSize / 2, 2f)
                        path.lineTo(width / 2, -arrowSize + arrowCircleOffset)
                        path.lineTo(width / 2 + arrowSize / 2, 2f)
                    } else {
                        path.moveTo(0f, -arrowSize + arrowCircleOffset)
                        path.lineTo(0f + arrowSize, 2f)
                        path.lineTo(0f, 2f)
                        path.lineTo(0f, -arrowSize + arrowCircleOffset)
                    }
                }
                path.offset(posX + offset.x, posY + offset.y)
            } else {
                if (posX > chart.width - width) {
                    path.moveTo(width, (height - 2) + arrowSize - arrowCircleOffset)
                    path.lineTo(width - arrowSize, height - 2)
                    path.lineTo(width - (2 * arrowSize), height - 2)
                } else {
                    if (posX > width / 2) {
                        path.moveTo(width / 2 + arrowSize / 2, height - 2)
                        path.lineTo(width / 2, (height - 2) + arrowSize - arrowCircleOffset)
                        path.lineTo(width / 2 - arrowSize / 2, height - 2)
                        path.lineTo(0f, height - 2)
                    } else {
                        path.moveTo(0f + (arrowSize * 2), height - 2)
                        path.lineTo(0f, (height - 2) + arrowSize - arrowCircleOffset)
                        path.lineTo(0f, height - 2)
                        path.lineTo(0f + arrowSize, height - 2)
                    }
                }
                path.offset(posX + offset.x, posY + offset.y)
            }
            canvas.drawPath(path, paint)
            canvas.translate(posX + offset.x, posY + offset.y)
            draw(canvas)
            canvas.restoreToCount(saveId)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "Exception in draw", exc)
        }
    }
}
