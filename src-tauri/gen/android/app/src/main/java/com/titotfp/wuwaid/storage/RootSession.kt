package com.titotfp.wuwaid.storage

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class RootSession(private val activity: Activity) {
  private val lock = Any()
  private var closed = false
  private var service: IStorageUserService? = null
  private var connection: ServiceConnection? = null
  private var bindingReady: CountDownLatch? = null

  fun granted(): Boolean = synchronized(lock) {
    !closed && service?.asBinder()?.isBinderAlive == true
  }

  // Never launches su or silently reconnects during a read, write, or transaction.
  fun connected(): IStorageUserService = synchronized(lock) {
    check(!closed) { "Root storage session was destroyed" }
    service?.takeIf { it.asBinder().isBinderAlive }
      ?: throw IllegalStateException("Akses root belum aktif atau service terputus. Berikan izin root lagi.")
  }

  // Called only by the explicit permission action on the storage worker, never the UI thread.
  fun request(requestId: Long) {
    if (granted()) return
    synchronized(lock) { check(!closed) { "Root storage session was destroyed" } }
    StorageTrace.phase(requestId, "permission.root") {
      Shell.Builder.create().setContext(activity).setTimeout(60).build().use { shell ->
        if (!shell.isRoot) {
          throw SecurityException("Izin root ditolak atau su tidak tersedia. Izinkan WuwaIDMobile di pengelola root, atau pilih AxManager/Shizuku.")
        }
        val ready = CountDownLatch(1)
        val conn = object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val accepted = synchronized(lock) {
              if (!closed && connection === this) {
                service = IStorageUserService.Stub.asInterface(binder)
                ready.countDown()
                bindingReady = null
                true
              } else false
            }
            // libsu may deliver a pending bind after timeout/destroy. Release that late client.
            if (!accepted) activity.runOnUiThread { RootService.unbind(this) }
          }

          override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
              if (connection === this) service = null
              ready.countDown()
            }
          }

          override fun onNullBinding(name: ComponentName) {
            onServiceDisconnected(name)
          }
        }
        val previous = synchronized(lock) {
          check(!closed) { "Root storage session was destroyed" }
          val old = connection
          connection = conn
          service = null
          bindingReady = ready
          old
        }
        try {
          val task = onUi {
            synchronized(lock) {
              check(!closed && connection === conn) { "Root storage binding was cancelled" }
              previous?.let { RootService.unbind(it) }
              RootService.bindOrTask(
                Intent(activity, RootStorageService::class.java), Executor { it.run() }, conn
              )
            }
          }
          task?.let { shell.execTask(it) }
          check(ready.await(15, TimeUnit.SECONDS)) {
            "Root storage service tidak tersambung dalam 15 detik. Periksa pengelola root, lalu buka ulang aplikasi."
          }
          connected()
        } catch (error: Exception) {
          synchronized(lock) {
            if (connection === conn) {
              connection = null
              service = null
              bindingReady?.countDown()
              bindingReady = null
            }
          }
          activity.runOnUiThread { RootService.unbind(conn) }
          throw error
        }
      }
    }
  }

  fun close() {
    val conn = synchronized(lock) {
      closed = true
      val old = connection
      connection = null
      service = null
      bindingReady?.countDown()
      bindingReady = null
      old
    }
    conn?.let { activity.runOnUiThread { RootService.unbind(it) } }
  }

  private fun <T> onUi(action: () -> T): T {
    val task = FutureTask(action)
    activity.runOnUiThread(task)
    return task.get(15, TimeUnit.SECONDS)
  }
}
