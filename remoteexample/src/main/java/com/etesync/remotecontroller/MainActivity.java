package com.etesync.remotecontroller;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.etesync.syncadapter.IEteSyncService;
import com.etesync.syncadapter.remote.Journal;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String JOURNAL_TYPE = "CALENDAR";


    private IEteSyncService mEteSyncService;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mEteSyncService = IEteSyncService.Stub.asInterface(iBinder);

            try {
                boolean isAllowed = mEteSyncService.hasPermission(JOURNAL_TYPE);
                if (!isAllowed) {
                    mEteSyncService.requestPermission(JOURNAL_TYPE);
                } else {
                    Journal[] journals = mEteSyncService.getJournals(JOURNAL_TYPE);
                    if (journals == null) {
                        Log.i(TAG, "Received no collection infos");
                    } else {
                        for (Journal journal : journals) {
                            Log.i(TAG, "Received collection info: " + journal.id);
                            Log.i(TAG, "Size: " + mEteSyncService.getJournalEntries(journal.id, null));
                        }
                    }
                }

                Log.i(TAG, JOURNAL_TYPE + " isAllowed:" + mEteSyncService.hasPermission(JOURNAL_TYPE) +
                        "\n other is allowed:" + mEteSyncService.hasPermission("other"));
            } catch (RemoteException aE) {
                aE.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mEteSyncService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = new Intent("com.etesync.syncadapter.RemoteService");
        intent.setPackage("com.etesync.syncadapter");
        startService(intent);
        bindService(intent, mServiceConnection, 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
    }
}
