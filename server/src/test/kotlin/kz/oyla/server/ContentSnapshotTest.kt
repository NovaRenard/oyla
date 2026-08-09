package kz.oyla.server

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseSnapshotOption
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.LessonTemplateItemRecord
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.UserStatus
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardExerciseConfig
import kz.oyla.server.model.dto.CreateExerciseRequest
import kz.oyla.server.model.dto.CreateLessonTemplateRequest
import kz.oyla.server.model.dto.LessonTemplateItemInput
import kz.oyla.server.model.dto.WhiteboardExerciseConfigDto
import kz.oyla.server.repository.InMemoryContentRepository
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.service.AuthorizedSession
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.CenterContext
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.storage.LocalMediaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSnapshotTest {
    @Test fun `center content is not visible through another center id`() = runBlocking {
        val repository = InMemoryContentRepository(); val centerA = UUID.randomUUID(); val centerB = UUID.randomUUID()
        val exercise = centerExercise(centerA, "До изменения")
        repository.insertExercise(exercise)

        assertTrue(repository.findExerciseAccessible(centerA, exercise.id) != null)
        assertNull(repository.findExerciseAccessible(centerB, exercise.id))
        assertTrue(repository.listExercises(centerB).none { it.id == exercise.id })
        assertTrue(repository.listExercises(centerA).any { it.ownership == ContentOwnership.SYSTEM })
    }

    @Test fun `template snapshot survives later exercise edit`() = runBlocking {
        val repository = InMemoryContentRepository(); val center = UUID.randomUUID(); val exercise = centerExercise(center, "Старый заголовок")
        repository.insertExercise(exercise)
        val templateId = UUID.randomUUID()
        repository.insertTemplate(LessonTemplateRecord(templateId, center, ContentOwnership.CENTER, "Шаблон", null, ContentStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH,
            listOf(LessonTemplateItemRecord(UUID.randomUUID(), templateId, exercise.id, 1))))
        val service = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-content"))
        val snapshot = service.snapshotTemplate(center, templateId)
        repository.updateExercise(exercise.copy(title = "Новый заголовок", instructionText = "Новая инструкция"))

        assertEquals("Старый заголовок", snapshot.exercises.single().title)
        assertEquals("Старая инструкция", snapshot.exercises.single().instructionText)
    }

    @Test fun `snapshot plan reports its actual dynamic length`() = runBlocking {
        val repository = InMemoryExerciseRepository(); val sessionId = UUID.randomUUID()
        val rows = (1..3).map { position ->
            val source = UUID.randomUUID().toString(); val correct = "correct-$position"
            val snapshot = ExerciseSnapshot(source, ActivityType.SINGLE_CHOICE, "Упражнение $position", "Инструкция $position", options = listOf(
                ExerciseSnapshotOption(correct, "Верно", position = 1), ExerciseSnapshotOption("wrong-$position", "Нет", position = 2)
            ), correctOptionId = correct)
            SessionExerciseRecord(UUID.randomUUID(), sessionId, source, ExerciseStatus.PENDING, null, null, null, Instant.EPOCH, position, position == 1, snapshot)
        }
        val service = ExerciseService(repository); service.ensureSnapshotPlan(rows)
        val session = SessionRecord(sessionId, "1234", "Ребёнок", SessionStatus.READY, "specialist", "token", "child", "child-token", Instant.EPOCH, Instant.MAX, Instant.EPOCH, null, isManaged = true)
        val state = service.stateFor(AuthorizedSession(session, DeviceRole.SPECIALIST))

        assertEquals(3, state.totalExercises)
        assertEquals("Упражнение 1", state.exercise?.title)
    }

    @Test fun `WHITEBOARD is accepted by templates and its snapshot remains immutable`() = runBlocking {
        val repository = InMemoryContentRepository(); val center = UUID.randomUUID(); val id = UUID.randomUUID()
        val originalConfig = WhiteboardExerciseConfig(
            backgroundAssetId = UUID.randomUUID().toString(), childDrawingInitiallyEnabled = false,
            availableColors = listOf(WhiteboardColor.BLACK, WhiteboardColor.BLUE, WhiteboardColor.GREEN, WhiteboardColor.RED),
            defaultColor = WhiteboardColor.BLUE, defaultBrushSize = WhiteboardBrushSize.THICK, allowEraser = false, allowClear = false
        )
        val exercise = ContentExerciseRecord(id, center, ContentOwnership.CENTER, ActivityType.WHITEBOARD, "Дорожка", "Проведи линию", null, null, ContentStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH, whiteboardConfig = originalConfig)
        repository.insertExercise(exercise)
        val templateId = UUID.randomUUID()
        repository.insertTemplate(LessonTemplateRecord(templateId, center, ContentOwnership.CENTER, "Смешанный", null, ContentStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH,
            listOf(LessonTemplateItemRecord(UUID.randomUUID(), templateId, id, 1))))
        val service = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-whiteboard-snapshot"))
        val snapshot = service.snapshotTemplate(center, templateId).exercises.single()
        repository.updateExercise(exercise.copy(title = "Изменено", whiteboardConfig = originalConfig.copy(defaultColor = WhiteboardColor.RED, backgroundAssetId = null)))

        assertEquals(ActivityType.WHITEBOARD, snapshot.activityType)
        assertEquals("Дорожка", snapshot.title)
        assertEquals(WhiteboardColor.BLUE, snapshot.whiteboardConfig?.defaultColor)
        assertEquals(originalConfig.backgroundAssetId, snapshot.whiteboardConfig?.backgroundAssetId)
    }

    @Test fun `WHITEBOARD validation rejects undersized palettes and foreign backgrounds`() = runBlocking {
        val repository = InMemoryContentRepository(); val center = UUID.randomUUID(); val foreignCenter = UUID.randomUUID()
        val service = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-whiteboard-validation"))
        val foreignAsset = UUID.randomUUID()
        repository.insertMedia(MediaAssetRecord(foreignAsset, foreignCenter, ContentOwnership.CENTER, MediaType.IMAGE, "foreign.png", "foreign.png", "image/png", 1, Instant.EPOCH))

        val smallPalette = runCatching {
            service.createExercise(context(center), whiteboardRequest(WhiteboardExerciseConfigDto(availableColors = listOf(WhiteboardColor.BLACK, WhiteboardColor.BLUE, WhiteboardColor.RED), defaultColor = WhiteboardColor.BLACK)), null)
        }.exceptionOrNull()
        val foreignBackground = runCatching {
            service.createExercise(context(center), whiteboardRequest(WhiteboardExerciseConfigDto(backgroundAssetId = foreignAsset.toString(), availableColors = palette(), defaultColor = WhiteboardColor.BLACK)), null)
        }.exceptionOrNull()

        assertTrue(smallPalette is ApiException); assertEquals("VALIDATION_ERROR", (smallPalette as ApiException).errorCode)
        assertTrue(foreignBackground is ApiException); assertEquals("VALIDATION_ERROR", (foreignBackground as ApiException).errorCode)
    }

    @Test fun `WHITEBOARD exercise can be created and placed in a mixed template`() = runBlocking {
        val repository = InMemoryContentRepository(); val center = UUID.randomUUID()
        val service = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-whiteboard-template"))
        val whiteboard = service.createExercise(context(center), whiteboardRequest(WhiteboardExerciseConfigDto(availableColors = palette(), defaultColor = WhiteboardColor.BLACK)), null)
        val template = service.createTemplate(context(center), CreateLessonTemplateRequest("Смешанный", items = listOf(
            LessonTemplateItemInput("00000000-0000-0000-0000-000000000101"), LessonTemplateItemInput(whiteboard.id)
        )), null)

        assertEquals(listOf(ActivityType.SINGLE_CHOICE, ActivityType.WHITEBOARD), template.items.mapNotNull { it.activityType })
        assertTrue(whiteboard.options.isEmpty())
    }

    private fun centerExercise(centerId: UUID, title: String): ContentExerciseRecord {
        val id = UUID.randomUUID(); val correct = ContentExerciseOptionRecord(UUID.randomUUID(), id, "Верно", null, null, 1, true)
        val wrong = ContentExerciseOptionRecord(UUID.randomUUID(), id, "Нет", null, null, 2, false)
        return ContentExerciseRecord(id, centerId, ContentOwnership.CENTER, ActivityType.SINGLE_CHOICE, title, "Старая инструкция", null, null, ContentStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH, listOf(correct, wrong))
    }

    private fun palette() = listOf(WhiteboardColor.BLACK, WhiteboardColor.BLUE, WhiteboardColor.GREEN, WhiteboardColor.RED)
    private fun whiteboardRequest(config: WhiteboardExerciseConfigDto) = CreateExerciseRequest("Белая доска", "Нарисуй", ActivityType.WHITEBOARD, whiteboardConfig = config)
    private fun context(centerId: UUID): CenterContext {
        val now = Instant.EPOCH; val userId = UUID.randomUUID()
        return CenterContext(
            UserRecord(userId, "owner@example.com", "hash", "Owner", null, UserStatus.ACTIVE, now, now, null),
            CenterRecord(centerId, "Центр", "center-$centerId", CenterStatus.ACTIVE, "Asia/Almaty", now, now),
            CenterMembershipRecord(UUID.randomUUID(), centerId, userId, MembershipRole.OWNER, MembershipStatus.ACTIVE, now, now)
        )
    }
}
