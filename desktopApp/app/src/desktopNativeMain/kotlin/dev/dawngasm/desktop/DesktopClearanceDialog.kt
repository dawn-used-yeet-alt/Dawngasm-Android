package dev.dawngasm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog allowing manual entry of clearance cookies and User-Agent when the
 * native webview helper is unavailable.
 */
@Composable
fun DesktopClearanceDialog(
    title: String,
    cookieLabel: String,
    initialCookie: String,
    initialUserAgent: String,
    showIpFamily: Boolean,
    initialIp: String,
    initialFamily: String,
    onDismiss: () -> Unit,
    onSave: (cookie: String, userAgent: String, ip: String?, family: String?) -> Unit,
) {
    var cookie by rememberSaveable(title) { mutableStateOf(initialCookie) }
    var userAgent by rememberSaveable(title) { mutableStateOf(initialUserAgent) }
    var ip by rememberSaveable(title) { mutableStateOf(initialIp) }
    var family by rememberSaveable(title) { mutableStateOf(initialFamily) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Open the site in a browser, complete verification, then paste the " +
                        "cookie header and browser User-Agent here.",
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text(cookieLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text("User-Agent") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                if (showIpFamily) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("Client IP (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = family,
                        onValueChange = { family = it },
                        label = { Text("IP family: ipv4, ipv6, or any") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        cookie.trim(),
                        userAgent.trim(),
                        ip.trim().ifBlank { null },
                        family.trim().ifBlank { null },
                    )
                },
                enabled = cookie.isNotBlank() && userAgent.isNotBlank(),
            ) {
                Text("Save clearance")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
