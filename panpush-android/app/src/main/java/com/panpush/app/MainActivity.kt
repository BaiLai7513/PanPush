package com.panpush.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import com.panpush.app.ui.MainViewModel
import com.panpush.app.ui.PanPushRoot
import com.panpush.app.ui.theme.PanPushTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            PanPushTheme {
                PanPushRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 处理「分享 -> PanPush」进来的文本 */
    private fun handleShareIntent(intent: Intent?) {
        val i = intent ?: return
        if (i.action == Intent.ACTION_SEND && i.type == "text/plain") {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.takeSharedText(it) }
        }
    }
}
