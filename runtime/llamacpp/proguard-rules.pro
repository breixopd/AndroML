# JNI method names are part of the native ABI and must survive release shrinking.
-keep class dev.androml.runtime.llamacpp.LlamaCppNative { *; }
