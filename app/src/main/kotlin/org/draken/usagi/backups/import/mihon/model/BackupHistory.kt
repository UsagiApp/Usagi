package org.draken.usagi.backups.import.mihon.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupHistory(
    @ProtoNumber(1) var url: String = "",
    @ProtoNumber(2) var lastRead: Long = 0,
    @ProtoNumber(3) var readDuration: Long = 0,
)
