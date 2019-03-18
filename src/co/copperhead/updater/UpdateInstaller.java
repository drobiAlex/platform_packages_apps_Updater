package co.copperhead.updater;

import static co.copperhead.updater.PeriodicJob.UPDATE_PATH;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngineCallback;
import android.util.Log;

class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";

    static final boolean IS_AB_UPDATE = SystemProperties.getBoolean("ro.build.ab_update", false);

    private Context mContext;
    private UpdateCallback mCallback;
    private UpdateEngine mUpdateEngine;

    boolean mFinalizing = false;
    private boolean mBound;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "onStatusUpdate: " + status + ", " + percent * 100 + "%");
                mCallback.onProgress(Math.round(percent * 100));

                switch (status) {
                    case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                        mFinalizing = true;
                    }
                    case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: {
                        mCallback.onComplete();
                    }
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "onPayloadApplicationComplete success");
                    //annoyUser();
                } else {
                    Log.d(TAG, "onPayloadApplicationComplete: " + errorCode);
                }
                UPDATE_PATH.delete();
            }
    };

    public UpdateInstaller(Context context) {
        mContext = context;
        mUpdateEngine = new UpdateEngine();
    }

    void setCallback(UpdateCallback callback) {
        mCallback = callback;
    }

    void applyUpdate() {
        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipFile zipFile = new ZipFile(UPDATE_PATH);
            offset = getZipEntryOffset(zipFile, "payload.bin");
            ZipEntry payloadPropEntry = zipFile.getEntry("payload_properties.txt");
            try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr)) {
                List<String> lines = new ArrayList<>();
                for (String line; (line = br.readLine()) != null;) {
                    lines.add(line);
                }
                headerKeyValuePairs = new String[lines.size()];
                headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
            }
            zipFile.close();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + UPDATE_PATH, e);
            return;
        }
        
        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback);
            if (!mBound) {
                Log.e(TAG, "Could not bind to update engine");
                return;
            }
        }

        String fileUri = "file://" + UPDATE_PATH.getAbsolutePath();
        mUpdateEngine.applyPayload(fileUri, offset, 0, headerKeyValuePairs);
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile   input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }

    interface UpdateCallback {
        void onProgress(int progress);

        void onComplete();
    }
}