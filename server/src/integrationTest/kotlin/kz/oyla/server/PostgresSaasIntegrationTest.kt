package kz.oyla.server

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.ActivateDeviceRequest
import kz.oyla.server.model.dto.CreateActivationCodeRequest
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.model.dto.UpdateDeviceRequest
import kz.oyla.server.model.dto.WhiteboardPointDto
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.GameExerciseConfig
import kz.oyla.server.model.WhiteboardExerciseConfig
import kz.oyla.server.repository.DatabaseContentRepository
import kz.oyla.server.repository.InMemoryContentRepository
import kz.oyla.server.repository.LessonTemplateFilter
import kz.oyla.server.repository.DatabaseExerciseRepository
import kz.oyla.server.repository.DatabaseSaasRepository
import kz.oyla.server.repository.ExerciseAttemptRecord
import kz.oyla.server.service.ContentService
import kz.oyla.server.storage.LocalMediaStorage
import kz.oyla.server.repository.DatabaseWhiteboardRepository
import kz.oyla.server.repository.WhiteboardStrokeInput
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SaasConfig
import kz.oyla.server.service.SaasService
import kz.oyla.server.util.SecretGenerator
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer

/** Real PostgreSQL/Flyway coverage for the production Saas repository. Docker is supplied by Testcontainers. */
class PostgresSaasIntegrationTest {
    private lateinit var flyway: Flyway
    private lateinit var repository: DatabaseSaasRepository
    private lateinit var clock: MutableClock
    private lateinit var service: SaasService

    @Before fun migrateEmptyDatabase() {
        flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").cleanDisabled(false).load()
        flyway.clean()
        flyway.migrate()
        Database.connect(postgres.jdbcUrl, driver = "org.postgresql.Driver", user = postgres.username, password = postgres.password)
        repository = DatabaseSaasRepository()
        clock = MutableClock(Instant.parse("2026-08-06T08:00:00Z"))
        service = SaasService(repository, testConfig, SecretGenerator(pepper = TestPepper), clock)
    }

    @Test fun `Flyway migrates an empty PostgreSQL database and owner constraint is enforced`() = runBlocking {
        assertEquals("SUCCESS", flyway.info().current()?.state?.name)
        val owner = register("Owner constraint", "owner-constraint@example.com")
        val centerId = UUID.fromString(owner.activeCenter!!.id)
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("DELETE FROM center_memberships WHERE center_id = ?").use { it.setObject(1, centerId); it.executeUpdate() }
            try {
                connection.commit()
                throw AssertionError("PostgreSQL allowed a center without an OWNER")
            } catch (_: SQLException) {
                connection.rollback()
            }
        }
    }

    @Test fun `registration persists center owner and only hashes secrets`() = runBlocking {
        val registered = register("Hash Center", "OWNER@EXAMPLE.COM")
        val centerId = UUID.fromString(registered.activeCenter!!.id)
        assertEquals("owner@example.com", registered.user.email)
        assertEquals(1, queryInt("SELECT COUNT(*) FROM center_memberships WHERE center_id = '$centerId' AND role = 'OWNER' AND status = 'ACTIVE'"))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE center_id = '$centerId' AND action = 'CENTER_CREATED'"))
        assertNotEquals(registered.refreshToken, queryText("SELECT token_hash FROM refresh_tokens LIMIT 1"))
        assertEquals(64, queryText("SELECT token_hash FROM refresh_tokens LIMIT 1")!!.length)
        try { register("Duplicate", "owner@example.com"); throw AssertionError("duplicate email was accepted") }
        catch (error: ApiException) { assertEquals("CONFLICT", error.errorCode) }
    }

    @Test fun `SQL repository isolates tenants and hashes activation and device tokens`() = runBlocking {
        val a = register("A", "sql-a@example.com")
        val b = register("B", "sql-b@example.com")
        val code = codeFor(a, "A tablet")
        val activated = activate(code.activationCode, "sql-isolated-uid")
        assertTrue(service.listDevices(UUID.fromString(b.user.id), UUID.fromString(b.activeCenter!!.id), null, null, null).isEmpty())
        assertNotEquals(code.activationCode, queryText("SELECT code_hash FROM device_activation_codes LIMIT 1"))
        assertNotEquals(activated.deviceToken, queryText("SELECT token_hash FROM devices WHERE id = '${activated.deviceId}'"))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE action = 'DEVICE_ACTIVATED'"))
    }

    @Test fun `one code concurrent activation creates one device and remains one time`() = runBlocking {
        val owner = register("Concurrent", "concurrent@example.com")
        val code = codeFor(owner, "Tablet")
        val outcomes = coroutineScope { listOf(
            async { runCatching { activate(code.activationCode, "concurrent-a") } },
            async { runCatching { activate(code.activationCode, "concurrent-b") } }
        ).awaitAll() }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is ApiException })
        assertEquals(1, queryInt("SELECT COUNT(*) FROM devices"))
        assertEquals("USED", queryText("SELECT status FROM device_activation_codes LIMIT 1"))
    }

    @Test fun `active and blocked devices cannot move but unlinked device can reactivate with a new token`() = runBlocking {
        val a = register("A", "move-a@example.com")
        val b = register("B", "move-b@example.com")
        val original = activate(codeFor(a, "A tablet").activationCode, "move-sql-uid")
        val codeB = codeFor(b, "B tablet")
        assertActivationError("DEVICE_ALREADY_ACTIVATED") { activate(codeB.activationCode, "move-sql-uid") }
        assertEquals("PENDING", queryText("SELECT status FROM device_activation_codes WHERE id = '${codeB.id}'"))
        assertEquals(a.centerId(), queryText("SELECT center_id::text FROM devices WHERE id = '${original.deviceId}'"))

        service.updateDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), UpdateDeviceRequest(status = DeviceStatus.BLOCKED), null)
        val blockedCodeB = codeFor(b, "B tablet 2")
        assertActivationError("DEVICE_BLOCKED") { activate(blockedCodeB.activationCode, "move-sql-uid") }
        assertEquals("PENDING", queryText("SELECT status FROM device_activation_codes WHERE id = '${blockedCodeB.id}'"))

        service.updateDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), UpdateDeviceRequest(status = DeviceStatus.ACTIVE), null)
        service.unlinkDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), null)
        val replacement = activate(codeFor(b, "B tablet 3").activationCode, "move-sql-uid")
        assertEquals(original.deviceId, replacement.deviceId)
        assertNull(service.authenticateDeviceToken(original.deviceToken))
        assertTrue(service.authenticateDeviceToken(replacement.deviceToken) != null)
        assertEquals(b.centerId(), queryText("SELECT center_id::text FROM devices WHERE id = '${original.deviceId}'"))
    }

    @Test fun `heartbeat updates last seen and online state`() = runBlocking {
        val owner = register("Heartbeat", "heartbeat-sql@example.com")
        val device = activate(codeFor(owner, "Tablet").activationCode, "heartbeat-sql-uid")
        clock.advanceSeconds(91)
        assertFalse(service.listDevices(UUID.fromString(owner.user.id), UUID.fromString(owner.centerId()), null, null, false).single().isOnline)
        val principal = checkNotNull(service.authenticateDeviceToken(device.deviceToken))
        service.heartbeat(principal, kz.oyla.server.model.dto.DeviceHeartbeatRequest(appVersion = "2.0"))
        assertTrue(service.listDevices(UUID.fromString(owner.user.id), UUID.fromString(owner.centerId()), null, null, true).single().isOnline)
        assertEquals("2.0", queryText("SELECT app_version FROM devices WHERE id = '${device.deviceId}'"))
    }

    @Test fun `WHITEBOARD state and completed strokes survive PostgreSQL revisions`() = runBlocking {
        val sessionId = UUID.randomUUID(); val exerciseId = UUID.randomUUID(); seedWhiteboardSession(sessionId, exerciseId)
        val boards = DatabaseWhiteboardRepository(); val now = Instant.parse("2026-08-09T00:00:00Z")
        boards.ensureState(exerciseId, childDrawingEnabled = true, now)
        coroutineScope {
            listOf(
                async { boards.completeStroke(stroke(exerciseId, DeviceRole.SPECIALIST, "specialist", now)) },
                async { boards.completeStroke(stroke(exerciseId, DeviceRole.CHILD, "child", now.plusSeconds(1))) }
            ).awaitAll()
        }
        val afterStrokes = boards.snapshot(exerciseId)!!
        assertEquals(listOf(1, 2), afterStrokes.strokes.map { it.sequenceNumber })
        assertEquals(2, afterStrokes.strokes.size)

        boards.undoLatest(exerciseId, DeviceRole.SPECIALIST, now.plusSeconds(2))
        boards.clear(exerciseId, now.plusSeconds(3))
        val afterClear = boards.snapshot(exerciseId)!!

        assertTrue(afterClear.strokes.isEmpty())
        assertEquals(1, afterClear.state.clearRevision)
        assertEquals(2, queryInt("SELECT COUNT(*) FROM whiteboard_strokes WHERE session_exercise_id = '$exerciseId'"))
    }

    @Test fun `game content, option columns and attempt secondary values round-trip through PostgreSQL`() = runBlocking {
        val content = DatabaseContentRepository()
        val contentService = ContentService(content, repository, LocalMediaStorage("build/integration-media"), clock)
        val games = contentService.snapshotTemplate(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000501"))
        assertEquals(listOf(ActivityType.MEMORY, ActivityType.REMEMBER, ActivityType.ODD_ONE, ActivityType.SORT, ActivityType.MAZE, ActivityType.WORD_BUILD), games.exercises.map { it.activityType })
        // V14: the SYSTEM games generate a fresh variant per lesson.
        assertEquals(3000, games.exercises[1].gameConfig?.showTargetMs)
        assertEquals(1, games.exercises[1].options.count { it.id == games.exercises[1].correctOptionId }); assertEquals("shape", games.exercises[1].gameConfig?.category)
        assertEquals(listOf("A", "A", "B", "B"), games.exercises[3].options.map { it.groupKey }.sortedBy { it })
        assertEquals(listOf("Р", "Л"), games.exercises[3].gameConfig?.groupLabels)
        assertEquals("wave", games.exercises[4].gameConfig?.mazeLayout)
        assertEquals(listOf(1, 2, 3), games.exercises[5].options.mapNotNull { it.sequenceIndex }.sorted()); assertEquals(5, games.exercises[5].options.size)
        assertEquals("exercise_cat", games.exercises[5].gameConfig?.imageAssetKey)
        assertEquals(5, contentService.snapshotTemplate(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000502")).exercises.size)

        val centerId = UUID.fromString(register("Games", "games@example.com").centerId())
        val exerciseId = UUID.randomUUID(); val now = Instant.parse("2026-08-09T00:00:00Z")
        val letters = listOf("М" to 1, "А" to 2, "М" to 3, "А" to 4, "О" to null, "К" to null, "Т" to null, "Л" to null, "Р" to null, "С" to null)
        val record = ContentExerciseRecord(exerciseId, centerId, ContentOwnership.CENTER, ActivityType.WORD_BUILD, "МАМА", "Собери слово", null, null, ContentStatus.ACTIVE, now, now,
            letters.mapIndexed { index, (label, sequence) -> ContentExerciseOptionRecord(UUID.randomUUID(), exerciseId, label, null, null, index + 1, false, null, sequence) },
            gameConfig = GameExerciseConfig(imageAssetKey = "exercise_mama", imageUrl = "/derived", targetOptionId = "derived"))
        content.insertExercise(record)
        val stored = checkNotNull(content.findExercise(exerciseId))
        assertEquals(letters.map { it.second }, stored.options.map { it.sequenceIndex })
        assertEquals(GameExerciseConfig(imageAssetKey = "exercise_mama"), stored.gameConfig)
        content.updateExercise(stored.copy(activityType = ActivityType.WORD_BUILD, gameConfig = GameExerciseConfig(imageAssetKey = "exercise_cat")))
        assertEquals("exercise_cat", content.findExercise(exerciseId)?.gameConfig?.imageAssetKey)

        val sessionId = UUID.randomUUID(); val sessionExerciseId = UUID.randomUUID(); seedWhiteboardSession(sessionId, sessionExerciseId)
        val attempts = DatabaseExerciseRepository()
        assertTrue(attempts.createAttempt(ExerciseAttemptRecord(UUID.randomUUID(), sessionExerciseId, "item", 1, true, 100, UUID.randomUUID(), now, secondaryValue = "B")))
        assertTrue(attempts.createAttempt(ExerciseAttemptRecord(UUID.randomUUID(), sessionExerciseId, "finish", 2, true, 200, UUID.randomUUID(), now)))
        assertEquals(listOf("B", null), attempts.findAttempts(sessionExerciseId).map { it.secondaryValue })
    }

    @Test fun `V12 games are seeded and syllables, countValue and POP targets round-trip through PostgreSQL`() = runBlocking {
        val content = DatabaseContentRepository()
        val contentService = ContentService(content, repository, LocalMediaStorage("build/integration-media"), clock)
        val funGames = contentService.snapshotTemplate(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000503"))
        assertEquals("Весёлые игры", funGames.name); assertEquals("Шарики, слоги, тени и счёт", funGames.description)
        assertEquals(listOf(ActivityType.POP, ActivityType.SYLLABLES, ActivityType.SHADOW, ActivityType.COUNT, ActivityType.MEMORY, ActivityType.ODD_ONE), funGames.exercises.map { it.activityType })
        assertEquals(listOf("307", "308", "309", "310", "301", "303").map { "00000000-0000-0000-0000-000000000$it" }, funGames.exercises.map { it.sourceExerciseId })
        val pop = funGames.exercises[0]
        assertEquals(6, pop.options.size); assertEquals("Р", pop.gameConfig?.sound)
        pop.options.forEach { assertEquals(it.label!!.uppercase().contains("Р"), it.isTarget) }
        assertEquals(listOf("РА", "КЕ", "ТА"), funGames.exercises[1].gameConfig?.syllables)
        assertEquals("exercise_rocket", funGames.exercises[1].gameConfig?.imageAssetKey)
        assertEquals(3, funGames.exercises[2].options.size)
        val count = funGames.exercises[3]
        assertTrue(count.gameConfig!!.countValue!! in 1..5); assertEquals("exercise_fish", count.gameConfig?.imageAssetKey)
        assertEquals(count.gameConfig?.countValue.toString(), count.options.single { it.id == count.correctOptionId }.label)
        assertTrue(count.options.all { it.localImageAssetKey == null })
        // The original V11 templates are untouched.
        assertEquals(6, contentService.snapshotTemplate(UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000501")).exercises.size)

        val centerId = UUID.fromString(register("More games", "more-games@example.com").centerId())
        val now = Instant.parse("2026-11-20T00:00:00Z")
        val syllablesId = UUID.randomUUID()
        content.insertExercise(ContentExerciseRecord(syllablesId, centerId, ContentOwnership.CENTER, ActivityType.SYLLABLES, "МАМА", "Постучи", null, null, ContentStatus.ACTIVE, now, now,
            gameConfig = GameExerciseConfig(syllables = listOf("МА", "МА"), imageAssetKey = "exercise_mama", imageUrl = "/derived")))
        assertEquals(GameExerciseConfig(syllables = listOf("МА", "МА"), imageAssetKey = "exercise_mama"), content.findExercise(syllablesId)?.gameConfig)
        val countId = UUID.randomUUID()
        content.insertExercise(ContentExerciseRecord(countId, centerId, ContentOwnership.CENTER, ActivityType.COUNT, "Сколько?", "Посчитай", null, null, ContentStatus.ACTIVE, now, now,
            listOf("2" to false, "7" to true).mapIndexed { index, (label, correct) -> ContentExerciseOptionRecord(UUID.randomUUID(), countId, label, null, null, index + 1, correct) },
            gameConfig = GameExerciseConfig(countValue = 7, imageAssetKey = "exercise_fish")))
        val storedCount = checkNotNull(content.findExercise(countId))
        assertEquals(7, storedCount.gameConfig?.countValue); assertEquals(listOf(false, true), storedCount.options.map { it.isCorrect })
        content.updateExercise(storedCount.copy(gameConfig = GameExerciseConfig(countValue = 2, imageAssetKey = "exercise_fish")))
        assertEquals(2, content.findExercise(countId)?.gameConfig?.countValue)
        assertEquals("2", queryText("SELECT game_config->>'countValue' FROM content_exercises WHERE id = '$countId'"))
        assertEquals("[\"МА\", \"МА\"]", queryText("SELECT game_config->'syllables' FROM content_exercises WHERE id = '$syllablesId'"))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM content_exercises WHERE id = '00000000-0000-0000-0000-000000000310' AND game_config->>'generate' = 'true' AND game_config->>'countValue' IS NULL"))

        val sessionId = UUID.randomUUID(); val sessionExerciseId = UUID.randomUUID(); seedWhiteboardSession(sessionId, sessionExerciseId)
        val attempts = DatabaseExerciseRepository()
        val shadow = "00000000-0000-0000-0000-000000000461"
        assertTrue(attempts.createAttempt(ExerciseAttemptRecord(UUID.randomUUID(), sessionExerciseId, shadow, 1, true, 100, UUID.randomUUID(), now, secondaryValue = shadow)))
        assertEquals(listOf(shadow), attempts.findAttempts(sessionExerciseId).map { it.secondaryValue })
    }

    @Test fun `V13 SYSTEM content in PostgreSQL mirrors the in-memory seeds`() = runBlocking {
        val content = DatabaseContentRepository(); val memory = InMemoryContentRepository(); val anyCenter = UUID.randomUUID()
        val dbExercises = content.listExercises(anyCenter).filter { it.ownership == ContentOwnership.SYSTEM }.associateBy { it.id }
        val memExercises = memory.listExercises(anyCenter).filter { it.ownership == ContentOwnership.SYSTEM }
        assertEquals(memExercises.map { it.id }.toSet(), dbExercises.keys)
        memExercises.forEach { expected ->
            val actual = checkNotNull(dbExercises[expected.id])
            // V8 option ids are md5-derived in SQL and random in memory; every later seed has the same deterministic ids.
            val legacy = expected.legacyKey != null
            fun ContentExerciseRecord.normalised() = copy(createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                options = options.map { if (legacy) it.copy(id = UUID(0, 0), exerciseId = id) else it })
            assertEquals("${expected.id} «${expected.title}»", expected.normalised(), actual.normalised())
        }
        val dbTemplates = content.listTemplates(anyCenter, LessonTemplateFilter()).filter { it.ownership == ContentOwnership.SYSTEM }.associateBy { it.id }
        memory.listTemplates(anyCenter, LessonTemplateFilter()).filter { it.ownership == ContentOwnership.SYSTEM }.forEach { expected ->
            val actual = checkNotNull(dbTemplates[expected.id]) { "missing template ${expected.name}" }
            assertEquals(expected.name, actual.name); assertEquals(expected.description, actual.description); assertEquals(ContentStatus.ACTIVE, actual.status)
            assertEquals(expected.items.map { it.exerciseId }, actual.items.map { it.exerciseId })
            // V8 template item ids are random in memory; V11+ items share deterministic ids.
            if (expected.id != UUID.fromString("00000000-0000-0000-0000-000000000201")) assertEquals(expected.items.map { it.id }, actual.items.map { it.id })
        }
        assertEquals(44, queryInt("SELECT COUNT(*) FROM content_exercises WHERE id::text BETWEEN '00000000-0000-0000-0000-000000000601' AND '00000000-0000-0000-0000-000000000699'"))
        assertEquals(7, queryInt("SELECT COUNT(*) FROM whiteboard_exercise_configs WHERE exercise_id::text LIKE '00000000-0000-0000-0000-0000000006%'"))
        assertEquals("sun", queryText("SELECT trace_template FROM whiteboard_exercise_configs WHERE exercise_id = '00000000-0000-0000-0000-000000000638'"))
        val contentService = ContentService(content, repository, LocalMediaStorage("build/integration-media"), clock)
        listOf(901, 902, 903, 904, 905).forEach { n ->
            val snapshot = contentService.snapshotTemplate(anyCenter, UUID.fromString("00000000-0000-0000-0000-000000000%03d".format(n)))
            assertTrue(snapshot.exercises.size in 5..7)
        }
        val motor = contentService.snapshotTemplate(anyCenter, UUID.fromString("00000000-0000-0000-0000-000000000904"))
        assertEquals("sun", motor.exercises.first { it.sourceExerciseId.endsWith("638") }.whiteboardConfig?.traceTemplate)
    }

    @Test fun `V14 generated games are seeded and snapshot fresh variants from PostgreSQL`() = runBlocking {
        val content = DatabaseContentRepository(); val anyCenter = UUID.randomUUID()
        assertEquals(35, queryInt("SELECT COUNT(*) FROM content_exercises WHERE id::text BETWEEN '00000000-0000-0000-0000-000000000701' AND '00000000-0000-0000-0000-000000000799'"))
        assertEquals(69, queryInt("SELECT COUNT(*) FROM content_exercises WHERE ownership = 'SYSTEM' AND game_config->>'generate' = 'true'"))
        assertEquals("Запомни и найди: фигуры", queryText("SELECT title FROM content_exercises WHERE id = '00000000-0000-0000-0000-000000000634'"))
        val arrows = checkNotNull(content.findExercise(UUID.fromString("00000000-0000-0000-0000-000000000793")))
        assertEquals(ActivityType.ARROWS, arrows.activityType); assertEquals(GameExerciseConfig(generate = true, level = 3), arrows.gameConfig)
        val contentService = ContentService(content, repository, LocalMediaStorage("build/integration-media"), clock)
        listOf(906, 907, 908, 909).forEach { n ->
            val id = UUID.fromString("00000000-0000-0000-0000-000000000%03d".format(n))
            val first = contentService.snapshotTemplate(anyCenter, id); val second = contentService.snapshotTemplate(anyCenter, id)
            assertTrue(first.exercises.size in 5..7)
            assertTrue(first.exercises.all { it.gameConfig?.generate == true })
            assertTrue(first.exercises.flatMap { it.options.map { option -> option.id } }.toSet().intersect(second.exercises.flatMap { it.options.map { option -> option.id } }.toSet()).isEmpty())
        }
        // A centre can store a generated new-type exercise with all its new fields.
        val centerId = UUID.fromString(register("Generated", "generated@example.com").centerId())
        val now = Instant.parse("2026-11-20T00:00:00Z"); val basketId = UUID.randomUUID()
        val config = GameExerciseConfig(generate = true, level = 2, container = "bottle", requirements = listOf(kz.oyla.server.model.BasketNeed("exercise_apple", "яблоко", 2)),
            blockedCells = listOf(3), diagonals = true, mathOp = "±", operandA = 1, operandB = 2, word = "КОТ", category = "fruit", categoryLabel = "Фрукты", gridSize = 5, startCell = 0, sound = "Р")
        content.insertExercise(ContentExerciseRecord(basketId, centerId, ContentOwnership.CENTER, ActivityType.BASKET, "Сок", "Положи", null, null, ContentStatus.ACTIVE, now, now, gameConfig = config))
        assertEquals(config, content.findExercise(basketId)?.gameConfig)
    }

    @Test fun `whiteboard trace template round-trips through PostgreSQL and is constrained`() = runBlocking {
        val content = DatabaseContentRepository()
        val centerId = UUID.fromString(register("Boards", "boards@example.com").centerId())
        val now = Instant.parse("2026-11-20T00:00:00Z"); val exerciseId = UUID.randomUUID()
        val config = WhiteboardExerciseConfig(availableColors = WhiteboardColor.entries.toList(), defaultColor = WhiteboardColor.ORANGE, traceTemplate = "flower")
        content.insertExercise(ContentExerciseRecord(exerciseId, centerId, ContentOwnership.CENTER, ActivityType.WHITEBOARD, "Цветок", "Обведи", null, null, ContentStatus.ACTIVE, now, now, whiteboardConfig = config))
        assertEquals(config, content.findExercise(exerciseId)?.whiteboardConfig)
        val stored = checkNotNull(content.findExercise(exerciseId))
        assertTrue(content.updateExercise(stored.copy(whiteboardConfig = config.copy(traceTemplate = null))))
        assertNull(content.findExercise(exerciseId)?.whiteboardConfig?.traceTemplate)
        assertTrue(content.updateExercise(stored.copy(whiteboardConfig = config.copy(traceTemplate = "heart"))))
        assertEquals("heart", queryText("SELECT trace_template FROM whiteboard_exercise_configs WHERE exercise_id = '$exerciseId'"))
        val rejected = runCatching {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { it.executeUpdate("UPDATE whiteboard_exercise_configs SET trace_template = 'dragon' WHERE exercise_id = '$exerciseId'") }
            }
        }.exceptionOrNull()
        assertTrue("CHECK must reject unknown templates, was $rejected", rejected is SQLException)
    }

    private suspend fun register(name: String, email: String) = service.registerCenter(RegisterCenterRequest(name, "Owner", null, email, "Password123"), null)
    private fun kz.oyla.server.model.dto.AuthResponse.centerId(): String = checkNotNull(activeCenter).id
    private suspend fun codeFor(owner: kz.oyla.server.model.dto.AuthResponse, name: String) = service.createActivationCode(
        UUID.fromString(owner.user.id), UUID.fromString(owner.activeCenter!!.id), CreateActivationCodeRequest(name, DeviceRole.CHILD), null
    )
    private suspend fun activate(code: String, uid: String) = service.activateDevice(ActivateDeviceRequest(code, uid, "1.0", "14", "Test"), null)
    private suspend fun assertActivationError(expected: String, action: suspend () -> Unit) {
        try { action(); throw AssertionError("Expected $expected") }
        catch (error: ApiException) { assertEquals(expected, error.errorCode) }
    }
    private fun queryText(sql: String): String? = postgres.createConnection("").use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null } }
    }
    private fun queryInt(sql: String): Int = queryText(sql)!!.toInt()
    private fun seedWhiteboardSession(sessionId: UUID, exerciseId: UUID) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("""INSERT INTO sessions
                (id, connection_code, child_name, status, specialist_device_id, specialist_token, child_device_id, child_token, created_at, expires_at, connected_at)
                VALUES (?, '1234', 'Child', 'READY', 'specialist-device', 'specialist-token', 'child-device', 'child-token', NOW(), NOW() + INTERVAL '1 hour', NOW())""").use { statement ->
                statement.setObject(1, sessionId); statement.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO session_exercises
                (id, session_id, exercise_id, status, created_at, position, is_current, activity_type)
                VALUES (?, ?, 'whiteboard-test', 'RUNNING', NOW(), 1, TRUE, 'WHITEBOARD')""").use { statement ->
                statement.setObject(1, exerciseId); statement.setObject(2, sessionId); statement.executeUpdate()
            }
        }
    }
    private fun stroke(exerciseId: UUID, role: DeviceRole, deviceId: String, now: Instant) = WhiteboardStrokeInput(
        id = UUID.randomUUID(), sessionExerciseId = exerciseId, actorRole = role, actorDeviceId = deviceId,
        tool = WhiteboardTool.PEN, color = WhiteboardColor.BLUE, brushSize = WhiteboardBrushSize.MEDIUM,
        points = listOf(WhiteboardPointDto(.1f, .1f), WhiteboardPointDto(.2f, .2f)), clientEventId = UUID.randomUUID(), createdAt = now
    )

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun instant(): Instant = instant
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
    }

    companion object {
        private const val TestPepper = "test-pepper-with-at-least-thirty-two-characters"
        private val testConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
        @JvmField @ClassRule val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @JvmStatic @AfterClass fun stopContainer() { postgres.stop() }
    }
}
