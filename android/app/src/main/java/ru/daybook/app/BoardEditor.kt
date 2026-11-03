package ru.daybook.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.abs

@Serializable
data class BoardNode(
    val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val label: String,
    val shape: String = "rounded",
    val color: Long = 0xFF7047D8
)

@Serializable
data class BoardLink(val from: String, val to: String, val curved: Boolean = false)

@Serializable
data class BoardDocument(
    val nodes: List<BoardNode> = emptyList(),
    val links: List<BoardLink> = emptyList()
)

private val boardJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
fun BoardEditor(
    initialTitle: String,
    initialJson: String,
    onClose: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var doc by remember {
        mutableStateOf(runCatching { boardJson.decodeFromString<BoardDocument>(initialJson) }.getOrDefault(BoardDocument()))
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var connectFrom by remember { mutableStateOf<String?>(null) }
    var connectMode by remember { mutableStateOf(false) }
    var panMode by remember { mutableStateOf(false) }
    var addShape by remember { mutableStateOf("rounded") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var editLabel by remember { mutableStateOf<String?>(null) }
    val accent = Color(0xFFB69CFF)
    val muted = Color(0xFFA7A69F)

    Column(Modifier.fillMaxSize().background(Color(0xFF111019)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("Назад", color = muted) }
            Text("Редактор схемы", color = Color(0xFFE8E7E3), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onSave(title.ifBlank { "Новая схема" }, boardJson.encodeToString(doc)) }) { Text("Сохранить", color = accent) }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Название схемы") },
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("rounded" to "Блок", "circle" to "Круг", "diamond" to "Ромб").forEach { (shape, label) ->
                FilterChip(selected = addShape == shape && !connectMode && !panMode, onClick = { addShape = shape; connectMode = false; connectFrom = null; panMode = false },
                    label = { Text(label) })
            }
            FilterChip(selected = connectMode, onClick = { connectMode = !connectMode; connectFrom = null; panMode = false },
                label = { Text("Связь") })
            FilterChip(selected = panMode, onClick = { panMode = !panMode; connectMode = false; connectFrom = null; selectedId = null },
                label = { Text("Рука") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { zoom = (zoom / 1.15f).coerceAtLeast(0.5f) }) { Text("−", color = accent) }
            Text("${(zoom * 100).toInt()}%", color = muted, fontSize = 12.sp)
            TextButton(onClick = { zoom = (zoom * 1.15f).coerceAtMost(2.5f) }) { Text("+", color = accent) }
            TextButton(onClick = { pan = Offset.Zero; zoom = 1f }) { Text("Сброс", color = muted) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF191622), RoundedCornerShape(18.dp))) {
            Canvas(Modifier.fillMaxSize().pointerInput(doc, zoom, pan, connectFrom, connectMode, panMode, addShape) {
                detectDragGestures(
                    onDragStart = { point ->
                        val world = Offset((point.x - pan.x) / zoom, (point.y - pan.y) / zoom)
                        val hit = doc.nodes.lastOrNull { abs(world.x - it.x) <= 64 && abs(world.y - it.y) <= 38 }
                        if (panMode) {
                            selectedId = null
                        } else if (connectMode) {
                            if (hit != null) {
                                val from = connectFrom
                                if (from == null) {
                                    connectFrom = hit.id
                                    selectedId = hit.id
                                } else if (hit.id != from) {
                                    val exists = doc.links.any { (it.from == from && it.to == hit.id) || (it.from == hit.id && it.to == from) }
                                    if (!exists) doc = doc.copy(links = doc.links + BoardLink(from, hit.id))
                                    connectFrom = null
                                    selectedId = hit.id
                                }
                            }
                        } else if (hit != null) {
                            selectedId = hit.id
                        } else {
                            val node = BoardNode(x = world.x, y = world.y, label = "Новый блок", shape = addShape)
                            doc = doc.copy(nodes = doc.nodes + node)
                            selectedId = node.id
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        if (panMode || (connectMode && connectFrom == null)) {
                            pan += delta
                        } else if (!connectMode && selectedId != null) {
                            doc = doc.copy(nodes = doc.nodes.map {
                                if (it.id == selectedId) it.copy(x = it.x + delta.x / zoom, y = it.y + delta.y / zoom) else it
                            })
                        }
                    }
                )
            }) {
                val grid = 28.dp.toPx() * zoom
                var x = (pan.x % grid)
                while (x < size.width) {
                    drawLine(Color(0xFF292438), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += grid
                }
                var y = (pan.y % grid)
                while (y < size.height) {
                    drawLine(Color(0xFF292438), Offset(0f, y), Offset(size.width, y), 1f)
                    y += grid
                }
                fun screen(node: BoardNode) = Offset(node.x * zoom + pan.x, node.y * zoom + pan.y)
                doc.links.forEach { link ->
                    val a = doc.nodes.firstOrNull { it.id == link.from } ?: return@forEach
                    val b = doc.nodes.firstOrNull { it.id == link.to } ?: return@forEach
                    val start = screen(a); val end = screen(b)
                    drawLine(Color(0xFFB69CFF), start, end, 2.5f)
                    val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
                    val arrow = Path().apply {
                        moveTo(end.x, end.y)
                        lineTo(end.x - 13f * zoom * kotlin.math.cos(angle - 0.45f), end.y - 13f * zoom * kotlin.math.sin(angle - 0.45f))
                        lineTo(end.x - 13f * zoom * kotlin.math.cos(angle + 0.45f), end.y - 13f * zoom * kotlin.math.sin(angle + 0.45f))
                        close()
                    }
                    drawPath(arrow, Color(0xFFB69CFF))
                }
                doc.nodes.forEach { node ->
                    val p = screen(node)
                    val w = 128f * zoom; val h = 76f * zoom
                    val color = Color(node.color)
                    val topLeft = Offset(p.x - w / 2, p.y - h / 2)
                    when (node.shape) {
                        "circle" -> drawCircle(color, radius = minOf(w, h) / 2, center = p)
                        "diamond" -> {
                            val path = Path().apply { moveTo(p.x, p.y-h/2); lineTo(p.x+w/2,p.y); lineTo(p.x,p.y+h/2); lineTo(p.x-w/2,p.y); close() }
                            drawPath(path, color)
                        }
                        else -> drawRoundRect(color, topLeft, Size(w,h), androidx.compose.ui.geometry.CornerRadius(18f * zoom))
                    }
                    if (node.id == selectedId) drawRoundRect(accent, topLeft, Size(w,h), androidx.compose.ui.geometry.CornerRadius(18f * zoom), style = Stroke(3f))
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = 13f * zoom
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        drawText(node.label.take(18), p.x, p.y + 5f * zoom, paint)
                    }
                }
            }
            if (doc.nodes.isEmpty()) {
                Text("Нажми на поле, чтобы добавить первый блок", color = muted,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when { panMode -> "Перетаскивай поле, чтобы перемещаться по схеме"; connectMode && connectFrom == null -> "Выбери первую фигуру для связи"; connectMode -> "Теперь выбери вторую фигуру"; else -> "Перетаскивай фигуры. Нажми на пустое место, чтобы добавить новую." },
                color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                selectedId?.let { id -> doc = doc.copy(nodes = doc.nodes.filterNot { it.id == id }, links = doc.links.filterNot { it.from == id || it.to == id }); selectedId = null }
            }) { Text("Удалить", color = Color(0xFFE58D9B)) }
        }
        val selected = doc.nodes.firstOrNull { it.id == selectedId }
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = selected.label, onValueChange = { value ->
                    doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(label = value) else it })
                }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Текст блока") })
                listOf(0xFF7047D8L, 0xFF356C91L, 0xFF477A5BL, 0xFF9A633EL).forEach { color ->
                    Surface(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(color = color) else it }) },
                        modifier = Modifier.size(27.dp), shape = RoundedCornerShape(50), color = Color(color)) {}
                }
            }
        }
    }
}
