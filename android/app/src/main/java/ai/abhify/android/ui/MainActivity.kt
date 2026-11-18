package ai.abhify.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var micGranted: (() -> Unit)? = null
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) micGranted?.invoke() else vm.voiceError.value = "Microphone permission was not given."
        micGranted = null
    }

    /** "Export all": the user picks where the JSON goes (Storage Access Framework). Keys are never in it. */
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val json = vm.exportJson()
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                .onSuccess { android.widget.Toast.makeText(this@MainActivity, "Exported", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(this@MainActivity, "Export failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    private fun exportAll() = exporter.launch("abhify-export.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()                 // the system splash with the Abhify mark (Theme.Abhify.Splash)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val coldStart = savedInstanceState == null
        setContent {
            // v4.3: the branded launch screen, once per cold start (not after rotation or when returning to the app)
            var splash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(coldStart) }
            AbhifyTheme(vm.theme.value) {
              // v4.4 in-app text size, on top of the phone's own font size (sp text only; layouts in dp are unchanged)
              val dens = androidx.compose.ui.platform.LocalDensity.current
              androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                  androidx.compose.ui.unit.Density(dens.density, dens.fontScale * vm.textSize.value.scale)) {
                BrandBackground {
                    Box(Modifier.safeDrawingPadding()) { Router(vm, ::askNotifications, ::withMic, ::exportAll); StudioOverlays(vm); VoiceOverlay(vm); AgentOverlay(vm); BriefingSheet(vm) }
                    androidx.compose.runtime.LaunchedEffect(Unit) { if (intent?.getBooleanExtra(BriefingWorker.EXTRA, false) == true) vm.openBriefing(); handleReminder(intent) }
                }
                androidx.compose.animation.AnimatedVisibility(splash, enter = androidx.compose.animation.EnterTransition.None,
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reducedMotion()) 0 else SplashTiming.FADE_OUT))) {   // 6.1: respects "Remove animations"
                    // 4.5: the app's own theme (light or dark), so the reveal hands over to the app without a colour flash
                    BrandSplash { splash = false } }
              }
            }
        }
    }

    override fun onResume() { super.onResume(); vm.refreshDevice(); vm.refreshPending() }

    /** 5.6: a to-do reminder's Snooze (1 hour) or tap (opens Home → Today). */
    private fun handleReminder(i: android.content.Intent?) {
        val snooze = i?.getLongExtra(TaskReminders.EXTRA_SNOOZE, -1L) ?: -1L
        val open = i?.getLongExtra(TaskReminders.EXTRA_OPEN, -1L) ?: -1L
        if (snooze >= 0) { val at = TaskReminders.snooze(this, snooze)
            runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(TaskReminders.notificationId(snooze)) }
            vm.studio.message.value = "Reminder snoozed until %02d:%02d.".format(at.hour, at.minute); i?.removeExtra(TaskReminders.EXTRA_SNOOZE) }
        if (open >= 0 || snooze >= 0) { vm.tab.value = AppViewModel.Tab.HOME; i?.removeExtra(TaskReminders.EXTRA_OPEN) }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); handleReminder(intent) }

    private fun askNotifications() { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

    private fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { micGranted = action; mic.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

@Composable
private fun Router(vm: AppViewModel, askNotifications: () -> Unit, withMic: (() -> Unit) -> Unit, exportAll: () -> Unit) {
    vm.appUnsupported()?.let { Unsupported(it); return }
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    when (vm.route) {
        AppViewModel.Route.WELCOME -> WelcomeScreen(vm)
        AppViewModel.Route.PROFILE -> ProfileScreen(vm)
        AppViewModel.Route.LOGIN -> LoginScreen(vm)
        AppViewModel.Route.REGISTER -> RegisterScreen(vm)
        AppViewModel.Route.FORGOT -> ForgotScreen(vm)
        AppViewModel.Route.LINK -> LinkScreen(vm)
        AppViewModel.Route.HOME -> HomeScreen(vm, withMic)
        AppViewModel.Route.HISTORY -> HistoryScreen(vm, exportAll)
        AppViewModel.Route.PROVIDERS -> ProvidersScreen(vm)
        AppViewModel.Route.MEMORIES -> PersonalMemoryScreen(vm)
        AppViewModel.Route.MODELS -> ModelsScreen(vm, askNotifications)
        AppViewModel.Route.ACCOUNT -> AccountScreen(vm)
        AppViewModel.Route.SYNC -> SyncScreen(vm)
        AppViewModel.Route.PRIVACY -> PrivacyScreen(vm)
        AppViewModel.Route.SETTINGS -> SettingsScreen(vm)
        AppViewModel.Route.ABOUT -> AboutScreen(vm)
        AppViewModel.Route.SETUP -> SetupScreen(vm)
        AppViewModel.Route.RESUME -> ResumeScreen(vm)
        AppViewModel.Route.DRIVE -> DriveScreen(vm)
        AppViewModel.Route.STORAGE -> StorageScreen(vm)
        AppViewModel.Route.BACKUP -> BackupScreen(vm)
        AppViewModel.Route.SEMANTIC -> SemanticSearchScreen(vm)
        AppViewModel.Route.SEARCH -> UnifiedSearchScreen(vm)
        AppViewModel.Route.CONNECTION -> ConnectionScreen(vm)
        AppViewModel.Route.TASKS -> TasksScreen(vm)
        AppViewModel.Route.PROJECTS -> ProjectsScreen(vm)
    }
}

@Composable
private fun Unsupported(reason: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandLockup(96); Spacer(Modifier.height(16.dp))
        Text("This phone can't run Abhify's on-device AI", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp)); Text(reason, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp)); Text("Nothing was downloaded.", color = LocalExtra.current.muted)
    }
}
