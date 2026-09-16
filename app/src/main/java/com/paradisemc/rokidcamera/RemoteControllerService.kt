package com.paradisemc.rokidcamera

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings

class RemoteControllerService : Service() {
    private var server: RemoteHttpServer? = null
    private val commandListener: (RemoteCommand) -> Unit = { command ->
        if (command is RemoteCommand.OpenCamera) openCameraActivity()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rokid Camera Remote")
            .setContentText("Ready for commands from your glasses")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(41, notification)
        AppState.subscribe(commandListener)
        server = RemoteHttpServer(this).also { it.start() }
    }

    private fun openCameraActivity() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            val intent = Intent(this, CameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        AppState.unsubscribe(commandListener)
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rokid camera bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object { const val CHANNEL_ID = "rokid_camera_bridge" }
}
