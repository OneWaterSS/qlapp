package com.example.qlapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.qlapp.data.GameRecordsRepository
import com.example.qlapp.data.Prefs
import com.example.qlapp.games.*
import com.example.qlapp.ui.theme.QlappTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 别踩白块的曲目入口：先选曲，再进游戏。 */
class PianoSongsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent { QlappTheme { PianoSongsHost { finish() } } }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun PianoSongsHost(onBack: () -> Unit) {
    var current by remember { mutableStateOf<Song?>(null) }
    val song = current
    if (song == null) SongPickerScreen(onBack = onBack, onPick = { current = it })
    else SongPlayerScreen(song = song, onExit = { current = null }, onHome = onBack)
}

/* ------------------------------- 选曲 ------------------------------- */

@Composable
private fun SongPickerScreen(onBack: () -> Unit, onPick: (Song) -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { SongLibrary(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val builtin = remember { BuiltinSongs.all() }
    val imported = remember(revision) { library.imported() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { library.import(uri, library.guessTitle(uri)) }
            }
            busy = false
            result.onSuccess {
                revision++
                Toast.makeText(context, "《${it.title}》做好了，一共 ${it.noteCount} 块", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "这个文件解析不了，换一个试试", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler(enabled = !busy) { onBack() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PianoTheme.skyTop, PianoTheme.skyMid, PianoTheme.skyLow)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PIANO TILES", color = Color.White.copy(alpha = .55f), fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("别踩白块", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text("挑一首曲子，跟着节奏按下方块。\n遇到长条要按住不放，直到它走完。",
                        color = Color.White.copy(alpha = .6f), fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                }
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "返回", tint = Color.White.copy(alpha = .7f)) }
            }

            Spacer(Modifier.height(22.dp))
            Text("经典玩法", color = Color.White.copy(alpha = .5f), fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            Card(onClick = { context.startActivity(Intent(context, GameActivity::class.java).putExtra("game", "piano")) },
                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .12f)),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("街机模式 · 无尽", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("4 行 4 列，越落越快，漏一块就结束", color = Color.White.copy(alpha = .55f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("→", color = Color.White.copy(alpha = .7f), fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("内置曲目", color = Color.White.copy(alpha = .5f), fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            builtin.forEach { song -> SongCard(song, onPick = { onPick(song) }) }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我导入的", color = Color.White.copy(alpha = .5f), fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !busy) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = PianoTheme.holdTop, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入音乐", color = PianoTheme.holdTop, fontSize = 12.sp)
                }
            }
            if (imported.isEmpty()) {
                Text("还没有导入过。点上面的「导入音乐」，选一首本地歌曲，会自动生成关卡。",
                    color = Color.White.copy(alpha = .38f), fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                imported.forEach { song ->
                    SongCard(song, onPick = { onPick(song) }) {
                        library.delete(song)
                        revision++
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PianoTheme.holdTop)
                    Text("正在听这首歌…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
                    Text("长歌会久一点", color = Color.White.copy(alpha = .5f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SongCard(song: Song, onPick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Card(onClick = onPick, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .10f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(song.author, color = Color.White.copy(alpha = .55f), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("${song.noteCount} 块")
                    if (song.holdCount > 0) { Spacer(Modifier.width(6.dp)); Chip("${song.holdCount} 长条") }
                    Spacer(Modifier.width(6.dp))
                    Chip(song.lengthLabel)
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", tint = Color.White.copy(alpha = .45f), modifier = Modifier.size(18.dp)) }
            }
            Text("→", color = Color.White.copy(alpha = .55f), fontSize = 18.sp)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.background(Color.White.copy(alpha = .14f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, color = Color.White.copy(alpha = .78f), fontSize = 10.sp)
    }
}

/* ------------------------------- 弹琴 ------------------------------- */

@Composable
private fun SongPlayerScreen(song: Song, onExit: () -> Unit, onHome: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("piano_songs", 0) }
    val nickname = remember(context) { Prefs(context).nickname }
    val records = remember(context) { GameRecordsRepository.get(context) }
    val audio = remember { GameAudio(context) }
    var game by remember(song) { mutableStateOf(SongPianoGame(song)) }
    var frame by remember { mutableIntStateOf(0) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var best by remember(song) { mutableIntStateOf(prefs.getInt("best_${song.id}", 0)) }
    val held = remember { mutableMapOf<Long, Int>() }

    fun leave() { onExit() }
    // 面板显隐、分数、连击都靠 frame 推动重组，所以暂停/开局都必须手动把帧计数推一格。
    fun pause() { if (game.phase == Phase.PLAYING) { game.pause(); audio.pause(); frame++ } }
    fun start(g: SongPianoGame) { g.start() }

    DisposableEffect(audio) { onDispose { audio.close() } }
    LaunchedEffect(sound) { audio.enabled = sound; prefs.edit().putBoolean("sound", sound).apply() }
    DisposableEffect(owner, game) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { if (game.phase == Phase.PLAYING) pause() else leave() }

    LaunchedEffect(game, game.phase) {
        if (game.phase != Phase.PLAYING) return@LaunchedEffect
        var previous = withFrameNanos { it }
        var pending = 0f
        while (isActive && game.phase == Phase.PLAYING) {
            val now = withFrameNanos { it }
            pending += ((now - previous) / 1_000_000_000f).coerceAtMost(.1f); previous = now
            while (pending >= 1f / 60f) { game.tick(1f / 60f); pending -= 1f / 60f }
            game.events.forEach { audio.play(it) }
            game.events.clear()
            frame++
        }
    }
    LaunchedEffect(game, game.phase) {
        if (game.phase != Phase.FINISHED) return@LaunchedEffect
        game.events.forEach { audio.play(it) }
        game.events.clear()
        if (game.score > best) { prefs.edit().putInt("best_${song.id}", game.score).apply(); best = game.score }
        if (game.score > 0) records.submit(GameKind.PIANO, game.score, nickname)
    }

    // 必须在组合阶段读一次 frame：写在 Canvas 的绘制 lambda 里只会触发重绘，
    // 而面板显隐、分数、连击、判定提示都是在组合阶段读的，不重组就永远不刷新。
    @Suppress("UNUSED_VARIABLE") val tick = frame

    Box(Modifier.fillMaxSize().background(PianoTheme.skyLow)) {
        Canvas(Modifier.fillMaxSize().pointerInput(game) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        when {
                            change.changedToDownIgnoreConsumed() -> {
                                val column = ((change.position.x / size.width) * 4).toInt().coerceIn(0, 3)
                                // 把手指高度换算回世界坐标：判定要看按在哪块方块上。
                                val worldY = change.position.y / (size.height / 559f) + 110f
                                held[change.id.value] = column
                                game.press(column, worldY)
                            }
                            change.changedToUpIgnoreConsumed() -> {
                                held.remove(change.id.value)?.let { game.release(it) }
                            }
                        }
                        change.consume()
                    }
                }
            }
        }) {
            @Suppress("UNUSED_VARIABLE") val redraw = frame
            val viewport = GameViewport(GameKind.PIANO, size.width, size.height)
            withTransform({ translate(viewport.origin.x, viewport.origin.y); scale(viewport.sx, viewport.sy, Offset.Zero) }) {
                val wobble = if (game.phase == Phase.PLAYING) kotlin.math.sin(game.elapsed * 90) * game.shake else 0f
                withTransform({ translate(wobble, 0f) }) { drawGame(game, viewport.bounds) }
            }
        }

        if (game.phase == Phase.PLAYING) {
            // 官方顶上有一条半透明的深色渐变，把分数和状态压在画面上方。
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .38f), Color.Transparent)))
                .safeDrawingPadding().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(song.title, color = Color.White.copy(alpha = .5f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::pause, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Pause, "暂停", tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(game.score.toString(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    if (game.combo >= 3) {
                        Spacer(Modifier.width(10.dp))
                        Text("${game.combo} 连击", color = PianoTheme.holdTop, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Row {
                        repeat(3) { index ->
                            Box(Modifier.size(6.dp).background(if (index < game.lives) PianoTheme.holdBottom else Color.White.copy(alpha = .18f), CircleShape))
                            if (index < 2) Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .14f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(game.progress.coerceAtLeast(.01f)).fillMaxHeight().background(PianoTheme.holdTop, RoundedCornerShape(2.dp)))
                }
            }

            val judge = game.lastJudge
            if (judge != null && game.judgeAge > 0f) {
                Text(when (judge) { Judge.PERFECT -> "PERFECT"; Judge.GREAT -> "GREAT"; Judge.GOOD -> "GOOD"; Judge.MISS -> "MISS" },
                    color = if (judge == Judge.MISS) Color(0xFFFF6B81) else Color.White,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp)
                        .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 4.dp))
            }
            if (game.noticeTime > 0f) {
                Text(game.notice, color = Color.White.copy(alpha = .75f), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 118.dp))
            }
        }

        if (game.phase != Phase.PLAYING) {
            SongSheet(song, game, best, sound,
                onSound = { sound = !sound },
                onExit = ::leave,
                onHome = onHome,
                onPrimary = {
                    when (game.phase) {
                        Phase.READY -> start(game)
                        Phase.PAUSED -> { game.resume() }
                        Phase.FINISHED -> { val fresh = SongPianoGame(song); game = fresh; start(fresh) }
                        else -> Unit
                    }
                    frame++
                })
        }
    }
}

@Composable
private fun SongSheet(song: Song, game: SongPianoGame, best: Int, sound: Boolean,
                      onSound: () -> Unit, onExit: () -> Unit, onHome: () -> Unit, onPrimary: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).widthIn(max = 380.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(PianoTheme.skyMid.copy(alpha = .96f), RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(28.dp))
            .padding(26.dp)) {
            Text("${song.author} · ${song.noteCount} 块${if (song.holdCount > 0) " · ${song.holdCount} 长条" else ""}",
                color = Color.White.copy(alpha = .5f), fontSize = 10.sp, letterSpacing = 1.sp)
            Text(when (game.phase) { Phase.READY -> song.title; Phase.PAUSED -> "休息一下"; else -> if (game.won) "弹完啦" else "断在这儿了" },
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
            Text(when (game.phase) {
                Phase.READY -> "点开始后有 2.6 秒准备时间，方块会从上方落下来。\n只点最下面那块，按在它身上就算数。\n点上面的块、点空白都算踩白块，只断连击不扣机会。\n暖色的长条要按住：按住那一刻它就顺着你的手指慢慢变短，走完再松手。中途松手也不算错，按满分数更高。"
                Phase.PAUSED -> game.status
                else -> game.reason
            }, color = Color.White.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 22.sp)

            if (game.phase == Phase.FINISHED) {
                Text(game.score.toString(), color = PianoTheme.holdTop, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                Text(game.summary, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            TextButton(onClick = onSound) {
                Text(if (sound) "音效：开" else "音效：关", color = PianoTheme.holdTop)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PianoTheme.holdTop, contentColor = PianoTheme.skyLow)) {
                Text(when (game.phase) { Phase.READY -> "开始弹 →"; Phase.PAUSED -> "继续弹 →"; else -> "再弹一次 →" }, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("换首曲子", color = Color.White.copy(alpha = .7f)) }
            TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("返回游乐场", color = Color.White.copy(alpha = .45f)) }
            Text("BEST  $best  ·  《${song.title}》本机纪录", color = Color.White.copy(alpha = .4f), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        }
    }
}
