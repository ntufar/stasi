package com.example.stasi.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class OasaArrivalJsonParsingTest {

    private val gson = Gson()

    @Test
    fun gson_deserializesDocumentedGetStopArrivalsShape() {
        val json = """
            [
              {"route_code":"2033","veh_code":"50328","btime2":"5"},
              {"route_code":"2005","veh_code":"20521","btime2":"5"}
            ]
        """.trimIndent()

        val type = object : TypeToken<List<OasaArrivalJson>>() {}.type
        val list: List<OasaArrivalJson> = gson.fromJson(json, type)

        assertEquals(2, list.size)
        assertEquals("2033", list[0].routeCode)
        assertEquals("50328", list[0].vehCode)
        assertEquals("5", list[0].btime2)
    }
}
