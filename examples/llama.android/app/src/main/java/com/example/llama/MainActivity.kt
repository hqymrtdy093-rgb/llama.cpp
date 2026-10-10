package com.example.llama

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var engineInitialized = false
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    private data class LoadedModel(
        val displayName: String,
        val sizeBytes: Long,
        val details: String,
        val storageDescription: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        // Initialize engine, then restore the last selected model if it still exists.
        userActionFab.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                engineInitialized = true
                val engineState = withTimeout(120_000L) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                    }
                }
                if (engineState is InferenceEngine.State.Error) throw engineState.exception

                val prefs = getPreferences(MODE_PRIVATE)
                val lastUri = prefs.getString(KEY_LAST_MODEL_URI, null)
                val lastModel = prefs.getString(KEY_LAST_MODEL, null)
                val restoredDescription = if (!lastUri.isNullOrBlank()) {
                    loadModelFromUri(Uri.parse(lastUri)).let {
                        "Ready: ${it.displayName}\nSize: ${formatBytes(it.sizeBytes)}\n${it.storageDescription}\n\n${it.details}"
                    }
                } else {
                    val modelFile = lastModel?.let { File(ensureModelsDirectory(), it) }
                    if (modelFile != null && modelFile.isFile && modelFile.length() > 0L) {
                        loadModel(modelFile.name, modelFile)
                        "Ready: ${modelFile.name}\nSize: ${formatBytes(modelFile.length())}\nStored in app-private model storage."
                    } else {
                        prefs.edit().remove(KEY_LAST_MODEL).apply()
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    if (restoredDescription != null) {
                        isModelReady = true
                        ggufTv.text = restoredDescription
                        userInputEt.hint = "Type and send a message!"
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                    } else {
                        ggufTv.text = "No model loaded. Tap the folder button to import a GGUF model, or Manage Models to choose an existing one."
                    }
                    userActionFab.isEnabled = true
                }
                withContext(Dispatchers.Main) { userActionFab.isEnabled = true }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine or restore model", e)
                withContext(Dispatchers.Main) {
                    userActionFab.isEnabled = true
                    Toast.makeText(this@MainActivity, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<android.view.View>(R.id.models_button).setOnClickListener {
            showModelManager()
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                // Otherwise, prompt user to select a GGUF metadata on the device
                getContent.launch(arrayOf("*/*"))
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        userActionFab.isEnabled = false
        userInputEt.isEnabled = false
        userInputEt.hint = "Reading model..."
        ggufTv.text = "Reading GGUF metadata...\n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loaded = loadModelFromUri(uri)
                withContext(Dispatchers.Main) {
                    isModelReady = true
                    ggufTv.text = "Ready: ${loaded.displayName}\nSize: ${formatBytes(loaded.sizeBytes)}\n${loaded.storageDescription}\n\n${loaded.details}"
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load selected model", e)
                withContext(Dispatchers.Main) {
                    isModelReady = false
                    userInputEt.isEnabled = false
                    userInputEt.hint = "Select a GGUF model to begin."
                    userActionFab.setImageResource(R.drawable.outline_folder_open_24)
                    userActionFab.isEnabled = true
                    ggufTv.text = "Could not load model.\n${e.message ?: e.javaClass.simpleName}"
                    Toast.makeText(this@MainActivity, "Model load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Uses a persisted SAF grant and /proc/self/fd for regular files. Some document
     * providers expose pipes or virtual files instead; those are copied into private
     * storage as a compatibility fallback.
     */
    private suspend fun loadModelFromUri(uri: Uri): LoadedModel {
        val metadata = contentResolver.openInputStream(uri)?.use { input ->
            GgufMetadataReader.create().readStructuredMetadata(input)
        } ?: throw java.io.IOException("Cannot open the selected file.")

        val name = queryDisplayName(uri) ?: metadata.filename() + FILE_EXTENSION_GGUF
        val persisted = getPreferences(MODE_PRIVATE)
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "Provider did not grant persistable URI access; copy fallback remains available.", e)
        }

        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null && descriptor.statSize > 0L) {
                val directPath = "/proc/self/fd/${descriptor.fd}"
                try {
                    prepareEngineForModelLoad()
                    withContext(Dispatchers.Main) { userInputEt.hint = "Loading original model..." }
                    engine.loadModel(directPath)
                    val size = descriptor.statSize
                    rememberUriModel(uri, name, size)
                    persisted.edit()
                        .putString(KEY_LAST_MODEL_URI, uri.toString())
                        .putString(KEY_LAST_MODEL_LABEL, name)
                        .putLong(KEY_LAST_MODEL_SIZE, size)
                        .remove(KEY_LAST_MODEL)
                        .apply()
                    return LoadedModel(name, size, metadata.toString(), "Using the original document through Android Storage Access Framework; no model copy created.")
                } catch (directError: Exception) {
                    Log.w(TAG, "Direct SAF file loading unavailable; falling back to one private copy.", directError)
                    if (engine.state.value is InferenceEngine.State.Error) {
                        try { engine.cleanUp() } catch (cleanupError: Exception) {
                            Log.w(TAG, "Could not reset engine after direct-load failure.", cleanupError)
                        }
                    }
                }
            }
        } finally {
            try { descriptor?.close() } catch (_: Exception) {}
        }

        val modelName = modelStorageName(uri, metadata)
        val modelFile = contentResolver.openInputStream(uri)?.use { input ->
            ensureModelFile(modelName, input)
        } ?: throw java.io.IOException("Cannot reopen the selected model file.")
        prepareEngineForModelLoad()
        loadModel(modelFile.name, modelFile)
        forgetUriModel(uri)
        return LoadedModel(modelFile.name, modelFile.length(), metadata.toString(), "Stored in app-private model storage (one copy required by this document provider).")
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun rememberUriModel(uri: Uri, name: String, size: Long) {
        val prefs = getPreferences(MODE_PRIVATE)
        val uris = prefs.getStringSet(KEY_MODEL_URIS, emptySet()).orEmpty().toMutableSet()
        uris.add(uri.toString())
        prefs.edit()
            .putStringSet(KEY_MODEL_URIS, uris)
            .putString(KEY_URI_LABEL_PREFIX + uri.toString(), name)
            .putLong(KEY_URI_SIZE_PREFIX + uri.toString(), size)
            .apply()
    }

    private fun forgetUriModel(uri: Uri) {
        val prefs = getPreferences(MODE_PRIVATE)
        val uris = prefs.getStringSet(KEY_MODEL_URIS, emptySet()).orEmpty().toMutableSet()
        uris.remove(uri.toString())
        prefs.edit()
            .putStringSet(KEY_MODEL_URIS, uris)
            .remove(KEY_URI_LABEL_PREFIX + uri.toString())
            .remove(KEY_URI_SIZE_PREFIX + uri.toString())
            .apply()
    }

    /** Uses the picker name plus URI identity to avoid collisions between different source files. */
    private fun modelStorageName(uri: Uri, metadata: GgufMetadata): String {
        val fallbackName = metadata.filename()
        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        val chosenName = displayName ?: fallbackName
        val baseName = chosenName.substringAfterLast('/')
            .substringBeforeLast('.', missingDelimiterValue = chosenName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(72)
            .ifBlank { "model" }
        val sourceId = Integer.toHexString(uri.toString().hashCode())
        return "$baseName-$sourceId.gguf"
    }
    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            val directory = ensureModelsDirectory()
            val file = File(directory, modelName)
            if (file.isFile && file.length() > 0L) {
                Log.i(TAG, "Reusing existing stored model $modelName")
                file
            } else {
                if (file.exists()) file.delete()
                val partial = File(directory, "$modelName.partial")
                if (partial.exists()) partial.delete()
                Log.i(TAG, "Copying selected model to app storage: $modelName")
                withContext(Dispatchers.Main) { userInputEt.hint = "Copying model..." }
                FileOutputStream(partial).use { output -> input.copyTo(output) }
                if (!partial.isFile || partial.length() <= 0L) {
                    partial.delete()
                    throw java.io.IOException("The selected model file is empty.")
                }
                if (!partial.renameTo(file)) {
                    partial.copyTo(file, overwrite = true)
                    partial.delete()
                }
                Log.i(TAG, "Stored model: $modelName (${file.length()} bytes)")
                file
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private fun prepareEngineForModelLoad() {
        val state = engine.state.value
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
            engine.cleanUp()
        }
    }

    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) { userInputEt.hint = "Loading model..." }
            engine.loadModel(modelFile.absolutePath)
            getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_MODEL, modelFile.name).apply()
        }

    /** Shows locally stored GGUF models and model-management actions. */
    private fun showModelManager() {
        val files = ensureModelsDirectory().listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
            .orEmpty()
        val prefs = getPreferences(MODE_PRIVATE)
        val uris = prefs.getStringSet(KEY_MODEL_URIS, emptySet()).orEmpty()
            .sortedBy { prefs.getString(KEY_URI_LABEL_PREFIX + it, it.substringAfterLast('/')) }
        val privateBytes = files.fold(0L) { total, file -> total + file.length() }
        val uriBytes = uris.fold(0L) { total, uri -> total + prefs.getLong(KEY_URI_SIZE_PREFIX + uri, 0L) }
        val entries = arrayOf("Import GGUF model…", "Delete a model…") +
            files.map { "${it.name}  •  ${formatBytes(it.length())}\nApp-private copy" } +
            uris.map { uri ->
                val label = prefs.getString(KEY_URI_LABEL_PREFIX + uri, uri.substringAfterLast('/')) ?: uri
                val size = prefs.getLong(KEY_URI_SIZE_PREFIX + uri, 0L)
                "${label}  •  ${formatBytes(size)}\nOriginal file (no copy)"
            }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("LocalMind models\n${files.size + uris.size} models • ${formatBytes(privateBytes + uriBytes)}")
            .setItems(entries) { _, index ->
                when {
                    index == 0 -> getContent.launch(arrayOf("*/*"))
                    index == 1 -> showDeleteModelDialog(files, uris)
                    index < 2 + files.size -> loadExistingModel(files[index - 2])
                    else -> {
                        val uriIndex = index - 2 - files.size
                        if (uriIndex in uris.indices) handleSelectedModel(Uri.parse(uris[uriIndex]))
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadExistingModel(modelFile: File) {
        if (!modelFile.isFile || modelFile.length() <= 0L) {
            Toast.makeText(this, "Model file is missing or empty.", Toast.LENGTH_LONG).show()
            return
        }
        userActionFab.isEnabled = false
        userInputEt.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                prepareEngineForModelLoad()
                isModelReady = false
                loadModel(modelFile.name, modelFile)
                withContext(Dispatchers.Main) {
                    isModelReady = true
                    ggufTv.text = "Ready: ${modelFile.name}\nSize: ${formatBytes(modelFile.length())}\nStored in app-private model storage."
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    Toast.makeText(this@MainActivity, "Model loaded.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load stored model", e)
                withContext(Dispatchers.Main) {
                    userInputEt.isEnabled = false
                    userActionFab.isEnabled = true
                    Toast.makeText(this@MainActivity, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDeleteModelDialog(files: List<File>, uris: List<String>) {
        if (files.isEmpty() && uris.isEmpty()) {
            Toast.makeText(this, "No stored models to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val labels = files.map { "${it.name}  •  ${formatBytes(it.length())}" } + uris.map { uri ->
            val name = prefs.getString(KEY_URI_LABEL_PREFIX + uri, uri.substringAfterLast('/')) ?: uri
            val size = prefs.getLong(KEY_URI_SIZE_PREFIX + uri, 0L)
            "${name}  •  ${formatBytes(size)} (original file)"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose a model to delete")
            .setItems(labels.toTypedArray()) { _, index ->
                val deletingPrivateFile = index < files.size
                val file = if (deletingPrivateFile) files[index] else null
                val uriText = if (deletingPrivateFile) null else uris[index - files.size]
                val label = file?.name ?: prefs.getString(KEY_URI_LABEL_PREFIX + uriText, uriText) ?: "model"
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Remove model?")
                    .setMessage(if (file != null) {
                        "Delete ${file.name}? This removes only LocalMind's stored copy, not the original file."
                    } else {
                        "Remove ${label} from LocalMind? The original file will not be deleted."
                    })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val currentFile = file
                            val currentUri = uriText
                            val isLastModel = if (currentFile != null) {
                                prefs.getString(KEY_LAST_MODEL, null) == currentFile.name
                            } else {
                                prefs.getString(KEY_LAST_MODEL_URI, null) == currentUri
                            }
                            if (isLastModel && engine.state.value is InferenceEngine.State.ModelReady) {
                                engine.cleanUp()
                                isModelReady = false
                            }
                            val deleted = if (currentFile != null) {
                                currentFile.delete()
                            } else {
                                val uri = Uri.parse(currentUri)
                                forgetUriModel(uri)
                                try {
                                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Unable to release persisted document permission", e)
                                }
                                true
                            }
                            if (isLastModel) {
                                prefs.edit().remove(KEY_LAST_MODEL).remove(KEY_LAST_MODEL_URI).remove(KEY_LAST_MODEL_LABEL).remove(KEY_LAST_MODEL_SIZE).apply()
                            }
                            withContext(Dispatchers.Main) {
                                if (isLastModel) {
                                    userInputEt.isEnabled = false
                                    userActionFab.setImageResource(R.drawable.outline_folder_open_24)
                                    userInputEt.hint = "Select a GGUF model to begin."
                                }
                                ggufTv.text = if (deleted) "Removed: ${label}" else "Could not delete: ${label}"
                                Toast.makeText(this@MainActivity, if (deleted) "Model removed." else "Delete failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        // InferenceEngineImpl is a process-wide singleton. destroy() cancels its
        // coroutine scope permanently, so it must not be called when an Activity
        // is recreated or merely leaves the foreground.
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
        private const val KEY_LAST_MODEL = "last_model_filename"
        private const val KEY_LAST_MODEL_URI = "last_model_uri"
        private const val KEY_LAST_MODEL_LABEL = "last_model_label"
        private const val KEY_LAST_MODEL_SIZE = "last_model_size"
        private const val KEY_MODEL_URIS = "model_uris"
        private const val KEY_URI_LABEL_PREFIX = "model_uri_label:"
        private const val KEY_URI_SIZE_PREFIX = "model_uri_size:"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
