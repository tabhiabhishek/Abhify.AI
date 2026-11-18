package ai.abhify.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    PageBody(Modifier.imePadding().padding(vertical = Dimens.small), maxWidth = 480.dp) { content() }
}

@Composable
fun WelcomeScreen(vm: AppViewModel) = AuthColumn {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandLockup(120)
        Text("Chat, interview practice and quizzes with an AI that runs on this phone. Works offline. An online account is optional.",
            style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted, textAlign = TextAlign.Center)
    }
    // 4.5.1: the three promises, each with its icon (same facts as before, easier to scan); 4.6: the assistant's orb greets
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AiOrb(OrbState.IDLE, 56) }
    Glass(padding = 12) {
        listOf(Triple("📱", "AI on this phone", "Answers work offline once a model is downloaded"),
            Triple("🔒", "Private by default", "Chats, documents and memories stay on this phone"),
            Triple("☁", "Online is optional", "Add your own provider keys or an Abhify server later")).forEach { (g, t, d) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(g, 22, MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(12.dp))
                Column { Text(t, style = MaterialTheme.typography.titleSmall); Text(d, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    GradientButton("Start on this phone", { vm.go(AppViewModel.Route.PROFILE) }, Modifier.fillMaxWidth())
    // v4.2 local-first: sign-in/registration only when an Abhify server is set up; otherwise just an optional link
    if (vm.g.serverUrl.isNotBlank() && vm.serverEnabled.value) {
        OutlinedButton({ vm.go(AppViewModel.Route.LOGIN) }, Modifier.fillMaxWidth().height(48.dp)) { Text("Sign in to an Abhify account") }
        TextButton({ vm.go(AppViewModel.Route.REGISTER) }, Modifier.fillMaxWidth()) { Text("Create an account") }
    } else TextButton({ vm.go(AppViewModel.Route.CONNECTION) }, Modifier.fillMaxWidth()) { Text("Have an Abhify server? Connect (optional)") }
    Text("No account needed: everything stays on this phone unless you later turn on cloud sync.", style = MaterialTheme.typography.bodySmall,
        color = LocalExtra.current.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun ProfileScreen(vm: AppViewModel) = AuthColumn {
    var name by remember { mutableStateOf(vm.g.userName) }
    ScreenTitle("Your profile", { vm.back() })
    Glass {
        Text("A local profile lives only on this phone. No email, no password, no internet needed.", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodyMedium)
        Field(name, { name = it.take(40) }, "Your name (optional)")
    }
    GradientButton("Continue", { vm.createLocalProfile(name) }, Modifier.fillMaxWidth())
}

@Composable
private fun ServerField(vm: AppViewModel) {
    Field(vm.server.value, { vm.server.value = it.trim() }, "Abhify server address (https://…)", keyboard = KeyboardType.Uri)
    if (vm.server.value.isBlank()) Text("Online accounts need an Abhify server. If you don't have one yet, use the app on this phone; online features are pending deployment.",
        style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
}

@Composable
fun LoginScreen(vm: AppViewModel) = AuthColumn {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    ScreenTitle("Sign in", { vm.back() })
    Glass {
        ServerField(vm)
        Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
        Field(password, { password = it }, "Password", password = true)
        Message(vm.authError.value, true)
    }
    GradientButton("Sign in", { vm.login(email, password) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && password.isNotBlank(), busy = vm.authBusy.value)
    TextButton({ vm.go(AppViewModel.Route.FORGOT) }, Modifier.fillMaxWidth()) { Text("Forgot password?") }
    TextButton({ vm.go(AppViewModel.Route.REGISTER) }, Modifier.fillMaxWidth()) { Text("New here? Create an account") }
}

@Composable
fun RegisterScreen(vm: AppViewModel) = AuthColumn {
    var name by remember { mutableStateOf(vm.g.userName) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ScreenTitle("Create account", { vm.back() })
    Glass {
        ServerField(vm)
        Field(name, { name = it.take(60) }, "Name")
        Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
        Field(password, { password = it }, "Password", password = true)
        Field(confirm, { confirm = it }, "Confirm password", password = true)
        Text("At least 10 characters with upper- and lower-case letters and a digit. Your password is never stored on this phone.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        Message(vm.authError.value, true)
    }
    GradientButton("Create account", { vm.register(name, email, password, confirm) }, Modifier.fillMaxWidth(),
        enabled = name.isNotBlank() && email.isNotBlank() && password.isNotBlank(), busy = vm.authBusy.value)
}

@Composable
fun ForgotScreen(vm: AppViewModel) = AuthColumn {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    ScreenTitle("Reset password", { vm.back() })
    Glass {
        Text("1. Get a code", fontWeight = FontWeight.SemiBold)
        ServerField(vm)
        Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
        OutlinedButton({ vm.forgot(email) }, enabled = email.isNotBlank() && !vm.authBusy.value) { Text("Send reset code") }
    }
    Glass {
        Text("2. Enter the code from the email", fontWeight = FontWeight.SemiBold)
        Field(code, { code = it.filter(Char::isDigit).take(8) }, "8-digit code", keyboard = KeyboardType.Number)
        Field(password, { password = it }, "New password", password = true)
        GradientButton("Set new password", { vm.reset(email, code, password) }, Modifier.fillMaxWidth(), enabled = code.length == 8 && password.isNotBlank(), busy = vm.authBusy.value)
    }
    Message(vm.authError.value, true); Message(vm.authInfo.value, false)
}

/** After sign-in: show what is on this phone and ask before linking. Linking uploads nothing. */
@Composable
fun LinkScreen(vm: AppViewModel) = AuthColumn {
    val s = vm.g.cloud.session
    val p = vm.preview.value
    ScreenTitle("Link this phone", { vm.declineLink() })
    Glass {
        Text("Signed in as ${s?.email ?: "?"}", fontWeight = FontWeight.SemiBold)
        Text("This phone's profile has:", color = LocalExtra.current.muted)
        Text("• ${p?.conversations ?: 0} conversations (${p?.messages ?: 0} messages)\n• ${p?.memories ?: 0} memories\n• ${p?.preferences ?: 0} preferences")
        Text("Linking connects this profile to your account. Nothing is uploaded now: cloud sync stays off until you turn it on in Sync settings, " +
            "where you can choose what to sync. Records keep their IDs, and duplicates are merged, never overwritten.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    GradientButton("Link this profile", { vm.confirmLink() }, Modifier.fillMaxWidth())
    OutlinedButton({ vm.declineLink() }, Modifier.fillMaxWidth().height(48.dp)) { Text("Don't link (sign out)") }
}
