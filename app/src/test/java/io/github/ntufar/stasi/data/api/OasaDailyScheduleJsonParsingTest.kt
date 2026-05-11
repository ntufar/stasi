package io.github.ntufar.stasi.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OasaDailyScheduleJsonParsingTest {

    private val gson = Gson()

    @Test
    fun gson_deserializesComeGoBuckets() {
        val json = """
            {
              "come": [
                {
                  "sde_start1": "1900-01-01 05:00:00",
                  "sde_end1": "1900-01-01 08:00:00",
                  "sdd_sort": 1
                }
              ],
              "go": []
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, OasaDailyScheduleResponseJson::class.java)
        assertNotNull(parsed)
        val come = requireNotNull(parsed.come)
        assertEquals(1, come.size)
        assertEquals("1900-01-01 05:00:00", come[0].sdeStart1)
        assertEquals("1900-01-01 08:00:00", come[0].sdeEnd1)
        assertEquals(0, parsed.go?.size ?: 0)
    }
}
