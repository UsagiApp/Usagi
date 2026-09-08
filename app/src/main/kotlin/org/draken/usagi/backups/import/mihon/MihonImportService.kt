package org.draken.usagi.backups.import.mihon

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.draken.usagi.R
import org.draken.usagi.backups.data.BackupRepository
import org.draken.usagi.backups.data.model.CategoryBackup
import org.draken.usagi.backups.data.model.FavouriteBackup
import org.draken.usagi.backups.data.model.ScrobblingBackup
import org.draken.usagi.backups.data.model.SourceBackup
import org.draken.usagi.backups.domain.BackupSection
import org.draken.usagi.backups.ui.BaseBackupRestoreService
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.util.ext.checkNotificationPermission
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.toUriOrNull
import org.draken.usagi.core.util.ext.withPartialWakeLock
import org.draken.usagi.core.util.progress.Progress
import org.draken.usagi.core.util.CompositeResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class MihonImportService : BaseBackupRestoreService() {
    override val notificationTag = TAG
    override val isRestoreService = true

    @Inject
    lateinit var repository: BackupRepository

    private val json = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        useAlternativeNames = false
    }

    override suspend fun IntentJobContext.processIntent(intent: Intent) {
        val notification = buildNotification(Progress.INDETERMINATE)
        setForeground(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val source = intent.getStringExtra(AppRouter.KEY_DATA)?.toUriOrNull()
            ?: throw FileNotFoundException()
        val sections = requireNotNull(
            intent.getSerializableExtraCompat<Array<BackupSection>>(AppRouter.KEY_ENTRIES)?.toSet()
        )

        powerManager.withPartialWakeLock(TAG) {
            val progress = MutableStateFlow(Progress.INDETERMINATE)
            val progressUpdateJob =
                if (checkNotificationPermission(CHANNEL_ID)) {
                    launch {
                        progress.collect {
                            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(it))
                        }
                    }
                } else {
                    null
                }

            val result = try {
                val mihonBackup = MihonBackupDecoder.decode(applicationContext, source)
                val mappedBackup = MihonBackupMapper.mapBackup(mihonBackup)
                val zipBytes = buildUsagiZip(mappedBackup, sections)
                val zipInput = ZipInputStream(ByteArrayInputStream(zipBytes))
                zipInput.use { input ->
                    repository.restoreBackup(input, sections, progress)
                }
            } catch (e: Exception) {
                e.printStackTraceDebug()
                CompositeResult.failure(e)
            }

            progressUpdateJob?.cancelAndJoin()
            showResultNotification(source, result)
        }
    }

    private fun buildUsagiZip(
        backup: MappedBackup,
        sections: Set<BackupSection>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { output ->
            if (BackupSection.HISTORY in sections && backup.history.isNotEmpty()) {
                writeSection(output, BackupSection.HISTORY, backup.history)
            }
            if (BackupSection.CATEGORIES in sections && backup.categories.isNotEmpty()) {
                writeSection(output, BackupSection.CATEGORIES, backup.categories)
            }
            if (BackupSection.FAVOURITES in sections && backup.manga.isNotEmpty()) {
                val favourites = backup.manga.mapIndexed { index, manga ->
                    FavouriteBackup(
                        mangaId = manga.id,
                        categoryId = 0,
                        sortKey = index,
                        isPinned = false,
                        createdAt = System.currentTimeMillis(),
                        manga = manga,
                    )
                }
                writeSection(output, BackupSection.FAVOURITES, favourites)
            }
            if (BackupSection.SOURCES in sections && backup.sources.isNotEmpty()) {
                writeSection(output, BackupSection.SOURCES, backup.sources)
            }
            if (BackupSection.SCROBBLING in sections && backup.scrobbling.isNotEmpty()) {
                writeSection(output, BackupSection.SCROBBLING, backup.scrobbling)
            }
            output.putNextEntry(ZipEntry(BackupSection.INDEX.entryName))
            try {
                output.write("[]".toByteArray())
            } finally {
                output.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private inline fun <reified T> writeSection(
        output: ZipOutputStream,
        section: BackupSection,
        data: List<T>,
    ) {
        output.putNextEntry(ZipEntry(section.entryName))
        try {
            val s = serializer<T>()
            output.write("[".toByteArray())
            data.forEachIndexed { index, item ->
                if (index > 0) output.write(",".toByteArray())
                json.encodeToStream(s, item, output)
            }
            output.write("]".toByteArray())
        } finally {
            output.closeEntry()
            output.flush()
        }
    }

    private fun IntentJobContext.buildNotification(progress: Progress): Notification =
        NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.importing_mihon_backup))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(
                progress.total.coerceAtLeast(0),
                progress.progress.coerceAtLeast(0),
                progress.isIndeterminate,
            ).setContentText(
                if (progress.isIndeterminate) {
                    getString(R.string.processing_)
                } else {
                    "${progress.progress}/${progress.total}"
                },
            ).setSmallIcon(android.R.drawable.stat_sys_download)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(android.R.string.cancel),
                getCancelIntent(),
            ).build()

    companion object {
        private const val TAG = "MIHON_IMPORT"
        private const val FOREGROUND_NOTIFICATION_ID = 40

        fun start(
            context: Context,
            uri: Uri,
            sections: Set<BackupSection>,
        ): Boolean =
            try {
                val intent = Intent(context, MihonImportService::class.java)
                intent.putExtra(AppRouter.KEY_DATA, uri.toString())
                intent.putExtra(AppRouter.KEY_ENTRIES, sections.toTypedArray())
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                e.printStackTraceDebug()
                false
            }
    }
}
