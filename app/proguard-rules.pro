-keep class com.takattowo.bootloaderspoofer.ModuleMain { *; }
-keep class com.takattowo.bootloaderspoofer.MainActivity { *; }
-keep class com.takattowo.bootloaderspoofer.AboutActivity { *; }
-keep class com.takattowo.bootloaderspoofer.AdvancedActivity { *; }
-keep class com.takattowo.bootloaderspoofer.EditKeyboxActivity { *; }
-keep class com.takattowo.bootloaderspoofer.App { *; }
-keep class com.takattowo.bootloaderspoofer.MiniProotActivity { *; }
-keep class com.takattowo.bootloaderspoofer.MiniProotManager { *; }
-keep class com.takattowo.bootloaderspoofer.MiniProotManager$AppInfo { *; }
-keep class com.takattowo.bootloaderspoofer.KeyboxData { *; }
-keep class com.takattowo.bootloaderspoofer.KeyboxData$* { *; }
-keepattributes RuntimeVisibleAnnotations
-keep,allowobfuscation class * extends io.github.libxposed.api.XposedModule

-keep class io.github.libxposed.service.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- Shizuku API: must NOT be obfuscated or stripped ---
# The Shizuku server sends a binder to ShizukuProvider by class name.
# If R8 renames these classes, the provider won't be found and the
# binder will never be delivered.
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }
-keep class moe.shizuku.api.** { *; }
-keep class moe.shizuku.server.** { *; }
-keep interface moe.shizuku.server.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn rikka.sui.**
-dontwarn moe.shizuku.**

# Keep all AIDL-generated stubs
-keep class * implements android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }

# Keep Parcelable creators (BinderContainer)
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keep class moe.shizuku.api.BinderContainer { *; }
