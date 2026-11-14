package app.orchard.tv.aerialvoice;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.List;

/** Optional notification-listener bridge for current media title and playback state. */
public final class MediaSessionBridge extends NotificationListenerService {
    private static final String TAG = "AerialVoiceMedia";

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Notification listener connected");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        // The active MediaSession is queried on demand by TvContextProvider.
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { }

    public static String currentMedia(Context context) {
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return "";
            ComponentName listener = new ComponentName(context, MediaSessionBridge.class);
            List<MediaController> sessions = manager.getActiveSessions(listener);
            if (sessions == null || sessions.isEmpty()) return "";
            MediaController selected = null;
            for (MediaController controller : sessions) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    selected = controller;
                    break;
                }
                if (selected == null && controller.getMetadata() != null) selected = controller;
            }
            if (selected == null) return "";
            MediaMetadata metadata = selected.getMetadata();
            if (metadata == null) return "";
            String title = clean(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = clean(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String album = clean(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            PlaybackState state = selected.getPlaybackState();
            String playback = state == null ? "состояние неизвестно"
                    : state.getState() == PlaybackState.STATE_PLAYING ? "воспроизводится"
                    : state.getState() == PlaybackState.STATE_PAUSED ? "пауза" : "не воспроизводится";
            if (title.isEmpty()) title = album;
            if (title.isEmpty()) return "";
            StringBuilder out = new StringBuilder("Медиа: ").append(title);
            if (!artist.isEmpty()) out.append(" — ").append(artist);
            out.append(" (").append(playback).append(")");
            return out.toString();
        } catch (SecurityException e) {
            return "Медиаинформация недоступна: не выдан доступ к уведомлениям/медиасессиям.";
        } catch (Exception e) {
            Log.d(TAG, "Could not read active media session", e);
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
