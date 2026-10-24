package com.enhance.comic

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.enhance.comic.data.BookStore
import com.enhance.comic.model.Book
import com.enhance.comic.ui.LibraryScreen
import com.enhance.comic.ui.ReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnhanceComicApp()
                }
            }
        }
    }

    /** Volume-key paging: consumed here because keys never reach the composable tree. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = VolumeKeyBridge.onStep
        if (handler != null &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    handler(down, false)
                } else if (VolumeKeyBridge.repeatEnabled) {
                    handler(down, true)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun EnhanceComicApp() {
    val context = LocalContext.current
    val store = remember { BookStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf(store.loadBooks()) }
    var openBook by remember { mutableStateOf<Book?>(null) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        message = null
    }

    // Books imported before covers existed get one rendered on first launch.
    LaunchedEffect(Unit) {
        val current = books
        if (current.isEmpty() || current.all { it.coverPath.isNotEmpty() }) return@LaunchedEffect
        val fixed = withContext(Dispatchers.IO) { store.backfillCovers(current) }
        if (fixed.any { it.coverPath.isNotEmpty() }) {
            books = fixed
            store.saveBooks(fixed)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> store.importDocument(uri, null) }
            }
            if (added.isNotEmpty()) {
                val merged = books + added
                books = merged
                store.saveBooks(merged)
            }
            importing = false
            if (added.size < uris.size) {
                message = "有 ${uris.size - added.size} 个文件无法识别（支持 ZIP / CBZ / RAR / CBR / 7Z / TAR / PDF）"
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        importing = true
        scope.launch {
            val book = withContext(Dispatchers.IO) { store.importFolder(uri) }
            if (book != null) {
                val merged = books + book
                books = merged
                store.saveBooks(merged)
            } else {
                message = "这个文件夹里没有可用的图片"
            }
            importing = false
        }
    }

    val reader = openBook
    if (reader == null) {
        LibraryScreen(
            books = books,
            importing = importing,
            onOpen = { openBook = it },
            onImportFiles = { pickFiles.launch(arrayOf("*/*")) },
            onImportFolder = {
                runCatching { pickFolder.launch(null) }
            },
            onDelete = { book ->
                store.deleteBook(book)
                books = store.loadBooks()
            },
        )
    } else {
        ReaderScreen(
            book = reader,
            store = store,
            onBack = { sourcePage, marks, names ->
                val updated = reader.copy(
                    lastPage = sourcePage,
                    bookmarks = marks,
                    bookmarkNames = names,
                )
                store.saveBooks(
                    store.loadBooks().map { if (it.id == updated.id) updated else it },
                )
                books = store.loadBooks()
                openBook = null
            },
        )
    }
}
