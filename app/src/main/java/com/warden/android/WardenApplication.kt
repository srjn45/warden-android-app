package com.warden.android

import android.app.Application
import com.warden.android.data.ConnectionStore
import com.warden.android.data.WardenRepository

/**
 * Owns the process-wide singletons. P0 uses plain manual DI (no Hilt) to keep
 * the first build small; the repository is created lazily off the encrypted
 * [ConnectionStore].
 */
class WardenApplication : Application() {

    val repository: WardenRepository by lazy {
        WardenRepository(ConnectionStore(this))
    }
}
