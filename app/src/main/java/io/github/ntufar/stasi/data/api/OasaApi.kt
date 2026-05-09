package io.github.ntufar.stasi.data.api

import android.util.Log
import io.github.ntufar.stasi.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.POST
import retrofit2.http.Query

private const val BASE = "https://telematics.oasa.gr/"

interface OasaApi {
    @POST("api/")
    suspend fun webGetLines(
        @Query("act") act: String = "webGetLines",
    ): List<OasaLineJson>

    @POST("api/")
    suspend fun webGetRoutes(
        @Query("act") act: String = "webGetRoutes",
        @Query("p1") lineCode: String,
    ): List<OasaRouteJson>

    @POST("api/")
    suspend fun webGetStops(
        @Query("act") act: String = "webGetStops",
        @Query("p1") routeCode: String,
    ): List<OasaWebStopJson>

    @POST("api/")
    suspend fun getStopNameAndXY(
        @Query("act") act: String = "getStopNameAndXY",
        @Query("p1") stopCode: String,
    ): List<OasaStopXYJson>

    @POST("api/")
    suspend fun getStopArrivals(
        @Query("act") act: String = "getStopArrivals",
        @Query("p1") stopCode: String,
    ): List<OasaArrivalJson>

    @POST("api/")
    suspend fun getBusLocation(
        @Query("act") act: String = "getBusLocation",
        @Query("p1") routeCode: String,
    ): List<OasaBusJson>

    @POST("api/")
    suspend fun getClosestStops(
        @Query("act") act: String = "getClosestStops",
        @Query("p1") lat: String,
        @Query("p2") lng: String,
    ): List<OasaClosestStopJson>
}

fun createOasaApi(): OasaApi {
    val logging = HttpLoggingInterceptor { line -> Log.d("StasiOasa", line) }.apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Stasi/1.0 (+https://github.com/you/stasi)")
                .build()
            chain.proceed(req)
        }
        .build()

    return Retrofit.Builder()
        .baseUrl(BASE)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OasaApi::class.java)
}
