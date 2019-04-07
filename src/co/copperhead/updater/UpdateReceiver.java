package co.copperhead.updater;

import static co.copperhead.updater.PeriodicJob.UPDATE_PATH;

import java.io.IOException;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.RecoverySystem;
import android.os.UserManager;

public class UpdateReceiver extends BroadcastReceiver {

    static final String DOWNLOAD_UPDATE_ACTION = "co.copperhead.updater.action.DOWNLOAD_UPDATE";
    static final String CANCEL_ACTION = "co.copperhead.updater.action.CANCEL";
    static final String RESUME_DOWNLOAD_ACTION = "co.copperhead.updater.action.RESUME_DOWNLOAD";
    static final String INSTALL_UPDATE_ACTION = "co.copperhead.updater.action.INSTALL_UPDATE";
    static final String INSTALL_UPDATE_LEGACY_ACTION = 
            "co.copperhead.updater.action.INSTALL_UPDATE_LEGACY";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (UserManager.get(context).isSystemUser()) {
                Settings.getPreferences(context).edit().putBoolean(
                        Settings.KEY_WAITING_FOR_REBOOT, false).apply();
                PeriodicJob.schedule(context);
            } else {
                context.getPackageManager().setApplicationEnabledSetting(context.getPackageName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            }
        } else if (DOWNLOAD_UPDATE_ACTION.equals(intent.getAction())) {
            PeriodicJob.scheduleDownload(context, intent.getStringExtra("update_path"), false);
        } else if (CANCEL_ACTION.equals(intent.getAction())) {
            PeriodicJob.cancel(context);
        } else if (RESUME_DOWNLOAD_ACTION.equals(intent.getAction())) {
            PeriodicJob.scheduleDownload(context, intent.getStringExtra("update_path"), true);
        } else if (INSTALL_UPDATE_ACTION.equals(intent.getAction())) {
            PeriodicJob.scheduleInstall(context);
        } else if (INSTALL_UPDATE_LEGACY_ACTION.equals(intent.getAction())) {
            try {
                RecoverySystem.installPackage(context, UPDATE_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
