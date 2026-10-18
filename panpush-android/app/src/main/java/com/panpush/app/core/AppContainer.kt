package com.panpush.app.core

import android.content.Context
import com.panpush.app.cred.AliyunCredStore
import com.panpush.app.cred.CredentialExtractor
import com.panpush.app.cred.XunleiCredStore
import com.panpush.app.push.GopeedClient

/** 极简依赖容器（不引入 DI 框架） */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val settings = Settings(appContext)
    val extractor = CredentialExtractor(appContext)

    /** 迅雷三件套凭据（root 直读 / 手动落盘） */
    val xunleiCreds = XunleiCredStore(appContext)

    /** 阿里云盘 token 凭据（root 直读 APP 明文 DB / 手动落盘） */
    val aliyunCreds = AliyunCredStore(appContext)

    /**
     * Gopeed 客户端。并发数按站点取（见 Settings.defaultConnections）：
     * 迅雷 16 / 阿里 64 / 其余 512，除非用户在设置里显式指定。
     */
    fun gopeed(site: SiteId? = null): GopeedClient = GopeedClient(
        base = settings.gopeedUrl,
        token = settings.gopeedToken.ifBlank { null },
        connections = settings.connectionsFor(site ?: SiteId.UNKNOWN)
    )
}
