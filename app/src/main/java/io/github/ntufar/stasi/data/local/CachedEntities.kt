package io.github.ntufar.stasi.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_lines")
data class CachedLineEntity(
    @PrimaryKey val lineCode: String,
    val lineId: String,
    val descr: String,
    val normDescr: String,
    val fetchedAtMillis: Long,
)

@Entity(tableName = "cached_routes")
data class CachedRouteEntity(
    @PrimaryKey val routeCode: String,
    val lineCode: String,
    val descr: String,
    val normDescr: String,
    val fetchedAtMillis: Long,
)

@Entity(tableName = "cached_stops")
data class CachedStopEntity(
    @PrimaryKey val stopCode: String,
    val descr: String,
    val normDescr: String,
    val lat: Double,
    val lng: Double,
    val fetchedAtMillis: Long,
)

@Entity(
    tableName = "arrival_cache",
    primaryKeys = ["stopCode", "routeCode", "vehCode"],
)
data class ArrivalCacheEntity(
    val stopCode: String,
    val routeCode: String,
    val vehCode: String,
    val destinationLabel: String,
    val lineLabel: String,
    val minutes: Int,
    val fetchedAtMillis: Long,
)

@Entity(tableName = "cache_meta")
data class CacheMetaEntity(
    @PrimaryKey val key: String,
    val lastSyncedMillis: Long,
)

@Entity(
    tableName = "route_stops",
    primaryKeys = ["routeCode", "stopCode"],
)
data class RouteStopCacheEntity(
    val routeCode: String,
    val stopCode: String,
    @ColumnInfo(name = "route_order") val routeOrder: Int,
    val lat: Double,
    val lng: Double,
    val descr: String,
    val fetchedAtMillis: Long,
)
