# Keep DeviceAdminReceiver and Boot receivers intact
-keep class com.blocker.app.admin.** { *; }
-keep class com.blocker.app.boot.** { *; }
-keep class com.blocker.app.vpn.** { *; }
-keep class com.blocker.app.worker.** { *; }
