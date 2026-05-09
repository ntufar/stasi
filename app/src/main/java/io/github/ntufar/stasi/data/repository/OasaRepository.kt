package io.github.ntufar.stasi.data.repository

import io.github.ntufar.stasi.data.api.OasaApi
import io.github.ntufar.stasi.data.api.OasaArrivalJson
import io.github.ntufar.stasi.data.api.OasaClosestStopJson
import io.github.ntufar.stasi.data.api.OasaStopXYJson
import io.github.ntufar.stasi.data.api.OasaWebRouteForStopJson
import io.github.ntufar.stasi.data.api.OasaWebStopJson
import io.github.ntufar.stasi.data.api.createOasaApi
import io.github.ntufar.stasi.data.local.ArrivalCacheEntity
import io.github.ntufar.stasi.data.local.CacheMetaEntity
import io.github.ntufar.stasi.data.local.CachedLineEntity
import io.github.ntufar.stasi.data.local.CachedRouteEntity
import io.github.ntufar.stasi.data.local.CachedStopEntity
import io.github.ntufar.stasi.data.local.RouteStopCacheEntity
import io.github.ntufar.stasi.data.local.StasiDao
import io.github.ntufar.stasi.data.util.EndpointRateLimiter
import io.github.ntufar.stasi.data.util.lineSearchNorm
import io.github.ntufar.stasi.data.util.normalizeGreek
import io.github.ntufar.stasi.data.util.stopSearchNorm
import io.github.ntufar.stasi.data.util.parseArrivalMinutes
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

data class RouteDirection(
    val routeCode: String,
    val descr: String,
)

data class LineRouteInfo(
    val lineCode: String,
    /** Public-facing line number, e.g. "750"; falls back to [lineCode] when absent. */
    val lineId: String,
    val lineDescr: String,
    val directions: List<RouteDirection>,
)

data class ArrivalDetail(
    val routeCode: String,
    val vehCode: String,
    val minutes: Int,
    val destinationLabel: String,
    val lineLabel: String,
    /**
     * When this stop is not the route's first stop, minutes until the next vehicle on [routeCode]
     * is expected to depart from that route's origin (first stop in order).
     */
    val originDepartureMinutes: Int? = null,
    val originStopDescription: String? = null,
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
    suspend fun warmLinesCatalogIfEmpty(): Boolean {
        ensureLinesCatalogForResolve()
        return try {
            dao.allLines().isNotEmpty()
        } catch (_: Exception) {
            false
        }
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

    /**
     * First stop (minimum [RouteStop.order]) for [routeCode], from cache or after a network refresh
     * via [getRouteStops] when the cache is empty.
     */
    suspend fun getRouteOriginStopCode(routeCode: String): String? {
        val rc = routeCode.trim()
        if (rc.isEmpty()) return null
        val fromDao = try {
            dao.routeStops(rc).minByOrNull { it.routeOrder }?.stopCode?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
        if (fromDao != null) return fromDao
        val fetch = getRouteStops(rc)
        return fetch.stops.minByOrNull { it.order }?.stopCode?.trim()?.ifBlank { null }
    }

    /**
     * Adds [ArrivalDetail.originDepartureMinutes] / [ArrivalDetail.originStopDescription] for each
     * arrival whose route starts at a different stop than [stopCode], using live data at the origin.
     */
    suspend fun enrichArrivalsWithOriginBoardings(
        stopCode: String,
        arrivals: List<ArrivalDetail>,
    ): List<ArrivalDetail> {
        if (arrivals.isEmpty()) return arrivals
        val distinctRoutes = arrivals.map { it.routeCode }.filter { it.isNotBlank() }.distinct()
        val originByRoute = mutableMapOf<String, String>()
        for (route in distinctRoutes) {
            val origin = getRouteOriginStopCode(route) ?: continue
            if (origin == stopCode) continue
            originByRoute[route] = origin
        }
        if (originByRoute.isEmpty()) return arrivals
        val labelCache = mutableMapOf<String, String>()
        suspend fun labelFor(code: String): String =
            labelCache.getOrPut(code) {
                getStopLabel(code).ifBlank { code }
            }
        val arrivalsByOriginStop = originByRoute.values.distinct().associateWith { o ->
            getStopArrivals(o)
        }
        val minutesByRoute = mutableMapOf<String, Int?>()
        for ((route, originStop) in originByRoute) {
            val atOrigin = arrivalsByOriginStop[originStop].orEmpty()
            val next = atOrigin
                .filter { it.routeCode == route }
                .minByOrNull { it.minutes }
                ?.minutes
            minutesByRoute[route] = next
        }
        return arrivals.map { arr ->
            val originStop = originByRoute[arr.routeCode] ?: return@map arr
            val mins = minutesByRoute[arr.routeCode]
            if (mins == null) arr
            else arr.copy(
                originDepartureMinutes = mins,
                originStopDescription = labelFor(originStop),
            )
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
            val routesAtStop = try {
                limiter.run(EndpointRateLimiter.EP_WEB_ROUTES_FOR_STOP) {
                    api.webRoutesForStop(stopCode = stopCode)
                }.routeMetaByRouteCode()
            } catch (_: Exception) {
                emptyMap()
            }
            val json = limiter.run(EndpointRateLimiter.EP_GET_ARRIVALS) {
                api.getStopArrivals(stopCode = stopCode)
            }
            val rows = json.mapNotNull { mapArrivalJson(it, stopCode, now, routesAtStop) }
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

    /**
     * First route code for [lineCodeOrId] (matches [StasiDao.linesByExactCodeOrId]), for map / webGetStops.
     * Uses Room routes when present, otherwise [webGetRoutes].
     */
    suspend fun primaryRouteCodeForLine(lineCodeOrId: String): String? {
        val trimmed = lineCodeOrId.trim()
        if (trimmed.isEmpty()) return null
        resolveLineToRouteCodeFromCache(trimmed)?.let { return it }
        return resolveLineToRouteCode(trimmed)
    }

    private suspend fun resolveLineToRouteCodeFromCache(userInput: String): String? {
        val line = dao.linesByExactCodeOrId(userInput).firstOrNull() ?: return null
        return dao.routesForLine(line.lineCode).firstOrNull()?.routeCode
    }

    /**
     * Returns the line metadata + every direction (route) for the line containing [routeCode].
     *
     * Uses cached `cached_routes` / `cached_lines` first (avoiding extra network during the live
     * map polling loop). Falls back to `webRoutesForStop` (using [hintStopCode] from the route's
     * stops list) to resolve the lineCode, and to `webGetRoutes` to enumerate directions when the
     * cache only knows about a single direction.
     */
    suspend fun getLineRouteInfoForRoute(
        routeCode: String,
        hintStopCode: String? = null,
    ): LineRouteInfo? {
        val rc = routeCode.trim()
        if (rc.isEmpty()) return null

        var lineCode: String? = try {
            dao.routeByCode(rc)?.lineCode?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }

        var fetchedDirections: List<RouteDirection>? = null
        var lineMetaFromWeb: RouteAtStopMeta? = null

        if (lineCode == null && !hintStopCode.isNullOrBlank()) {
            val rows = try {
                limiter.run(EndpointRateLimiter.EP_WEB_ROUTES_FOR_STOP) {
                    api.webRoutesForStop(stopCode = hintStopCode)
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                emptyList()
            }
            val match = rows.firstOrNull { it.routeCode?.trim() == rc }
            lineCode = match?.lineCode?.trim()?.ifBlank { null }
            if (match != null) {
                lineMetaFromWeb = RouteAtStopMeta(
                    lineCode = match.lineCode?.trim().orEmpty(),
                    lineId = match.lineId?.trim().orEmpty(),
                    lineDescr = match.lineDescr?.trim().orEmpty(),
                    routeDescr = match.routeDescr?.trim().orEmpty(),
                )
            }
        }

        if (lineCode == null) return null

        val resolvedLineCode = lineCode

        val cachedRoutes = try {
            dao.routesForLine(resolvedLineCode)
        } catch (_: Exception) {
            emptyList()
        }
        val cachedDirections = cachedRoutes.map { RouteDirection(it.routeCode, it.descr) }

        if (cachedDirections.size < 2) {
            fetchedDirections = try {
                val raw = limiter.run(EndpointRateLimiter.gateWebGetRoutes(resolvedLineCode)) {
                    api.webGetRoutes(lineCode = resolvedLineCode)
                }
                val mapped = raw.mapNotNull { r ->
                    val code = r.routeCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
                    RouteDirection(
                        routeCode = code,
                        descr = r.routeDescr?.trim().orEmpty(),
                    )
                }
                if (mapped.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    dao.insertRoutes(
                        mapped.map { d ->
                            CachedRouteEntity(
                                routeCode = d.routeCode,
                                lineCode = resolvedLineCode,
                                descr = d.descr,
                                normDescr = normalizeGreek(d.descr),
                                fetchedAtMillis = now,
                            )
                        },
                    )
                }
                mapped
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                null
            }
        }

        val directions = (fetchedDirections ?: cachedDirections).ifEmpty {
            // Last resort: at least surface the current direction so the UI has something to show.
            listOf(RouteDirection(rc, ""))
        }

        val lineRow = try {
            dao.lineByCode(resolvedLineCode)
        } catch (_: Exception) {
            null
        }
        val lineId = lineRow?.lineId?.trim().orEmpty()
            .ifBlank { lineMetaFromWeb?.lineId.orEmpty() }
        val lineDescr = lineRow?.descr?.trim().orEmpty()
            .ifBlank { lineMetaFromWeb?.lineDescr.orEmpty() }

        return LineRouteInfo(
            lineCode = resolvedLineCode,
            lineId = lineId,
            lineDescr = lineDescr,
            directions = directions,
        )
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

    private suspend fun mapArrivalJson(
        j: OasaArrivalJson,
        stopCode: String,
        now: Long,
        routesAtStop: Map<String, RouteAtStopMeta>,
    ): ArrivalCacheEntity? {
        val route = j.routeCode?.trim().orEmpty().ifBlank { return null }
        val veh = j.vehCode?.trim().orEmpty().ifBlank { "?" }
        val mins = parseArrivalMinutes(j.btime2)
        val meta = routesAtStop[route]
        val routeRow = dao.routeByCode(route)
        val dest = j.routeDescr?.trim().orEmpty()
            .ifBlank { routeRow?.descr.orEmpty() }
            .ifBlank { meta?.routeDescr.orEmpty() }
            .ifBlank { route }
        val lineCodeValue = j.lineCode?.trim().orEmpty()
            .ifBlank { routeRow?.lineCode.orEmpty() }
            .ifBlank { meta?.lineCode.orEmpty() }
        val lineRow = if (lineCodeValue.isNotBlank()) dao.lineByCode(lineCodeValue) else null
        val lineCodeFromRoute = routeRow?.lineCode?.trim().orEmpty()
        val lineLabel = when {
            lineRow != null -> {
                val num = lineRow.lineId.ifBlank { lineRow.lineCode }
                val name = lineRow.descr.trim()
                when {
                    num.isNotBlank() && name.isNotBlank() -> "$num · $name"
                    num.isNotBlank() -> num
                    name.isNotBlank() -> name
                    else -> route
                }
            }
            meta != null -> {
                val num = meta.lineId.ifBlank { meta.lineCode }
                val name = meta.lineDescr.trim()
                when {
                    num.isNotBlank() && name.isNotBlank() -> "$num · $name"
                    num.isNotBlank() -> num
                    name.isNotBlank() -> name
                    else -> route
                }
            }
            lineCodeValue.isNotBlank() -> lineCodeValue
            lineCodeFromRoute.isNotBlank() -> lineCodeFromRoute
            else -> route
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

private data class RouteAtStopMeta(
    val lineCode: String,
    val lineId: String,
    val lineDescr: String,
    val routeDescr: String,
)

private fun List<OasaWebRouteForStopJson>.routeMetaByRouteCode(): Map<String, RouteAtStopMeta> =
    mapNotNull { j ->
        val rc = j.routeCode?.trim().orEmpty().ifBlank { return@mapNotNull null }
        rc to RouteAtStopMeta(
            lineCode = j.lineCode?.trim().orEmpty(),
            lineId = j.lineId?.trim().orEmpty(),
            lineDescr = j.lineDescr?.trim().orEmpty(),
            routeDescr = j.routeDescr?.trim().orEmpty(),
        )
    }.toMap()

private fun OasaStopXYJson.descrValue(): String =
    stopDescr?.trim().orEmpty().ifBlank { stopDescrAlt?.trim().orEmpty() }
