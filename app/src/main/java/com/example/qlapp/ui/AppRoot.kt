package com.example.qlapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.location.LocationManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private fun daysTogether(since: Long): Int {
    val start = Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDate()
    return ChronoUnit.DAYS.between(start, LocalDate.now()).toInt() + 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    var tab by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    // 还没认领过身份 = 第一次进来，先选小江 / 甜甜，选完才进主界面。
    // 不能只看「昵称是否为空」：老版本装上来的人本地早就有昵称了，只看昵称他们永远见不到这一屏。
    var showIdentity by remember { mutableStateOf(!vm.prefs.identityChosen) }
    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadLocation(context, vm) }

    LaunchedEffect(Unit) {
        vm.refreshAll()
        // 已经选过身份、但还没认领过旧数据的（老版本升上来的），补认领一次
        if (vm.prefs.identityChosen && !vm.prefs.claimDone) vm.claimOldData()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) loadLocation(context, vm) else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // 身份没定之前只渲染这一屏：顶栏、Tab 都不出来，也省得底下界面先闪一下。
    // 数据在上一行的 LaunchedEffect 里已经先拉起来了，选完就能直接用。
    if (showIdentity) {
        IdentityGate(onPick = { vm.chooseIdentity(it); showIdentity = false })
        return
    }

    // 游戏 Tab 的红点：5 秒看一眼五子棋有没有「等我」的事。
    // 放在身份选择之后，所以第一次进软件选完身份才会开始拉。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                vm.refreshGomokuAlert()
                delay(5_000)
            }
        }
    }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.consumeNotice()
        }
    }

    val anniversary = vm.prefs.anniversary

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // 键盘弹起时不要让 Scaffold 把 IME 高度算进内容区，
        // 否则底部导航栏和输入框会各让一次、留出一大块空白。
        // 键盘高度交给留言页的输入条自己用 imePadding() 处理。
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                title = {
                    Column {
                        Text(
                            text = "小江&甜甜",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "认识 ${daysTogether(anniversary)} 天",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        bottomBar = {
            // 五个 Tab 的话标签会被挤换行，所以统一收成两个字。
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("相册") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = null) },
                    label = { Text("任务") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = {
                        // 对方在等你（发来邀请 / 该你落子）时亮个小点
                        BadgedBox(badge = { if (vm.gomokuAlert) Badge() }) {
                            Icon(Icons.Default.SportsEsports, contentDescription = null)
                        }
                    },
                    label = { Text("游戏") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    label = { Text("日记") },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = {
                        // 未读用小圆点提示就够了，数字挤在图标上反而看不清
                        BadgedBox(
                            badge = {
                                if (vm.wallUnread > 0) {
                                    Badge { Text(if (vm.wallUnread > 99) "99+" else "${vm.wallUnread}") }
                                }
                            },
                        ) { Icon(Icons.Default.Forum, contentDescription = null) }
                    },
                    label = { Text("留言") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                WeatherCard(vm)
                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        0 -> AlbumScreen(vm)
                        1 -> TaskScreen(vm)
                        2 -> GameHubScreen(vm)
                        3 -> DiaryScreen(vm)
                        else -> WallScreen(vm)
                    }
                }
            }

            if (vm.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }
    }
}

private fun loadLocation(context: android.content.Context, vm: AppViewModel) {
    val lm = context.getSystemService(LocationManager::class.java)
    val provider = when { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER; else -> LocationManager.NETWORK_PROVIDER }
    if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        lm.getLastKnownLocation(provider)?.let { vm.loadWeather(it.latitude, it.longitude) }
    }
}

@Composable private fun WeatherCard(vm: AppViewModel) {
    val w = vm.weather ?: return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("今日天气 · ${w.city}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
            Text("${w.description}  ${w.temperature}℃", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(w.suggestion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
