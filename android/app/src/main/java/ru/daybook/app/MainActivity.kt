package ru.daybook.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.Calendar
import java.util.Locale
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.daybook.app.data.DaybookEntity
import ru.daybook.app.data.DaybookRepository
import ru.daybook.app.data.DaybookSyncStateStore
import ru.daybook.app.data.DaybookDriveSyncCoordinator

class MainActivity : ComponentActivity() {
    private var driveConnected by mutableStateOf(false)
    private var driveStatus by mutableStateOf("Не подключён")
    private var driveAccessToken by mutableStateOf<String?>(null)
    private lateinit var authorizationLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    private val tokenRefreshHandler = Handler(Looper.getMainLooper())
    private val tokenRefreshRunnable = Runnable { if (driveConnected) authorizeDrive(allowResolution = false) }
    private val drivePreferences by lazy {
        getSharedPreferences("daybook_auth", MODE_PRIVATE)
    }

    private fun scheduleTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable)
        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 45 * 60 * 1000L)
    }

    override fun onDestroy() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.data != null) {
                runCatching {
                    Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(result.data!!)
                }.onSuccess { authorizationResult ->
                    driveAccessToken = authorizationResult.accessToken
                    driveConnected = !driveAccessToken.isNullOrBlank()
                    if (driveConnected) {
                        drivePreferences.edit().putBoolean("connected", true).apply()
                        scheduleTokenRefresh()
                    }
                    driveStatus = if (driveConnected) "Google Drive подключён" else
                        "Google не выдал токен. Проверь разрешение доступа к Drive."
                }.onFailure { error ->
                    driveConnected = false
                    driveAccessToken = null
                    drivePreferences.edit().putBoolean("connected", false).apply()
                    driveStatus = if (error is ApiException) {
                        "Ошибка Google OAuth (" + error.statusCode + "): " + (error.status.statusMessage ?: "без описания")
                    } else {
                        "Ошибка авторизации: " + (error.localizedMessage ?: error.javaClass.simpleName)
                    }
                }
            } else {
                driveConnected = false
                driveAccessToken = null
                drivePreferences.edit().putBoolean("connected", false).apply()
                driveStatus = "Google вернул пустой результат (resultCode=" + result.resultCode + "). Проверь OAuth-настройки."
            }
        }

        val repository = DaybookRepository(this)
        val syncCoordinator = DaybookDriveSyncCoordinator(repository, DaybookSyncStateStore(this))
        setContent {
            val vm: DaybookViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DaybookViewModel(repository, syncCoordinator) as T
            })
            DaybookApp(vm, driveConnected, driveStatus, driveAccessToken) { authorizeDrive(true) }
        }
        if (drivePreferences.getBoolean("connected", false)) authorizeDrive(allowResolution = false)
    }

    private fun authorizeDrive(allowResolution: Boolean = true) {
        driveAccessToken = null
        driveConnected = false
        driveStatus = "Проверяем доступ к Google Drive…"
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null && allowResolution) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else if (pendingIntent != null) {
                        driveConnected = false
                        driveStatus = "Нужно подтвердить доступ к Google Drive. Нажми кнопку подключения."
                    } else {
                        driveStatus = "Google сообщил, что требуется подтверждение, но не вернул окно авторизации."
                    }
                } else {
                    driveAccessToken = result.accessToken
                    driveConnected = !driveAccessToken.isNullOrBlank()
                    if (driveConnected) {
                        drivePreferences.edit().putBoolean("connected", true).apply()
                        scheduleTokenRefresh()
                    }
                    driveStatus = if (driveConnected) "Google Drive подключён" else "Не удалось получить токен"
                }
            }
            .addOnFailureListener { error ->
                driveConnected = false
                driveStatus = if (error is ApiException) {
                    "Ошибка Google OAuth (" + error.statusCode + "): " + (error.status.statusMessage ?: "без описания")
                } else {
                    "Ошибка подключения: " + (error.localizedMessage ?: error.javaClass.simpleName)
                }
            }
    }
}

class DaybookViewModel(
    private val repository: DaybookRepository,
    private val syncCoordinator: DaybookDriveSyncCoordinator
) : ViewModel() {
    val syncStatus = MutableStateFlow("Локально")
    val entries = repository.observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    private var activeAccessToken: String? = null
    private var syncJob: Job? = null
    private var debounceJob: Job? = null
    private var syncAgain = false

    fun save(
        id: String?, kind: String, title: String, body: String,
        due: String?, priority: String
    ) = viewModelScope.launch {
        repository.save(id = id, kind = kind, title = title, body = body, due = due, priority = priority)
        scheduleSync()
    }

    fun sync(accessToken: String) {
        activeAccessToken = accessToken
        requestSync()
    }

    fun syncNow() = requestSync()

    private fun scheduleSync() {
        if (activeAccessToken == null) return
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(900)
            requestSync()
        }
    }

    private fun requestSync() {
        val token = activeAccessToken ?: return
        if (syncJob?.isActive == true) {
            syncAgain = true
            return
        }
        syncJob = viewModelScope.launch {
            do {
                syncAgain = false
                syncStatus.value = "Синхронизация с Google Drive…"
                try {
                    val result = syncCoordinator.sync(token)
                    syncStatus.value = result.message
                } catch (error: Exception) {
                    syncStatus.value = error.message ?: "Ошибка синхронизации"
                }
            } while (syncAgain)
        }
    }

    fun toggle(entry: DaybookEntity) = viewModelScope.launch {
        repository.toggleDone(entry)
        scheduleSync()
    }

    fun delete(entry: DaybookEntity) = viewModelScope.launch {
        repository.delete(entry.id)
        scheduleSync()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaybookApp(
    vm: DaybookViewModel,
    driveConnected: Boolean,
    driveStatus: String,
    accessToken: String?,
    onConnectDrive: () -> Unit
) {
    val entries by vm.entries.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    LaunchedEffect(accessToken) { if (accessToken != null) vm.sync(accessToken) }
    val context = LocalContext.current
    var section by remember { mutableStateOf("home") }
    var taskFilter by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    var editorKind by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var priorityMenu by remember { mutableStateOf(false) }

    val ink = androidx.compose.ui.graphics.Color(0xFFE8E7E3)
    val muted = androidx.compose.ui.graphics.Color(0xFFA7A69F)
    val canvas = androidx.compose.ui.graphics.Color(0xFF141512)
    val accent = androidx.compose.ui.graphics.Color(0xFFA9BE9B)
    val tasks = entries.filter { it.kind == "task" }
    val notes = entries.filter { it.kind == "note" }
    val query = search.trim().lowercase()
    val filteredTasks = tasks.filter {
        (it.title + " " + it.body).lowercase().contains(query) &&
            when (taskFilter) { "active" -> !it.done; "done" -> it.done; else -> true }
    }.sortedWith(compareBy<DaybookEntity> { it.done }.thenBy { it.due ?: "9999" })
    val filteredNotes = notes.filter {
        (it.title + " " + it.body).lowercase().contains(query)
    }.sortedByDescending { it.updated }

    fun openEditor(kind: String, entry: DaybookEntity? = null) {
        editorKind = kind
        editingId = entry?.id
        title = entry?.title.orEmpty()
        body = entry?.body.orEmpty()
        due = entry?.due.orEmpty()
        priority = entry?.priority ?: "normal"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = androidx.compose.ui.graphics.Color.White,
        background = canvas,
        onBackground = ink,
        surface = androidx.compose.ui.graphics.Color(0xFF20221E),
        onSurface = ink,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A2D28),
        onSurfaceVariant = muted,
        secondary = androidx.compose.ui.graphics.Color(0xFFB6C9A9)
    )) {
        Scaffold(
            containerColor = canvas,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { openEditor(if (section == "tasks") "task" else "note") },
                    containerColor = accent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    shape = RoundedCornerShape(18.dp),
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                    text = { Text(if (section == "tasks") "Новая задача" else "Новая заметка") }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF20221E),
                    contentColor = accent,
                    tonalElevation = 0.dp
                ) {
                    listOf("home" to "Обзор", "tasks" to "Задачи", "notes" to "Заметки").forEach { (key, label) ->
                        NavigationBarItem(
                            selected = section == key,
                            onClick = { section = key },
                            icon = {
                                Text(
                                    when (key) { "home" -> "⌂"; "tasks" -> "✓"; else -> "▤" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = accent,
                                selectedTextColor = accent,
                                indicatorColor = androidx.compose.ui.graphics.Color(0xFF384536),
                                unselectedIconColor = muted,
                                unselectedTextColor = muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "DAYBOOK",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )
                        Text(
                            when (section) { "tasks" -> "Мои задачи"; "notes" -> "Мои заметки"; else -> "Моё пространство" },
                            style = MaterialTheme.typography.headlineSmall,
                            color = ink
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (driveConnected) androidx.compose.ui.graphics.Color(0xFF384536) else androidx.compose.ui.graphics.Color(0xFF2A2D28),
                        modifier = Modifier.clickable(onClick = onConnectDrive)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(if (driveConnected) "●" else "○", color = if (driveConnected) accent else muted)
                            Text(if (driveConnected) "Drive" else "Подключить", style = MaterialTheme.typography.labelMedium, color = ink)
                        }
                    }
                }

                if (driveConnected || driveStatus != "Не подключён") {
                    Text(
                        if (driveConnected) syncStatus else driveStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (driveConnected) accent else muted,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                if (section == "home") {
                    Text("Хорошие идеи начинаются с заметки.", style = MaterialTheme.typography.bodyLarge, color = muted)
                    Spacer(Modifier.height(18.dp))
                    DaybookSearchField(search, { search = it })
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryTile("Задачи", tasks.count { !it.done }.toString(), "осталось сделать",
                            androidx.compose.ui.graphics.Color(0xFF303A2D), Modifier.weight(1f)) { section = "tasks" }
                        SummaryTile("Заметки", notes.size.toString(), "сохранено мыслей",
                            androidx.compose.ui.graphics.Color(0xFF3A3429), Modifier.weight(1f)) { section = "notes" }
                    }
                    Spacer(Modifier.height(24.dp))
                    SectionHeading("На сегодня", "Все задачи") { section = "tasks" }
                    val homeTasks = tasks.filter { !it.done }.sortedBy { it.due ?: "9999" }.take(2)
                    if (homeTasks.isEmpty()) DaybookEmpty("Пока всё спокойно", "Добавь задачу, чтобы ничего не забыть.")
                    else EntryList(homeTasks, vm, ::openEditor, Modifier.heightIn(max = 145.dp))
                    Spacer(Modifier.height(22.dp))
                    SectionHeading("Недавние заметки", "Все заметки") { section = "notes" }
                    val homeNotes = notes.sortedByDescending { it.updated }.take(2)
                    if (homeNotes.isEmpty()) DaybookEmpty("Здесь будет твоя библиотека", "Сохраняй идеи, планы и важные мысли.")
                    else EntryList(homeNotes, vm, ::openEditor, Modifier.heightIn(max = 145.dp))
                } else {
                    DaybookSearchField(search, { search = it })
                    Spacer(Modifier.height(14.dp))
                    if (section == "tasks") {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("all" to "Все", "active" to "Активные", "done" to "Готово").forEach { (key, label) ->
                                FilterChip(
                                    selected = taskFilter == key,
                                    onClick = { taskFilter = key },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = androidx.compose.ui.graphics.Color(0xFF384536),
                                        selectedLabelColor = accent
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (filteredTasks.isEmpty()) DaybookEmpty("Ничего не найдено", "Попробуй изменить запрос или добавь новую задачу.")
                        else EntryList(filteredTasks, vm, ::openEditor, Modifier.weight(1f))
                    } else {
                        Text(filteredNotes.size.toString() + " заметок", style = MaterialTheme.typography.labelMedium, color = muted)
                        Spacer(Modifier.height(10.dp))
                        if (filteredNotes.isEmpty()) DaybookEmpty("Заметок пока нет", "Нажми «Новая заметка», чтобы записать мысль.")
                        else EntryList(filteredNotes, vm, ::openEditor, Modifier.weight(1f))
                    }
                }
            }
        }

        if (editorKind != null) {
            AlertDialog(
                onDismissRequest = { editorKind = null },
                containerColor = androidx.compose.ui.graphics.Color(0xFF20221E),
                title = {
                    Text(if (editingId == null) {
                        if (editorKind == "task") "Новая задача" else "Новая заметка"
                    } else {
                        if (editorKind == "task") "Редактировать задачу" else "Редактировать заметку"
                    })
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it },
                            label = { Text("Название") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(value = body, onValueChange = { body = it },
                            label = { Text("Текст") }, minLines = 3, shape = RoundedCornerShape(14.dp))
                        if (editorKind == "task") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = due, onValueChange = {}, readOnly = true,
                                    label = { Text("Срок") }, placeholder = { Text("Не задан") },
                                    modifier = Modifier.weight(1f), singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                TextButton(onClick = {
                                    val calendar = Calendar.getInstance()
                                    val parts = due.split("-")
                                    if (parts.size == 3) runCatching {
                                        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                    }
                                    DatePickerDialog(context, { _, year, month, day ->
                                        due = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)).show()
                                }) { Text("Выбрать") }
                                if (due.isNotBlank()) TextButton(onClick = { due = "" }) { Text("×") }
                            }
                            ExposedDropdownMenuBox(
                                expanded = priorityMenu,
                                onExpandedChange = { priorityMenu = !priorityMenu }
                            ) {
                                OutlinedTextField(
                                    value = when (priority) { "high" -> "Высокий"; "low" -> "Низкий"; else -> "Обычный" },
                                    onValueChange = {}, readOnly = true, label = { Text("Приоритет") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityMenu) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                ExposedDropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                                    listOf("normal" to "Обычный", "high" to "Высокий", "low" to "Низкий").forEach { (key, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { priority = key; priorityMenu = false })
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank() || body.isNotBlank()) {
                            vm.save(editingId, editorKind!!, title, body, due.takeIf { it.isNotBlank() }, priority)
                            editorKind = null
                        }
                    }) { Text("Сохранить", color = accent) }
                },
                dismissButton = {
                    Row {
                        if (editingId != null) TextButton(onClick = {
                            entries.firstOrNull { it.id == editingId }?.let(vm::delete)
                            editorKind = null
                        }) { Text("Удалить", color = androidx.compose.ui.graphics.Color(0xFFE58D82)) }
                        TextButton(onClick = { editorKind = null }) { Text("Отмена", color = muted) }
                    }
                }
            )
        }
    }
}

@Composable
private fun DaybookSearchField(value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Поиск по заметкам и задачам…") },
        leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF2A2D28),
            unfocusedContainerColor = androidx.compose.ui.graphics.Color(0xFF2A2D28),
            disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF2A2D28),
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
}

@Composable
private fun SummaryTile(
    title: String, value: String, caption: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(modifier = modifier.clickable(onClick = onClick), color = color, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color(0xFFC5C5BC))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineLarge, color = androidx.compose.ui.graphics.Color(0xFFE8E7E3))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFA7A69F))
        }
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color(0xFFE8E7E3))
        TextButton(onClick = onClick) { Text(action, color = androidx.compose.ui.graphics.Color(0xFFA9BE9B)) }
    }
}

@Composable
private fun DaybookEmpty(title: String, subtitle: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = androidx.compose.ui.graphics.Color(0xFF2A2D28),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFFE2E2DA))
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFA7A69F))
        }
    }
}

@Composable
private fun EntryList(
    items: List<DaybookEntity>,
    vm: DaybookViewModel,
    onEdit: (String, DaybookEntity?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { entry ->
            val noteColors = listOf(
                androidx.compose.ui.graphics.Color(0xFF3A3429),
                androidx.compose.ui.graphics.Color(0xFF303A2D),
                androidx.compose.ui.graphics.Color(0xFF2D3440),
                androidx.compose.ui.graphics.Color(0xFF3A2D2C)
            )
            val cardColor = if (entry.kind == "note") noteColors[kotlin.math.abs(entry.id.hashCode()) % noteColors.size]
                else androidx.compose.ui.graphics.Color(0xFF20221E)
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x30FFFFFF)),
                modifier = Modifier.fillMaxWidth().clickable { onEdit(entry.kind, entry) }
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.kind == "task") {
                        Checkbox(checked = entry.done, onCheckedChange = { vm.toggle(entry) },
                            colors = CheckboxDefaults.colors(checkedColor = androidx.compose.ui.graphics.Color(0xFFA9BE9B)))
                    } else {
                        Text("✦", modifier = Modifier.padding(horizontal = 8.dp), color = androidx.compose.ui.graphics.Color(0xFFC4B18E))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall, color = androidx.compose.ui.graphics.Color(0xFFE8E7E3))
                        if (entry.body.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(entry.body, maxLines = 3, color = androidx.compose.ui.graphics.Color(0xFFB8B8AF),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.kind == "task") {
                            val meta = listOfNotNull(
                                entry.due?.takeIf { it.isNotBlank() }?.let { "Срок: " + it },
                                when (entry.priority) { "high" -> "Высокий приоритет"; "low" -> "Низкий приоритет"; else -> null }
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color(0xFFA9BE9B))
                        }
                    }
                    TextButton(onClick = { vm.delete(entry) }) {
                        Text("⋯", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color(0xFFA7A69F))
                    }
                }
            }
        }
    }
}
