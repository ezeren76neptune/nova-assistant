package app.nova.assistant.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.nova.assistant.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class CatalogModel(
    val id: String,
    val name: String,
    val file: String,
    val url: String,
    val sizeMb: Int,
    val engine: String, // litertlm | mediapipe
    val note: String,
    val gated: Boolean = false,
)

/** Curated on-device models that are known to exist on Hugging Face (litert-community). */
object ModelCatalog {
    private fun hf(repo: String, file: String) = "https://huggingface.co/$repo/resolve/main/$file"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            "gemma4-e2b", "Gemma 4 E2B (GPU) — recommended", "gemma-4-E2B-it-gpu.litertlm",
            hf("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it-gpu.litertlm"), 2341, "litertlm",
            "Best balance of smarts and speed for tool use. Wants a phone with 8 GB+ RAM; fast on recent GPUs.",
        ),
        CatalogModel(
            "gemma4-e4b", "Gemma 4 E4B (GPU)", "gemma-4-E4B-it-gpu.litertlm",
            hf("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it-gpu.litertlm"), 3311, "litertlm",
            "Smartest option here. Flagship phones with 12 GB+ RAM; noticeably slower to answer.",
        ),
        CatalogModel(
            "qwen35-2b", "Qwen 3.5 2B (int8)", "Qwen3.5-2B_int8.litertlm",
            hf("litert-community/Qwen3.5-2B", "Qwen3.5-2B_int8.litertlm"), 2120, "litertlm",
            "Good at following the JSON tool format. Needs ~8 GB RAM.",
        ),
        CatalogModel(
            "qwen3-4b-2507", "Qwen 3 4B Instruct 2507 (int4)", "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            hf("litert-community/Qwen3-4B-Instruct-2507", "qwen3_4b_instruct_2507_mixed_int4.litertlm"), 2659, "litertlm",
            "Very capable instruction model; 8–12 GB RAM, slower than the 2B models.",
        ),
        CatalogModel(
            "qwen3-1.7b", "Qwen 3 1.7B (int4)", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            hf("litert-community/Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"), 977, "litertlm",
            "Solid small all-rounder for 6 GB phones. Keep 'thinking' off for speed.",
        ),
        CatalogModel(
            "qwen35-0.8b", "Qwen 3.5 0.8B (GPU)", "Qwen3.5-0.8B_int8_gpu_composite_v0190.litertlm",
            hf("litert-community/Qwen3.5-0.8B", "Qwen3.5-0.8B_int8_gpu_composite_v0190.litertlm"), 888, "litertlm",
            "Fast and light. Fine for simple commands; weaker at multi-step requests.",
        ),
        CatalogModel(
            "lfm25-1.2b", "LFM 2.5 1.2B Instruct (GPU)", "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            hf("litert-community/LFM2.5-1.2B-Instruct", "LFM2.5-1.2B-Instruct_int4_gpu.litertlm"), 736, "litertlm",
            "Very fast small model with decent instruction following.",
        ),
        CatalogModel(
            "qwen3-0.6b", "Qwen 3 0.6B (int4)", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            hf("litert-community/Qwen3-0.6B", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"), 343, "litertlm",
            "Tiny and quick for low-RAM phones; limited reasoning — lean on quick commands.",
        ),
        CatalogModel(
            "gemma3-1b", "Gemma 3 1B (int4)", "gemma3-1b-it-int4.litertlm",
            hf("litert-community/Gemma3-1B-IT", "gemma3-1b-it-int4.litertlm"), 584, "litertlm",
            "Gated: accept the Gemma license on Hugging Face and paste your HF token first.", gated = true,
        ),
        CatalogModel(
            "qwen25-1.5b-task", "Qwen 2.5 1.5B (MediaPipe .task)", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            hf("litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"), 1599, "mediapipe",
            "Runs on the MediaPipe engine — a fallback if LiteRT-LM misbehaves on your phone.",
        ),
    )

    fun modelsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun fileFor(ctx: Context, m: CatalogModel): File = File(modelsDir(ctx), m.file)

    fun engineForPath(path: String): String = if (path.lowercase().endsWith(".task")) "mediapipe" else "litertlm"
}

data class DownloadProgress(val id: Long, val status: Int, val done: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Large model downloads go through Android's DownloadManager (resumes, survives the app being closed). */
object ModelDownloads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_downloads", Context.MODE_PRIVATE)
    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    fun start(ctx: Context, url: String, fileName: String, hfToken: String?): Long {
        val dm = dm(ctx)
        val old = prefs(ctx).getLong(fileName, -1L)
        if (old >= 0) runCatching { dm.remove(old) }
        File(ModelCatalog.modelsDir(ctx), fileName).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Nova model: $fileName")
            .setDescription("Downloading on-device AI model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "models", fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        if (!hfToken.isNullOrBlank() && url.contains("huggingface.co")) {
            req.addRequestHeader("Authorization", "Bearer ${hfToken.trim()}")
        }
        val id = dm.enqueue(req)
        prefs(ctx).edit { putLong(fileName, id) }
        AppLog.d("llm", "download started: $fileName (id=$id)")
        return id
    }

    fun progress(ctx: Context, fileName: String): DownloadProgress? {
        val id = prefs(ctx).getLong(fileName, -1L)
        if (id < 0) return null
        return runCatching {
            dm(ctx).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use null
                DownloadProgress(
                    id,
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            }
        }.getOrNull()
    }

    fun cancel(ctx: Context, fileName: String) {
        val id = prefs(ctx).getLong(fileName, -1L)
        if (id >= 0) runCatching { dm(ctx).remove(id) }
        prefs(ctx).edit { remove(fileName) }
        File(ModelCatalog.modelsDir(ctx), fileName).delete()
        AppLog.d("llm", "download cancelled/removed: $fileName")
    }

    /** Copies a user-picked file (SAF) into the models folder so native engines can open it by path. */
    suspend fun importFromUri(ctx: Context, uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "model.litertlm"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = ModelCatalog.modelsDir(ctx)
        val dest = File(dir, name)
        val tmp = File(dir, "$name.part")
        AppLog.d("llm", "importing $name (${size / 1_000_000} MB)")
        try {
            (cr.openInputStream(uri) ?: error("Can't open the selected file")).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (size > 0) {
                            val pct = (done * 100 / size).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        withContext(Dispatchers.Main) { onProgress(1f) }
        AppLog.d("llm", "imported to ${dest.absolutePath}")
        dest
    }
}
