package com.titotfp.wuwaid.storage

import android.content.Intent
import android.os.IBinder
import android.os.Process
import frb.axeron.shell.AxShellService

// Axeron's token-authenticated AxShellService hosts the same restricted storage implementation.
// No Axeron file API, arbitrary shell command, or alternate installer is exposed to the UI.
class AxManagerStorageService : AxShellService() {
  private var storage: StorageUserService? = null

  override fun onBind(intent: Intent): IBinder {
    check(Process.myUid() == 0 || Process.myUid() == 2000) {
      "AxManager storage service did not obtain root or shell privileges"
    }
    return storage ?: StorageUserService().also { storage = it }
  }

  override fun onDestroy() {
    storage?.close()
  }
}
