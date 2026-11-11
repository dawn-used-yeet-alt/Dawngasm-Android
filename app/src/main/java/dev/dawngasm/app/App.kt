package dev.dawngasm.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dawngasm.app.data.AppPreferences
import dev.dawngasm.app.platform.SettingsPlatformActions

/**
 * Android host for the shared Compose Multiplatform UI. It adapts the existing
 * [AppViewModel] and activity-result launchers to the platform contracts the
 * shared `DawngasmApp` expects.
 */
@Composable
fun AndroidDawngasmApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setDownloadTree(uri.toString())
        }
    }

    val hotaudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.refreshHotaudioClearance()
    }

    val audiochanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.refreshAudiochanClearance()
    }

    val settingsPlatform = remember(folderPicker, hotaudioLauncher, audiochanLauncher, prefs) {
        object : SettingsPlatformActions {
            override fun chooseDownloadFolder() {
                folderPicker.launch(null)
            }

            override fun openHotaudioClearance() {
                hotaudioLauncher.launch(Intent(context, HotaudioClearanceActivity::class.java))
            }

            override fun openAudiochanClearance() {
                audiochanLauncher.launch(Intent(context, AudiochanClearanceActivity::class.java))
            }

            override fun hotaudioClearanceDetail(): String? {
                val ip = prefs.hotaudioClientIp()
                val family = prefs.hotaudioIpFamily()?.uppercase()
                return when {
                    !ip.isNullOrBlank() && !family.isNullOrBlank() -> "$family: $ip"
                    !ip.isNullOrBlank() -> ip
                    !family.isNullOrBlank() -> family
                    else -> null
                }
            }
        }
    }

    DawngasmApp(
        state = state,
        progress = progress,
        messages = viewModel.messages,
        actions = viewModel,
        settingsPlatform = settingsPlatform,
        appVersion = BuildConfig.VERSION_NAME,
    )
}
