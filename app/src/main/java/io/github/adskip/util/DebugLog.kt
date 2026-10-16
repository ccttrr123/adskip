package io.github.adskip.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一个带环形缓冲的日志。
 *
 * 存在的理由很实际：真机上"到底有没有识别到广告、执行了哪条策略、有没有误点"
 * 是无法靠回看代码判断的，必须能在应用界面上直接看到。
 * M1 阶段这个面板的价值高于任何自动统计。
 */
object DebugLog {

    private const val TAG = "AdSkip"
    private const val CAPACITY = 400

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun i(msg: String) = append("I", msg)

    fun w(msg: String) = append("W", msg)

    fun e(msg: String) = append("E", msg)

    private fun append(level: String, msg: String) {
        when (level) {
            "W" -> Log.w(TAG, msg)
            "E" -> Log.e(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        val line = "${timeFormat.format(Date())} $level $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        listeners.forEach { it(line) }
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
