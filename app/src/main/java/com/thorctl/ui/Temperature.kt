package com.thorctl.ui

import kotlin.math.roundToInt

internal const val FahrenheitPreference = "use_fahrenheit"

internal fun formatTemperature(celsius: Float, fahrenheit: Boolean): String =
    if (fahrenheit) "${(celsius * 9f / 5f + 32f).roundToInt()} °F" else "${celsius.toInt()} °C"
