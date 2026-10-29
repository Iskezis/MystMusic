package com.iskezis.myst.data.account

import com.iskezis.myst.util.sha256Hex

/**
 * Identidade estável da conta do YouTube usada no vínculo com a conta Myst.
 * O hash nunca expõe o cookie: serve apenas para comparar se o YouTube logado
 * é o mesmo que já estava vinculado ao perfil.
 */
object YouTubeAccount {

    fun extractSapisid(cookies: String?): String? {
        if (cookies.isNullOrBlank()) return null
        val map = cookies.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) {
                null
            } else {
                part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
        }.toMap()
        return map["SAPISID"] ?: map["__Secure-3PASISID"] ?: map["__Secure-1PASID"]
    }

    fun accountId(cookies: String): String {
        val sapisid = extractSapisid(cookies)
        return sha256Hex(sapisid ?: cookies.take(512)).take(20)
    }

    fun localUsername(accountId: String): String = "yt_$accountId"
}
