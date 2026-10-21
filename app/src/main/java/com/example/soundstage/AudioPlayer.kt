package com.example.soundstage

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays 8 kHz mono 8-bit mu-law audio (the same format AudioStreamer sends).
 * Call start(), then play(bytes) for every chunk received from the device.
 */
class AudioPlayer {

    private val sampleRate = 8000
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return false
        }

        track = t
        queue.clear()
        running = true
        t.play()

        thread = Thread {
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val pcm = ShortArray(chunk.size) { i -> muLawToLinear(chunk[i]) }
                t.write(pcm, 0, pcm.size)
            }
        }.also { it.start() }

        return true
    }

    /** Add a received chunk. Old chunks are dropped if playback falls behind. */
    fun play(data: ByteArray) {
        if (!running) return
        if (queue.size > 50) queue.poll()
        queue.offer(data)
    }

    fun stop() {
        running = false
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
        }
        thread = null

        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
        queue.clear()
    }

    /** Standard G.711 mu-law decoder (8-bit -> 16-bit PCM). */
    private fun muLawToLinear(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F

        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84

        return (if (sign != 0) -sample else sample).toShort()
    }
}