package io.github.ntufar.stasi.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class OasaWebRouteForStopJsonParsingTest {

    private val gson = Gson()

    @Test
    fun gson_deserializesDocumentedWebRoutesForStopShape() {
        val json = """
            [
              {
                "RouteCode":"1867",
                "LineCode":"851",
                "RouteDescr":"ΠΕΙΡΑΙΑΣ - Ν. ΣΜΥΡΝΗ",
                "LineID":"130",
                "LineDescr":"ΠΕΙΡΑΙΑΣ - Ν. ΣΜΥΡΝΗ (ΚΥΚΛΙΚΗ)"
              }
            ]
        """.trimIndent()

        val type = object : TypeToken<List<OasaWebRouteForStopJson>>() {}.type
        val list: List<OasaWebRouteForStopJson> = gson.fromJson(json, type)

        assertEquals(1, list.size)
        assertEquals("1867", list[0].routeCode)
        assertEquals("851", list[0].lineCode)
        assertEquals("130", list[0].lineId)
        assertEquals("ΠΕΙΡΑΙΑΣ - Ν. ΣΜΥΡΝΗ", list[0].routeDescr)
        assertEquals("ΠΕΙΡΑΙΑΣ - Ν. ΣΜΥΡΝΗ (ΚΥΚΛΙΚΗ)", list[0].lineDescr)
    }

    @Test
    fun gson_deserializesLiveApi_numericRouteAndLineCode_andStringLineId() {
        val json = """
            [{
              "RouteCode":1754,
              "LineCode":799,
              "RouteDescr":"ΕΛ.ΒΕΝΙΖΕΛΟΥ - ΚΑΙΣΑΡΙΑΝΗ",
              "LineID":"224",
              "LineDescr":"ΚΑΙΣΑΡΙΑΝΗ - ΕΛ. ΒΕΝΙΖΕΛΟΥ"
            }]
        """.trimIndent()

        val type = object : TypeToken<List<OasaWebRouteForStopJson>>() {}.type
        val list: List<OasaWebRouteForStopJson> = gson.fromJson(json, type)

        assertEquals(1, list.size)
        assertEquals("1754", list[0].routeCode)
        assertEquals("799", list[0].lineCode)
        assertEquals("224", list[0].lineId)
    }
}
