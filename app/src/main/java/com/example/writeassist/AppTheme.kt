package com.example.writeassist

import android.content.Context
import android.content.res.Configuration

object AppTheme {
    fun isDark(context: Context): Boolean {
        val selected = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("appearance", "system")
        return when (selected) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
