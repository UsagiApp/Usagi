package org.draken.usagi.alternatives.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.alternatives.domain.MigrateToSourceUseCase
import org.draken.usagi.core.ErrorReporterReceiver
import org.draken.usagi.core.model.MangaSource
import org.draken.usagi.core.ui.CoroutineIntentService
import org.draken.usagi.core.util.ext.checkNotificationPermission
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.printStackTraceDebug
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class MigrateSourceService : CoroutineIntentService() {
	@Inject
	lateinit var migrateToSourceUseCase: MigrateToSourceUseCase

	private lateinit var notificationManager: NotificationManagerCompat

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(this)
	}

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		val ids = requireNotNull(intent.getLongArrayExtra(DATA_IDS))
		val target = MangaSource(intent.getStringExtra(DATA_TARGET))
		startForeground(this)
		var migrated = 0
		var missing = 0
		var failed = 0
		// sequential on purpose: one search per manga keeps the load on sources gentle
		ids.forEachIndexed { index, mangaId ->
			runCatchingCancellable { migrateToSourceUseCase(mangaId, target) }
				.onSuccess { found -> if (found) migrated++ else missing++ }
				.onFailure { failed++ }
			if (checkNotificationPermission(CHANNEL_ID)) {
				notificationManager.notify(TAG, FOREGROUND_NOTIFICATION_ID, buildProgressNotification(this, index + 1, ids.size))
			}
		}
		if (checkNotificationPermission(CHANNEL_ID)) {
			notificationManager.notify(TAG, SUMMARY_NOTIFICATION_ID, buildSummaryNotification(migrated, missing, failed))
		}
	}

	override fun IntentJobContext.onError(error: Throwable) {
		if (!checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val builder =
			NotificationCompat
				.Builder(this@MigrateSourceService, CHANNEL_ID)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setDefaults(0)
				.setSilent(true)
				.setAutoCancel(true)
				.setContentTitle(getString(R.string.error_occurred))
				.setContentText(error.getDisplayMessage(resources))
				.setSmallIcon(android.R.drawable.stat_notify_error)
		ErrorReporterReceiver
			.getNotificationAction(
				context = this@MigrateSourceService,
				e = error,
				notificationId = startId,
				notificationTag = TAG,
			)?.let { action ->
				builder.addAction(action)
			}
		notificationManager.notify(TAG, startId, builder.build())
	}

	@SuppressLint("InlinedApi")
	private fun startForeground(jobContext: IntentJobContext) {
		val title = getString(R.string.migrating_favourites)
		val channel =
			NotificationChannelCompat
				.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
				.setName(title)
				.setShowBadge(false)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
		notificationManager.createNotificationChannel(channel)

		val notification =
			NotificationCompat
				.Builder(this, CHANNEL_ID)
				.setContentTitle(title)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setDefaults(0)
				.setSilent(true)
				.setOngoing(true)
				.setProgress(0, 0, true)
				.setSmallIcon(R.drawable.ic_stat_auto_fix)
				.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
				.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				.addAction(
					appcompatR.drawable.abc_ic_clear_material,
					getString(android.R.string.cancel),
					jobContext.getCancelIntent(),
				).build()

		jobContext.setForeground(
			FOREGROUND_NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	}

	private fun buildProgressNotification(
		jobContext: IntentJobContext,
		current: Int,
		total: Int,
	): Notification =
		NotificationCompat
			.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.migrating_favourites))
			.setContentText(getString(R.string.migration_progress, current, total))
			.setProgress(total, current, false)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setSmallIcon(R.drawable.ic_stat_auto_fix)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				getString(android.R.string.cancel),
				jobContext.getCancelIntent(),
			).build()

	private fun buildSummaryNotification(
		migrated: Int,
		missing: Int,
		failed: Int,
	): Notification =
		NotificationCompat
			.Builder(this, CHANNEL_ID)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setDefaults(0)
			.setSilent(true)
			.setAutoCancel(true)
			.setContentTitle(getString(R.string.migration_completed))
			.setContentText(getString(R.string.migration_summary, migrated, missing, failed))
			.setSmallIcon(R.drawable.ic_stat_done)
			.build()

	companion object {
		private const val DATA_IDS = "ids"
		private const val DATA_TARGET = "target"
		private const val TAG = "migrate_source"
		private const val CHANNEL_ID = "migrate_source"
		private const val FOREGROUND_NOTIFICATION_ID = 39
		private const val SUMMARY_NOTIFICATION_ID = 40

		fun start(
			context: Context,
			mangaIds: Collection<Long>,
			target: MangaSource,
		): Boolean =
			try {
				val intent = Intent(context, MigrateSourceService::class.java)
				intent.putExtra(DATA_IDS, mangaIds.toLongArray())
				intent.putExtra(DATA_TARGET, target.name)
				ContextCompat.startForegroundService(context, intent)
				true
			} catch (e: Exception) {
				e.printStackTraceDebug()
				false
			}
	}
}
