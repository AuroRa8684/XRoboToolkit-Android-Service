package com.xrobotoolkit.androidservice.protocol

data class XrtPacket(
    val head: Int,
    val cmd: Int,
    val length: Int,
    val body: ByteArray,
    val timestamp: Long,
    val tail: Int,
    val rawBytes: ByteArray
)
