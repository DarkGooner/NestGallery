# LibVLC uses JNI/reflection for its Android bindings.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# ONNX Runtime uses JNI.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
