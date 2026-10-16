package cn.qingji.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf

class MainActivity : ComponentActivity() {
    private var updateRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == "cn.qingji.app.CHECK_UPDATE") updateRequest++
        enableEdgeToEdge()
        setContent { QingJiTheme { QingJiApp(updateRequest = updateRequest) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "cn.qingji.app.CHECK_UPDATE") updateRequest++
    }
}
