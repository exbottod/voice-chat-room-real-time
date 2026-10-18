package com.example.googlelogin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Scope yang hidup selama proses app berjalan (tidak ikut batal saat layar ditutup). */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
