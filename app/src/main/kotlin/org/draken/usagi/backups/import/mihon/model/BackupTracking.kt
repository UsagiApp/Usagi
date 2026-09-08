package org.draken.usagi.backups.import.mihon.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupTracking(
    @ProtoNumber(1) var syncId: Int = 0,
    @ProtoNumber(2) var libraryId: Long = 0,
    @ProtoNumber(3) var mediaIdInt: Int = 0,
    @ProtoNumber(4) var trackingUrl: String = "",
    @ProtoNumber(5) var title: String = "",
    @ProtoNumber(6) var lastChapterRead: Float = 0F,
    @ProtoNumber(7) var totalChapters: Int = 0,
    @ProtoNumber(8) var score: Float = 0F,
    @ProtoNumber(9) var status: Int = 0,
    @ProtoNumber(10) var startedReadingDate: Long = 0,
    @ProtoNumber(11) var finishedReadingDate: Long = 0,
    @ProtoNumber(12) var `private`: Boolean = false,
    @ProtoNumber(100) var mediaId: Long = 0,
)
