package co.copperhead.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;
import android.content.Context;
import android.content.Intent;

import co.copperhead.license.LicenseCallback;
import co.copperhead.license.LicenseManager;

import java.util.List;

public class TriggerUpdateReceiver extends WakefulBroadcastReceiver {

    private static final String TAG = "TriggerUpdateReceiver";

    private static final int NOTIFICATION_ID = 101;
    private static final String NOTIFICATION_CHANNEL_ID = "license-check";

    static final String CHECK_UPDATE_ACTION = "co.copperhead.action.CHECK_FOR_UPDATE";
    static final String DOWNLOAD_UPDATE_ACTION = "co.copperhead.action.DOWNLOAD_UPDATE";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        LicenseManager lm = new LicenseManager(context.getApplicationContext());
        lm.checkLicense(new LicenseCallback() {
            @Override
            public void onResult(boolean active, List<String> reasons) {
                Log.d(TAG, "license check : " + active);
                if (active) {
                    if (CHECK_UPDATE_ACTION.equals(intent.getAction())) {
                        startWakefulService(context, new Intent(context, Service.class));
                    } else if (DOWNLOAD_UPDATE_ACTION.equals(intent.getAction())) {
                        PeriodicJob.scheduleDownload(context, intent.getStringExtra("update_path"));
                    }
                } else {
                    annoyUser(context);
                }
            }
        });
    }

    private void annoyUser(Context context) {
        Intent lc = new Intent("co.copperhead.setupwizard.LICENSE_CHECK");
        lc.putExtra("force_show_activity", true);
        lc.putExtra("finish_on_next", true);
        final PendingIntent settings = PendingIntent.getActivity(context, 3,
                lc, 0);
        final NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.license_notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
        notificationManager.notify(NOTIFICATION_ID, new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentIntent(settings)
                .setContentTitle(context.getString(R.string.license_check_failed_notif_title))
                .setContentText(context.getString(R.string.license_check_failed_notif_msg))
                .setOngoing(true)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }
}
