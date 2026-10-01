package com.example.meteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicsTest {
    @Test fun dewPointOfDryRoom() = assertEquals(-2.0, Physics.dewPoint(22.0, 20.0), 0.1)

    @Test fun dewPointAtSaturationEqualsTemperature() = assertEquals(10.0, Physics.dewPoint(10.0, 100.0), 1e-6)

    @Test fun seaLevelAddsAbout7hPaFor60m() = assertEquals(1007.2, Physics.seaLevel(1000.0, 60.0), 0.2)

    @Test fun tendencyNeedsThreeHours() {
        val r = listOf(Record(0, 5.0, 80.0, 1000.0), Record(600, 5.0, 80.0, 999.0))
        assertNull(Physics.tendency3h(r, 0.0))
    }

    @Test fun tendencyOverThreeHours() {
        val r = (0..18).map { Record(it * 600L, 5.0, 80.0, 1000.0 - it * 0.25) }
        assertEquals(-4.5, Physics.tendency3h(r, 0.0)!!, 1e-6)
    }

    @Test fun parseLogSkipsHeaderAndMissingHumidity() {
        val r = Csv.parseLog("ts;t_C;rh_pct;p_hPa\n1790000000;3.25;-1.0;1001.50\n1790000600;3.30;85.0;1001.40\n")
        assertEquals(2, r.size)
        assertNull(r[0].h)
        assertEquals(85.0, r[1].h!!, 1e-9)
    }
}
