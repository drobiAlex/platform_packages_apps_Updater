package co.copperhead.updater;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

public class Service extends IntentService {
    private static final String TAG = "Service";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID_OLD = "updates";
    private static final String NOTIFICATION_CHANNEL_ID = "updates2";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;
    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;
    private static final File CARE_MAP_PATH = new File("/data/ota_package/care_map.txt");
    static final File UPDATE_PATH = new File("/data/ota_package/update.zip");
    private static final String PREFERENCE_CHANNEL = "channel";
    private static final String PREFERENCE_DOWNLOAD_FILE = "download_file";
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private boolean mUpdating = false;

    public Service() {
        super(TAG);
    }

    static boolean isAbUpdate() {
        return SystemProperties.getBoolean("ro.build.ab_update", false);
    }

    private URLConnection fetchData(final String path) throws IOException {
        final URL url = new URL(getString(isAbUpdate() ? R.string.url : R.string.url_legacy) + path);
        final URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        return urlConnection;
    }

    private boolean otaExists(final String path) {
        try {
            HttpURLConnection con = (HttpURLConnection) fetchData(path);
            con.setRequestMethod("HEAD");
            return (con.getResponseCode() == HttpURLConnection.HTTP_OK);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        Log.d(TAG, "onHandleIntent");

        final PowerManager pm = getSystemService(PowerManager.class);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        try {
            wakeLock.acquire();

            if (mUpdating) {
                Log.d(TAG, "updating already, returning early");
                return;
            }
            final SharedPreferences preferences = Settings.getPreferences(this);
            if (preferences.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                Log.d(TAG, "updated already, waiting for reboot");
                return;
            }
            mUpdating = true;

            final String channel = SystemProperties.get("sys.update.channel",
                    preferences.getString(PREFERENCE_CHANNEL, "stable"));

            Log.d(TAG, "fetching metadata for " + DEVICE + "-" + channel);
            InputStream input = fetchData(DEVICE + "-" + channel).getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            final String[] metadata = reader.readLine().split(" ");
            reader.close();

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (targetBuildDate <= sourceBuildDate) {
                Log.d(TAG,
                        "targetBuildDate: " + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                mUpdating = false;
                if (intent.getBooleanExtra("show_toast", false)) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(Service.this.getApplicationContext(), R.string.no_updates_found, Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }

            preferences.edit().putLong("target_build_date", targetBuildDate).apply();

            String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
            long downloaded = UPDATE_PATH.length();

            final String incrementalUpdate = DEVICE + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + "-ota_update-" + targetIncremental + ".zip";

            Log.d(TAG, "incr found - " + otaExists(incrementalUpdate));
            String updatePath = fullUpdate;
            if (otaExists(incrementalUpdate)) {
                updatePath = fullUpdate;
            }

            Intent downloadIntent = new Intent(this, TriggerUpdateReceiver.class);
            downloadIntent.setAction(TriggerUpdateReceiver.DOWNLOAD_UPDATE_ACTION);
            downloadIntent.putExtra("update_path", updatePath);

            final NotificationChannel notifChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH);
            

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            nm.createNotificationChannel(notifChannel);

            Notification nc = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                    .setContentTitle(getString(R.string.update_found_notification_title))
                    .setContentText(getString(R.string.update_found_notification_msg))
                    .setContentIntent(PendingIntent.getBroadcast(this, 101, downloadIntent, 0))
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFICATION_ID, nc);
            
        } catch (IOException e) {
            Log.e(TAG, "failed to download and install update", e);
            mUpdating = false;
            PeriodicJob.scheduleRetry(this);
        } finally {
            Log.d(TAG, "release wake locks");
            wakeLock.release();
            TriggerUpdateReceiver.completeWakefulIntent(intent);
        }
    }
}
