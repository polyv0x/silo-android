package org.siloserver.silo.common.player

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MatroskaFlacCodecAdapterFactoryTest {
    private val streamInfo = ByteArray(34) { it.toByte() }
    private val streamHeader = byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0, 0, 0, 34) + streamInfo

    @Test
    fun configuresCodecWithStreamInfoAndPreservesTheOriginalMatroskaData() {
        val original = streamHeader +
            byteArrayOf(3, 0, 10, 32) + ByteArray(2592) + // SEEKTABLE
            byteArrayOf(4, 0, 0, 84) + ByteArray(84) + // VORBIS_COMMENT
            byteArrayOf(0x81.toByte(), 1, 0, 0) + ByteArray(65536) // Last block, PADDING
        val track = trackFormat(original)
        val csd = ByteBuffer.allocateDirect(original.size + 5).apply {
            position(5)
            put(original)
            flip()
            position(5)
        }.asReadOnlyBuffer()
        val codec = MediaFormat.createAudioFormat(MimeTypes.AUDIO_FLAC, 48000, 2).apply {
            setByteBuffer("csd-0", csd)
        }

        codec.useMatroskaFlacStreamInfo(track)

        val expected = streamHeader.copyOf().apply { this[4] = 0x80.toByte() }
        val result = requireNotNull(codec.getByteBuffer("csd-0"))
        assertEquals(42, result.remaining())
        assertContentEquals(expected, ByteArray(result.remaining()).also { result.duplicate().get(it) })
        assertEquals(5, csd.position())
        assertEquals(68266, csd.remaining())
        assertContentEquals(original, ByteArray(csd.remaining()).also { csd.duplicate().get(it) })
        assertContentEquals(original, track.initializationData.single())
        codec.useMatroskaFlacStreamInfo(track)
        assertSame(result, codec.getByteBuffer("csd-0"))
    }

    @Test
    fun leavesCanonicalAndMalformedHeadersAlone() {
        val canonical = streamHeader.copyOf().apply { this[4] = 0x80.toByte() }
        for (data in listOf(
            canonical,
            streamHeader.copyOf(20),
            streamHeader + byteArrayOf(0x81.toByte(), 0, 0, 50, 0), // Truncated padding
            streamHeader + byteArrayOf(1, 0, 0, 0), // Missing last block
            canonical + byteArrayOf(1, 0, 0, 0), // Trailing bytes after the last block
        )) {
            assertUnchanged(data, trackFormat(data), MimeTypes.AUDIO_FLAC)
        }
    }

    @Test
    fun leavesOtherContainersTracksAndCodecMimeTypesAlone() {
        val data = streamHeader + byteArrayOf(0x81.toByte(), 0, 0, 1, 0)
        assertUnchanged(data, trackFormat(data, container = MimeTypes.AUDIO_FLAC), MimeTypes.AUDIO_FLAC)
        assertUnchanged(data, trackFormat(data, audio = MimeTypes.AUDIO_AAC), MimeTypes.AUDIO_AAC)
        assertUnchanged(data, trackFormat(data), MimeTypes.AUDIO_RAW)
    }

    private fun trackFormat(
        data: ByteArray,
        container: String = MimeTypes.VIDEO_MATROSKA,
        audio: String = MimeTypes.AUDIO_FLAC,
    ): Format = Format.Builder()
        .setContainerMimeType(container)
        .setSampleMimeType(audio)
        .setInitializationData(listOf(data))
        .build()

    private fun assertUnchanged(data: ByteArray, track: Format, codecMime: String) {
        val csd = ByteBuffer.wrap(data)
        val codec = MediaFormat.createAudioFormat(codecMime, 48000, 2).apply { setByteBuffer("csd-0", csd) }
        codec.useMatroskaFlacStreamInfo(track)
        assertSame(csd, codec.getByteBuffer("csd-0"))
    }
}
