package app.myzel394.alibi.helpers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.db.RecordingInformation
import app.myzel394.alibi.ui.MEDIA_RECORDINGS_PREFIX
import app.myzel394.alibi.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.myzel394.alibi.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.myzel394.alibi.ui.SUPPORTS_SCOPED_STORAGE
import app.myzel394.alibi.ui.utils.PermissionHelper
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KFunction4


abstract class BatchesFolder(
    open val context: Context,
    open val type: BatchType,
    open val customFolder: DocumentFile? = null,
    open val subfolderName: String = ".recordings",
) {
    abstract val concatenationFunction: KFunction4<Iterable<String>, String, String, (Int) -> Unit, CompletableDeferred<Unit>>
    abstract val ffmpegParameters: Array<String>
    abstract val scopedMediaContentUri: Uri
    abstract val legacyMediaFolder: File

    val mediaPrefix
        get() = MEDIA_RECORDINGS_PREFIX + subfolderName.substring(1) + "-"

    private class BatchReference(val counter: Long, val key: String, val delete: () -> Boolean) {
        override fun equals(other: Any?) = other is BatchReference && key == other.key
        override fun hashCode() = key.hashCode()
    }
    private val retentionIndex = BatchRetentionIndex<BatchReference>()
    private val retentionIndexLock = Any()

    private fun numericCounter(name: String?): Long? {
        val stem = name?.substringBeforeLast(".") ?: return null
        return if (type == BatchType.MEDIA) {
            if (stem.startsWith(mediaPrefix)) stem.removePrefix(mediaPrefix).toLongOrNull() else null
        } else stem.toLongOrNull()
    }

    private fun referenceForFile(counter: Long, file: File) = BatchReference(counter, file.absolutePath) {
        !file.exists() || file.delete()
    }

    private fun referenceForDocument(counter: Long, file: DocumentFile) = BatchReference(counter, file.uri.toString()) {
        runCatching { file.delete() }.getOrDefault(false) || documentUriExists(file.uri) == false
    }

    private fun documentUriExists(uri: Uri): Boolean? = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() }
    } catch (_: Exception) { null }

    protected fun registerBatch(name: String, file: File? = null, document: DocumentFile? = null) {
        val counter = numericCounter(name) ?: return
        val ref = when {
            file != null -> referenceForFile(counter, file)
            document != null -> referenceForDocument(counter, document)
            type == BatchType.MEDIA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                referenceForMediaName(counter, name)
            else -> return
        }
        synchronized(retentionIndexLock) { retentionIndex.register(counter, ref) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun referenceForMediaName(counter: Long, name: String) = BatchReference(counter, name) {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val deleted = context.contentResolver.delete(scopedMediaContentUri, selection, arrayOf(name)) > 0
        deleted || mediaNameExists(name) == false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaNameExists(name: String): Boolean? = try {
        context.contentResolver.query(
            scopedMediaContentUri,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.moveToFirst() }
    } catch (_: Exception) { null }

    private fun ensureRetentionIndex() = synchronized(retentionIndexLock) {
        if (retentionIndex.isInitialized) return@synchronized
        val references = mutableListOf<Pair<Long, BatchReference>>()
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().listFiles()?.forEach { file ->
                numericCounter(file.name)?.let { references += it to referenceForFile(it, file) }
            }
            BatchType.CUSTOM -> getCustomDefinedFolder().listFiles().forEach { file ->
                numericCounter(file.name)?.let { references += it to referenceForDocument(it, file) }
            }
            BatchType.MEDIA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryMediaContent { rawName, counter, _, _ ->
                    val value = counter.toLong()
                    references += value to referenceForMediaName(value, rawName)
                    Unit
                }
            } else {
                legacyMediaFolder.listFiles()?.forEach { file ->
                    numericCounter(file.name)?.let { references += it to referenceForFile(it, file) }
                }
            }
        }
        retentionIndex.replaceAll(references)
    }

    fun initFolders() {
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().mkdirs()

            BatchType.CUSTOM -> {
                if (customFolder!!.findFile(subfolderName) == null) {
                    customFolder!!.createDirectory(subfolderName)
                }
            }

            BatchType.MEDIA -> {
                // Scoped storage works fine on new Android versions,
                // we need to manually manage the folder on older versions
                if (!SUPPORTS_SCOPED_STORAGE) {
                    legacyMediaFolder.mkdirs()
                }
            }
        }
    }

    fun getInternalFolder(): File {
        return File(context.filesDir, subfolderName)
    }

    fun getCustomDefinedFolder(): DocumentFile {
        return customFolder!!.findFile(subfolderName)!!
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    protected fun queryMediaContent(
        callback: (rawName: String, counter: Int, uri: Uri, cursor: Cursor) -> Any?,
    ) {
        context.contentResolver.query(
            scopedMediaContentUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
            null,
            null,
        )!!.use { cursor ->
            while (cursor.moveToNext()) {
                val rawName = cursor.getColumnIndex(Media.DISPLAY_NAME).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (rawName.isNullOrBlank() || !rawName.startsWith(mediaPrefix)) {
                    continue
                }

                val counter =
                    rawName.substringAfter(mediaPrefix).substringBeforeLast(".").toIntOrNull()
                        ?: continue

                val id = cursor.getColumnIndex(Media._ID).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (id.isNullOrBlank()) {
                    continue
                }

                val uri = Uri.withAppendedPath(scopedMediaContentUri, id)

                val result = callback(rawName, counter, uri, cursor)

                if (result == false) {
                    return
                }
            }
        }
    }

    fun getBatchesForFFmpeg(): List<String> {
        return when (type) {
            BatchType.INTERNAL ->
                ((getInternalFolder()
                    .listFiles()
                    ?.filter {
                        it.nameWithoutExtension.toIntOrNull() != null
                    }
                    ?.toList()
                    ?: emptyList()) as List<File>)
                    .sortedBy {
                        it.nameWithoutExtension.toInt()
                    }
                    .map { it.absolutePath }

            BatchType.CUSTOM -> getCustomDefinedFolder()
                .listFiles()
                .filter {
                    it.name?.substringBeforeLast(".")?.toIntOrNull() != null
                }
                .sortedBy {
                    it.name!!.substringBeforeLast(".").toInt()
                }
                .map {
                    FFmpegKitConfig.getSafParameterForRead(
                        context,
                        it.uri,
                    )!!
                }

            BatchType.MEDIA -> {
                val fileUris = mutableListOf<Pair<String, Uri>>()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, uri, _ ->
                        fileUris.add(Pair(rawName, uri))
                    }
                } else {
                    legacyMediaFolder.listFiles()?.forEach {
                        fileUris.add(Pair(it.name, it.toUri()))
                    }
                }

                fileUris
                    .sortedBy {
                        val name = it.first

                        return@sortedBy name
                            .substring(mediaPrefix.length)
                            .substringBeforeLast(".")
                            .toInt()
                    }
                    .map { pair ->
                        val uri = pair.second

                        FFmpegKitConfig.getSafParameterForRead(
                            context,
                            uri,
                        )!!
                    }
            }
        }
    }

    fun getName(date: LocalDateTime, extension: String): String {
        val name = date
            .format(DateTimeFormatter.ISO_DATE_TIME)
            .toString()
            .replace(":", "-")
            .replace(".", "_")

        return "$name.$extension"
    }

    fun asInternalGetOutputFile(fileName: String): File {
        return File(getInternalFolder(), fileName)
    }

    fun asMediaGetLegacyFile(name: String): File = File(
        legacyMediaFolder,
        name
    ).apply {
        createNewFile()
        registerBatch(name, file = this)
    }

    fun checkIfOutputAlreadyExists(
        fileName: String,
    ): Boolean {
        return when (type) {
            BatchType.INTERNAL -> File(getInternalFolder(), fileName).exists()

            BatchType.CUSTOM ->
                getCustomDefinedFolder().findFile(fileName)?.exists() ?: false

            BatchType.MEDIA -> {
                var exists = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, _, _ ->
                        if (rawName == fileName) {
                            exists = true
                            return@queryMediaContent true
                        } else {
                        }
                    }

                    return exists
                } else {
                    return File(
                        legacyMediaFolder,
                        fileName,
                    ).exists()
                }
            }
        }
    }

    abstract fun getOutputFileForFFmpeg(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): String

    abstract fun cleanup()

    suspend fun concatenate(
        recording: RecordingInformation,
        filenameFormat: AppSettings.FilenameFormat,
        disableCache: Boolean? = null,
        onNextParameterTry: (String) -> Unit = {},
        onProgress: (Float?) -> Unit = {},
        fileName: String,
    ): String {
        val disableCache = disableCache ?: (type != BatchType.INTERNAL)
        val date = recording.getStartDateForFilename(filenameFormat)

        if (!disableCache && checkIfOutputAlreadyExists(fileName)
        ) {
            return getOutputFileForFFmpeg(
                date = recording.recordingStart,
                extension = recording.fileExtension,
                fileName = fileName,
            )
        }

        for (parameter in ffmpegParameters) {
            Log.i("Concatenation", "Trying parameter $parameter")
            onNextParameterTry(parameter)
            onProgress(null)

            try {
                val fullTime = recording.getFullDuration().toFloat();
                val filePaths = getBatchesForFFmpeg()

                val outputFile = getOutputFileForFFmpeg(
                    date = date,
                    extension = recording.fileExtension,
                    fileName = fileName,
                )

                concatenationFunction(
                    filePaths,
                    outputFile,
                    parameter
                ) { time ->
                    // The progressbar for the conversion is calculated based on the
                    // current time of the conversion and the total time of the batches.
                    onProgress(time / fullTime)
                }.await()
                return outputFile
            } catch (e: MediaConverter.FFmpegException) {
                continue
            }
        }

        throw MediaConverter.FFmpegException("Failed to concatenate")
    }

    fun exportFolderForSettings(): String {
        return when (type) {
            BatchType.INTERNAL -> RECORDER_INTERNAL_SELECTED_VALUE
            BatchType.MEDIA -> RECORDER_MEDIA_SELECTED_VALUE
            BatchType.CUSTOM -> customFolder!!.uri.toString()
        }
    }

    fun deleteRecordings() {
        synchronized(retentionIndexLock) { retentionIndex.invalidate() }
        // Currently deletes all recordings.
        // This is fine, because we are saving the recordings
        // in a dedicated subfolder
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().deleteRecursively()

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.delete()
                ?: customFolder?.findFile(subfolderName)?.listFiles()?.forEach {
                    it.delete()
                }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // TODO: Also delete pending recordings
                    // --> Doesn't seem to be possible :/
                    context.contentResolver.delete(
                        scopedMediaContentUri,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                        null,
                    )

                } else {
                    legacyMediaFolder.deleteRecursively()
                }
            }
        }
        synchronized(retentionIndexLock) { retentionIndex.invalidate() }
    }

    fun hasRecordingsAvailable(): Boolean {
        return when (type) {
            BatchType.INTERNAL -> getInternalFolder().listFiles()?.isNotEmpty() ?: false

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.listFiles()?.isNotEmpty()
                ?: false

            BatchType.MEDIA -> {
                var hasRecordings = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                        null,
                        null,
                    )!!.use { cursor ->
                        if (cursor.moveToFirst()) {
                            hasRecordings = true
                        }
                    }

                    return hasRecordings
                } else {
                    return legacyMediaFolder.listFiles()?.isNotEmpty() ?: false
                }
            }
        }
    }

    fun deleteRecordings(range: LongRange) {
        ensureRetentionIndex()
        synchronized(retentionIndexLock) {
            retentionIndex.prune(range) { it.delete() }
        }
    }

    fun checkIfFolderIsAccessible(): Boolean {
        try {
            return when (type) {
                BatchType.INTERNAL -> true
                BatchType.CUSTOM -> getCustomDefinedFolder().canWrite() && getCustomDefinedFolder().canRead()
                BatchType.MEDIA -> {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        return true
                    }

                    return PermissionHelper.hasGranted(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) &&
                            PermissionHelper.hasGranted(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                }
            }
        } catch (error: NullPointerException) {
            error.printStackTrace()
            return false
        }
    }

    fun asInternalGetFile(counter: Long, fileExtension: String): File {
        val file = File(getInternalFolder(), "$counter.$fileExtension")
        registerBatch(file.name, file = file)
        return file
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun getOrCreateMediaFile(
        name: String,
        mimeType: String,
        relativePath: String,
    ): Uri {
        // Check if already exists
        var uri: Uri? = null

        context.contentResolver.query(
            scopedMediaContentUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = '$name'",
            null,
            null,
        )!!.use { cursor ->
            if (cursor.moveToFirst()) {
                // No need to check for the name since the query already did that
                val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)

                if (id == -1) {
                    return@use
                }

                uri = ContentUris.withAppendedId(
                    scopedMediaContentUri,
                    cursor.getLong(id)
                )
            }
        }

        if (uri == null) {
            try {
                // Create empty output file to be able to write to it
                uri = context.contentResolver.insert(
                    scopedMediaContentUri,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            name
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            mimeType
                        )

                        put(
                            Media.RELATIVE_PATH,
                            relativePath,
                        )
                    }
                )!!
            } catch (e: Exception) {
                Log.e("Media", "Failed to create file", e)
            }
        }

        registerBatch(name)
        return uri!!
    }

    fun getAvailableBytes(): Long? {
        if (type == BatchType.CUSTOM) {
            var fileDescriptor: ParcelFileDescriptor? = null

            try {
                fileDescriptor =
                    context.contentResolver.openFileDescriptor(customFolder!!.uri, "r")!!
                val stats = Os.fstatvfs(fileDescriptor.fileDescriptor)

                val available = stats.f_bavail * stats.f_bsize

                runCatching {
                    fileDescriptor.close()
                }

                return available
            } catch (e: Exception) {
                runCatching {
                    fileDescriptor?.close();
                }

                return null
            }
        }

        val storageManager = context.getSystemService(StorageManager::class.java) ?: return null
        val file = when (type) {
            BatchType.INTERNAL -> context.filesDir
            BatchType.MEDIA ->
                if (SUPPORTS_SCOPED_STORAGE)
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_SCOPED_STORAGE_RELATIVE_PATH),
                        Media.EXTERNAL_CONTENT_URI.toString(),
                    )
                else
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_LEGACY_STORAGE_FOLDER),
                        VideoBatchesFolder.MEDIA_RECORDINGS_SUBFOLDER,
                    )

            BatchType.CUSTOM -> throw IllegalArgumentException("This code should not be reachable")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(file))
        } else {
            file.usableSpace;
        }
    }

    enum class BatchType {
        INTERNAL,
        CUSTOM,
        MEDIA,
    }

    companion object {
        fun requiredBytesForOneMinuteOfRecording(appSettings: AppSettings): Long {
            // 350 MiB sounds like a good default
            return 350 * 1024 * 1024
        }

        fun canAccessFolder(context: Context, uri: Uri): Boolean {
            // This always returns false for some reason, let's just assume it's true
            return true
            /*
            return try {
                // Create temp file
                val docFile = DocumentFile.fromSingleUri(context, uri)!!

                return docFile.canWrite().also {
                    println("Can write? ${it}")
                } && docFile.canRead().also {
                    println("Can read? ${it}")
                }
            } catch (error: RuntimeException) {
                error.printStackTrace()
                false
            }
             */
        }
    }
}

