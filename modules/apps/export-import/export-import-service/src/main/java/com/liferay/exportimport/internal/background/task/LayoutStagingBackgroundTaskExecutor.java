/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.exportimport.internal.background.task;

import com.liferay.exportimport.constants.ExportImportBackgroundTaskContextMapConstants;
import com.liferay.exportimport.internal.background.task.display.LayoutStagingBackgroundTaskDisplay;
import com.liferay.exportimport.kernel.lar.ExportImportHelper;
import com.liferay.exportimport.kernel.lar.ExportImportHelperUtil;
import com.liferay.exportimport.kernel.lar.ExportImportThreadLocal;
import com.liferay.exportimport.kernel.lar.ManifestSummary;
import com.liferay.exportimport.kernel.lar.MissingReferences;
import com.liferay.exportimport.kernel.lifecycle.ExportImportLifecycleConstants;
import com.liferay.exportimport.kernel.lifecycle.ExportImportLifecycleManagerUtil;
import com.liferay.exportimport.kernel.model.ExportImportConfiguration;
import com.liferay.exportimport.kernel.service.ExportImportLocalServiceUtil;
import com.liferay.exportimport.kernel.service.StagingLocalServiceUtil;
import com.liferay.portal.kernel.backgroundtask.BackgroundTask;
import com.liferay.portal.kernel.backgroundtask.BackgroundTaskConstants;
import com.liferay.portal.kernel.backgroundtask.BackgroundTaskExecutor;
import com.liferay.portal.kernel.backgroundtask.BackgroundTaskManagerUtil;
import com.liferay.portal.kernel.backgroundtask.BackgroundTaskResult;
import com.liferay.portal.kernel.backgroundtask.display.BackgroundTaskDisplay;
import com.liferay.portal.kernel.exception.NoSuchGroupException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.service.GroupLocalServiceUtil;
import com.liferay.portal.kernel.service.LayoutSetBranchLocalServiceUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.transaction.TransactionInvokerUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LongWrapper;
import com.liferay.portal.kernel.util.MapUtil;
import com.liferay.portal.kernel.util.MimeTypesUtil;
import com.liferay.portal.kernel.util.TempFileEntryUtil;
import com.liferay.portal.kernel.util.UnicodeProperties;
import com.liferay.trash.service.TrashEntryLocalServiceUtil;

import java.io.File;
import java.io.Serializable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * @author Julio Camarero
 */
public class LayoutStagingBackgroundTaskExecutor
	extends BaseStagingBackgroundTaskExecutor {

	public LayoutStagingBackgroundTaskExecutor() {
		setBackgroundTaskStatusMessageTranslator(
			new LayoutStagingBackgroundTaskStatusMessageTranslator());
	}

	@Override
	public BackgroundTaskExecutor clone() {
		LayoutStagingBackgroundTaskExecutor
			layoutStagingBackgroundTaskExecutor =
				new LayoutStagingBackgroundTaskExecutor();

		layoutStagingBackgroundTaskExecutor.
			setBackgroundTaskStatusMessageTranslator(
				getBackgroundTaskStatusMessageTranslator());
		layoutStagingBackgroundTaskExecutor.setIsolationLevel(
			getIsolationLevel());

		return layoutStagingBackgroundTaskExecutor;
	}

	@Override
	public BackgroundTaskResult execute(BackgroundTask backgroundTask)
		throws PortalException {

		ExportImportConfiguration exportImportConfiguration =
			getExportImportConfiguration(backgroundTask);

		Map<String, Serializable> settingsMap =
			exportImportConfiguration.getSettingsMap();

		long userId = MapUtil.getLong(settingsMap, "userId");
		long targetGroupId = MapUtil.getLong(settingsMap, "targetGroupId");
		long sourceGroupId = MapUtil.getLong(settingsMap, "sourceGroupId");

		clearBackgroundTaskStatus(backgroundTask);

		File file = null;
		MissingReferences missingReferences = null;

		try {
			ExportImportThreadLocal.setLayoutStagingInProcess(true);

			Group targetGroup = GroupLocalServiceUtil.fetchGroup(targetGroupId);

			if (targetGroup == null) {
				throw new NoSuchGroupException(
					"Target group does not exists with the primary key " +
						targetGroupId);
			}

			Group sourceGroup = GroupLocalServiceUtil.getGroup(sourceGroupId);

			if (sourceGroup.hasStagingGroup()) {
				Group stagingGroup = sourceGroup.getStagingGroup();

				if (stagingGroup.getGroupId() == targetGroupId) {
					ExportImportThreadLocal.setInitialLayoutStagingInProcess(
						true);

					TrashEntryLocalServiceUtil.deleteEntries(
						sourceGroupId, true);
				}
			}

			ExportImportLifecycleManagerUtil.fireExportImportLifecycleEvent(
				ExportImportLifecycleConstants.
					EVENT_PUBLICATION_LAYOUT_LOCAL_STARTED,
				ExportImportLifecycleConstants.
					PROCESS_FLAG_LAYOUT_STAGING_IN_PROCESS,
				String.valueOf(
					exportImportConfiguration.getExportImportConfigurationId()),
				exportImportConfiguration);

			boolean privateLayout = MapUtil.getBoolean(
				settingsMap, "privateLayout");

			initThreadLocals(sourceGroupId, privateLayout);

			LayoutStagingCallable layoutStagingCallable =
				new LayoutStagingCallable(
					backgroundTask.getBackgroundTaskId(),
					exportImportConfiguration, sourceGroupId, targetGroupId,
					userId);

			missingReferences = TransactionInvokerUtil.invoke(
				transactionConfig, layoutStagingCallable);

			file = layoutStagingCallable.getFile();

			ExportImportThreadLocal.setInitialLayoutStagingInProcess(false);
			ExportImportThreadLocal.setLayoutStagingInProcess(false);

			ExportImportLifecycleManagerUtil.fireExportImportLifecycleEvent(
				ExportImportLifecycleConstants.
					EVENT_PUBLICATION_LAYOUT_LOCAL_SUCCEEDED,
				ExportImportLifecycleConstants.
					PROCESS_FLAG_LAYOUT_STAGING_IN_PROCESS,
				String.valueOf(
					exportImportConfiguration.getExportImportConfigurationId()),
				exportImportConfiguration);

			FileEntry fileEntry = null;

			try {
				fileEntry = TempFileEntryUtil.addTempFileEntry(
					sourceGroupId, userId, ExportImportHelper.TEMP_FOLDER_NAME,
					file.getName(), file, MimeTypesUtil.getContentType(file));

				ManifestSummary manifestSummary =
					ExportImportHelperUtil.getManifestSummary(
						userId, sourceGroupId, new HashMap<>(), fileEntry);

				Map<String, Serializable> taskContextMap =
					backgroundTask.getTaskContextMap();

				HashMap<String, LongWrapper> modelAdditionCounters =
					new HashMap<>(manifestSummary.getModelAdditionCounters());

				taskContextMap.put(
					ExportImportBackgroundTaskContextMapConstants.
						MODEL_ADDITION_COUNTERS,
					modelAdditionCounters);

				HashMap<String, LongWrapper> modelDeletionCounters =
					new HashMap<>(manifestSummary.getModelDeletionCounters());

				taskContextMap.put(
					ExportImportBackgroundTaskContextMapConstants.
						MODEL_DELETION_COUNTERS,
					modelDeletionCounters);

				HashSet<String> manifestSummaryKeys = new HashSet<>(
					manifestSummary.getManifestSummaryKeys());

				taskContextMap.put(
					ExportImportBackgroundTaskContextMapConstants.
						MANIFEST_SUMMARY_KEYS,
					manifestSummaryKeys);
			}
			catch (Exception e) {
				if (_log.isWarnEnabled()) {
					_log.warn(
						"Unable to process manifest for the process summary " +
							"screen");
				}
			}
			finally {
				if (fileEntry != null) {
					TempFileEntryUtil.deleteTempFileEntry(
						fileEntry.getFileEntryId());
				}
			}
		}
		catch (Throwable t) {
			ExportImportThreadLocal.setInitialLayoutStagingInProcess(false);
			ExportImportThreadLocal.setLayoutStagingInProcess(false);

			ExportImportLifecycleManagerUtil.fireExportImportLifecycleEvent(
				ExportImportLifecycleConstants.
					EVENT_PUBLICATION_LAYOUT_LOCAL_FAILED,
				ExportImportLifecycleConstants.
					PROCESS_FLAG_LAYOUT_STAGING_IN_PROCESS,
				String.valueOf(
					exportImportConfiguration.getExportImportConfigurationId()),
				exportImportConfiguration, t);

			Group sourceGroup = GroupLocalServiceUtil.getGroup(sourceGroupId);

			if (sourceGroup.hasStagingGroup()) {
				ServiceContext serviceContext = new ServiceContext();

				serviceContext.setUserId(userId);

				StagingLocalServiceUtil.disableStaging(
					sourceGroup, serviceContext);

				List<BackgroundTask> queuedBackgroundTasks =
					BackgroundTaskManagerUtil.getBackgroundTasks(
						sourceGroupId,
						LayoutStagingBackgroundTaskExecutor.class.getName(),
						BackgroundTaskConstants.STATUS_QUEUED);

				for (BackgroundTask queuedBackgroundTask :
						queuedBackgroundTasks) {

					BackgroundTaskManagerUtil.amendBackgroundTask(
						queuedBackgroundTask.getBackgroundTaskId(), null,
						BackgroundTaskConstants.STATUS_CANCELLED,
						new ServiceContext());
				}
			}

			deleteTempLarOnFailure(file);

			throw new SystemException(t);
		}

		deleteTempLarOnSuccess(file);

		return processMissingReferences(
			backgroundTask.getBackgroundTaskId(), missingReferences);
	}

	@Override
	public BackgroundTaskDisplay getBackgroundTaskDisplay(
		BackgroundTask backgroundTask) {

		return new LayoutStagingBackgroundTaskDisplay(backgroundTask);
	}

	protected void initLayoutSetBranches(
			long userId, long sourceGroupId, long targetGroupId)
		throws PortalException {

		Group sourceGroup = GroupLocalServiceUtil.getGroup(sourceGroupId);

		if (!sourceGroup.hasStagingGroup()) {
			return;
		}

		LayoutSetBranchLocalServiceUtil.deleteLayoutSetBranches(
			targetGroupId, false, true);
		LayoutSetBranchLocalServiceUtil.deleteLayoutSetBranches(
			targetGroupId, true, true);

		UnicodeProperties typeSettingsProperties =
			sourceGroup.getTypeSettingsProperties();

		boolean branchingPrivate = GetterUtil.getBoolean(
			typeSettingsProperties.getProperty("branchingPrivate"));
		boolean branchingPublic = GetterUtil.getBoolean(
			typeSettingsProperties.getProperty("branchingPublic"));

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setUserId(userId);

		StagingLocalServiceUtil.checkDefaultLayoutSetBranches(
			userId, sourceGroup, branchingPublic, branchingPrivate, false,
			serviceContext);
	}

	private static final Log _log = LogFactoryUtil.getLog(
		LayoutStagingBackgroundTaskExecutor.class);

	private class LayoutStagingCallable implements Callable<MissingReferences> {

		public LayoutStagingCallable(
			long backgroundTaskId,
			ExportImportConfiguration exportImportConfiguration,
			long sourceGroupId, long targetGroupId, long userId) {

			_backgroundTaskId = backgroundTaskId;
			_exportImportConfiguration = exportImportConfiguration;
			_sourceGroupId = sourceGroupId;
			_targetGroupId = targetGroupId;
			_userId = userId;
		}

		@Override
		public MissingReferences call() throws PortalException {
			_file = ExportImportLocalServiceUtil.exportLayoutsAsFile(
				_exportImportConfiguration);

			markBackgroundTask(_backgroundTaskId, "exported");

			ExportImportLocalServiceUtil.importLayoutsDataDeletions(
				_exportImportConfiguration, _file);

			MissingReferences missingReferences =
				ExportImportLocalServiceUtil.validateImportLayoutsFile(
					_exportImportConfiguration, _file);

			markBackgroundTask(_backgroundTaskId, "validated");

			ExportImportLocalServiceUtil.importLayouts(
				_exportImportConfiguration, _file);

			initLayoutSetBranches(_userId, _sourceGroupId, _targetGroupId);

			return missingReferences;
		}

		public File getFile() {
			return _file;
		}

		private final long _backgroundTaskId;
		private final ExportImportConfiguration _exportImportConfiguration;
		private File _file;
		private final long _sourceGroupId;
		private final long _targetGroupId;
		private final long _userId;

	}

}