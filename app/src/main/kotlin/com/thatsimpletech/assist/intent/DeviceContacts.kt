package com.thatsimpletech.assist.intent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.thatsimpletech.assist.core.intent.ContactPick

/** On-device contact rows for [ContactPick]. READ_CONTACTS; no numbers are logged. */
class DeviceContacts(private val context: Context) {
    fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun phones(): List<ContactPick.Hit> {
        if (!granted()) return emptyList()
        val out = ArrayList<ContactPick.Hit>(64)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iType = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            if (iName < 0 || iNum < 0) return emptyList()
            while (c.moveToNext() && out.size < MAX) {
                val name = c.getString(iName)?.trim().orEmpty()
                val number = c.getString(iNum)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                val mobile = iType >= 0 && c.getInt(iType) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                out += ContactPick.Hit(name, number, mobile)
            }
        }
        return out
    }

    companion object {
        const val MAX = 500
    }
}
