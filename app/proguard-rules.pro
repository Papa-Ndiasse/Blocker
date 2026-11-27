# Keep only Android Manifest entry points so R8 can fully inline & strip dead code (including removed VPN classes)
-keep class com.blocker.app.BlockerApp { *; }
-keep class com.blocker.app.MainActivity { *; }
-keep class com.blocker.app.ui.BlockedActivity { *; }
-keep class com.blocker.app.ui.LockoutActivity { *; }
-keep class com.blocker.app.guard.BlockerAccessibilityService { *; }
-keep class com.blocker.app.admin.BlockerDeviceAdminReceiver { *; }
-keep class com.blocker.app.admin.PackageInstallReceiver { *; }
-keep class com.blocker.app.admin.UninstallResultReceiver { *; }
-keep class com.blocker.app.boot.BootCompletedReceiver { *; }
-keep class com.blocker.app.updater.InstallResultReceiver { *; }

# Keep WorkManager CoroutineWorker constructors
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep ViewModel constructors
-keep class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# Strip verbose/debug/info logs in release builds for lower CPU & smaller DEX on Galaxy A55
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
