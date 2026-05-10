package io.github.ntufar.stasi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StasiDao {
    @Query("SELECT * FROM cache_meta WHERE `key` = :key LIMIT 1")
    suspend fun meta(key: String): CacheMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: CacheMetaEntity)

    @Query("SELECT * FROM cached_lines ORDER BY lineCode")
    suspend fun allLines(): List<CachedLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<CachedLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(routes: List<CachedRouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(stops: List<CachedStopEntity>)

    @Query("SELECT * FROM cached_stops")
    suspend fun allStops(): List<CachedStopEntity>

    @Query("SELECT * FROM cached_routes WHERE routeCode = :routeCode LIMIT 1")
    suspend fun routeByCode(routeCode: String): CachedRouteEntity?

    @Query("SELECT * FROM cached_stops WHERE stopCode = :stopCode LIMIT 1")
    suspend fun stopByCode(stopCode: String): CachedStopEntity?

    @Transaction
    suspend fun replaceArrivals(stopCode: String, rows: List<ArrivalCacheEntity>) {
        deleteArrivalsForStop(stopCode)
        if (rows.isNotEmpty()) insertArrivals(rows)
    }

    @Query("DELETE FROM arrival_cache WHERE stopCode = :stopCode")
    suspend fun deleteArrivalsForStop(stopCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArrivals(rows: List<ArrivalCacheEntity>)

    @Query(
        "SELECT * FROM arrival_cache WHERE stopCode = :stopCode AND fetchedAtMillis >= :minTime ORDER BY minutes ASC, routeCode ASC",
    )
    suspend fun arrivalsFresh(stopCode: String, minTime: Long): List<ArrivalCacheEntity>

    @Query("SELECT MAX(fetchedAtMillis) FROM arrival_cache WHERE stopCode = :stopCode")
    suspend fun latestArrivalFetch(stopCode: String): Long?

    @Query("SELECT * FROM cached_lines WHERE lineCode = :lineCode LIMIT 1")
    suspend fun lineByCode(lineCode: String): CachedLineEntity?

    @Query(
        "SELECT * FROM cached_lines WHERE lineId = :input OR lineCode = :input LIMIT 5",
    )
    suspend fun linesByExactCodeOrId(input: String): List<CachedLineEntity>

    @Query(
        "SELECT * FROM cached_routes WHERE lineCode = :lineCode ORDER BY routeCode ASC LIMIT 8",
    )
    suspend fun routesForLine(lineCode: String): List<CachedRouteEntity>

    @Query("SELECT * FROM route_stops WHERE routeCode = :routeCode ORDER BY route_order ASC")
    suspend fun routeStops(routeCode: String): List<RouteStopCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteStops(stops: List<RouteStopCacheEntity>)

    @Query("DELETE FROM route_stops WHERE routeCode = :routeCode")
    suspend fun deleteRouteStops(routeCode: String)

    @Query("SELECT * FROM cached_stops WHERE normDescr LIKE '%' || :needle || '%' LIMIT 120")
    suspend fun searchStopsByNorm(needle: String): List<CachedStopEntity>

    @Query("SELECT * FROM cached_lines WHERE normDescr LIKE '%' || :needle || '%' LIMIT 80")
    suspend fun searchLinesByNorm(needle: String): List<CachedLineEntity>
}
