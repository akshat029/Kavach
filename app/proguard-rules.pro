# Kavach ProGuard / R8 rules

# Keep the DeviceAdminReceiver - referenced from XML metadata
-keep class com.kavach.app.isolation.KavachDeviceAdminReceiver { *; }

# Keep VpnService entry point
-keep class com.kavach.app.vpn.KavachVpnService { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Keep data model classes used by Room/serialization
-keep class com.kavach.app.core.model.** { *; }
-keep class com.kavach.app.data.db.** { *; }
