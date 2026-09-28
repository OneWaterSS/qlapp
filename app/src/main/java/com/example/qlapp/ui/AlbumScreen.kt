package com.example.qlapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.qlapp.data.PhotoItem
import com.example.qlapp.util.PhotoDownloader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<PhotoItem?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var deleting by remember { mutableStateOf<List<String>>(emptyList()) }
    val working = vm.albumProgress != null

    /**
     * 下载选中照片前先确认一次存储权限。
     * Android 10 起照片是通过 MediaStore 写进系统相册的，不需要权限，这里直接放行；
     * 只有 Android 9 及以下才需要 WRITE_EXTERNAL_STORAGE，用系统弹窗要一次即可。
     */
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.downloadPhotos(vm.photos.filter { it.id in selectedIds })
        // 被拒绝时不再提示：保存失败的原因用户自己清楚，反复弹窗更烦。
    }
    fun requestDownload(photos: List<PhotoItem>) {
        if (photos.isEmpty()) return
        if (!PhotoDownloader.needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.downloadPhotos(photos)
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun toggle(id: String) {
        selectedIds = ArrayList(if (id in selectedIds) selectedIds - id else selectedIds + id)
    }
    LaunchedEffect(vm.photos) {
        selectedIds = ArrayList(selectedIds.filter { id -> vm.photos.any { it.id == id } })
    }
    BackHandler(selecting) { selecting = false; selectedIds = arrayListOf() }
    // Use the visual media picker instead of the document provider: users see
    // the camera roll as a thumbnail grid and can select many images directly.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris ->
        uris.forEach { uri ->
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Temporary access is sufficient for the current upload. */ }
        }
        vm.uploadPhotos(uris)
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={selecting=false;selectedIds=arrayListOf()}) {
                        Icon(Icons.Default.Close,contentDescription="退出多选")
                    }
                    Text("已选 ${selectedIds.size} 张",Modifier.weight(1f))
                    TextButton(enabled=!working,onClick={
                        selectedIds=if(selectedIds.size==vm.photos.size) arrayListOf() else ArrayList(vm.photos.map { it.id })
                    }) { Text(if(selectedIds.size==vm.photos.size)"取消全选" else "全选") }
                    IconButton(enabled=!working && selectedIds.isNotEmpty(),
                        onClick={requestDownload(vm.photos.filter { it.id in selectedIds })} ) {
                        Icon(Icons.Default.Download,contentDescription="保存选中照片到手机")
                    }
                    IconButton(enabled=!working && selectedIds.isNotEmpty(),onClick={deleting=selectedIds.toList()}) {
                        Icon(Icons.Default.Delete,contentDescription="删除选中照片")
                    }
                }
            }
        } else Text("点击查看大图 · 长按多选 · 支持批量上传与保存",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp,vertical=10.dp))
        vm.albumProgress?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
                Text("正在${progress.action} · ${progress.completed} / ${progress.total}",style=MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress={progress.completed.toFloat()/progress.total},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
            }
        }
        if (!working && (vm.failedUploads.isNotEmpty() || vm.failedDeletes.isNotEmpty())) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                if(vm.failedUploads.isNotEmpty()) TextButton(onClick={vm.uploadPhotos(vm.failedUploads.toList())}) {
                    Text("重试上传 ${vm.failedUploads.size} 张")
                }
                if(vm.failedDeletes.isNotEmpty()) TextButton(onClick={deleting=vm.failedDeletes.toList()}) {
                    Text("重试删除 ${vm.failedDeletes.size} 张")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(vm.photos.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhotoLibrary,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary.copy(alpha=.5f))
                Spacer(Modifier.height(12.dp))
                Text("还没有照片",style=MaterialTheme.typography.titleMedium)
                Text("一次选择多张，留下共同的回忆",style=MaterialTheme.typography.bodySmall)
            } else LazyVerticalGrid(columns=GridCells.Fixed(3),contentPadding=PaddingValues(start=4.dp,end=4.dp,top=4.dp,bottom=96.dp),modifier=Modifier.fillMaxSize()) {
                items(vm.photos,key={it.id}) { photo ->
                    val checked=photo.id in selectedIds
                    Box(Modifier.padding(3.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(enabled=!working,onClick={if(selecting)toggle(photo.id) else preview=photo},
                            onLongClickLabel="选择照片",onLongClick={selecting=true;if(!checked)toggle(photo.id)})
                        .semantics { selected=checked }) {
                        AsyncImage(model=vm.api.photoUrl(photo.id),contentDescription="${photo.uploadedBy}的照片",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                        if(checked)Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha=.25f)).border(3.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(12.dp)))
                        if(selecting) Icon(if(checked)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription=if(checked)"已选中" else "未选中",tint=if(checked)MaterialTheme.colorScheme.primary else Color.White,
                            modifier=Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White.copy(alpha=.8f),CircleShape).size(24.dp))
                    }
                }
            }
            if(!selecting && !working) FloatingActionButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Default.AddAPhoto,contentDescription="批量上传照片")
            }
        }
    }
    if(deleting.isNotEmpty()) ConfirmDialog(title="删除这 ${deleting.size} 张照片？",
        message="这些照片会从两个人的相册中删除，删除后无法恢复。",
        onDismiss={deleting=emptyList()},onConfirm={vm.removePhotos(deleting.toList())})
    preview?.let { photo -> PhotoPreview(vm,photo) { preview=null } }
}

@Composable
private fun PhotoPreview(vm: AppViewModel, photo: PhotoItem, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context=LocalContext.current
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember(photo.id) { mutableStateOf(Size.Zero) }
    var loading by remember(photo.id) { mutableStateOf(true) }
    var loadError by remember(photo.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var saved by remember(photo.id) { mutableStateOf(false) }

    // 保存回手机也要过一遍存储权限，逻辑和相册页里的批量下载一致。
    // 只有真下载成功（onDone 拿到 uri）才点亮「已保存」，失败时按钮仍可再点。
    val onSaved: (Any?) -> Unit = { if (it != null) saved = true }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.downloadPhoto(photo, onSaved) }
    fun save() {
        if (!PhotoDownloader.needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.downloadPhoto(photo, onSaved)
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    fun constrain(value: Offset, zoom: Float): Offset {
        if(imageSize.width<=0 || imageSize.height<=0)return Offset.Zero
        val fit=min(viewport.width/imageSize.width,viewport.height/imageSize.height)
        val dx=max(0f,(imageSize.width*fit*zoom-viewport.width)/2)
        val dy=max(0f,(imageSize.height*fit*zoom-viewport.height)/2)
        return Offset(value.x.coerceIn(-dx,dx),value.y.coerceIn(-dy,dy))
    }
    fun zoomAt(factor: Float, center: Offset, pan: Offset=Offset.Zero) {
        val next=(scale*factor).coerceIn(1f,5f)
        val ratio=next/scale
        offset=constrain(offset*ratio+(center-Offset(viewport.width/2,viewport.height/2))*(1-ratio)+pan,next)
        scale=next
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged {
                viewport=Size(it.width.toFloat(),it.height.toFloat());offset=constrain(offset,scale)
            }.pointerInput(photo.id) { detectTapGestures(onDoubleTap={point->zoomAt(if(scale>1f)1f/scale else 2.5f,point)}) }
                .pointerInput(photo.id) { detectTransformGestures { center,pan,zoom,_->zoomAt(zoom,center,pan) } }) {
                key(retry) {
                    AsyncImage(model=ImageRequest.Builder(context).data(vm.api.photoUrl(photo.id)).size(coil.size.Size.ORIGINAL).build(),
                        contentDescription="照片，双指缩放，双击放大或还原",contentScale=ContentScale.Fit,
                        onSuccess={imageSize=Size(it.result.drawable.intrinsicWidth.toFloat(),it.result.drawable.intrinsicHeight.toFloat());loading=false;loadError=false},
                        onError={loading=false;loadError=true},
                        modifier=Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y })
                }
            }
            if(loading)CircularProgressIndicator(Modifier.align(Alignment.Center),color=Color.White)
            if(loadError)TextButton(onClick={loading=true;loadError=false;retry++},modifier=Modifier.align(Alignment.Center)) { Text("图片加载失败，点击重试",color=Color.White) }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().background(Color.Black.copy(alpha=.4f)).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("来自 ${photo.uploadedBy} · ${formatTime(photo.createdAt)}",color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                IconButton(onClick=onDismiss) { Icon(Icons.Default.Close,"关闭",tint=Color.White) }
            }
            Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).background(Color.Black.copy(alpha=.5f),RoundedCornerShape(24.dp)),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={zoomAt(1/1.5f,Offset(viewport.width/2,viewport.height/2))},enabled=scale>1f) { Icon(Icons.Default.Remove,"缩小",tint=Color.White.copy(alpha=if(scale>1f)1f else .3f)) }
                TextButton(onClick={scale=1f;offset=Offset.Zero}) { Text("${(scale*100).toInt()}%",color=Color.White) }
                IconButton(onClick={zoomAt(1.5f,Offset(viewport.width/2,viewport.height/2))},enabled=scale<5f) { Icon(Icons.Default.Add,"放大",tint=Color.White.copy(alpha=if(scale<5f)1f else .3f)) }
                IconButton(onClick={save()},enabled=vm.albumProgress==null && !saved) {
                    Icon(if(saved)Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription=if(saved)"已保存到手机" else "保存到手机",
                        tint=Color.White.copy(alpha=if(vm.albumProgress!=null) .4f else 1f))
                }
                IconButton(onClick={confirmDelete=true},enabled=vm.albumProgress==null) { Icon(Icons.Default.Delete,"删除",tint=Color.White) }
            }
        }
    }
    if(confirmDelete)ConfirmDialog("删除这张照片？","删除后两个人的相册里都不会再有它了",{confirmDelete=false},{vm.removePhotos(listOf(photo.id));onDismiss()})
}
