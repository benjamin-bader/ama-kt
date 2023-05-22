package com.bendb.ama

import kotlinx.serialization.Serializable

@Serializable
data class Configuration(
    val port: Int = 9977,
)
