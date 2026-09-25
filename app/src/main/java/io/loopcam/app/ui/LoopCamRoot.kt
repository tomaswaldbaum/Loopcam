package io.loopcam.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.loopcam.app.R

private val requiredPermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun Context.hasPermissions(): Boolean = requiredPermissions
    .filter { it != Manifest.permission.POST_NOTIFICATIONS } // opcional: solo afecta la notificación
    .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun LoopCamRoot() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = context.hasPermissions() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (granted) {
            RecordingScreen()
        } else {
            PermissionScreen(onRequest = { launcher.launch(requiredPermissions) })
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.permissions_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRequest) { Text(stringResource(R.string.permissions_grant)) }
    }
}
