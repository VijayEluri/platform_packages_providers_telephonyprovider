/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.providers.telephony;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.Telephony;
import android.provider.Telephony.Carriers;
import android.support.test.InstrumentationRegistry;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;

import junit.framework.TestCase;

import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests for testing CRUD operations of TelephonyProvider.
 * Uses a MockContentResolver to get permission WRITE_APN_SETTINGS in order to test insert/delete
 * Uses TelephonyProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/TelephonyProviderTest.java \
 *                 --test-method testInsertCarriers
 */
public class TelephonyProviderTest extends TestCase {
    private static final String TAG = "TelephonyProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private TelephonyProviderTestable mTelephonyProviderTestable;

    @Mock
    private UiccController mUiccController;

    @Mock
    private IccRecords mIcRecords;

    private int notifyChangeCount;
    private int notifyChangeRestoreCount;
    private int notifyWfcCount;
    private int notifyWfcCountWithTestSubId;

    private static final String TEST_SUBID = "1";
    private static final String TEST_OPERATOR = "123456";
    private static final String TEST_MCC = "123";
    private static final String TEST_MNC = "456";
    private static final String TEST_SPN = TelephonyProviderTestable.TEST_SPN;

    // Used to test the path for URL_TELEPHONY_USING_SUBID with subid 1
    private static final Uri CONTENT_URI_WITH_SUBID = Uri.parse(
            "content://telephony/carriers/subId/" + TEST_SUBID);

    // Used to test the "restore to default"
    private static final Uri URL_RESTOREAPN_USING_SUBID = Uri.parse(
            "content://telephony/carriers/restore/subId/" + TEST_SUBID);
    // Used to test the preferred apn
    private static final Uri URL_PREFERAPN_USING_SUBID = Uri.parse(
            "content://telephony/carriers/preferapn/subId/" + TEST_SUBID);
    private static final Uri URL_WFC_ENABLED_USING_SUBID = Uri.parse(
            "content://telephony/siminfo/" + TEST_SUBID);
    private static final Uri URL_SIM_APN_LIST = Uri.parse(
        "content://telephony/carriers/sim_apn_list");

    private static final String COLUMN_APN_ID = "apn_id";

    // Constants for DPC related tests.
    private static final Uri URI_DPC = Uri.parse("content://telephony/carriers/dpc");
    private static final Uri URI_TELEPHONY = Carriers.CONTENT_URI;
    private static final Uri URI_FILTERED = Uri.parse("content://telephony/carriers/filtered");
    private static final Uri URI_ENFORCE_MANAGED= Uri.parse("content://telephony/carriers/enforce_managed");
    private static final String ENFORCED_KEY = "enforced";

    /**
     * This is used to give the TelephonyProviderTest a mocked context which takes a
     * TelephonyProvider and attaches it to the ContentResolver with telephony authority.
     * The mocked context also gives WRITE_APN_SETTINGS permissions
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;
        private TelephonyManager mTelephonyManager = mock(TelephonyManager.class);

        private final List<String> GRANTED_PERMISSIONS = Arrays.asList(
                Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.WRITE_APN_SETTINGS,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        public MockContextWithProvider(TelephonyProvider telephonyProvider) {
            mResolver = new MockContentResolver() {
                @Override
                public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                        int userHandle) {
                    notifyChangeCount++;
                    if (URL_RESTOREAPN_USING_SUBID.equals(uri)) {
                        notifyChangeRestoreCount++;
                    } else if (SubscriptionManager.WFC_ENABLED_CONTENT_URI.equals(uri)) {
                        notifyWfcCount++;
                    } else if (URL_WFC_ENABLED_USING_SUBID.equals(uri)) {
                        notifyWfcCountWithTestSubId++;
                    }
                }
            };

            // return test subId 0 for all operators
            doReturn(TEST_OPERATOR).when(mTelephonyManager).getSimOperator(anyInt());

            doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
            doReturn(TEST_OPERATOR).when(mTelephonyManager).getSimOperator();
            doReturn(mIcRecords).when(mUiccController).getIccRecords(anyInt(),
                    ArgumentMatchers.eq(UiccController.APP_FAM_3GPP));
            doReturn(TEST_SPN).when(mIcRecords).getServiceProviderName();

            // Add authority="telephony" to given telephonyProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = "telephony";

            // Add context to given telephonyProvider
            telephonyProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: telephonyProvider.getContext(): "
                    + telephonyProvider.getContext());

            // Add given telephonyProvider to mResolver with authority="telephony" so that
            // mResolver can send queries to mTelephonyProvider
            mResolver.addProvider("telephony", telephonyProvider);
            Log.d(TAG, "MockContextWithProvider: Add telephonyProvider to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            if (name.equals(Context.TELEPHONY_SERVICE)) {
                Log.d(TAG, "getSystemService: returning mock TM");
                return mTelephonyManager;
            } else {
                Log.d(TAG, "getSystemService: returning null");
                return null;
            }
        }

        @Override
        public Resources getResources() {
            Log.d(TAG, "getResources: returning null");
            return null;
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return InstrumentationRegistry.getContext().getSharedPreferences(name, mode);
        }

        // Gives permission to write to the APN table within the MockContext
        @Override
        public int checkCallingOrSelfPermission(String permission) {
            if (GRANTED_PERMISSIONS.contains(permission)) {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning PackageManager.PERMISSION_GRANTED");
                return PackageManager.PERMISSION_GRANTED;
            } else {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning PackageManager.PERMISSION_DENIED");
                return PackageManager.PERMISSION_DENIED;
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mTelephonyProviderTestable = new TelephonyProviderTestable();
        mContext = new MockContextWithProvider(mTelephonyProviderTestable);
        mContentResolver = (MockContentResolver) mContext.getContentResolver();
        replaceInstance(UiccController.class, "mInstance", null, mUiccController);
        notifyChangeCount = 0;
        notifyChangeRestoreCount = 0;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mTelephonyProviderTestable.closeDatabase();
    }

    /**
     * Test bulk inserting, querying;
     * Verify that the inserted values match the result of the query.
     */
    @Test
    @SmallTest
    public void testBulkInsertCarriers() {
        // insert 2 test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final Integer insertCurrent = 1;
        final String insertNumeric = TEST_OPERATOR;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.CURRENT, insertCurrent);
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        ContentValues contentValues2 = new ContentValues();
        final String insertApn2 = "exampleApnName2";
        final String insertName2 = "exampleName2";
        final Integer insertCurrent2 = 1;
        final String insertNumeric2 = "789123";
        contentValues2.put(Carriers.APN, insertApn2);
        contentValues2.put(Carriers.NAME, insertName2);
        contentValues2.put(Carriers.CURRENT, insertCurrent2);
        contentValues2.put(Carriers.NUMERIC, insertNumeric2);

        Log.d(TAG, "testInsertCarriers: Bulk inserting contentValues=" + contentValues
                + ", " + contentValues2);
        ContentValues[] values = new ContentValues[]{ contentValues, contentValues2 };
        int rows = mContentResolver.bulkInsert(Carriers.CONTENT_URI, values);
        assertEquals(2, rows);
        assertEquals(1, notifyChangeCount);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultCurrent = cursor.getInt(2);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        assertEquals(insertCurrent, resultCurrent);
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testInsertCarriers() {
        doSimpleTestForUri(Carriers.CONTENT_URI);
    }

    /**
     * Test migrating int-based MCC/MNCs over to Strings in the sim info table
     */
    @Test
    @SmallTest
    public void testMccMncMigration() {
        CarrierIdProviderTestable carrierIdProvider = new CarrierIdProviderTestable();
        carrierIdProvider.initializeForTesting(mContext);
        mContentResolver.addProvider(Telephony.CarrierId.All.CONTENT_URI.getAuthority(),
                carrierIdProvider);
        // Insert a few values into the carrier ID db
        List<String> mccMncs = Arrays.asList("99910", "999110", "999060", "99905");
        ContentValues[] carrierIdMccMncs = mccMncs.stream()
                .map((mccMnc) -> {
                    ContentValues cv = new ContentValues(1);
                    cv.put(Telephony.CarrierId.All.MCCMNC, mccMnc);
                    return cv;
                }).toArray(ContentValues[]::new);
        mContentResolver.bulkInsert(Telephony.CarrierId.All.CONTENT_URI, carrierIdMccMncs);

        // Populate the sim info db with int-format entries
        ContentValues[] existingSimInfoEntries = IntStream.range(0, mccMncs.size())
                .mapToObj((idx) -> {
                    int mcc = Integer.valueOf(mccMncs.get(idx).substring(0, 3));
                    int mnc = Integer.valueOf(mccMncs.get(idx).substring(3));
                    ContentValues cv = new ContentValues(4);
                    cv.put(SubscriptionManager.MCC, mcc);
                    cv.put(SubscriptionManager.MNC, mnc);
                    cv.put(SubscriptionManager.ICC_ID, String.valueOf(idx));
                    cv.put(SubscriptionManager.CARD_ID, String.valueOf(idx));
                    return cv;
        }).toArray(ContentValues[]::new);

        mContentResolver.bulkInsert(SubscriptionManager.CONTENT_URI, existingSimInfoEntries);

        // Run the upgrade helper on all the sim info entries.
        String[] proj = {SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
                SubscriptionManager.MCC, SubscriptionManager.MNC,
                SubscriptionManager.MCC_STRING, SubscriptionManager.MNC_STRING};
        try (Cursor c = mContentResolver.query(SubscriptionManager.CONTENT_URI, proj,
                null, null, null)) {
            while (c.moveToNext()) {
                TelephonyProvider.fillInMccMncStringAtCursor(mContext,
                        mTelephonyProviderTestable.getWritableDatabase(), c);
            }
        }

        // Loop through and make sure that everything got filled in correctly.
        try (Cursor c = mContentResolver.query(SubscriptionManager.CONTENT_URI, proj,
                null, null, null)) {
            while (c.moveToNext()) {
                String mcc = c.getString(c.getColumnIndexOrThrow(SubscriptionManager.MCC_STRING));
                String mnc = c.getString(c.getColumnIndexOrThrow(SubscriptionManager.MNC_STRING));
                assertTrue(mccMncs.contains(mcc + mnc));
            }
        }
    }

    /**
     * Test updating values in carriers table. Verify that when update hits a conflict using URL_ID
     * we merge the rows.
     */
    @Test
    @SmallTest
    public void testUpdateConflictingCarriers() {
        // insert 2 test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final String insertNumeric = TEST_OPERATOR;
        final String insertMcc = TEST_MCC;
        final String insertMnc = TEST_MNC;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.NUMERIC, insertNumeric);
        contentValues.put(Carriers.MCC, insertMcc);
        contentValues.put(Carriers.MNC, insertMnc);

        ContentValues contentValues2 = new ContentValues();
        final String insertName2 = "exampleName2";
        contentValues2.put(Carriers.NAME, insertName2);

        Uri row1 = mContentResolver.insert(Carriers.CONTENT_URI, contentValues);
        Uri row2 = mContentResolver.insert(Carriers.CONTENT_URI, contentValues2);

        // use URL_ID to update row2 apn so it conflicts with row1
        Log.d(TAG, "testUpdateConflictingCarriers: update row2=" + row2);
        contentValues.put(Carriers.NAME, insertName2);
        mContentResolver.update(row2, contentValues, null, null);

        // verify that only 1 APN now exists and it has the fields from row1 and row2
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.NUMERIC,
            Carriers.MCC,
            Carriers.MNC
        };
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI, testProjection, null, null,
                null);
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(insertApn, cursor.getString(0 /* APN */));
        assertEquals(insertName2, cursor.getString(1 /* NAME */));
        assertEquals(insertNumeric, cursor.getString(2 /* NUMERIC */));
        assertEquals(insertMcc, cursor.getString(3 /* MCC */));
        assertEquals(insertMnc, cursor.getString(4 /* MNC */));
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testInsertCarriersWithSubId() {
        doSimpleTestForUri(CONTENT_URI_WITH_SUBID);
    }

    private void doSimpleTestForUri(Uri uri) {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final String insertNumeric = TEST_OPERATOR;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        Log.d(TAG, "testInsertCarriers Inserting contentValues: " + contentValues);
        mContentResolver.insert(uri, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(uri, testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);

        // delete test content
        final String selectionToDelete = Carriers.NUMERIC + "=?";
        String[] selectionArgsToDelete = { insertNumeric };
        Log.d(TAG, "testInsertCarriers deleting selection: " + selectionToDelete
                + "testInsertCarriers selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(uri, selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(uri, testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    @Test
    @SmallTest
    public void testOwnedBy() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final String insertNumeric = TEST_OPERATOR;
        final Integer insertOwnedBy = Carriers.OWNED_BY_OTHERS;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.NUMERIC, insertNumeric);
        contentValues.put(Carriers.OWNED_BY, insertOwnedBy);

        Log.d(TAG, "testInsertCarriers Inserting contentValues: " + contentValues);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.OWNED_BY,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultOwnedBy = cursor.getInt(2);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        // Verify that OWNED_BY is force set to OWNED_BY_OTHERS when inserted with general uri
        assertEquals(insertOwnedBy, resultOwnedBy);

        // delete test content
        final String selectionToDelete = Carriers.NUMERIC + "=?";
        String[] selectionArgsToDelete = { insertNumeric };
        Log.d(TAG, "testInsertCarriers deleting selection: " + selectionToDelete
                + "testInsertCarriers selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(Carriers.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testSimTable() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final int insertSubId = 11;
        final String insertDisplayName = "exampleDisplayName";
        final String insertCarrierName = "exampleCarrierName";
        final String insertIccId = "exampleIccId";
        final String insertCardId = "exampleCardId";
        final int insertProfileClass = SubscriptionManager.PROFILE_CLASS_DEFAULT;
        contentValues.put(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID, insertSubId);
        contentValues.put(SubscriptionManager.DISPLAY_NAME, insertDisplayName);
        contentValues.put(SubscriptionManager.CARRIER_NAME, insertCarrierName);
        contentValues.put(SubscriptionManager.ICC_ID, insertIccId);
        contentValues.put(SubscriptionManager.CARD_ID, insertCardId);
        contentValues.put(SubscriptionManager.PROFILE_CLASS, insertProfileClass);

        Log.d(TAG, "testSimTable Inserting contentValues: " + contentValues);
        mContentResolver.insert(SubscriptionManager.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
            SubscriptionManager.CARRIER_NAME,
            SubscriptionManager.CARD_ID,
            SubscriptionManager.PROFILE_CLASS,
        };
        final String selection = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgs = { insertDisplayName };
        Log.d(TAG,"\ntestSimTable selection: " + selection
                + "\ntestSimTable selectionArgs: " + selectionArgs.toString());
        Cursor cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final int resultSubId = cursor.getInt(0);
        final String resultCarrierName = cursor.getString(1);
        final String resultCardId = cursor.getString(2);
        final int resultProfileClass = cursor.getInt(3);
        assertEquals(insertSubId, resultSubId);
        assertEquals(insertCarrierName, resultCarrierName);
        assertEquals(insertCardId, resultCardId);
        assertEquals(insertProfileClass, resultProfileClass);

        // delete test content
        final String selectionToDelete = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgsToDelete = { insertDisplayName };
        Log.d(TAG, "testSimTable deleting selection: " + selectionToDelete
                + "testSimTable selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(SubscriptionManager.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    private int parseIdFromInsertedUri(Uri uri) throws NumberFormatException {
        return (uri != null) ? Integer.parseInt(uri.getLastPathSegment()) : -1;
    }

    private int insertApnRecord(Uri uri, String apn, String name, int current, String numeric) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Carriers.APN, apn);
        contentValues.put(Carriers.NAME, name);
        contentValues.put(Carriers.CURRENT, current);
        contentValues.put(Carriers.NUMERIC, numeric);
        Uri resultUri = mContentResolver.insert(uri, contentValues);
        return parseIdFromInsertedUri(resultUri);
    }

    /**
     * Test URL_ENFORCE_MANAGED and URL_FILTERED works correctly.
     * Verify that when enforce is set true via URL_ENFORCE_MANAGED, only DPC records are returned
     * for URL_FILTERED and URL_FILTERED_ID.
     * Verify that when enforce is set false via URL_ENFORCE_MANAGED, only non-DPC records
     * are returned for URL_FILTERED and URL_FILTERED_ID.
     */
    @Test
    @SmallTest
    public void testEnforceManagedUri() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final int current = 1;
        final String numeric = TEST_OPERATOR;

        // Insert DPC record.
        final String dpcRecordApn = "exampleApnNameDPC";
        final String dpcRecordName = "exampleNameDPC";
        final int dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                current, numeric);

        // Insert non-DPC record.
        final String othersRecordApn = "exampleApnNameOTHERS";
        final String othersRecordName = "exampleNameDPOTHERS";
        final int othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                current, numeric);

        // Set enforced = false.
        ContentValues enforceManagedValue = new ContentValues();
        enforceManagedValue.put(ENFORCED_KEY, false);
        Log.d(TAG, "testEnforceManagedUri Updating enforced = false: "
                + enforceManagedValue);
        mContentResolver.update(URI_ENFORCE_MANAGED, enforceManagedValue, "", new String[]{});

        // Verify that enforced is set to false in TelephonyProvider.
        Cursor enforceCursor = mContentResolver.query(URI_ENFORCE_MANAGED,
            null, null, null, null);
        assertNotNull(enforceCursor);
        assertEquals(1, enforceCursor.getCount());
        enforceCursor.moveToFirst();
        assertEquals(0, enforceCursor.getInt(0));

        // Verify URL_FILTERED query only returns non-DPC record.
        final String[] testProjection =
        {
            Carriers._ID,
            Carriers.OWNED_BY
        };
        final String selection = Carriers.NUMERIC + "=?";
        final String[] selectionArgs = { numeric };
        final Cursor cursorNotEnforced = mContentResolver.query(URI_FILTERED,
            testProjection, selection, selectionArgs, null);
        assertNotNull(cursorNotEnforced);
        assertEquals(1, cursorNotEnforced.getCount());
        cursorNotEnforced.moveToFirst();
        assertEquals(othersRecordId, cursorNotEnforced.getInt(0));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorNotEnforced.getInt(1));

        // Verify that URL_FILTERED_ID cannot get DPC record.
        Cursor cursorNotEnforcedDpc = mContentResolver.query(Uri.withAppendedPath(URI_FILTERED,
                Integer.toString(dpcRecordId)), null, null, null, null);
        assertNotNull(cursorNotEnforcedDpc);
        assertTrue(cursorNotEnforcedDpc.getCount() == 0);
        // Verify that URL_FILTERED_ID can get non-DPC record.
        Cursor cursorNotEnforcedOthers = mContentResolver.query(Uri.withAppendedPath(URI_FILTERED,
                Integer.toString(othersRecordId)), null, null, null, null);
        assertNotNull(cursorNotEnforcedOthers);
        assertTrue(cursorNotEnforcedOthers.getCount() == 1);

        // Set enforced = true.
        enforceManagedValue.put(ENFORCED_KEY, true);
        Log.d(TAG, "testEnforceManagedUri Updating enforced = true: "
                + enforceManagedValue);
        mContentResolver.update(URI_ENFORCE_MANAGED, enforceManagedValue, "", new String[]{});

        // Verify that enforced is set to true in TelephonyProvider.
        enforceCursor = mContentResolver.query(URI_ENFORCE_MANAGED,
            null, null, null, null);
        assertNotNull(enforceCursor);
        assertEquals(1, enforceCursor.getCount());
        enforceCursor.moveToFirst();
        assertEquals(1, enforceCursor.getInt(0));

        // Verify URL_FILTERED query only returns DPC record.
        final Cursor cursorEnforced = mContentResolver.query(URI_FILTERED,
                testProjection, selection, selectionArgs, null);
        assertNotNull(cursorEnforced);
        assertEquals(1, cursorEnforced.getCount());
        cursorEnforced.moveToFirst();
        assertEquals(dpcRecordId, cursorEnforced.getInt(0));
        assertEquals(Carriers.OWNED_BY_DPC, cursorEnforced.getInt(1));

        // Verify that URL_FILTERED_ID can get DPC record.
        cursorNotEnforcedDpc = mContentResolver.query(Uri.withAppendedPath(URI_FILTERED,
                Integer.toString(dpcRecordId)), null, null, null, null);
        assertNotNull(cursorNotEnforcedDpc);
        assertTrue(cursorNotEnforcedDpc.getCount() == 1);
        // Verify that URL_FILTERED_ID cannot get non-DPC record.
        cursorNotEnforcedOthers = mContentResolver.query(Uri.withAppendedPath(URI_FILTERED,
                Integer.toString(othersRecordId)), null, null, null, null);
        assertNotNull(cursorNotEnforcedOthers);
        assertTrue(cursorNotEnforcedOthers.getCount() == 0);

        // Delete testing records.
        int numRowsDeleted = mContentResolver.delete(URI_TELEPHONY, selection, selectionArgs);
        assertEquals(1, numRowsDeleted);

        numRowsDeleted = mContentResolver.delete(
                ContentUris.withAppendedId(URI_DPC, dpcRecordId), "", null);
        assertEquals(1, numRowsDeleted);
    }

    private Cursor queryFullTestApnRecord(Uri uri, String numeric) {
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { numeric };
        final String[] testProjection =
                {
                        Carriers._ID,
                        Carriers.APN,
                        Carriers.NAME,
                        Carriers.CURRENT,
                        Carriers.OWNED_BY,
                };
        return mContentResolver.query(uri, testProjection, selection, selectionArgs, null);
    }

    @Test
    @SmallTest
    /**
     * Test URL_TELEPHONY cannot insert, query, update or delete DPC records.
     */
    public void testTelephonyUriDpcRecordAccessControl() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final int current = 1;
        final String numeric = TEST_OPERATOR;
        final String selection = Carriers.NUMERIC + "=?";
        final String[] selectionArgs = { numeric };

        // Insert DPC record.
        final String dpcRecordApn = "exampleApnNameDPC";
        final String dpcRecordName = "exampleNameDPC";
        final int dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                current, numeric);

        // Insert non-DPC record.
        final String othersRecordApn = "exampleApnNameOTHERS";
        final String othersRecordName = "exampleNameDPOTHERS";
        final int othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                current, numeric);

        // Verify URL_TELEPHONY query only returns non-DPC record.
        final Cursor cursorTelephony = queryFullTestApnRecord(URI_TELEPHONY, numeric);
        assertNotNull(cursorTelephony);
        assertEquals(1, cursorTelephony.getCount());
        cursorTelephony.moveToFirst();
        assertApnEquals(cursorTelephony, othersRecordId, othersRecordApn, othersRecordName,
                current, Carriers.OWNED_BY_OTHERS);

        // Verify URI_TELEPHONY updates only non-DPC records.
        ContentValues contentValuesOthersUpdate = new ContentValues();
        final String othersRecordUpdatedApn = "exampleApnNameOTHERSUpdated";
        final String othersRecordUpdatedName = "exampleNameOTHERSpdated";
        contentValuesOthersUpdate.put(Carriers.APN, othersRecordUpdatedApn);
        contentValuesOthersUpdate.put(Carriers.NAME, othersRecordUpdatedName);

        final int updateCount = mContentResolver.update(URI_TELEPHONY, contentValuesOthersUpdate,
                selection, selectionArgs);
        assertEquals(1, updateCount);
        final Cursor cursorNonDPCUpdate = queryFullTestApnRecord(URI_TELEPHONY, numeric);
        final Cursor cursorDPCUpdate = queryFullTestApnRecord(URI_DPC, numeric);

        // Verify that non-DPC records are updated.
        assertNotNull(cursorNonDPCUpdate);
        assertEquals(1, cursorNonDPCUpdate.getCount());
        cursorNonDPCUpdate.moveToFirst();
        assertApnEquals(cursorNonDPCUpdate, othersRecordId, othersRecordUpdatedApn,
                othersRecordUpdatedName);

        // Verify that DPC records are not updated.
        assertNotNull(cursorDPCUpdate);
        assertEquals(1, cursorDPCUpdate.getCount());
        cursorDPCUpdate.moveToFirst();
        assertApnEquals(cursorDPCUpdate, dpcRecordId, dpcRecordApn, dpcRecordName);

        // Verify URI_TELEPHONY deletes only non-DPC records.
        int numRowsDeleted = mContentResolver.delete(URI_TELEPHONY, selection, selectionArgs);
        assertEquals(1, numRowsDeleted);
        final Cursor cursorTelephonyRemaining = queryFullTestApnRecord(URI_TELEPHONY, numeric);
        assertNotNull(cursorTelephonyRemaining);
        assertEquals(0, cursorTelephonyRemaining.getCount());
        final Cursor cursorDPCDeleted = queryFullTestApnRecord(URI_DPC, numeric);
        assertNotNull(cursorDPCDeleted);
        assertEquals(1, cursorDPCDeleted.getCount());

        // Delete remaining test records.
        numRowsDeleted = mContentResolver.delete(
                ContentUris.withAppendedId(URI_DPC, dpcRecordId), "", null);
        assertEquals(1, numRowsDeleted);
    }

    /**
     * Test URL_DPC cannot insert or query non-DPC records.
     * Test URL_DPC_ID cannot update or delete non-DPC records.
     */
    @Test
    @SmallTest
    public void testDpcUri() {
        int dpcRecordId = 0, othersRecordId = 0;
        try {
            mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

            final int current = 1;
            final String numeric = TEST_OPERATOR;

            // Insert DPC record.
            final String dpcRecordApn = "exampleApnNameDPC";
            final String dpcRecordName = "exampleNameDPC";
            dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                    current, numeric);

            // Insert non-DPC record.
            final String othersRecordApn = "exampleApnNameOTHERS";
            final String othersRecordName = "exampleNameDPOTHERS";
            othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                    current, numeric);

            Log.d(TAG, "testDPCIdUri Id for inserted DPC record: " + dpcRecordId);
            Log.d(TAG, "testDPCIdUri Id for inserted non-DPC record: " + othersRecordId);

            // Verify that URI_DPC query only returns DPC records.
            final Cursor cursorDPC = queryFullTestApnRecord(URI_DPC, numeric);
            assertNotNull(cursorDPC);
            assertEquals(1, cursorDPC.getCount());
            cursorDPC.moveToFirst();
            assertApnEquals(cursorDPC, dpcRecordId, dpcRecordApn, dpcRecordName, current,
                    Carriers.OWNED_BY_DPC);

            // Verify that URI_DPC_ID updates only DPC records.
            ContentValues contentValuesDpcUpdate = new ContentValues();
            final String dpcRecordUpdatedApn = "exampleApnNameDPCUpdated";
            final String dpcRecordUpdatedName = "exampleNameDPCUpdated";
            contentValuesDpcUpdate.put(Carriers.APN, dpcRecordUpdatedApn);
            contentValuesDpcUpdate.put(Carriers.NAME, dpcRecordUpdatedName);
            final int updateCount = mContentResolver.update(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId),
                    contentValuesDpcUpdate, null, null);
            assertEquals(1, updateCount);
            final Cursor cursorNonDPCUpdate = queryFullTestApnRecord(URI_TELEPHONY, numeric);
            final Cursor cursorDPCUpdate = queryFullTestApnRecord(URI_DPC, numeric);

            // Verify that non-DPC records are not updated.
            assertNotNull(cursorNonDPCUpdate);
            assertEquals(1, cursorNonDPCUpdate.getCount());
            cursorNonDPCUpdate.moveToFirst();
            assertApnEquals(cursorNonDPCUpdate, othersRecordId, othersRecordApn, othersRecordName);

            // Verify that DPC records are updated.
            assertNotNull(cursorDPCUpdate);
            assertEquals(1, cursorDPCUpdate.getCount());
            cursorDPCUpdate.moveToFirst();
            assertApnEquals(cursorDPCUpdate, dpcRecordId, dpcRecordUpdatedApn,
                    dpcRecordUpdatedName);

            // Test URI_DPC_ID deletes only DPC records.
            int numRowsDeleted = mContentResolver.delete(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId), null, null);
            assertEquals(1, numRowsDeleted);
            numRowsDeleted = mContentResolver.delete(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId), null, null);
            assertEquals(0, numRowsDeleted);

        } finally {
            // Delete remaining test records.
            int numRowsDeleted = mContentResolver.delete(
                    ContentUris.withAppendedId(URI_TELEPHONY, othersRecordId), null, null);
            assertEquals(1, numRowsDeleted);
        }
    }

    private void assertApnEquals(Cursor cursor, Object... values) {
        assertTrue(values.length <= cursor.getColumnCount());
        for (int i = 0; i < values.length; i ++) {
            if (values[i] instanceof Integer) {
                assertEquals(values[i], cursor.getInt(i));
            } else if (values[i] instanceof String) {
                assertEquals(values[i], cursor.getString(i));
            } else {
                fail("values input type not correct");
            }
        }
    }

    /**
     * Test URL_DPC does not change database on conflict for insert and update.
     */
    @Test
    @SmallTest
    public void testDpcUriOnConflict() {
        int dpcRecordId1 = 0, dpcRecordId2 = 0;
        try {
            mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

            final int current = 1;
            final String numeric = TEST_OPERATOR;

            // Insert DPC record 1.
            final String dpcRecordApn1 = "exampleApnNameDPC";
            final String dpcRecordName = "exampleNameDPC";
            dpcRecordId1 = insertApnRecord(URI_DPC, dpcRecordApn1, dpcRecordName,
                    current, numeric);
            Log.d(TAG, "testDpcUriOnConflict Id for DPC record 1: " + dpcRecordId1);

            // Insert conflicting DPC record.
            final String dpcRecordNameConflict = "exampleNameDPCConflict";
            final int dpcRecordIdConflict = insertApnRecord(URI_DPC, dpcRecordApn1,
                    dpcRecordNameConflict, current, numeric);

            // Verity that conflicting DPC record is not inserted.
            assertEquals(-1, dpcRecordIdConflict);
            // Verify that APN 1 is not replaced or updated.
            Cursor cursorDPC1 = queryFullTestApnRecord(URI_DPC, numeric);
            assertNotNull(cursorDPC1);
            assertEquals(1, cursorDPC1.getCount());
            cursorDPC1.moveToFirst();
            assertApnEquals(cursorDPC1, dpcRecordId1, dpcRecordApn1, dpcRecordName, current,
                    Carriers.OWNED_BY_DPC);

            // Insert DPC record 2.
            final String dpcRecordApn2 = "exampleApnNameDPC2";
            dpcRecordId2 = insertApnRecord(URI_DPC, dpcRecordApn2, dpcRecordName,
                    current, numeric);
            Log.d(TAG, "testDpcUriOnConflict Id for DPC record 2: " + dpcRecordId2);

            // Update DPC record 2 to the values of DPC record 1.
            ContentValues contentValuesDpcUpdate = new ContentValues();
            contentValuesDpcUpdate.put(Carriers.APN, dpcRecordApn1);
            contentValuesDpcUpdate.put(Carriers.NAME, dpcRecordNameConflict);
            final int updateCount = mContentResolver.update(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId2),
                    contentValuesDpcUpdate, null, null);

            // Verify that database is not updated.
            assertEquals(0, updateCount);
            Cursor cursorDPC2 = queryFullTestApnRecord(URI_DPC, numeric);
            assertNotNull(cursorDPC2);
            assertEquals(2, cursorDPC2.getCount());
            cursorDPC2.moveToFirst();
            assertApnEquals(cursorDPC2, dpcRecordId1, dpcRecordApn1, dpcRecordName, current,
                    Carriers.OWNED_BY_DPC);
            cursorDPC2.moveToNext();
            assertApnEquals(cursorDPC2, dpcRecordId2, dpcRecordApn2, dpcRecordName, current,
                    Carriers.OWNED_BY_DPC);
        } finally {
            // Delete test records.
            int numRowsDeleted = mContentResolver.delete(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId1), null, null);
            assertEquals(1, numRowsDeleted);
            numRowsDeleted = mContentResolver.delete(
                    ContentUris.withAppendedId(URI_DPC, dpcRecordId2), null, null);
            assertEquals(1, numRowsDeleted);
        }
    }

    /**
     * Verify that SecurityException is thrown if URL_DPC, URL_FILTERED and
     * URL_ENFORCE_MANAGED is accessed from neither SYSTEM_UID nor PHONE_UID.
     */
    @Test
    @SmallTest
    public void testAccessUrlDpcThrowSecurityExceptionFromOtherUid() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID + 123456);

        // Test insert().
        ContentValues contentValuesDPC = new ContentValues();
        try {
            mContentResolver.insert(URI_DPC, contentValuesDPC);
            assertFalse("SecurityException should be thrown when URI_DPC is called from"
                    + " neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test query().
        try {
            mContentResolver.query(URI_DPC,
                    new String[]{}, "", new String[]{}, null);
            assertFalse("SecurityException should be thrown when URI_DPC is called from"
                    + " neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.query(URI_ENFORCE_MANAGED,
            new String[]{}, "", new String[]{}, null);
            assertFalse("SecurityException should be thrown when URI_ENFORCE_MANAGED is "
                    + "called from neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test update().
        ContentValues contentValuesDPCUpdate = new ContentValues();
        try {
            mContentResolver.update(
                    Uri.parse(URI_DPC + "/1"),
                    contentValuesDPCUpdate, "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.update(URI_ENFORCE_MANAGED, contentValuesDPCUpdate,
                    "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test delete().
        try {
            mContentResolver.delete(
                    Uri.parse(URI_DPC + "/0"), "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from neither SYSTEM_UID nor PHONE_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
    }

    /**
     * Verify that user/carrier edited/deleted APNs have priority in the EDITED field over
     * insertions which set EDITED=UNEDITED. In these cases instead of merging the APNs using the
     * new APN's value we keep the old value.
     */
    @Test
    @SmallTest
    public void testPreserveEdited() {
        preserveEditedValueInMerge(Carriers.USER_EDITED);
    }

    @Test
    @SmallTest
    public void testPreserveUserDeleted() {
        preserveDeletedValueInMerge(Carriers.USER_DELETED);
    }

    @Test
    @SmallTest
    public void testPreserveUserDeletedButPresentInXml() {
        preserveDeletedValueInMerge(Carriers.USER_DELETED_BUT_PRESENT_IN_XML);
    }

    @Test
    @SmallTest
    public void testPreserveCarrierEdited() {
        preserveEditedValueInMerge(Carriers.CARRIER_EDITED);
    }

    @Test
    @SmallTest
    public void testPreserveCarrierDeleted() {
        preserveDeletedValueInMerge(Carriers.CARRIER_DELETED);
    }

    @Test
    @SmallTest
    public void testPreserveCarrierDeletedButPresentInXml() {
        preserveDeletedValueInMerge(Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML);
    }

    private void preserveEditedValueInMerge(int value) {
        // insert user deleted APN
        String carrierName1 = "carrier1";
        String numeric1 = "123234";
        String mcc1 = "123";
        String mnc1 = "234";
        ContentValues editedValue = new ContentValues();
        editedValue.put(Carriers.NAME, carrierName1);
        editedValue.put(Carriers.NUMERIC, numeric1);
        editedValue.put(Carriers.MCC, mcc1);
        editedValue.put(Carriers.MNC, mnc1);
        editedValue.put(Carriers.EDITED_STATUS, value);
        assertNotNull(mContentResolver.insert(URI_TELEPHONY, editedValue));

        Cursor cur = mContentResolver.query(URI_TELEPHONY, null, null, null, null);
        assertEquals(1, cur.getCount());

        // insert APN that conflicts with edited APN
        String carrierName2 = "carrier2";
        ContentValues values = new ContentValues();
        values.put(Carriers.NAME, carrierName2);
        values.put(Carriers.NUMERIC, numeric1);
        values.put(Carriers.MCC, mcc1);
        values.put(Carriers.MNC, mnc1);
        values.put(Carriers.EDITED_STATUS, Carriers.UNEDITED);
        mContentResolver.insert(URI_TELEPHONY, values);

        String[] testProjection = {
            Carriers.NAME,
            Carriers.APN,
            Carriers.EDITED_STATUS,
            Carriers.TYPE,
            Carriers.PROTOCOL,
            Carriers.BEARER_BITMASK,
        };
        final int indexOfName = 0;
        final int indexOfEdited = 2;

        // Assert that the conflicting APN is merged into the existing user-edited APN, so only 1
        // APN exists in the db
        cur = mContentResolver.query(URI_TELEPHONY, testProjection, null, null, null);
        assertEquals(1, cur.getCount());
        cur.moveToFirst();
        assertEquals(carrierName2, cur.getString(indexOfName));
        assertEquals(value, cur.getInt(indexOfEdited));
    }

    private void preserveDeletedValueInMerge(int value) {
        // insert user deleted APN
        String carrierName1 = "carrier1";
        String numeric1 = "123234";
        String mcc1 = "123";
        String mnc1 = "234";
        ContentValues editedValue = new ContentValues();
        editedValue.put(Carriers.NAME, carrierName1);
        editedValue.put(Carriers.NUMERIC, numeric1);
        editedValue.put(Carriers.MCC, mcc1);
        editedValue.put(Carriers.MNC, mnc1);
        editedValue.put(Carriers.EDITED_STATUS, value);
        assertNotNull(mContentResolver.insert(URI_TELEPHONY, editedValue));

        // insert APN that conflicts with edited APN
        String carrierName2 = "carrier2";
        ContentValues values = new ContentValues();
        values.put(Carriers.NAME, carrierName2);
        values.put(Carriers.NUMERIC, numeric1);
        values.put(Carriers.MCC, mcc1);
        values.put(Carriers.MNC, mnc1);
        values.put(Carriers.EDITED_STATUS, Carriers.UNEDITED);
        mContentResolver.insert(URI_TELEPHONY, values);

        String[] testProjection = {
            Carriers.NAME,
            Carriers.APN,
            Carriers.EDITED_STATUS,
            Carriers.TYPE,
            Carriers.PROTOCOL,
            Carriers.BEARER_BITMASK,
        };
        final int indexOfEdited = 2;

        // Assert that the conflicting APN is merged into the existing user-deleted APN.
        // Entries marked deleted will not show up in queries so we verify that no APNs can
        // be seen
        Cursor cur = mContentResolver.query(URI_TELEPHONY, testProjection, null, null, null);
        assertEquals(0, cur.getCount());
    }

    /**
     * Test URL_PREFERAPN_USING_SUBID works correctly.
     */
    @Test
    @SmallTest
    public void testQueryPreferredApn() {
        // create APNs
        ContentValues preferredValues = new ContentValues();
        final String preferredApn = "preferredApn";
        final String preferredName = "preferredName";
        preferredValues.put(Carriers.APN, preferredApn);
        preferredValues.put(Carriers.NAME, preferredName);
        preferredValues.put(Carriers.NUMERIC, TEST_OPERATOR);
        ContentValues otherValues = new ContentValues();
        final String otherApn = "otherApnName";
        final String otherName = "otherName";
        otherValues.put(Carriers.APN, otherApn);
        otherValues.put(Carriers.NAME, otherName);
        otherValues.put(Carriers.NUMERIC, TEST_OPERATOR);

        // insert APNs
        // TODO if using URL_TELEPHONY, SubscriptionManager.getDefaultSubscriptionId() returns -1
        Log.d(TAG, "testQueryPreferredApn: Bulk inserting contentValues=" + preferredValues + ", "
                + otherValues);
        Uri uri = mContentResolver.insert(CONTENT_URI_WITH_SUBID, preferredValues);
        mContentResolver.insert(CONTENT_URI_WITH_SUBID, otherValues);
        final String preferredApnIdString = uri.getLastPathSegment();
        final long preferredApnId = Long.parseLong(preferredApnIdString);
        Log.d(TAG, "testQueryPreferredApn: preferredApnString=" + preferredApnIdString);

        // set preferred apn
        preferredValues.put(COLUMN_APN_ID, preferredApnIdString);
        mContentResolver.insert(URL_PREFERAPN_USING_SUBID, preferredValues);

        // query preferred APN
        final String[] testProjection = { Carriers.APN, Carriers.NAME };
        Cursor cursor = mContentResolver.query(
                URL_PREFERAPN_USING_SUBID, testProjection, null, null, null);

        // verify that preferred apn was set and retreived
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(preferredApn, cursor.getString(0));
        assertEquals(preferredName, cursor.getString(1));
    }

    /**
     *  Test that APN_SET_ID works correctly.
     */
    @Test
    @SmallTest
    public void testApnSetId() {
        // create APNs
        ContentValues values1 = new ContentValues();
        final String apn = "apnName";
        final String apnName = "name";
        values1.put(Carriers.APN, apn);
        values1.put(Carriers.NAME, apnName);
        values1.put(Carriers.NUMERIC, TEST_OPERATOR);

        ContentValues values2 = new ContentValues();
        final String otherApn = "otherApnName";
        final String otherName = "otherName";
        values2.put(Carriers.APN, otherApn);
        values2.put(Carriers.NAME, otherName);
        values2.put(Carriers.NUMERIC, TEST_OPERATOR);
        values2.put(Carriers.APN_SET_ID, 1);

        // insert APNs
        // TODO if using URL_TELEPHONY, SubscriptionManager.getDefaultSubscriptionId() returns -1
        Log.d(TAG, "testApnSetId: inserting contentValues=" + values1 + ", " + values2);
        mContentResolver.insert(CONTENT_URI_WITH_SUBID, values1);
        mContentResolver.insert(CONTENT_URI_WITH_SUBID, values2);

        // query APN with default APN_SET_ID
        final String[] testProjection = { Carriers.NAME };
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI, testProjection,
                Carriers.APN_SET_ID + "=?", new String[] { "0" }, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(apnName, cursor.getString(0));

        // query APN with APN_SET_ID=1
        cursor = mContentResolver.query(Carriers.CONTENT_URI, testProjection,
                Carriers.APN_SET_ID + "=?", new String[] { "1" }, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(otherName, cursor.getString(0));
    }

    /**
     *  Test that querying with the PREFERAPNSET url yields all APNs in the preferred set.
     */
    @Test
    @SmallTest
    public void testPreferApnSetUrl() {
        // create APNs
        ContentValues values1 = new ContentValues();
        final String apn = "apnName";
        final String apnName = "name";
        values1.put(Carriers.APN, apn);
        values1.put(Carriers.NAME, apnName);
        values1.put(Carriers.NUMERIC, TEST_OPERATOR);

        ContentValues values2 = new ContentValues();
        final String apn2 = "otherApnName";
        final String name2 = "name2";
        values2.put(Carriers.APN, apn2);
        values2.put(Carriers.NAME, name2);
        values2.put(Carriers.NUMERIC, TEST_OPERATOR);
        values2.put(Carriers.APN_SET_ID, 1);

        ContentValues values3 = new ContentValues();
        final String apn3 = "thirdApnName";
        final String name3 = "name3";
        values3.put(Carriers.APN, apn3);
        values3.put(Carriers.NAME, name3);
        values3.put(Carriers.NUMERIC, TEST_OPERATOR);
        values3.put(Carriers.APN_SET_ID, 1);

        // insert APNs
        // we explicitly include subid, as SubscriptionManager.getDefaultSubscriptionId() returns -1
        Log.d(TAG, "testPreferApnSetUrl: inserting contentValues=" + values1 + ", " + values2
                + ", " + values3);
        mContentResolver.insert(CONTENT_URI_WITH_SUBID, values1);
        mContentResolver.insert(CONTENT_URI_WITH_SUBID, values2);
        Uri uri = mContentResolver.insert(CONTENT_URI_WITH_SUBID, values3);

        // before there's a preferred APN set, assert that all APNs are returned
        final String[] testProjection = { Carriers.NAME };
        Cursor cursor = mContentResolver.query(
                Uri.withAppendedPath(Carriers.CONTENT_URI, "preferapnset/subId/" + TEST_SUBID),
                testProjection, null, null, null);
        assertEquals(3, cursor.getCount());

        // set the APN from values3 (apn_set_id = 1) to the preferred APN
        final String preferredApnIdString = uri.getLastPathSegment();
        final long preferredApnId = Long.parseLong(preferredApnIdString);
        ContentValues prefer = new ContentValues();
        prefer.put("apn_id", preferredApnId);
        int count = mContentResolver.update(URL_PREFERAPN_USING_SUBID, prefer, null, null);
        assertEquals(1, count);

        // query APN with PREFERAPNSET url
        // explicitly include SUB_ID, as SubscriptionManager.getDefaultSubscriptionId() returns -1
        cursor = mContentResolver.query(
                Uri.withAppendedPath(Carriers.CONTENT_URI, "preferapnset/subId/" + TEST_SUBID),
                testProjection, null, null, null);
        assertEquals(2, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(name2, cursor.getString(0));
        cursor.moveToNext();
        assertEquals(name3, cursor.getString(0));
    }

    /**
     * Test URL_RESTOREAPN_USING_SUBID works correctly.
     */
    @Test
    @SmallTest
    public void testRestoreDefaultApn() {
        // setup for multi-SIM
        TelephonyManager telephonyManager =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        doReturn(2).when(telephonyManager).getPhoneCount();

        // create APN to be deleted (including MVNO values)
        ContentValues targetValues = new ContentValues();
        targetValues.put(Carriers.APN, "apnName");
        targetValues.put(Carriers.NAME, "name");
        targetValues.put(Carriers.NUMERIC, TEST_OPERATOR);
        targetValues.put(Carriers.MVNO_TYPE, "spn");
        targetValues.put(Carriers.MVNO_MATCH_DATA, TelephonyProviderTestable.TEST_SPN);
        // create other operator APN (sama MCCMNC)
        ContentValues otherValues = new ContentValues();
        final String otherApn = "otherApnName";
        final String otherName = "otherName";
        final String otherMvnoTyp = "spn";
        final String otherMvnoMatchData = "testOtherOperator";
        otherValues.put(Carriers.APN, otherApn);
        otherValues.put(Carriers.NAME, otherName);
        otherValues.put(Carriers.NUMERIC, TEST_OPERATOR);
        otherValues.put(Carriers.MVNO_TYPE, otherMvnoTyp);
        otherValues.put(Carriers.MVNO_MATCH_DATA, otherMvnoMatchData);

        // insert APNs
        Log.d(TAG, "testRestoreDefaultApn: Bulk inserting contentValues=" + targetValues + ", "
                + otherValues);
        ContentValues[] values = new ContentValues[]{ targetValues, otherValues };
        mContentResolver.bulkInsert(Carriers.CONTENT_URI, values);

        // restore to default
        mContentResolver.delete(URL_RESTOREAPN_USING_SUBID, null, null);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.MVNO_TYPE,
            Carriers.MVNO_MATCH_DATA,
        };
        // verify that deleted result match results of query
        Cursor cursor = mContentResolver.query(
                Carriers.CONTENT_URI, testProjection, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(otherApn, cursor.getString(0));
        assertEquals(otherName, cursor.getString(1));
        assertEquals(otherMvnoTyp, cursor.getString(2));
        assertEquals(otherMvnoMatchData, cursor.getString(3));

        // create APN to be deleted (not include MVNO values)
        ContentValues targetValues2 = new ContentValues();
        targetValues2.put(Carriers.APN, "apnName");
        targetValues2.put(Carriers.NAME, "name");
        targetValues2.put(Carriers.NUMERIC, TEST_OPERATOR);

        // insert APN
        mContentResolver.insert(Carriers.CONTENT_URI, targetValues2);

        // restore to default
        mContentResolver.delete(URL_RESTOREAPN_USING_SUBID, null, null);

        // verify that deleted result match results of query
        cursor = mContentResolver.query(Carriers.CONTENT_URI, testProjection, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(otherApn, cursor.getString(0));
        assertEquals(otherName, cursor.getString(1));
        assertEquals(otherMvnoTyp, cursor.getString(2));
        assertEquals(otherMvnoMatchData, cursor.getString(3));

        // setup for single-SIM
        doReturn(1).when(telephonyManager).getPhoneCount();

        // restore to default
        mContentResolver.delete(URL_RESTOREAPN_USING_SUBID, null, null);

        // verify that deleted values are gone
        cursor = mContentResolver.query(
                Carriers.CONTENT_URI, testProjection, null, null, null);
        assertEquals(0, cursor.getCount());
        assertEquals(3, notifyChangeRestoreCount);
    }

    /**
     * Test changes to siminfo/WFC_IMS_ENABLED and simInfo/ENHANCED_4G
     */
    @Test
    @SmallTest
    public void testUpdateWfcEnabled() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final int insertSubId = 1;
        final String insertDisplayName = "exampleDisplayName";
        final String insertCarrierName = "exampleCarrierName";
        final String insertIccId = "exampleIccId";
        final String insertCardId = "exampleCardId";
        contentValues.put(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID, insertSubId);
        contentValues.put(SubscriptionManager.DISPLAY_NAME, insertDisplayName);
        contentValues.put(SubscriptionManager.CARRIER_NAME, insertCarrierName);
        contentValues.put(SubscriptionManager.ICC_ID, insertIccId);
        contentValues.put(SubscriptionManager.CARD_ID, insertCardId);

        Log.d(TAG, "testSimTable Inserting wfc contentValues: " + contentValues);
        mContentResolver.insert(SubscriptionManager.CONTENT_URI, contentValues);
        assertEquals(0, notifyWfcCount);

        // update wfc_enabled
        ContentValues values = new ContentValues();
        values.put(SubscriptionManager.WFC_IMS_ENABLED, true);
        final String selection = SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=?";
        final String[] selectionArgs = { "" + insertSubId };
        mContentResolver.update(SubscriptionManager.CONTENT_URI, values, selection, selectionArgs);
        assertEquals(1, notifyWfcCount);
        assertEquals(0, notifyWfcCountWithTestSubId);

        // update other fields
        values = new ContentValues();
        values.put(SubscriptionManager.DISPLAY_NAME, "exampleDisplayNameNew");
        mContentResolver.update(SubscriptionManager.CONTENT_URI, values, selection, selectionArgs);
        // expect no change on wfc count
        assertEquals(1, notifyWfcCount);
        assertEquals(0, notifyWfcCountWithTestSubId);

        // update WFC using subId
        values = new ContentValues();
        values.put(SubscriptionManager.WFC_IMS_ENABLED, false);
        mContentResolver.update(SubscriptionManager.getUriForSubscriptionId(insertSubId),
                values, null, null);
        assertEquals(1, notifyWfcCount);
        assertEquals(0, notifyWfcCountWithTestSubId);
    }

    protected void replaceInstance(final Class c, final String instanceName,
            final Object obj, final Object newValue)
            throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);
        field.set(obj, newValue);
    }

    @Test
    @SmallTest
    public void testSIMAPNLIST_APNMatchTheMCCMNCAndMVNO() {
        // Test on getCurrentAPNList() step 1
        final String apnName = "apnName";
        final String carrierName = "name";
        final String numeric = TEST_OPERATOR;
        final String mvnoType = "spn";
        final String mvnoData = TEST_SPN;

        // Insert the APN and DB only have the MCC/MNC and MVNO APN
        ContentValues contentValues = new ContentValues();
        contentValues.put(Carriers.APN, apnName);
        contentValues.put(Carriers.NAME, carrierName);
        contentValues.put(Carriers.NUMERIC, numeric);
        contentValues.put(Carriers.MVNO_TYPE, mvnoType);
        contentValues.put(Carriers.MVNO_MATCH_DATA, mvnoData);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // Query DB
        final String[] testProjection =
                {
                        Carriers.APN,
                        Carriers.NAME,
                        Carriers.NUMERIC,
                        Carriers.MVNO_MATCH_DATA
                };
        Cursor cursor = mContentResolver.query(URL_SIM_APN_LIST,
                testProjection, null, null, null);

        cursor.moveToFirst();
        assertEquals(apnName, cursor.getString(0));
        assertEquals(carrierName, cursor.getString(1));
        assertEquals(numeric, cursor.getString(2));
        assertEquals(mvnoData, cursor.getString(3));
    }

    @Test
    @SmallTest
    public void testSIMAPNLIST_APNMatchTheParentMCCMNC() {
        // Test on getCurrentAPNList() step 2
        final String apnName = "apnName";
        final String carrierName = "name";
        final String numeric = TEST_OPERATOR;

        // Insert the APN and DB only have the MNO APN
        ContentValues contentValues = new ContentValues();
        contentValues.put(Carriers.APN, apnName);
        contentValues.put(Carriers.NAME, carrierName);
        contentValues.put(Carriers.NUMERIC, numeric);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // Query DB
        final String[] testProjection =
                {
                        Carriers.APN,
                        Carriers.NAME,
                        Carriers.NUMERIC,
                };
        Cursor cursor = mContentResolver.query(URL_SIM_APN_LIST,
                testProjection, null, null, null);

        cursor.moveToFirst();
        assertEquals(apnName, cursor.getString(0));
        assertEquals(carrierName, cursor.getString(1));
        assertEquals(numeric, cursor.getString(2));
    }
}
