package com.example.impulse.util

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    @Test
    fun reportContainsCoreFieldsAndExtra() {
        val ex = RuntimeException("boom")
        val report = CrashLog.buildCrashReport(
            thread = Thread.currentThread(),
            throwable = ex,
            versionName = "2.5.1",
            versionCode = 6,
            sdkInt = 29,
            release = "10",
            manufacturer = "Xiaomi",
            model = "Redmi Note 8",
            timeMillis = 0L,
            extra = "  prod_001: READY lastError=null\n  logBytes=123456",
        )
        assertTrue(report.contains("App version: 2.5.1 (6)"))
        assertTrue(report.contains("SDK: 29 (10)"))
        assertTrue(report.contains("Xiaomi Redmi Note 8"))
        assertTrue(report.contains("boom"))
        assertTrue(report.contains("prod_001: READY lastError=null"))
        assertTrue(report.contains("logBytes=123456"))
    }
}
