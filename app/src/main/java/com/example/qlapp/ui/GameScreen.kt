package com.example.qlapp.ui

import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.repeatOnLifecycle
import com.example.qlapp.games.*
import com.example.qlapp.data.GomokuStanding
import com.example.qlapp.data.Prefs
import com.example.qlapp.data.GameRecordsRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlin.math.min

private fun SharedPreferences.record(id: String) =
    GameRecord(getInt("best_$id", 0), getString("best_owner_$id", null))

// Piano lanes fill the screen vertically; ships and runners keep their proportions.
internal class GameViewport(kind: GameKind, width: Float, height: Float) {
    val sx = if (kind == GameKind.PIANO) width / 400f else min(width / 400f, height / 720f)
    val sy = if (kind == GameKind.PIANO) height / 559f else sx
    val origin = if (kind == GameKind.PIANO) Offset(0f, -110f * sy)
        else Offset((width - 400f * sx) / 2, (height - 720f * sy) / 2)
    fun world(point: Offset) = Offset((point.x - origin.x) / sx, (point.y - origin.y) / sy)
    val bounds = Rect(world(Offset.Zero), world(Offset(width, height)))
}

@Composable
fun GameHubScreen(vm: AppViewModel) {
    val context=LocalContext.current
    val repository=remember(context){GameRecordsRepository.get(context)}
    val online by repository.state.collectAsState()
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(repository,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                repository.sync()
                delay(30_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("PLAY / TOGETHER",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,letterSpacing=2.sp)
        Text("两个人的游乐场",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
        Text("三款无尽挑战，外加一局联机五子棋。",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp,bottom=24.dp))
        // 联机对战排在最上面：这是两个人真正一起玩的那一项，不该埋在列表里
        GomokuEntryCard(hint=vm.gomokuHint,alert=vm.gomokuAlert,standing=vm.gomokuStanding) {
            context.startActivity(Intent(context,GomokuActivity::class.java))
        }
        GameKind.entries.forEachIndexed { index, kind ->
            // 别踩白块的首页预览用曲目模式，才能看到新的渐变背景和长按长条。
            val preview=remember(kind){
                if(kind==GameKind.PIANO) SongPianoGame(BuiltinSongs.all().first()) else GameModel.create(kind)
            }
            // 别踩白块先进曲目选择，另外两个直接开局。
            Card(onClick={
                val target=if(kind==GameKind.PIANO) PianoSongsActivity::class.java else GameActivity::class.java
                context.startActivity(Intent(context,target).putExtra("game",kind.name.lowercase()))
            },shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(bottom=18.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min=240.dp).background(kind.ink())) {
                    Canvas(Modifier.matchParentSize()) {
                        val scale=size.width/400f
                        withTransform({scale(scale,scale,Offset.Zero);translate(0f,-140f)}){drawGame(preview)}
                        drawRect(Brush.horizontalGradient(listOf(kind.ink(),kind.ink().copy(alpha=.85f),Color.Transparent)))
                        if(kind==GameKind.AIR)ship(size.width*.79f,size.height*.58f,1.2f)
                        if(kind==GameKind.RUNNER)runner(size.width*.8f,size.height*.82f,0f)
                    }
                    Column(Modifier.padding(22.dp)) {
                        Text("0${index+1} / ${kind.subtitle.substringBefore(" /")}",color=kind.accent(),fontSize=10.sp,letterSpacing=1.sp)
                        Text(kind.title,color=kind.onInk(),fontSize=27.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
                        Text(kind.subtitle.substringAfter("/ "),color=kind.onInkMuted(),fontSize=12.sp,modifier=Modifier.padding(top=4.dp))
                        Text("进入冒险  →",color=kind.accent(),fontSize=13.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=20.dp))
                        Text("联网最高纪录",color=kind.accent(),fontSize=10.sp,modifier=Modifier.padding(top=14.dp))
                        Text(if(online.hasCache)online.records.getValue(kind).label else if(online.error)"暂时无法获取联网纪录" else "正在获取联网纪录…",color=kind.onInkSoft(),fontSize=12.sp,modifier=Modifier.padding(top=4.dp))
                    }
                }
            }
        }
        Text(when {
            online.syncing -> "正在同步成绩…"
            online.error && online.hasCache -> "当前显示上次联网纪录 · 未上传成绩会自动重试"
            online.error -> "联网失败 · 成绩已保存在本机，稍后自动重试"
            else -> "所有玩家共享纪录 · 每 30 秒自动刷新"
        },color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp)
        TextButton(onClick={repository.requestSync()},enabled=!online.syncing) { Text("刷新纪录") }
    }
}

/**
 * 游戏首页的「联机对战」入口。
 *
 * 木色底 + 一小块棋盘当装饰，和真正的棋盘同一套画法（网格画在交叉点上）。
 * [hint] 是当前对局状态，[alert] 为真时用醒目颜色写——对方在等你，
 * 这里和底部 Tab 的红点是同一个信号。[standing] 是累计胜场，
 * 固定 小江 在前、甜甜 在后，领先的那位标朱红。
 */
@Composable
private fun GomokuEntryCard(hint:String?,alert:Boolean,standing:GomokuStanding,onClick:()->Unit) {
    Card(onClick=onClick,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(bottom=18.dp)) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFEBC59A),Color(0xFFC58F49)))).padding(22.dp)) {
            Canvas(Modifier.align(Alignment.CenterEnd).size(104.dp)) {
                val unit=size.minDimension/5f
                val ox=(size.width-unit*5)/2f+unit/2f
                val oy=(size.height-unit*5)/2f+unit/2f
                val line=Color(0xFF7A5220).copy(alpha=.38f)
                for(i in 0 until 5){
                    drawLine(line,Offset(ox+i*unit,oy),Offset(ox+i*unit,oy+4*unit),1.5f)
                    drawLine(line,Offset(ox,oy+i*unit),Offset(ox+4*unit,oy+i*unit),1.5f)
                }
                // 三个子摆一条斜线，一眼看出是五子棋
                drawCircle(Color(0xFF232326),unit*.42f,Offset(ox+unit,oy+unit))
                drawCircle(Color(0xFFF7F4ED),unit*.42f,Offset(ox+2*unit,oy+2*unit))
                drawCircle(Color(0xFFB4AA99),unit*.42f,Offset(ox+2*unit,oy+2*unit),style=Stroke(unit*.06f))
                drawCircle(Color(0xFF232326),unit*.42f,Offset(ox+3*unit,oy+3*unit))
            }
            Column(Modifier.padding(end=118.dp)) {
                Text("ONLINE / 联机对战",color=Color(0xFF6B4A1E),fontSize=10.sp,letterSpacing=1.sp)
                Text("五子棋",color=Color(0xFF2E1F0B),fontSize=27.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp))
                Text("和对方在同一张棋盘上轮流落子，先连成五个的赢。",color=Color(0xFF4A351A),fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=6.dp))
                GomokuScoreboard(standing)
                Text(hint ?: "点这里开一局  →",color=if(alert)Color(0xFF9B2C20) else Color(0xFF6B4A1E),
                    fontSize=13.sp,fontWeight=if(alert)FontWeight.Bold else FontWeight.Medium,modifier=Modifier.padding(top=14.dp))
            }
        }
    }
}

/**
 * 入口卡上的累计胜场：`胜场  小江 3 · 甜甜 1`。
 *
 * 顺序固定 小江 在前、甜甜 在后，领先的那位标朱红 —— 一眼看出谁赢得多。
 * 一次都没分出胜负（0:0）或打平时，谁都不标红。
 *
 * 用 AnnotatedString 而不是几个并排的 Text：这一行挤在棋盘装饰左边，
 * 屏幕窄的时候能自己折行，不会溢出。
 */
@Composable
private fun GomokuScoreboard(standing:GomokuStanding) {
    if(standing.entries.isEmpty())return
    Text(
        text=buildAnnotatedString {
            append("胜场  ")
            standing.entries.forEachIndexed { index, entry ->
                if(index>0)append("  ·  ")
                val leading=entry.first==standing.leader
                withStyle(SpanStyle(
                    color=if(leading)Color(0xFFA62B1F) else Color(0xFF4A351A),
                    fontWeight=if(leading)FontWeight.Bold else FontWeight.Medium,
                )) { append("${entry.first} ${entry.second}") }
            }
        },
        color=Color(0xFF8A6A3A),
        fontSize=13.sp,
        modifier=Modifier.padding(top=12.dp),
    )
}

class GameSession : ViewModel() {
    var model:GameModel?=null
    var player: String? = null
    var revision by mutableIntStateOf(0)
    fun changed(){revision++}
}

@Composable
fun NativeGame(id:String,onBack:()->Unit) {
    val kind=remember(id){GameKind.entries.firstOrNull{it.name.equals(id,true)}?:GameKind.AIR}
    val session:GameSession=viewModel(key="arcade-$id")
    if(session.model==null)session.model=GameModel.create(kind)
    val revision=session.revision
    val game=session.model!!
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(game, canvasSize) {
        if (game is AirGame && canvasSize.width > 0 && canvasSize.height > 0) {
            val bounds = GameViewport(kind, canvasSize.width.toFloat(), canvasSize.height.toFloat()).bounds
            game.setArena(bounds.left, bounds.top, bounds.right, bounds.bottom)
            session.changed()
        }
    }
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val prefs=remember{context.getSharedPreferences("native_arcade",0)}
    val recordsRepository=remember(context){GameRecordsRepository.get(context)}
    if (session.player == null) session.player = Prefs(context).nickname
    var best by remember(kind){mutableIntStateOf(prefs.getInt("best_$id",0))}
    var sound by remember{mutableStateOf(prefs.getBoolean("sound",true))}
    val audio=remember{GameAudio(context)}
    // 一局里暂停、离开、结算都会走到 saveRecord。用「本局已上报的最高分」去重，
    // 免得同一个成绩往服务器连发几遍；分数真的涨了（继续游戏后刷新）才会再报一次。
    var reported by remember(game){mutableIntStateOf(0)}
    fun saveRecord() {
        val current = prefs.record(id)
        val updated = current.improvedBy(game.score, session.player.orEmpty())
        if (updated != current) {
            prefs.edit().putInt("best_$id", updated.score)
                .putString("best_owner_$id", updated.owner).apply()
        }
        best = updated.score
        if (game.score > reported) {
            reported = game.score
            recordsRepository.submit(kind, game.score, session.player.orEmpty())
        }
    }
    fun leave() { saveRecord(); onBack() }
    DisposableEffect(audio){onDispose{audio.close()}}
    LaunchedEffect(sound){audio.enabled=sound;prefs.edit().putBoolean("sound",sound).apply()}
    // A tap can end the game between frames, so persist results independently of the loop.
    LaunchedEffect(game, game.phase) {
        if (game.phase == Phase.FINISHED) {
            saveRecord()
            game.events.forEach { audio.play(it) }
            game.events.clear()
        }
    }
    fun pause(){game.pause();saveRecord();audio.pause();session.changed()}
    BackHandler { if(game.phase==Phase.PLAYING)pause() else leave() }
    DisposableEffect(owner,game) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE){pause()}}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(game,game.phase) {
        if(game.phase!=Phase.PLAYING)return@LaunchedEffect
        var previous=withFrameNanos{it}
        var pending=0f
        while(isActive&&game.phase==Phase.PLAYING){
            val now=withFrameNanos{it}
            pending+=((now-previous)/1_000_000_000f).coerceAtMost(.1f);previous=now
            while(pending>=1f/60f){game.tick(1f/60f);pending-=1f/60f}
            game.events.forEach{audio.play(it)};game.events.clear()
            session.changed()
        }
    }
    Box(Modifier.fillMaxSize().background(kind.ink())) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(game,canvasSize){
            if (kind == GameKind.PIANO) {
                // Each finger gets its own key-down, even while another finger is still held.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val viewport = GameViewport(kind, size.width.toFloat(), size.height.toFloat())
                        event.changes.forEach { change ->
                            if (change.changedToDownIgnoreConsumed()) {
                                val position = viewport.world(change.position)
                                game.tap(position.x, position.y)
                                session.changed()
                            }
                            change.consume()
                        }
                    }
                }
            }
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                val viewport=GameViewport(kind,size.width.toFloat(),size.height.toFloat())
                if (game is AirGame) with(viewport.bounds) { game.setArena(left,top,right,bottom) }
                fun world(p:Offset)=viewport.world(p)
                val start=world(down.position)
                var last=down.position
                var slid=false
                down.consume()
                do {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                    val p=world(change.position)
                    if(kind==GameKind.AIR){val delta=world(change.position)-world(last);game.move(delta.x,delta.y)}
                    if(kind==GameKind.RUNNER&&!slid&&p.y-start.y>34){game.skill();slid=true}
                    last=change.position;change.consume()
                    if(!change.pressed){if(kind==GameKind.RUNNER&&!slid)game.tap(p.x,p.y);session.changed();break}
                }while(true)
            }
        }) {
            // Reading the revision here invalidates the native draw pass.
            @Suppress("UNUSED_VARIABLE") val frame=revision
            val viewport=GameViewport(kind,size.width,size.height)
            withTransform({translate(viewport.origin.x,viewport.origin.y);scale(viewport.sx,viewport.sy,Offset.Zero)}) {
                val wobble=if(game.phase==Phase.PLAYING)kotlin.math.sin(game.elapsed*90)*game.shake else 0f
                withTransform({translate(wobble,0f)}){drawGame(game,viewport.bounds)}
            }
        }
        if (game.phase == Phase.PLAYING) {
            // Floating controls do not reserve any space in the playing field.
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                .padding(horizontal=12.dp,vertical=4.dp), verticalAlignment=Alignment.CenterVertically) {
                val lightHud=kind==GameKind.PIANO
                Column(Modifier.weight(1f),horizontalAlignment=if(lightHud)Alignment.CenterHorizontally else Alignment.Start) {
                    // 街机白块现在也是深底画面，分数用白色加大号，和曲目模式一致。
                    Text(if(lightHud)game.score.toString() else game.score.toString().padStart(6,'0'), color=if(lightHud)Color.White else kind.onInk(),
                        fontSize=if(lightHud)40.sp else 20.sp, fontWeight=FontWeight.Bold,
                        fontFamily=if(lightHud)FontFamily.Default else FontFamily.Monospace,
                        style=LocalTextStyle.current.copy(shadow=Shadow(if(lightHud)Color.Black.copy(alpha=.5f) else Color.Black.copy(alpha=.8f),Offset(0f,2f),if(lightHud)7f else 5f)))
                    val lives=when(game){is AirGame->game.lives;is RunnerGame->game.lives;else->null}
                    if(lives!=null) Row(Modifier.padding(top=3.dp).semantics { contentDescription="剩余 $lives 条生命" },
                        horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        repeat(3) { index -> Box(Modifier.size(5.dp).background(
                            if(index<lives)kind.accent() else Color.White.copy(alpha=.2f),CircleShape)) }
                    }
                }
                RoundControl("暂停",Icons.Default.Pause){pause()}
            }
            if(game.noticeTime>0) {
                Text(game.notice,color=if(kind==GameKind.PIANO)Color.White.copy(alpha=.75f) else kind.accent(),
                    fontSize=11.sp,fontWeight=FontWeight.Medium,
                    modifier=Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top=if(kind==GameKind.PIANO)80.dp else 62.dp),
                    style=LocalTextStyle.current.copy(shadow=Shadow(if(kind==GameKind.PIANO)Color.Black.copy(alpha=.5f) else Color.Black.copy(alpha=.25f),Offset(0f,1f),3f)))
            }
            if(kind!=GameKind.PIANO) {
                val available=game !is AirGame || game.pulses>0
                IconButton(onClick={game.skill();session.changed()},enabled=available,
                    modifier=Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp).size(52.dp)
                        .background(kind.ink().copy(alpha=.3f),CircleShape)
                        .border(1.dp,kind.accent().copy(alpha=if(available).35f else .1f),CircleShape)) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(if(kind==GameKind.AIR)Icons.Default.Bolt else Icons.Default.KeyboardArrowDown,
                            contentDescription=if(game is AirGame)"脉冲，剩余 ${game.pulses} 次" else "滑铲",
                            tint=kind.accent().copy(alpha=if(available).85f else .25f),modifier=Modifier.size(23.dp))
                        if(game is AirGame)Text(game.pulses.toString(),color=kind.accent().copy(alpha=.7f),fontSize=9.sp)
                    }
                }
            }
        }
        if(game.phase!=Phase.PLAYING)GameSheet(game,best,sound,{sound=!sound},::leave) {
            when(game.phase){
                Phase.READY->game.start()
                Phase.PAUSED->game.resume()
                Phase.FINISHED->{session.model=GameModel.create(kind).also{it.start()}}
                else->Unit
            }
            session.changed()
        }
    }
}

@Composable private fun RoundControl(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,light:Boolean=false,click:()->Unit) {
    val tint=if(light)Color(0xFF1B1B1D) else Color.White
    IconButton(onClick=click,modifier=Modifier.size(48.dp).padding(5.dp).background(if(light)Color(0xFF1B1B1D).copy(alpha=.10f) else Color(0xFF0A1626).copy(alpha=.28f),CircleShape)) {
        Icon(icon,contentDescription=label,tint=tint,modifier=Modifier.size(20.dp))
    }
}

@Composable private fun GameSheet(game:GameModel,best:Int,sound:Boolean,onSound:()->Unit,onBack:()->Unit,onPrimary:()->Unit) {
    val kind=game.kind
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.38f)),contentAlignment=Alignment.Center) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).widthIn(max=380.dp).fillMaxWidth().verticalScroll(rememberScrollState()).background(kind.ink().copy(alpha=.96f),RoundedCornerShape(28.dp)).border(1.dp,kind.accent().copy(alpha=.25f),RoundedCornerShape(28.dp)).padding(26.dp)) {
            Text(kind.subtitle,color=kind.accent(),fontSize=10.sp,letterSpacing=1.sp)
            val lightSheet=kind==GameKind.PIANO
            Text(when(game.phase){Phase.READY->kind.title;Phase.PAUSED->"休息一下";else->if(game.won)"漂亮收官" else "再来一局"},fontSize=34.sp,color=kind.onInk(),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp,bottom=14.dp))
            Text(when(game.phase){Phase.READY->kind.arcadeInstructions;Phase.PAUSED->game.status+"\n\n"+kind.arcadeInstructions;else->game.reason},fontSize=13.sp,lineHeight=23.sp,color=kind.onInkMuted())
            if(game.phase==Phase.FINISHED){
                Text(if(lightSheet)game.score.toString() else game.score.toString().padStart(6,'0'),fontSize=42.sp,fontFamily=if(lightSheet)FontFamily.Default else FontFamily.Monospace,color=kind.accent(),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=20.dp))
                Text(game.summary,fontSize=12.sp,color=kind.onInkSoft(),modifier=Modifier.padding(top=8.dp))
            }
            TextButton(onClick=onSound) {
                Icon(if(sound)Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription=null,modifier=Modifier.size(18.dp),tint=kind.accent())
                Spacer(Modifier.width(8.dp))
                Text(if(sound)"音效：开" else "音效：关",color=kind.accent())
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick=onPrimary,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=kind.accent(),contentColor=kind.ink())) {
                Text(when(game.phase){Phase.READY->"开始冒险 →";Phase.PAUSED->"继续游戏 →";else->"再玩一次 →"},fontWeight=FontWeight.Bold)
            }
            TextButton(onClick=onBack,modifier=Modifier.fillMaxWidth()){Text("返回游乐场",color=kind.onInkMuted())}
            Text("BEST  ${best.toString().padStart(6,'0')}  /  本机纪录",color=kind.accent().copy(alpha=.6f),fontSize=10.sp,fontFamily=FontFamily.Monospace,modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp))
        }
    }
}
