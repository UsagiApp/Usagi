package org.draken.usagi.backups.import.mihon

import android.content.Context
import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.draken.usagi.backups.import.mihon.model.Backup
import org.draken.usagi.core.exceptions.BadBackupFormatException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Decodes Mihon `.tachibk` backup files (protobuf + gzip).
 * Detects gzip magic bytes (0x1f8b), decompresses, and deserializes protobuf to [Backup].
 */
object MihonBackupDecoder {

    private const val GZIP_MAGIC_0: Int = 0x1f
    private const val GZIP_MAGIC_1: Int = 0x8b

    /**
     * Decode a Mihon backup from a content URI.
     *
     * @param context Android context for content resolver access
     * @param uri URI pointing to the `.tachibk` file
     * @return Decoded [Backup] object
     * @throws BadBackupFormatException if the file is corrupted or not a valid Mihon backup
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(context: Context, uri: Uri): Backup {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw BadBackupFormatException(null)

        return inputStream.use { stream ->
            decodeFromStream(stream)
        }
    }

    /**
     * Decode a Mihon backup from an input stream.
     * Auto-detects gzip compression and decompresses if needed.
     *
     * @param stream Input stream containing the backup data
     * @return Decoded [Backup] object
     * @throws BadBackupFormatException if the stream is corrupted or not valid protobuf
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeFromStream(stream: InputStream): Backup {
        val bytes = stream.readBytes()

        if (bytes.size < 2) {
            throw BadBackupFormatException(
                Exception("Backup file too small to be a valid Mihon backup")
            )
        }

        val dataStream = if (isGzip(bytes[0].toInt(), bytes[1].toInt())) {
            decompressGzip(ByteArrayInputStream(bytes))
        } else {
            ByteArrayInputStream(bytes)
        }

        return try {
            ProtoBuf.decodeFromByteArray(Backup.serializer(), dataStream.readBytes())
        } catch (e: Exception) {
            throw BadBackupFormatException(e)
        }
    }

    /**
     * Check if the first two bytes are gzip magic bytes (0x1f, 0x8b).
     */
    private fun isGzip(byte0: Int, byte1: Int): Boolean {
        return byte0 and 0xFF == GZIP_MAGIC_0 && byte1 and 0xFF == GZIP_MAGIC_1
    }

    /**
     * Decompress a gzip stream.
     */
    private fun decompressGzip(inputStream: InputStream): InputStream {
        return try {
            GZIPInputStream(inputStream)
        } catch (e: Exception) {
            throw BadBackupFormatException(e)
        }
    }
}
