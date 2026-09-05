package ua.starky.audiohunter

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.starky.audiohunter.ui.AppNavigation
import ua.starky.audiohunter.ui.theme.AudioHunterTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val settings = app.settings

        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = 0)
            val scope = rememberCoroutineScope()

            AudioHunterTheme(themeMode = themeMode) {
                AppNavigation(
                    themeMode = themeMode,
                    onToggleTheme = {
                        scope.launch {
                            // системная → светлая → тёмная → системная
                            settings.setThemeMode((themeMode + 1) % 3)
                        }
                    },
                )
            }
        }
    }
}
