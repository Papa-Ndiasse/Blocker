# Keep Android manifest components (receivers, services, activities, workers) intact under R8
-keep class com.blocker.app.BlockerApp { *; }
-keep class com.blocker.app.MainActivity { *; }
-keep class com.blocker.app.ui.BlockedActivity { *; }
-keep class com.blocker.app.admin.** { *; }
-keep class com.blocker.app.boot.** { *; }
-keep class com.blocker.app.guard.** { *; }
-keep class com.blocker.app.updater.** { *; }
-keep class com.blocker.app.vpn.** { *; }
-keep class com.blocker.app.worker.** { *; }

# Keep WorkManager CoroutineWorker constructors
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep ViewModel constructors
-keep class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}
