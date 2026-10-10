package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import com.arm.aichat.ConversationTurn
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
    private lateinit var chatsButton: Button
    private lateinit var chatDatabase: ChatDatabase
    private var currentChatId: String = ""

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var engineInitialized = false
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages) { message -> showMessageActions(message) }

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
        chatsButton = findViewById(R.id.chats_button)
        chatDatabase = ChatDatabase(applicationContext)
        initializeChatState()

        chatsButton.setOnClickListener { showChatsDialog() }
        findViewById<Button>(R.id.new_chat_button).setOnClickListener { createAndSwitchChat() }

        // Initialize engine, then restore the last selected model if it still exists.
        userActionFab.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                engineInitialized = true
                val engineState = withTimeout(120_000L) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady ||
                        it is InferenceEngine.State.Error
                    }
                }
                if (engineState is InferenceEngine.State.Error) throw engineState.exception

                val prefs = getPreferences(MODE_PRIVATE)
                val lastUri = prefs.getString(KEY_LAST_MODEL_URI, null)
                val lastModel = prefs.getString(KEY_LAST_MODEL, null)
                val restoredDescription = if (!lastUri.isNullOrBlank()) {
                    if (engineState is InferenceEngine.State.ModelReady) {
                        val label = prefs.getString(KEY_LAST_MODEL_LABEL, null) ?: "Selected GGUF"
                        val size = prefs.getLong(KEY_LAST_MODEL_SIZE, 0L)
                        "Ready: ${label}\nSize: ${formatBytes(size)}\nReused the model already loaded in memory."
                    } else {
                        loadModelFromUri(Uri.parse(lastUri)).let {
                            "Ready: ${it.displayName}\nSize: ${formatBytes(it.sizeBytes)}\n${it.storageDescription}\n\n${it.details}"
                        }
                    }
                } else {
                    val modelFile = lastModel?.let { File(ensureModelsDirectory(), it) }
                    if (modelFile != null && modelFile.isFile && modelFile.length() > 0L) {
                        if (engineState !is InferenceEngine.State.ModelReady) {
                            loadModel(modelFile.name, modelFile)
                        }
                        "Ready: ${modelFile.name}\nSize: ${formatBytes(modelFile.length())}\nStored in app-private model storage."
                    } else {
                        prefs.edit().remove(KEY_LAST_MODEL).apply()
                        null
                    }
                }
                if (restoredDescription != null) {
                    restoreCurrentConversationHistoryToEngine(currentChatId)
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
                restoreCurrentConversationHistoryToEngine(currentChatId)
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
        var hasPersistedReadGrant = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPersistedReadGrant) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                hasPersistedReadGrant = contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            } catch (e: Exception) {
                Log.w(TAG, "Provider did not grant persistable URI access; using a private copy to support restart.", e)
            }
        }

        var descriptor: ParcelFileDescriptor? = null
        try {
            if (hasPersistedReadGrant) descriptor = contentResolver.openFileDescriptor(uri, "r")
            if (hasPersistedReadGrant && descriptor != null && descriptor.statSize > 0L) {
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
        val fallbackName = metadata.filename() ?: "model.gguf"
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
            // A private-copy model must take precedence over any stale SAF URI.
            getPreferences(MODE_PRIVATE).edit()
                .putString(KEY_LAST_MODEL, modelFile.name)
                .remove(KEY_LAST_MODEL_URI)
                .remove(KEY_LAST_MODEL_LABEL)
                .remove(KEY_LAST_MODEL_SIZE)
                .apply()
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
        val entries = arrayOf("View storage usage…", "Import GGUF model…", "Delete a model…") +
            files.map { "${it.name}  •  ${formatBytes(it.length())}\n${it.absolutePath}" } +
            uris.map { uri ->
                val label = prefs.getString(KEY_URI_LABEL_PREFIX + uri, uri.substringAfterLast('/')) ?: uri
                val size = prefs.getLong(KEY_URI_SIZE_PREFIX + uri, 0L)
                "${label}  •  ${formatBytes(size)}\nSAF URI: ${uri}"
            }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("LocalMind models\n${files.size + uris.size} models • ${formatBytes(privateBytes)} in app storage")
            .setItems(entries) { _, index ->
                when {
                    index == 0 -> showStorageUsage(files, uris)
                    index == 1 -> getContent.launch(arrayOf("*/*"))
                    index == 2 -> showDeleteModelDialog(files, uris)
                    index < 3 + files.size -> loadExistingModel(files[index - 3])
                    else -> {
                        val uriIndex = index - 3 - files.size
                        if (uriIndex in uris.indices) handleSelectedModel(Uri.parse(uris[uriIndex]))
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showStorageUsage(files: List<File>, uris: List<String>) {
        val prefs = getPreferences(MODE_PRIVATE)
        val modelCopyBytes = files.fold(0L) { total, file -> total + file.length() }
        val linkedOriginalBytes = uris.fold(0L) { total, uri ->
            total + prefs.getLong(KEY_URI_SIZE_PREFIX + uri, 0L)
        }
        val cacheBytes = directorySize(cacheDir)
        val totalAppDataBytes = directorySize(File(applicationInfo.dataDir))
        val otherDataBytes = (totalAppDataBytes - modelCopyBytes - cacheBytes).coerceAtLeast(0L)
        val details = "Model copies in app storage: ${formatBytes(modelCopyBytes)}\n" +
            "Other app data (estimated): ${formatBytes(otherDataBytes)}\n" +
            "Cache: ${formatBytes(cacheBytes)}\n" +
            "Total app data (estimated): ${formatBytes(totalAppDataBytes)}\n\n" +
            "Linked original GGUF files: ${formatBytes(linkedOriginalBytes)}\n" +
            "Original files remain in their existing folders and are not counted as LocalMind storage."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Storage usage")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun directorySize(entry: File): Long {
        if (!entry.exists()) return 0L
        if (entry.isFile) return entry.length()
        return entry.listFiles()?.fold(0L) { total, child -> total + directorySize(child) } ?: 0L
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
                restoreCurrentConversationHistoryToEngine(currentChatId)
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
                val label = file?.name ?: uriText?.let { uri ->
                    prefs.getString(KEY_URI_LABEL_PREFIX + uri, uri)
                } ?: "model"
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
                                val uri = Uri.parse(requireNotNull(currentUri))
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
    private fun initializeChatState() {
        val chats = chatDatabase.listChats().ifEmpty { listOf(chatDatabase.createChat()) }
        val prefs = getPreferences(MODE_PRIVATE)
        val savedId = prefs.getString(KEY_LAST_CHAT, null)
        currentChatId = savedId?.takeIf { chatDatabase.getChat(it) != null } ?: chats.first().id
        prefs.edit().putString(KEY_LAST_CHAT, currentChatId).apply()
        reloadMessagesFromDatabase(currentChatId)
        updateChatButtonTitle()
    }

    private fun reloadMessagesFromDatabase(chatId: String) {
        messages.clear()
        messages.addAll(chatDatabase.getMessages(chatId).map { record ->
            Message(record.id, record.content, record.role == ChatMessageRecord.ROLE_USER)
        })
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) messagesRv.scrollToPosition(0)
    }

    private fun updateChatButtonTitle() {
        if (!::chatsButton.isInitialized || !::chatDatabase.isInitialized) return
        val title = chatDatabase.getChat(currentChatId)?.title ?: "New chat"
        chatsButton.text = "Chats: ${title.take(22)}"
    }

    private fun createAndSwitchChat() {
        val chat = chatDatabase.createChat()
        switchToChat(chat.id)
    }

    private fun showChatsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val search = EditText(this).apply {
            hint = "Search chat titles"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val listView = ListView(this)
        container.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
        ))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newButton = Button(this).apply { text = "New" }
        val renameButton = Button(this).apply { text = "Rename" }
        val deleteButton = Button(this).apply { text = "Delete" }
        actions.addView(newButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(renameButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(deleteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(actions)

        val displayed = mutableListOf<ChatRecord>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chats and history")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        fun refresh(query: String) {
            displayed.clear()
            displayed.addAll(chatDatabase.listChats(query))
            adapter.clear()
            adapter.addAll(displayed.map { chat ->
                val selected = if (chat.id == currentChatId) "  •  Current" else ""
                "${chat.title}${selected}\n${chatDatabase.getMessages(chat.id).size} messages"
            })
            adapter.notifyDataSetChanged()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(text?.toString().orEmpty())
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        listView.setOnItemClickListener { _, _, index, _ ->
            displayed.getOrNull(index)?.let { chat ->
                dialog.dismiss()
                switchToChat(chat.id)
            }
        }
        newButton.setOnClickListener {
            dialog.dismiss()
            createAndSwitchChat()
        }
        renameButton.setOnClickListener {
            dialog.dismiss()
            showRenameChatDialog(currentChatId)
        }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            showDeleteChatDialog(currentChatId)
        }
        refresh("")
        dialog.show()
    }

    private fun showRenameChatDialog(chatId: String) {
        val chat = chatDatabase.getChat(chatId) ?: return
        val nameInput = EditText(this).apply {
            setText(chat.title)
            setSelection(text.length)
            hint = "Chat title"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename chat")
            .setView(nameInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = nameInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    chatDatabase.renameChat(chatId, title)
                    updateChatButtonTitle()
                } else {
                    Toast.makeText(this, "Title cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showDeleteChatDialog(chatId: String) {
        val chat = chatDatabase.getChat(chatId) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete chat?")
            .setMessage("Delete \"${chat.title}\" and all its saved messages? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    generationJob?.cancelAndJoin()
                    chatDatabase.deleteChat(chatId)
                    val remaining = chatDatabase.listChats()
                    val next = if (remaining.isEmpty()) chatDatabase.createChat() else remaining.first()
                    switchToChat(next.id)
                }
            }
            .show()
    }

    private fun switchToChat(chatId: String) {
        if (chatDatabase.getChat(chatId) == null) return
        lifecycleScope.launch {
            generationJob?.cancelAndJoin()
            currentChatId = chatId
            getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_CHAT, chatId).apply()
            reloadMessagesFromDatabase(chatId)
            updateChatButtonTitle()

            if (isModelReady) {
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false
                try {
                    withContext(Dispatchers.IO) {
                        engine.restoreConversationHistory(historyForEngine(chatId))
                    }
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not restore selected chat", e)
                    userInputEt.isEnabled = false
                    userActionFab.isEnabled = true
                    Toast.makeText(this@MainActivity, "Could not restore chat context: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun historyForEngine(chatId: String): List<ConversationTurn> =
        chatDatabase.getMessages(chatId)
            .filterNot { it.role == ChatMessageRecord.ROLE_ASSISTANT && it.content.isBlank() }
            .takeLast(MAX_RESTORED_MESSAGES)
            .map { turn ->
                ConversationTurn(turn.role, turn.content.takeLast(MAX_RESTORED_CHARS))
            }

    private suspend fun restoreCurrentConversationHistoryToEngine(chatId: String) {
        engine.restoreConversationHistory(historyForEngine(chatId))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMessageActions(message: Message) {
        val options = mutableListOf("Copy")
        if (message.isUser) options.add("Edit and resend")
        else options.add("Regenerate answer")
        options.add("Delete message")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (message.isUser) "User message" else "Assistant message")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Copy" -> copyMessage(message)
                    "Edit and resend" -> editAndResend(message)
                    "Regenerate answer" -> regenerateAnswer(message)
                    "Delete message" -> deleteMessageAndRestoreContext(message)
                }
            }
            .show()
    }

    private fun copyMessage(message: Message) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LocalMind message", message.content))
        Toast.makeText(this, "Message copied.", Toast.LENGTH_SHORT).show()
    }

    private fun editAndResend(message: Message) {
        val record = chatDatabase.getMessages(currentChatId).firstOrNull { it.id == message.id } ?: return
        val editor = EditText(this).apply {
            setText(record.content)
            setSelection(text.length)
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit and resend")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Resend") { _, _ ->
                val editedText = editor.text.toString().trim()
                if (editedText.isNotEmpty()) {
                    lifecycleScope.launch {
                        generationJob?.cancelAndJoin()
                        chatDatabase.deleteMessagesFrom(currentChatId, record.position)
                        reloadMessagesFromDatabase(currentChatId)
                        if (isModelReady) {
                            withContext(Dispatchers.IO) {
                                engine.restoreConversationHistory(historyForEngine(currentChatId))
                            }
                            sendMessage(editedText)
                        } else {
                            userInputEt.setText(editedText)
                        }
                    }
                }
            }
            .show()
    }

    private fun regenerateAnswer(message: Message) {
        val records = chatDatabase.getMessages(currentChatId)
        val assistantIndex = records.indexOfFirst { it.id == message.id }
        if (assistantIndex < 0) return
        val userRecord = records.take(assistantIndex)
            .lastOrNull { it.role == ChatMessageRecord.ROLE_USER }
        if (userRecord == null) {
            Toast.makeText(this, "No preceding user message to regenerate.", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = userRecord.content
        lifecycleScope.launch {
            generationJob?.cancelAndJoin()
            chatDatabase.deleteMessagesFrom(currentChatId, userRecord.position)
            reloadMessagesFromDatabase(currentChatId)
            if (isModelReady) {
                withContext(Dispatchers.IO) {
                    engine.restoreConversationHistory(historyForEngine(currentChatId))
                }
                sendMessage(prompt)
            } else {
                userInputEt.setText(prompt)
                Toast.makeText(this@MainActivity, "Load a model before regenerating.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteMessageAndRestoreContext(message: Message) {
        val records = chatDatabase.getMessages(currentChatId)
        val index = records.indexOfFirst { it.id == message.id }
        if (index < 0) return
        val record = records[index]
        val deleteFrom = if (record.role == ChatMessageRecord.ROLE_USER) {
            record.position
        } else {
            records.take(index).lastOrNull { it.role == ChatMessageRecord.ROLE_USER }?.position ?: record.position
        }
        lifecycleScope.launch {
            generationJob?.cancelAndJoin()
            chatDatabase.deleteMessagesFrom(currentChatId, deleteFrom)
            reloadMessagesFromDatabase(currentChatId)
            if (isModelReady) {
                try {
                    withContext(Dispatchers.IO) {
                        engine.restoreConversationHistory(historyForEngine(currentChatId))
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Could not update model context: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleUserInput() {
        val userMessage = userInputEt.text.toString().trim()
        if (userMessage.isEmpty()) {
            Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isModelReady) {
            getContent.launch(arrayOf("*/*"))
            return
        }
        userInputEt.text = null
        sendMessage(userMessage)
    }

    private fun sendMessage(userMessage: String) {
        val prompt = userMessage.trim()
        if (prompt.isEmpty() || !isModelReady || currentChatId.isBlank()) return

        val targetChatId = currentChatId
        val userRecord = chatDatabase.insertMessage(
            targetChatId, ChatMessageRecord.ROLE_USER, prompt
        )
        val assistantRecord = chatDatabase.insertMessage(
            targetChatId, ChatMessageRecord.ROLE_ASSISTANT, ""
        )
        if (targetChatId == currentChatId) {
            messages.add(Message(userRecord.id, userRecord.content, true))
            messages.add(Message(assistantRecord.id, "", false))
            messageAdapter.notifyDataSetChanged()
            messagesRv.scrollToPosition(0)
            updateChatButtonTitle()
        }
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        lastAssistantMsg.clear()
        lastAssistantMsg.append("")

        val answer = StringBuilder()
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            var tokenCount = 0
            try {
                engine.sendUserPrompt(prompt)
                    .onCompletion {
                        chatDatabase.updateMessage(assistantRecord.id, answer.toString())
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (currentChatId == targetChatId) {
                                userInputEt.isEnabled = isModelReady
                                userActionFab.isEnabled = true
                            }
                        }
                    }
                    .collect { token ->
                        answer.append(token)
                        tokenCount++
                        if (tokenCount % PERSIST_PARTIAL_EVERY_TOKENS == 0) {
                            chatDatabase.updateMessage(assistantRecord.id, answer.toString())
                        }
                        withContext(Dispatchers.Main) {
                            if (currentChatId == targetChatId) {
                                val index = messages.indexOfFirst { it.id == assistantRecord.id }
                                if (index >= 0) {
                                    messages[index] = messages[index].copy(content = answer.toString())
                                    messageAdapter.notifyItemChanged(index)
                                }
                            }
                        }
                    }
                chatDatabase.updateMessage(assistantRecord.id, answer.toString())
            } catch (e: CancellationException) {
                chatDatabase.updateMessage(assistantRecord.id, answer.toString())
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat generation failed", e)
                val failureText = if (answer.isEmpty()) {
                    "[Generation failed: ${e.message ?: e.javaClass.simpleName}]"
                } else {
                    answer.toString()
                }
                chatDatabase.updateMessage(assistantRecord.id, failureText)
                withContext(Dispatchers.Main) {
                    if (currentChatId == targetChatId) {
                        val index = messages.indexOfFirst { it.id == assistantRecord.id }
                        if (index >= 0) {
                            messages[index] = messages[index].copy(content = failureText)
                            messageAdapter.notifyItemChanged(index)
                        }
                        Toast.makeText(this@MainActivity, "Generation failed: ${e.message}", Toast.LENGTH_LONG).show()
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
