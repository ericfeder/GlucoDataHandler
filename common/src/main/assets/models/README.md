# Glucose Prediction Models

This directory contains ONNX models for on-device glucose prediction.

## Required Files

The following ONNX model files should be placed here after running `export_onnx.py`:

- `model_q10_5min.onnx` - 10th percentile, 5-minute horizon
- `model_q50_5min.onnx` - 50th percentile (median), 5-minute horizon
- `model_q90_5min.onnx` - 90th percentile, 5-minute horizon
- `model_q10_10min.onnx` - 10th percentile, 10-minute horizon
- `model_q50_10min.onnx` - 50th percentile, 10-minute horizon
- `model_q90_10min.onnx` - 90th percentile, 10-minute horizon
- `model_q10_15min.onnx` - 10th percentile, 15-minute horizon
- `model_q50_15min.onnx` - 50th percentile, 15-minute horizon
- `model_q90_15min.onnx` - 90th percentile, 15-minute horizon
- `model_q10_20min.onnx` - 10th percentile, 20-minute horizon
- `model_q50_20min.onnx` - 50th percentile, 20-minute horizon
- `model_q90_20min.onnx` - 90th percentile, 20-minute horizon
- `model_q10_25min.onnx` - 10th percentile, 25-minute horizon
- `model_q50_25min.onnx` - 50th percentile, 25-minute horizon
- `model_q90_25min.onnx` - 90th percentile, 25-minute horizon
- `model_q10_30min.onnx` - 10th percentile, 30-minute horizon
- `model_q50_30min.onnx` - 50th percentile, 30-minute horizon
- `model_q90_30min.onnx` - 90th percentile, 30-minute horizon
- `model_metadata.json` - Model configuration and feature information

## Generating Models

Run the export script from the dexcom analysis directory:

```bash
cd /Users/eric.feder/dexcom/analysis
pip install onnxmltools skl2onnx python-dateutil
python export_onnx.py
```

Then copy the generated files:

```bash
cp /Users/eric.feder/dexcom/data/onnx_models/*.onnx .
cp /Users/eric.feder/dexcom/data/onnx_models/model_metadata.json .
```

## Model Input

Each model expects 15 float features (8 cumulative + 7 interval):

**Cumulative features (indices 0-7):**
- `past_delta_5min`: Current glucose - glucose 5 minutes ago
- `past_delta_10min`: Current glucose - glucose 10 minutes ago
- `past_delta_15min`: Current glucose - glucose 15 minutes ago
- `past_delta_20min`: Current glucose - glucose 20 minutes ago
- `past_delta_25min`: Current glucose - glucose 25 minutes ago
- `past_delta_30min`: Current glucose - glucose 30 minutes ago
- `past_delta_35min`: Current glucose - glucose 35 minutes ago
- `past_delta_40min`: Current glucose - glucose 40 minutes ago

**Interval features (indices 8-14):**
- `interval_10_to_5`: Glucose 5min ago - glucose 10min ago
- `interval_15_to_10`: Glucose 10min ago - glucose 15min ago
- `interval_20_to_15`: Glucose 15min ago - glucose 20min ago
- `interval_25_to_20`: Glucose 20min ago - glucose 25min ago
- `interval_30_to_25`: Glucose 25min ago - glucose 30min ago
- `interval_35_to_30`: Glucose 30min ago - glucose 35min ago
- `interval_40_to_35`: Glucose 35min ago - glucose 40min ago

## Model Output

Each model outputs a single float value representing the predicted delta (change in glucose) for the specified horizon and quantile. Add this to the current glucose value to get the absolute predicted glucose.

## Model Version

- Version: 2.0 (Original model with combined features)
- Training window: Rolling 6 months from latest data
- Model type: Trained on ALL data (not exclusion-aware)


