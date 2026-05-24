package com.xrobotoolkit.androidservice.network

enum class BindMode {
    SELECTED_IP,
    ANY_IPV4;

    companion object {
        fun fromName(value: String?): BindMode {
            if (value.isNullOrBlank()) return ANY_IPV4
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ANY_IPV4
        }
    }
}
