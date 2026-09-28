package com.example.qlapp.games

import kotlin.math.*
import kotlin.random.Random

enum class GameKind(val title: String, val subtitle: String, val instructions: String) {
    AIR("飞机大战", "STELLAR / 星际突袭", "无尽模式：拖动飞船，自动开火。拾取补给、使用脉冲清除弹幕。每击败一名守卫进入下一星域，生命耗尽时结算。"),
    PIANO("别踩白块", "SONGS / 曲目与长按", "选一首曲子，跟着节奏按下方块；遇到长条要按住不放，直到它走完。也可以导入自己的音乐，自动生成关卡。"),
    RUNNER("跑酷", "SUNSET / 落日快递", "无尽模式：点击跳跃，再点二段跳；下滑或按滑铲键穿过横梁。收集星币，在循环街区中挑战更远距离，生命耗尽时结算。"),
}
/** 街机模式专用的说明。别踩白块的 instructions 现在写的是曲目模式，两边文案不一样。 */
val GameKind.arcadeInstructions: String
    get() = if (this == GameKind.PIANO)
        "街机模式：4 行 4 列，画面一直往下落并且越来越快。只按最下面那块黑的，踩到白块或漏掉一块就结束。"
    else instructions

enum class Phase { READY, PLAYING, PAUSED, FINISHED }
enum class Cue {
    SHOT, EXPLOSION, HURT, PICKUP, JUMP, NOTE, WIN, LOSE,
    // 五子棋：棋子落盘的木质「嗒」（我 / 对方音色略不同）、提示音，
    // 以及五声音阶（宫商角徵羽）的胜 / 负 / 和棋。只往后追加，不动前面的音色——
    // 改了旧 cue 的合成方式，已经落在 cacheDir 里的 wav 就是错的。
    PLACE, PLACE_RIVAL, CHIME, GOMOKU_WIN, GOMOKU_LOSE, GOMOKU_DRAW,
}
data class GameEvent(val cue: Cue, val note: Int = 0)
data class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val gold: Boolean)

/** All positions use a 400 x 720 world; the renderer and input share one transform. */
abstract class GameModel(val kind: GameKind, protected val random: Random = Random.Default) {
    var phase = Phase.READY
        private set
    var score = 0
        protected set
    var elapsed = 0f
        protected set
    var won = false
        private set
    var reason = ""
        private set
    var notice = ""
        protected set
    var noticeTime = 0f
        protected set
    var shake = 0f
        protected set
    val sparks = mutableListOf<Spark>()
    val events = mutableListOf<GameEvent>()
    abstract val status: String
    abstract val summary: String
    abstract val progress: Float
    fun start() { if (phase == Phase.READY) phase = Phase.PLAYING }
    fun pause() { if (phase == Phase.PLAYING) phase = Phase.PAUSED }
    fun resume() { if (phase == Phase.PAUSED) phase = Phase.PLAYING }
    fun tick(dt: Float) {
        if (phase != Phase.PLAYING) return
        val step = dt.coerceIn(0f, 1f / 30f)
        elapsed += step
        noticeTime = max(0f, noticeTime - step)
        shake = max(0f, shake - step * 18f)
        sparks.forEach { it.life -= step; it.x += it.vx * step; it.y += it.vy * step; it.vy += step * 35f }
        sparks.removeAll { it.life <= 0f }
        update(step)
    }
    protected abstract fun update(dt: Float)
    open fun tap(x: Float, y: Float) {}
    open fun move(dx: Float, dy: Float) {}
    open fun skill() {}
    protected fun finish(victory: Boolean, message: String) {
        if (phase != Phase.PLAYING) return
        phase = Phase.FINISHED; won = victory; reason = message
        events.add(GameEvent(if (victory) Cue.WIN else Cue.LOSE))
    }
    protected fun announce(text: String) { notice = text; noticeTime = 2f }
    protected fun burst(x: Float, y: Float, gold: Boolean = true, count: Int = 16) {
        repeat(count) {
            val angle = random.nextFloat() * PI.toFloat() * 2
            val speed = 30 + random.nextFloat() * 130
            sparks.add(Spark(x, y, cos(angle)*speed, sin(angle)*speed, .3f+random.nextFloat()*.6f, gold))
        }
        while (sparks.size > 220) sparks.removeAt(0)
    }
    companion object {
        fun create(kind: GameKind): GameModel = when (kind) {
            GameKind.AIR -> AirGame()
            GameKind.PIANO -> PianoGame()
            GameKind.RUNNER -> RunnerGame()
        }
    }
}

data class Shot(var x: Float, var y: Float, val vx: Float = 0f, val vy: Float = -520f, var spent: Boolean = false)
data class Enemy(var x: Float, var y: Float, var hp: Int, val heavy: Boolean, val baseX: Float = x, var age: Float = 0f, var fire: Float = 1.8f) {
    val radius get() = if (heavy) 23f else 17f
}
data class Boss(var x: Float = 200f, var y: Float = -60f, var hp: Int, val maxHp: Int = hp, var age: Float = 0f, var fire: Float = 2f)
data class Supply(var x: Float, var y: Float, val type: Int)

class AirGame(random: Random = Random.Default) : GameModel(GameKind.AIR, random) {
    var arenaLeft = 0f; private set
    var arenaTop = 0f; private set
    var arenaRight = 400f; private set
    var arenaBottom = 720f; private set
    fun setArena(left: Float, top: Float, right: Float, bottom: Float) {
        require(right - left >= 68f && bottom - top >= 72f)
        arenaLeft = left; arenaTop = top; arenaRight = right; arenaBottom = bottom
        x = x.coerceIn(left + 34f, right - 34f)
        y = y.coerceIn(top + 36f, bottom - 36f)
    }
    var x = 200f; private set
    var y = 548f; private set
    var lives = 3; private set
    var weapon = 1; private set
    var pulses = 3; private set
    var stage = 1; private set
    var kills = 0; private set
    var combo = 0; private set
    var shield = 0f; private set
    var invulnerable = 2f; private set
    var flash = 0f; private set
    val enemies = mutableListOf<Enemy>()
    val shots = mutableListOf<Shot>()
    val threats = mutableListOf<Shot>()
    val supplies = mutableListOf<Supply>()
    var boss: Boss? = null; private set
    private var stageTime = 0f
    private var bossSpawned = false
    private var fire = 0f
    private var spawn = .9f
    private var comboTime = 0f
    private val difficulty get() = min(stage, 6)
    override val status get() = "无尽 · 星域 $stage    火力 LV.$weapon    连击 $combo"
    override val summary get() = "击落 $kills 架  ·  抵达第 $stage 星域"
    override val progress get() = if (bossSpawned) .65f + .35f*(1f-(boss?.let { it.hp.toFloat()/it.maxHp } ?: 0f)) else min(.65f,stageTime/28f*.65f)
    override fun move(dx: Float, dy: Float) {
        if (phase != Phase.PLAYING) return
        // Only the ship's own size limits movement, not an invisible menu area.
        x = (x+dx).coerceIn(arenaLeft+34f,arenaRight-34f)
        y = (y+dy).coerceIn(arenaTop+36f,arenaBottom-36f)
    }
    private fun hurt() {
        if (invulnerable > 0 || shield > 0 || phase != Phase.PLAYING) return
        lives--; invulnerable=2.2f; weapon=max(1,weapon-1); combo=0; shake=7f
        burst(x,y,false); events.add(GameEvent(Cue.HURT))
        if (lives==0) finish(false,"机体受损。调整航线，再出发一次。")
    }
    private fun destroy(e: Enemy) {
        kills++; combo++; comboTime=2.6f
        score += (if(e.heavy) 100 else 35) * min(4, 1+combo/8)
        burst(e.x,e.y,true,if(e.heavy) 26 else 15); events.add(GameEvent(Cue.EXPLOSION))
        if(kills%6==0) supplies.add(Supply(e.x,e.y,when { kills%18==0 -> 2; kills%12==0 -> 1; else -> 0 }))
    }
    override fun skill() {
        if(phase!=Phase.PLAYING || pulses<=0)return
        pulses--; flash=.6f; shake=5f; threats.clear()
        enemies.forEach(::destroy); enemies.clear()
        boss?.let { it.hp-=90 }
        events.add(GameEvent(Cue.EXPLOSION))
        checkBoss()
    }
    private fun checkBoss() {
        val b=boss ?: return
        if(b.hp>0)return
        burst(b.x,b.y,true,70); score+=stage*1500; flash=.5f; shake=10f; threats.clear(); boss=null
        lives=min(3,lives+1); events.add(GameEvent(Cue.EXPLOSION))
        stage++; stageTime=0f; bossSpawned=false; spawn=2f; pulses=min(3,pulses+1); invulnerable=3f; announce("星域 $stage · 航道已开启")
    }
    override fun update(dt: Float) {
        stageTime+=dt; fire-=dt; spawn-=dt; comboTime-=dt
        if(comboTime<=0)combo=0
        invulnerable=max(0f,invulnerable-dt); shield=max(0f,shield-dt); flash=max(0f,flash-dt)
        if(fire<=0){fire=.15f;val lanes=when(weapon){1->listOf(0f);2->listOf(-9f,9f);else->listOf(-16f,0f,16f)};lanes.forEach { shots.add(Shot(x+it,y-27,it*1.5f)) };events.add(GameEvent(Cue.SHOT))}
        if(!bossSpawned && stageTime<24 && spawn<=0){
            spawn=max(.5f,1.05f-difficulty*.12f)
            val heavy=random.nextFloat()<.25f
            enemies.add(Enemy(arenaLeft+35+random.nextFloat()*(arenaRight-arenaLeft-70),arenaTop-30f,if(heavy) 5+difficulty else 2,heavy))
            if(stage>=2 && random.nextFloat()<.25f){val bx=arenaLeft+60+random.nextFloat()*(arenaRight-arenaLeft-180);repeat(3){enemies.add(Enemy(bx+it*35,arenaTop-70f-it*25,2,false))}}
        }
        if(!bossSpawned && stageTime>=28){bossSpawned=true;boss=Boss(y=arenaTop-60f,hp=130+difficulty*45);announce("警戒 · 星域守卫接近")}
        for(e in enemies){e.age+=dt;e.y+=(if(e.heavy)55+difficulty*8 else 85+difficulty*10)*dt;e.x=(e.baseX+sin(e.age*1.7f)*22).coerceIn(arenaLeft+22f,arenaRight-22f);e.fire-=dt
            if(e.heavy&&e.fire<=0&&e.y in (arenaTop+50f)..(arenaBottom-100f)){e.fire=2f;val a=atan2(y-e.y,x-e.x);threats.add(Shot(e.x,e.y,cos(a)*125,sin(a)*125))}
            if(hypot(e.x-x,e.y-y)<e.radius+10){hurt();e.hp=0}
        }
        boss?.let { b -> b.age+=dt;b.y=min(arenaTop+150f,b.y+55*dt);b.x=200+sin(b.age*.8f)*105;b.fire-=dt
            if(b.fire<=0&&b.y>arenaTop+90){b.fire=1.35f-difficulty*.15f;val a=atan2(y-b.y,x-b.x);for(i in -2..2)threats.add(Shot(b.x,b.y+25,cos(a+i*.22f)*(100+difficulty*12),sin(a+i*.22f)*(100+difficulty*12)))}
            if(hypot(b.x-x,b.y-y)<62)hurt()
        }
        for(s in shots){s.x+=s.vx*dt;s.y+=s.vy*dt
            val e=enemies.firstOrNull{it.hp>0 && abs(s.x-it.x)<it.radius+3 && abs(s.y-it.y)<it.radius+10}
            if(e!=null){e.hp--;s.spent=true;burst(s.x,s.y,false,2)}
            else boss?.let{b->if(abs(s.x-b.x)<53 && abs(s.y-b.y)<50){b.hp--;s.spent=true;burst(s.x,s.y,false,2)}}
        }
        enemies.filter{it.hp<=0}.forEach(::destroy); enemies.removeAll{it.hp<=0||it.y>arenaBottom+50}
        shots.removeAll{it.spent||it.y<arenaTop-40}
        for(s in threats){s.x+=s.vx*dt;s.y+=s.vy*dt;if(hypot(s.x-x,s.y-y)<15){hurt();s.spent=true}}
        threats.removeAll{it.spent||it.y>arenaBottom+30||it.y<arenaTop-80||it.x !in (arenaLeft-30)..(arenaRight+30)}
        for(d in supplies){d.y+=65*dt;if(hypot(d.x-x,d.y-y)<34){when(d.type){0->weapon=min(3,weapon+1);1->shield=7f;2->lives=min(3,lives+1)};d.y=arenaBottom+80;score+=100;events.add(GameEvent(Cue.PICKUP));announce(listOf("火力升级","护盾开启 · 7 秒","机体修复")[d.type])}}
        supplies.removeAll{it.y>arenaBottom+30};checkBoss()
    }
}

/**
 * 预生成的钢琴音色：C4..C6 两个八度的全部 25 个半音（含黑键）。
 * 必须覆盖完整半音阶 —— 只装大调音阶的话，任何歌的旋律都会被压进同一个调里，
 * 不同曲子弹出来全是一个味儿。
 */
val PianoScale = IntArray(25) { it }

/** 街机模式按顺序上行时用的大调音阶（半音偏移），纯上行听感更顺。 */
val PianoScaleMajor = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)

/**
 * 把绝对半音高度（60 = C4）映射到预生成的 25 个钢琴音色索引（0..24），区间外截断。
 * 注意：semitone 是绝对高度，不是相对 C4 的偏移 —— 两套约定混用会把所有音都压到边界上，
 * 弹出来就只剩最高音那一下「叮」。
 */
fun pianoIndex(semitone: Int): Int = (semitone - 60).coerceIn(0, PianoScale.size - 1)

data class PianoTile(val col: Int, var y: Float, val note: Int, var hit: Boolean = false)

/**
 * 官方街机模式：4 行 4 列，每行恰好一块黑键。
 * 画面持续下落且不因点击停顿，速度随命中数递增；只按最下面那块，踩白块或漏掉即结束。
 */
class PianoGame(random: Random = Random.Default) : GameModel(GameKind.PIANO, random) {

    val rowHeight = ROW_HEIGHT
    val tiles = mutableListOf<PianoTile>()
    var hits = 0; private set
    var wrongColumn: Int? = null; private set

    /** 当前下落速度（世界单位/秒），随命中数递增并封顶。 */
    val speed: Float get() = BASE_SPEED + min(MAX_GAIN, hits * GAIN)

    private var sequence = 0
    private var lastColumn = -1
    private var sameColumnRun = 0

    init { repeat(VISIBLE_ROWS + 2) { tiles.add(next(BOTTOM - rowHeight - it * rowHeight)) } }

    /** 纯随机选列，但不允许同一列连着出现超过两次，免得节奏太呆。 */
    private fun pickColumn(): Int {
        var col = random.nextInt(4)
        if (col == lastColumn) {
            sameColumnRun++
            if (sameColumnRun >= 2) { col = (col + 1 + random.nextInt(3)) % 4; sameColumnRun = 0 }
        } else sameColumnRun = 0
        lastColumn = col
        return col
    }

    private fun next(y: Float): PianoTile {
        val n = sequence++
        // 街机模式按大调音阶顺序上行：半音阶的索引就是半音值，直接用。
        return PianoTile(pickColumn(), y, PianoScaleMajor[n % PianoScaleMajor.size])
    }

    override val status get() = "街机 · $hits 块    速度 ${(speed / BASE_SPEED * 100).toInt()}%"
    override val summary get() = "按对了 $hits 块黑键"
    override val progress get() = (hits % 100) / 100f

    override fun tap(x: Float, y: Float) {
        if (phase != Phase.PLAYING || x < 0f || x >= 400f) return
        val target = tiles.firstOrNull { !it.hit } ?: return
        val column = (x / 100f).toInt().coerceIn(0, 3)
        // 官方判定：按在黑键所在那一列就算中，按在别的列（白块）直接结束。
        if (column != target.col) {
            wrongColumn = column
            finish(false, "踩到白块了。只按最下面那一块黑的，看准了再下手。")
            return
        }
        target.hit = true
        hits++
        score = hits
        events.add(GameEvent(Cue.NOTE, target.note))
        burst(column * 100f + 50f, target.y + rowHeight / 2, false, 5)
        if (hits % 25 == 0) announce("$hits 块 · 稳住节奏")
    }

    override fun update(dt: Float) {
        // 开局先静止一小段，让玩家看清第一块在哪，然后再缓入到正常速度。
        val ramp = if (elapsed <= LEAD) 0f else min(1f, (elapsed - LEAD) / RAMP_IN)
        if (ramp <= 0f) return
        val step = speed * ramp * dt
        for (t in tiles) t.y += step

        // 整块滑出屏幕才算漏掉，这样每块都留足一整行的反应时间。
        val target = tiles.firstOrNull { !it.hit }
        if (target != null && target.y > BOTTOM) {
            finish(false, "有一块黑键滑出屏幕了。盯住最下面那块，再来一次。")
            return
        }
        tiles.removeAll { it.y > BOTTOM }
        while (tiles.size < VISIBLE_ROWS + 2) {
            val top = tiles.lastOrNull()?.y ?: (TOP - rowHeight)
            tiles.add(next(top - rowHeight))
        }
    }

    companion object {
        const val TOP = 110f
        const val BOTTOM = 669f
        const val VISIBLE_ROWS = 4
        const val ROW_HEIGHT = 139.75f
        const val LEAD = 0.9f
        const val RAMP_IN = 0.35f
        const val BASE_SPEED = 210f
        const val GAIN = 6.5f
        const val MAX_GAIN = 430f
    }
}

data class Obstacle(var x:Float,val beam:Boolean,var hit:Boolean=false){val width get()=if(beam)72f else 34f}
data class Coin(var x:Float,val y:Float,var taken:Boolean=false)
class RunnerGame(random:Random=Random.Default):GameModel(GameKind.RUNNER,random){
    var height=0f; private set
    var velocity=0f; private set
    var jumps=0; private set
    var sliding=0f; private set
    var lives=3; private set
    var invulnerable=0f; private set
    var distance=0f; private set
    var coinsCollected=0; private set
    var speed=205f; private set
    val obstacles=mutableListOf<Obstacle>()
    val coins=mutableListOf<Coin>()
    private var spawn=2.2f
    private var lastZone=1
    val district get()=1+(distance/400).toInt()
    val zone get()=1+(district-1)%3
    val isSliding get()=sliding>0 && height<1
    override val status get()="无尽 · ${distance.toInt()} 米    $coinsCollected 星币    街区 $district"
    override val summary get()="${distance.toInt()} 米  ·  $coinsCollected 枚星币"
    override val progress get()=(distance%400)/400
    override fun tap(x:Float,y:Float){
        if(phase!=Phase.PLAYING||jumps>=2)return
        sliding=0f;velocity=445f;jumps++;burst(91f,572-height,true,8);events.add(GameEvent(Cue.JUMP))
    }
    override fun skill(){if(phase==Phase.PLAYING){sliding=.9f;if(height>0)velocity=-600f}}
    override fun update(dt:Float){
        invulnerable=max(0f,invulnerable-dt);sliding=max(0f,sliding-dt)
        speed=205+min(110f,distance*.06f);distance+=speed*dt/15
        if(zone!=lastZone){lastZone=zone;announce(listOf("暖阳街区","霞光高架","星灯屋顶")[zone-1])}
        velocity-=1050*dt;height=max(0f,height+velocity*dt);if(height==0f){velocity=0f;jumps=0}
        spawn-=dt;if(spawn<=0){spawn=1.8f+random.nextFloat()*.6f;val beam=random.nextFloat()<.35f;obstacles.add(Obstacle(465f,beam));repeat(5){coins.add(Coin(470+it*25f,if(beam)560f else 485-sin(it*PI.toFloat()/4)*45))}}
        val bottom=580-height;val top=bottom-(if(isSliding)24 else 54)
        for(o in obstacles){o.x-=speed*dt;val ot=if(o.beam)505f else 538f;val ob=if(o.beam)540f else 580f
            if(!o.hit&&o.x<111&&o.x+o.width>75&&top<ob&&bottom>ot){o.hit=true;if(invulnerable<=0){lives--;invulnerable=1.8f;shake=7f;events.add(GameEvent(Cue.HURT));burst(92f,bottom-20,false);if(lives<=0)finish(false,"送信途中摔了一跤，整理行囊再出发。")}}
        }
        for(c in coins){c.x-=speed*dt;if(!c.taken&&c.x in 66f..119f&&c.y in (top-10)..(bottom+10)){c.taken=true;coinsCollected++;events.add(GameEvent(Cue.PICKUP));burst(c.x,c.y,true,4)}}
        obstacles.removeAll{it.x+it.width< -20};coins.removeAll{it.taken||it.x< -20};score=distance.toInt()+coinsCollected*20
    }
}
