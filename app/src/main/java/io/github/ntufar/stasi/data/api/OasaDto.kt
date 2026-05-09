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
