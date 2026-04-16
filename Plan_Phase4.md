# Phase 4 Plan — Image Attachment + Chat-Bar Collection Picker

---

## Feature A — Image Attachment

### A0 · LiteRT SDK Upgrade (prerequisite)

The compiled dex for `litertlm-android:0.10.0` only contains `Content$Text`.
`Content.Image` does not exist in this version.

**Action:** Bump the dependency in `app/build.gradle.kts`:
```kotlin
// from
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
// to
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.1")   // or latest stable
```
After upgrading, verify `Content.Image(bitmap: Bitmap)` or `Content.InlineData(mimeType, ByteArray)` is
available. If neither exists, fall back to base64-in-prompt encoding (described in A3b below).
Both Gemma 4 E2B and E4B are vision-capable models so the runtime supports images; it is the SDK
wrapper that needs updating.

---

### A1 · DB Layer — Migration 4 → 5

**Modified: `ChatMessageEntity`**
```kotlin
val imagePath: String? = null   // absolute path inside filesDir/chat_images/; null = no image
```

**Modified: `AnvitDatabase`** — version 4 → 5, `MIGRATION_4_5`:
```sql
ALTER TABLE chat_messages ADD COLUMN imagePath TEXT
```
No default needed; null means no image, which is correct for all existing rows.

Add `MIGRATION_4_5` to the `Room.databaseBuilder` migrations list.

---

### A2 · New: `ImageAttachmentManager.kt`

Location: `document/ImageAttachmentManager.kt`

```kotlin
object ImageAttachmentManager {

    private const val DIR = "chat_images"
    private const val MAX_DIM = 1024
    private const val QUALITY  = 85

    /** Copy a content URI into stable internal storage. Returns the absolute file path. */
    fun copyToStorage(context: Context, uri: Uri): String

    /** Load and scale the image to MAX_DIM on its longest side. */
    fun loadScaledBitmap(path: String): Bitmap

    /** Convert Bitmap → JPEG ByteArray for the SDK InlineData API. */
    fun toJpegBytes(bitmap: Bitmap): ByteArray

    /** Delete a stored image (call after the chat message is deleted). */
    fun delete(path: String)
}
```

Internally `copyToStorage` reads from the content resolver, writes to
`context.filesDir/chat_images/<uuid>.jpg`, and returns the path string.
`loadScaledBitmap` uses `BitmapFactory.Options.inSampleSize` to avoid OOM on large photos.

---

### A3 · `GemmaInferenceService` — Vision Support

Add `supportsVision: Boolean` to `GemmaModel` (both E2B and E4B → `true`).

**Modified: `GemmaInferenceService.generateStream()` and `generateResponse()`**
```kotlin
fun generateStream(
    prompt: String,
    systemPrompt: String,
    useAgentTools: Boolean = false,
    imagePath: String? = null          // ← new
): Flow<String>

suspend fun generateResponse(
    prompt: String,
    systemPrompt: String? = null,
    imagePath: String? = null          // ← new
): String
```

**A3a — SDK has `Content.Image`** (preferred, once SDK ≥ 0.10.1 confirms it):
```kotlin
val contents = if (imagePath != null) {
    val bitmap = ImageAttachmentManager.loadScaledBitmap(imagePath)
    Contents.of(Content.Image(bitmap), Content.Text(prompt))
} else {
    Contents.of(Content.Text(prompt))
}
conv.sendMessageAsync(contents).collect { … }
```

**A3b — Fallback (if SDK still lacks image API):**
Encode the image as a base64 data URL and prepend it to the prompt text:
```kotlin
val effectivePrompt = if (imagePath != null) {
    val bytes  = ImageAttachmentManager.toJpegBytes(
                     ImageAttachmentManager.loadScaledBitmap(imagePath))
    val b64    = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    "[image/jpeg;base64,$b64]\n$prompt"
} else prompt
```
Note: this only works if the model's text encoder understands the tag format — treat it as a
best-effort fallback. Prefer A3a once the SDK exposes the proper Image content type.

---

### A4 · Thread `imagePath` Through the Pipeline

**`AgenticRagOrchestrator.process()`** — add `imagePath: String? = null`:
- Pass `imagePath` to all `inferenceService.generateStream()` call sites
  (`DIRECT`, `SINGLE_SHOT`, `agenticFlow` initial generation, self-critique refinement)

**`ChatViewModel.sendMessage()`**:
- Read `pendingImagePath` from `ChatUiState` before sending
- Pass to `orchestrator.process()`
- Clear `pendingImagePath` after the coroutine starts (before collect)

---

### A5 · ViewModel Changes

**`ChatUiState`** — add:
```kotlin
val pendingImagePath: String? = null
```

**`ChatViewModel`** — add:
```kotlin
fun attachImage(context: Context, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
        val path = ImageAttachmentManager.copyToStorage(context, uri)
        _uiState.update { it.copy(pendingImagePath = path) }
    }
}

fun clearAttachedImage() {
    _uiState.value.pendingImagePath?.let { ImageAttachmentManager.delete(it) }
    _uiState.update { it.copy(pendingImagePath = null) }
}
```

In `sendMessage()`:
1. If `pendingImagePath != null` AND the current model's `supportsVision == false`:
   emit an error: *"This model doesn't support images. Load a Gemma 4 vision model first."*
2. Capture `val imagePath = _uiState.value.pendingImagePath` at start
3. `_uiState.update { it.copy(pendingImagePath = null) }` right after capturing (so the UI clears)
4. Pass `imagePath` to `orchestrator.process()`

`ChatMessage` (UI model) already has `wasStopped` from last phase; add `imagePath: String? = null`.

---

### A6 · ChatScreen UI

**Image picker launcher** — at the top of `ChatScreen`:
```kotlin
// Preferred: no permission needed on API 33+
val imagePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri -> uri?.let { viewModel.attachImage(context, it) } }

// Legacy fallback for API < 33
val imagePickerLegacy = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri -> uri?.let { viewModel.attachImage(context, it) } }
```
Pick `PickVisualMedia` on API ≥ 33, `GetContent("image/*")` otherwise
(check `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`).

**`ChatInputBar`** — add `pendingImagePath: String?` + `onAttachImage: () -> Unit` + `onClearImage: () -> Unit`:

Layout (vertical stack inside the `Surface`):
```
┌──────────────────────────────────────────────────────────┐
│  [Thumbnail 48dp × 48dp  ✕]   ← shown only when attached  │
│  ┌──────────────────────────────────────────────────┐  ⏹ │
│  │  Ask about your documents…                        │    │
│  └──────────────────────────────────────────────────┘    │
│  [🖼 Attach image]  (small text-button, bottom-left)      │
└──────────────────────────────────────────────────────────┘
```

- Thumbnail: `AsyncImage(model = File(pendingImagePath))` at 48dp, `RoundedCornerShape(8.dp)`,
  with a 16dp `✕` `IconButton` overlaid at top-right corner
- "Attach image" button: only visible when `pendingImagePath == null`; hidden when generating

**`MessageBubble`** — when `message.imagePath != null`:
Show `AsyncImage` above the text content inside the bubble:
```kotlin
if (!isUser && message.imagePath != null) {
    AsyncImage(
        model = File(message.imagePath),
        contentDescription = "Attached image",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 4.dp)),
        contentScale = ContentScale.Crop
    )
    Spacer(Modifier.height(6.dp))
}
// user bubble: same pattern but right-aligned shape
```

---

---

## Feature B — Collection Picker in Chat Bar + No-RAG Mode

### B1 · `ChatUiState` Changes

```kotlin
data class ChatUiState(
    ...
    val collections: List<CollectionEntity> = emptyList(),
    val chatCollectionId: String? = null,    // null = no collection → direct mode
    val chatCollectionName: String = "No docs",
    ...
)
```

`chatCollectionId = null` means bypass RAG entirely. Non-null means use that collection.

---

### B2 · `ChatViewModel` Changes

**In `init {}`:**

1. Observe `collectionDao.getAllCollections()` to keep `collections` in state in sync:
   ```kotlin
   viewModelScope.launch {
       app.database.collectionDao().getAllCollections().collect { list ->
           _uiState.update { it.copy(collections = list) }
       }
   }
   ```

2. When the active collection changes in preferences **or** when a new session is loaded,
   initialize `chatCollectionId` from preferences (so it defaults to whatever the user
   has selected in the Documents screen, but can be overridden per-chat without persisting):
   ```kotlin
   // Inside the flatMapLatest block that already watches activeSessionId:
   val collectionId = app.preferences.getActiveCollectionId()
   val collectionName = app.database.collectionDao().getCollection(collectionId)?.name ?: "General"
   _uiState.update { it.copy(
       chatCollectionId   = collectionId,
       chatCollectionName = collectionName
   )}
   ```

**New function:**
```kotlin
fun selectChatCollection(id: String?) {
    viewModelScope.launch {
        val name = if (id == null) "No docs"
                   else app.database.collectionDao().getCollection(id)?.name ?: "General"
        _uiState.update { it.copy(chatCollectionId = id, chatCollectionName = name) }
    }
}
```

**Modified `sendMessage()`:**

Replace:
```kotlin
val collectionId = prefs.getActiveCollectionId()
```
With:
```kotlin
val collectionId = _uiState.value.chatCollectionId   // null if user chose "No docs"
```

Then add a short-circuit at the top of the generation logic:
```kotlin
if (collectionId == null) {
    // Direct mode — skip orchestrator entirely
    val directFlow = inferenceService.generateStream(
        prompt       = buildPrompt(history, userText, null),
        systemPrompt = "You are Sage, a helpful and concise AI assistant.",
        useAgentTools = false,
        imagePath     = imagePath
    )
    // collect directFlow exactly like result.answerFlow below (same try/finally block)
    ...
    return@launch  // after collect
}
// Otherwise fall through to orchestrator path as today
val result = orchestrator.process(userQuery = userText, ..., collectionId = collectionId)
```

To avoid code duplication, extract the stream-collection logic into a private `collectAnswerFlow(flow, assistantMsgId, sessionId, agentSteps)` suspend function that both the direct path and the orchestrator path share.

---

### B3 · `ChatScreen` UI

#### Updated `ChatTopBar`

Replace the current static collection chip with a tappable one:
```kotlin
// Inside the subtitle Row:
Surface(
    modifier = Modifier
        .clip(RoundedCornerShape(4.dp))
        .clickable { onCollectionClick() },   // ← opens picker sheet
    ...
) { ... }
```
Pass `onCollectionClick: () -> Unit` into `ChatTopBar`.
Show `chatCollectionName` (or "No docs" with a `FolderOff`-style icon when null).

#### Collection row above input bar

Add an optional row directly above the `ChatInputBar` divider that shows the current
collection chip. This is the primary interactive entry point:

```kotlin
// In the Column of ChatScreen, between message list and input bar:
CollectionSelectorRow(
    collectionName = uiState.chatCollectionName,
    isNoCollection = uiState.chatCollectionId == null,
    onClick        = { showCollectionPicker = true }
)
```

`CollectionSelectorRow` design:
```
 📁 Research   ▾          (teal chip, animated)
 — No docs mode —         (grey chip with folder-off icon, when null)
```

Width: `wrapContentWidth`, left-aligned, 6dp vertical padding.
`AnimatedContent` on the chip text so switching collection name slides in.

#### `CollectionPickerSheet` composable (ModalBottomSheet)

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPickerSheet(
    collections: List<CollectionEntity>,
    currentId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
)
```

Layout:
```
┌────────────────────────────────────────────┐
│  Search in…                  ────────────  │  ← drag handle + header
│                                            │
│  ○  No collection  (direct chat)           │  ← always first
│  ●  General          (default, checked)    │  ← collections from DB
│  ○  Research                               │
│  ○  Work                                   │
└────────────────────────────────────────────┘
```

- Uses `ModalBottomSheet` (M3) with `sheetState = rememberModalBottomSheetState()`
- `LazyColumn` of collection rows: `RadioButton` + name + doc count badge
- "No collection" row at top with `Icons.Default.FolderOff`
- Selected item: radio filled + row background `TealPrimary.copy(0.08f)`
- Selecting any item calls `onSelect(id)` and dismisses the sheet

---

### B4 · Visual indicator in MessageBubble

When a message was sent in "no collection" (direct) mode, show a small
`💬 Direct chat` indicator instead of the collection chip — so the user
can see at-a-glance which messages used RAG and which didn't.

Store `usedDirectMode: Boolean` on `ChatMessage`. Set it in `sendMessage()`
based on `collectionId == null`.

---

---

## Implementation Order

| Step | What | Why |
|------|------|-----|
| 1 | Feature B: ViewModel (B2) | Pure logic, no UI risk |
| 2 | Feature B: UI — CollectionSelectorRow + Sheet (B3) | Unlocks testing the no-RAG path |
| 3 | Feature B: MessageBubble direct-mode indicator (B4) | Polish |
| 4 | LiteRT SDK upgrade check (A0) | Gate for image feature |
| 5 | Feature A: DB migration + `ImageAttachmentManager` (A1, A2) | Foundation |
| 6 | Feature A: `GemmaInferenceService` vision path (A3) | Core inference change |
| 7 | Feature A: Thread through orchestrator + ViewModel (A4, A5) | Wiring |
| 8 | Feature A: ChatScreen UI — picker + thumbnail + bubble (A6) | UI completion |

---

## Files Changed Summary

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Bump litertlm to ≥ 0.10.1 |
| `data/db/AnvitDatabase.kt` | Version 4→5, MIGRATION_4_5 |
| `data/db/entities/ChatMessageEntity.kt` | + `imagePath: String?` |
| `data/models/GemmaModel.kt` | + `supportsVision: Boolean` |
| `document/ImageAttachmentManager.kt` | **NEW** |
| `inference/GemmaInferenceService.kt` | + `imagePath` param, vision content |
| `agentic/AgenticRagOrchestrator.kt` | + `imagePath` param thread-through |
| `ui/viewmodels/ChatViewModel.kt` | + `collections`, `chatCollectionId`, `pendingImagePath`, `selectChatCollection()`, `attachImage()`, direct-mode short-circuit |
| `ui/screens/ChatScreen.kt` | + `CollectionSelectorRow`, `CollectionPickerSheet`, image picker launcher, thumbnail preview, updated `MessageBubble` |
| `ui/viewmodels/ChatViewModel.kt` (ChatMessage) | + `imagePath`, `usedDirectMode` |
