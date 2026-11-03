package ru.daybook.app.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class DaybookRepository(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        DaybookDatabase::class.java,
        "daybook.db"
    ).build()
    private val dao = database.daybookDao()

    fun observeAll(): Flow<List<DaybookEntity>> = dao.observeAll()

    suspend fun save(
        id: String? = null,
        kind: String,
        title: String,
        body: String,
        due: String? = null,
        priority: String = "normal"
    ) {
        val existing = id?.let { key -> dao.observeAll().first().firstOrNull { it.id == key } }
        val now = System.currentTimeMillis()
        dao.upsert(DaybookEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            kind = kind,
            title = title.ifBlank { "Без названия" },
            body = body,
            created = existing?.created ?: now,
            updated = now,
            due = due,
            priority = priority,
            done = existing?.done ?: false
        ))
    }

    suspend fun toggleDone(entry: DaybookEntity) {
        dao.upsert(entry.copy(done = !entry.done, updated = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun exportDocument(): DaybookDocument {
        val all = dao.observeAll().first()
        fun DaybookEntity.toEntry() = DaybookEntry(
            id = id, title = title, body = body, created = created,
            updated = updated, due = due, priority = priority, done = done
        )
        return DaybookDocument(
            tasks = all.filter { it.kind == "task" }.map { it.toEntry() },
            notes = all.filter { it.kind == "note" }.map { it.toEntry() }
        )
    }

    suspend fun importDocument(document: DaybookDocument) {
        // Boards are currently Android-local and must survive a notes/tasks Drive import.
        val localBoards = dao.observeAll().first().filter { it.kind == "board" }
        val rows = document.tasks.map { it.toEntity("task") } +
            document.notes.map { it.toEntity("note") } + localBoards
        dao.replaceAll(rows)
    }

    private fun DaybookEntry.toEntity(kind: String) = DaybookEntity(
        id = id, kind = kind, title = title, body = body,
        created = created, updated = updated, due = due,
        priority = priority, done = done ?: false
    )
}
