package de.michelinside.glucodatahandler.common.prediction

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils

/**
 * Represents a glucose prediction for a specific time horizon.
 */
data class GlucosePrediction(
    val horizon: Int,       // Minutes ahead (5, 10, 15, 20, 25, 30)
    val q10: Float,         // 10th percentile (lower bound) - absolute glucose value
    val q50: Float,         // 50th percentile (median) - absolute glucose value
    val q90: Float,         // 90th percentile (upper bound) - absolute glucose value
    val q10Delta: Float,    // Delta for Q10
    val q50Delta: Float,    // Delta for Q50
    val q90Delta: Float     // Delta for Q90
) {
    val intervalWidth: Float get() = q90 - q10
}

/**
 * Model metadata loaded from tcn_metadata.json
 */
data class ModelMetadata(
    val version: String,
    val created_at: String,
    val model_type: String = "TCN"
)

/**
 * Service for on-device glucose predictions using TCN models.
 * 
 * Supports two model types:
 * - Baseline: 6 separate single-horizon TCN models
 * - E2: Single multi-output TCN model with horizon-specific bins
 * 
 * Model selection is controlled via SHARED_PREF_PREDICTION_MODEL preference.
 */
object GlucosePredictionService : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val LOG_ID = "GDH.PredictionService"
    
    private val PREDICTION_HORIZONS = listOf(5, 10, 15, 20, 25, 30)
    
    private var metadata: ModelMetadata? = null
    private var initialized = false
    
    // Current model selection
    private var currentModel: String = Constants.PREDICTION_MODEL_BASELINE
    
    // Cache for predictions
    private var cachedPredictions: List<GlucosePrediction> = emptyList()
    private var cachedTime: Long = 0L
    
    /**
     * Initialize the prediction service.
     */
    fun init(context: Context) {
        if (initialized) return
        
        Log.i(LOG_ID, "Initializing GlucosePredictionService")
        
        try {
            // Load model selection from preferences
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            currentModel = sharedPref.getString(Constants.SHARED_PREF_PREDICTION_MODEL, Constants.PREDICTION_MODEL_BASELINE) 
                ?: Constants.PREDICTION_MODEL_BASELINE
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            
            Log.i(LOG_ID, "Selected model: $currentModel")
            
            // Initialize both services (they'll load on demand)
            TcnPredictionService.init(context)
            TcnMultiheadPredictionService.init(context)
            
            // Update metadata based on selected model
            updateMetadata()
            
            initialized = true
            Log.i(LOG_ID, "Initialization complete. Baseline available: ${TcnPredictionService.isAvailable()}, E2 available: ${TcnMultiheadPredictionService.isAvailable()}")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Failed to initialize: ${e.message}", e)
            initialized = true
        }
    }
    
    /**
     * Update metadata based on selected model.
     */
    private fun updateMetadata() {
        metadata = when (currentModel) {
            Constants.PREDICTION_MODEL_E2 -> ModelMetadata(
                version = "2.0-E2",
                created_at = "2026-01-01",
                model_type = "TCN Multi-horizon"
            )
            else -> ModelMetadata(
                version = "2.0-TCN",
                created_at = "2025-01-01",
                model_type = "TCN Distribution"
            )
        }
    }
    
    /**
     * Check if prediction service is available (models loaded successfully).
     */
    fun isAvailable(): Boolean {
        return when (currentModel) {
            Constants.PREDICTION_MODEL_E2 -> initialized && TcnMultiheadPredictionService.isAvailable()
            else -> initialized && TcnPredictionService.isAvailable()
        }
    }
    
    /**
     * Get predictions for all horizons based on current glucose data.
     * Routes to the appropriate service based on model selection.
     */
    fun getPredictions(context: Context): List<GlucosePrediction> {
        init(context)
        
        if (!isAvailable()) {
            Log.w(LOG_ID, "Selected model ($currentModel) not available, returning empty predictions")
            return emptyList()
        }
        
        // Check cache
        val currentTime = ReceiveData.time
        if (currentTime == cachedTime && cachedPredictions.isNotEmpty()) {
            return cachedPredictions
        }
        
        try {
            // Get quantile predictions from the selected model
            val tcnPredictions = when (currentModel) {
                Constants.PREDICTION_MODEL_E2 -> TcnMultiheadPredictionService.getAllQuantilePredictions(context)
                else -> TcnPredictionService.getAllQuantilePredictions(context)
            }
            
            // Convert to GlucosePrediction format
            val predictions = tcnPredictions.map { tcn ->
                GlucosePrediction(
                    horizon = tcn.horizon,
                    q10 = tcn.q10,
                    q50 = tcn.q50,
                    q90 = tcn.q90,
                    q10Delta = tcn.q10Delta,
                    q50Delta = tcn.q50Delta,
                    q90Delta = tcn.q90Delta
                )
            }
            
            // Update cache
            cachedPredictions = predictions
            cachedTime = currentTime
            
            Log.d(LOG_ID, "Generated ${predictions.size} predictions from $currentModel model")
            return predictions
            
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error generating predictions: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Get trend probabilities for a specific horizon using the currently selected model.
     * Routes to either baseline TcnPredictionService or E2 TcnMultiheadPredictionService.
     */
    fun getTrendProbabilities(context: Context, horizon: Int): TrendProbabilities? {
        init(context)
        
        if (!isAvailable()) {
            Log.w(LOG_ID, "Selected model ($currentModel) not available for trend probabilities")
            return null
        }
        
        return when (currentModel) {
            Constants.PREDICTION_MODEL_E2 -> TcnMultiheadPredictionService.getTrendProbabilities(context, horizon)
            else -> TcnPredictionService.getTrendProbabilities(context, horizon)
        }
    }
    
    /**
     * Get zone probabilities for a specific horizon using the currently selected model.
     * Routes to either baseline TcnPredictionService or E2 TcnMultiheadPredictionService.
     */
    fun getZoneProbabilities(context: Context, horizon: Int, currentGlucose: Int): ZoneProbabilities? {
        init(context)
        
        if (!isAvailable()) {
            Log.w(LOG_ID, "Selected model ($currentModel) not available for zone probabilities")
            return null
        }
        
        return when (currentModel) {
            Constants.PREDICTION_MODEL_E2 -> TcnMultiheadPredictionService.getZoneProbabilities(context, horizon, currentGlucose)
            else -> TcnPredictionService.getZoneProbabilities(context, horizon, currentGlucose)
        }
    }
    
    /**
     * Get the predicted trend rate based on Q50 15-minute prediction.
     * Returns rate in mg/dL per minute (same scale as Dexcom rate).
     * 
     * Dexcom thresholds (per minute):
     * - Steady (→): < 1 mg/dL/min (< 15 mg/dL per 15 min)
     * - Slowly (↗↘): 1-2 mg/dL/min (15-30 mg/dL per 15 min)
     * - Rising/Falling (↑↓): 2-3 mg/dL/min (30-45 mg/dL per 15 min)
     * - Rapidly (⇈⇊): > 3 mg/dL/min (> 45 mg/dL per 15 min)
     */
    fun getModelTrendRate(context: Context): Float {
        val predictions = getPredictions(context)
        val pred15 = predictions.find { it.horizon == 15 } ?: return Float.NaN
        
        // Convert 15-min delta to rate per minute (matching Dexcom's rate scale)
        val rate = pred15.q50Delta / 15f
        
        Log.d(LOG_ID, "getModelTrendRate: currentGlucose=${ReceiveData.rawValue}, " +
                "q50=${pred15.q50}, q50Delta=${pred15.q50Delta}, rate=$rate (per min)")
        
        return rate
    }
    
    /**
     * Get the predicted trend arrow symbol based on model predictions.
     */
    fun getModelTrendArrow(context: Context): Char {
        val rate = getModelTrendRate(context)
        return GlucoDataUtils.getRateSymbol(rate)
    }
    
    /**
     * Get the predicted trend rate as a Dexcom-style label.
     */
    fun getModelTrendLabel(context: Context): String {
        val rate = getModelTrendRate(context)
        return GlucoDataUtils.getDexcomLabel(rate)
    }
    
    /**
     * Get prediction for a specific horizon (5, 10, 15, 20, 25, or 30 minutes).
     */
    fun getPrediction(context: Context, horizon: Int): GlucosePrediction? {
        return getPredictions(context).find { it.horizon == horizon }
    }
    
    /**
     * Get model metadata (training info, version, etc.)
     */
    fun getMetadata(): ModelMetadata? = metadata
    
    /**
     * Get the currently selected model name.
     */
    fun getCurrentModel(): String = currentModel
    
    /**
     * Clear cached predictions (call when new glucose data arrives).
     */
    fun clearCache() {
        cachedPredictions = emptyList()
        cachedTime = 0L
        TcnPredictionService.clearCache()
        TcnMultiheadPredictionService.clearCache()
    }
    
    /**
     * Refresh model selection from preferences.
     * Call this to ensure the latest model preference is used.
     */
    fun refreshModelSelection(context: Context) {
        val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        val newModel = sharedPref.getString(Constants.SHARED_PREF_PREDICTION_MODEL, Constants.PREDICTION_MODEL_BASELINE)
            ?: Constants.PREDICTION_MODEL_BASELINE
        if (newModel != currentModel) {
            Log.i(LOG_ID, "Refreshing model selection: $currentModel -> $newModel")
            currentModel = newModel
            updateMetadata()
            clearCache()
        }
    }
    
    /**
     * Handle preference changes.
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Constants.SHARED_PREF_PREDICTION_MODEL -> {
                val newModel = sharedPreferences?.getString(key, Constants.PREDICTION_MODEL_BASELINE) 
                    ?: Constants.PREDICTION_MODEL_BASELINE
                if (newModel != currentModel) {
                    Log.i(LOG_ID, "Model changed: $currentModel -> $newModel")
                    currentModel = newModel
                    updateMetadata()
                    clearCache()
                }
            }
        }
    }
    
    /**
     * Close and release resources.
     */
    fun close() {
        try {
            TcnPredictionService.close()
            TcnMultiheadPredictionService.close()
            initialized = false
            clearCache()
            Log.i(LOG_ID, "GlucosePredictionService closed")
        } catch (e: Exception) {
            Log.e(LOG_ID, "Error closing service: ${e.message}", e)
        }
    }
}
