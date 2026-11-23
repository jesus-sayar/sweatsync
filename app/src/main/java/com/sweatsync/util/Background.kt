package com.sweatsync.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One shared pool for blocking work (network, uploads), so no code spawns raw threads. */
object Background {
    val io: ExecutorService = Executors.newCachedThreadPool()
}
