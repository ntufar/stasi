package io.github.ntufar.stasi.data.api

import com.google.gson.annotations.SerializedName

data class OasaLineJson(
    @SerializedName("LineCode") val lineCode: String?,
    @SerializedName("LineID") val lineId: String?,
    @SerializedName("LineDescr") val lineDescr: String?,
)

data class OasaRouteJson(
    @SerializedName("RouteCode") val routeCode: String?,
    @SerializedName("LineCode") val lineCode: String?,
    @SerializedName("RouteDescr") val routeDescr: String?,
    @SerializedName("RouteType") val routeType: Int?,
)

/** Raw JSON from [webGetStops](https://oasa-telematics-api.readthedocs.io/en/latest/webGetStops.html). */
data class OasaWebStopJson(
    @SerializedName("StopCode") val stopCode: String?,
    @SerializedName("StopDescr") val stopDescr: String?,
    @SerializedName("StopLat") val stopLat: String?,
    @SerializedName("StopLng") val stopLng: String?,
    @SerializedName("RouteStopOrder") val routeStopOrder: String?,
)

data class OasaStopXYJson(
    @SerializedName("stop_descr") val stopDescr: String?,
    @SerializedName("StopDescr") val stopDescrAlt: String?,
    @SerializedName("stop_lat") val stopLatLower: String?,
    @SerializedName("StopLat") val stopLatUpper: String?,
    @SerializedName("stop_lng") val stopLngLower: String?,
    @SerializedName("StopLng") val stopLngUpper: String?,
    @SerializedName("stop_id") val stopId: String?,
)

/** [getStopArrivals](https://oasa-telematics-api.readthedocs.io/en/latest/getStopArrivals.html) — fields vary; we map what exists. */
data class OasaArrivalJson(
    @SerializedName("route_code") val routeCode: String?,
    @SerializedName("veh_code") val vehCode: String?,
    @SerializedName("btime2") val btime2: String?,
    @SerializedName("line_code") val lineCode: String?,
    @SerializedName("route_descr") val routeDescr: String?,
)

/** Raw JSON from [webRoutesForStop](https://oasa-telematics-api.readthedocs.io/en/latest/webRoutesForStop.html). */
data class OasaWebRouteForStopJson(
    @SerializedName("RouteCode") val routeCode: String?,
    @SerializedName("LineCode") val lineCode: String?,
    @SerializedName("RouteDescr") val routeDescr: String?,
    @SerializedName("LineID") val lineId: String?,
    @SerializedName("LineDescr") val lineDescr: String?,
)

/** Raw JSON from [getBusLocation](https://oasa-telematics-api.readthedocs.io/en/latest/getBusLocation.html). */
data class OasaBusJson(
    @SerializedName("VEH_NO") val vehNo: String?,
    @SerializedName("CS_LAT") val csLat: String?,
    @SerializedName("CS_LNG") val csLng: String?,
    @SerializedName("ROUTE_CODE") val routeCode: String?,
)

data class OasaClosestStopJson(
    @SerializedName("StopCode") val stopCode: String?,
    @SerializedName("StopDescr") val stopDescr: String?,
    @SerializedName("StopLat") val stopLat: String?,
    @SerializedName("StopLng") val stopLng: String?,
    @SerializedName("distance") val distance: String?,
)

data class OasaDailyScheduleResponseJson(
    @SerializedName("come") val come: List<OasaDailyScheduleSlotJson>?,
    @SerializedName("go") val go: List<OasaDailyScheduleSlotJson>?,
)

data class OasaDailyScheduleSlotJson(
    @SerializedName("line_descr") val lineDescr: String?,
    @SerializedName("sde_start1") val sdeStart1: String?,
    @SerializedName("sde_end1") val sdeEnd1: String?,
    @SerializedName("sde_start2") val sdeStart2: String?,
    @SerializedName("sde_end2") val sdeEnd2: String?,
    @SerializedName("sdd_start1") val sddStart1: String?,
    /** Sort order within the section; API may send a number. */
    @SerializedName("sdd_sort") val sddSort: Number?,
)
