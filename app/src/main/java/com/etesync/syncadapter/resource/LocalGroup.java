/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.resource;

import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Build;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContacts.Data;
import android.text.TextUtils;

import com.etesync.syncadapter.App;

import org.apache.commons.lang3.ArrayUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import at.bitfire.vcard4android.AndroidAddressBook;
import at.bitfire.vcard4android.AndroidGroup;
import at.bitfire.vcard4android.AndroidGroupFactory;
import at.bitfire.vcard4android.BatchOperation;
import at.bitfire.vcard4android.CachedGroupMembership;
import at.bitfire.vcard4android.Contact;
import at.bitfire.vcard4android.ContactsStorageException;
import ezvcard.VCardVersion;
import lombok.Cleanup;
import lombok.Getter;
import lombok.ToString;

import static at.bitfire.vcard4android.GroupMethod.GROUP_VCARDS;

@ToString(callSuper=true)
public class LocalGroup extends AndroidGroup implements LocalResource {
    @Getter
    protected String uuid;
    /** marshalled list of member UIDs, as sent by server */
    public static final String COLUMN_PENDING_MEMBERS = Groups.SYNC3;

    public LocalGroup(AndroidAddressBook addressBook, long id, String fileName, String eTag) {
        super(addressBook, id, fileName, eTag);
    }

    public LocalGroup(AndroidAddressBook addressBook, Contact contact, String fileName, String eTag) {
        super(addressBook, contact, fileName, eTag);
    }

    @Override
    public String getContent() throws IOException, ContactsStorageException {
        final Contact contact;
        contact = getContact();

        App.log.log(Level.FINE, "Preparing upload of VCard " + getUuid(), contact);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        contact.write(VCardVersion.V4_0, GROUP_VCARDS, os);

        return os.toString();
    }

    @Override
    public boolean isLocalOnly() {
        return TextUtils.isEmpty(getETag());
    }

    @Override
    public void clearDirty(String eTag) throws ContactsStorageException {
        assertID();

        ContentValues values = new ContentValues(2);
        values.put(Groups.DIRTY, 0);
        values.put(COLUMN_ETAG, this.eTag = eTag);
        update(values);

        // update cached group memberships
        BatchOperation batch = new BatchOperation(addressBook.provider);

        // delete cached group memberships
        batch.enqueue(new BatchOperation.Operation(
                ContentProviderOperation.newDelete(addressBook.syncAdapterURI(ContactsContract.Data.CONTENT_URI))
                        .withSelection(
                                CachedGroupMembership.MIMETYPE + "=? AND " + CachedGroupMembership.GROUP_ID + "=?",
                                new String[] { CachedGroupMembership.CONTENT_ITEM_TYPE, String.valueOf(id) }
                        )
        ));

        // insert updated cached group memberships
        for (long member : getMembers())
            batch.enqueue(new BatchOperation.Operation(
                    ContentProviderOperation.newInsert(addressBook.syncAdapterURI(ContactsContract.Data.CONTENT_URI))
                            .withValue(CachedGroupMembership.MIMETYPE, CachedGroupMembership.CONTENT_ITEM_TYPE)
                            .withValue(CachedGroupMembership.RAW_CONTACT_ID, member)
                            .withValue(CachedGroupMembership.GROUP_ID, id)
                            .withYieldAllowed(true)
            ));

        batch.commit();
    }

    @Override
    public void prepareForUpload() throws ContactsStorageException {
        final String uid = UUID.randomUUID().toString();
        final String newFileName = uid;

        ContentValues values = new ContentValues(2);
        values.put(COLUMN_FILENAME, newFileName);
        values.put(COLUMN_UID, uid);
        update(values);

        fileName = newFileName;
    }

    @Override
    protected ContentValues contentValues() {
        ContentValues values = super.contentValues();

        @Cleanup("recycle") Parcel members = Parcel.obtain();
        members.writeStringList(contact.members);
        values.put(COLUMN_PENDING_MEMBERS, members.marshall());

        return values;
    }


    /**
     * Marks all members of the current group as dirty.
     */
    public void markMembersDirty() throws ContactsStorageException {
        assertID();
        BatchOperation batch = new BatchOperation(addressBook.provider);

        for (long member : getMembers())
            batch.enqueue(new BatchOperation.Operation(
                    ContentProviderOperation.newUpdate(addressBook.syncAdapterURI(ContentUris.withAppendedId(RawContacts.CONTENT_URI, member)))
                            .withValue(RawContacts.DIRTY, 1)
                            .withYieldAllowed(true)
            ));

        batch.commit();
    }

    /**
     * Processes all groups with non-null {@link #COLUMN_PENDING_MEMBERS}: the pending memberships
     * are (if possible) applied, keeping cached memberships in sync.
     * @param addressBook    address book to take groups from
     * @throws ContactsStorageException on contact provider errors
     */
    public static void applyPendingMemberships(LocalAddressBook addressBook) throws ContactsStorageException {
        try {
            @Cleanup Cursor cursor = addressBook.provider.query(
                    addressBook.syncAdapterURI(Groups.CONTENT_URI),
                    new String[] { Groups._ID, COLUMN_PENDING_MEMBERS },
                    COLUMN_PENDING_MEMBERS + " IS NOT NULL", new String[] {},
                    null
            );

            BatchOperation batch = new BatchOperation(addressBook.provider);
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                App.log.fine("Assigning members to group " + id);

                // required for workaround for Android 7 which sets DIRTY flag when only meta-data is changed
                Set<Long> changeContactIDs = new HashSet<>();

                // delete all memberships and cached memberships for this group
                for (LocalContact contact : addressBook.getByGroupMembership(id)) {
                    contact.removeGroupMemberships(batch);
                    changeContactIDs.add(contact.getId());
                }

                // extract list of member UIDs
                List<String> members = new LinkedList<>();
                byte[] raw = cursor.getBlob(1);
                @Cleanup("recycle") Parcel parcel = Parcel.obtain();
                parcel.unmarshall(raw, 0, raw.length);
                parcel.setDataPosition(0);
                parcel.readStringList(members);

                // insert memberships
                for (String uid : members) {
                    App.log.fine("Assigning member: " + uid);
                    try {
                        LocalContact member = addressBook.findContactByUID(uid);
                        member.addToGroup(batch, id);
                        changeContactIDs.add(member.getId());
                    } catch(FileNotFoundException e) {
                        App.log.log(Level.WARNING, "Group member not found: " + uid, e);
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    // workaround for Android 7 which sets DIRTY flag when only meta-data is changed
                    for (Long contactID : changeContactIDs) {
                        LocalContact contact = new LocalContact(addressBook, contactID, null, null);
                        contact.updateHashCode(batch);
                    }

                // remove pending memberships
                batch.enqueue(new BatchOperation.Operation(
                        ContentProviderOperation.newUpdate(addressBook.syncAdapterURI(ContentUris.withAppendedId(Groups.CONTENT_URI, id)))
                                .withValue(COLUMN_PENDING_MEMBERS, null)
                                .withYieldAllowed(true)
                ));

                batch.commit();
            }
        } catch(RemoteException e) {
            throw new ContactsStorageException("Couldn't get pending memberships", e);
        }
    }


    // helpers

    private void assertID() {
        if (id == null)
            throw new IllegalStateException("Group has not been saved yet");
    }

    /**
     * Lists all members of this group.
     * @return list of all members' raw contact IDs
     * @throws ContactsStorageException on contact provider errors
     */
    protected long[] getMembers() throws ContactsStorageException {
        assertID();
        List<Long> members = new LinkedList<>();
        try {
            @Cleanup Cursor cursor = addressBook.provider.query(
                    addressBook.syncAdapterURI(ContactsContract.Data.CONTENT_URI),
                    new String[] { Data.RAW_CONTACT_ID },
                    GroupMembership.MIMETYPE + "=? AND " + GroupMembership.GROUP_ROW_ID + "=?",
                    new String[] { GroupMembership.CONTENT_ITEM_TYPE, String.valueOf(id) },
                    null
            );
            while (cursor != null && cursor.moveToNext())
                members.add(cursor.getLong(0));
        } catch(RemoteException e) {
            throw new ContactsStorageException("Couldn't list group members", e);
        }
        return ArrayUtils.toPrimitive(members.toArray(new Long[members.size()]));
    }


    // factory

    static class Factory extends AndroidGroupFactory {
        static final Factory INSTANCE = new Factory();

        @Override
        public LocalGroup newInstance(AndroidAddressBook addressBook, long id, String fileName, String eTag) {
            return new LocalGroup(addressBook, id, fileName, eTag);
        }

        @Override
        public LocalGroup newInstance(AndroidAddressBook addressBook, Contact contact, String fileName, String eTag) {
            return new LocalGroup(addressBook, contact, fileName, eTag);
        }

        @Override
        public LocalGroup[] newArray(int size) {
            return new LocalGroup[size];
        }

    }

}
