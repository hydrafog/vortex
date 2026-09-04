package com.vortex.a3.service

import kotlinx.coroutines.launch

internal fun VortexStack.forwardHandoff() {
    scope.launch {
        VortexService.handoffBus.collect { ev ->
            VortexService.currentHandoff = if (ev.url.isEmpty()) null else ev
            val server = gattServer ?: return@collect
            val json = ev.toJsonBytes()
            for (peer in peerStore.list()) {
                server.sendHandoffEncrypted(peer.peerStaticPub, json)
            }
        }
    }
}
