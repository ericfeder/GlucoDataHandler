package de.michelinside.glucodatahandler

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.database.dbAccess
import de.michelinside.glucodatahandler.common.prediction.GlucosePrediction
import de.michelinside.glucodatahandler.common.prediction.PredictionData
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.BitmapUtils
import de.michelinside.glucodatahandler.common.R as CR

/**
 * Activity to display glucose prediction visualizations.
 * Shows a chart with historical glucose and predicted future values,
 * plus a table with detailed prediction information.
 */
class PredictionActivity : AppCompatActivity(), NotifierInterface {
    
    companion object {
        private const val LOG_ID = "GDH.PredictionActivity"
    }
    
    private lateinit var chart: LineChart
    private lateinit var predictionsTable: TableLayout
    private lateinit var currentGlucoseText: TextView
    private lateinit var dexcomTrendImage: ImageView
    private lateinit var modelTrendImage: ImageView
    private lateinit var modelInfoText: TextView
    
    // Colors
    private val actualColor = Color.rgb(0, 122, 255)  // Blue for actual glucose
    private val q50Color = Color.rgb(255, 149, 0)      // Orange for Q50 prediction
    private val intervalColor = Color.argb(60, 255, 149, 0)  // Light orange for interval
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prediction)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Glucose Predictions"
        
        // Initialize views
        chart = findViewById(R.id.predictionChart)
        predictionsTable = findViewById(R.id.predictionsTable)
        currentGlucoseText = findViewById(R.id.currentGlucose)
        dexcomTrendImage = findViewById(R.id.dexcomTrend)
        modelTrendImage = findViewById(R.id.modelTrend)
        modelInfoText = findViewById(R.id.modelInfo)
        
        setupChart()
        updateUI()
        
        // Register for updates
        InternalNotifier.addNotifier(
            this,
            this,
            mutableSetOf(NotifySource.BROADCAST, NotifySource.MESSAGECLIENT, NotifySource.PREDICTION_UPDATE)
        )
        
        Log.i(LOG_ID, "PredictionActivity created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        InternalNotifier.remNotifier(this, this)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun OnNotifyData(context: android.content.Context, dataSource: NotifySource, extras: Bundle?) {
        Log.d(LOG_ID, "OnNotifyData: $dataSource")
        runOnUiThread {
            updateUI()
        }
    }
    
    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            
            // X-axis configuration
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 5f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val minutes = value.toInt()
                        return if (minutes == 0) "Now" else "${if (minutes > 0) "+" else ""}${minutes}m"
                    }
                }
            }
            
            // Y-axis configuration
            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 40f
                axisMaximum = 300f
                
                // Add target range lines
                val lowLine = LimitLine(70f, "Low").apply {
                    lineColor = Color.rgb(255, 59, 48)
                    lineWidth = 1f
                    enableDashedLine(10f, 5f, 0f)
                }
                val highLine = LimitLine(180f, "High").apply {
                    lineColor = Color.rgb(255, 204, 0)
                    lineWidth = 1f
                    enableDashedLine(10f, 5f, 0f)
                }
                addLimitLine(lowLine)
                addLimitLine(highLine)
            }
            
            axisRight.isEnabled = false
            legend.isEnabled = true
        }
    }
    
    private fun updateUI() {
        updateCurrentGlucose()
        updateChart()
        updateTable()
        updateModelInfo()
    }
    
    private fun updateCurrentGlucose() {
        // Current glucose value
        currentGlucoseText.text = ReceiveData.getGlucoseAsString()
        currentGlucoseText.setTextColor(ReceiveData.getGlucoseColor())
        
        // Dexcom trend arrow
        val dexcomBitmap = BitmapUtils.getRateAsBitmap(
            color = ReceiveData.getGlucoseColor(),
            width = 64,
            height = 64
        )
        if (dexcomBitmap != null) {
            dexcomTrendImage.setImageBitmap(dexcomBitmap)
        }
        
        // Model trend arrow
        if (PredictionData.hasPredictions()) {
            val modelBitmap = BitmapUtils.getRateAsBitmap(
                color = q50Color,
                width = 64,
                height = 64,
                rate = PredictionData.modelRate
            )
            if (modelBitmap != null) {
                modelTrendImage.setImageBitmap(modelBitmap)
                modelTrendImage.visibility = View.VISIBLE
            }
        } else {
            modelTrendImage.visibility = View.GONE
        }
    }
    
    private fun updateChart() {
        val dataSets = mutableListOf<LineDataSet>()
        val currentGlucose = ReceiveData.rawValue.toFloat()
        val currentTime = ReceiveData.time
        
        // Get historical data (last 60 minutes)
        val historyEntries = mutableListOf<Entry>()
        if (dbAccess.active && currentTime > 0) {
            val minTime = currentTime - (60 * 60 * 1000) // 60 minutes ago
            val historicalValues = dbAccess.getGlucoseValuesInRange(minTime, currentTime)
            
            for (value in historicalValues) {
                val minutesAgo = ((value.timestamp - currentTime) / (60 * 1000)).toFloat()
                historyEntries.add(Entry(minutesAgo, value.value.toFloat()))
            }
        }
        
        // Add current value
        historyEntries.add(Entry(0f, currentGlucose))
        historyEntries.sortBy { it.x }
        
        // Historical glucose line
        val historyDataSet = LineDataSet(historyEntries, "Actual Glucose").apply {
            color = actualColor
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 3f
            setCircleColor(actualColor)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        dataSets.add(historyDataSet)
        
        // Prediction data
        val predictions = PredictionData.getPredictions()
        if (predictions.isNotEmpty()) {
            val q50Entries = mutableListOf<Entry>()
            val q10Entries = mutableListOf<Entry>()
            val q90Entries = mutableListOf<Entry>()
            
            // Start from current value
            q50Entries.add(Entry(0f, currentGlucose))
            q10Entries.add(Entry(0f, currentGlucose))
            q90Entries.add(Entry(0f, currentGlucose))
            
            for (pred in predictions.sortedBy { it.horizon }) {
                q50Entries.add(Entry(pred.horizon.toFloat(), pred.q50))
                q10Entries.add(Entry(pred.horizon.toFloat(), pred.q10))
                q90Entries.add(Entry(pred.horizon.toFloat(), pred.q90))
            }
            
            // Q50 line (median prediction)
            val q50DataSet = LineDataSet(q50Entries, "Predicted (Q50)").apply {
                color = q50Color
                lineWidth = 2f
                setDrawCircles(true)
                circleRadius = 4f
                setCircleColor(q50Color)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
            }
            dataSets.add(q50DataSet)
            
            // Q10 line (lower bound)
            val q10DataSet = LineDataSet(q10Entries, "Lower (Q10)").apply {
                color = Color.argb(150, 255, 149, 0)
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                enableDashedLine(5f, 5f, 0f)
                mode = LineDataSet.Mode.LINEAR
            }
            dataSets.add(q10DataSet)
            
            // Q90 line (upper bound)
            val q90DataSet = LineDataSet(q90Entries, "Upper (Q90)").apply {
                color = Color.argb(150, 255, 149, 0)
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                enableDashedLine(5f, 5f, 0f)
                mode = LineDataSet.Mode.LINEAR
            }
            dataSets.add(q90DataSet)
        }
        
        // Update chart
        chart.data = LineData(dataSets.toList())
        
        // Adjust y-axis to data
        val allValues = dataSets.flatMap { it.values.map { e -> e.y } }
        if (allValues.isNotEmpty()) {
            val minY = (allValues.minOrNull() ?: 70f) - 20f
            val maxY = (allValues.maxOrNull() ?: 180f) + 20f
            chart.axisLeft.axisMinimum = maxOf(40f, minY)
            chart.axisLeft.axisMaximum = minOf(400f, maxY)
        }
        
        chart.invalidate()
    }
    
    private fun updateTable() {
        // Remove all rows except header
        while (predictionsTable.childCount > 1) {
            predictionsTable.removeViewAt(1)
        }
        
        val predictions = PredictionData.getPredictions()
        val currentGlucose = ReceiveData.rawValue
        val horizons = listOf(5, 10, 15, 20, 25, 30)
        
        // Q50 Row
        addTableRow("Q50 (Median)", horizons.map { horizon ->
            val pred = predictions.find { it.horizon == horizon }
            if (pred != null) {
                "${pred.q50.toInt()} (${formatDelta(pred.q50Delta)})"
            } else "--"
        }, q50Color)
        
        // Q10 Row
        addTableRow("Q10 (Lower)", horizons.map { horizon ->
            val pred = predictions.find { it.horizon == horizon }
            if (pred != null) {
                "${pred.q10.toInt()} (${formatDelta(pred.q10Delta)})"
            } else "--"
        }, Color.rgb(255, 149, 0))
        
        // Q90 Row
        addTableRow("Q90 (Upper)", horizons.map { horizon ->
            val pred = predictions.find { it.horizon == horizon }
            if (pred != null) {
                "${pred.q90.toInt()} (${formatDelta(pred.q90Delta)})"
            } else "--"
        }, Color.rgb(255, 149, 0))
        
        // Interval Width Row
        addTableRow("Interval Width", horizons.map { horizon ->
            val pred = predictions.find { it.horizon == horizon }
            if (pred != null) {
                "${pred.intervalWidth.toInt()}"
            } else "--"
        }, Color.GRAY)
    }
    
    private fun addTableRow(label: String, values: List<String>, textColor: Int) {
        val row = TableRow(this).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
        }
        
        // Label cell
        val labelView = TextView(this).apply {
            text = label
            setPadding(8, 8, 8, 8)
            setTextColor(textColor)
        }
        row.addView(labelView)
        
        // Value cells
        for (value in values) {
            val valueView = TextView(this).apply {
                text = value
                setPadding(8, 8, 8, 8)
                gravity = android.view.Gravity.CENTER
                setTextColor(textColor)
            }
            row.addView(valueView)
        }
        
        predictionsTable.addView(row)
    }
    
    private fun updateModelInfo() {
        val metadata = PredictionData.getMetadata()
        val infoText = if (metadata != null) {
            """
            Model Version: ${metadata.version}
            Training Period: ${metadata.training_start} to ${metadata.training_end}
            Training Samples: ${metadata.training_samples}
            Features: ${metadata.num_features} past delta values
            """.trimIndent()
        } else {
            "Model not loaded. Please ensure ONNX model files are in assets/models/"
        }
        modelInfoText.text = infoText
    }
    
    private fun formatDelta(delta: Float): String {
        return if (delta >= 0) "+${delta.toInt()}" else "${delta.toInt()}"
    }
}


