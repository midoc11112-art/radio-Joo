package com.example.radiojooo

import android.content.Context
import com.example.radiojooo.data.Health
import com.example.radiojooo.data.Repo

/** Tiny service locator: one Repo and one Health for the whole process. */
object Graph {
    @Volatile private var repoInst: Repo? = null
    @Volatile private var healthInst: Health? = null

    fun repo(context: Context): Repo = repoInst ?: synchronized(this) {
        repoInst ?: Repo(context.applicationContext).also { repoInst = it }
    }

    fun health(context: Context): Health = healthInst ?: synchronized(this) {
        healthInst ?: Health(context.applicationContext).also { healthInst = it }
    }
}
