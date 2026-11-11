package com.simone.jarvismobile.ui.guardian

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.guardian.GuardianCheck
import com.simone.jarvismobile.core.guardian.GuardianState
import com.simone.jarvismobile.guardian.DeviceGuardian
import com.simone.jarvismobile.guardian.GuardianFileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** G2 is an honest, on-demand local audit, NOT an always-on firewall or antivirus. */
@Composable
fun GuardianScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val device = remember(context) { DeviceGuardian(context.applicationContext) }
    val hasher = remember(context) { GuardianFileHasher(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<GuardianCheck>>(emptyList()) }
    var checking by remember { mutableStateOf(true) }
    var fileResult by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }

    LaunchedEffect(device) {
        checks = withContext(Dispatchers.Default) { runCatching { device.snapshot() }.getOrDefault(emptyList()) }
        checking = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            analyzing = true
            fileResult = "Calcolo SHA-256 locale in corso..."
            try {
                val result = hasher.sha256(uri)
                fileResult = "Byte letti: " + result.bytes + "\nSHA-256: " + result.sha256 +
                    "\nUn hash non garantisce che il file sia sicuro."
            } catch (e: Exception) {
                fileResult = if (e is IllegalArgumentException) {
                    "File oltre il limite di 128 MiB: non analizzato."
                } else {
                    "File non analizzato: autorizzazione o lettura non disponibile."
                }
            } finally {
                analyzing = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("JARVIS GUARDIAN", style = MaterialTheme.typography.headlineSmall)
        Text(
            "G2 iniziale: controlli locali realmente osservabili. Non è ancora un antivirus, " +
                "firewall o sistema di difesa continua.", style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "STATO GENERALE: NON VERIFICATO. Nessun punteggio globale senza verifiche.",
            color = Color(0xFFF0BC62), style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
        Button(
            onClick = {
                scope.launch {
                    checking = true
                    checks = withContext(Dispatchers.Default) {
                        runCatching { device.snapshot() }.getOrDefault(emptyList())
                    }
                    checking = false
                }
            },
            enabled = !checking, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) "Verifica in corso..." else "Aggiorna controlli Android") }
        if (!checking && checks.isEmpty()) Text("Controlli NON VERIFICATI: lettura non disponibile.")
        checks.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (item.state) {
                            GuardianState.PROTECTED -> "PROTETTO — solo questo controllo"
                            GuardianState.ATTENTION -> "ATTENZIONE"
                            GuardianState.UNVERIFIED -> "NON VERIFICATO"
                            GuardianState.UNAVAILABLE -> "NON DISPONIBILE"
                        },
                        color = when (item.state) {
                            GuardianState.PROTECTED -> Color(0xFF58C995)
                            GuardianState.ATTENTION -> Color(0xFFF0BC62)
                            else -> Color(0xFF9AAEBB)
                        },
                    )
                    Text(item.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: SecurityException) {
                    // OS denied: no bypass.
                }
            }, modifier = Modifier.fillMaxWidth(),
        ) { Text("Apri impostazioni di sicurezza Android") }

        HorizontalDivider()
        Text("File Security — scelta esplicita", style = MaterialTheme.typography.titleMedium)
        Text(
            "Calcola soltanto l'impronta SHA-256 di un file scelto da te (max 128 MiB). " +
                "Niente upload, lettura di altre app o scansione antimalware.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !analyzing, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (analyzing) "Lettura in corso..." else "Scegli file e calcola SHA-256") }
        if (fileResult.isNotBlank()) Text(fileResult, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Rete e privacy — future milestone", style = MaterialTheme.typography.titleMedium)
        Text("Firewall DNS: NON DISPONIBILE — G3")
        Text("VPN WireGuard: NON DISPONIBILE — G4")
        Text("Tor Browser: NON DISPONIBILE — G5")
        Text(
            "JARVIS Guardian non modifica impostazioni, permessi, connessioni o protezioni Android. " +
                "Non controlla tutte le app installate e non verifica da solo firmware o bootloader.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
