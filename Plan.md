Here is the full implementation plan across all three features.

---

## Feature 1 — Chat Sessions

### DB Layer  (migration 2 → 3)

**New file: `data/db/entities/ChatSessionEntity.kt`**
```kotlin
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,         // UUID
    val title: String,                  // auto-generated from first user message (first 40 chars)
    val createdAt: Long,
    val updatedAt: Long,                // updated on every new message
    val messageCount: Int = 0
)
```

**Modified: `ChatMessageEntity`** — add `val sessionId: String` (NOT NULL, no default — migration fills existing rows with a "legacy" session ID).

**New file: `data/db/ChatSessionDao.kt`**
```kotlin
@Dao interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = REPLACE) suspend fun insertSession(session: ChatSessionEntity)
    @Update suspend fun updateSession(session: ChatSessionEntity)
    @Query("DELETE FROM chat_sessions WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId") suspend fun deleteMessagesForSession(sessionId: String)
}
```

**Modified: `ChatDao`**  
- Add `getMessagesForSession(sessionId): Flow<List<ChatMessageEntity>>`  
- Add `getMessagesForSessionList(sessionId): List<ChatMessageEntity>`  
- Existing `getAllMessages()` kept for migration compatibility only

**Modified: `AnvitDatabase`** — version 2 → 3, `MIGRATION_2_3`:
1. `CREATE TABLE chat_sessions (...)`
2. `INSERT INTO chat_sessions VALUES ('legacy-session', 'Previous Conversation', <now>, <now>, 0)`
3. `ALTER TABLE chat_messages ADD COLUMN sessionId TEXT NOT NULL DEFAULT 'legacy-session'`

---

### Preferences

**Modified: `AnvitPreferences`** — add `ACTIVE_SESSION_ID` key, `activeSessionId: Flow<String>`, `setActiveSessionId()`.

---

### ViewModel Layer

**New file: `ui/viewmodels/ChatSessionsViewModel.kt`**
```kotlin
data class ChatSessionsUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String = ""
)
```
- `createNewSession()` — generates UUID, title = "New Chat", inserts, sets as active
- `deleteSession(id)` — deletes session + all its messages in one transaction
- `renameSession(id, newTitle)` — update title
- `switchToSession(id)` — update active session pref

**Modified: `ChatViewModel`**
- Observe `activeSessionId` pref; reload messages from `getMessagesForSession(sessionId)` whenever it changes
- `buildHistory()` — query last `MAX_HISTORY_MESSAGES` **filtered by current sessionId**
- `sendMessage()` — on first message in a new session, auto-set session title to first 40 chars of user input
- Update `updatedAt` + `messageCount` on `ChatSessionEntity` after each assistant reply
- `clearChat()` — now deletes only messages for the active session, not everything globally

---

### UI Layer (Compose)

**`ChatScreen.kt`** restructured around a `ModalNavigationDrawer`:

```
ModalNavigationDrawer(
    drawerContent = { SessionsDrawerContent(...) }
) {
    Scaffold(
        topBar = { ChatTopBar(sessionTitle, onMenuClick = { openDrawer() }) }
        ...
    )
}
```

**`SessionsDrawerContent` composable** (new, inside `ChatScreen.kt` or extracted):
- **Drawer header**: gradient `Navy900 → Navy800` background, "Anvit" wordmark + subtitle "Local AI"
- **"New Chat" button**: full-width `OutlinedButton` with `Icons.Default.Add`, teal border
- `LazyColumn` of session items:
  - `SessionItem` composable: title (bold), relative timestamp ("2 min ago"), message count chip
  - Active session: left accent bar in TealPrimary, slightly lighter background
  - `SwipeToDismissBox` for swipe-left delete with red background + trash icon revealed
  - Long-press → `AlertDialog` to rename (single `OutlinedTextField`)
- Drawer width: `0.82f` of screen width via `DrawerDefaults`

**`ChatTopBar` composable**:
- Hamburger `IconButton` on the left → opens drawer
- Center: active session title (truncated at 20 chars with ellipsis)
- Right: model status indicator dot (green/grey) + clear button

**History sent to LLM**: Last 10 messages WHERE `sessionId = activeSessionId`. Multi-turn continuity is fully preserved per session.

---

## Feature 2 — Vector DB Collections

### DB Layer  (migration 3 → 4)

**New file: `data/db/entities/CollectionEntity.kt`**
```kotlin
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,          // UUID
    val name: String,                    // user-defined
    val description: String = "",
    val createdAt: Long,
    val isDefault: Boolean = false       // the "All Documents" default collection
)
```

**Modified: `DocumentEntity`** — add `val collectionId: String` (default = "default-collection")

**Modified: `ChunkEntity`** — add `val collectionId: String` (denormalized from parent doc — avoids a JOIN on every retrieval query)

**New file: `data/db/CollectionDao.kt`**
```kotlin
@Dao interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY createdAt ASC")
    fun getAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT COUNT(*) FROM documents WHERE collectionId = :id")
    fun getDocumentCount(id: String): Flow<Int>

    @Insert(onConflict = REPLACE) suspend fun insertCollection(c: CollectionEntity)
    @Update suspend fun updateCollection(c: CollectionEntity)
    @Query("DELETE FROM collections WHERE id = :id") suspend fun deleteCollection(id: String)
    @Query("DELETE FROM documents WHERE collectionId = :id") suspend fun deleteDocumentsForCollection(id: String)
}
```

**Modified: `DocumentDao`**  
- `getAllDocuments()` → `getDocumentsForCollection(collectionId): Flow<List<DocumentEntity>>`  
- Keep `getAllDocuments()` for the "All" virtual collection view

**Modified: `AnvitDatabase`** — version 3 → 4, `MIGRATION_3_4`:
1. `CREATE TABLE collections (...)`
2. Insert default collection row (`id='default-collection', name='General', isDefault=1`)
3. `ALTER TABLE documents ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'`
4. `ALTER TABLE chunks ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'`

---

### Preferences

**Modified: `AnvitPreferences`** — add `ACTIVE_COLLECTION_ID`, default = `"default-collection"`.

---

### Retrieval Layer

**Modified: `HybridRetriever`**  
- `retrieve(query, maxResults, collectionId: String? = null)` — when `collectionId` is non-null and not the "all" sentinel, add `WHERE collectionId = :collectionId` to both vector fetch and FTS queries
- `retrieveVector()` and `retrieveLexical()` both accept and thread through the filter

**Modified: `AgenticRagOrchestrator`** — pass `collectionId` down from caller through all retrieval steps.

**Modified: `ChatViewModel`** — read `activeCollectionId` from prefs, pass to orchestrator.

---

### ViewModel Layer

**New file: `ui/viewmodels/CollectionsViewModel.kt`**
```kotlin
data class CollectionsUiState(
    val collections: List<CollectionEntity> = emptyList(),
    val activeCollectionId: String = "default-collection",
    val documentCountsById: Map<String, Int> = emptyMap()
)
```
- `createCollection(name, description)` — insert new `CollectionEntity`
- `deleteCollection(id)` — cascade deletes documents + chunks + updates active if needed
- `renameCollection(id, newName)`
- `setActiveCollection(id)` — persists to prefs
- `getDocumentCountForCollection(id): Flow<Int>`

**Modified: `DocumentsViewModel`**  
- Observe `activeCollectionId`; pass it to `getAllDocuments()` filter
- `ingestPdf()` — attach current `activeCollectionId` to `DocumentEntity` and `ChunkEntity` on creation

---

### UI Layer (Compose)

**Redesigned `DocumentsScreen.kt`** — two-panel concept:

**Top section — Collection Selector:**
```
LazyRow of CollectionChip composables
  [+ New]  [General ✓]  [Research]  [Work]  ...
```
- `CollectionChip`: pill-shaped `FilterChip`, selected = TealPrimary fill, unselected = outlined
- `[+ New]` chip: opens `CreateCollectionDialog` (`AlertDialog` with name + description fields)
- Long-press on any chip: `DropdownMenu` with Rename / Delete options
- Delete: `AlertDialog` warning "This will delete N documents and all their embeddings"

**Main section — Document list for active collection:**
- Same `LazyColumn` of `DocumentCard` as now, but filtered to active collection
- Empty state updates to: "No documents in [collection name]. Tap + to add a PDF"
- `DocumentCard` — slightly redesigned (see aesthetic section)

**FAB** — opens file picker, ingests to active collection (unchanged behavior, new routing)

The "All Documents" view: a special read-only collection chip called "All" that shows everything with collection name badges on each `DocumentCard`.

---

## Feature 3 — Aesthetic Overhaul

### Color System  (`ui/theme/Color.kt`)

Add elevation-based surface colors and gradient helpers:
```kotlin
// Surfaces (elevation layers)
val Surface0 = Color(0xFF0A0E1A)   // deepest background
val Surface1 = Color(0xFF111827)   // cards, drawers  (current NavyCard)
val Surface2 = Color(0xFF1A2235)   // elevated cards, modals
val Surface3 = Color(0xFF1E2940)   // chips, input fields

// Borders
val BorderSubtle  = Color(0xFF1F2D45)
val BorderDefault = Color(0xFF2A3D5C)
val BorderFocus   = TealPrimary.copy(alpha = 0.6f)

// Semantic
val UserBubbleBg     = Color(0xFF0D3349)   // deep blue for user messages
val AssistantBubbleBg = Surface1           // card-level for assistant
val ThinkingBg       = Color(0xFF12213A)
```

Remove hard-coded `Navy900`, `NavyCard`, `Navy700`, `Navy800` usages — replace with the surface scale throughout all screens.

---

### Typography  (`ui/theme/Type.kt`)

Define the full Material3 type scale with intentional weights:
```kotlin
val SageTypography = Typography(
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,   letterSpacing = (-0.3).sp),
    titleLarge     = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleMedium    = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontSize = 12.sp, color = TextSecondary),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
)
```
Use `MaterialTheme.typography.*` instead of hard-coded `fontSize` throughout.

---

### Chat UI  (`ChatScreen.kt`)

**Message bubbles** — `MessageBubble` composable overhaul:
- **User bubble**: right-aligned, `UserBubbleBg` background, 18dp radius top-left/bottom both, 4dp radius top-right (asymmetric — modern chat style), subtle `1dp` border in `TealPrimary.copy(0.3f)`
- **Assistant bubble**: left-aligned, `AssistantBubbleBg`, 4dp top-left / 18dp elsewhere, left accent strip — a 3dp wide `Box` in `TealPrimary`
- **Timestamps**: shown below each bubble, `TextSecondary` 10sp, formatted "HH:mm"
- **Streaming indicator**: three animated dots (`InfiniteTransition` + `animateFloat`) replacing the static "…"
- **Thinking section**: shimmer gradient animation (`InfiniteTransition` sweeping `Surface2 → TealPrimary.copy(0.15f) → Surface2`) while thinking is active; static italic text after completion
- `animateContentSize(spring(stiffness = Spring.StiffnessLow))` on each bubble for smooth height growth while streaming

**Input bar**:
- Pill-shaped container: `Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 4.dp)`
- `TextField` with no border (transparent), `bodyMedium` style
- Send button changes: idle = outlined icon, generating = `CircularProgressIndicator(size=20dp)` with cancel semantics
- `animateContentSize` on the bar itself so it expands gracefully for multi-line input

**Empty state**:
- Centered `Column` with a large `Icon(Icons.Outlined.AutoAwesome)` in `TealPrimary.copy(0.4f)`
- "Ask Anvit anything" headline
- Subtle suggestion chips row: `["Summarize my docs", "Key findings?", "Compare sections"]` — tapping pre-fills input

---

### Sessions Drawer

- Gradient drawer header using `Box` with `Brush.verticalGradient(Surface0, Surface2)`, "Anvit" in large `headlineMedium` with TealPrimary tint, version/model name in `bodySmall`
- Session items: `Card(tonalElevation = 2.dp)` per item, animated selection highlight via `animateColorAsState`
- Swipe-to-delete: red `Surface` revealed behind, trash icon, spring-back animation on cancel

---

### Documents Screen

**Collection chips row**: `LazyRow` with `horizontalArrangement = Arrangement.spacedBy(8.dp)`, smooth `animateContentSize` on selection change.

**`DocumentCard`** redesign:
- Left side: PDF icon in a rounded `Surface(color = TealPrimary.copy(0.15f))` box
- Right: file name in `titleMedium`, metadata row (pages, chunks) in `labelSmall`
- Status chip: `AnimatedContent` transitions between PROCESSING/READY/FAILED
- Processing state: `LinearProgressIndicator` with animated `indeterminate` mode (wavy)
- Swipe-to-delete available (same pattern as session drawer)

**Empty state**: illustration-style `Canvas`-drawn document stack icon, "Add your first PDF" with arrow pointing to FAB.

---

### Settings Screen

- Section headers: `Text` with a `Divider` that is 40% width and `TealPrimary` color (accent line) instead of full-width grey divider
- Model cards: selected model gets a `Border(1.5.dp, TealPrimary)` + faint `TealPrimary.copy(0.06f)` background tint
- Download button: when downloading, animate from button → progress bar using `AnimatedContent(targetState = downloadState)`

---

### Navigation Bar

- `NavigationBar` gets `tonalElevation = 8.dp` and a top border `Divider(color = BorderSubtle, thickness = 0.5.dp)` to visually separate from content
- Selected tab icon: `Icon` wrapped in a `Surface(shape = RoundedCornerShape(16.dp), color = TealPrimary.copy(0.15f))` pill — same pattern as Material3 Expressive

---

## Implementation Sequence

The features have dependencies, so the recommended build order is:

1. **DB migrations first** (2→3 for sessions, 3→4 for collections) — everything else depends on the schema being stable
2. **Session DAOs + ViewModel** — no UI dependency, can be done in isolation
3. **Chat Sessions UI** — `ModalNavigationDrawer` + `SessionsDrawerContent` in `ChatScreen`
4. **Collection DAOs + ViewModel** — depends on migration 3→4
5. **Documents Screen redesign with collections** — depends on `CollectionsViewModel`
6. **HybridRetriever + Orchestrator collection filter** — plumb `collectionId` through the RAG pipeline
7. **Aesthetic pass** — do this last so it applies to the final UI surface area, not an intermediate state. Start with `Color.kt` / `Type.kt` (global), then `ChatScreen`, then `DocumentsScreen`, then `SettingsScreen`

Each migration step must be tested for data preservation (existing messages survive, existing documents get assigned to `default-collection`) before moving to the next layer.