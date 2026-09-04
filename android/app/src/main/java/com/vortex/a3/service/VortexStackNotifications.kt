package com.vortex.a3.service

import kotlinx.coroutines.launch

@Volatile private var lastHandledNotifInvokeSeq = 0L

internal fun VortexStack.handleNotifInvoke(m: com.vortex.a3.core.notif.NotificationMirror) {
    if (m.invokeIndex < 0 || m.key.isEmpty()) return
    if (m.seq > 0L) {
        synchronized(this) {
            if (m.seq <= lastHandledNotifInvokeSeq) return
            lastHandledNotifInvokeSeq = m.seq
        }
    }
    com.vortex.a3.core.media.MediaNotificationListenerService
        .invokeAction(m.key, m.invokeIndex, m.reply)
}

internal fun VortexStack.forwardNotifications() {
    scope.launch {
        VortexService.notificationBus.collect { mirror ->
            if (!com.vortex.a3.core.notif.NotificationMirrorSetting.isEnabled()) return@collect
            val json = mirror.toJsonBytes()
            for (peer in peerStore.list()) {
                val peerHex = peer.peerStaticPub.notifHex()
                val server = gattServer
                val sent = server?.sendNotificationEncrypted(peer.peerStaticPub, json) ?: false
                if (!sent) notificationOutbox.enqueue(peerHex, mirror)
            }
            val pkg = mirror.appId
            if (pkg.isNotEmpty() && !mirror.dismiss && sentIconPkgs.add(pkg)) {
                scope.launch { sendAppIcon(pkg) }
            }
        }
    }
}
