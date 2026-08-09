package kz.oyla.server.repository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.LessonTemplateItemRecord
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/** PostgreSQL content access. Every public lookup receives a centre predicate. */
class DatabaseContentRepository : ContentRepository {
    override suspend fun listExercises(centerId: UUID, filter: ContentExerciseFilter): List<ContentExerciseRecord> = database {
        val clauses = mutableListOf("(ownership = 'SYSTEM' OR center_id = ?)")
        filter.ownership?.let { clauses += "ownership = ?" }
        filter.status?.let { clauses += "status = ?" }
        filter.activityType?.let { clauses += "activity_type = ?" }
        filter.search?.takeIf { it.isNotBlank() }?.let { clauses += "(title ILIKE ? OR instruction_text ILIKE ?)" }
        connection.prepareStatement("SELECT * FROM content_exercises WHERE ${clauses.joinToString(" AND ")} ORDER BY ownership DESC, title, id").use { statement ->
            var i = 1; statement.setObject(i++, centerId)
            filter.ownership?.let { statement.setString(i++, it.name) }; filter.status?.let { statement.setString(i++, it.name) }
            filter.activityType?.let { statement.setString(i++, it.name) }; filter.search?.takeIf { it.isNotBlank() }?.let { value -> statement.setString(i++, "%$value%"); statement.setString(i++, "%$value%") }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toExercise(connection.options(rows.getObject("id", UUID::class.java)))) } }
        }
    }

    override suspend fun findExercise(id: UUID): ContentExerciseRecord? = database {
        connection.prepareStatement("SELECT * FROM content_exercises WHERE id = ?").use { it.setObject(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.toExercise(connection.options(id)) else null } }
    }

    override suspend fun findExerciseAccessible(centerId: UUID, id: UUID): ContentExerciseRecord? = database {
        connection.prepareStatement("SELECT * FROM content_exercises WHERE id = ? AND (ownership = 'SYSTEM' OR center_id = ?)").use { statement ->
            statement.setObject(1, id); statement.setObject(2, centerId); statement.executeQuery().use { rows -> if (rows.next()) rows.toExercise(connection.options(id)) else null }
        }
    }

    override suspend fun insertExercise(record: ContentExerciseRecord): ContentExerciseRecord = database {
        connection.prepareStatement("""INSERT INTO content_exercises
            (id, legacy_key, center_id, ownership, activity_type, title, instruction_text, instruction_audio_asset_id, local_audio_asset_key, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
            statement.setObject(1, record.id); statement.setString(2, record.legacyKey); statement.setObject(3, record.centerId); statement.setString(4, record.ownership.name)
            statement.setString(5, record.activityType.name); statement.setString(6, record.title); statement.setString(7, record.instructionText); statement.setObject(8, record.instructionAudioAssetId)
            statement.setString(9, record.localAudioAssetKey); statement.setString(10, record.status.name); statement.setInstant(11, record.createdAt); statement.setInstant(12, record.updatedAt); statement.executeUpdate()
        }
        connection.insertOptions(record.options); record
    }

    override suspend fun updateExercise(record: ContentExerciseRecord): Boolean = database {
        val changed = connection.prepareStatement("""UPDATE content_exercises SET title=?, instruction_text=?, instruction_audio_asset_id=?, status=?, updated_at=?
            WHERE id=? AND center_id=? AND ownership='CENTER'""").use { statement ->
            statement.setString(1, record.title); statement.setString(2, record.instructionText); statement.setObject(3, record.instructionAudioAssetId); statement.setString(4, record.status.name)
            statement.setInstant(5, record.updatedAt); statement.setObject(6, record.id); statement.setObject(7, record.centerId); statement.executeUpdate() == 1
        }
        if (!changed) return@database false
        connection.prepareStatement("DELETE FROM content_exercise_options WHERE exercise_id = ?").use { it.setObject(1, record.id); it.executeUpdate() }
        connection.insertOptions(record.options)
        true
    }

    override suspend fun templateUsageCount(exerciseId: UUID): Int = count("SELECT COUNT(*) FROM lesson_template_items WHERE exercise_id = ?", exerciseId)
    override suspend fun activeTemplateUsageCount(exerciseId: UUID): Int = count("""SELECT COUNT(*) FROM lesson_template_items i
        JOIN lesson_templates t ON t.id=i.template_id WHERE i.exercise_id=? AND t.status='ACTIVE'""", exerciseId)

    override suspend fun listTemplates(centerId: UUID, filter: LessonTemplateFilter): List<LessonTemplateRecord> = database {
        val clauses = mutableListOf("(ownership = 'SYSTEM' OR center_id = ?)")
        filter.ownership?.let { clauses += "ownership = ?" }; filter.status?.let { clauses += "status = ?" }
        filter.search?.takeIf { it.isNotBlank() }?.let { clauses += "(name ILIKE ? OR description ILIKE ?)" }
        connection.prepareStatement("SELECT * FROM lesson_templates WHERE ${clauses.joinToString(" AND ")} ORDER BY ownership DESC, name, id").use { statement ->
            var i = 1; statement.setObject(i++, centerId); filter.ownership?.let { statement.setString(i++, it.name) }; filter.status?.let { statement.setString(i++, it.name) }
            filter.search?.takeIf { it.isNotBlank() }?.let { value -> statement.setString(i++, "%$value%"); statement.setString(i++, "%$value%") }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toTemplate(connection.templateItems(rows.getObject("id", UUID::class.java)))) } }
        }
    }

    override suspend fun findTemplate(id: UUID): LessonTemplateRecord? = database {
        connection.prepareStatement("SELECT * FROM lesson_templates WHERE id=?").use { it.setObject(1,id); it.executeQuery().use { rows -> if(rows.next()) rows.toTemplate(connection.templateItems(id)) else null } }
    }
    override suspend fun findTemplateAccessible(centerId: UUID, id: UUID): LessonTemplateRecord? = database {
        connection.prepareStatement("SELECT * FROM lesson_templates WHERE id=? AND (ownership='SYSTEM' OR center_id=?)").use { statement ->
            statement.setObject(1,id); statement.setObject(2,centerId); statement.executeQuery().use { rows -> if(rows.next()) rows.toTemplate(connection.templateItems(id)) else null }
        }
    }

    override suspend fun insertTemplate(record: LessonTemplateRecord): LessonTemplateRecord = database {
        connection.prepareStatement("""INSERT INTO lesson_templates (id,center_id,ownership,name,description,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?)""").use { statement ->
            statement.setObject(1,record.id); statement.setObject(2,record.centerId); statement.setString(3,record.ownership.name); statement.setString(4,record.name); statement.setString(5,record.description)
            statement.setString(6,record.status.name); statement.setInstant(7,record.createdAt); statement.setInstant(8,record.updatedAt); statement.executeUpdate()
        }; connection.insertTemplateItems(record.id, record.items.map { it.exerciseId }); record
    }
    override suspend fun updateTemplate(record: LessonTemplateRecord): Boolean = database {
        connection.prepareStatement("UPDATE lesson_templates SET name=?,description=?,status=?,updated_at=? WHERE id=? AND center_id=? AND ownership='CENTER'").use { statement ->
            statement.setString(1,record.name); statement.setString(2,record.description); statement.setString(3,record.status.name); statement.setInstant(4,record.updatedAt); statement.setObject(5,record.id); statement.setObject(6,record.centerId); statement.executeUpdate()==1
        }
    }
    override suspend fun replaceTemplateItems(templateId: UUID, items: List<UUID>): Boolean = database {
        connection.prepareStatement("SELECT 1 FROM lesson_templates WHERE id=? AND ownership='CENTER' FOR UPDATE").use { it.setObject(1,templateId); it.executeQuery().use { rows -> if(!rows.next()) return@database false } }
        connection.prepareStatement("DELETE FROM lesson_template_items WHERE template_id=?").use { it.setObject(1,templateId); it.executeUpdate() }
        connection.insertTemplateItems(templateId,items); true
    }

    override suspend fun insertMedia(asset: MediaAssetRecord): MediaAssetRecord = database {
        connection.prepareStatement("""INSERT INTO media_assets (id,center_id,ownership,type,storage_key,original_filename,mime_type,size_bytes,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)""").use { statement ->
            statement.setObject(1,asset.id); statement.setObject(2,asset.centerId); statement.setString(3,asset.ownership.name); statement.setString(4,asset.type.name); statement.setString(5,asset.storageKey)
            statement.setString(6,asset.originalFilename); statement.setString(7,asset.mimeType); statement.setLong(8,asset.sizeBytes); statement.setInstant(9,asset.createdAt); statement.executeUpdate()
        }; asset
    }
    override suspend fun findMedia(id: UUID): MediaAssetRecord? = database { connection.findMedia("SELECT * FROM media_assets WHERE id=?") { setObject(1,id) } }
    override suspend fun findMediaAccessible(centerId: UUID, id: UUID, type: MediaType?): MediaAssetRecord? = database {
        val sql = "SELECT * FROM media_assets WHERE id=? AND (ownership='SYSTEM' OR center_id=?)" + if(type != null) " AND type=?" else ""
        connection.findMedia(sql) { setObject(1,id); setObject(2,centerId); if(type != null) setString(3,type.name) }
    }
    override suspend fun mediaUsageCount(id: UUID): Int = database {
        connection.prepareStatement("""SELECT (SELECT COUNT(*) FROM content_exercises WHERE instruction_audio_asset_id=?) +
            (SELECT COUNT(*) FROM content_exercise_options WHERE image_asset_id=?)""").use { it.setObject(1,id);it.setObject(2,id);it.executeQuery().use { rows -> rows.next();rows.getInt(1) } }
    }

    private fun Connection.options(exerciseId: UUID): List<ContentExerciseOptionRecord> = prepareStatement("SELECT * FROM content_exercise_options WHERE exercise_id=? ORDER BY sort_order").use { statement ->
        statement.setObject(1,exerciseId); statement.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.toOption()) } }
    }
    private fun Connection.templateItems(templateId: UUID): List<LessonTemplateItemRecord> = prepareStatement("SELECT * FROM lesson_template_items WHERE template_id=? ORDER BY position").use { statement ->
        statement.setObject(1,templateId); statement.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.toTemplateItem()) } }
    }
    private fun Connection.insertOptions(options: List<ContentExerciseOptionRecord>) {
        if(options.isEmpty()) return
        prepareStatement("INSERT INTO content_exercise_options (id,exercise_id,label,image_asset_id,local_image_asset_key,sort_order,is_correct) VALUES (?,?,?,?,?,?,?)").use { statement -> options.forEach { item ->
            statement.setObject(1,item.id);statement.setObject(2,item.exerciseId);statement.setString(3,item.label);statement.setObject(4,item.imageAssetId);statement.setString(5,item.localImageAssetKey);statement.setInt(6,item.sortOrder);statement.setBoolean(7,item.isCorrect);statement.addBatch()
        }; statement.executeBatch() }
    }
    private fun Connection.insertTemplateItems(templateId: UUID, items: List<UUID>) {
        if(items.isEmpty()) return
        prepareStatement("INSERT INTO lesson_template_items (id,template_id,exercise_id,position) VALUES (?,?,?,?)").use { statement -> items.forEachIndexed { index,item ->
            statement.setObject(1,UUID.randomUUID());statement.setObject(2,templateId);statement.setObject(3,item);statement.setInt(4,index+1);statement.addBatch()
        }; statement.executeBatch() }
    }
    private fun Connection.findMedia(sql:String, bind: java.sql.PreparedStatement.()->Unit): MediaAssetRecord? = prepareStatement(sql).use { statement -> statement.bind();statement.executeQuery().use { rows -> if(rows.next()) rows.toMedia() else null } }
    private suspend fun count(sql:String,id:UUID): Int = database { connection.prepareStatement(sql).use { it.setObject(1,id);it.executeQuery().use { rows -> rows.next();rows.getInt(1) } } }
    private fun ResultSet.toExercise(options:List<ContentExerciseOptionRecord>) = ContentExerciseRecord(getObject("id",UUID::class.java),getObject("center_id",UUID::class.java),ContentOwnership.valueOf(getString("ownership")),ActivityType.valueOf(getString("activity_type")),getString("title"),getString("instruction_text"),getObject("instruction_audio_asset_id",UUID::class.java),getString("local_audio_asset_key"),ContentStatus.valueOf(getString("status")),getTimestamp("created_at").toInstant(),getTimestamp("updated_at").toInstant(),options,getString("legacy_key"))
    private fun ResultSet.toOption() = ContentExerciseOptionRecord(getObject("id",UUID::class.java),getObject("exercise_id",UUID::class.java),getString("label"),getObject("image_asset_id",UUID::class.java),getString("local_image_asset_key"),getInt("sort_order"),getBoolean("is_correct"))
    private fun ResultSet.toTemplate(items:List<LessonTemplateItemRecord>) = LessonTemplateRecord(getObject("id",UUID::class.java),getObject("center_id",UUID::class.java),ContentOwnership.valueOf(getString("ownership")),getString("name"),getString("description"),ContentStatus.valueOf(getString("status")),getTimestamp("created_at").toInstant(),getTimestamp("updated_at").toInstant(),items)
    private fun ResultSet.toTemplateItem() = LessonTemplateItemRecord(getObject("id",UUID::class.java),getObject("template_id",UUID::class.java),getObject("exercise_id",UUID::class.java),getInt("position"))
    private fun ResultSet.toMedia() = MediaAssetRecord(getObject("id",UUID::class.java),getObject("center_id",UUID::class.java),ContentOwnership.valueOf(getString("ownership")),MediaType.valueOf(getString("type")),getString("storage_key"),getString("original_filename"),getString("mime_type"),getLong("size_bytes"),getTimestamp("created_at").toInstant())
    private fun java.sql.PreparedStatement.setInstant(index:Int,value:Instant?) { setTimestamp(index,value?.let(java.sql.Timestamp::from)) }
    private val connection: Connection get()=(TransactionManager.current().connection as JdbcConnectionImpl).connection
    private suspend fun <T> database(block:()->T):T=withContext(Dispatchers.IO){ transaction { block() } }
}
