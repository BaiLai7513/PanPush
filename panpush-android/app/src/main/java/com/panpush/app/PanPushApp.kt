package com.panpush.app

import android.app.Application
import com.panpush.app.core.AppContainer

class PanPushApp : Application() {

    /** 全局单例容器（HTTP 客户端 / 配置 / 凭据仓库 / 引擎） */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
