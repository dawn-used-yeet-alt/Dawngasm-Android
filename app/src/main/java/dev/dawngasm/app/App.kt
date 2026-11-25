package dev.dawngasm.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) { AppPreferences(context) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.flushSync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingExport by remember { mutableStateOf<Pair<String, (Result<String>) -> Unit>?>(null) }
    var pendingImportCallback by remember { mutableStateOf<((Result<String>) -> Unit)?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            pending.second(Result.failure(IllegalStateException("Export cancelled")))
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(pending.first.encodeToByteArray())
            } ?: error("Could not open file for writing")
            "Encrypted backup saved"
        }
        pending.second(result)
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingImportCallback
        pendingImportCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            callback(Result.failure(IllegalStateException("Import cancelled")))
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().decodeToString()
            } ?: error("Could not read selected file")
        }
        callback(result)
    }

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

    val settingsPlatform = remember(
        folderPicker,
        hotaudioLauncher,
        audiochanLauncher,
        exportBackupLauncher,
        importBackupLauncher,
        prefs,
    ) {
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

            override fun canPickBackupFiles(): Boolean = true

            override fun saveBackupFile(
                suggestedName: String,
                content: String,
                onResult: (Result<String>) -> Unit,
            ) {
                pendingExport = content to onResult
                exportBackupLauncher.launch(suggestedName)
            }

            override fun pickBackupFile(onResult: (Result<String>) -> Unit) {
                pendingImportCallback = onResult
                importBackupLauncher.launch(arrayOf("text/plain", "*/*"))
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
