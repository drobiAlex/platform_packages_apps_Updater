package co.copperhead.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TriggerUpdateReceiver extends BroadcastReceiver {

    static final String DOWNLOAD_UPDATE_ACTION = "co.copperhead.action.DOWNLOAD_UPDATE";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (DOWNLOAD_UPDATE_ACTION.equals(intent.getAction())) {
            PeriodicJob.scheduleDownload(context, intent.getStringExtra("update_path"));
        }
    }
}
