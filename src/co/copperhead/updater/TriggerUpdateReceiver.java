package co.copperhead.updater;

import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;
import android.content.Context;
import android.content.Intent;

import java.util.List;

public class TriggerUpdateReceiver extends WakefulBroadcastReceiver {

    private static final String TAG = "TriggerUpdateReceiver";

    static final String CHECK_UPDATE_ACTION = "co.copperhead.action.CHECK_FOR_UPDATE";
    static final String DOWNLOAD_UPDATE_ACTION = "co.copperhead.action.DOWNLOAD_UPDATE";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (CHECK_UPDATE_ACTION.equals(intent.getAction())) {
            startWakefulService(context, new Intent(context, Service.class));
        } else if (DOWNLOAD_UPDATE_ACTION.equals(intent.getAction())) {
            PeriodicJob.scheduleDownload(context, intent.getStringExtra("update_path"));
        }
    }
}
