package app.tracklogger.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvFormatTest {
    private val cols = CsvFormat.GPS_HEADER.split(",")

    @Test
    fun gpsRowWithMissingValuesKeepsColumnCount() {
        val row = CsvFormat.gpsRow(1000, 2000, 13.0, 80.0, null, null, null, null, null, 7).trimEnd('\n').split(",")
        assertEquals(cols.size, row.size)
        // alt, speed, heading, hacc, sacc are empty
        for (name in listOf("alt_m", "speed_mps", "heading_deg", "hacc_m", "sacc_mps")) {
            assertEquals(name, "", row[cols.indexOf(name)])
        }
        assertEquals("2", row[cols.indexOf("fix_type")])
        assertEquals("7", row[cols.indexOf("sats")])
    }

    @Test
    fun gpsRowWithAltitudeIsFixType3() {
        val row = CsvFormat.gpsRow(1000, 2000, 13.0, 80.0, 12.345, 20f, 90f, 3.5f, 0.2f, 9).trimEnd('\n').split(",")
        assertEquals(cols.size, row.size)
        assertEquals("12.35", row[cols.indexOf("alt_m")])
        assertEquals("3", row[cols.indexOf("fix_type")])
    }

    @Test
    fun imuRowHasSevenColumns() {
        val row = CsvFormat.imuRow(5, 1f, 2f, 3f, 0.1f, 0.2f, 0.3f).trimEnd('\n').split(",")
        assertEquals(CsvFormat.IMU_HEADER.split(",").size, row.size)
    }

    @Test
    fun imuPeriodMaxUsesSensorMinimum() {
        assertEquals(2500, CsvFormat.imuPeriodUs(2500, -1))
        assertEquals(5000, CsvFormat.imuPeriodUs(0, -1))
    }

    @Test
    fun imuPeriodFixedRateIsClamped() {
        assertEquals(10000, CsvFormat.imuPeriodUs(2500, 100))
        assertEquals(10000, CsvFormat.imuPeriodUs(10000, 200)) // sensor cannot do 200 Hz, gets 100 Hz
        assertEquals(40000, CsvFormat.imuPeriodUs(2500, 25))
    }
}
