package io.github.ntufar.stasi.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class OasaBusJsonParsingTest {

    private val gson = Gson()

    @Test
    fun gson_deserializesGetBusLocationShape() {
        val json = """
            [
              {"VEH_NO":"123","CS_LAT":"37.9","CS_LNG":"23.7","ROUTE_CODE":"2033"}
            ]
        """.trimIndent()
        val type = object : TypeToken<List<OasaBusJson>>() {}.type
        val list: List<OasaBusJson> = gson.fromJson(json, type)
        assertEquals(1, list.size)
        assertEquals("123", list[0].vehNo)
        assertEquals("37.9", list[0].csLat)
        assertEquals("23.7", list[0].csLng)
        assertEquals("2033", list[0].routeCode)
    }
}
