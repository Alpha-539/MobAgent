package com.mobagent.app.model

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

object ModelStarStore {
    private const val PREFS_NAME = "model_stars_prefs"
    val starsMap = mutableStateMapOf<String, Int>()
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (value is Int) {
                starsMap[key] = value
            }
        }
        initialized = true
    }

    fun getStars(context: Context, modelId: String): Int {
        init(context)
        return starsMap[modelId] ?: 0
    }

    fun recordDownload(context: Context, modelId: String) {
        init(context)
        val current = starsMap[modelId] ?: 0
        val next = current + 1
        starsMap[modelId] = next
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(modelId, next).apply()
    }
}
