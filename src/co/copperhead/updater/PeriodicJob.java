package co.copperhead.updater;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;

import static co.copperhead.updater.Settings.PREFERENCE_CHANNEL;
import static co.copperhead.updater.UpdateInstaller.IS_AB_UPDATE;

import java.io.File;
import java.text.NumberFormat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RecoverySystem;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.support.v4.app.NotificationCompat;
import android.text.format.Formatter;
import android.util.Log;

import co.copperhead.updater.download.DownloadClient;
import co.copperhead.updater.download.DownloadClient.Headers;
import co.copperhead.updater.misc.StringGenerator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PeriodicJob extends JobService {
    private static final String TAG = "PeriodicJob";
    private static final int JOB_ID_PERIODIC = 1;
    private static final int JOB_ID_RETRY = 2;
    private static final int JOB_ID_DOWNLOAD_UPDATE = 3;
    private static final long INTERVAL_MILLIS = 4 * 60 * 60 * 1000;
    private static final long MIN_LATENCY_MILLIS = 4 * 60 * 1000;
    private static final int MAX_REPORT_INTERVAL_MS = 1000;

    private static final String ONGOING_NOTIFICATION_CHANNEL = "ongoing_notification_channel";

    private static final int NOTIFICATION_ID = 10;

    private static final int PENDING_REBOOT_ID = 1;

    static final File UPDATE_PATH = new File("/data/ota_package/update.zip");

    private static final String PAUSE_INTENT_ACTION = "co.copperhead.updater.action.PAUSE_DOWNLOAD";
    private static final String RESUME_INTENT_ACTION = "co.copperhead.updater.action.RESUME_DOWNLOAD";

    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotificationBuilder = null;
    private NotificationCompat.BigTextStyle mNotificationStyle = null;

    private DownloadClient mDownloadClient;
    private String mUpdatePath;
    private boolean mRunning = false;

    static void scheduleDownload(final Context context, String updatePath) {
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo jobInfo = scheduler.getPendingJob(JOB_ID_DOWNLOAD_UPDATE);
        if (jobInfo != null) {
            Log.d(TAG, "update already in progress ignoring request");
        } else {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("update_path", updatePath);
            final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
            final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_DOWNLOAD_UPDATE, serviceName)
                    .setRequiredNetworkType(networkType)
                    .setRequiresBatteryNotLow(batteryNotLow)
                    .setPersisted(true)
                    .setExtras(extras)
                    .build());
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "Periodic job schedule failed");
            }
        }
    }

    static void schedule(final Context context) {
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo jobInfo = scheduler.getPendingJob(JOB_ID_PERIODIC);
        if (jobInfo != null &&
                jobInfo.getNetworkType() == networkType &&
                jobInfo.isRequireBatteryNotLow() == batteryNotLow &&
                jobInfo.isPersisted() &&
                jobInfo.getIntervalMillis() == INTERVAL_MILLIS) {
            Log.d(TAG, "Periodic job already registered");
            return;
        }
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_PERIODIC, serviceName)
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(batteryNotLow)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MILLIS)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Periodic job schedule failed");
        }
    }

    static void scheduleRetry(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_RETRY, serviceName)
            .setRequiredNetworkType(Settings.getNetworkType(context))
            .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
            .setMinimumLatency(MIN_LATENCY_MILLIS)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Retry job schedule failed");
        }
    }

    static void cancel(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID_PERIODIC);
        scheduler.cancel(JOB_ID_RETRY);
    }

    private final String getUrl(String path) {
        return getString(IS_AB_UPDATE ? R.string.url : R.string.url_legacy) + path;
    }

    private static ZipEntry getEntry(final ZipFile zipFile, final String name) throws GeneralSecurityException {
        final ZipEntry entry = zipFile.getEntry(name);
        if (entry == null) {
            throw new GeneralSecurityException("missing zip entry: " + name);
        }
        return entry;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (mRunning) {
            return true;
        }
        mRunning = true;
        Log.d(TAG, "onStartJob id: " + params.getJobId());
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (params.getJobId() == JOB_ID_DOWNLOAD_UPDATE) {
            IntentFilter filter = new IntentFilter("co.copperhead.action.INSTALL_UPDATE");
            filter.addAction("co.copperhead.action.INSTALL_UPDATE_LEGACY");
            filter.addAction(PAUSE_INTENT_ACTION);
            filter.addAction(RESUME_INTENT_ACTION);
            registerReceiver(mReceiver, filter);
            mUpdatePath = params.getExtras().getString("update_path");
            downloadUpdate();
        } else {
            Intent intent = new Intent(this, TriggerUpdateReceiver.class);
            intent.setAction(TriggerUpdateReceiver.CHECK_UPDATE_ACTION);
            sendBroadcast(intent);
            mRunning = false;
        }
        return params.getJobId() == JOB_ID_DOWNLOAD_UPDATE;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        unregisterReceiver(mReceiver);
        mRunning = false;
        return false;
    }

    private boolean verifyPackage(File update) {
        try {
            RecoverySystem.verifyPackage(update, null, null);
            UPDATE_PATH.setReadable(true, false);
            final ZipFile zipFile = new ZipFile(UPDATE_PATH);

            final String channel = SystemProperties.get("sys.update.channel",
                    Settings.getPreferences(this).getString(PREFERENCE_CHANNEL, "stable"));

            final long targetBuildDate = Settings.getPreferences(this).getLong("target_build_date", 0);

            final ZipEntry metadata = getEntry(zipFile, "META-INF/com/android/metadata");
            final BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(metadata)));
            String device = null;
            String serialno = null;
            String type = null;
            String sourceIncremental = null;
            String sourceFingerprint = null;
            String streamingPropertyFiles[] = null;
            long timestamp = 0;
            for (String line; (line = reader.readLine()) != null;) {
                final String[] pair = line.split("=");
                if ("post-timestamp".equals(pair[0])) {
                    timestamp = Long.parseLong(pair[1]);
                } else if ("serialno".equals(pair[0])) {
                    serialno = pair[1];
                } else if ("pre-device".equals(pair[0])) {
                    device = pair[1];
                } else if ("ota-type".equals(pair[0])) {
                    type = pair[1];
                } else if ("ota-streaming-property-files".equals(pair[0])) {
                    streamingPropertyFiles = pair[1].trim().split(",");
                } else if ("pre-build-incremental".equals(pair[0])) {
                    sourceIncremental = pair[1];
                } else if ("pre-build".equals(pair[0])) {
                    sourceFingerprint = pair[1];
                }
            }
            if (timestamp != targetBuildDate) {
                throw new GeneralSecurityException("timestamp does not match server metadata");
            }
            if (!DEVICE.equals(device)) {
                throw new GeneralSecurityException("device mismatch");
            }
            if (serialno != null) {
                if ("stable".equals(channel) || "beta".equals(channel)) {
                    throw new GeneralSecurityException("serialno constraint not permitted for channel " + channel);
                }
                if (!serialno.equals(Build.getSerial())) {
                    throw new GeneralSecurityException("serialno mismatch");
                }
            }
            if ("AB".equals(type) != IS_AB_UPDATE) {
                throw new GeneralSecurityException("update type does not match device");
            }
            if (sourceIncremental != null && !sourceIncremental.equals(INCREMENTAL)) {
                throw new GeneralSecurityException("source incremental mismatch");
            }
            if (sourceFingerprint != null && !sourceFingerprint.equals(FINGERPRINT)) {
                throw new GeneralSecurityException("source fingerprint mismatch");
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error while verify the file", e);
        }
        return false;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == "co.copperhead.action.INSTALL_UPDATE") {
                UpdateInstaller installer = new UpdateInstaller(getApplicationContext());
                installer.setCallback(new UpdateInstaller.UpdateCallback() {
                    @Override
                    public void onProgress(int progress, boolean finalizing) {
                        mNotificationBuilder.setProgress(100, progress, false);
                        mNotificationBuilder.mActions.clear();
                        String percent = NumberFormat.getPercentInstance().format(progress / 100.f);
                        mNotificationStyle.setSummaryText(percent);
                        mNotificationStyle.bigText(IS_AB_UPDATE ? (finalizing ?
                                getString(R.string.prepare_zip_message)
                                    : getString(R.string.finalizing_package))
                                        : getString(R.string.preparing_ota_first_boot));
                        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                    }
                
                    @Override
                    public void onComplete() {
                        final PendingIntent reboot = PendingIntent.getBroadcast(getApplicationContext(),
                                PENDING_REBOOT_ID, new Intent(getApplicationContext(), RebootReceiver.class), 0);

                        mNotificationBuilder.setStyle(null);
                        mNotificationBuilder.setProgress(0, 0, false);
                        String text = getString(R.string.installing_update_finished);
                        mNotificationBuilder.setContentText(text);
                        mNotificationBuilder.addAction(R.drawable.ic_system_update_white_24dp,
                                getString(R.string.reboot), reboot);
                        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                    }
                    
                    @Override
                    public void onError(int errorCode) {
                        mNotificationBuilder.setStyle(null);
                        mNotificationBuilder.setProgress(0, 0, false);
                        String text = getString(R.string.update_failed_notification_msg);
                        mNotificationBuilder.setContentText(text);
                        mNotificationBuilder.setOngoing(false);
                        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                    }
                });
                installer.applyUpdate();
            } else if (intent.getAction().equals("co.copperhead.action.INSTALL_UPDATE_LEGACY")) {
                try {
                    RecoverySystem.installPackage(context, UPDATE_PATH);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (PAUSE_INTENT_ACTION.equals(intent.getAction())) {
                mDownloadClient.cancel();
                mNotificationBuilder.mActions.clear();
                String text = getString(R.string.download_paused_notification);
                mNotificationStyle.bigText(text);
                mNotificationBuilder.addAction(android.R.drawable.ic_media_play,
                        getString(R.string.resume_button),
                        getResumePendingIntent());
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setOngoing(false);
                mNotificationBuilder.setAutoCancel(false);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            } else if (RESUME_INTENT_ACTION.equals(intent.getAction())) {
                downloadUpdate();
            }
        }
    };

    private DownloadClient.DownloadCallback mDownloadCallback=new DownloadClient.DownloadCallback(){
    
        @Override
        public void onSuccess(File destination) {
            Log.d(TAG, "Download Complete!");
            mNotificationBuilder.setProgress(100, 0, true);
            mNotificationStyle.setSummaryText(null);
            mNotificationBuilder.setStyle(mNotificationStyle);
            String text = getString(R.string.verifying_download_notification);
            mNotificationStyle.bigText(text);
            mNotificationBuilder.setTicker(text);
            mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            if (verifyPackage(destination)) {
                Log.d(TAG, "Download Verified");
                if (IS_AB_UPDATE) {
                    sendBroadcast(new Intent("co.copperhead.action.INSTALL_UPDATE"));
                } else {
                    final PendingIntent reboot =
                            PendingIntent.getBroadcast(getApplicationContext(), 101,
                            new Intent("co.copperhead.action.INSTALL_UPDATE_LEGACY"), 0);
                    mNotificationBuilder.setStyle(null);
                    mNotificationBuilder.setProgress(0, 0, false);
                    text = getString(R.string.notification_text_legacy);
                    mNotificationBuilder.setContentText(text);
                    mNotificationBuilder.setTicker(text);
                    mNotificationBuilder.setOngoing(false);
                    mNotificationBuilder.setAutoCancel(true);
                    mNotificationBuilder.addAction(R.drawable.ic_restart,
                            getString(R.string.reboot), reboot);
                    mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                }
            } else {
                mNotificationBuilder.setStyle(null);
                mNotificationBuilder.setProgress(0, 0, false);
                text = getString(R.string.verification_failed_notification);
                mNotificationBuilder.setContentText(text);
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setOngoing(false);
                mNotificationBuilder.setAutoCancel(true);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            }
        }
    
        @Override
        public void onResponse(int statusCode, String url, Headers headers) {
            
        }
    
        @Override
        public void onFailure(boolean cancelled) {
            if (!cancelled) {
                mNotificationBuilder.mActions.clear();
                String text = getString(R.string.download_paused_error_notification);
                mNotificationStyle.bigText(text);
                mNotificationBuilder.addAction(android.R.drawable.ic_media_play,
                        getString(R.string.resume_button),
                        getResumePendingIntent());
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setOngoing(false);
                mNotificationBuilder.setAutoCancel(false);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            }
        }
    };

    private DownloadClient.ProgressListener mProgressListener = new DownloadClient.ProgressListener() {
        private long mLastUpdate = 0;
        private int mProgress = 0;
        @Override
        public void update(long bytesRead, long contentLength, long speed, long eta, boolean done) {
            if (contentLength <= 0) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            int progress = Math.round(bytesRead * 100 / contentLength);
            if (progress != mProgress || mLastUpdate - now > MAX_REPORT_INTERVAL_MS) {
                mProgress = progress;
                mLastUpdate = now;

                mNotificationBuilder.setProgress(100, progress, false);

                String percent = NumberFormat.getPercentInstance().format(progress / 100.f);
                mNotificationStyle.setSummaryText(percent);

                String speedString = Formatter.formatFileSize(getApplicationContext(), speed);
                CharSequence etaString = StringGenerator.formatETA(getApplicationContext(), eta * 1000);
                mNotificationStyle.bigText(getString(R.string.text_download_speed, etaString, speedString));

                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            }
        }
    };

    private void downloadUpdate() {
        try {
            mDownloadClient = new DownloadClient.Builder().setUrl(getUrl(mUpdatePath))
                    .setDestination(UPDATE_PATH).setDownloadCallback(mDownloadCallback)
                    .setProgressListener(mProgressListener).setUseDuplicateLinks(true).build();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        showProgressNotification();
        mDownloadClient.start();
    }

    private void showProgressNotification() {
        NotificationChannel channel = new NotificationChannel(ONGOING_NOTIFICATION_CHANNEL,
                getString(R.string.ongoing_channel_title), NotificationManager.IMPORTANCE_LOW);

        String text = getString(R.string.downloading_notification);
        mNotificationManager.createNotificationChannel(channel);
        mNotificationBuilder = new NotificationCompat.Builder(this, ONGOING_NOTIFICATION_CHANNEL);
        mNotificationBuilder.setSmallIcon(R.drawable.ic_system_update_white_24dp);
        mNotificationBuilder.setShowWhen(false);
        mNotificationBuilder.addAction(android.R.drawable.ic_media_pause,
                getString(R.string.pause_button),
                getPausePendingIntent());
        mNotificationStyle = new NotificationCompat.BigTextStyle();
        mNotificationStyle.bigText(text);
        mNotificationBuilder.setStyle(mNotificationStyle);
        mNotificationBuilder.setTicker(text);
        mNotificationBuilder.setOngoing(true);
        mNotificationBuilder.setAutoCancel(false);

        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
    }
    
    private PendingIntent getPausePendingIntent() {
        return PendingIntent.getBroadcast(this, 0, new Intent(PAUSE_INTENT_ACTION), 0);
    }

    private PendingIntent getResumePendingIntent() {
        return PendingIntent.getBroadcast(this, 0, new Intent(RESUME_INTENT_ACTION), 0);
    }
}
