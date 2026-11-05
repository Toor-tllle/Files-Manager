package com.rui.filesmanager

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * 共有フォルダの「実列挙」。サーバ直下（smb://host/）の一覧が共有フォルダそのもの。
 * smbj には列挙APIが無いので、列挙だけ jcifs-ng を使う（接続・閲覧・再生は従来通り smbj）。
 */
object ShareEnum {
    fun listShares(conn: NasConnection): List<String> {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.responseTimeout", "12000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
        }
        val ctx = BaseContext(PropertyConfiguration(props))
            .withCredentials(NtlmPasswordAuthenticator(conn.domain, conn.username, conn.password))
        val root = SmbFile("smb://${conn.host}/", ctx)
        val names = root.list() ?: return emptyList()
        return names.map { it.trimEnd('/') }
            .filter { it.isNotBlank() && !it.endsWith("$") }
            .distinct()
            .sortedBy { it.lowercase() }
    }
}
