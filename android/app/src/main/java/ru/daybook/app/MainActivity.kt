package ru.daybook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.daybook.app.data.DaybookEntity
import ru.daybook.app.data.DaybookRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = DaybookRepository(this)
        setContent {
            val vm: DaybookViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DaybookViewModel(repository) as T
            })
            DaybookApp(vm)
        }
    }
}

class DaybookViewModel(private val repository: DaybookRepository) : ViewModel() {
    val entries = repository.observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    fun save(id: String?, kind: String, title: String, body: String) = viewModelScope.launch {
        repository.save(id = id, kind = kind, title = title, body = body)
    }
    fun toggle(entry: DaybookEntity) = viewModelScope.launch { repository.toggleDone(entry) }
    fun delete(entry: DaybookEntity) = viewModelScope.launch { repository.delete(entry.id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaybookApp(vm: DaybookViewModel) {
    val entries by vm.entries.collectAsState()
    var section by remember { mutableStateOf("home") }
    var editorKind by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val tasks = entries.filter { it.kind == "task" }
    val notes = entries.filter { it.kind == "note" }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("daybook") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    editorKind = if (section == "tasks") "task" else "note"
                    editingId = null
                    title = ""; body = ""
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
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text(
                    when (section) { "home" -> "Всё важное — в одном месте."; "tasks" -> "Задачи"; else -> "Заметки" },
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(16.dp))
                val shown = when (section) {
                    "tasks" -> tasks
                    "notes" -> notes
                    else -> tasks.filter { !it.done }.take(4) + notes.sortedByDescending { it.updated }.take(3)
                }
                if (shown.isEmpty()) {
                    Text("Пока здесь пусто. Нажми +, чтобы добавить запись.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shown, key = { it.id }) { entry ->
                            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (entry.kind == "task") {
                                        Checkbox(checked = entry.done, onCheckedChange = { vm.toggle(entry) })
                                    }
                                    Column(Modifier.weight(1f).clickable {
                                        editorKind = entry.kind
                                        editingId = entry.id
                                        title = entry.title
                                        body = entry.body
                                    }) {
                                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                        if (entry.body.isNotBlank()) Text(entry.body, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (entry.kind == "task" && entry.due != null) Text("Срок: ${entry.due}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { vm.delete(entry) }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (editorKind != null) {
            AlertDialog(
                onDismissRequest = { editorKind = null },
                title = { Text(if (editorKind == "task") "Задача" else "Заметка") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") })
                        OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Текст") }, minLines = 3)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.save(editingId, editorKind!!, title, body)
                        editorKind = null
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { editorKind = null }) { Text("Отмена") } }
            )
        }
    }
}
