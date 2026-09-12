package com.vortex.a3.core.lan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class FileTransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "File transfer cancel broadcast received")
        IncomingFile.requestCancel(context)
        Toast.makeText(context, "Transfer cancelled", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "FileTransferCancel"
        const val ACTION_CANCEL_TRANSFER = "com.vortex.a3.ACTION_CANCEL_FILE_TRANSFER"
    }
}
