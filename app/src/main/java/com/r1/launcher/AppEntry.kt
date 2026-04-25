package com.r1.launcher

import android.content.pm.ResolveInfo

sealed class AppEntry {
    data class Real(val info: ResolveInfo) : AppEntry()
    object Settings : AppEntry()
    object OpenClaw : AppEntry()
    object AudioTest : AppEntry()
}
