This folder should contain a TFLite model named `mobile_face_net.tflite` for computing face embeddings.

Place a lightweight face recognition tflite model here (128-d embedding). Recommended formats: FP16 or INT8 quantized mobile model.

If you don't add a model, the app will still run but embedding-based matching will be unavailable.

