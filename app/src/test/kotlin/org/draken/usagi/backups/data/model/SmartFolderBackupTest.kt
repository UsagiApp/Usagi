package org.draken.usagi.backups.data.model

import org.draken.usagi.backups.domain.BackupSection
import org.draken.usagi.favourites.data.SmartFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.ZipEntry

class SmartFolderBackupTest {
	@Test
	fun `smart folders use an independent backup section`() {
		assertEquals(BackupSection.SMART_FOLDERS, BackupSection.of(ZipEntry("smart_folders")))
	}

	@Test
	fun `backup model preserves the complete smart folder record`() {
		val entity =
			SmartFolderEntity(
				id = 42L,
				title = "Offline SFW",
				sortKey = 3,
				listOrder = "UPDATED",
				rules = "{\"version\":1,\"device\":\"ON_DEVICE\"}",
				createdAt = 100L,
				updatedAt = 200L,
				deletedAt = 300L,
			)

		assertEquals(entity, SmartFolderBackup(entity).toEntity())
	}
}
