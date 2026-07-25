package com.titotfp.wuwaid

import android.os.Handler
import android.os.Looper

internal fun interface ScheduledTask {
    fun cancel()
}

internal interface TaskScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledTask
}

internal class HandlerTaskScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TaskScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledTask {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0L))
        return ScheduledTask { handler.removeCallbacks(runnable) }
    }
}
