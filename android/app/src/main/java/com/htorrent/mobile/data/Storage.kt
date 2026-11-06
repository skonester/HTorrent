package com.htorrent.mobile.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.htorrent.mobile.core.FileCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Writes a file safely: a temporary copy first, then a rename, so a crash never leaves half a list. */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { delete(); tmp.renameTo(this) }
}

/** The download list, kept in the app's private folder. A damaged list is kept as a copy, never silently replaced. */
class ListStore(context: Context) {
    private val file = File(context.filesDir, "downloads.json")

    fun load(): List<DownloadItem> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { DownloadItem.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "downloads.unreadable-${System.currentTimeMillis()}.json"))
            emptyList()
        }
    }

    fun save(items: List<DownloadItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        file.writeAtomically(arr.toString())
    }
}

/**
 * Cookies and passwords, encrypted with a key that never leaves the Android Keystore (AES-GCM).
 * If the key can't be used, nothing is saved in plain text: the secrets are simply not kept.
 */
class SecretStore(context: Context) {
    private val file = File(context.filesDir, "secrets.bin")
    private val alias = "htorrent-secrets"
    private var cache: MutableMap<Long, Secrets>? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized private fun all(): MutableMap<Long, Secrets> {
        cache?.let { return it }
        val map = mutableMapOf<Long, Secrets>()
        runCatching {
            if (file.exists()) {
                val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                val obj = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size))))
                obj.keys().forEach { k ->
                    val s = obj.getJSONObject(k)
                    map[k.toLong()] = Secrets(s.optString("c").ifEmpty { null }, s.optString("u").ifEmpty { null }, s.optString("p").ifEmpty { null })
                }
            }
        }
        cache = map
        return map
    }

    @Synchronized private fun persist() {
        val obj = JSONObject()
        all().forEach { (id, s) ->
            obj.put(id.toString(), JSONObject().apply { s.cookies?.let { put("c", it) }; s.user?.let { put("u", it) }; s.password?.let { put("p", it) } })
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(obj.toString().toByteArray())
            file.writeAtomically(Base64.encodeToString(out, Base64.NO_WRAP))
        }.onFailure { file.delete() }
    }

    @Synchronized fun get(id: Long): Secrets = all()[id] ?: Secrets()

    @Synchronized fun put(id: Long, secrets: Secrets) {
        if (secrets.isEmpty) all().remove(id) else all()[id] = secrets
        persist()
    }

    @Synchronized fun remove(id: Long) {
        if (all().remove(id) != null) persist()
    }
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var connections: Int get() = p.getInt("connections", 8); set(v) = p.edit().putInt("connections", v.coerceIn(1, 32)).apply()
    var maxActive: Int get() = p.getInt("maxActive", 3); set(v) = p.edit().putInt("maxActive", v.coerceIn(1, 6)).apply()
    var speedLimitKB: Long get() = p.getLong("speedLimitKB", 0); set(v) = p.edit().putLong("speedLimitKB", v.coerceAtLeast(0)).apply()
    var wifiOnly: Boolean get() = p.getBoolean("wifiOnly", false); set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var askBeforeDownload: Boolean get() = p.getBoolean("ask", true); set(v) = p.edit().putBoolean("ask", v).apply()
    var categoryFolders: Boolean get() = p.getBoolean("categoryFolders", true); set(v) = p.edit().putBoolean("categoryFolders", v).apply()
    var scheduleEnabled: Boolean get() = p.getBoolean("scheduleEnabled", false); set(v) = p.edit().putBoolean("scheduleEnabled", v).apply()
    var scheduleMinutes: Int get() = p.getInt("scheduleMinutes", 2 * 60); set(v) = p.edit().putInt("scheduleMinutes", v.coerceIn(0, 24 * 60 - 1)).apply()
    var blockThirdPartyCookies: Boolean get() = p.getBoolean("block3p", true); set(v) = p.edit().putBoolean("block3p", v).apply()
    var homePage: String get() = p.getString("home", "https://duckduckgo.com/") ?: "https://duckduckgo.com/"; set(v) = p.edit().putString("home", v).apply()
    var searchEngine: String get() = p.getString("searchEngine", "duckduckgo") ?: "duckduckgo"; set(v) = p.edit().putString("searchEngine", v).apply()
    var notifyFinished: Boolean get() = p.getBoolean("notifyFinished", true); set(v) = p.edit().putBoolean("notifyFinished", v).apply()
    var notifyFailed: Boolean get() = p.getBoolean("notifyFailed", true); set(v) = p.edit().putBoolean("notifyFailed", v).apply()
    /** A folder chosen with the system picker (SD card, USB drive…); null = the phone's Downloads folder. */
    var saveTree: String? get() = p.getString("saveTree", null); set(v) = p.edit().putString("saveTree", v).apply()
    var saveTreeName: String get() = p.getString("saveTreeName", "") ?: ""; set(v) = p.edit().putString("saveTreeName", v).apply()
    /** Torrents stay off until the user turns them on (peers and trackers can see the phone's IP address). */
    var torrentsEnabled: Boolean get() = p.getBoolean("torrents", false); set(v) = p.edit().putBoolean("torrents", v).apply()
}

/** The browser's bookmarks (bookmarks.txt in the app's private folder). */
class BookmarkStore(context: Context) {
    private val file = File(context.filesDir, "bookmarks.txt")

    fun load(): List<com.htorrent.mobile.core.Bookmarks.Bookmark> =
        runCatching { com.htorrent.mobile.core.Bookmarks.parse(file.readText()) }.getOrDefault(emptyList())

    fun save(list: List<com.htorrent.mobile.core.Bookmarks.Bookmark>) {
        runCatching { file.writeAtomically(com.htorrent.mobile.core.Bookmarks.format(list)) }
    }
}

/** Finished files go to the phone's public Downloads folder (Download/HTorrent/<type>), visible in the Files app. */
object Publisher {
    data class Saved(val uri: Uri, val name: String, val path: String)

    /** Into the folder chosen in Settings (SD card, USB…) when it is still available, else into Downloads. */
    fun publish(context: Context, source: File, fileName: String, mime: String?, categoryFolders: Boolean, tree: String?): Saved {
        val treeUri = tree?.let { Uri.parse(it) }
        val allowed = treeUri != null && context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        return if (allowed) publishToTree(context, treeUri!!, source, fileName, mime, categoryFolders)
        else publish(context, source, fileName, mime, categoryFolders)
    }

    private fun mimeOf(fileName: String, mime: String?): String =
        mime?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Saves into a folder the user picked with the system file picker (works for SD cards and USB drives). */
    private fun publishToTree(context: Context, tree: Uri, source: File, fileName: String, mime: String?, categoryFolders: Boolean): Saved {
        val resolver = context.contentResolver
        var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var path = Downloads.settings.saveTreeName.ifEmpty { "Chosen folder" }
        if (categoryFolders) FileCategory.of(fileName).folder?.let { sub ->
            dir = childFolder(resolver, tree, dir, sub)
            path += "/$sub"
        }
        val doc = DocumentsContract.createDocument(resolver, dir, mimeOf(fileName, mime), fileName)
            ?: throw IllegalStateException("The chosen folder is not available. Choose it again in Settings.")
        try {
            resolver.openOutputStream(doc, "w")!!.use { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        var name = fileName
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: fileName
        }
        source.delete()
        return Saved(doc, name, "$path/$name")
    }

    private fun childFolder(resolver: android.content.ContentResolver, tree: Uri, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext())
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: parent
    }

    fun publish(context: Context, source: File, fileName: String, mime: String?, categoryFolders: Boolean): Saved {
        val folder = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append("/HTorrent")
            if (categoryFolders) FileCategory.of(fileName).folder?.let { append('/').append(it) }
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            mime?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("The Downloads folder is not available")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        var name = fileName
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: fileName
        }
        source.delete()
        return Saved(uri, name, "$folder/$name")
    }

    fun delete(context: Context, uri: String) {
        val u = Uri.parse(uri)
        runCatching {
            if (DocumentsContract.isDocumentUri(context, u)) DocumentsContract.deleteDocument(context.contentResolver, u)
            else context.contentResolver.delete(u, null, null)
        }
    }
}
