package com.rui.filesmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NAS アカウント保存。
 * パスワードは Android Keystore の AES 鍵で暗号化してから SharedPreferences に保存する。
 * 平文パスワードは端末外に出ない。
 */
object CryptoBox {
    private const val ALIAS = "filesmanager_nas_key"
    private const val TRANS = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANS)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ct
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(packedB64: String): String {
        if (packedB64.isEmpty()) return ""
        return try {
            val packed = Base64.decode(packedB64, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val ct = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANS)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

class NasStore(context: Context) {
    private val prefs = context.getSharedPreferences("filesmanager_store", Context.MODE_PRIVATE)

    fun loadConnections(): List<NasConnection> {
        val raw = prefs.getString("nas_connections", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NasConnection(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    host = o.optString("host"),
                    share = o.optString("share"),
                    username = o.optString("username"),
                    password = CryptoBox.decrypt(o.optString("password_enc")),
                    domain = o.optString("domain"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveConnections(list: List<NasConnection>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("host", c.host)
                    .put("share", c.share)
                    .put("username", c.username)
                    .put("password_enc", CryptoBox.encrypt(c.password))
                    .put("domain", c.domain)
            )
        }
        prefs.edit().putString("nas_connections", arr.toString()).apply()
    }

    fun upsert(conn: NasConnection) {
        val list = loadConnections().toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = conn else list.add(conn)
        saveConnections(list)
    }

    fun remove(id: String) {
        saveConnections(loadConnections().filter { it.id != id })
    }

    // お気に入り: "L|<mediaId>" (ローカル) / "N|<connId>|<path>" (NAS)
    fun loadFavorites(): Set<String> =
        prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(favs: Set<String>) {
        prefs.edit().putStringSet("favorites", favs).apply()
    }

    // 設定値
    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun getBool(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    // SAF で許可したフォルダ (フォルダ閲覧ブックマーク)
    fun loadFolderUris(): List<String> =
        (prefs.getString("folder_uris", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun saveFolderUris(uris: List<String>) {
        prefs.edit().putString("folder_uris", uris.joinToString("\n")).apply()
    }
}
