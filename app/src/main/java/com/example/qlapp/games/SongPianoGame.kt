package com.example.qlapp.games

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 命中评价。 */
enum class Judge { PERFECT, GREAT, GOOD, MISS }

/**
 * 曲目模式沿用街机那一套 400×720 世界坐标。
 *
 * 这里没有「判定线」这个玩法概念 —— 别踩白块的规则是：
 * 只有最靠下那块是当前的黑键，手指压在它身上就算数，压到别处就是踩白块。
 * LINE 只是「正拍位置」，用来算方块画在哪、以及评级：
 * 方块底边走到 LINE 时按下评级最高，早按晚按都算中，只是评价低一点。
 *
 * LINE 必须等于 FLOOR（屏幕最底部）。它俩一旦有差值，方块沉底时底边会停在半空、
 * 底下留一道空隙，而且长按条永远剩最后那一截吃不掉 —— 看着就不像「顺着手指变短」。
 */
object PianoLanes {
    const val TOP = 110f      // 可见区域上沿
    const val LINE = 669f     // 正拍位置：方块底边走到屏幕最底部时手感最好
    const val FLOOR = 669f    // 可见区域下沿
    const val ROW = 139.75f   // 一行的高度，用来标出「最下面一行」
    val BAND = FLOOR - ROW    // 最下面一行的上沿，只用于画高亮
}

/**
 * 下落轨道：把「时间」换算成「滚动距离」。
 *
 * 稀疏的段落用基准速度；相邻两块太近、按基准速度会叠在一起时，
 * 这一段就局部加速把间距撑开 —— 下落速度本来就该跟着音乐密度变化，
 * 而不是从头到尾一个速度、靠压扁方块硬凑。
 *
 * 关键是每块方块都锚定自己的拍点：bottom = LINE - (posAt(timeMs) - posAt(now))，
 * 所以不管怎么变速，每一块都正好在自己的时刻落到底，音乐不会跑偏。
 */
class ScrollTrack(beatTimesMs: List<Int>, val baseSpeed: Float) {
    private val bounds: FloatArray
    private val speeds: FloatArray
    private val cumP: FloatArray

    /** 全程最快的段落速度。 */
    val maxSpeed: Float

    init {
        val distinct = beatTimesMs.distinct().sorted()
        bounds = FloatArray(distinct.size) { distinct[it].toFloat() }
        val n = (bounds.size - 1).coerceAtLeast(0)
        speeds = FloatArray(n)
        cumP = FloatArray(bounds.size)
        var max = baseSpeed
        for (i in 0 until n) {
            val gap = bounds[i + 1] - bounds[i]
            // 这一段的间距至少要放得下一块方块加一条缝；放不下就加速。
            speeds[i] = max(baseSpeed, (SongPianoGame.TILE_H + GAP_MIN) / gap)
            if (speeds[i] > max) max = speeds[i]
            cumP[i + 1] = cumP[i] + speeds[i] * gap
        }
        maxSpeed = max
    }

    private fun segment(t: Float): Int {
        if (bounds.isEmpty() || t <= bounds[0]) return -1
        for (i in speeds.indices) if (t < bounds[i + 1]) return i
        return speeds.size
    }

    /** 时刻 t 对应的滚动距离。 */
    fun posAt(t: Float): Float {
        if (bounds.isEmpty()) return baseSpeed * t
        val k = segment(t)
        return when {
            k < 0 -> cumP[0] + baseSpeed * (t - bounds[0])
            k >= speeds.size -> cumP[bounds.size - 1] + baseSpeed * (t - bounds[bounds.size - 1])
            else -> cumP[k] + speeds[k] * (t - bounds[k])
        }
    }

    companion object {
        const val GAP_MIN = 10f   // 相邻两块之间最少留的缝
    }
}

/**
 * 一个音符。方块的 y 坐标完全由「当前时间」算出来，
 * 所以掉帧也好、音效延迟也好，画面永远不会和音乐跑偏。
 */
class SongNote(
    val timeMs: Int,
    val holdMs: Int,
    val column: Int,
    val semitone: Int,
    val height: Float,
    /** 这块方块在轨道上的滚动距离（它拍点时刻的累计位移）。 */
    val posStart: Float,
    private val track: ScrollTrack,
) {
    val isHold get() = holdMs > 0
    val endMs get() = timeMs + holdMs

    var done = false
    /** 定格的那一下，用来播命中后的消失动画。 */
    var doneMs = 0f
    var holding = false
    var result: Judge? = null
    var pressDelta = 0f
    /** 长按按住的比例（0..1），把手指上方剩下的条按完就是 1。 */
    var held = 0f
    /** 按住长条时手指压的高度：条的底边钉在这里，从手指开始被一点点吃掉。 */
    var pressY = -1f
    /** 按住那一刻轨道的滚动距离。 */
    var pressPos = 0f
    /** 手指上方剩下的条长（滚动距离），轨道滚完这段就算按满整条。 */
    var holdPx = 0f

    /** 底边位置：拍点那一刻正好落在 LINE。变速只改方块间距，不改每块的拍点。 */
    fun bottom(now: Float) = PianoLanes.LINE - (posStart - track.posAt(now))
    fun top(now: Float) = bottom(now) - height
}

/**
 * 曲目模式：跟着一首曲子的时间轴下落方块。
 *
 * 两种方块：
 *  · 普通块 —— 手指压在它身上就算中；
 *  · 长方块 —— 压住不放，条从手指的位置开始一点点变短，
 *    走完就能松手；中途松手也不算错，只是分少一些。
 *
 * 下落速度跟着密度变化：密的段落自动加速、稀的段落回到基准速，
 * 方块永远不会叠在一起，也每块都正好在自己的拍点上落到底。
 *
 * 判定是空间判定（手指压没压在最下面那块上），不是时间窗。
 * 空按不扣机会但会断连击并倒扣一点分，避免乱按也能拿高分。
 */
class SongPianoGame(val song: Song, random: Random = Random.Default) : GameModel(GameKind.PIANO, random) {

    /** 基准下落速度：稀疏段落的速度。密集段落会局部加速，见 track。 */
    val speed = song.scrollPxPerMs
    /** 下落轨道：把时间换算成滚动距离，密集的段落自动加速。 */
    val track = ScrollTrack(song.beats.map { it.timeMs }, song.scrollPxPerMs)
    /** 全程最快段落的速度。 */
    val maxSpeed get() = track.maxSpeed
    val notes: List<SongNote>
    val finishMs: Int

    var nowMs = 0f; private set
    var hits = 0; private set
    var combo = 0; private set
    var maxCombo = 0; private set
    var perfect = 0; private set
    var missed = 0; private set
    var lives = 3; private set
    var lastJudge: Judge? = null; private set
    var judgeAge = 0f; private set


    init {
        val built = ArrayList<SongNote>(song.beats.size)
        for (i in song.beats.indices) {
            val beat = song.beats[i]
            val hold = beat.holdMs.coerceIn(0, 2400)
            val posStart = track.posAt(beat.timeMs.toFloat())
            // 与下一块之间的可用空间（滚动距离）。密集段已经被 track 加速撑开，
            // 这里只是兜底，不会再出现两块叠在一起。
            val posNext = if (i + 1 < song.beats.size) track.posAt(song.beats[i + 1].timeMs.toFloat())
                else posStart + 1600 * speed
            val room = (posNext - posStart - 8f).coerceAtLeast(TILE_H * 0.8f)
            val height = if (hold > 0) {
                (track.posAt(beat.timeMs + hold.toFloat()) - posStart).coerceIn(TILE_H * 0.8f, room)
            } else {
                TILE_H.coerceAtMost(room)
            }
            built.add(SongNote(beat.timeMs, hold, beat.column.coerceIn(0, 3), beat.semitone, height, posStart, track))
        }
        notes = built
        finishMs = max(song.durationMs, notes.maxOfOrNull { it.endMs + 900 } ?: 0)
    }

    override val status get() = "连击 $combo    最长 $maxCombo    剩余 $lives 次机会"
    override val summary get() = "命中 $hits / ${notes.size} 块  ·  最长连击 $maxCombo  ·  PERFECT $perfect"
    override val progress get() = (nowMs / finishMs.toFloat()).coerceIn(0f, 1f)

    /** 当前该点的那块：未完成方块里最靠下的一个（按时间排，最早的自然在最下面）。 */
    val target: SongNote? get() = notes.firstOrNull { !it.done }

    /**
     * 按下。判定的不是「有没有对上某个时刻」，而是「手指压没压在最下面那块上」。
     * worldY 是手指在世界坐标里的高度，用来确认按在方块身上而不是空白处。
     */
    fun press(column: Int, worldY: Float = PianoLanes.LINE) {
        if (phase != Phase.PLAYING || column !in 0..3) return
        val note = target
        // 别踩白块的核心规则：只有最靠下那块能点，点别的块、点空白都算踩白块。
        if (note == null || note.column != column) { white(column); return }
        // 这根长条已经在按着了：多指或重复按下不能把进度清零重来。
        if (note.holding) return
        // SLACK 是给手指粗细留的余量：压在方块边缘外一点点也算数，
        // 否则正拍那一瞬间底边正好等于手指高度，浮点差一点就被判成白块。
        val top = max(note.top(nowMs), PianoLanes.TOP) - SLACK
        val bottom = note.bottom(nowMs) + SLACK
        if (worldY < top || worldY > bottom) { white(column); return }
        note.pressDelta = abs(nowMs - note.timeMs)
        if (note.isHold) {
            note.holding = true
            // 官方的长按条是「顺着手指变短」：手指下面那截立刻被吃掉，底边钉在手指上，
            // 剩下的部分继续往手指这边走。要按多久取决于手指上方还剩多长，
            // 在屏幕中间按住也一样 —— 而不是死板地等它滚到屏幕最底才开始算。
            val topEdge = note.top(nowMs)
            note.pressY = worldY.coerceIn(topEdge, note.bottom(nowMs))
            note.pressPos = track.posAt(nowMs)
            note.holdPx = (note.pressY - topEdge).coerceAtLeast(8f)
            events.add(GameEvent(Cue.NOTE, pianoIndex(note.semitone)))
        } else {
            hit(note, note.pressDelta, 10)
        }
    }

    fun release(column: Int) {
        if (phase != Phase.PLAYING || column !in 0..3) return
        val note = notes.firstOrNull { it.holding && it.column == column } ?: return
        // 官方的长按条：松手永远不算错，按了多久就给多少分。
        // 只有从头到尾没碰过它才算漏。
        complete(note, note.held.coerceIn(0f, 1f))
    }

    override fun update(dt: Float) {
        nowMs += dt * 1000f
        // 开头 2.6 秒是准备时间，方块还在屏幕上方，先给一句提示免得玩家以为没开始。
        if (nowMs < 80f) announce("只点最下面那块")
        if (judgeAge > 0f) judgeAge = max(0f, judgeAge - dt * 2.2f)

        for (note in notes) {
            if (note.done) continue
            if (note.holding) {
                // 条从手指的位置开始被吃：轨道滚过 holdPx 那段（顶边走到 pressY）就算按满。
                note.held = ((track.posAt(nowMs) - note.pressPos) / note.holdPx).coerceIn(0f, 1f)
                if (note.held >= 1f) complete(note)
                continue
            }
            // 整块沉出屏幕底部才算漏。留给「按晚了」的余量比判定窗宽松得多。
            if (nowMs > note.timeMs + LATE_MS) fail(note)
        }

        if (nowMs >= finishMs) {
            val ratio = hits.toFloat() / notes.size.coerceAtLeast(1)
            finish(true, when {
                ratio >= 0.98f -> "全连！${song.title} 被你完整弹下来了。"
                ratio >= 0.85f -> "弹得很稳，${song.title} 基本拿下了。"
                else -> "${song.title} 弹完了，再练一遍会更顺。"
            })
        }
    }

    /** 踩到白块：不扣机会，但连击断了，还要倒扣一点分。 */
    private fun white(column: Int) {
        combo = 0
        score = max(0, score - 2)
    }

    /** 贴着正拍按是 PERFECT，抢拍和拖拍都算中，只是评价低。 */
    private fun grade(delta: Float) = when {
        delta <= 80f -> Judge.PERFECT
        delta <= 200f -> Judge.GREAT
        else -> Judge.GOOD
    }

    /**
     * partial 只有长按会用：按得不满就降一级评价，让玩家知道还能按得更久。
     * sound=false 用于长按收尾：按下那一刻已经响过了，松手不该再来一声。
     */
    private fun hit(note: SongNote, delta: Float, base: Int, partial: Float = 1f, sound: Boolean = true) {
        note.done = true
        note.doneMs = nowMs
        note.holding = false
        note.result = if (partial < .9f && grade(delta) != Judge.GOOD) {
            if (grade(delta) == Judge.PERFECT) Judge.GREAT else Judge.GOOD
        } else grade(delta)
        hits++
        combo++
        maxCombo = max(maxCombo, combo)
        if (note.result == Judge.PERFECT) perfect++
        score += base + min(60, combo)
        lastJudge = note.result
        judgeAge = 1f
        if (sound) events.add(GameEvent(Cue.NOTE, pianoIndex(note.semitone)))
    }

    /** partial 是按住的比例：按满拿满，按一半拿一半，但都不算错。 */
    private fun complete(note: SongNote, partial: Float = 1f) {
        if (!note.holding) return
        hit(note, note.pressDelta, (HOLD_SCORE * partial).toInt().coerceAtLeast(6), partial, sound = false)
    }

    private fun fail(note: SongNote) {
        note.done = true
        note.doneMs = nowMs
        note.holding = false
        note.result = Judge.MISS
        combo = 0
        missed++
        lives--
        shake = 9f
        lastJudge = Judge.MISS
        judgeAge = 1f
        events.add(GameEvent(Cue.HURT))
        if (lives <= 0) {
            finish(
                false,
                if (note.isHold) "长方块要按住不放。听到长音再松手，再来一次。"
                else "错过了太多方块。先完整听一遍这首歌，跟着旋律上手会顺很多。"
            )
        }
    }

    companion object {
        const val LATE_MS = 190f    // 方块沉出底部之后还能救回来的时间
        const val SLACK = 12f       // 判定的边缘容差，给手指粗细留的余量
        const val HOLD_SCORE = 25f   // 长按条按满能拿到的基础分
        const val TILE_H = 104f      // 普通方块的高度
    }
}
