package com.flygonlc.onebattery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.flygonlc.onebattery.ui.Dashboard
import com.flygonlc.onebattery.ui.Palette
import com.flygonlc.onebattery.ui.RefreshRate
import com.flygonlc.onebattery.ui.Themes
import com.flygonlc.onebattery.ui.OneBatteryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.load(this)
        // PowerProfile parsing (design capacity) is slow; warm its cache off the main thread so the
        // dashboard's first composition doesn't pay for it.
        Thread({ BatteryReader.designCapacityMah(applicationContext) }, "design-capacity").start()
        RefreshRate.attach(this)
        enableEdgeToEdge()
        SampleWorker.schedule(this)
        MonitorService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val theme = Palette.theme
            // Light themes need dark status/nav bar icons, and the window behind Compose should match.
            LaunchedEffect(theme) {
                val transparent = Color.Transparent.toArgb()
                val style = if (theme.dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.decorView.setBackgroundColor(theme.paper.toArgb())
            }
            OneBatteryTheme { Dashboard() }
        }
    }

    override fun onDestroy() {
        RefreshRate.detach(this)
        super.onDestroy()
    }
}
