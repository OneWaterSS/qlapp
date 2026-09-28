package com.example.qlapp.ui

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.qlapp.data.Api
import com.example.qlapp.data.GomokuMatch
import com.example.qlapp.data.GomokuStats
import com.example.qlapp.data.Prefs
import com.example.qlapp.games.Cue
import com.example.qlapp.games.GOMOKU_SIZE
import com.example.qlapp.games.GameAudio
import com.example.qlapp.games.GameEvent
import com.example.qlapp.games.Gomoku
import com.example.qlapp.games.Point
import com.example.qlapp.games.Stone
import com.example.qlapp.ui.theme.QlappTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 联机五子棋。
 *
 * 没有 WebSocket，也不打算上 Durable Objects——两个人、回合制，
 * 前台每 2 秒拉一次 `/api/gomoku/current` 就够了，对手落子最多晚 2 秒看到。
 * 轮次、空位、胜负全由后端判定，客户端只负责画和发坐标，
 * 这样两个人同时点同一格也不会各画各的。
 *
 * 外观走「素雅水墨」：宣纸底、墨色字、朱红点睛，棋盘保留暖木色（黑白子对比最好认）。
 * 音效全部是代码合成的，没有音频素材文件，见 [GameAudio]。
 */
class GomokuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QlappTheme { GomokuScreen(onBack = { finish() }) } }
    }
}

class GomokuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val api = Api(prefs)

    /** 音效与街机游戏共用同一份开关，静音一次全场生效。 */
    private val gamePrefs = app.getSharedPreferences("native_arcade", 0)
    private val audio = GameAudio(app)

    var soundOn by mutableStateOf(gamePrefs.getBoolean("sound", true))
        private set

    init { audio.enabled = soundOn }

    fun toggleSound() {
        soundOn = !soundOn
        audio.enabled = soundOn
        gamePrefs.edit().putBoolean("sound", soundOn).apply()
    }

    override fun onCleared() { audio.close() }

    var match by mutableStateOf<GomokuMatch?>(null)
        private set

    var stats by mutableStateOf(GomokuStats())
        private set

    /** 首次拉取还没回来。用来区分「真的没有局」和「还不知道」。 */
    var loading by mutableStateOf(true)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    fun consumeError() { error = null }
    fun consumeNotice() { notice = null }
    fun notify(message: String) { notice = message }

    private fun sideOf(m: GomokuMatch?): Stone? = m?.let {
        when (prefs.deviceId) {
            it.blackId -> Stone.BLACK
            it.whiteId -> Stone.WHITE
            else -> null
        }
    }

    /** 我在这局里执什么颜色；没加入（或压根没有局）时是 null。 */
    val mySide: Stone? get() = sideOf(match)

    /** 是不是轮到我落子。 */
    val myTurn: Boolean
        get() {
            val m = match ?: return false
            val side = mySide ?: return false
            return m.status == "playing" && m.turn == side.code
        }

    /**
     * 按前后两次状态的**差异**补音效。
     *
     * 轮询每 2 秒来一次，同一手棋会被反复看到，所以必须比出增量再响；
     * 一看是 playing 就播的话，会每两秒响一声。
     */
    private fun playCues(prev: GomokuMatch?, next: GomokuMatch?) {
        if (next == null) return
        val mine = sideOf(next)

        if (prev == null || prev.id != next.id) {
            // 新看到一局：只有「对方发来、还没人接」的邀请值得提醒。
            // 自己发起的邀请不必响——刚点完按钮，我知道。
            if (next.status == "waiting" && next.blackId != prefs.deviceId) {
                audio.play(GameEvent(Cue.CHIME))
            }
            return
        }

        // 对方接了邀请，正式开局。这一步手数没变，得单独判。
        if (prev.status == "waiting" && next.status == "playing") {
            audio.play(GameEvent(Cue.CHIME))
            return
        }

        val before = Gomoku.parseMoves(prev.moves).size
        val after = Gomoku.parseMoves(next.moves).size
        if (after > before) {
            val placed = Gomoku.stoneAt(after - 1)
            audio.play(GameEvent(if (placed == mine) Cue.PLACE else Cue.PLACE_RIVAL))
        }

        if (next.status == "finished" && prev.status != "finished") {
            audio.play(GameEvent(when (next.winner) {
                "draw" -> Cue.GOMOKU_DRAW
                mine?.code -> Cue.GOMOKU_WIN
                else -> Cue.GOMOKU_LOSE
            }))
        }
    }

    /**
     * 服务端状态落地。
     *
     * 只认「不比手上这份旧」的结果：轮询和落子响应是并发跑的，慢到的轮询可能带着
     * 上一手的局面，直接覆盖会让刚落的子闪一下又消失。局号变了（再来一局）
     * 或者服务端说没有局了，都直接采纳。
     */
    private fun applyMatch(next: GomokuMatch?) {
        val current = match
        if (current != null && next != null && next.id == current.id && next.updatedAt < current.updatedAt) return
        playCues(current, next)
        match = next
    }

    /** 轮询用：失败静默，下次再来，不打扰正在下棋的人。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                val feed = withContext(Dispatchers.IO) { api.gomokuCurrent() }
                applyMatch(feed.match)
                stats = feed.stats
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    /** 带反馈的动作（落子、接受、认输…）：失败会弹提示。 */
    private fun action(block: suspend () -> GomokuMatch?) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val before = match?.status
                val next = withContext(Dispatchers.IO) { block() }
                if (next != null) applyMatch(next)
                // 只有「这一手把棋下完了」战绩才会变，平时不用多跑一趟：
                // 多一次往返等于多等一个网络延迟才轮到自己再落子。
                if (next != null && next.status == "finished" && before != "finished") {
                    stats = withContext(Dispatchers.IO) { api.gomokuCurrent().stats }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message ?: "连不上服务器，检查下网络"
            } finally {
                busy = false
            }
        }
    }

    fun create() = action { api.gomokuCreate() }

    fun join(id: String) = action { api.gomokuJoin(id) }

    fun move(x: Int, y: Int) {
        val m = match ?: return
        if (!myTurn) return
        action { api.gomokuMove(m.id, x, y) }
    }

    fun resign() {
        val m = match ?: return
        action { api.gomokuResign(m.id) }
    }

    /** 再来一局：后端会把黑白对调，直接开新局。 */
    fun rematch() {
        val m = match ?: return
        action { api.gomokuRematch(m.id) }
    }

    /** 取消自己发起的邀请，或拒绝对方的邀请。 */
    fun cancel() {
        val m = match ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { api.gomokuCancel(m.id) }
                applyMatch(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message ?: "连不上服务器，检查下网络"
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GomokuScreen(onBack: () -> Unit) {
    val vm: GomokuViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val owner = LocalLifecycleOwner.current

    // 前台每 2 秒拉一次：对手的落子、对方接受邀请、对方点「再来一局」都靠它同步。
    // 切后台就停，不在后台空转。
    LaunchedEffect(vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                vm.refresh()
                delay(2000)
            }
        }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it); vm.consumeError() }
    }
    LaunchedEffect(vm.notice) {
        vm.notice?.let { snackbar.showSnackbar(it); vm.consumeNotice() }
    }

    val rawMoves = vm.match?.moves.orEmpty()
    val moves = remember(rawMoves) { Gomoku.parseMoves(rawMoves) }
    var confirmResign by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = PAPER_TOP,
        // 这屏没有底部输入条，交给 Scaffold 自己按系统栏留边就够
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = PAPER_TOP,
                        titleContentColor = INK,
                        navigationIconContentColor = INK,
                        actionIconContentColor = INK,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "五子棋",
                                fontFamily = Serif,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                            )
                            Spacer(Modifier.width(9.dp))
                            SealMark()
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.toggleSound() }) {
                            Icon(
                                if (vm.soundOn) Icons.AutoMirrored.Filled.VolumeUp
                                else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = if (vm.soundOn) "关闭音效" else "打开音效",
                            )
                        }
                        if (vm.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 14.dp).size(16.dp),
                                strokeWidth = 2.dp,
                                color = CINNABAR,
                            )
                        }
                    },
                )
                // 一条墨色发丝线，把顶栏和棋盘分开，比 Material 的分割线轻
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(INK_HAIR))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(PAPER_TOP, PAPER_BOTTOM)))
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            ScoreRow(vm.stats)
            StatusBanner(vm)
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 棋盘外面裱一圈纸：留白 + 一道墨线，像把棋盘裱在宣纸上
                Box(
                    modifier = Modifier
                        .size(minOf(maxWidth, maxHeight))
                        .background(PAPER_CARD, RoundedCornerShape(18.dp))
                        .border(1.dp, INK.copy(alpha = .30f), RoundedCornerShape(18.dp))
                        .padding(7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GomokuBoard(
                        moves = moves,
                        enabled = vm.myTurn,
                        onTap = { x, y -> vm.move(x, y) },
                        onOccupied = { vm.notify("这个点已经有子了") },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ActionBar(vm, onBack = onBack, onResign = { confirmResign = true })
            Spacer(Modifier.height(14.dp))
        }
    }

    if (confirmResign) {
        ConfirmDialog(
            title = "认输？",
            message = "认输这一局就算对方赢，不能撤回。",
            onDismiss = { confirmResign = false },
            onConfirm = { vm.resign() },
        )
    }
}

/** 一句话说清现在该谁动、我执什么颜色。 */
private fun statusOf(vm: GomokuViewModel): Pair<String, Boolean> {
    val m = vm.match ?: return "和对方轮流落子，先连成五个的赢" to false
    val side = vm.mySide
    return when {
        m.status == "waiting" && side == Stone.BLACK -> "邀请已发出，等对方接受…" to false
        m.status == "waiting" -> "对方邀请你对局" to true
        m.status == "playing" && vm.myTurn -> "轮到你落子（你执${side?.label}）" to true
        m.status == "playing" -> "等对方落子（你执${side?.label}）" to false
        m.status == "finished" -> when (m.winner) {
            "draw" -> "棋盘下满了，和棋" to false
            side?.code -> "你赢了 🎉" to true
            else -> "这局对方赢了" to false
        }
        else -> "" to false
    }
}

@Composable
private fun StatusBanner(vm: GomokuViewModel) {
    val (text, highlight) = statusOf(vm)
    Text(
        text = text,
        fontSize = 13.sp,
        fontFamily = Serif,
        letterSpacing = .5.sp,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        color = if (highlight) CINNABAR else INK_SOFT,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun ScoreRow(stats: GomokuStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatChip("你", stats.mine, Modifier.weight(1f))
        StatChip("和", stats.draws, Modifier.weight(1f))
        StatChip("对方", stats.theirs, Modifier.weight(1f))
    }
}

/** 战绩做成三张「笺」：米白纸底 + 一道细墨边。 */
@Composable
private fun StatChip(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(PAPER_CARD, RoundedCornerShape(10.dp))
            .border(1.dp, INK.copy(alpha = .22f), RoundedCornerShape(10.dp))
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            fontSize = 20.sp,
            fontFamily = Serif,
            fontWeight = FontWeight.Bold,
            color = INK,
        )
        Text(text = label, fontSize = 11.sp, color = INK_SOFT)
    }
}

/** 标题旁那枚朱红小印，全页唯一的朱红装饰，多一个就俗了。 */
@Composable
private fun SealMark() {
    Box(
        modifier = Modifier.size(22.dp).background(CINNABAR, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "弈",
            color = Color(0xFFFFF7EE),
            fontSize = 13.sp,
            fontFamily = Serif,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ActionBar(vm: GomokuViewModel, onBack: () -> Unit, onResign: () -> Unit) {
    val m = vm.match
    val side = vm.mySide
    val primary = ButtonDefaults.buttonColors(
        containerColor = CINNABAR,
        contentColor = Color(0xFFFFF7EE),
    )
    val ghost = ButtonDefaults.outlinedButtonColors(contentColor = INK)
    val quiet = ButtonDefaults.textButtonColors(contentColor = INK_SOFT)
    val ghostBorder = BorderStroke(1.dp, INK.copy(alpha = .45f))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            vm.loading && m == null ->
                Button(onClick = {}, enabled = false, shape = SealShape, colors = primary) {
                    Text("加载中…")
                }

            m == null ->
                Button(onClick = { vm.create() }, enabled = !vm.busy, shape = SealShape, colors = primary) {
                    Text("发起对局（执黑先手）", fontFamily = Serif)
                }

            m.status == "waiting" && side == Stone.BLACK ->
                OutlinedButton(
                    onClick = { vm.cancel() },
                    enabled = !vm.busy,
                    shape = SealShape,
                    colors = ghost,
                    border = ghostBorder,
                ) { Text("取消邀请", fontFamily = Serif) }

            m.status == "waiting" -> {
                Button(onClick = { vm.join(m.id) }, enabled = !vm.busy, shape = SealShape, colors = primary) {
                    Text("接受（执白）", fontFamily = Serif)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { vm.cancel() }, enabled = !vm.busy, colors = quiet) {
                    Text("拒绝", fontFamily = Serif)
                }
            }

            m.status == "playing" ->
                OutlinedButton(
                    onClick = onResign,
                    enabled = !vm.busy,
                    shape = SealShape,
                    colors = ghost,
                    border = ghostBorder,
                ) { Text("认输", fontFamily = Serif) }

            else -> {
                Button(onClick = { vm.rematch() }, enabled = !vm.busy, shape = SealShape, colors = primary) {
                    Text("再来一局（换先手）", fontFamily = Serif)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onBack, colors = quiet) { Text("返回", fontFamily = Serif) }
            }
        }
    }
}

/* ------------------------------ 素雅水墨配色 ------------------------------ */

private val PAPER_TOP = Color(0xFFF8F2E5)
private val PAPER_BOTTOM = Color(0xFFEFE6D2)
private val PAPER_CARD = Color(0xFFFBF7EC)
private val INK = Color(0xFF2B2A28)
private val INK_SOFT = Color(0xFF6B655C)
private val INK_HAIR = Color(0xFFC9BEA6)
private val CINNABAR = Color(0xFFA62B1F)

/** 宋体/明朝体观感，中文下比黑体更「中式」。 */
private val Serif = FontFamily.Serif

private val SealShape = RoundedCornerShape(10.dp)

/* --------------------------------- 棋盘 --------------------------------- */

private val BOARD_LIGHT = Color(0xFFE7BC85)
private val BOARD_DARK = Color(0xFFD3A063)
private val GRID_COLOR = Color(0xFF7A5220)
private val BLACK_STONE = Color(0xFF232326)
private val BLACK_EDGE = Color(0xFF5C5C64)
private val WHITE_STONE = Color(0xFFF7F4ED)
private val WHITE_EDGE = Color(0xFFB4AA99)
private val WIN_RING = Color(0xFFD6433B)

/**
 * 15 路木色棋盘。
 *
 * 网格线画在**交叉点**上（不是格子中间），边缘留半个格子的空，
 * 落子的坐标就是「离哪个交叉点最近」——和后端存 `x,y` 的语义一致。
 */
@Composable
private fun GomokuBoard(
    moves: List<Point>,
    enabled: Boolean,
    onTap: (Int, Int) -> Unit,
    onOccupied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val occupied = remember(moves) { moves.map { it.x to it.y }.toHashSet() }
    val winning = remember(moves) { Gomoku.winningLine(moves)?.map { it.x to it.y }?.toHashSet().orEmpty() }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(enabled, moves) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val unit = boardUnit(size.width, size.height)
                    val originX = (size.width - unit * GOMOKU_SIZE) / 2f + unit / 2f
                    val originY = (size.height - unit * GOMOKU_SIZE) / 2f + unit / 2f
                    val col = ((offset.x - originX) / unit).roundToInt()
                    val row = ((offset.y - originY) / unit).roundToInt()
                    if (col !in 0 until GOMOKU_SIZE || row !in 0 until GOMOKU_SIZE) return@detectTapGestures
                    if ((col to row) in occupied) {
                        onOccupied()
                        return@detectTapGestures
                    }
                    onTap(col, row)
                }
            },
    ) {
        val unit = size.minDimension / GOMOKU_SIZE
        val left = (size.width - unit * GOMOKU_SIZE) / 2f + unit / 2f
        val top = (size.height - unit * GOMOKU_SIZE) / 2f + unit / 2f
        fun px(i: Int) = left + i * unit
        fun py(j: Int) = top + j * unit

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(BOARD_LIGHT, BOARD_DARK),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )

        val lineColor = GRID_COLOR.copy(alpha = .5f)
        val lineWidth = 1.dp.toPx()
        for (i in 0 until GOMOKU_SIZE) {
            drawLine(lineColor, Offset(px(i), py(0)), Offset(px(i), py(GOMOKU_SIZE - 1)), lineWidth)
            drawLine(lineColor, Offset(px(0), py(i)), Offset(px(GOMOKU_SIZE - 1), py(i)), lineWidth)
        }
        // 外框比内线稍粗，棋盘有边界感
        drawRect(
            color = GRID_COLOR.copy(alpha = .45f),
            topLeft = Offset(px(0), py(0)),
            size = Size(unit * (GOMOKU_SIZE - 1), unit * (GOMOKU_SIZE - 1)),
            style = Stroke(width = 1.6.dp.toPx()),
        )
        Gomoku.STAR_POINTS.forEach { p ->
            drawCircle(GRID_COLOR.copy(alpha = .7f), radius = unit * .1f, center = Offset(px(p.x), py(p.y)))
        }

        val radius = unit * .44f
        moves.forEachIndexed { index, p ->
            val center = Offset(px(p.x), py(p.y))
            when (Gomoku.stoneAt(index)) {
                Stone.BLACK -> {
                    drawCircle(BLACK_STONE, radius, center)
                    drawCircle(BLACK_EDGE, radius, center, style = Stroke(width = radius * .1f))
                }
                Stone.WHITE -> {
                    drawCircle(WHITE_STONE, radius, center)
                    drawCircle(WHITE_EDGE, radius, center, style = Stroke(width = radius * .1f))
                }
            }
        }

        // 最后一手点个小点：一眼看到对方刚下在哪
        moves.lastOrNull()?.let { p ->
            val dot = if (Gomoku.stoneAt(moves.size - 1) == Stone.BLACK) Color(0xFFE9E9EC) else Color(0xFF3A3A3E)
            drawCircle(dot, radius = radius * .26f, center = Offset(px(p.x), py(p.y)))
        }

        // 连成的那五个圈出来，输的一方看得明白是哪条线
        winning.forEach { (x, y) ->
            drawCircle(
                color = WIN_RING,
                radius = radius * .95f,
                center = Offset(px(x), py(y)),
                style = Stroke(width = unit * .1f),
            )
        }
    }
}

/** 交叉点间距：取短边除以 15，横竖各留半个格子当边距。 */
private fun boardUnit(width: Int, height: Int): Float =
    (if (width < height) width else height).toFloat() / GOMOKU_SIZE
