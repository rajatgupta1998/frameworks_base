/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.keyvalue;

import android.annotation.Nullable;
import android.app.backup.BackupManager;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.pm.PackageInfo;
import android.util.EventLog;
import android.util.Slog;

import com.android.server.EventLogTags;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.remote.RemoteResult;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Reports events that happen during a key-value backup task to:
 *
 * <ul>
 *   <li>Logcat (main and event buffers).
 *   <li>Backup observer (see {@link IBackupObserver}).
 *   <li>Backup manager monitor (see {@link IBackupManagerMonitor}).
 * </ul>
 */
// TODO: In KeyValueBackupTaskTest, remove direct assertions on logcat, observer or monitor and
//       verify calls to this object. Add these and more assertions to the test of this class.
class KeyValueBackupReporter {
    private static final String TAG = "KeyValueBackupTask";
    private static final boolean DEBUG = BackupManagerService.DEBUG;
    private static final boolean MORE_DEBUG = BackupManagerService.MORE_DEBUG || true;

    private final BackupManagerService mBackupManagerService;
    private final IBackupObserver mObserver;
    @Nullable private IBackupManagerMonitor mMonitor;

    KeyValueBackupReporter(
            BackupManagerService backupManagerService,
            IBackupObserver observer,
            IBackupManagerMonitor monitor) {
        mBackupManagerService = backupManagerService;
        mObserver = observer;
        mMonitor = monitor;
    }

    /** Returns the monitor or {@code null} if we lost connection to it. */
    @Nullable
    IBackupManagerMonitor getMonitor() {
        return mMonitor;
    }

    void onSkipBackup() {
        if (DEBUG) {
            Slog.d(TAG, "Skipping backup since one is already in progress");
        }
    }

    void onEmptyQueueAtStart() {
        Slog.w(TAG, "Backup begun with an empty queue, nothing to do");
    }

    void onPmFoundInQueue() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "PM metadata in queue, removing");
        }
    }

    void onQueueReady(List<BackupRequest> queue) {
        if (DEBUG) {
            Slog.v(TAG, "Beginning backup of " + queue.size() + " targets");
        }
    }

    void onTransportReady(String transportName) {
        EventLog.writeEvent(EventLogTags.BACKUP_START, transportName);
    }

    void onInitializeTransport(String transportName) {
        Slog.i(TAG, "Initializing transport and resetting backup state");
    }

    void onTransportInitialized(int status) {
        if (status == BackupTransport.TRANSPORT_OK) {
            EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
        } else {
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
            Slog.e(TAG, "Transport error in initializeDevice()");
        }
    }

    void onInitializeTransportError(Exception e) {
        Slog.e(TAG, "Error during initialization", e);
    }

    void onSkipPm() {
        Slog.d(TAG, "Skipping backup of PM metadata");
    }

    void onInvokePmAgentError(Exception e) {
        Slog.e(TAG, "Error during PM metadata backup", e);
    }

    void onEmptyQueue() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Queue now empty");
        }
    }

    void onStartPackageBackup(String packageName) {
        Slog.d(TAG, "Starting key-value backup of " + packageName);
    }

    void onPackageNotEligibleForBackup(String packageName) {
        Slog.i(TAG, "Package " + packageName + " no longer supports backup, skipping");
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    void onPackageEligibleForFullBackup(String packageName) {
        Slog.i(
                TAG,
                "Package " + packageName + " performs full-backup rather than key-value, skipping");
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    void onPackageStopped(String packageName) {
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    void onBindAgentError(SecurityException e) {
        Slog.d(TAG, "Error in bind/backup", e);
    }

    void onAgentUnknown(String packageName) {
        Slog.d(TAG, "Package does not exist, skipping");
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_PACKAGE_NOT_FOUND);
    }

    void onAgentError(String packageName) {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Agent failure for " + packageName + ", re-staging");
        }
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_AGENT_FAILURE);
    }

    void onInvokeAgent(String packageName) {
        if (DEBUG) {
            Slog.d(TAG, "Invoking agent on " + packageName);
        }
    }

    void onAgentFilesReady(File backupDataFile) {
        if (MORE_DEBUG) {
            Slog.d(TAG, "Data file: " + backupDataFile);
        }
    }

    void onRestoreconFailed(File backupDataFile) {
        Slog.e(TAG, "SELinux restorecon failed on " + backupDataFile);
    }

    void onCallAgentDoBackupError(String packageName, boolean callingAgent, Exception e) {
        if (callingAgent) {
            Slog.e(TAG, "Error invoking agent on " + packageName + ": " + e);
        } else {
            Slog.e(TAG, "Error before invoking agent on " + packageName + ": " + e);
        }
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName, e.toString());
    }

    void onFailAgentError(String packageName) {
        Slog.w(TAG, "Error conveying failure to " + packageName);
    }

    void onAgentIllegalKey(PackageInfo packageInfo, String key) {
        String packageName = packageInfo.packageName;
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName, "bad key");
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_ILLEGAL_KEY,
                        packageInfo,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        BackupManagerMonitorUtils.putMonitoringExtra(
                                null, BackupManagerMonitor.EXTRA_LOG_ILLEGAL_KEY, key));
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_AGENT_FAILURE);
        if (MORE_DEBUG) {
            Slog.i(
                    TAG,
                    "Agent failure for " + packageName + " with illegal key " + key + ", dropped");
        }
    }

    void onReadAgentDataError(String packageName, IOException e) {
        Slog.w(TAG, "Unable read backup data for " + packageName + ": " + e);
    }

    void onWriteWidgetDataError(String packageName, IOException e) {
        Slog.w(TAG, "Unable to save widget data for " + packageName + ": " + e);
    }

    void onDigestError(NoSuchAlgorithmException e) {
        Slog.e(TAG, "Unable to use SHA-1!");
    }

    void onWriteWidgetData(boolean priorStateExists, @Nullable byte[] widgetState) {
        if (MORE_DEBUG) {
            Slog.i(
                    TAG,
                    "Checking widget update: state="
                            + (widgetState != null)
                            + " prior="
                            + priorStateExists);
        }
    }

    void onTruncateDataError() {
        Slog.w(TAG, "Unable to roll back");
    }

    void onSendDataToTransport(String packageName) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Sending non-empty data to transport for " + packageName);
        }
    }

    void onNonIncrementalAndNonIncrementalRequired() {
        Slog.e(TAG, "Transport requested non-incremental but already the case");
    }

    void onEmptyData(PackageInfo packageInfo) {
        if (MORE_DEBUG) {
            Slog.i(TAG, "No backup data written, not calling transport");
        }
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_NO_DATA_TO_SEND,
                        packageInfo,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        null);
    }

    void onPackageBackupComplete(String packageName, long size) {
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.SUCCESS);
        EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE, packageName, size);
        mBackupManagerService.logBackupComplete(packageName);
    }

    void onPackageBackupRejected(String packageName) {
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        EventLogTags.writeBackupAgentFailure(packageName, "Transport rejected");
    }

    void onPackageBackupQuotaExceeded(String packageName) {
        if (MORE_DEBUG) {
            Slog.d(TAG, "Package " + packageName + " hit quota limit on key-value backup");
        }
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
        EventLog.writeEvent(EventLogTags.BACKUP_QUOTA_EXCEEDED, packageName);
    }

    void onAgentDoQuotaExceededError(Exception e) {
        Slog.e(TAG, "Unable to notify about quota exceeded: " + e);
    }

    void onPackageBackupNonIncrementalRequired(PackageInfo packageInfo) {
        Slog.i(TAG, "Transport lost data, retrying package");
        BackupManagerMonitorUtils.monitorEvent(
                mMonitor,
                BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
                packageInfo,
                BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT,
                /* extras */ null);
    }

    void onPackageBackupTransportFailure(String packageName) {
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_TRANSPORT_ABORTED);
        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, packageName);
    }

    void onPackageBackupError(String packageName, Exception e) {
        Slog.e(TAG, "Transport error backing up " + packageName, e);
        BackupObserverUtils.sendBackupOnPackageResult(
                mObserver, packageName, BackupManager.ERROR_TRANSPORT_ABORTED);
        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, packageName);
    }

    void onCloseFileDescriptorError(String logName) {
        Slog.w(TAG, "Error closing " + logName + " file-descriptor");
    }

    void onCancel() {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Cancel received");
        }
    }

    void onAgentTimedOut(@Nullable PackageInfo packageInfo) {
        String packageName = getPackageName(packageInfo);
        Slog.i(TAG, "Agent " + packageName + " timed out");
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName);
        // Time-out used to be implemented as cancel w/ cancelAll = false.
        // TODO: Change monitoring event to reflect time-out as an event itself.
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL,
                        packageInfo,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                        BackupManagerMonitorUtils.putMonitoringExtra(
                                null, BackupManagerMonitor.EXTRA_LOG_CANCEL_ALL, false));
    }

    void onAgentCancelled(@Nullable PackageInfo packageInfo) {
        String packageName = getPackageName(packageInfo);
        Slog.i(TAG, "Cancel backing up " + packageName);
        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName);
        mMonitor =
                BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL,
                        packageInfo,
                        BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT,
                        BackupManagerMonitorUtils.putMonitoringExtra(
                                null, BackupManagerMonitor.EXTRA_LOG_CANCEL_ALL, true));
    }

    private String getPackageName(@Nullable PackageInfo packageInfo) {
        return (packageInfo != null) ? packageInfo.packageName : "no_package_yet";
    }

    void onRevertBackup() {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Reverting backup queue, re-staging everything");
        }
    }

    void onTransportRequestBackupTimeError(Exception e) {
        Slog.w(TAG, "Unable to contact transport for recommended backoff: " + e);
    }

    void onRemoteCallReturned(RemoteResult result) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Agent call returned " + result);
        }
    }

    void onJournalDeleteFailed(DataChangedJournal journal) {
        Slog.e(TAG, "Unable to remove backup journal file " + journal);
    }

    void onSetCurrentTokenError(Exception e) {
        Slog.e(TAG, "Transport threw reporting restore set: " + e);
    }

    void onTransportNotInitialized() {
        if (MORE_DEBUG) {
            Slog.d(TAG, "Transport requires initialization, rerunning");
        }
    }

    void onPendingInitializeTransportError(Exception e) {
        Slog.w(TAG, "Failed to query transport name for pending init: " + e);
    }

    void onBackupFinished(int status) {
        BackupObserverUtils.sendBackupFinished(mObserver, status);
    }

    void onStartFullBackup(List<String> pendingFullBackups) {
        Slog.d(TAG, "Starting full backups for: " + pendingFullBackups);
    }

    void onKeyValueBackupFinished() {
        Slog.i(TAG, "K/V backup pass finished");
    }
}
