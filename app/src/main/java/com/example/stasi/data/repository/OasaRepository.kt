package com.example.stasi.data.repository

import com.example.stasi.data.api.OasaApi
import com.example.stasi.data.api.OasaArrivalJson
import com.example.stasi.data.api.OasaClosestStopJson
import com.example.stasi.data.api.OasaStopXYJson
import com.example.stasi.data.api.OasaWebStopJson
import com.example.stasi.data.api.createOasaApi
import com.example.stasi.data.local.ArrivalCacheEntity
import com.example.stasi.data.local.CacheMetaEntity
import com.example.stasi.data.local.CachedLineEntity
import com.example.stasi.data.local.CachedRouteEntity
import com.example.stasi.data.local.CachedStopEntity
import com.example.stasi.data.local.RouteStopCacheEntity
import com.example.stasi.data.local.StasiDao
import com.example.stasi.data.util.EndpointRateLimiter
import com.example.stasi.data.util.lineSearchNorm
import com.example.stasi.data.util.normalizeGreek
import com.example.stasi.data.util.stopSearchNorm
import com.example.stasi.data.util.parseArrivalMinutes
import kotlinx.coroutines.CancellationException

/** Broad catch blocks must not swallow this or [withTimeout] / cancel never completes correctly. */
private fun rethrowIfCancellation(e: Exception) {
    if (e is CancellationException) throw e
}

/** Bumped so existing installs re-sync lines/stops with richer search norms (line number, codes). */
private const val META_LINES = "lines_v2"
private const val TWENTY_FOUR_H_MS = 24L * 60 * 60 * 1000
private const val ARRIVAL_CACHE_MS = 30_000L

data class RouteStop(
    val stopCode: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val order: Int,
)

data class RouteStopsFetch(
    val stops: List<RouteStop>,
    /** OASA route code for webGetStops / getBusLocation; may differ from what the user typed. */
    val effectiveRouteCode: String,
)

data class BusOnRoute(
    val vehicleNo: String,
    val lat: Double,
    val lng: Double,
)

data class ArrivalDetail(
    val routeCode: String,
    val vehCode: String,
    val minutes: Int,
    val destinationLabel: String,
    val lineLabel: String,
)

data class NearbyStop(
    val stopCode: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val distanceKm: Double?,
)

class OasaRepository(
    private val dao: StasiDao,
    private val api: OasaApi = createOasaApi(),
    private val limiter: EndpointRateLimiter = EndpointRateLimiter(),
) {
    suspend fun syncCatalogIncremental(
        maxLines: Int = 80,
        maxRoutesPerLine: Int = 4,
    ) {
        try {
            val now = System.currentTimeMillis()
            val meta = dao.meta(META_LINES)
            if (meta != null && now - meta.lastSyncedMillis < TWENTY_FOUR_H_MS && dao.allLines().isNotEmpty()) {
                return
            }

            val linesJson = limiter.run(EndpointRateLimiter.EP_WEB_GET_LINES) { api.webGetLines() }
            val t = now
            val lineEntities = linesJson.mapNotNull { j ->
                val code = j.lineCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
                val descr = j.lineDescr?.trim().orEmpty()
                val lineId = j.lineId?.trim().orEmpty()
                CachedLineEntity(
                    lineCode = code,
                    lineId = lineId,
                    descr = descr,
                    normDescr = lineSearchNorm(lineId, code, descr),
                    fetchedAtMillis = t,
                )
            }
            if (lineEntities.isNotEmpty()) {
                dao.insertLines(lineEntities)
            }
            dao.upsertMeta(CacheMetaEntity(META_LINES, t))

            for (line in lineEntities.take(maxLines)) {
                val routesJson = try {
                    limiter.run(EndpointRateLimiter.gateWebGetRoutes(line.lineCode)) {
                        api.webGetRoutes(lineCode = line.lineCode)
                    }
                } catch (_: Exception) {
                    continue
                }
                val routeEntities = routesJson.mapNotNull { r ->
                    val rc = r.routeCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
                    val descr = r.routeDescr?.trim().orEmpty()
                    CachedRouteEntity(
                        routeCode = rc,
                        lineCode = r.lineCode?.trim() ?: line.lineCode,
                        descr = descr,
                        normDescr = normalizeGreek(descr),
                        fetchedAtMillis = t,
                    )
                }
                if (routeEntities.isNotEmpty()) {
                    dao.insertRoutes(routeEntities)
                }
                for (route in routeEntities.take(maxRoutesPerLine)) {
                    ingestRouteStops(route.routeCode, t)
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            // Offline / throttling: keep existing cache.
        }
    }

    /** Fast path for Search screen: lines list only, no route/stop ingest (avoids starving interactive API use). */
    suspend fun warmLinesCacheIfEmpty() {
        ensureLinesCatalogForResolve()
    }

    /** One lightweight [webGetLines] when DB has no lines so map/search can resolve public line numbers. */
    private suspend fun ensureLinesCatalogForResolve() {
        val lines = try {
            dao.allLines()
        } catch (_: Exception) {
            emptyList()
        }
        if (lines.isNotEmpty()) return
        try {
            val linesJson = limiter.run(EndpointRateLimiter.EP_WEB_GET_LINES) { api.webGetLines() }
            val t = System.currentTimeMillis()
            val lineEntities = linesJson.mapNotNull { j ->
                val code = j.lineCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
                val descr = j.lineDescr?.trim().orEmpty()
                val lineId = j.lineId?.trim().orEmpty()
                CachedLineEntity(
                    lineCode = code,
                    lineId = lineId,
                    descr = descr,
                    normDescr = lineSearchNorm(lineId, code, descr),
                    fetchedAtMillis = t,
                )
            }
            if (lineEntities.isNotEmpty()) {
                dao.insertLines(lineEntities)
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            // Offline
        }
    }

    private suspend fun ingestRouteStops(routeCode: String, t: Long) {
        val raw = try {
            limiter.run(EndpointRateLimiter.gateWebGetStops(routeCode)) {
                api.webGetStops(routeCode = routeCode)
            }
        } catch (_: Exception) {
            return
        }
        val globalStops = mutableListOf<CachedStopEntity>()
        val routeStops = mutableListOf<RouteStopCacheEntity>()
        for (dto in raw) {
            val code = dto.stopCode?.trim().orEmpty()
            if (code.isBlank()) continue
            val lat = dto.stopLat?.toDoubleOrNull() ?: continue
            val lng = dto.stopLng?.toDoubleOrNull() ?: continue
            val descr = dto.stopDescr?.trim().orEmpty()
            val order = dto.routeStopOrder?.toIntOrNull() ?: 0
            globalStops.add(
                CachedStopEntity(
                    stopCode = code,
                    descr = descr,
                    normDescr = stopSearchNorm(code, descr),
                    lat = lat,
                    lng = lng,
                    fetchedAtMillis = t,
                ),
            )
            routeStops.add(
                RouteStopCacheEntity(
                    routeCode = routeCode,
                    stopCode = code,
                    routeOrder = order,
                    lat = lat,
                    lng = lng,
                    descr = descr,
                    fetchedAtMillis = t,
                ),
            )
        }
        if (globalStops.isNotEmpty()) {
            dao.insertStops(globalStops)
        }
        if (routeStops.isNotEmpty()) {
            dao.deleteRouteStops(routeCode)
            dao.insertRouteStops(routeStops)
        }
    }

    suspend fun searchStops(query: String): List<CachedStopEntity> {
        val q = normalizeGreek(query).trim()
        if (q.length < 2) return emptyList()
        val needle = q.replace("%", "").replace("_", "")
        if (needle.isEmpty()) return emptyList()
        return try {
            dao.searchStopsByNorm(needle)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchLines(query: String): List<CachedLineEntity> {
        val q = normalizeGreek(query).trim()
        if (q.length < 2) return emptyList()
        val needle = q.replace("%", "").replace("_", "")
        if (needle.isEmpty()) return emptyList()
        return try {
            dao.searchLinesByNorm(needle)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun allCachedLines(): List<CachedLineEntity> = try {
        dao.allLines()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getStopLabel(stopCode: String): String {
        val cached = dao.stopByCode(stopCode)
        if (cached != null) return cached.descr
        return try {
            val row = limiter.run(EndpointRateLimiter.EP_GET_STOP_XY) {
                api.getStopNameAndXY(stopCode = stopCode)
            }.firstOrNull()
            row?.descrValue().orEmpty().ifBlank { stopCode }
        } catch (_: Exception) {
            stopCode
        }
    }

    suspend fun getStopArrivals(stopCode: String): List<ArrivalDetail> {
        val now = System.currentTimeMillis()
        val minFresh = now - ARRIVAL_CACHE_MS
        val cached = try {
            dao.arrivalsFresh(stopCode, minFresh)
        } catch (_: Exception) {
            emptyList()
        }
        if (cached.isNotEmpty()) {
            return cached.map { it.toDetail() }
        }
        return fetchAndCacheArrivals(stopCode, now)
    }

    private suspend fun fetchAndCacheArrivals(stopCode: String, now: Long): List<ArrivalDetail> {
        return try {
            val json = limiter.run(EndpointRateLimiter.EP_GET_ARRIVALS) {
                api.getStopArrivals(stopCode = stopCode)
            }
            val rows = json.mapNotNull { mapArrivalJson(it, stopCode, now) }
            dao.replaceArrivals(stopCode, rows)
            rows.map { it.toDetail() }
        } catch (_: Exception) {
            val stale = try {
                dao.arrivalsFresh(stopCode, 0L)
            } catch (_: Exception) {
                emptyList()
            }
            stale.map { it.toDetail() }
        }
    }

    suspend fun getRouteStops(routeCodeOrLineNumber: String): RouteStopsFetch {
        val trimmed = routeCodeOrLineNumber.trim()
        if (trimmed.isEmpty()) return RouteStopsFetch(emptyList(), trimmed)
        ensureLinesCatalogForResolve()

        fun mapWebStops(raw: List<OasaWebStopJson>): List<RouteStop> =
            raw.mapNotNull { dto ->
                val code = dto.stopCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
                val lat = dto.stopLat?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = dto.stopLng?.toDoubleOrNull() ?: return@mapNotNull null
                val order = dto.routeStopOrder?.toIntOrNull() ?: 0
                RouteStop(
                    stopCode = code,
                    description = dto.stopDescr?.trim().orEmpty(),
                    lat = lat,
                    lng = lng,
                    order = order,
                )
            }.sortedBy { it.order }

        suspend fun loadFromCache(routeCode: String): List<RouteStop> =
            try {
                dao.routeStops(routeCode).map { e ->
                    RouteStop(
                        stopCode = e.stopCode,
                        description = e.descr,
                        lat = e.lat,
                        lng = e.lng,
                        order = e.routeOrder,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }

        suspend fun persistRouteStops(routeCode: String, mapped: List<RouteStop>) {
            if (mapped.isEmpty()) return
            val t = System.currentTimeMillis()
            dao.insertStops(
                mapped.map { s ->
                    CachedStopEntity(
                        stopCode = s.stopCode,
                        descr = s.description,
                        normDescr = stopSearchNorm(s.stopCode, s.description),
                        lat = s.lat,
                        lng = s.lng,
                        fetchedAtMillis = t,
                    )
                },
            )
            dao.deleteRouteStops(routeCode)
            dao.insertRouteStops(
                mapped.map { s ->
                    RouteStopCacheEntity(
                        routeCode = routeCode,
                        stopCode = s.stopCode,
                        routeOrder = s.order,
                        lat = s.lat,
                        lng = s.lng,
                        descr = s.description,
                        fetchedAtMillis = t,
                    )
                },
            )
        }

        return try {
            var effective = trimmed
            var raw = limiter.run(EndpointRateLimiter.gateWebGetStops(trimmed)) {
                api.webGetStops(routeCode = trimmed)
            }
            var mapped = mapWebStops(raw)
            if (mapped.isEmpty()) {
                val resolved = resolveLineToRouteCode(trimmed)
                if (resolved != null && resolved != trimmed) {
                    effective = resolved
                    raw = limiter.run(EndpointRateLimiter.gateWebGetStops(effective)) {
                        api.webGetStops(routeCode = effective)
                    }
                    mapped = mapWebStops(raw)
                }
            }
            if (mapped.isNotEmpty()) {
                persistRouteStops(effective, mapped)
            }
            RouteStopsFetch(mapped, effective)
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            var effective = trimmed
            var fromCache = loadFromCache(trimmed)
            if (fromCache.isEmpty()) {
                val resolved = resolveLineToRouteCodeFromCache(trimmed)
                if (resolved != null) {
                    effective = resolved
                    fromCache = loadFromCache(resolved)
                }
            }
            RouteStopsFetch(fromCache, effective)
        }
    }

    private suspend fun resolveLineToRouteCode(userInput: String): String? {
        val line = dao.linesByExactCodeOrId(userInput).firstOrNull() ?: return null
        return try {
            val routes = limiter.run(EndpointRateLimiter.gateWebGetRoutes(line.lineCode)) {
                api.webGetRoutes(lineCode = line.lineCode)
            }
            routes.firstOrNull { !it.routeCode.isNullOrBlank() }
                ?.routeCode?.trim().orEmpty().ifBlank { null }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            null
        }
    }

    private suspend fun resolveLineToRouteCodeFromCache(userInput: String): String? {
        val line = dao.linesByExactCodeOrId(userInput).firstOrNull() ?: return null
        return dao.routesForLine(line.lineCode).firstOrNull()?.routeCode
    }

    suspend fun getBusesOnRoute(routeCode: String): List<BusOnRoute> {
        return try {
            val raw = limiter.run(EndpointRateLimiter.EP_BUS_LOCATION) {
                api.getBusLocation(routeCode = routeCode.trim())
            }
            raw.mapNotNull { dto ->
                val no = dto.vehNo?.trim().orEmpty().ifBlank { return@mapNotNull null }
                val lat = dto.csLat?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = dto.csLng?.toDoubleOrNull() ?: return@mapNotNull null
                BusOnRoute(vehicleNo = no, lat = lat, lng = lng)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getClosestStops(lat: Double, lng: Double): List<NearbyStop> {
        return try {
            val raw = limiter.run(EndpointRateLimiter.EP_CLOSEST_STOPS) {
                api.getClosestStops(lat = lat.toString(), lng = lng.toString())
            }
            raw.mapNotNull { it.toNearby() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun OasaClosestStopJson.toNearby(): NearbyStop? {
        val code = stopCode?.trim().orEmpty().ifBlank { return null }
        val la = stopLat?.toDoubleOrNull() ?: return null
        val lo = stopLng?.toDoubleOrNull() ?: return null
        val dist = distance?.toDoubleOrNull()
        return NearbyStop(
            stopCode = code,
            description = stopDescr?.trim().orEmpty(),
            lat = la,
            lng = lo,
            distanceKm = dist,
        )
    }

    private fun ArrivalCacheEntity.toDetail(): ArrivalDetail = ArrivalDetail(
        routeCode = routeCode,
        vehCode = vehCode,
        minutes = minutes,
        destinationLabel = destinationLabel,
        lineLabel = lineLabel,
    )

    private suspend fun mapArrivalJson(j: OasaArrivalJson, stopCode: String, now: Long): ArrivalCacheEntity? {
        val route = j.routeCode?.trim().orEmpty().ifBlank { return null }
        val veh = j.vehCode?.trim().orEmpty().ifBlank { "?" }
        val mins = parseArrivalMinutes(j.btime2)
        val routeRow = dao.routeByCode(route)
        val dest = j.routeDescr?.trim().orEmpty().ifBlank { routeRow?.descr ?: route }
        val lineCodeValue = j.lineCode?.trim().orEmpty().ifBlank { routeRow?.lineCode.orEmpty() }
        val lineRow = if (lineCodeValue.isNotBlank()) dao.lineByCode(lineCodeValue) else null
        val lineLabel = when {
            lineRow != null -> "${lineRow.lineId.ifBlank { lineRow.lineCode }} · ${lineRow.descr}"
            lineCodeValue.isNotBlank() -> lineCodeValue
            else -> veh
        }
        return ArrivalCacheEntity(
            stopCode = stopCode,
            routeCode = route,
            vehCode = veh,
            destinationLabel = dest,
            lineLabel = lineLabel,
            minutes = mins,
            fetchedAtMillis = now,
        )
    }
}

private fun OasaStopXYJson.descrValue(): String =
    stopDescr?.trim().orEmpty().ifBlank { stopDescrAlt?.trim().orEmpty() }
