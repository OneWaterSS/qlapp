package com.example.qlapp.games

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * 把用户导入的音乐变成关卡。
 *
 * 解码成单声道 PCM 后，用「能量突增」找节拍点（onset），
 * 再用归一化自相关测每个节拍点的基频 —— 这才是真音高，
 * 玩家按出来的钢琴音连起来就是原曲的旋律，不放原声也听得出是哪首歌。
 * 列号跟着音高走：低音在左，高音在右。
 */
object AudioAnalyzer {

    private const val TARGET_RATE = 11025
    private const val MAX_SECONDS = 120
    private const val WINDOW = 512
    private const val HOP = 256
    private const val MIN_GAP_FRAMES = 4 // ≈ 90ms，最密每秒 11 个方块
    private const val MAX_BEATS = 420
    private const val LEAD_MS = 2600 // 和内置曲目一样的起手准备时间

    /** 解析失败或音频太短时抛 IllegalArgumentException，由界面提示用户。 */
    fun analyze(context: Context, uri: Uri): List<SongBeat> {
        val pcm = decodeMono(context, uri)
        if (pcm.size < TARGET_RATE) throw IllegalArgumentException("这段音频太短了，换一首试试")
        return analyzePcm(pcm)
    }

    /**
     * 从单声道 PCM 直接生成关卡：找节拍点 → 测每个节拍点的真音高 → 排时间、列和长方块。
     * 不碰 Android 解码器，是纯函数 —— 整条流水线在单元测试里用合成旋律端到端验证。
     */
    fun analyzePcm(pcm: FloatArray): List<SongBeat> {
        val frames = (pcm.size - WINDOW) / HOP
        if (frames < 8) throw IllegalArgumentException("这段音频太短了，换一首试试")

        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * HOP
            var sum = 0.0
            for (i in 0 until WINDOW) {
                val s = pcm[start + i].toDouble()
                sum += s * s
            }
            rms[f] = kotlin.math.sqrt(sum / WINDOW).toFloat()
        }

        val peak = rms.max()
        if (peak <= 1e-5f) throw IllegalArgumentException("这段音频几乎没有声音，换一首试试")

        val onsets = pickOnsets(rms, peak, frames)
        val resolved = if (onsets.size < 12) uniformGrid(frames) else onsets
        if (resolved.isEmpty()) throw IllegalArgumentException("没能从这段音频里找到节奏")

        assignPitches(pcm, resolved)

        val frameMs = 1000.0 * HOP / TARGET_RATE
        val first = resolved.first().first
        val shift = LEAD_MS - (first * frameMs)
        val usable = resolved.take(MAX_BEATS)
        val times = usable.map { (frame, _) -> (frame * frameMs + shift).toInt() }

        // 拖得比常规间隔明显更长的音（长音、尾音）做成需要按住的长方块。
        val gaps = times.zipWithNext { a, b -> b - a }.sorted()
        val median = (gaps.getOrNull(gaps.size / 2) ?: 500).coerceAtLeast(160)

        return usable.mapIndexed { index, (_, shape) ->
            var hold = 0
            if (index < times.size - 1) {
                val gap = times[index + 1] - times[index]
                if (gap >= median * 1.9f && gap >= 420) hold = (gap * 0.62f).toInt().coerceIn(0, 2400)
            }
            SongBeat(times[index], shape.column, shape.semitone, hold)
        }
    }

    /* ---------------------------- 节拍点检测 ---------------------------- */

    /** 能量上升沿。局部极大 + 自适应阈值，阈值取周围窗口的滑动均值。 */
    private fun pickOnsets(rms: FloatArray, peak: Float, frames: Int): MutableList<Pair<Int, Shape>> {
        val novelty = FloatArray(frames)
        for (f in 1 until frames) {
            novelty[f] = (rms[f] - rms[f - 1]).coerceAtLeast(0f)
        }
        val window = 24 // ±0.5 秒
        val out = mutableListOf<Pair<Int, Shape>>()
        var last = -MIN_GAP_FRAMES
        for (f in 1 until frames - 1) {
            if (f - last < MIN_GAP_FRAMES) continue
            val value = novelty[f]
            if (value < peak * 0.06f) continue
            if (value < novelty[f - 1] || value < novelty[f + 1]) continue
            val from = (f - window).coerceAtLeast(0)
            val to = (f + window).coerceAtMost(frames - 1)
            var sum = 0f
            for (i in from..to) sum += novelty[i]
            val mean = sum / (to - from + 1)
            if (value < mean * 1.7f) continue
            out.add(f to Shape(0, 0))
            last = f
        }
        return out
    }

    /** 检测不出足够节拍时退化成均匀网格，至少让人能玩。 */
    private fun uniformGrid(frames: Int): MutableList<Pair<Int, Shape>> {
        val step = (TARGET_RATE * 0.38 / HOP).toInt().coerceAtLeast(1) // 目标 380ms，取整到帧后实际约 371ms
        val out = mutableListOf<Pair<Int, Shape>>()
        var f = 2
        while (f < frames) {
            out.add(f to Shape(0, 0))
            f += step
        }
        return out
    }

    /* ---------------------------- 列与音高 ---------------------------- */

    private data class Shape(val column: Int, val semitone: Int)

    /** 测音高的窗口：93ms，足够装下最低 60Hz 的好几个周期。 */
    private const val PITCH_WINDOW = 1024
    /** 跳过音头那一小段瞬态（敲弦、喷麦），之后的波形才稳。 */
    private const val PITCH_SKIP_MS = 30
    private const val MIN_HZ = 60f
    private const val MAX_HZ = 1000f
    /** 归一化自相关低于这个值就当「听不出音高」。真实歌曲里鼓和伴奏会稀释相关度，别定太高。 */
    private const val PITCH_CLARITY = 0.28

    /**
     * 归一化自相关测基频：波形和自己错开 lag 个点相乘求和，
     * 哪个 lag 相关度最高，哪个就是周期。返回 Hz，听不出明确音高返回 -1。
     * 纯函数，方便单元测试。
     */
    fun pitchOf(pcm: FloatArray, start: Int, length: Int, rate: Int = TARGET_RATE): Float {
        if (start < 0 || length <= 0 || start + length > pcm.size) return -1f
        val minLag = (rate / MAX_HZ).toInt().coerceAtLeast(2)
        val maxLag = (rate / MIN_HZ).toInt().coerceAtMost(length / 2)
        // 能量的前缀和，O(1) 拿任意区间的能量做归一化。
        val prefix = DoubleArray(length + 1)
        for (i in 0 until length) prefix[i + 1] = prefix[i] + pcm[start + i].toDouble() * pcm[start + i]
        if (prefix[length] < 1e-6) return -1f

        val corr = DoubleArray(maxLag + 1)
        var bestLag = -1
        var best = 0.0
        for (lag in minLag..maxLag) {
            var dot = 0.0
            val n = length - lag
            for (i in 0 until n) dot += pcm[start + i].toDouble() * pcm[start + i + lag]
            val norm = dot / kotlin.math.sqrt(prefix[n] * (prefix[length] - prefix[lag]))
            corr[lag] = norm
            if (norm > best) { best = norm; bestLag = lag }
        }
        // 相关度太低说明这段是鼓点/噪声，没有明确音高。
        if (bestLag < 0 || best < PITCH_CLARITY) return -1f
        // 倍频程纠错：短 lag（高八度）相关度只要接近最优，就取短的那个。
        var chosen = bestLag
        for (lag in minLag until bestLag) {
            if (corr[lag] >= best * 0.85) { chosen = lag; break }
        }
        // 抛物线插值：lag 是整数，插值能把频率精度从 ±4% 收到 ±0.5%，半音才不会听岔。
        val refined = if (chosen > minLag && chosen < maxLag) {
            val y1 = corr[chosen - 1]; val y2 = corr[chosen]; val y3 = corr[chosen + 1]
            val denom = y1 - 2 * y2 + y3
            if (kotlin.math.abs(denom) > 1e-9) chosen + (y1 - y3) / (2 * denom) else chosen.toDouble()
        } else chosen.toDouble()
        return (rate / refined).toFloat()
    }

    /** 频率（Hz）折叠进钢琴音色覆盖的两个八度（C4..C6），保持音名不变。 */
    fun foldToPianoRange(hz: Float): Int {
        if (hz <= 0f) return 72
        var semitone = kotlin.math.round(69 + 12 * kotlin.math.log2(hz / 440f)).toInt()
        while (semitone > 84) semitone -= 12
        while (semitone < 60) semitone += 12
        return semitone
    }

    /**
     * 去掉测音高常见的八度毛刺：前后两个音很接近、只有自己跳了 ±7 半音以上的，
     * 拉回前一个音。纯函数，方便单元测试。
     */
    fun smoothMelody(semitones: IntArray): IntArray {
        if (semitones.size < 3) return semitones
        val out = semitones.copyOf()
        for (i in 1 until semitones.size - 1) {
            val a = out[i - 1]; val b = out[i]; val c = semitones[i + 1]
            if (kotlin.math.abs(b - a) >= 7 && kotlin.math.abs(b - c) >= 7 && kotlin.math.abs(c - a) <= 4) out[i] = a
        }
        return out
    }

    /** 每个节拍点测一次真实音高；听不出音高的沿用前一个（鼓点不打断旋律）。 */
    private fun assignPitches(pcm: FloatArray, onsets: MutableList<Pair<Int, Shape>>) {
        val skip = PITCH_SKIP_MS * TARGET_RATE / 1000
        val raw = IntArray(onsets.size)
        var last = 72
        var pitched = 0
        for ((index, onset) in onsets.withIndex()) {
            // 音头被鼓点盖住时，往后再试一个窗口，两个都听不出才放弃。
            val hz = listOf(skip, skip * 3).firstNotNullOfOrNull { offset ->
                val from = (onset.first * HOP + offset).coerceAtMost(pcm.size - PITCH_WINDOW)
                if (from >= 0) pitchOf(pcm, from, PITCH_WINDOW).takeIf { it > 0f } else null
            } ?: -1f
            if (hz > 0f) { last = foldToPianoRange(hz); pitched++ }
            raw[index] = last
        }
        if (pitched == 0) {
            // 整首都听不出音高（纯鼓点/说唱）：沿五声音阶铺开，至少弹起来有起伏，
            // 绝不能全塌成同一个音 —— 那就是「叮叮叮只有一种声音」的样子。
            val pent = intArrayOf(60, 62, 64, 67, 69, 72, 74, 76)
            for (i in raw.indices) raw[i] = pent[(i * 3 + i / 4) % pent.size]
        }
        val semitones = smoothMelody(raw)
        // 音域几乎没变化时（比如纯鼓点），改用一个不会连续三次同列的可玩序列。
        val low = semitones.min(); val high = semitones.max()
        if (high - low < 2) {
            var column = 0
            for (i in onsets.indices) {
                column = (column + 1 + (i * 7) % 3) % 4
                onsets[i] = onsets[i].first to Shape(column, semitones[i])
            }
            return
        }
        for ((index, onset) in onsets.withIndex()) {
            val norm = (semitones[index] - low).toFloat() / (high - low)
            onsets[index] = onset.first to Shape((norm * 3.999f).toInt().coerceIn(0, 3), semitones[index])
        }
    }

    /* ------------------------------ 解码 ------------------------------ */

    /** 解码成单声道、11025Hz 的浮点采样，长度上限 MAX_SECONDS 秒。 */
    private fun decodeMono(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var mime = ""
            var channels = 1
            var sourceRate = 44100
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val type = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!type.startsWith("audio/")) continue
                track = i
                mime = type
                sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                channels = (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).coerceIn(1, 2)
                break
            }
            if (track < 0) throw IllegalArgumentException("这个文件里没有音轨")

            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            val capacity = TARGET_RATE * MAX_SECONDS
            val out = FloatArray(capacity)
            var written = 0

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val ratio = sourceRate.toDouble() / TARGET_RATE
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var accumulator = 0f
                var counted = 0
                var phase = 0.0

                while (!outputDone && written < capacity) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val total = shorts.remaining()
                            var offset = 0
                            while (offset + channels <= total && written < capacity) {
                                var sum = 0
                                for (c in 0 until channels) sum += shorts.get(offset + c).toInt()
                                accumulator += sum.toFloat() / channels / 32768f
                                counted++
                                phase += 1.0
                                // 按比例做块平均再抽取，相当于一个抗混叠的降采样。
                                if (phase >= ratio) {
                                    out[written++] = accumulator / counted
                                    accumulator = 0f
                                    counted = 0
                                    phase -= ratio
                                }
                                offset += channels
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return if (written == capacity) out else out.copyOf(written)
        } finally {
            extractor.release()
        }
    }
}
