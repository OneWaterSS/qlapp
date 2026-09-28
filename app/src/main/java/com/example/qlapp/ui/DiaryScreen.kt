package com.example.qlapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.qlapp.data.DiaryEntry
import com.example.qlapp.data.Mood
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 日历从周一排到周日，和国内习惯一致。 */
private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 月份标题用「2026 年 9 月」，不要前导零。 */
private fun monthTitle(month: String): String {
    val ym = YearMonth.parse(month)
    return "${ym.year} 年 ${ym.monthValue} 月"
}

private fun dayTitle(date: String): String {
    val d = LocalDate.parse(date)
    return "${d.monthValue} 月 ${d.dayOfMonth} 日"
}

/** 列表里用「9/24」，比「9 月 24 日」省地方。 */
private val SHORT_DATE = DateTimeFormatter.ofPattern("M/d")

private fun shortDate(date: String): String =
    runCatching { LocalDate.parse(date).format(SHORT_DATE) }.getOrDefault(date)

/**
 * 把「按日期分组」的日记摊平成一维列表，给日历下方的列表用。
 *
 * 日期倒序（最近的排最上面）；同一天两人都写了，就按写入时间倒序，谁的新谁在上面。
 * `date` 是 `YYYY-MM-DD`，字典序恰好等于时间序，所以直接比字符串就够。
 * 抽成纯函数是为了能单测——排序错了界面只会「看着乱」，但看不出错在哪。
 */
internal fun monthDiaryRows(entries: Map<String, List<DiaryEntry>>): List<DiaryEntry> =
    entries.values.flatten().sortedWith(
        compareByDescending<DiaryEntry> { it.date }.thenByDescending { it.updatedAt },
    )

@Composable
fun DiaryScreen(vm: AppViewModel) {
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }
    var writingDate by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MonthHeader(vm)
            WeekHeader()
            CalendarGrid(
                month = vm.diaryCursor,
                entries = vm.diaryMonth,
                onPickDay = { vm.openDiaryDay(it) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MonthDiaryList(
                entries = vm.diaryMonth,
                onOpenDay = { vm.openDiaryDay(it) },
                modifier = Modifier.weight(1f),
            )
        }

        // 写今天的日记是最常用的动作，不该逼用户先点日历格子再点按钮。
        val today = remember { LocalDate.now().toDiaryDate() }
        val myToday = vm.diaryMonth[today].orEmpty().firstOrNull { it.mine }
        ExtendedFloatingActionButton(
            onClick = { if (myToday != null) editing = myToday else writingDate = today },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            text = { Text(if (myToday != null) "改今天的" else "写今天的") },
        )
    }

    // 点某天 → 当天详情（自己的那条可改可删，对方的只读）
    val openDate = vm.diaryDay
    if (openDate != null) {
        DayDialog(
            date = openDate,
            entries = vm.diaryDayEntries,
            onWrite = { writingDate = openDate },
            onEdit = { editing = it },
            onDelete = { vm.removeDiary(openDate) },
            onDismiss = { vm.closeDiaryDay() },
        )
    }

    // 写/编辑时先把详情弹窗收掉，不然两层 AlertDialog 会叠在一起。
    writingDate?.let { date ->
        LaunchedEffect(date) { vm.closeDiaryDay() }
        DiaryEditDialog(
            date = date,
            existing = null,
            onDismiss = { writingDate = null },
            onSave = { mood, text -> vm.saveDiary(date, mood, text) },
        )
    }

    editing?.let { entry ->
        LaunchedEffect(entry.date) { vm.closeDiaryDay() }
        DiaryEditDialog(
            date = entry.date,
            existing = entry,
            onDismiss = { editing = null },
            onSave = { mood, text -> vm.saveDiary(entry.date, mood, text) },
        )
    }
}

/* ------------------------------ 月份切换条 ------------------------------ */

@Composable
private fun MonthHeader(vm: AppViewModel) {
    val thisMonth = remember { YearMonth.now().toString() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.shiftDiaryMonth(-1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = monthTitle(vm.diaryCursor),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { vm.shiftDiaryMonth(1) }) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
        }
        // 翻远了要回得来，所以留一个「回今天」快捷入口
        if (vm.diaryCursor != thisMonth) {
            TextButton(onClick = { vm.goToDiaryMonth(thisMonth) }) { Text("回今天") }
        }
    }
}

@Composable
private fun WeekHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        WEEK_LABELS.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* ------------------------------- 日历网格 ------------------------------- */

/**
 * 月历。每格左上角是日期数字，下面是两个人的心情表情。
 *
 * 表情位置是固定的：左边永远是本机，右边永远是对方。
 * 如果按写入顺序排，她先写就她的在左边，日历看着会乱跳，
 * 所以用后端回的 `mine` 判断哪个槽位是自己，另一条放右边。
 */
@Composable
private fun CalendarGrid(
    month: String,
    entries: Map<String, List<DiaryEntry>>,
    onPickDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ym = remember(month) { YearMonth.parse(month) }
    val today = remember { LocalDate.now().toDiaryDate() }

    // 前面补空格，让 1 号落在正确的星期列上（周一 = 0）
    val leadingBlanks = remember(ym) { ym.atDay(1).dayOfWeek.value - 1 }
    val totalCells = leadingBlanks + ym.lengthOfMonth()

    // 整月最多 31 天，一次画完不需要 Lazy，避免嵌套滚动冲突
    val rows = (totalCells + 6) / 7

    Column(modifier.fillMaxWidth()) {
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - leadingBlanks + 1
                    // 格子做成正方形：宽度由 weight 定，高度靠 aspectRatio 跟着宽度走。
                    // ⚠️ 不要再用 weight 去撑高度（早先版本 CalendarGrid 带 weight(1f)、
                    // 每行再 weight(1f)），那会让每格被拉伸成竖长条，高屏上格子下半截
                    // 全是空白——「日期框留白太多」就是这么来的。
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (dayNumber in 1..ym.lengthOfMonth()) {
                            val date = ym.atDay(dayNumber).toDiaryDate()
                            DayCell(
                                day = dayNumber,
                                date = date,
                                entries = entries[date].orEmpty(),
                                isToday = date == today,
                                onClick = { onPickDay(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    date: String,
    entries: List<DiaryEntry>,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    // 本机那条放左槽，对方放右槽；没写就留空。
    val mine = entries.firstOrNull { it.mine }
    val theirs = entries.firstOrNull { !it.mine }
    val hasEntry = entries.isNotEmpty()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = when {
            isToday -> MaterialTheme.colorScheme.primaryContainer
            hasEntry -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
        border = when {
            isToday -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            hasEntry -> BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            else -> null
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 日期贴顶、表情贴底，格子里的竖向空间被用满，不再留一大块空
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = when {
                    isToday -> FontWeight.SemiBold
                    hasEntry -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    hasEntry -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 两个槽位都用固定宽度的槽装着，谁在谁不在位置都不变
                MoodSlot(mine?.mood)
                MoodSlot(theirs?.mood)
            }
        }
    }
}

@Composable
private fun MoodSlot(mood: Mood?) {
    Box(Modifier.width(17.dp), contentAlignment = Alignment.Center) {
        if (mood != null) {
            Text(text = mood.emoji, fontSize = 14.sp)
        }
    }
}

/* ----------------------------- 本月日记列表 ----------------------------- */

/**
 * 日历下面是这个月所有日记的列表。
 *
 * 存在的理由：格子改成正方形后，屏幕下方会空出一块；与其留白，不如把这个月
 * 写过的东西平铺出来——不用逐天点开就能回顾，点一条直接跳到那天详情。
 */
@Composable
private fun MonthDiaryList(
    entries: Map<String, List<DiaryEntry>>,
    onOpenDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 一天最多两条（两人各一条）。排序规则见 monthDiaryRows。
    val rows = remember(entries) { monthDiaryRows(entries) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本月日记",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (rows.isNotEmpty()) {
                Text(
                    text = "${rows.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = "这个月还没有人写日记",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 底部留出 FAB 的高度，免得最后一条被按钮盖住
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // key 用「日期 + 署名」：同一天两条的署名必然不同，
                // 而 deviceId 可能相同（两个人都从同一台手机写过），拿它当 key 会撞。
                items(rows, key = { it.date + "|" + it.author }) { entry ->
                    DiaryRow(
                        entry = entry,
                        isMine = entry.mine,
                        onClick = { onOpenDay(entry.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry, isMine: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortDate(entry.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(text = entry.mood.emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.author.ifBlank { if (isMine) "我" else "对方" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isMine) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = entry.content.ifBlank { entry.mood.label },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* ------------------------------ 当天详情 ------------------------------ */

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DayDialog(
    date: String,
    entries: List<DiaryEntry>,
    onWrite: () -> Unit,
    onEdit: (DiaryEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val mine = entries.firstOrNull { it.mine }
    val theirs = entries.firstOrNull { !it.mine }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dayTitle(date)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text(
                        text = "这天还没有日记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    theirs?.let { EntryBlock(it, editable = false, onEdit = {}, onDelete = {}) }
                    if (theirs != null && mine != null) Spacer(Modifier.height(14.dp))
                    mine?.let {
                        EntryBlock(it, editable = true, onEdit = { onEdit(it) }, onDelete = { confirmDelete = true })
                    }
                }
            }
        },
        confirmButton = {
            if (mine == null) {
                TextButton(onClick = onWrite) { Text("写日记") }
            } else {
                TextButton(onClick = { onEdit(mine) }) { Text("编辑") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除这天的日记？",
            message = "你写的内容会从日历上消失，删除后无法恢复。",
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete(); onDismiss() },
        )
    }
}

@Composable
private fun EntryBlock(
    entry: DiaryEntry,
    editable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = entry.mood.emoji, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.author.ifBlank { "对方" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = entry.mood.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.updatedAt > 0L) {
                Text(
                    text = "写于 " + java.util.Date(entry.updatedAt).toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime().format(TIME_FORMAT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        if (editable) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (entry.content.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 28.dp),
        )
    }
}

/* ------------------------------- 写 / 编辑 ------------------------------- */

@Composable
private fun DiaryEditDialog(
    date: String,
    existing: DiaryEntry?,
    onDismiss: () -> Unit,
    onSave: (Mood, String) -> Unit,
) {
    var mood by remember(date) { mutableStateOf(existing?.mood) }
    var text by remember(date) { mutableStateOf(existing?.content.orEmpty()) }
    val canSave = mood != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "写日记 · ${dayTitle(date)}" else "改日记 · ${dayTitle(date)}") },
        text = {
            Column {
                Text(
                    text = "今天心情怎样？",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 五个心情横排，选中的那个加个底色圈
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Mood.entries.forEach { option ->
                        val selected = option == mood
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { mood = option }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(text = option.emoji, fontSize = 22.sp)
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 2000) text = it },
                    label = { Text("今天想说点什么") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { mood?.let { onSave(it, text.trim()) }; onDismiss() }, enabled = canSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
