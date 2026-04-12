# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# sherpa-onnx: JNI side may look up classes/methods by original names.
# Keep them to avoid NoClassDefFoundError in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepnames class com.k2fsa.sherpa.onnx.**
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# Backup/restore uses Gson + TypeToken to deserialize persisted JSON into
# Room entity models. Release builds enable R8, so keep the generic metadata
# that reflection-based parsers rely on to make debug/release behavior match.
#
# Signature is required for Gson TypeToken and Retrofit generic parsing.
# InnerClasses/EnclosingMethod are required so anonymous TypeToken subclasses
# and suspend API signatures can still be reconstructed correctly at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes *Annotation*

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit suspend APIs parse generic Continuation signatures via reflection.
# If these signatures are stripped in release, Retrofit can crash with:
# "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType".
-keep class kotlin.coroutines.Continuation
-keep,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-keep class tao.test.flipaccounting.data.local.entity.** { *; }

# Built-in category/asset presets are deserialized from raw JSON with Gson in
# release builds. Keep field names stable so raw data loads exactly like debug.
-keep class tao.test.flipaccounting.BuiltInCategory { *; }

# Retrofit/Gson DTOs used by AI config, chat, and model testing.
-keep class tao.test.flipaccounting.SiliconFlowApi { *; }
-keep class tao.test.flipaccounting.ChatRequest { *; }
-keep class tao.test.flipaccounting.Message { *; }
-keep class tao.test.flipaccounting.MultimodalMessage { *; }
-keep class tao.test.flipaccounting.ContentPart { *; }
-keep class tao.test.flipaccounting.ImageUrl { *; }
-keep class tao.test.flipaccounting.MessageUnion { *; }
-keep class tao.test.flipaccounting.MessageUnion$* { *; }
-keep class tao.test.flipaccounting.MessageUnionSerializer { *; }
-keep class tao.test.flipaccounting.ResponseFormat { *; }
-keep class tao.test.flipaccounting.ChatResponse { *; }
-keep class tao.test.flipaccounting.Choice { *; }
-keep class tao.test.flipaccounting.AudioResponse { *; }
-keep class tao.test.flipaccounting.ModelsResponse { *; }
-keep class tao.test.flipaccounting.ModelItem { *; }

# Shizuku screenshot path uses reflection to call Shizuku.newProcess(...).
# R8 may strip/rename reflectively-accessed members in release, causing
# debug works but release returns empty screencap bytes or fails silently.
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keep class tao.test.flipaccounting.ShizukuHelper { *; }
-keep class tao.test.flipaccounting.ShizukuShell { *; }
