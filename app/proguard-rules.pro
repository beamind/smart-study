# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Keep application classes
-keep class com.smartstudy.** { *; }

# Keep MediaPlayer and audio related classes
-keep class android.media.MediaPlayer { *; }
-keep class android.media.AudioRecord { *; }
-keep class android.media.AudioFormat { *; }

# Keep OkHttp classes (already have, but ensure)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep image cropper library (CanHub)
-keep class com.canhub.cropper.** { *; }
-keep interface com.canhub.cropper.** { *; }
-dontwarn com.canhub.cropper.**

# Keep DashScope SDK
-keep class com.alibaba.dashscope.** { *; }
-keep interface com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# Keep AMap (高德) SDK
# 3dmap/location 内部大量反射/动态加载；release 混淆时建议保守 keep，避免运行期崩溃
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.**
-dontwarn com.autonavi.**

# Keep ONNX Runtime (Sherpa-onnx KWS 依赖)
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# 保留 native 方法（ONNX Runtime 使用 JNI）
-keepclasseswithmembernames class ai.onnxruntime.** {
    native <methods>;
}

# Suppress Lombok warnings (compile-time annotation processor, not needed at runtime)
-dontwarn lombok.bytecode.PoolConstantsApp
-dontwarn lombok.bytecode.PostCompilerApp
-dontwarn lombok.bytecode.PreventNullAnalysisRemover
-dontwarn lombok.bytecode.SneakyThrowsRemover
-dontwarn lombok.core.Main$LicenseApp
-dontwarn lombok.core.Main$VersionApp
-dontwarn lombok.core.PublicApiCreatorApp
-dontwarn lombok.core.configuration.ConfigurationApp
-dontwarn lombok.core.handlers.SneakyThrowsAndCleanupDependencyInfo
-dontwarn lombok.core.runtimeDependencies.CreateLombokRuntimeApp
-dontwarn lombok.delombok.DelombokApp
-dontwarn lombok.eclipse.agent.MavenEcjBootstrapApp
-dontwarn lombok.eclipse.handlers.HandleAccessors
-dontwarn lombok.eclipse.handlers.HandleBuilder
-dontwarn lombok.eclipse.handlers.HandleBuilderDefault
-dontwarn lombok.eclipse.handlers.HandleCleanup
-dontwarn lombok.eclipse.handlers.HandleConstructor$HandleAllArgsConstructor
-dontwarn lombok.eclipse.handlers.HandleConstructor$HandleNoArgsConstructor
-dontwarn lombok.eclipse.handlers.HandleConstructor$HandleRequiredArgsConstructor
-dontwarn lombok.eclipse.handlers.HandleData
-dontwarn lombok.eclipse.handlers.HandleDelegate
-dontwarn lombok.eclipse.handlers.HandleEqualsAndHashCode
-dontwarn lombok.eclipse.handlers.HandleExtensionMethod
-dontwarn lombok.eclipse.handlers.HandleFieldDefaults
-dontwarn lombok.eclipse.handlers.HandleFieldNameConstants
-dontwarn lombok.eclipse.handlers.HandleGetter
-dontwarn lombok.eclipse.handlers.HandleHelper
-dontwarn lombok.eclipse.handlers.HandleJacksonized
-dontwarn lombok.eclipse.handlers.HandleLog$HandleCommonsLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleCustomLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleFloggerLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleJBossLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleJulLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleLog4j2Log
-dontwarn lombok.eclipse.handlers.HandleLog$HandleLog4jLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleSlf4jLog
-dontwarn lombok.eclipse.handlers.HandleLog$HandleXSlf4jLog
-dontwarn lombok.eclipse.handlers.HandleNonNull
-dontwarn lombok.eclipse.handlers.HandlePrintAST
-dontwarn lombok.eclipse.handlers.HandleSetter
-dontwarn lombok.eclipse.handlers.HandleSneakyThrows
-dontwarn lombok.eclipse.handlers.HandleStandardException
-dontwarn lombok.eclipse.handlers.HandleSuperBuilder
-dontwarn lombok.eclipse.handlers.HandleSynchronized
-dontwarn lombok.eclipse.handlers.HandleToString
-dontwarn lombok.eclipse.handlers.HandleUtilityClass
-dontwarn lombok.eclipse.handlers.HandleVal
-dontwarn lombok.eclipse.handlers.HandleValue
-dontwarn lombok.eclipse.handlers.HandleWith
-dontwarn lombok.eclipse.handlers.HandleWithBy
-dontwarn lombok.eclipse.handlers.singulars.EclipseGuavaMapSingularizer
-dontwarn lombok.eclipse.handlers.singulars.EclipseGuavaSetListSingularizer
-dontwarn lombok.eclipse.handlers.singulars.EclipseGuavaTableSingularizer
-dontwarn lombok.eclipse.handlers.singulars.EclipseJavaUtilListSingularizer
-dontwarn lombok.eclipse.handlers.singulars.EclipseJavaUtilMapSingularizer
-dontwarn lombok.eclipse.handlers.singulars.EclipseJavaUtilSetSingularizer
-dontwarn lombok.installer.Installer$CommandLineInstallerApp
-dontwarn lombok.installer.Installer$CommandLineUninstallerApp
-dontwarn lombok.installer.Installer$GraphicalInstallerApp
-dontwarn lombok.installer.eclipse.AngularIDELocationProvider
-dontwarn lombok.installer.eclipse.EclipseLocationProvider
-dontwarn lombok.installer.eclipse.JbdsLocationProvider
-dontwarn lombok.installer.eclipse.MyEclipseLocationProvider
-dontwarn lombok.installer.eclipse.RhcrLocationProvider
-dontwarn lombok.installer.eclipse.RhdsLocationProvider
-dontwarn lombok.installer.eclipse.STS4LocationProvider
-dontwarn lombok.installer.eclipse.STSLocationProvider
-dontwarn lombok.javac.handlers.HandleAccessors
-dontwarn lombok.javac.handlers.HandleBuilder
-dontwarn lombok.javac.handlers.HandleBuilderDefault
-dontwarn lombok.javac.handlers.HandleBuilderDefaultRemove
-dontwarn lombok.javac.handlers.HandleBuilderRemove
-dontwarn lombok.javac.handlers.HandleCleanup
-dontwarn lombok.javac.handlers.HandleConstructor$HandleAllArgsConstructor
-dontwarn lombok.javac.handlers.HandleConstructor$HandleNoArgsConstructor
-dontwarn lombok.javac.handlers.HandleConstructor$HandleRequiredArgsConstructor
-dontwarn lombok.javac.handlers.HandleData
-dontwarn lombok.javac.handlers.HandleDelegate
-dontwarn lombok.javac.handlers.HandleEqualsAndHashCode
-dontwarn lombok.javac.handlers.HandleExtensionMethod
-dontwarn lombok.javac.handlers.HandleFieldDefaults
-dontwarn lombok.javac.handlers.HandleFieldNameConstants
-dontwarn lombok.javac.handlers.HandleGetter
-dontwarn lombok.javac.handlers.HandleHelper
-dontwarn lombok.javac.handlers.HandleJacksonized
-dontwarn lombok.javac.handlers.HandleLog$HandleCommonsLog
-dontwarn lombok.javac.handlers.HandleLog$HandleCustomLog
-dontwarn lombok.javac.handlers.HandleLog$HandleFloggerLog
-dontwarn lombok.javac.handlers.HandleLog$HandleJBossLog
-dontwarn lombok.javac.handlers.HandleLog$HandleJulLog
-dontwarn lombok.javac.handlers.HandleLog$HandleLog4j2Log
-dontwarn lombok.javac.handlers.HandleLog$HandleLog4jLog
-dontwarn lombok.javac.handlers.HandleLog$HandleSlf4jLog
-dontwarn lombok.javac.handlers.HandleLog$HandleXSlf4jLog
-dontwarn lombok.javac.handlers.HandleNonNull
-dontwarn lombok.javac.handlers.HandlePrintAST
-dontwarn lombok.javac.handlers.HandleSetter
-dontwarn lombok.javac.handlers.HandleSneakyThrows
-dontwarn lombok.javac.handlers.HandleStandardException
-dontwarn lombok.javac.handlers.HandleSuperBuilder
-dontwarn lombok.javac.handlers.HandleSuperBuilderRemove
-dontwarn lombok.javac.handlers.HandleSynchronized
-dontwarn lombok.javac.handlers.HandleToString
-dontwarn lombok.javac.handlers.HandleUtilityClass
-dontwarn lombok.javac.handlers.HandleVal
-dontwarn lombok.javac.handlers.HandleValue
-dontwarn lombok.javac.handlers.HandleWith
-dontwarn lombok.javac.handlers.HandleWithBy
-dontwarn lombok.javac.handlers.singulars.JavacGuavaMapSingularizer
-dontwarn lombok.javac.handlers.singulars.JavacGuavaSetListSingularizer
-dontwarn lombok.javac.handlers.singulars.JavacGuavaTableSingularizer
-dontwarn lombok.javac.handlers.singulars.JavacJavaUtilListSingularizer
-dontwarn lombok.javac.handlers.singulars.JavacJavaUtilMapSingularizer
-dontwarn lombok.javac.handlers.singulars.JavacJavaUtilSetSingularizer
