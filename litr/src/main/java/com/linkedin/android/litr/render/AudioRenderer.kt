/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr.render

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.linkedin.android.litr.codec.Encoder
import com.linkedin.android.litr.codec.Frame
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

private const val BYTES_PER_SAMPLE = 2
private const val FRAME_WAIT_TIMEOUT: Long = 0L

private const val TAG = "AudioRenderer"

class AudioRenderer(private val encoder: Encoder) : Renderer {

    private var sourceMediaFormat: MediaFormat? = null
    private var targetMediaFormat: MediaFormat? = null
    private var sampleDurationUs: Float = 0f
    private var channelCount = 2

    private var released: AtomicBoolean = AtomicBoolean(false)

    private val renderQueue = LinkedBlockingDeque<Frame>()

    private val renderThread = RenderThread()

    override fun init(outputSurface: Surface?, sourceMediaFormat: MediaFormat?, targetMediaFormat: MediaFormat?) {
        onMediaFormatChanged(sourceMediaFormat, targetMediaFormat)
        released.set(false)
        renderThread.start()
    }

    override fun onMediaFormatChanged(sourceMediaFormat: MediaFormat?, targetMediaFormat: MediaFormat?) {
        this.sourceMediaFormat = sourceMediaFormat
        this.targetMediaFormat = targetMediaFormat

        if (targetMediaFormat?.containsKey(MediaFormat.KEY_SAMPLE_RATE) == true) {
            sampleDurationUs = 1_000_000f / targetMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }

        if (targetMediaFormat?.containsKey(MediaFormat.KEY_CHANNEL_COUNT) == true) {
            channelCount = targetMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }

    override fun getInputSurface(): Surface? {
        return null
    }

    override fun renderFrame(inputFrame: Frame?, presentationTimeNs: Long) {
        if (!released.get() && inputFrame?.buffer != null) {
            val buffer = ByteBuffer.allocate(inputFrame.buffer.limit())
            buffer.put(inputFrame.buffer)
            buffer.flip()

            val bufferInfo = MediaCodec.BufferInfo()
            bufferInfo.set(
                0,
                inputFrame.bufferInfo.size,
                inputFrame.bufferInfo.presentationTimeUs,
                inputFrame.bufferInfo.flags
            )

            renderQueue.add(Frame(inputFrame.tag, buffer, bufferInfo))
        }
    }

    override fun release() {
        released.set(true)
    }

    override fun hasFilters(): Boolean {
        return false
    }

    private inner class RenderThread : Thread() {
        override fun run() {
            while (!released.get()) {
                renderQueue.peekFirst()?.let { inputFrame ->
                    val tag = encoder.dequeueInputFrame(FRAME_WAIT_TIMEOUT)
                    when {
                        tag >= 0 -> renderFrame(tag, inputFrame)
                        tag == MediaCodec.INFO_TRY_AGAIN_LATER -> {} // do nothing, will try later
                        else -> Log.e(TAG, "Unhandled value $tag when receiving decoded input frame")
                    }
                }
            }
            renderQueue.clear()
        }

        private fun renderFrame(tag: Int, inputFrame: Frame) {
            encoder.getInputFrame(tag)?.let { outputFrame ->
                if (outputFrame.buffer != null && inputFrame.buffer != null) {
                    outputFrame.bufferInfo.offset = 0
                    outputFrame.bufferInfo.flags = inputFrame.bufferInfo.flags
                    outputFrame.bufferInfo.presentationTimeUs =
                        inputFrame.bufferInfo.presentationTimeUs +
                            ((inputFrame.buffer.position() / (channelCount * BYTES_PER_SAMPLE)) * sampleDurationUs).toLong()

                    if (outputFrame.buffer.limit() >= inputFrame.buffer.remaining()) {
                        // if remaining input bytes fit output buffer, use them all and discard the output buffer
                        outputFrame.bufferInfo.size = inputFrame.buffer.remaining()
                        renderQueue.removeFirst()
                    } else {
                        // otherwise, fill the output buffer and clear its EOS flag
                        outputFrame.bufferInfo.size = outputFrame.buffer.limit()
                        outputFrame.bufferInfo.flags = outputFrame.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    }

                    outputFrame.buffer.put(
                        inputFrame.buffer.array(),
                        inputFrame.buffer.position(),
                        outputFrame.bufferInfo.size)

                    // advance input buffer position by number of bytes copied
                    inputFrame.buffer.position(inputFrame.buffer.position() + outputFrame.bufferInfo.size)

                    encoder.queueInputFrame(outputFrame)
                }
            }
        }
    }
}