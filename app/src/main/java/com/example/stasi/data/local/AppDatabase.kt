package com.example.stasi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedLineEntity::class,
        CachedRouteEntity::class,
        CachedStopEntity::class,
        ArrivalCacheEntity::class,
        CacheMetaEntity::class,
        RouteStopCacheEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stasiDao(): StasiDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "stasi.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
