package com.android.rcsclient

import android.app.Application
import com.android.rcsclient.data.RcsDatabaseHelper
import com.android.rcsclient.data.RcsRepository
import com.android.rcsclient.telephony.AndroidSystemMessageStore
import com.android.rcsclient.telephony.CarrierImsRcsManager

class RcsApplication : Application() {

    lateinit var repository: RcsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RcsRepository(
            dbHelper = RcsDatabaseHelper(this),
            imsRcsManager = CarrierImsRcsManager(this),
            systemStore = AndroidSystemMessageStore(this)
        )
    }
}
