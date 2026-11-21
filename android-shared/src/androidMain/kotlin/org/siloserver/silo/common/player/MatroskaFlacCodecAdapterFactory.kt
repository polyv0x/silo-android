package org.siloserver.silo.common.player

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
internal class MatroskaFlacCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory by delegate {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        configuration.mediaFormat.useMatroskaFlacStreamInfo(configuration.format)
        return delegate.createAdapter(configuration)
    }
}

private const val FLAC_MARKER = 0x664c6143 // fLaC
private const val STREAMINFO_SIZE = 34
private const val STREAMINFO_END = 4 + 4 + STREAMINFO_SIZE

/**
 * Media3 passes Matroska's entire FLAC CodecPrivate to MediaCodec. Tags, seek tables and
 * padding can exceed the codec's input buffer. Its standalone FLAC extractor configures the
 * decoder with just the marker and STREAMINFO. Use the same representation at codec startup.
 */
@UnstableApi
internal fun MediaFormat.useMatroskaFlacStreamInfo(format: Format) {
    if (format.sampleMimeType != MimeTypes.AUDIO_FLAC ||
        (format.containerMimeType != MimeTypes.VIDEO_MATROSKA && format.containerMimeType != MimeTypes.AUDIO_MATROSKA) ||
        getString(MediaFormat.KEY_MIME) != MimeTypes.AUDIO_FLAC
    ) return

    val csd = getByteBuffer("csd-0") ?: return
    val blocks = csd.duplicate().order(ByteOrder.BIG_ENDIAN)
    if (blocks.remaining() <= STREAMINFO_END || blocks.int != FLAC_MARKER || blocks.int != STREAMINFO_SIZE) return
    blocks.position(blocks.position() + STREAMINFO_SIZE)

    // Only replace a complete metadata chain. The first block above is a non-final STREAMINFO.
    while (blocks.remaining() >= 4) {
        val header = blocks.int
        val type = (header ushr 24) and 0x7f
        val length = header and 0xffffff
        if (type == 0 || type == 127 || length > blocks.remaining()) return
        blocks.position(blocks.position() + length)
        if (header < 0) { // Last metadata block
            if (blocks.hasRemaining()) return
            val streamInfo = ByteArray(STREAMINFO_END)
            csd.duplicate().get(streamInfo)
            streamInfo[4] = 0x80.toByte() // The decoder now receives STREAMINFO as its last block.
            setByteBuffer("csd-0", ByteBuffer.wrap(streamInfo))
            return
        }
    }
}
