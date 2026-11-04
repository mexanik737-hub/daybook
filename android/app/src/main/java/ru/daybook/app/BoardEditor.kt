package ru.daybook.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    val color: Long = 0xFF7047D8,
    val width: Float = 128f,
    val height: Float = 76f
)

@Serializable
data class BoardLink(
    val from: String,
    val to: String,
    val curved: Boolean = false,
    val style: String = if (curved) "curved" else "straight",
    val color: Long = 0xFFB69CFF,
    val arrowEnd: Boolean = true
)

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
    var selectedLinkIndex by remember { mutableStateOf<Int?>(null) }
    var connectFrom by remember { mutableStateOf<String?>(null) }
    var connectMode by remember { mutableStateOf(false) }
    var panMode by remember { mutableStateOf(false) }
    var addShape by remember { mutableStateOf("rounded") }
    var linkStyle by remember { mutableStateOf("straight") }
    var linkColor by remember { mutableLongStateOf(0xFFB69CFF) }
    var linkArrow by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var editLabel by remember { mutableStateOf<String?>(null) }
    val accent = Color(0xFFB69CFF)
    val muted = Color(0xFFA7A69F)
    val latestDoc by rememberUpdatedState(doc)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestConnectFrom by rememberUpdatedState(connectFrom)
    val latestConnectMode by rememberUpdatedState(connectMode)
    val latestPanMode by rememberUpdatedState(panMode)
    val latestAddShape by rememberUpdatedState(addShape)
    val latestSelectedId by rememberUpdatedState(selectedId)

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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("rounded" to "Блок", "rect" to "Квадрат", "circle" to "Круг", "diamond" to "Ромб", "text" to "Текст").forEach { (shape, label) ->
                FilterChip(selected = addShape == shape && !connectMode && !panMode, onClick = { addShape = shape; connectMode = false; connectFrom = null; panMode = false },
                    label = { Text(label) })
            }
            FilterChip(selected = connectMode, onClick = { connectMode = !connectMode; connectFrom = null; panMode = false },
                label = { Text("Связь") })
            FilterChip(selected = panMode, onClick = { panMode = !panMode; connectMode = false; connectFrom = null; selectedId = null },
                label = { Text("Рука") })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Линия", color = muted, fontSize = 11.sp)
            listOf("straight" to "Прямая", "curved" to "Кривая", "arc" to "Дуга").forEach { (style, label) ->
                FilterChip(selected = linkStyle == style, onClick = { linkStyle = style },
                    label = { Text(label, fontSize = 11.sp) })
            }
            FilterChip(selected = linkArrow, onClick = { linkArrow = !linkArrow },
                label = { Text(if (linkArrow) "Со стрелкой" else "Без стрелки", fontSize = 11.sp) })
            listOf(0xFFB69CFFL, 0xFF63B6E8L, 0xFF70C99AL, 0xFFE6A36AL, 0xFFE58D9BL).forEach { color ->
                Surface(onClick = { linkColor = color }, modifier = Modifier.size(24.dp),
                    shape = RoundedCornerShape(50), color = Color(color),
                    border = if (linkColor == color) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null) {}
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { zoom = (zoom / 1.15f).coerceAtLeast(0.5f) }) { Text("−", color = accent) }
            Text("${(zoom * 100).toInt()}%", color = muted, fontSize = 12.sp)
            TextButton(onClick = { zoom = (zoom * 1.15f).coerceAtMost(2.5f) }) { Text("+", color = accent) }
            TextButton(onClick = { pan = Offset.Zero; zoom = 1f }) { Text("Сброс", color = muted) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF191622), RoundedCornerShape(18.dp))) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        val world = Offset((point.x - latestPan.x) / latestZoom, (point.y - latestPan.y) / latestZoom)
                        val hit = latestDoc.nodes.lastOrNull { abs(world.x - it.x) <= it.width / 2f && abs(world.y - it.y) <= it.height / 2f }
                        when {
                            latestPanMode -> { selectedId = null; selectedLinkIndex = null }
                            latestConnectMode && hit != null -> {
                                val from = latestConnectFrom
                                if (from == null) {
                                    connectFrom = hit.id
                                    selectedId = hit.id
                                    selectedLinkIndex = null
                                } else if (hit.id != from) {
                                    val exists = latestDoc.links.any { (it.from == from && it.to == hit.id) || (it.from == hit.id && it.to == from) }
                                    if (!exists) doc = latestDoc.copy(links = latestDoc.links + BoardLink(from, hit.id, style = linkStyle, color = linkColor, arrowEnd = linkArrow))
                                    connectFrom = null
                                    selectedId = hit.id
                                    selectedLinkIndex = null
                                }
                            }
                            !latestConnectMode && !latestPanMode && hit != null -> { selectedId = hit.id; selectedLinkIndex = null }
                            !latestConnectMode && !latestPanMode -> {
                                val linkHit = latestDoc.links.indices.lastOrNull { index ->
                                    val link = latestDoc.links[index]
                                    val fromNode = latestDoc.nodes.firstOrNull { it.id == link.from }
                                    val toNode = latestDoc.nodes.firstOrNull { it.id == link.to }
                                    fromNode != null && toNode != null && pointNearLink(
                                        point,
                                        Offset(fromNode.x * latestZoom + latestPan.x, fromNode.y * latestZoom + latestPan.y),
                                        Offset(toNode.x * latestZoom + latestPan.x, toNode.y * latestZoom + latestPan.y),
                                        link.style, 14f
                                    )
                                }
                                if (linkHit != null) {
                                    selectedId = null
                                    selectedLinkIndex = linkHit
                                } else {
                                    val node = BoardNode(x = world.x, y = world.y, label = "Новый блок", shape = latestAddShape)
                                    doc = latestDoc.copy(nodes = latestDoc.nodes + node)
                                    selectedId = node.id
                                    selectedLinkIndex = null
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            val nextZoom = (latestZoom * zoomChange).coerceIn(0.35f, 4f)
                            val scale = nextZoom / latestZoom
                            pan = Offset(
                                centroid.x - (centroid.x - latestPan.x) * scale + panChange.x,
                                centroid.y - (centroid.y - latestPan.y) * scale + panChange.y
                            )
                            zoom = nextZoom
                            selectedLinkIndex = null
                        } else if (latestPanMode) {
                            pan += panChange
                        } else if (!latestConnectMode) {
                            val activeId = latestSelectedId ?: run {
                                val world = Offset((centroid.x - latestPan.x) / latestZoom, (centroid.y - latestPan.y) / latestZoom)
                                latestDoc.nodes.lastOrNull { abs(world.x - it.x) <= it.width / 2f && abs(world.y - it.y) <= it.height / 2f }
                                    ?.id?.also { selectedId = it; selectedLinkIndex = null }
                            }
                            if (activeId != null) {
                                doc = latestDoc.copy(nodes = latestDoc.nodes.map { node ->
                                    if (node.id == activeId) node.copy(
                                        x = node.x + panChange.x / latestZoom,
                                        y = node.y + panChange.y / latestZoom
                                    ) else node
                                })
                            }
                        }
                    }
                }
            ) {
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
                    val dx = end.x - start.x
                    val dy = end.y - start.y
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val normal = Offset(-dy / distance, dx / distance)
                    val control = when (link.style) {
                        "curved" -> Offset((start.x + end.x) / 2f + normal.x * distance * 0.22f, (start.y + end.y) / 2f + normal.y * distance * 0.22f)
                        "arc" -> Offset((start.x + end.x) / 2f + normal.x * distance * 0.38f, (start.y + end.y) / 2f + normal.y * distance * 0.38f)
                        else -> null
                    }
                    val lineColor = Color(link.color)
                    val lineWidth = if (doc.links.indexOf(link) == selectedLinkIndex) 4.5f else 2.5f
                    if (control == null) drawLine(lineColor, start, end, lineWidth)
                    else {
                        val curve = Path().apply {
                            moveTo(start.x, start.y)
                            quadraticTo(control.x, control.y, end.x, end.y)
                        }
                        drawPath(curve, lineColor, style = Stroke(width = lineWidth))
                    }
                    if (link.arrowEnd) {
                        val tangent = if (control == null) Offset(dx, dy) else Offset(end.x - control.x, end.y - control.y)
                        val angle = kotlin.math.atan2(tangent.y, tangent.x)
                        val arrow = Path().apply {
                            moveTo(end.x, end.y)
                            lineTo(end.x - 13f * zoom * kotlin.math.cos(angle - 0.45f), end.y - 13f * zoom * kotlin.math.sin(angle - 0.45f))
                            lineTo(end.x - 13f * zoom * kotlin.math.cos(angle + 0.45f), end.y - 13f * zoom * kotlin.math.sin(angle + 0.45f))
                            close()
                        }
                        drawPath(arrow, lineColor)
                    }
                }
                doc.nodes.forEach { node ->
                    val p = screen(node)
                    val w = node.width * zoom
                    val h = node.height * zoom
                    val color = Color(node.color)
                    val topLeft = Offset(p.x - w / 2, p.y - h / 2)
                    when (node.shape) {
                        "circle" -> drawCircle(color, radius = minOf(w, h) / 2, center = p)
                        "diamond" -> {
                            val path = Path().apply { moveTo(p.x, p.y-h/2); lineTo(p.x+w/2,p.y); lineTo(p.x,p.y+h/2); lineTo(p.x-w/2,p.y); close() }
                            drawPath(path, color)
                        }
                        "rect" -> drawRect(color, topLeft, Size(w,h))
                        "text" -> Unit
                        else -> drawRoundRect(color, topLeft, Size(w,h), androidx.compose.ui.geometry.CornerRadius(18f * zoom))
                    }
                    if (node.id == selectedId) drawRoundRect(accent, topLeft, Size(w,h), androidx.compose.ui.geometry.CornerRadius(18f * zoom), style = Stroke(3f))
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = 13f * zoom
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                            if (node.shape == "text") this.color = android.graphics.Color.rgb(230, 224, 255)
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
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = selected.label, onValueChange = { value ->
                        doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(label = value) else it })
                    }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Текст фигуры") })
                    listOf(0xFF7047D8L, 0xFF356C91L, 0xFF477A5BL, 0xFF9A633EL).forEach { color ->
                        Surface(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(color = color) else it }) },
                            modifier = Modifier.size(27.dp), shape = RoundedCornerShape(50), color = Color(color)) {}
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Размер", color = muted, fontSize = 11.sp)
                    TextButton(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(width = (it.width - 16f).coerceAtLeast(64f)) else it }) }) { Text("Ш −", color = accent) }
                    Text("${selected.width.toInt()} px", color = muted, fontSize = 11.sp)
                    TextButton(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(width = (it.width + 16f).coerceAtMost(320f)) else it }) }) { Text("Ш +", color = accent) }
                    TextButton(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(height = (it.height - 12f).coerceAtLeast(44f)) else it }) }) { Text("В −", color = accent) }
                    Text("${selected.height.toInt()} px", color = muted, fontSize = 11.sp)
                    TextButton(onClick = { doc = doc.copy(nodes = doc.nodes.map { if (it.id == selected.id) it.copy(height = (it.height + 12f).coerceAtMost(240f)) else it }) }) { Text("В +", color = accent) }
                }
            }
        }

        val selectedLink = selectedLinkIndex?.let { doc.links.getOrNull(it) }
        if (selectedLink != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Связь", color = accent, fontSize = 12.sp)
                listOf("straight" to "Прямая", "curved" to "Кривая", "arc" to "Дуга").forEach { (style, label) ->
                    FilterChip(selected = selectedLink.style == style,
                        onClick = { doc = doc.copy(links = doc.links.mapIndexed { index, link -> if (index == selectedLinkIndex) link.copy(style = style, curved = style != "straight") else link }) },
                        label = { Text(label, fontSize = 11.sp) })
                }
                FilterChip(selected = selectedLink.arrowEnd,
                    onClick = { doc = doc.copy(links = doc.links.mapIndexed { index, link -> if (index == selectedLinkIndex) link.copy(arrowEnd = !link.arrowEnd) else link }) },
                    label = { Text(if (selectedLink.arrowEnd) "Со стрелкой" else "Без стрелки", fontSize = 11.sp) })
                listOf(0xFFB69CFFL, 0xFF63B6E8L, 0xFF70C99AL, 0xFFE6A36AL, 0xFFE58D9BL).forEach { color ->
                    Surface(onClick = { doc = doc.copy(links = doc.links.mapIndexed { index, link -> if (index == selectedLinkIndex) link.copy(color = color) else link }) },
                        modifier = Modifier.size(24.dp), shape = RoundedCornerShape(50), color = Color(color),
                        border = if (selectedLink.color == color) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null) {}
                }
                TextButton(onClick = { doc = doc.copy(links = doc.links.filterIndexed { index, _ -> index != selectedLinkIndex }); selectedLinkIndex = null }) {
                    Text("Удалить связь", color = Color(0xFFE58D9B))
                }
            }
        }
    }
}

private fun pointNearLink(point: Offset, start: Offset, end: Offset, style: String, tolerance: Float): Boolean {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val normal = Offset(-dy / distance, dx / distance)
    val bend = when (style) { "curved" -> 0.22f; "arc" -> 0.38f; else -> 0f }
    val control = Offset((start.x + end.x) / 2f + normal.x * distance * bend,
        (start.y + end.y) / 2f + normal.y * distance * bend)
    var previous = start
    for (step in 1..24) {
        val t = step / 24f
        val current = if (bend == 0f) Offset(start.x + dx * t, start.y + dy * t) else {
            val inverse = 1f - t
            Offset(inverse * inverse * start.x + 2f * inverse * t * control.x + t * t * end.x,
                inverse * inverse * start.y + 2f * inverse * t * control.y + t * t * end.y)
        }
        val sx = current.x - previous.x
        val sy = current.y - previous.y
        val lengthSquared = sx * sx + sy * sy
        val projection = if (lengthSquared == 0f) 0f else (((point.x - previous.x) * sx + (point.y - previous.y) * sy) / lengthSquared).coerceIn(0f, 1f)
        val px = point.x - (previous.x + projection * sx)
        val py = point.y - (previous.y + projection * sy)
        if (px * px + py * py <= tolerance * tolerance) return true
        previous = current
    }
    return false
}
