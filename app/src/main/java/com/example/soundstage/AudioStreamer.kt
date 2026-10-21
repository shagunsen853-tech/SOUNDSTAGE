package com.example.soundstage

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Records the phone mic (8 kHz, mono) and delivers 160-byte chunks
 * (20 ms each) encoded as 8-bit mu-law. That is 8 KB/s, which fits
 * comfortably in BLE write-without-response.
 *
 * onChunk is called on a background thread.
 */
class AudioStreamer(private val onChunk: (ByteArray) -> Unit) {

    private val tag = "SoundStageAudio"
    private val sampleRate = 8000
    private val samplesPerChunk = 160 // 20 ms

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            Log.e(tag, "AudioRecord create failed", e)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        recorder = rec
        running = true
        rec.startRecording()

        thread = Thread {
            val pcm = ShortArray(samplesPerChunk)
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) {
                    val out = ByteArray(n) { i -> linearToMuLaw(pcm[i]) }
                    onChunk(out)
                }
            }
        }.also { it.start() }

        return true
    }

    fun stop() {
        running = false
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
        }
        thread = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
    }

    /** Standard G.711 mu-law encoder (16-bit PCM -> 8-bit). */
    private fun linearToMuLaw(sample: Short): Byte {
        val bias = 0x84
        val clip = 32635

        var s = sample.toInt()
        val sign = if (s < 0) {
            s = -s
            0x80
        } else {
            0
        }
        if (s > clip) s = clip
        s += bias

        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) {
            exponent--
            mask = mask shr 1
        }

        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}