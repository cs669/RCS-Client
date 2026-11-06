package com.android.rcsclient.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.RcsMessage
import com.android.rcsclient.network.RcsClientConfig
import com.android.rcsclient.telephony.DeviceContactItem

/**
 * Shows the real messaging state: carrier, RCS connection, default SMS role, and permissions.
 * There's no fake "IMS online" switch any more. RCS shows connected only when it really is.
 */
@Composable
fun ImsProvisioningDiagnosticsDialog(
    status: ImsRegistrationStatus,
    networkConfig: RcsClientConfig,
    onRequestDefaultSmsRole: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onSaveNetworkConfig: (RcsClientConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var localPhone by remember(networkConfig) { mutableStateOf(networkConfig.localPhoneNumber) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.NetworkCell,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Messaging status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (status.isImsRegistered) "RCS connected" else "RCS not connected",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = status.acsProvisioningState,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DiagnosticKeyValueRow("Carrier", status.carrierName)
                DiagnosticKeyValueRow("How messages are sent", status.imsTransportType)
                DiagnosticKeyValueRow(
                    "SIM subscription",
                    if (status.subscriptionId >= 0) "subId=${status.subscriptionId}" else "No active SIM"
                )
                DiagnosticKeyValueRow(
                    "Android's built-in RCS (ImsRcsManager)",
                    if (status.isHardwareImsBacked) {
                        "Accessible to this app"
                    } else {
                        "Blocked. Android only allows system or carrier apps to use it."
                    }
                )
                DiagnosticKeyValueRow(
                    "Default SMS app",
                    if (status.defaultSmsAppGranted) "Yes" else "No. Incoming texts won't reach this app."
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRequestRuntimePermissions,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Grant SMS/Contacts", fontSize = 12.sp)
                    }

                    if (!status.defaultSmsAppGranted) {
                        FilledTonalButton(
                            onClick = onRequestDefaultSmsRole,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Set Default SMS", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = localPhone,
                    onValueChange = { localPhone = it },
                    label = { Text("Your phone number") },
                    placeholder = { Text("+15551234567") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveNetworkConfig(networkConfig.copy(localPhoneNumber = localPhone.trim()))
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        }
    )
}

/**
 * Shows how a single message was sent and its delivery state.
 */
@Composable
fun MessageInfoDialog(
    message: RcsMessage,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Message info",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticKeyValueRow("Sent as", message.protocol.shortBadge)
                DiagnosticKeyValueRow("Status", message.deliveryStatus.label)
                DiagnosticKeyValueRow(
                    "From",
                    if (message.senderPhone.isNotBlank()) "${message.senderName} (${message.senderPhone})" else message.senderName
                )
                DiagnosticKeyValueRow("Encryption", "None. SMS isn't end-to-end encrypted.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Dialog for starting a conversation with a phone number, picked from contacts or typed in.
 * Only 1:1 SMS for now. Group texts need MMS, which comes next.
 */
@Composable
fun NewRcsConversationDialog(
    deviceContacts: List<DeviceContactItem>,
    onRequestPermissions: () -> Unit,
    onCreateConversation: (title: String, phone: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("New conversation", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (deviceContacts.isNotEmpty()) {
                    Text(
                        text = "Pick a contact:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        deviceContacts.take(15).forEach { contact ->
                            AssistChip(
                                onClick = {
                                    title = contact.displayName
                                    phone = contact.phoneNumber
                                },
                                label = {
                                    Text("${contact.displayName} (${contact.phoneNumber})", fontSize = 11.sp)
                                }
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Grant SMS & Contacts Permissions", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    placeholder = { Text("+15551234567") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Messages go out as SMS. RCS isn't connected yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                enabled = phone.any { it.isDigit() },
                onClick = { onCreateConversation(title.trim(), phone.trim()) }
            ) {
                Text("Start chat")
            }
        }
    )
}

@Composable
private fun DiagnosticKeyValueRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
