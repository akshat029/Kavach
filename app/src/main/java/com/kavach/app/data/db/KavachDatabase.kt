package com.kavach.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppPolicyEntity::class,
        DomainRuleEntity::class,
        ConnectionLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KavachDatabase : RoomDatabase() {

    abstract fun dao(): KavachDao

    companion object {
        private const val NAME = "kavach.db"

        @Volatile
        private var instance: KavachDatabase? = null

        fun get(context: Context): KavachDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): KavachDatabase =
            Room.databaseBuilder(context, KavachDatabase::class.java, NAME)
                // v1 has no migrations yet. Every future schema change MUST ship a real
                // Migration - destructive fallback would silently wipe the user's policy,
                // which is the one thing this app must never do.
                .build()
    }
}
