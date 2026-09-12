package com.vortex.a3.ui.components

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ThisDeviceCard(
    wifiIp: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceName = remember(context) {
        try {
            Settings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }
    val thisDeviceText = str("device.this")
    val hasDistinctName = deviceName.isNotBlank() && !deviceName.equals(thisDeviceText, ignoreCase = true)

    val asyncIp by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { currentWifiIp() }
    }
    val resolvedIp = wifiIp?.takeIf { it.isNotBlank() } ?: asyncIp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pillowCard()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            com.vortex.a3.ui.icons.SolarDuotoneIcon(
                icon = SolarIcons.Smartphone,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hasDistinctName) deviceName else thisDeviceText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FW.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (hasDistinctName) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = thisDeviceText.uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FW.SemiBold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (!resolvedIp.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = resolvedIp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

fun currentWifiIp(): String? = try {
    java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }
        ?.filter { ni ->
            val n = ni.name
            n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")
        }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.filterIsInstance<java.net.Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
} catch (_: Exception) {
    null
}
