package com.rui.filesmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class Nav {
    data object Home : Nav()
    data class LocalList(val category: Category) : Nav()
    data class SearchList(val query: String) : Nav()
    data object NasHome : Nav()
    data class NasEdit(val conn: NasConnection?) : Nav()
    data class NasBrowse(val conn: NasConnection, val path: String) : Nav()
    data class NasShares(val conn: NasConnection) : Nav()
    data class Play(val source: String, val title: String) : Nav()
    data class ViewImage(val source: String, val title: String, val isNas: Boolean, val conn: NasConnection? = null, val nasPath: String = "") : Nav()
    data class SafBrowse(val treeUri: String) : Nav()
    data object Favorites : Nav()
    data object Settings : Nav()
}

private var openSettingsCallback: (() -> Unit)? = null

private val LightColors = lightColorScheme(
    primary = Color(0xFF3E60D2),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2FB),
    onSurface = Color(0xFF181C26),
    onSurfaceVariant = Color(0xFF6E7687),
)

@Composable
fun FilesManagerApp() {
    val context = LocalContext.current
    val store = remember { NasStore(context) }
    val navStack = remember { mutableStateListOf<Nav>(Nav.Home) }
    val nav = navStack.last()
    var favorites by remember { mutableStateOf(store.loadFavorites()) }
    var connections by remember { mutableStateOf(store.loadConnections()) }
    var folderUris by remember { mutableStateOf(store.loadFolderUris()) }

    fun push(n: Nav) { navStack.add(n) }
    fun pop() { if (navStack.size > 1) navStack.removeAt(navStack.size - 1) }
    openSettingsCallback = { if (nav !is Nav.Settings) push(Nav.Settings) }

    var startupUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var crashText by remember { mutableStateOf(CrashLog.read(context)) }
    var startupChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CrashLog.setStage(context, "root-composed") }
    LaunchedEffect(Unit) {
        if (!startupChecked && Updater.autoCheck(store)) {
            startupChecked = true
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check(context, store) }.getOrNull() }
            if (info != null) startupUpdate = info
        }
    }

    // メディア権限リクエスト
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}
    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    BackHandler(enabled = navStack.size > 1) { pop() }

    MaterialTheme(colorScheme = LightColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            crashText?.let { text ->
                AlertDialog(
                    onDismissRequest = { CrashLog.clear(context); crashText = null },
                    title = { Text("前回クラッシュしました") },
                    text = {
                        Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "クラッシュ記録を共有"))
                            } catch (e: Exception) { /* noop */ }
                        }) { Text("共有") }
                    },
                    dismissButton = {
                        TextButton(onClick = { CrashLog.clear(context); crashText = null }) { Text("消して閉じる") }
                    },
                )
            }
            startupUpdate?.let { info ->
                AlertDialog(
                    onDismissRequest = { startupUpdate = null },
                    title = { Text("アップデートがあります") },
                    text = { Text("FilesManager v${info.versionName} が利用できます。\n${info.notes}") },
                    confirmButton = {
                        TextButton(onClick = { startupUpdate = null; push(Nav.Settings) }) { Text("設定で更新") }
                    },
                    dismissButton = {
                        TextButton(onClick = { startupUpdate = null }) { Text("あとで") }
                    },
                )
            }
            when (val n = nav) {
                is Nav.Home -> HomeScreen(
                    favoritesCount = favorites.size,
                    folderUris = folderUris,
                    onCategory = { push(Nav.LocalList(it)) },
                    onSearch = { push(Nav.SearchList(it)) },
                    onNas = { push(Nav.NasHome) },
                    onFavorites = { push(Nav.Favorites) },
                    onFolderAdded = { uriStr ->
                        folderUris = folderUris + uriStr
                        store.saveFolderUris(folderUris)
                    },
                    onOpenFolder = { push(Nav.SafBrowse(it)) },
                )
                is Nav.LocalList -> LocalListScreen(
                    title = n.category.label,
                    loader = { LocalRepo.query(context.contentResolver, n.category) },
                    favorites = favorites,
                    onToggleFavorite = { item ->
                        val key = "L|${item.uri}"
                        favorites = if (key in favorites) favorites - key else favorites + key
                        store.saveFavorites(favorites)
                    },
                    onPlay = { push(Nav.Play(it.uri.toString(), it.name)) },
                    onViewImage = { push(Nav.ViewImage(it.uri.toString(), it.name, isNas = false)) },
                    onBack = { pop() },
                )
                is Nav.SearchList -> LocalListScreen(
                    title = "検索: ${n.query}",
                    loader = { LocalRepo.search(context.contentResolver, n.query) },
                    favorites = favorites,
                    onToggleFavorite = { item ->
                        val key = "L|${item.uri}"
                        favorites = if (key in favorites) favorites - key else favorites + key
                        store.saveFavorites(favorites)
                    },
                    onPlay = { push(Nav.Play(it.uri.toString(), it.name)) },
                    onViewImage = { push(Nav.ViewImage(it.uri.toString(), it.name, isNas = false)) },
                    onBack = { pop() },
                )
                is Nav.NasHome -> NasHomeScreen(
                    connections = connections,
                    onAdd = { push(Nav.NasEdit(null)) },
                    onEdit = { push(Nav.NasEdit(it)) },
                    onDelete = { c ->
                        connections = connections.filter { it.id != c.id }
                        store.remove(c.id)
                    },
                    onOpen = { if (it.share.isBlank()) push(Nav.NasShares(it)) else push(Nav.NasBrowse(it, "")) },
                    onBack = { pop() },
                )
                is Nav.NasEdit -> NasEditScreen(
                    initial = n.conn,
                    onSave = { c ->
                        store.upsert(c)
                        connections = store.loadConnections()
                        pop()
                    },
                    onBack = { pop() },
                )
                is Nav.NasBrowse -> NasBrowseScreen(
                    conn = n.conn,
                    path = n.path,
                    favorites = favorites,
                    onToggleFavorite = { e ->
                        val key = "N|${n.conn.id}|${e.path}"
                        favorites = if (key in favorites) favorites - key else favorites + key
                        store.saveFavorites(favorites)
                    },
                    onOpenDir = { push(Nav.NasBrowse(n.conn, it.path)) },
                    onPlay = { source, title -> push(Nav.Play(source, title)) },
                    onViewImage = { e -> push(Nav.ViewImage("", e.name, isNas = true, conn = n.conn, nasPath = e.path)) },
                    onBack = { pop() },
                )
                is Nav.NasShares -> NasSharesScreen(
                    conn = n.conn,
                    onOpenShare = { name -> push(Nav.NasBrowse(n.conn.copy(share = name), "")) },
                    onBack = { pop() },
                )
                is Nav.Play -> PlayScreen(source = n.source, title = n.title, onBack = { pop() })
                is Nav.ViewImage -> ImageViewScreen(nav = n, onBack = { pop() })
                is Nav.SafBrowse -> SafBrowseScreen(
                    treeUriStr = n.treeUri,
                    onPlay = { uriStr, name -> push(Nav.Play(uriStr, name)) },
                    onViewImage = { uriStr, name -> push(Nav.ViewImage(uriStr, name, isNas = false)) },
                    onBack = { pop() },
                )
                is Nav.Settings -> SettingsScreen(
                    store = store,
                    connectionsCount = connections.size,
                    onOpenNas = { push(Nav.NasHome) },
                    onBack = { pop() },
                )
                is Nav.Favorites -> FavoritesScreen(
                    favorites = favorites,
                    connections = connections,
                    onPlay = { push(Nav.Play(it.uri.toString(), it.name)) },
                    onViewImage = { push(Nav.ViewImage(it.uri.toString(), it.name, isNas = false)) },
                    onOpenNas = { conn, path -> push(Nav.NasBrowse(conn, parentSmbPath(path))) },
                    onBack = { pop() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
        },
        actions = {
            IconButton(onClick = { openSettingsCallback?.invoke() }) {
                Icon(Icons.Filled.Settings, contentDescription = "設定")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun HomeScreen(
    favoritesCount: Int,
    folderUris: List<String>,
    onCategory: (Category) -> Unit,
    onSearch: (String) -> Unit,
    onNas: () -> Unit,
    onFavorites: () -> Unit,
    onFolderAdded: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val context = LocalContext.current
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) { /* 読み取りだけでも可 */ }
            onFolderAdded(uri.toString())
            onOpenFolder(uri.toString())
        }
    }

    val stat = remember {
        try {
            val fs = StatFs(Environment.getDataDirectory().path)
            fs.totalBytes to fs.availableBytes
        } catch (e: Exception) { 0L to 0L }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ファイル", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("FilesManager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(Icons.Filled.Search, contentDescription = "検索", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { openSettingsCallback?.invoke() }) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", tint = MaterialTheme.colorScheme.primary)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (searchOpen) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("ファイル名で検索") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { if (query.isNotBlank()) onSearch(query.trim()) }) { Text("検索") }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (stat.first > 0) {
                item {
                    Text("内部ストレージ", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val usedRatio = 1f - stat.second.toFloat() / stat.first.toFloat()
                    LinearProgressIndicator(progress = { usedRatio }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "空き ${fmtSize(stat.second)} / ${fmtSize(stat.first)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            item { Text("カテゴリ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Spacer(Modifier.height(8.dp)) }
            val cats = listOf(
                Triple(Category.DOWNLOAD, AppIcons.Download, "ダウンロード"),
                Triple(Category.IMAGE, AppIcons.Image, "画像"),
                Triple(Category.VIDEO, AppIcons.Movie, "動画"),
                Triple(Category.AUDIO, AppIcons.MusicNote, "音声"),
                Triple(Category.DOCUMENT, AppIcons.Description, "ドキュメント"),
                Triple(Category.APP, AppIcons.Android, "アプリ"),
                Triple(Category.ARCHIVE, AppIcons.Archive, "圧縮ファイル"),
                Triple(Category.RECENT, AppIcons.History, "最近のファイル"),
            )
            items(cats.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((cat, icon, label) in pair) {
                        Card(
                            modifier = Modifier.weight(1f).clickable { onCategory(cat) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { Text("接続先", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNas() },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ネットワーク・NAS / クラウド", fontWeight = FontWeight.Bold)
                            Text(
                                "SMB（NAS）・保存済みアカウントで自動ログイン",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { Text("コレクション", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier.weight(1f).clickable { onFavorites() },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text("お気に入り（$favoritesCount）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f).clickable { treeLauncher.launch(null) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(AppIcons.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text("フォルダを開く", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (folderUris.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                item { Text("登録済みフォルダ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
                items(folderUris) { uriStr ->
                    val name = remember(uriStr) {
                        try {
                            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.name ?: uriStr
                        } catch (e: Exception) { uriStr }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenFolder(uriStr) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(name)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun LocalListScreen(
    title: String,
    loader: suspend () -> List<LocalItem>,
    favorites: Set<String>,
    onToggleFavorite: (LocalItem) -> Unit,
    onPlay: (LocalItem) -> Unit,
    onViewImage: (LocalItem) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var itemsState by remember { mutableStateOf<List<LocalItem>?>(null) }
    var sortMode by remember { mutableStateOf(0) } // 0=新しい順 1=名前 2=サイズ
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { loader() } }
        r.onSuccess { itemsState = it }.onFailure { loadError = it.message; itemsState = emptyList() }
    }
    val sorted = remember(itemsState, sortMode) {
        val base = itemsState ?: emptyList()
        when (sortMode) {
            1 -> base.sortedBy { it.name.lowercase() }
            2 -> base.sortedByDescending { it.size }
            else -> base.sortedByDescending { it.modified }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar(title, onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("新しい順", "名前順", "サイズ順").forEachIndexed { i, label ->
                TextButton(onClick = { sortMode = i }) {
                    Text(
                        label,
                        fontWeight = if (sortMode == i) FontWeight.Bold else FontWeight.Normal,
                        color = if (sortMode == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val current = itemsState
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (current.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (loadError != null) "読み込みに失敗しました: $loadError" else "ファイルがありません（権限を許可してね）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sorted, key = { it.uri.toString() + it.id }) { item ->
                    LocalItemRow(
                        item = item,
                        isFav = ("L|${item.uri}") in favorites,
                        onToggleFavorite = { onToggleFavorite(item) },
                        onClick = {
                            when {
                                item.isVideo || item.isAudio -> onPlay(item)
                                item.isImage -> onViewImage(item)
                                else -> {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(item.uri, item.mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalItemRow(
    item: LocalItem,
    isFav: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri) {
        thumb = withContext(Dispatchers.IO) { LocalRepo.thumbnail(context.contentResolver, item) }
    }
    var menu by remember { mutableStateOf(false) }
    val dateStr = remember(item.modified) {
        if (item.modified > 0) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(item.modified)) else ""
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        item.isVideo -> AppIcons.Movie
                        item.isAudio -> AppIcons.MusicNote
                        item.isImage -> AppIcons.Image
                        else -> AppIcons.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(
                listOf(fmtSize(item.size), dateStr).filter { it.isNotEmpty() }.joinToString(" ・ "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFav) Icons.Filled.Star else AppIcons.StarBorder,
                contentDescription = "お気に入り",
                tint = if (isFav) Color(0xFFF2A900) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("共有") },
                    onClick = {
                        menu = false
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = item.mime
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, item.name))
                        } catch (e: Exception) {
                            Toast.makeText(context, "共有できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("削除") },
                    onClick = {
                        menu = false
                        try {
                            val n = context.contentResolver.delete(item.uri, null, null)
                            Toast.makeText(context, if (n > 0) "削除しました" else "削除できませんでした", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "削除できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayScreen(source: String, title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
            )
        },
        containerColor = Color.Black,
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            PlayerScreen(source = source, title = title)
        }
    }
}

@Composable
private fun ImageViewScreen(nav: Nav.ViewImage, onBack: () -> Unit) {
    val context = LocalContext.current
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nav) {
        withContext(Dispatchers.IO) {
            try {
                val bytes: ByteArray = if (nav.isNas && nav.conn != null) {
                    val baos = ByteArrayOutputStream()
                    SmbRepo.copyTo(nav.conn, nav.nasPath, baos)
                    baos.toByteArray()
                } else {
                    context.contentResolver.openInputStream(Uri.parse(nav.source))?.readBytes()
                        ?: throw IllegalStateException("開けません")
                }
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) error = "画像を読み込めませんでした"
            } catch (e: Exception) {
                error = "読み込みに失敗: ${e.message}"
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        SimpleBar(nav.title, onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                bmp != null -> Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = nav.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                error != null -> Text(error!!, color = Color.White)
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun NasHomeScreen(
    connections: List<NasConnection>,
    onAdd: () -> Unit,
    onEdit: (NasConnection) -> Unit,
    onDelete: (NasConnection) -> Unit,
    onOpen: (NasConnection) -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<NasConnection?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar("ネットワーク・NAS", onBack)
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (connections.isEmpty()) {
                item {
                    Text(
                        "NASがまだ登録されていません。下のボタンから追加してね。\nアカウントは暗号化してこの端末に保存され、次回からは自動でログインします。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            items(connections, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(c) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.label.ifBlank { c.host }, fontWeight = FontWeight.Bold)
                            Text(
                                if (c.share.isBlank()) "smb://${c.host} ・ ${c.username}（共有は開くときに選択）" else "smb://${c.host}/${c.share} ・ ${c.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "保存済みアカウントで自動ログイン",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("編集") }, onClick = { menu = false; onEdit(c) })
                                DropdownMenuItem(text = { Text("削除") }, onClick = { menu = false; confirmDelete = c })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            item {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("NASを追加（SMB）")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("接続先を削除") },
            text = { Text("「${c.label.ifBlank { c.host }}」の保存アカウントを削除します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = { onDelete(c); confirmDelete = null }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun NasEditScreen(
    initial: NasConnection?,
    onSave: (NasConnection) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var share by remember { mutableStateOf(initial?.share ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }

    var discovering by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<DiscoveredNas>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var foundShares by remember { mutableStateOf<List<String>>(emptyList()) }

    fun build(): NasConnection = NasConnection(
        id = initial?.id ?: UUID.randomUUID().toString(),
        label = label.trim(),
        host = host.trim(),
        share = share.trim().trim('/').replace('/', '\\'),
        username = username.trim(),
        password = password,
        domain = domain.trim(),
    )

    fun doDiscover() {
        if (discovering) return
        discovering = true; searchNote = null
        scope.launch {
            val r = runCatching {
                NasDiscovery.discover(context) { list -> found = list }
            }
            discovering = false
            searchNote = when {
                r.isFailure -> "検索できませんでした: ${r.exceptionOrNull()?.message}"
                found.isEmpty() -> "見つかりませんでした。NASの電源と、同じWi-Fiにつながっているかを確認して、もう一度検索してね。下の欄にIPアドレスを直接入力してもOKです。"
                else -> "${found.size}件見つかりました。タップすると入力欄に入ります。"
            }
        }
    }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { doDiscover() }
    fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            doDiscover()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar(if (initial == null) "NASを追加" else "NASを編集", onBack)
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "まず「検索」でNASを見つけてタップ。あとはユーザー名とパスワードでログインするだけです。共有フォルダは、NASを開いたときに直下のフォルダとしてそのまま並びます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { startDiscovery() }, modifier = Modifier.fillMaxWidth(), enabled = !discovering) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (discovering) "検索中…（数秒かかります）" else "ネットワーク上のNASを検索")
                }
                if (discovering) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (searchNote != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(searchNote!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
            }
            items(found, key = { it.host }) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        host = d.host
                        if (label.isBlank() && d.name.isNotBlank()) label = d.name
                        statusMsg = "「${d.name.ifBlank { d.host }}」を入力欄に入れました。下でログインしてね。"
                        statusOk = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(d.name.ifBlank { "（名前不明の機器）" }, fontWeight = FontWeight.Bold)
                            Text(d.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(label, { label = it }, label = { Text("表示名（例：自宅NAS）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(host, { host = it }, label = { Text("ホスト名 / IPアドレス（例：192.168.1.10）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(username, { username = it }, label = { Text("ユーザー名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(domain, { domain = it }, label = { Text("ドメイン / ワークグループ（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val c = build()
                        if (c.host.isBlank() || c.username.isBlank()) {
                            Toast.makeText(context, "ホストとユーザー名を入力してね", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        busy = "login"; statusMsg = null; foundShares = emptyList()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    SmbRepo.loginTest(c)
                                    SmbRepo.probeShares(c)
                                }
                            }
                            busy = ""
                            r.onSuccess { shares ->
                                foundShares = shares
                                if (shares.isNotEmpty()) {
                                    // ログインできたら設定は完了。共有は選ばせず、そのまま保存して戻る。
                                    Toast.makeText(context, "ログインできました。NASを開くと直下のフォルダがそのまま開きます", Toast.LENGTH_LONG).show()
                                    onSave(c.copy(share = "", label = c.label.ifBlank { c.host }))
                                } else {
                                    statusOk = true
                                    statusMsg = "ログインできました。ただ共有フォルダが自動では見つからなかったので、そのまま「保存」して、NASを開くときに共有名を入れてね。"
                                }
                            }.onFailure { e ->
                                statusOk = false
                                statusMsg = SmbRepo.describeError(e)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = busy.isEmpty(),
                ) { Text(if (busy == "login") "ログイン中…" else "ログインして保存") }
                if (statusMsg != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        statusMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (foundShares.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("見つかった共有フォルダ", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                }
                items(foundShares, key = { it }) { name ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { share = name },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (share == name) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(name, fontWeight = FontWeight.Medium)
                            if (share == name) {
                                Spacer(Modifier.width(8.dp))
                                Text("選択中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(share, { share = it }, label = { Text("共有フォルダ名（例：share、public）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    "共有フォルダ名は空のままでも保存できます（NASを開くときに一覧から選べます）。アカウントはこの端末の中だけに、暗号化して保存します。次回からは入力なしで自動ログインします。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val c = build()
                        if (c.host.isBlank()) {
                            Toast.makeText(context, "ホストは必須です", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onSave(c.copy(label = c.label.ifBlank { c.host }))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NasSharesScreen(
    conn: NasConnection,
    onOpenShare: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shares by remember(conn.id) { mutableStateOf<List<String>?>(null) }
    var error by remember(conn.id) { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var manualOpen by remember { mutableStateOf(false) }

    fun reload() {
        shares = null; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    SmbRepo.loginTest(conn)
                    SmbRepo.probeShares(conn)
                }
            }
            r.onSuccess { shares = it }
                .onFailure { error = SmbRepo.describeError(it) }
        }
    }
    LaunchedEffect(conn.id) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar(conn.label.ifBlank { conn.host }, onBack)
        Text(
            "smb://${conn.host} ・ 直下のフォルダ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            error != null -> Column(modifier = Modifier.padding(16.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { reload() }) { Text("再接続") }
            }
            shares == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("NASにログイン中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (shares!!.isEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "よくある名前の共有フォルダは見つかりませんでした。下の「見つからない場合はこちら」から共有フォルダ名を入力して開いてね。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shares!!, key = { it }) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenShare(name) }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { manualOpen = !manualOpen }) {
                        Text("共有フォルダが見つからない場合はこちら（名前を入力）")
                    }
                    if (manualOpen) {
                        OutlinedTextField(manual, { manual = it }, label = { Text("共有フォルダ名を直接入力") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val name = manual.trim()
                                if (name.isBlank()) {
                                    Toast.makeText(context, "共有フォルダ名を入力してね", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOpenShare(name)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("この共有フォルダを開く") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun NasBrowseScreen(
    conn: NasConnection,
    path: String,
    favorites: Set<String>,
    onToggleFavorite: (NasEntry) -> Unit,
    onOpenDir: (NasEntry) -> Unit,
    onPlay: (String, String) -> Unit,
    onViewImage: (NasEntry) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(conn.id, path) { mutableStateOf<List<NasEntry>?>(null) }
    var error by remember(conn.id, path) { mutableStateOf<String?>(null) }
    var busyMsg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        entries = null; error = null
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { SmbRepo.list(conn, path) }
            } catch (e: Exception) {
                error = "保存アカウントで自動ログインを試しましたが、接続できませんでした。\n" + SmbRepo.describeError(e) + "\n\nパスワードが変わっている場合は「接続先」から編集してね。"
            }
        }
    }
    LaunchedEffect(conn.id, path) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar(
            if (path.isEmpty()) (conn.label.ifBlank { conn.host }) else path.substringAfterLast('\\'),
            onBack,
        )
        Text(
            "smb://${conn.host}/${conn.share}" + if (path.isEmpty()) "" else "/" + path.replace('\\', '/'),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (busyMsg != null) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(busyMsg!!, style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            error != null -> Column(modifier = Modifier.padding(16.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { reload() }) { Text("再接続") }
            }
            entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("NASに接続中…（保存アカウントで自動ログイン）", style = MaterialTheme.typography.bodySmall)
                }
            }
            entries!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("フォルダは空です", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries!!, key = { it.path }) { e ->
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            when {
                                e.isDir -> onOpenDir(e)
                                e.isVideo || e.isAudio -> {
                                    busyMsg = "再生を準備中…"
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            runCatching { SmbRepo.streamUrl(conn, e) }
                                        }
                                        busyMsg = null
                                        r.onSuccess { url -> onPlay(url, e.name) }
                                            .onFailure { Toast.makeText(context, "再生できません: ${it.message}", Toast.LENGTH_LONG).show() }
                                    }
                                }
                                e.isImage -> onViewImage(e)
                                else -> Toast.makeText(context, "この形式はメニューからダウンロードして開いてね", Toast.LENGTH_SHORT).show()
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                e.isDir -> AppIcons.Folder
                                e.isVideo -> AppIcons.Movie
                                e.isAudio -> AppIcons.MusicNote
                                e.isImage -> AppIcons.Image
                                else -> AppIcons.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
                            if (!e.isDir) Text(fmtSize(e.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!e.isDir) {
                            val favKey = "N|${conn.id}|${e.path}"
                            IconButton(onClick = { onToggleFavorite(e) }) {
                                Icon(
                                    if (favKey in favorites) Icons.Filled.Star else AppIcons.StarBorder,
                                    contentDescription = "お気に入り",
                                    tint = if (favKey in favorites) Color(0xFFF2A900) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!e.isDir) {
                                    DropdownMenuItem(
                                        text = { Text("スマホにダウンロード") },
                                        onClick = {
                                            menu = false
                                            busyMsg = "ダウンロード中: ${e.name}"
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        val values = android.content.ContentValues().apply {
                                                            put(MediaStore.Downloads.DISPLAY_NAME, e.name)
                                                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FilesManager")
                                                        }
                                                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                                            ?: error("保存先を作成できません")
                                                        val notifId = (e.path.hashCode() and 0x7fffffff) % 90000 + 1000
                                                        Notifier.progress(context, notifId, "ダウンロード: ${e.name}", null, "NASから受信中")
                                                        var lastPct = -1
                                                        try {
                                                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                                                SmbRepo.copyToProgress(conn, e.path, out, e.size) { done ->
                                                                    if (e.size > 0) {
                                                                        val pct = (done * 100 / e.size).toInt()
                                                                        if (pct != lastPct) {
                                                                            lastPct = pct
                                                                            Notifier.progress(context, notifId, "ダウンロード: ${e.name}", pct, "${fmtSize(done)} / ${fmtSize(e.size)}")
                                                                        }
                                                                    }
                                                                }
                                                            } ?: error("保存先を開けません")
                                                            Notifier.done(context, notifId, "ダウンロード完了", e.name)
                                                        } catch (ex: Exception) {
                                                            Notifier.failed(context, notifId, "ダウンロード失敗: ${e.name}")
                                                            throw ex
                                                        }
                                                    }.isSuccess
                                                }
                                                busyMsg = null
                                                Toast.makeText(context, if (ok) "Download/FilesManager に保存しました" else "ダウンロードに失敗しました", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("削除") },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                runCatching { SmbRepo.delete(conn, e) }.isSuccess
                                            }
                                            Toast.makeText(context, if (ok) "削除しました" else "削除に失敗しました", Toast.LENGTH_SHORT).show()
                                            if (ok) reload()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafBrowseScreen(
    treeUriStr: String,
    onPlay: (String, String) -> Unit,
    onViewImage: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<DocumentFile?>(null) }
    var stack by remember { mutableStateOf(listOf<DocumentFile>()) }
    var files by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun load(dir: DocumentFile?) {
        loading = true
        current = dir
        files = try {
            dir?.listFiles()?.sortedWith(
                compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() }
            ) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        loading = false
    }
    LaunchedEffect(treeUriStr) {
        val root = try { DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) } catch (e: Exception) { null }
        stack = if (root != null) listOf(root) else emptyList()
        load(root)
    }
    BackHandler(enabled = stack.size > 1) {
        val newStack = stack.dropLast(1)
        stack = newStack
        load(newStack.lastOrNull())
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar(current?.name ?: "フォルダ", onBack)
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("フォルダは空です", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.uri.toString() }) { f ->
                    val mime = f.type ?: ""
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            when {
                                f.isDirectory -> { stack = stack + f; load(f) }
                                mime.startsWith("video/") || mime.startsWith("audio/") -> onPlay(f.uri.toString(), f.name ?: "")
                                mime.startsWith("image/") -> onViewImage(f.uri.toString(), f.name ?: "")
                                else -> {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(f.uri, mime.ifBlank { "*/*" })
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (f.isDirectory) AppIcons.Folder else AppIcons.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name ?: "(名称なし)", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            if (f.isFile) Text(fmtSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(
    favorites: Set<String>,
    connections: List<NasConnection>,
    onPlay: (LocalItem) -> Unit,
    onViewImage: (LocalItem) -> Unit,
    onOpenNas: (NasConnection, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var localItems by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    LaunchedEffect(favorites) {
        localItems = withContext(Dispatchers.IO) { runCatching {
            val ids = favorites.filter { it.startsWith("L|") }
                .mapNotNull { it.removePrefix("L|").substringAfterLast('/').toLongOrNull() }.toSet()
            if (ids.isEmpty()) emptyList()
            else LocalRepo.query(context.contentResolver, Category.RECENT, 2000).filter { it.id in ids }
        }.getOrElse { emptyList() } }
    }
    val nasFavs = favorites.filter { it.startsWith("N|") }
    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar("お気に入り", onBack)
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("お気に入りはまだありません（☆で登録）", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(localItems, key = { it.uri.toString() }) { item ->
                    LocalItemRow(
                        item = item,
                        isFav = true,
                        onToggleFavorite = {},
                        onClick = {
                            when {
                                item.isVideo || item.isAudio -> onPlay(item)
                                item.isImage -> onViewImage(item)
                            }
                        },
                    )
                }
                items(nasFavs) { key ->
                    val parts = key.removePrefix("N|").split("|", limit = 2)
                    val conn = connections.firstOrNull { it.id == parts.getOrNull(0) }
                    val path = parts.getOrNull(1) ?: ""
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { if (conn != null) onOpenNas(conn, path) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(path.substringAfterLast('\\'), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (conn != null) "${conn.label.ifBlank { conn.host }} のファイル" else "NAS（接続先が削除済み）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    store: NasStore,
    connectionsCount: Int,
    onOpenNas: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (curCode, curName) = remember { Updater.currentVersion(context) }
    var autoCheck by remember { mutableStateOf(Updater.autoCheck(store)) }
    var urlText by remember { mutableStateOf(Updater.versionUrl(store)) }
    var checkMsg by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadId by remember { mutableStateOf(Updater.savedDownloadId(store)) }
    var dlProgress by remember { mutableStateOf<Updater.Progress?>(null) }

    // ダウンロード中は進捗をポーリング（ステータスバー通知と併せてアプリ内でも表示）
    LaunchedEffect(downloadId) {
        if (downloadId > 0) {
            while (true) {
                val p = withContext(Dispatchers.IO) { Updater.queryProgress(context, downloadId) }
                dlProgress = p
                if (p == null || p.status == android.app.DownloadManager.STATUS_SUCCESSFUL || p.status == android.app.DownloadManager.STATUS_FAILED) break
                kotlinx.coroutines.delay(600)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleBar("設定", onBack)
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("アプリ情報", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("FilesManager v$curName（versionCode $curCode）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("明るい表示（ライトテーマ）固定。NASはSMB接続、動画はアプリ内プレーヤーで再生します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
            }
            item {
                Text("アップデート（GitHub）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("起動時に自動で確認", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = autoCheck,
                        onCheckedChange = { autoCheck = it; Updater.setAutoCheck(store, it) },
                    )
                }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("version.json のURL（GitHub）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            Updater.setVersionUrl(store, urlText.trim())
                            checking = true; checkMsg = null; update = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { Updater.check(context, store) }
                                }
                                checking = false
                                r.onSuccess { info ->
                                    if (info != null) {
                                        update = info
                                        checkMsg = "アップデートがあります：v${info.versionName}"
                                    } else {
                                        checkMsg = "最新版です（v$curName）"
                                    }
                                }.onFailure {
                                    checkMsg = "確認できませんでした：${it.message}（GitHubリポジトリ未作成の場合は、作成後に確認できます）"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (checking) "確認中…" else "更新を確認") }
                    TextButton(
                        onClick = { Updater.setVersionUrl(store, urlText.trim()); Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.weight(1f),
                    ) { Text("URLを保存") }
                }
                if (checkMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(checkMsg!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                val u = update
                if (u != null) {
                    Spacer(Modifier.height(10.dp))
                    if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (!Updater.canInstall(context)) {
                                Toast.makeText(context, "初回のみ「このアプリからのインストール」を許可してね", Toast.LENGTH_LONG).show()
                                Updater.openInstallPermissionSettings(context)
                                return@Button
                            }
                            downloadId = Updater.startDownload(context, store, u)
                            dlProgress = null
                            Toast.makeText(context, "ダウンロードを開始しました（ステータスバーでも進捗が見られます）", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("v${u.versionName} をダウンロードして更新") }
                }
                val p = dlProgress
                if (downloadId > 0 && p != null) {
                    Spacer(Modifier.height(12.dp))
                    when (p.status) {
                        android.app.DownloadManager.STATUS_RUNNING, android.app.DownloadManager.STATUS_PENDING -> {
                            val pct = if (p.total > 0) (p.downloaded * 100 / p.total).toInt() else 0
                            Text("ダウンロード中… $pct%（${fmtSize(p.downloaded)} / ${if (p.total > 0) fmtSize(p.total) else "?"}）")
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("ステータスバーの通知でも進捗を確認できます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                            Text("ダウンロード完了")
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    if (!Updater.install(context, downloadId)) {
                                        Toast.makeText(context, "インストーラーを開けませんでした", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("インストールする") }
                        }
                        android.app.DownloadManager.STATUS_FAILED -> {
                            Text("ダウンロードに失敗しました", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                Text("NAS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("登録済みNAS：${connectionsCount}件（アカウントは暗号化して保存・自動ログイン）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOpenNas, modifier = Modifier.weight(1f)) { Text("接続先を開く") }
                    TextButton(
                        onClick = {
                            scope.launch { withContext(Dispatchers.IO) { runCatching { SmbRepo.disconnect() } } }
                            Toast.makeText(context, "NAS接続を切断しました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("接続を切断") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
