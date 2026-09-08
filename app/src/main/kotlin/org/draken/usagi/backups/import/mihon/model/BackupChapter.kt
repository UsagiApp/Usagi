package org.draken.usagi.backups.import.mihon.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupChapter(
    @ProtoNumber(1) var url: String = "",
    @ProtoNumber(2) var name: String = "",
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    @ProtoNumber(6) var lastPageRead: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    @ProtoNumber(9) var chapterNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    @ProtoNumber(11) var lastModifiedAt: Long = 0,
    @ProtoNumber(12) var version: Long = 0,
)
