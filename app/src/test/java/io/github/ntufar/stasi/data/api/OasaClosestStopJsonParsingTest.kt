package io.github.ntufar.stasi.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class OasaClosestStopJsonParsingTest {

    private val gson = Gson()

    @Test
    fun gson_deserializesGetClosestStopsShape() {
        val json = """
            [
              {
                "StopCode":"07001",
                "StopDescr":"ΣΥΝΤΑΓΜΑ",
                "StopLat":"37.97",
                "StopLng":"23.73",
                "distance":"0.12"
              }
            ]
        """.trimIndent()
        val type = object : TypeToken<List<OasaClosestStopJson>>() {}.type
        val list: List<OasaClosestStopJson> = gson.fromJson(json, type)
        assertEquals(1, list.size)
        assertEquals("07001", list[0].stopCode)
        assertEquals("ΣΥΝΤΑΓΜΑ", list[0].stopDescr)
        assertEquals("0.12", list[0].distance)
    }
}
