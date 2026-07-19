package com.vynex.gumshoos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
    /**
     * Stable across account re-syncs, unlike [id], which is why the hidden-contacts list keys on
     * this instead.
     */
    val lookupKey: String,
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
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ),
                // Skip the entries with no name at all; they can't be searched for.
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
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
                            lookupKey = cursor.getString(lookupCol) ?: continue,
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
        // Filtered here rather than at load, so the settings picker can still list a hidden contact
        // in order to un-hide it.
        return contacts
            .filterNot { it.lookupKey in GumshoosConfig.hiddenContacts }
            .mapNotNull { entry ->
                // Same tier toggles as apps: they describe how matching works, not what it's over.
                AppMatcher.score(query.trim(), entry.indexed, GumshoosConfig.matchOptions)
                    ?.let { entry to it }
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
                    .also { mail ->
                        // Send it straight to the chosen app rather than through the chooser.
                        GumshoosConfig.contactActionPackages[ContactAction.EMAIL]
                            ?.let(mail::setPackage)
                    }

            ContactAction.WHATSAPP ->
                Intent(
                    Intent.ACTION_VIEW,
                    "https://wa.me/${entry.phone.orEmpty().filter { it.isDigit() }}".toUri(),
                ).setPackage(WHATSAPP_PACKAGE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** An app that can handle a given contact action, for the settings picker. */
    data class ActionHandler(
        val packageName: String,
        val label: String,
        val icon: ImageBitmap?,
    )

    /**
     * Everything that can handle [action], for choosing between them in settings. Loaded on demand
     * rather than at warm time — it's only needed while the settings screen is open.
     */
    suspend fun handlersFor(context: Context, action: ContactAction): List<ActionHandler> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val px = (context.resources.displayMetrics.density * 32).toInt().coerceAtLeast(32)
            val intent = probeIntent(action)
            pm.queryIntentActivities(intent, 0)
                .filterNot { it.isResolver() }
                .distinctBy { it.activityInfo.packageName }
                .map { info ->
                    ActionHandler(
                        packageName = info.activityInfo.packageName,
                        label = runCatching { info.loadLabel(pm).toString() }
                            .getOrDefault(info.activityInfo.packageName),
                        icon = runCatching {
                            info.loadIcon(pm).toBitmap(px, px).asImageBitmap()
                        }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }

    private fun probeIntent(action: ContactAction): Intent = when (action) {
        ContactAction.CALL -> Intent(Intent.ACTION_DIAL, "tel:0000000000".toUri())
        ContactAction.MESSAGE -> Intent(Intent.ACTION_SENDTO, "smsto:0000000000".toUri())
        ContactAction.EMAIL -> Intent(Intent.ACTION_SENDTO, "mailto:a@b.c".toUri())
        ContactAction.WHATSAPP -> Intent(Intent.ACTION_VIEW, "https://wa.me/0".toUri())
    }

    private fun packageIcon(pm: PackageManager, packageName: String, sizePx: Int): ImageBitmap? =
        runCatching {
            pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()

    /**
     * Icon of the app that will actually handle this intent.
     *
     * resolveActivity does not return null when several apps can handle an intent and none is the
     * default — it returns the framework's ResolverActivity, whose icon is a generic cogwheel. That
     * is what put a settings gear next to contacts instead of a mail app. So: take the resolved
     * activity when it is a real one, and otherwise pick the highest-priority candidate ourselves.
     */
    private fun handlerIcon(
        pm: PackageManager,
        intent: Intent,
        sizePx: Int,
    ): ImageBitmap? {
        val preferred = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.takeUnless { it.isResolver() }
        val chosen = preferred
            ?: pm.queryIntentActivities(intent, 0).firstOrNull { !it.isResolver() }
            ?: return null
        return runCatching {
            chosen.loadIcon(pm).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()
    }

    /** The chooser rather than an app: framework-owned, and never what we want to draw. */
    private fun ResolveInfo.isResolver(): Boolean {
        val info = activityInfo ?: return true
        return info.packageName == "android" || info.name.orEmpty().contains("ResolverActivity")
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

        val probes = listOf(ContactAction.CALL, ContactAction.MESSAGE, ContactAction.EMAIL)
            .associateWith { probeIntent(it) }

        val icons = mutableMapOf<ContactAction, ImageBitmap>()
        probes.forEach { (action, intent) ->
            runCatching {
                // An explicitly chosen handler wins; otherwise fall back to resolving one.
                val chosen = GumshoosConfig.contactActionPackages[action]
                val icon = chosen?.let { packageIcon(pm, it, px) } ?: handlerIcon(pm, intent, px)
                icon?.let { icons[action] = it }
            }
        }
        runCatching {
            icons[ContactAction.WHATSAPP] =
                pm.getApplicationIcon(WHATSAPP_PACKAGE).toBitmap(px, px).asImageBitmap()
        }

        withContext(Dispatchers.Main) { actionIcons = icons }
    }
}
