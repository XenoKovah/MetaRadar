package com.darkmentor.data.helpers

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow


fun PowerManager.screenStateFlow(context: Context): Flow<Boolean> = callbackFlow {

    trySend(isInteractive)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> trySend(true)
                Intent.ACTION_SCREEN_OFF -> trySend(false)
            }
        }
    }

    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    context.registerReceiver(receiver, filter)

    awaitClose {
        context.unregisterReceiver(receiver)
    }
}

fun PowerManager.userInteractionFlow(context: Context): Flow<Boolean> = callbackFlow {
    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    fun emitCurrentState() {
        val interactive = isInteractive && !km.isKeyguardLocked
        trySend(interactive)
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            emitCurrentState()
        }
    }

    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    context.registerReceiver(receiver, filter)

    emitCurrentState()

    awaitClose {
        context.unregisterReceiver(receiver)
    }
}