package com.iastudio.vm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            AppTheme(vm.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(vm) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Notifier.foreground = true
    }

    override fun onStop() {
        super.onStop()
        Notifier.foreground = false
        vm.saveSession()
    }
}

@Composable
fun Root(vm: AppViewModel) {
    // Un seul écran de travail (Accueil) ; Modèles et Réglages s'ouvrent depuis les icônes du haut ou le menu.
    var screen by rememberSaveable { mutableStateOf("home") }
    var browserUrl by rememberSaveable { mutableStateOf(BROWSER_HOME) }
    val keepOn = vm.busy || vm.progress.isNotEmpty()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = keepOn }
    BackHandler(enabled = screen != "home") { screen = "home" }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                "models" -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton({ screen = "home" }) { Text("← Retour") }
                    Box(Modifier.weight(1f)) { ModelsScreen(vm, onBrowser = { browserUrl = HF_HOME; screen = "web" }) }
                }
                "settings" -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton({ screen = "home" }) { Text("← Retour") }
                    Box(Modifier.weight(1f)) { SettingsScreen(vm) }
                }
                "web" -> BrowserScreen(vm, browserUrl) { screen = "home" }
                else -> HomeScreen(
                    vm, onModels = { screen = "models" }, onSettings = { screen = "settings" },
                    onBrowser = { browserUrl = BROWSER_HOME; screen = "web" },
                )
            }
        }
    }
}
