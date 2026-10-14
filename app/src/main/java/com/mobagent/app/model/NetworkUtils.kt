package com.mobagent.app.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Hozirgi tarmoq pullik/cheklangan (mobil internet, hotspot) bo'lsa true. Aniqlab bo'lmasa false. */
fun isMeteredNetwork(context: Context): Boolean = try {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.isActiveNetworkMetered
} catch (e: Exception) {
    false
}

/** [NetworkCapabilities] bo'yicha tarmoq pullik/cheklangan (mobil internet, hotspot) bo'lsa true. */
fun NetworkCapabilities.isMetered(): Boolean =
    !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
