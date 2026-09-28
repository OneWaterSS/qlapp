package com.example.qlapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.qlapp.data.WallMessage

/** 便签右下角的时间，只显示到分钟。 */
private val NOTE_TIME = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun noteTime(ts: Long): String =
    java.util.Date(if (ts > 0L) ts else System.currentTimeMillis()).toInstant()
        .atZone(java.time.ZoneId.systemDefault())
        .format(NOTE_TIME)

/**
 * 便签的配色。两个人的便签要用不同底色区分，
 * 所以按「是不是本机写的」挑两套颜色，而不是按作者昵称（昵称可能重复）。
 */
private data class NoteColors(val fill: Color, val accent: Color, val text: Color)

@Composable
private fun noteColors(isMine: Boolean): NoteColors {
    // Amber（自己）和 Blue（对方）在浅色底上都够看清，且色相互补、一眼能分。
    return if (isMine) {
        NoteColors(
            fill = Color(0xFFFAEEDA),
            accent = Color(0xFF854F0B),
            text = Color(0xFF412402),
        )
    } else {
        NoteColors(
            fill = Color(0xFFE6F1FB),
            accent = Color(0xFF185FA5),
            text = Color(0xFF042C53),
        )
    }
}

@Composable
fun WallScreen(vm: AppViewModel) {
    // 切走再切回来会重新进组合，所以这里每次进留言墙都会重新拉一次最新留言。
    LaunchedEffect(Unit) { vm.openWall() }

    Column(Modifier.fillMaxSize()) {
        if (vm.messages.isEmpty()) {
            EmptyWall()
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(vm.messages, key = { it.id }) { message ->
                    NoteCard(message, isMine = message.mine)
                }
            }
        }
        Composer(vm)
    }
}

/**
 * 空墙占位。
 * 声明成 ColumnScope 的扩展才能用 weight —— 直接写成普通 Composable 的话
 * 拿不到外层 Column 的 scope，weight 就没法调用。
 */
@Composable
private fun ColumnScope.EmptyWall() {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Forum,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(12.dp))
        Text("墙上还空着", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "留句话给对方吧，比如「冰箱里有你爱吃的」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 一张便签。
 *
 * 高度随内容长短变化（配合瀑布流布局），内容过长就先在卡片里截断，
 * 不然一条很长的留言会把整列撑得很高、其它便签都被挤下去。
 */
@Composable
private fun NoteCard(message: WallMessage, isMine: Boolean) {
    val colors = noteColors(isMine)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.fill,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (isMine) "${message.author.ifBlank { "我" }}（我）" else message.author.ifBlank { "对方" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                maxLines = 8,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = noteTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 底部输入条。
 *
 * imePadding 是必须的：Activity 开了 enableEdgeToEdge()，Android 15 起更是强制
 * 边到边，这种情况下 windowSoftInputMode="adjustResize" 不再压缩窗口，
 * 键盘是直接盖在内容上的。所以得自己把 IME 的高度补成内边距，
 * 否则输入框会被键盘挡住。
 *
 * 另外它必须加在 Scaffold 内容区里、底部导航栏之上——Scaffold 只处理了
 * 系统栏（状态栏/导航栏）的 inset，键盘高度得由调用方自己负责。
 */
@Composable
private fun Composer(vm: AppViewModel) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = text.isNotBlank() && !vm.busy
    val focus = LocalFocusManager.current

    fun send() {
        if (!canSend) return
        vm.postMessage(text.trim())
        text = ""
        focus.clearFocus()
        keyboard?.hide()
    }

    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 500) text = it },
                placeholder = { Text("给对方留句话…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { send() },
                enabled = canSend,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
