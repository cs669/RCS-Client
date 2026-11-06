package com.android.rcsclient.telephony

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * Helper for managing Android's Default SMS / Messaging App role (`RoleManager.ROLE_SMS`).
 */
object DefaultSmsRoleHelper {

    fun isDefaultSmsApp(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
                }
            }
            val defaultPkg = Telephony.Sms.getDefaultSmsPackage(context)
            defaultPkg == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    fun createRequestDefaultSmsRoleIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                }
            }
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
