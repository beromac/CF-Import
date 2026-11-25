package fr.cfimport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHOTO_EXT = setOf("jpg", "jpeg", "cr2", "cr3", "crw", "dng", "nef", "arw")
private val VIDEO_EXT = setOf("avi", "mov", "mp4", "mpg", "mpeg", "mts", "m2ts", "mkv")

data class Item(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val isVideo: Boolean,
) {
    val key get() = "$name|$size|$modified"
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isJpg get() = ext == "jpg" || ext == "jpeg"
}

fun mimeFor(ext: String) = when (ext) {
    "jpg", "jpeg" -> "image/jpeg"
    "cr2" -> "image/x-canon-cr2"
    "cr3" -> "image/x-canon-cr3"
    "crw" -> "image/x-canon-crw"
    "dng" -> "image/x-adobe-dng"
    "nef" -> "image/x-nikon-nef"
    "arw" -> "image/x-sony-arw"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "mp4" -> "video/mp4"
    "mpg", "mpeg" -> "video/mpeg"
    "mts", "m2ts" -> "video/mp2t"
    "mkv" -> "video/x-matroska"
    else -> "application/octet-stream"
}

/** Parcourt récursivement la carte et liste photos + vidéos. */
fun scan(ctx: Context, tree: Uri): List<Item> {
    val out = mutableListOf<Item>()
    fun walk(docId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(id)
                } else if (!name.startsWith(".")) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val video = ext in VIDEO_EXT
                    if (ext in PHOTO_EXT || video) {
                        out += Item(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, c.getLong(3), c.getLong(4), video,
                        )
                    }
                }
            }
        }
    }
    walk(DocumentsContract.getTreeDocumentId(tree))
    return out
}

/** Copie un fichier vers Pictures/Import ou Movies/Import. */
fun importItem(ctx: Context, item: Item): Boolean {
    val r = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(item.ext))
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (item.isVideo) "Movies/Import" else "Pictures/Import",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = if (item.isVideo)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val dest = r.insert(collection, values) ?: return false
    return try {
        r.openInputStream(item.uri)!!.use { input ->
            r.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        r.update(dest, done, null, null)
        true
    } catch (e: Exception) {
        r.delete(dest, null, null)
        false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("imported", Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getStringSet("keys", emptySet())!!.toSet()) }
    var items by remember { mutableStateOf(listOf<Item>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf("Branchez le lecteur, puis choisissez la carte.") }
    var progress by remember { mutableStateOf<Float?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            status = "Analyse de la carte…"
            val found = withContext(Dispatchers.IO) { scan(ctx, tree) }.sortedBy { it.name }
            items = found
            selected = found.filter { it.key !in done }.map { it.key }.toSet()
            status = "${found.size} fichiers trouvés, ${selected.size} nouveaux."
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp),
    ) {
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Choisir la carte (Stockage USB)")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selected = items.map { it.key }.toSet() }) { Text("Tout") }
            OutlinedButton(onClick = { selected = items.filter { it.key !in done }.map { it.key }.toSet() }) { Text("Nouveaux") }
            OutlinedButton(onClick = { selected = emptySet() }) { Text("Aucun") }
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                val isSel = item.key in selected
                val already = item.key in done
                Box(
                    Modifier.aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .alpha(if (already && !isSel) 0.45f else 1f)
                        .clickable { selected = if (isSel) selected - item.key else selected + item.key },
                ) {
                    if (item.isJpg) {
                        AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            (if (item.isVideo) "VIDÉO\n" else "RAW\n") + item.ext.uppercase(),
                            Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Checkbox(isSel, { selected = if (isSel) selected - item.key else selected + item.key },
                        Modifier.align(Alignment.TopEnd))
                    if (already) Text("✓ déjà importé", Modifier.align(Alignment.BottomStart).padding(2.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Button(
            enabled = selected.isNotEmpty() && progress == null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val todo = items.filter { it.key in selected }
                    var ok = 0
                    todo.forEachIndexed { i, f ->
                        progress = i / todo.size.toFloat()
                        status = "Copie ${i + 1}/${todo.size} : ${f.name}"
                        if (withContext(Dispatchers.IO) { importItem(ctx, f) }) {
                            ok++
                            done = done + f.key
                            prefs.edit().putStringSet("keys", done).apply()
                        }
                    }
                    progress = null
                    selected = emptySet()
                    status = "Terminé : $ok/${todo.size} copiés (Pictures/Import et Movies/Import)."
                }
            },
        ) { Text("Importer (${selected.size})") }
    }
}
