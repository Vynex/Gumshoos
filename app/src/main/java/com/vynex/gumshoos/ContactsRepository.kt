package com.vynex.gumshoos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEntry(
    val id: Long,
    val name: String,
    val photoUri: String?,
    val phone: String?,
    val email: String?,
    val hasWhatsApp: Boolean,
    /** Same folding and tiering as apps, so "jose" finds "José". */
    val indexed: AppMatcher.Indexed,
) {
    fun actions(): List<ContactAction> = buildList {
        if (!phone.isNullOrBlank()) add(ContactAction.CALL)
        if (!phone.isNullOrBlank()) add(ContactAction.MESSAGE)
        if (!email.isNullOrBlank()) add(ContactAction.EMAIL)
        if (hasWhatsApp && !phone.isNullOrBlank()) add(ContactAction.WHATSAPP)
    }
}

enum class ContactAction { CALL, MESSAGE, EMAIL, WHATSAPP }

/**
 * Contacts index, loaded once at warm time like the app index.
 *
 * Optional throughout: without READ_CONTACTS the section simply doesn't appear, and nothing else
 * changes. The permission has to be requested from MainActivity — a service has no activity to
 * show the dialog from.
 */
object ContactsRepository {

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

    var contacts by mutableStateOf<List<ContactEntry>>(emptyList())
        private set

    var granted by mutableStateOf(false)
        private set

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) {
            withContext(Dispatchers.Main) { granted = false; contacts = emptyList() }
            return@withContext
        }

        // Contact details live in separate tables, so gather them by id first and join in memory —
        // one query per table rather than one per contact.
        val phones = firstValuePerContact(
            context,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val emails = firstValuePerContact(
            context,
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        val whatsAppIds = whatsAppContactIds(context)

        val loaded = mutableListOf<ContactEntry>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ),
                // Skip the entries with no name at all; they can't be searched for.
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoCol =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.isBlank()) continue
                    loaded.add(
                        ContactEntry(
                            id = id,
                            name = name,
                            photoUri = cursor.getString(photoCol),
                            phone = phones[id],
                            email = emails[id],
                            hasWhatsApp = id in whatsAppIds,
                            indexed = AppMatcher.index(name),
                        )
                    )
                }
            }
        }

        withContext(Dispatchers.Main) {
            granted = true
            contacts = loaded
        }
    }

    private fun firstValuePerContact(
        context: Context,
        uri: Uri,
        idColumn: String,
        valueColumn: String,
    ): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(uri, arrayOf(idColumn, valueColumn), null, null, null)
                ?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(idColumn)
                    val value = cursor.getColumnIndexOrThrow(valueColumn)
                    while (cursor.moveToNext()) {
                        val key = cursor.getLong(id)
                        // First wins: contacts often have several numbers and the primary is first.
                        if (key !in out) cursor.getString(value)?.let { out[key] = it }
                    }
                }
        }
        return out
    }

    /**
     * WhatsApp writes a data row per contact it can reach, which is how launchers know whether to
     * offer it. Without this the action would appear for contacts who aren't on WhatsApp.
     */
    private fun whatsAppContactIds(context: Context): Set<Long> {
        val out = mutableSetOf<Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(WHATSAPP_MIME),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                while (cursor.moveToNext()) out.add(cursor.getLong(idCol))
            }
        }
        return out
    }

    fun search(query: String, limit: Int): List<ContactEntry> {
        if (query.isBlank() || contacts.isEmpty()) return emptyList()
        return contacts
            .mapNotNull { entry ->
                AppMatcher.score(query.trim(), entry.indexed)?.let { entry to it }
            }
            .sortedWith(
                compareByDescending<Pair<ContactEntry, Int>> { it.second }
                    .thenBy { it.first.name.lowercase() }
            )
            .take(limit)
            .map { it.first }
    }

    fun perform(context: Context, entry: ContactEntry, action: ContactAction) {
        val intent = when (action) {
            // DIAL rather than CALL: it opens the dialer pre-filled and needs no permission.
            ContactAction.CALL ->
                Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(entry.phone.orEmpty())}".toUri())

            ContactAction.MESSAGE ->
                Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(entry.phone.orEmpty())}".toUri())

            ContactAction.EMAIL ->
                Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(entry.email.orEmpty())}".toUri())

            ContactAction.WHATSAPP ->
                Intent(
                    Intent.ACTION_VIEW,
                    "https://wa.me/${entry.phone.orEmpty().filter { it.isDigit() }}".toUri(),
                ).setPackage(WHATSAPP_PACKAGE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the contact in the People app. */
    fun openContact(context: Context, entry: ContactEntry) {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, entry.id)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // --- icons for the action buttons ------------------------------------------------------------

    /**
     * The real launcher icon of whichever app handles each action, resolved once at warm time.
     *
     * Drawing the actual Phone/Messages/Gmail/WhatsApp icons is both what the reference does and
     * self-maintaining: whatever the user has set as their dialer or mail client is what appears.
     */
    var actionIcons by mutableStateOf<Map<ContactAction, ImageBitmap>>(emptyMap())
        private set

    suspend fun loadActionIcons(context: Context) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val px = (context.resources.displayMetrics.density * 24).toInt().coerceAtLeast(24)

        val probes = mapOf(
            ContactAction.CALL to Intent(Intent.ACTION_DIAL, "tel:0000000000".toUri()),
            ContactAction.MESSAGE to Intent(Intent.ACTION_SENDTO, "smsto:0000000000".toUri()),
            ContactAction.EMAIL to Intent(Intent.ACTION_SENDTO, "mailto:a@b.c".toUri()),
        )

        val icons = mutableMapOf<ContactAction, ImageBitmap>()
        probes.forEach { (action, intent) ->
            runCatching {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.let { info ->
                    icons[action] = info.loadIcon(pm).toBitmap(px, px).asImageBitmap()
                }
            }
        }
        runCatching {
            icons[ContactAction.WHATSAPP] =
                pm.getApplicationIcon(WHATSAPP_PACKAGE).toBitmap(px, px).asImageBitmap()
        }

        withContext(Dispatchers.Main) { actionIcons = icons }
    }
}
