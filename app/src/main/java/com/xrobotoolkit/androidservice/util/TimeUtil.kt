package com.xrobotoolkit.androidservice.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtil {
    private val readableFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val sessionFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun formatReadable(epochMs: Long): String {
        if (epochMs <= 0L) return "-"
        return readableFormat.format(Date(epochMs))
    }

    fun sessionNameNow(): String = sessionFormat.format(Date())
}
