package com.example.DSH_Mobile.vm

import android.content.Context
import com.example.DSH_Mobile.dsh.DshClient
import com.example.DSH_Mobile.dsh.DshRepository
import com.example.DSH_Mobile.store.SettingsStore

/** Process-wide singletons, initialized in MainActivity.onCreate. */
object Graph {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    val settings: SettingsStore by lazy { SettingsStore(app) }
    val client: DshClient by lazy { DshClient() }
    val repo: DshRepository by lazy { DshRepository(client) }
}
