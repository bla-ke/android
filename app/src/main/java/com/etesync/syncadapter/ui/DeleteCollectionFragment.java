/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.ui;

import android.accounts.Account;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.HttpClient;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.Crypto;
import com.etesync.syncadapter.journalmanager.Exceptions;
import com.etesync.syncadapter.journalmanager.JournalManager;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.JournalEntity;

import io.requery.Persistable;
import io.requery.sql.EntityDataStore;
import okhttp3.HttpUrl;

public class DeleteCollectionFragment extends DialogFragment implements LoaderManager.LoaderCallbacks<Exception> {
    protected static final String
            ARG_ACCOUNT = "account",
            ARG_COLLECTION_INFO = "collectionInfo";

    protected Account account;
    protected CollectionInfo collectionInfo;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getLoaderManager().initLoader(0, getArguments(), this);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ProgressDialog progress = new ProgressDialog(getContext());
        progress.setTitle(R.string.delete_collection_deleting_collection);
        progress.setMessage(getString(R.string.please_wait));
        progress.setIndeterminate(true);
        progress.setCanceledOnTouchOutside(false);
        setCancelable(false);
        return progress;
    }


    @Override
    public Loader<Exception> onCreateLoader(int id, Bundle args) {
        account = args.getParcelable(ARG_ACCOUNT);
        collectionInfo = (CollectionInfo) args.getSerializable(ARG_COLLECTION_INFO);
        return new DeleteCollectionLoader(getContext(), account, collectionInfo);
    }

    @Override
    public void onLoadFinished(Loader loader, Exception exception) {
        dismissAllowingStateLoss();

        if (exception != null)
            getFragmentManager().beginTransaction()
                    .add(ExceptionInfoFragment.newInstance(exception, account), null)
                    .commitAllowingStateLoss();
        else {
            Activity activity = getActivity();
            if (activity instanceof Refreshable)
                ((Refreshable) activity).refresh();
            else if (activity instanceof EditCollectionActivity)
                activity.finish();
        }
    }

    @Override
    public void onLoaderReset(Loader loader) {
    }


    protected static class DeleteCollectionLoader extends AsyncTaskLoader<Exception> {
        final Account account;
        final CollectionInfo collectionInfo;

        public DeleteCollectionLoader(Context context, Account account, CollectionInfo collectionInfo) {
            super(context);
            this.account = account;
            this.collectionInfo = collectionInfo;
        }

        @Override
        protected void onStartLoading() {
            forceLoad();
        }

        @Override
        public Exception loadInBackground() {
            try {
                // delete collection locally
                EntityDataStore<Persistable> data = ((App) getContext().getApplicationContext()).getData();

                AccountSettings settings = new AccountSettings(getContext(), account);
                HttpUrl principal = HttpUrl.get(settings.getUri());

                JournalManager journalManager = new JournalManager(HttpClient.create(getContext(), settings), principal);
                Crypto.CryptoManager crypto = new Crypto.CryptoManager(collectionInfo.version, settings.password(), collectionInfo.uid);

                journalManager.delete(new JournalManager.Journal(crypto, collectionInfo.toJson(), collectionInfo.uid));
                JournalEntity journalEntity = JournalEntity.fetch(data, collectionInfo.getServiceEntity(data), collectionInfo.uid);
                journalEntity.setDeleted(true);
                data.update(journalEntity);

                return null;
            } catch (Exceptions.HttpException|Exceptions.IntegrityException|Exceptions.GenericCryptoException e) {
                return e;
            } catch (InvalidAccountException e) {
                return e;
            }
        }
    }


    public static class ConfirmDeleteCollectionFragment extends DialogFragment {

        public static ConfirmDeleteCollectionFragment newInstance(Account account, CollectionInfo collectionInfo) {
            ConfirmDeleteCollectionFragment frag = new ConfirmDeleteCollectionFragment();
            Bundle args = new Bundle(2);
            args.putParcelable(ARG_ACCOUNT, account);
            args.putSerializable(ARG_COLLECTION_INFO, collectionInfo);
            frag.setArguments(args);
            return frag;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            CollectionInfo collectionInfo = (CollectionInfo) getArguments().getSerializable(ARG_COLLECTION_INFO);
            String name = TextUtils.isEmpty(collectionInfo.displayName) ? collectionInfo.uid : collectionInfo.displayName;

            return new AlertDialog.Builder(getContext())
                    .setTitle(R.string.delete_collection_confirm_title)
                    .setMessage(getString(R.string.delete_collection_confirm_warning, name))
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            DialogFragment frag = new DeleteCollectionFragment();
                            frag.setArguments(getArguments());
                            frag.show(getFragmentManager(), null);
                        }
                    })
                    .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                        }
                    })
                    .create();
        }
    }

}
