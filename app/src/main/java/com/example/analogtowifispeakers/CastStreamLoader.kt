package com.example.analogtowifispeakers

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient

object CastStreamLoader {

    private const val TAG = "CastStreamLoader"

    fun loadLiveHls(
        context: Context,
        url: String,
        title: String = "Analog to WiFi Speakers"
    ): Boolean {

        Toast.makeText(context, "Cast loadLiveHls() gestart", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "loadLiveHls() called with url=$url")

        val castSession: CastSession? = try {
            CastContext.getSharedInstance(context)
                .sessionManager
                .currentCastSession
        } catch (e: Exception) {
            Log.e(TAG, "CastContext error", e)
            null
        }

        if (castSession == null) {
            Toast.makeText(context, "Cast session = null", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Cast session is null")
            return false
        }

        val remoteClient = castSession.remoteMediaClient
        if (remoteClient == null) {
            Toast.makeText(context, "remoteMediaClient = null", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "remoteMediaClient is null")
            return false
        }

        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val mediaStatus = remoteClient.mediaStatus
                val playerState = mediaStatus?.playerState
                val idleReason = mediaStatus?.idleReason
                val position = remoteClient.approximateStreamPosition
                Log.d(
                    TAG,
                    "onStatusUpdated playerState=$playerState idleReason=$idleReason positionMs=$position"
                )
            }

            override fun onMetadataUpdated() {
                Log.d(TAG, "onMetadataUpdated")
            }

            override fun onQueueStatusUpdated() {
                Log.d(TAG, "onQueueStatusUpdated")
            }

            override fun onPreloadStatusUpdated() {
                Log.d(TAG, "onPreloadStatusUpdated")
            }

            override fun onSendingRemoteMediaRequest() {
                Log.d(TAG, "onSendingRemoteMediaRequest")
            }

            override fun onAdBreakStatusUpdated() {
                Log.d(TAG, "onAdBreakStatusUpdated")
            }
        }

        try {
            remoteClient.unregisterCallback(callback)
        } catch (_: Throwable) {
        }
        remoteClient.registerCallback(callback)

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(0L)
            .build()

        return try {
            remoteClient.load(request)
            Toast.makeText(context, "Cast HLS LOAD verstuurd", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "remoteClient.load(request) sent to HLS url=$url")
            true
        } catch (t: Throwable) {
            Toast.makeText(context, "Cast HLS LOAD fout", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "remoteClient.load failed", t)
            false
        }
    }

    fun stop(context: Context): Boolean {
        val castSession: CastSession? = try {
            CastContext.getSharedInstance(context)
                .sessionManager
                .currentCastSession
        } catch (e: Exception) {
            Log.e(TAG, "No Cast session in stop()", e)
            null
        }

        val session = castSession ?: return false
        val remoteClient = session.remoteMediaClient ?: return false

        return try {
            remoteClient.stop()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "remoteClient.stop failed", t)
            false
        }
    }
}