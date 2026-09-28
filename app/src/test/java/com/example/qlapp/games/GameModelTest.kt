package com.example.qlapp.games

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GameModelTest {
    private fun advance(game: GameModel, seconds: Float) {
        repeat((seconds * 60).toInt()) { game.tick(1f / 60f) }
    }

    @Test fun pauseFreezesEveryGameAndResumeContinues() {
        GameKind.entries.forEach { kind ->
            val game = GameModel.create(kind)
            game.start()
            advance(game, .2f)
            game.pause()
            val time = game.elapsed
            advance(game, 3f)
            assertEquals(time, game.elapsed, 0f)
            game.resume()
            advance(game, .2f)
            assertTrue(game.elapsed > time)
        }
    }

    @Test fun pianoScoresOrderedKeysAndWrongKeyEndsWithScorePreserved() {
        val game = PianoGame(Random(1))
        game.start()
        val key = game.tiles.first()
        game.tap(key.col * 100f + 50f, key.y + 63f)
        assertEquals(1, game.hits)
        assertEquals(1, game.score)
        val next = game.tiles.first { !it.hit }
        game.tap(((next.col + 1) % 4) * 100f + 50f, next.y + 63f)
        assertEquals(Phase.FINISHED, game.phase)
        assertEquals(1, game.score)
        assertFalse(game.won)
    }

    @Test fun pianoMissingKeyEndsTheRunButHasNoTimerWin() {
        val game = PianoGame(Random(1))
        game.start()
        advance(game, 2f)
        assertEquals(Phase.FINISHED, game.phase)
        assertFalse(game.won)
    }

    @Test fun pianoContinuesPastThreeMinutesWithPlayableRows() {
        val game = PianoGame(Random(1))
        game.start()
        repeat(12000) {
            val key = game.tiles.first { !it.hit }
            if (key.y >= PianoGame.BOTTOM - game.rowHeight - 1f) game.tap(key.col * 100f + 50f, key.y + 63f)
            game.tick(1f / 60f)
            game.events.clear()
        }
        assertEquals(Phase.PLAYING, game.phase)
        assertTrue(game.elapsed > 180f)
        assertTrue(game.hits > 400)
        assertFalse(game.won)
    }

    @Test fun runnerContinuesPastOldFinishAndCyclesScenery() {
        val game = RunnerGame(Random(1)).also { it.start() }
        repeat(12000) {
            // Clear obstacles to isolate the distance/finish rule from player mistakes.
            game.obstacles.clear()
            game.tick(1f / 60f)
            game.events.clear()
            assertTrue(game.zone in 1..3)
            assertTrue(game.progress in 0f..1f)
        }
        assertTrue(game.distance > 2400f)
        assertTrue(game.district > 6)
        assertEquals(Phase.PLAYING, game.phase)
    }

    @Test fun airContinuesPastThirdBossAndLateBossHasSafeFireRate() {
        val game = AirGame(Random(1)).also { it.start() }
        repeat(12) {
            repeat(1700) {
                game.enemies.clear()
                game.threats.clear()
                game.tick(1f / 60f)
                game.events.clear()
            }
            val boss = requireNotNull(game.boss)
            assertTrue(boss.maxHp <= 400)
            boss.y = 150f
            boss.fire = 0f
            game.tick(1f / 60f)
            assertTrue(boss.fire >= .44f)
            boss.hp = 0
            game.tick(1f / 60f)
            assertEquals(Phase.PLAYING, game.phase)
            assertTrue(game.progress in 0f..1f)
        }
        assertEquals(13, game.stage)
        assertFalse(game.won)
    }

    private fun testSong(beats: List<SongBeat>) = Song("test", "测试", "单元测试", beats)

    /** 方块中部的高度。测试都按在这里，免得正好压在方块边界上。 */
    private val tileMid = PianoLanes.LINE - 45f

    @Test fun songPianoTapsScoreAndEmptyPressBreaksCombo() {
        val game = SongPianoGame(testSong(listOf(SongBeat(1000, 0, 0), SongBeat(2000, 1, 4))))
        game.start()
        repeat(60) { game.tick(1f / 60f) } // ≈ 1000ms，第一块到位
        game.press(0, tileMid)
        assertEquals(1, game.hits)
        assertEquals(1, game.combo)
        game.press(3, tileMid) // 空按：不扣机会，但连击断掉
        assertEquals(0, game.combo)
        assertEquals(3, game.lives)
        repeat(60) { game.tick(1f / 60f) }
        game.press(1, tileMid)
        assertEquals(2, game.hits)
        repeat(120) { game.tick(1f / 60f) } // 走到曲末
        assertEquals(Phase.FINISHED, game.phase)
        assertTrue(game.won)
    }

    @Test fun songPianoLongBlockNeedsACompleteHold() {
        // 完全不碰长条：真的漏了，要扣机会。
        val skipped = SongPianoGame(testSong(listOf(SongBeat(1000, 2, 7, 1200))))
        skipped.start()
        repeat(120) { skipped.tick(1f / 60f) }
        assertEquals(1, skipped.missed)
        assertEquals(2, skipped.lives)

        // 按住一小会儿就松手：官方的长按条不算错，只是拿不到满分。
        val early = SongPianoGame(testSong(listOf(SongBeat(1000, 2, 7, 1200))))
        early.start()
        repeat(60) { early.tick(1f / 60f) }
        early.press(2, tileMid)
        assertEquals(0, early.hits) // 还没松手，先不算数
        repeat(20) { early.tick(1f / 60f) }
        early.release(2) // 只按了约 330ms 就松手
        assertEquals("松手也要算接住", 1, early.hits)
        assertNotEquals(Judge.MISS, early.notes[0].result)
        assertEquals(3, early.lives)
        assertEquals(0, early.missed)

        val held = SongPianoGame(testSong(listOf(SongBeat(1000, 2, 7, 1200))))
        held.start()
        repeat(60) { held.tick(1f / 60f) }
        held.press(2, tileMid)
        repeat(80) { held.tick(1f / 60f) } // 按满 1200ms
        assertEquals(1, held.hits)
        assertEquals(0, held.missed)
        assertEquals(3, held.lives)
        assertTrue(held.score > 0)
    }

    @Test fun songPianoAcceptsAPressAnywhereOnTheBottomTile() {
        fun fresh() = SongPianoGame(testSong(listOf(SongBeat(1000, 0, 0), SongBeat(3000, 1, 4))))
            .also { g -> g.start(); repeat(60) { g.tick(1f / 60f) } }
        val probe = fresh()
        val tile = probe.target!!
        val top = tile.top(probe.nowMs)
        val bottom = tile.bottom(probe.nowMs)
        assertTrue("方块要有高度", top < bottom)
        // 别踩白块没有「判定线」：手指压在方块身上的任何高度都该算数。
        fresh().also { it.press(0, (top + bottom) / 2) }.let { assertEquals("按在方块正中间要算中", 1, it.hits) }
        fresh().also { it.press(0, top + 3f) }.let { assertEquals("按在方块顶边也要算中", 1, it.hits) }
        fresh().also { it.press(0, bottom - 3f) }.let { assertEquals("按在方块底边也要算中", 1, it.hits) }
    }

    @Test fun songPianoAllowsHittingTheBottomTileAsSoonAsItAppears() {
        // 抢拍也应该算中，只是评价低 —— 这是别踩白块和「等到线上再按」最本质的区别。
        val game = SongPianoGame(testSong(listOf(SongBeat(3000, 3, 0))))
        game.start()
        repeat(90) { game.tick(1f / 60f) } // 方块刚从屏幕顶部露头
        val tile = game.target!!
        val top = tile.top(game.nowMs)
        assertTrue("这时候方块应该刚露出来", top >= PianoLanes.TOP - 1f)
        game.press(3, top + 20f)
        assertEquals(1, game.hits)
        assertEquals(Judge.GOOD, tile.result)
    }

    @Test fun songPianoTreatsUpperTilesAndBlankSpaceAsWhite() {
        // 只有最靠下那块是黑键：点上面的块、点空白，都算踩白块。
        val game = SongPianoGame(testSong(listOf(SongBeat(1000, 2, 0), SongBeat(1600, 0, 4))))
        game.start()
        repeat(60) { game.tick(1f / 60f) }
        val upper = game.notes.last()
        val upperY = (upper.top(game.nowMs) + upper.bottom(game.nowMs)) / 2
        game.press(upper.column, upperY)
        assertEquals("上面的方块不能点", 0, game.hits)
        assertEquals(3, game.lives)
        game.press(1, 300f) // 空白列
        assertEquals("空白列不能点", 0, game.hits)
        assertEquals("踩白块只断连击，不扣机会", 3, game.lives)
    }

    @Test fun songPianoLongBlockShrinksFromTheFingerRightWhenPressed() {
        // 官方手感：在屏幕中间按住长条的那一刻，底边就钉在手指上、条从手指开始变短，
        // 而不是等它落到屏幕最底部才开始。
        val game = SongPianoGame(testSong(listOf(SongBeat(4000, 1, 7, 1200))))
        game.start()
        val note = game.notes.first()
        // 走到长条的下半截经过屏幕中间附近再按。
        while (note.bottom(game.nowMs) < 540f) game.tick(1f / 60f)
        val pressAt = 430f // 屏幕中间偏上，明确在方块身上
        assertTrue("按下位置要在方块身上", pressAt > note.top(game.nowMs) + 10f)
        assertTrue("按下时方块底边还没到屏幕最底", note.bottom(game.nowMs) < PianoLanes.FLOOR - 10f)
        game.press(1, pressAt)
        // 钉住的是手指位置，不是屏幕最底部。
        assertEquals(pressAt, note.pressY, 0.01f)
        // 可见长度 = 手指到顶边；要按完的长度就是这段。
        fun visible() = (note.pressY - note.top(game.nowMs)).coerceAtLeast(0f)
        assertEquals("要按完的长度 = 手指上方剩余长度", visible(), note.holdPx, 0.5f)
        // 按住一段时间：顶边每下降一点，条就立刻短一点（不用等到底部）。
        val before = visible()
        repeat(30) { game.tick(1f / 60f) } // 500ms
        val after = visible()
        assertEquals("变短量 ≈ 时间 × 速度", 500f * game.speed, before - after, 40f)
        // 按满整条：刚好归零，且算接住。
        while (!note.done) game.tick(1f / 60f)
        assertTrue("按完整条要刚好归零（${visible()}）", visible() < 4f)
        assertEquals(1, game.hits)
        assertEquals(0, game.missed)
        assertNotEquals(Judge.MISS, note.result)
        // 没人按的条沉到屏幕最底部才开始被吃掉（那是漏掉的情形）。
        assertEquals("没人按的条底边停在屏幕最底", PianoLanes.LINE, PianoLanes.FLOOR, 0f)
    }

    @Test fun songPianoLongBlockStaysSilentOnRelease() {
        // 按下时响一声就够了，松手不该再触发一次钢琴音。
        val game = SongPianoGame(testSong(listOf(SongBeat(1000, 1, 7, 1200))))
        game.start()
        repeat(60) { game.tick(1f / 60f) }
        game.events.clear()
        game.press(1, tileMid)
        val onPress = game.events.count { it.cue == Cue.NOTE }
        game.events.clear()
        repeat(20) { game.tick(1f / 60f) }
        game.release(1)
        assertEquals("按下要响一声", 1, onPress)
        assertEquals("松手不该再响", 0, game.events.count { it.cue == Cue.NOTE })
    }

    @Test fun songPianoLongBlockScoresLessWhenReleasedEarly() {
        // 松手早不扣分也不扣机会，只是分低 —— 按满整条必须明显更值钱。
        val quick = SongPianoGame(testSong(listOf(SongBeat(1000, 1, 7, 1200))))
        quick.start()
        repeat(60) { quick.tick(1f / 60f) }
        quick.press(1, tileMid)
        repeat(6) { quick.tick(1f / 60f) } // 只按了约 100ms
        quick.release(1)
        assertEquals(1, quick.hits)
        assertEquals(0, quick.missed)
        assertEquals(3, quick.lives)

        val full = SongPianoGame(testSong(listOf(SongBeat(1000, 1, 7, 1200))))
        full.start()
        repeat(60) { full.tick(1f / 60f) }
        full.press(1, tileMid)
        repeat(80) { full.tick(1f / 60f) } // 按满整条
        assertEquals(1, full.hits)
        assertTrue("按满整条分数要更高（${full.score} vs ${quick.score}）", full.score > quick.score)
    }

    @Test fun songPianoDensePassagesSpeedUpInsteadOfOverlapping() {
        // 一串间隔只有 120ms 的密集音符：按基准速度方块间距只有约 70，会叠在一起。
        // 做法是局部加速把间距撑开，而不是压扁方块。
        val beats = (0..8).map { SongBeat(1000 + it * 120, it % 4, it % 12) }
        val game = SongPianoGame(testSong(beats))
        game.start()
        for (i in 0 until game.notes.size - 1) {
            val gap = game.notes[i + 1].posStart - game.notes[i].posStart
            assertTrue("相邻方块间距 $gap 不能小于一块高", gap >= SongPianoGame.TILE_H)
        }
        // 每块仍然正好在自己的拍点落到底 —— 变速只改间距，不改 timing。
        val n = game.notes[5]
        assertEquals(PianoLanes.LINE, n.bottom(n.timeMs.toFloat()), 0.5f)
        // 密集段确实比基准速度快。
        assertTrue("密集段要加速（${game.maxSpeed} vs ${game.speed}）", game.maxSpeed > game.speed * 1.2f)
        // 稀疏段不被带快：首尾之外留一个大间隔，速度回到基准。
        val calm = SongPianoGame(testSong(listOf(SongBeat(1000, 0, 0), SongBeat(3000, 1, 4))))
        assertEquals(calm.speed, calm.maxSpeed, 0.0001f)
    }

    @Test fun builtinSongsArePlayableAndContainLongBlocks() {
        BuiltinSongs.all().forEach { song ->
            assertTrue(song.noteCount > 20)
            assertTrue("下落速度要看得清", song.scrollPxPerMs in 0.05f..1f)
            assertTrue(song.beats.all { it.column in 0..3 })
            assertTrue("内置曲目的音高要映射到不同的琴键", song.beats.map { pianoIndex(it.semitone) }.distinct().size >= 4)
            assertTrue(song.beats.zipWithNext { a, b -> b.timeMs > a.timeMs }.all { it })
            // 长按条必须在下一个方块落下来之前就收尾，不然两块会叠在一起。
            song.beats.forEachIndexed { index, beat ->
                if (!beat.isHold) return@forEachIndexed
                val next = song.beats.getOrNull(index + 1)?.timeMs ?: Int.MAX_VALUE
                assertTrue("长按条压到了下一个方块", beat.timeMs + beat.holdMs <= next - 100)
            }
        }
        assertTrue("小星星每个乐句收尾都该是长音", BuiltinSongs.all().first().holdCount >= 6)
    }

    @Test fun pianoIndexMapsAnyPitchIntoThePreloadedScale() {
        // 音色库是完整半音阶（C4..C6）：semitone 用绝对高度（60=C4），一一对应，任何调都按原音高弹。
        assertEquals(25, PianoScale.size)
        assertEquals(0, pianoIndex(60))
        assertEquals(12, pianoIndex(72))
        assertEquals(24, pianoIndex(84))
        assertEquals(24, pianoIndex(99))  // 超出上沿截断，不会缠回错误的音
        assertEquals(0, pianoIndex(-1))   // 没测到音高的兜底
    }

    @Test fun smoothMelodyOnlyFixesOctaveGlitches() {
        // 前后都接近、只有自己跳了一个八度的，是测音高的毛刺，拉回来。
        val cleaned = AudioAnalyzer.smoothMelody(intArrayOf(60, 62, 74, 64, 65))
        assertEquals(62, cleaned[2])
        // 持续上行的大跳进是真旋律，不能动。
        val legit = AudioAnalyzer.smoothMelody(intArrayOf(60, 64, 72, 76, 79))
        assertEquals(64, legit[1])
        assertEquals(72, legit[2])
        assertEquals(76, legit[3])
    }

    @Test fun pitchOfReadsRealNotesFromImportedAudio() {
        // 导入歌曲靠的是真音高：正弦波 → 对应半音，白噪声 → 听不出音高。
        val rate = 11025
        fun sine(hz: Float): FloatArray {
            val n = rate / 2
            return FloatArray(n) { kotlin.math.sin(2 * Math.PI * hz * it / rate).toFloat() * .8f }
        }
        val a4 = AudioAnalyzer.pitchOf(sine(440f), 0, 1024, rate)
        assertEquals("A4 要测出 440Hz", 440f, a4, 5f)
        assertEquals(69, AudioAnalyzer.foldToPianoRange(a4))
        val c4 = AudioAnalyzer.pitchOf(sine(261.63f), 0, 1024, rate)
        assertEquals("C4 要测出 261.63Hz", 261.63f, c4, 4f)
        assertEquals(60, AudioAnalyzer.foldToPianoRange(c4))
        // 折叠：超出两个八度的音保持音名、搬进 C4..C6，旋律轮廓不变。
        assertEquals(84, AudioAnalyzer.foldToPianoRange(1046.5f))  // C6 正好在上沿
        assertEquals(60, AudioAnalyzer.foldToPianoRange(65.4f))    // C2 → C4（第一个进区间的八度）
        assertEquals(81, AudioAnalyzer.foldToPianoRange(1760f))    // A6 → A5
        // 白噪声没有明确音高，要识别成「无音高」而不是瞎猜一个。
        val random = java.util.Random(7)
        val noise = FloatArray(4096) { random.nextFloat() * 2 - 1 }
        assertEquals(-1f, AudioAnalyzer.pitchOf(noise, 0, 1024, rate))
    }

    @Test fun importedMelodyKeepsItsTuneEndToEnd() {
        // 端到端钉死「导入的歌弹出来是它的旋律」：合成一段已知旋律（C E G A G E C，
        // 带泛音和指数衰减模拟钢琴音色，再掺一点噪声），跑完整条分析流水线，
        // 出来的关卡音高轮廓必须还是这段旋律 —— 绝不许塌成「叮叮叮」一个音。
        val rate = 11025
        val notes = intArrayOf(60, 64, 67, 69, 67, 64, 60)
        fun hz(semitone: Int) = 440f * Math.pow(2.0, (semitone - 69) / 12.0).toFloat()
        val noteLen = rate * 2 / 5  // 每个音 400ms
        val gap = rate / 10         // 音与音之间 100ms 安静，能量才有起伏
        val pcm = FloatArray(notes.size * (noteLen + gap) + rate)
        val rnd = java.util.Random(3)
        notes.forEachIndexed { index, semitone ->
            val f = hz(semitone)
            val start = index * (noteLen + gap)
            for (i in 0 until noteLen) {
                val t = i.toDouble() / rate
                val env = Math.min(1.0, t / .004) * Math.exp(-t / .5)
                pcm[start + i] = ((Math.sin(2 * Math.PI * f * t) + .4 * Math.sin(4 * Math.PI * f * t) + .15 * Math.sin(6 * Math.PI * f * t)) * env * .6).toFloat() + (rnd.nextFloat() - .5f) * .01f
            }
        }
        val beats = AudioAnalyzer.analyzePcm(pcm)
        assertTrue("要识别出足够多的节拍（${beats.size}）", beats.size >= 5)
        val semitones = beats.map { it.semitone }
        assertTrue("绝不能全塌成一个音：$semitones", semitones.distinct().size >= 3)
        // 一个音可能被能量抖动拆成相邻两块，把连续重复的音折叠掉再比轮廓。
        val contour = semitones.fold(mutableListOf<Int>()) { acc, s -> if (acc.isEmpty() || acc.last() != s) acc.add(s); acc }
        assertTrue("折叠后的轮廓要接近原旋律：$contour", contour.size >= 5)
        // 音高轮廓要和原旋律一致：第 2 个比第 1 个高 3~5 半音，第 3 个再高 2~4，第 4 个再高 1~3。
        assertTrue("轮廓要对上原旋律：$contour", contour[1] - contour[0] in 3..5)
        assertTrue("轮廓要对上原旋律：$contour", contour[2] - contour[1] in 2..4)
        assertTrue("轮廓要对上原旋律：$contour", contour[3] - contour[2] in 1..3)
        // 每一个音高都映射到不同的琴键音色，不存在「只有一个声音」。
        val keys = beats.map { pianoIndex(it.semitone) }.distinct()
        assertTrue("琴键音色要随旋律变化：$keys", keys.size >= 3)
    }

    @Test fun juebieshuCustomLevelMatchesTheTranscription() {
        val song = BuiltinSongs.all().first { it.id == "builtin.juebieshu" }
        // 扒带数据：开头是 A#4 → A4 → G#4 → D4（D4 是第一个长音）。
        assertEquals(70, song.beats[0].semitone)
        assertEquals(69, song.beats[1].semitone)
        assertEquals(68, song.beats[2].semitone)
        assertEquals(62, song.beats[3].semitone)
        assertTrue("开头长音要做成长方块", song.beats[3].isHold)
        assertEquals(2600, song.beats[0].timeMs)
        // 整首的琴键跨度要足够大，绝不能塌成几个音。
        val keys = song.beats.map { pianoIndex(it.semitone) }.distinct()
        assertTrue("琴键跨度要足够大：$keys", keys.size >= 10)
        assertTrue("一整首歌的体量（${song.noteCount}）", song.noteCount >= 350)
        assertTrue("要有足够的长条（${song.holdCount}）", song.holdCount >= 60)
    }

    @Test fun recordsKeepOriginalOwnerOnTieAndOnlyChangeForHigherScore() {
        val initial = GameRecord().improvedBy(100, "小江")
        assertEquals(initial, initial.improvedBy(100, "甜甜"))
        assertEquals(initial, initial.improvedBy(80, "甜甜"))
        assertEquals(GameRecord(150, "甜甜"), initial.improvedBy(150, "甜甜"))
        assertTrue(GameRecord().label.contains("暂无纪录"))
        assertTrue(GameRecord(90).label.contains("未记录昵称"))
    }

    @Test fun runnerAllowsTwoJumpsThenResetsOnLanding() {
        val game = RunnerGame(Random(1))
        game.start()
        game.tap(200f, 400f)
        advance(game, .1f)
        game.tap(200f, 400f)
        advance(game, .1f)
        val velocity = game.velocity
        game.tap(200f, 400f)
        assertEquals(2, game.jumps)
        assertEquals(velocity, game.velocity, 0f)
        advance(game, 1.2f)
        assertEquals(0, game.jumps)
        assertEquals(0f, game.height, 0f)
    }

    @Test fun runnerSlideClearsBeamButStandingHitsIt() {
        val standing = RunnerGame(Random(1)).also { it.start() }
        standing.obstacles.add(Obstacle(100f, true))
        standing.tick(1f / 60f)
        assertEquals(2, standing.lives)
        val sliding = RunnerGame(Random(1)).also { it.start(); it.skill() }
        sliding.obstacles.add(Obstacle(100f, true))
        sliding.tick(1f / 60f)
        assertEquals(3, sliding.lives)
    }

    @Test fun airPulseClearsEnemiesAndThreatsAndIsLimited() {
        val game = AirGame(Random(1))
        game.start()
        game.enemies.add(Enemy(150f, 300f, 8, true))
        game.threats.add(Shot(200f, 400f))
        game.skill()
        assertTrue(game.enemies.isEmpty())
        assertTrue(game.threats.isEmpty())
        assertEquals(1, game.kills)
        repeat(5) { game.skill() }
        assertEquals(0, game.pulses)
        assertEquals(1, game.kills)
    }

    @Test fun airMovementClampsToPlayableArea() {
        val game = AirGame(Random(1))
        game.start()
        game.move(-10000f, 10000f)
        assertEquals(34f, game.x, 0f)
        assertEquals(684f, game.y, 0f)
        game.move(10000f, -10000f)
        assertEquals(366f, game.x, 0f)
        assertEquals(36f, game.y, 0f)
    }

    @Test fun airCanReachEveryEdgeOfTallAndWideScreens() {
        val game = AirGame(Random(1)).also { it.start() }
        game.setArena(0f, -100f, 400f, 820f)
        game.move(0f, -10000f)
        assertEquals(-64f, game.y, 0f)
        game.tick(1f / 60f)
        assertTrue("Bullets must remain visible above the old canvas", game.shots.isNotEmpty())
        game.move(0f, 10000f)
        assertEquals(784f, game.y, 0f)
        game.supplies.add(Supply(100f, 760f, 0))
        game.threats.add(Shot(100f, 760f, vy=100f))
        game.tick(1f / 60f)
        assertTrue(game.supplies.isNotEmpty())
        assertTrue(game.threats.isNotEmpty())
        game.setArena(-80f, 0f, 480f, 720f)
        game.move(-10000f, 0f)
        assertEquals(-46f, game.x, 0f)
        game.move(10000f, 0f)
        assertEquals(446f, game.x, 0f)
        assertTrue(game.y <= 684f)
    }
}
