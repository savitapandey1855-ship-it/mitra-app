package com.mitra.assistant

import android.content.Context
import android.provider.ContactsContract

/** Contacts se naam ka phone number nikalta hai (READ_CONTACTS permission chahiye). */
object Contacts {
    fun numberFor(c: Context, name: String): String? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
            val args = arrayOf("%$name%")
            c.contentResolver.query(uri, proj, sel, args, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val n = cur.getString(1) ?: return null
                    return n.replace(" ", "").replace("-", "")
                }
            }
        } catch (e: Exception) {}
        return null
    }
}
