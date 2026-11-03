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
    private val tokenRefreshRunnable = Runnable {
        if (driveConnected) authorizeDrive()
    }
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
            if (result.resultCode == RESULT_OK && result.data != null) {
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
                    driveStatus = if (driveConnected) "Google Drive подключён" else "Не удалось получить токен"
                }.onFailure { error ->
                    driveConnected = false
                    driveStatus = error.message ?: "Ошибка авторизации"
                }
            } else {
                driveConnected = false
                drivePreferences.edit().putBoolean("connected", false).apply()
                driveStatus = "Авторизация отменена"
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
            DaybookApp(vm, driveConnected, driveStatus, driveAccessToken, ::authorizeDrive)
        }
        if (drivePreferences.getBoolean("connected", false)) authorizeDrive()
    }

    private fun authorizeDrive() {
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
                    if (pendingIntent != null) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else {
                        driveStatus = "Google не вернул окно подтверждения"
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
                driveStatus = error.message ?: "Ошибка подключения Google Drive"
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
private fun DaybookApp(vm: DaybookViewModel, driveConnected: Boolean, driveStatus: String, accessToken: String?, onConnectDrive: () -> Unit) {
    val entries by vm.entries.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    LaunchedEffect(accessToken) {
        if (accessToken != null) {
            vm.sync(accessToken)
            while (true) {
                delay(30_000)
                vm.syncNow()
            }
        }
    }
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

    val tasks = entries.filter { it.kind == "task" }
    val notes = entries.filter { it.kind == "note" }
    val query = search.trim().lowercase()
    val filteredTasks = tasks.filter {
        (it.title + " " + it.body).lowercase().contains(query) &&
            when (taskFilter) {
                "active" -> !it.done
                "done" -> it.done
                else -> true
            }
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
        primary = androidx.compose.ui.graphics.Color(0xFFA99BFF),
        background = androidx.compose.ui.graphics.Color(0xFF111318),
        surface = androidx.compose.ui.graphics.Color(0xFF1B1E25)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("daybook") },
                    actions = {
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = onConnectDrive) { Text(if (driveConnected) "Drive ✓ · синхр." else "Подключить Drive") }
                            Text(if (driveConnected) syncStatus else driveStatus,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 12.dp))
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    openEditor(if (section == "tasks") "task" else "note")
                }) { Text("+") }
            },
            bottomBar = {
                NavigationBar {
                    listOf("home" to "Главная", "tasks" to "Задачи", "notes" to "Заметки").forEach { (key, label) ->
                        NavigationBarItem(
                            selected = section == key,
                            onClick = { section = key },
                            icon = { Text(when (key) { "home" -> "⌂"; "tasks" -> "☑"; else -> "▤" }) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (section == "home") {
                    Text("ТВОЙ ДЕНЬ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text("Всё важное —", style = MaterialTheme.typography.headlineMedium)
                    Text("в одном месте.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Записывай мысли, планируй дела и возвращайся к ним когда угодно.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openEditor("task") }, modifier = Modifier.weight(1f)) {
                            Text("+ Задача")
                        }
                        OutlinedButton(onClick = { openEditor("note") }, modifier = Modifier.weight(1f)) {
                            Text("+ Заметка")
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("ТВОИ ЗАДАЧИ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    val homeTasks = tasks.filter { !it.done }.sortedBy { it.due ?: "9999" }.take(4)
                    if (homeTasks.isEmpty()) EmptyMessage("Задач пока нет.")
                    else EntryList(homeTasks, vm, ::openEditor, Modifier.heightIn(max = 180.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("ПОСЛЕДНИЕ ЗАМЕТКИ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    val homeNotes = notes.sortedByDescending { it.updated }.take(3)
                    if (homeNotes.isEmpty()) EmptyMessage("Заметок пока нет.")
                    else EntryList(homeNotes, vm, ::openEditor, Modifier.heightIn(max = 180.dp))
                } else {
                    Text(if (section == "tasks") "Задачи" else "Заметки",
                        style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Поиск заметок и задач…") },
                        leadingIcon = { Text("⌕") }
                    )
                    if (section == "tasks") {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("all" to "Все", "active" to "Активные", "done" to "Выполненные").forEach { (key, label) ->
                                FilterChip(
                                    selected = taskFilter == key,
                                    onClick = { taskFilter = key },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (filteredTasks.isEmpty()) EmptyMessage("Ничего не найдено")
                        else EntryList(filteredTasks, vm, ::openEditor, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text("${filteredNotes.size} заметок", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        if (filteredNotes.isEmpty()) EmptyMessage("Ничего не найдено")
                        else EntryList(filteredNotes, vm, ::openEditor, Modifier.weight(1f))
                    }
                }
            }
        }

        if (editorKind != null) {
            AlertDialog(
                onDismissRequest = { editorKind = null },
                title = { Text(if (editingId == null) {
                    if (editorKind == "task") "Новая задача" else "Новая заметка"
                } else {
                    if (editorKind == "task") "Редактировать задачу" else "Редактировать заметку"
                }) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it },
                            label = { Text("Название") }, singleLine = true)
                        OutlinedTextField(value = body, onValueChange = { body = it },
                            label = { Text("Текст") }, minLines = 3)
                        if (editorKind == "task") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = due, onValueChange = {}, readOnly = true,
                                    label = { Text("Срок выполнения") },
                                    placeholder = { Text("Не задан") },
                                    modifier = Modifier.weight(1f), singleLine = true
                                )
                                TextButton(onClick = {
                                    val calendar = Calendar.getInstance()
                                    val parts = due.split("-")
                                    if (parts.size == 3) {
                                        runCatching {
                                            calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                        }
                                    }
                                    DatePickerDialog(
                                        context,
                                        { _, year, month, day ->
                                            due = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }) { Text("Выбрать") }
                                if (due.isNotBlank()) {
                                    TextButton(onClick = { due = "" }) { Text("×") }
                                }
                            }
                            ExposedDropdownMenuBox(
                                expanded = priorityMenu,
                                onExpandedChange = { priorityMenu = !priorityMenu }
                            ) {
                                OutlinedTextField(
                                    value = when (priority) { "high" -> "Высокий"; "low" -> "Низкий"; else -> "Обычный" },
                                    onValueChange = {}, readOnly = true, label = { Text("Приоритет") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityMenu) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                                    listOf("normal" to "Обычный", "high" to "Высокий", "low" to "Низкий").forEach { (key, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            priority = key; priorityMenu = false
                                        })
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
                    }) { Text("Сохранить") }
                },
                dismissButton = {
                    Row {
                        if (editingId != null) {
                            TextButton(onClick = {
                                entries.firstOrNull { it.id == editingId }?.let(vm::delete)
                                editorKind = null
                            }) { Text("Удалить") }
                        }
                        TextButton(onClick = { editorKind = null }) { Text("Отмена") }
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(message, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryList(
    items: List<DaybookEntity>,
    vm: DaybookViewModel,
    onEdit: (String, DaybookEntity?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { entry ->
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.kind == "task") {
                        Checkbox(checked = entry.done, onCheckedChange = { vm.toggle(entry) })
                    }
                    Column(Modifier.weight(1f).clickable { onEdit(entry.kind, entry) }) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        if (entry.body.isNotBlank()) Text(entry.body, maxLines = 3,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        if (entry.kind == "task") {
                            val meta = listOfNotNull(
                                entry.due?.takeIf { it.isNotBlank() }?.let { "Срок: $it" },
                                when (entry.priority) { "high" -> "Высокий приоритет"; "low" -> "Низкий приоритет"; else -> null }
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { vm.delete(entry) }) { Text("Удалить") }
                }
            }
        }
    }
}
