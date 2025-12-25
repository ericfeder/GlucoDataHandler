package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils

/**
 * Singleton that stores and manages prediction data.
 * 
 * Listens for new glucose data and automatically updates predictions.
 * Notifies listeners when predictions change.
 */
object PredictionData : NotifierInterface, SharedPreferences.OnSharedPreferenceChangeListener {
    private const val LOG_ID = "GDH.PredictionData"
    
    // Shared preferences keys
    const val PREF_PREDICTIONS_ENABLED = "predictions_enabled"
    const val PREF_SHOW_MODEL_ARROW = "show_model_arrow"
    
    // Current predictions
    private var predictions: List<GlucosePrediction> = emptyList()
    private var lastUpdateTime: Long = 0L
    
    // Model trend (based on 30-min Q50 prediction)
    var modelRate: Float = Float.NaN
        private set
    var modelTrendArrow: Char = '?'
        private set
    var modelTrendLabel: String = ""
        private set
    
    // Settings
    var predictionsEnabled: Boolean = true
        private set
    var showModelArrow: Boolean = true
        private set
    
    private var initialized = false
    
    /**
     * Initialize prediction data and start listening for glucose updates.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing PredictionData")
        
        try {
            // Load settings
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            predictionsEnabled = sharedPref.getBoolean(PREF_PREDICTIONS_ENABLED, true)
            showModelArrow = sharedPref.getBoolean(PREF_SHOW_MODEL_ARROW, true)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            
            // Initialize prediction service
            GlucosePredictionService.init(context)
            
            // Register for glucose updates
            InternalNotifier.addNotifier(
                context, 
                this, 
                mutableSetOf(NotifySource.BROADCAST, NotifySource.MESSAGECLIENT)
            )
            
            // Generate initial predictions if we have data
            if (ReceiveData.time > 0) {
                updatePredictions(context)
            }
            
            initialized = true
            Log.i(LOG_ID, "PredictionData initialized. Predictions enabled: $predictionsEnabled")
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to initialize: ${e.message}", e)
        }
    }
    
    /**
     * Update predictions based on current glucose data.
     */
    private fun updatePredictions(context: Context) {
        if (!predictionsEnabled) {
            Log.d(LOG_ID, "Predictions disabled, skipping update")
            return
        }
        
        if (!GlucosePredictionService.isAvailable()) {
            Log.w(LOG_ID, "Prediction service not available")
            return
        }
        
        try {
            // Get new predictions
            predictions = GlucosePredictionService.getPredictions(context)
            lastUpdateTime = ReceiveData.time
            
            // Update model trend
            modelRate = GlucosePredictionService.getModelTrendRate(context)
            modelTrendArrow = GlucosePredictionService.getModelTrendArrow(context)
            modelTrendLabel = GlucosePredictionService.getModelTrendLabel(context)
            
            Log.d(LOG_ID, "Predictions updated: ${predictions.size} horizons, " +
                    "modelRate=$modelRate, arrow=$modelTrendArrow")
            
            // Notify listeners
            InternalNotifier.notify(context, NotifySource.PREDICTION_UPDATE, null)
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error updating predictions: ${e.message}", e)
        }
    }
    
    /**
     * Get all current predictions.
     */
    fun getPredictions(): List<GlucosePrediction> = predictions
    
    /**
     * Get prediction for a specific horizon.
     */
    fun getPrediction(horizon: Int): GlucosePrediction? = 
        predictions.find { it.horizon == horizon }
    
    /**
     * Get the 30-minute prediction (most commonly used for trend arrows).
     */
    fun get30MinPrediction(): GlucosePrediction? = getPrediction(30)
    
    /**
     * Check if predictions are available.
     */
    fun hasPredictions(): Boolean = predictions.isNotEmpty()
    
    /**
     * Check if model trend differs significantly from Dexcom trend.
     * Returns true if the arrows are different.
     */
    fun trendsDiffer(): Boolean {
        if (modelRate.isNaN() || ReceiveData.rate.isNaN()) return false
        
        val dexcomArrow = GlucoDataUtils.getRateSymbol(ReceiveData.rate)
        return modelTrendArrow != dexcomArrow
    }
    
    /**
     * Get formatted prediction string for display.
     */
    fun getPredictionAsString(horizon: Int): String {
        val pred = getPrediction(horizon) ?: return "--"
        return "${pred.q50.toInt()} (${formatDelta(pred.q50Delta)})"
    }
    
    /**
     * Get interval string for display.
     */
    fun getIntervalAsString(horizon: Int): String {
        val pred = getPrediction(horizon) ?: return "--"
        return "${pred.q10.toInt()} - ${pred.q90.toInt()}"
    }
    
    private fun formatDelta(delta: Float): String {
        return if (delta >= 0) "+${delta.toInt()}" else "${delta.toInt()}"
    }
    
    /**
     * Get model metadata.
     */
    fun getMetadata(): ModelMetadata? = GlucosePredictionService.getMetadata()
    
    /**
     * Called when new glucose data arrives.
     */
    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        Log.d(LOG_ID, "OnNotifyData: $dataSource")
        
        if (dataSource == NotifySource.BROADCAST || dataSource == NotifySource.MESSAGECLIENT) {
            updatePredictions(context)
        }
    }
    
    /**
     * Handle preference changes.
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PREF_PREDICTIONS_ENABLED -> {
                predictionsEnabled = sharedPreferences?.getBoolean(key, true) ?: true
                Log.i(LOG_ID, "Predictions enabled changed: $predictionsEnabled")
                if (predictionsEnabled && GlucoDataService.context != null) {
                    updatePredictions(GlucoDataService.context!!)
                }
            }
            PREF_SHOW_MODEL_ARROW -> {
                showModelArrow = sharedPreferences?.getBoolean(key, true) ?: true
                Log.i(LOG_ID, "Show model arrow changed: $showModelArrow")
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun close(context: Context) {
        try {
            InternalNotifier.remNotifier(context, this)
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
            GlucosePredictionService.close()
            predictions = emptyList()
            initialized = false
            Log.i(LOG_ID, "PredictionData closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing: ${e.message}", e)
        }
    }
}


