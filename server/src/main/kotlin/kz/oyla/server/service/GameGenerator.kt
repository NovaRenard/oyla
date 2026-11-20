package kz.oyla.server.service

import java.util.UUID
import kotlin.random.Random
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.BasketNeed
import kz.oyla.server.model.ExerciseSnapshotOption
import kz.oyla.server.model.GameExerciseConfig

/** One concrete variant of a generated exercise, ready to be stored in a session snapshot. */
data class GeneratedExercise(
    val options: List<ExerciseSnapshotOption>,
    val correctOptionId: String?,
    val gameConfig: GameExerciseConfig
)

/** The content constraints cannot be satisfied; the message is shown to methodists as is. */
class GenerationException(message: String) : IllegalArgumentException(message)

/**
 * Pure generators `(type, config, random) → GeneratedExercise` for every game type that supports
 * `generate = true` (docs/game-generation-contract.md). Fields already set in [GameExerciseConfig]
 * are constraints; everything else is chosen from [PicturePool] by the level table. Option ids are
 * fresh UUIDs drawn from [Random], so a fixed seed reproduces the same variant.
 */
object GameGenerator {
    /** REMEMBER only: `category = "shape"` draws coloured shapes instead of pool pictures. */
    const val ShapeCategory = "shape"
    const val Vowel = "V"
    const val Consonant = "C"
    const val Hard = "H"
    const val Soft = "S"
    const val Container = "basket"
    const val Bottle = "bottle"
    const val CandyKey = "exercise_candy"

    val Supported: Set<ActivityType> = setOf(
        ActivityType.ODD_ONE, ActivityType.COUNT, ActivityType.MEMORY, ActivityType.SHADOW, ActivityType.REMEMBER, ActivityType.SYLLABLES,
        ActivityType.WORD_BUILD, ActivityType.POP, ActivityType.SORT, ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS, ActivityType.TYPE_WORD,
        ActivityType.PHONETICS, ActivityType.LABEL_SCENE, ActivityType.BASKET, ActivityType.CATEGORY_HOUSE, ActivityType.MISSING, ActivityType.PARKING, ActivityType.ARROWS
    )
    val MathOps = setOf("+", "-", "×", "±")

    fun supports(type: ActivityType): Boolean = type in Supported

    fun generate(type: ActivityType, config: GameExerciseConfig, random: Random): GeneratedExercise {
        val level = (config.level ?: 1).also { if (it !in 1..3) throw GenerationException("Уровень сложности должен быть от 1 до 3") }
        val base = config.copy(generate = true, level = level, imageUrl = null, targetOptionId = null)
        val context = Context(base, level, random)
        return when (type) {
            ActivityType.ODD_ONE -> context.oddOne()
            ActivityType.COUNT -> context.count()
            ActivityType.MEMORY -> context.memory()
            ActivityType.SHADOW -> context.shadow()
            ActivityType.REMEMBER -> context.remember()
            ActivityType.SYLLABLES -> context.syllables()
            ActivityType.WORD_BUILD -> context.wordBuild()
            ActivityType.POP -> context.pop()
            ActivityType.SORT -> context.sort()
            ActivityType.MATH -> context.math()
            ActivityType.PAIRS -> context.pairs()
            ActivityType.PARTS -> context.parts()
            ActivityType.TYPE_WORD -> context.typeWord()
            ActivityType.PHONETICS -> context.phonetics()
            ActivityType.LABEL_SCENE -> context.labelScene()
            ActivityType.BASKET -> context.basket()
            ActivityType.CATEGORY_HOUSE -> context.categoryHouse()
            ActivityType.MISSING -> context.missing()
            ActivityType.PARKING -> context.parking()
            ActivityType.ARROWS -> context.arrows()
            else -> throw GenerationException("Для этого вида упражнения генерация недоступна")
        }
    }

    /**
     * Phonetic colour of letter [index] in [word] (upper case): vowels АОУЫЭЕЁИЮЯ are "V"; at level 1 every
     * consonant is "C"; otherwise Ж Ш Ц are always hard "H", Ч Щ Й always soft "S", and the rest are soft
     * when followed by Е Ё И Ю Я Ь.
     */
    fun phoneticClass(word: String, index: Int, level: Int): String {
        val letter = word[index].uppercaseChar()
        if (letter in Vowels) return Vowel
        if (level <= 1) return Consonant
        if (letter in AlwaysHard) return Hard
        if (letter in AlwaysSoft) return Soft
        val next = word.getOrNull(index + 1)?.uppercaseChar()
        return if (next != null && next in Softeners) Soft else Hard
    }

    /** Words the PHONETICS game may use: Cyrillic letters only and no Ь/Ъ. */
    fun isPhoneticWord(word: String): Boolean = word.isNotEmpty() && word.all { it in Alphabet } && word.none { it == 'Ь' || it == 'Ъ' }

    /** Cells reachable from [start] on a [size]×[size] board without crossing [blocked] cells or cutting their corners. */
    fun reachableCells(size: Int, start: Int, blocked: Set<Int>, diagonals: Boolean): Set<Int> {
        val moves = buildList {
            addAll(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
            if (diagonals) addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
        }
        fun free(row: Int, col: Int) = row in 0 until size && col in 0 until size && (row * size + col) !in blocked
        val seen = mutableSetOf(start); val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst(); val row = cell / size; val col = cell % size
            for ((dr, dc) in moves) {
                val r = row + dr; val c = col + dc
                if (!free(r, c)) continue
                if (dr != 0 && dc != 0 && (!free(row + dr, col) || !free(row, col + dc))) continue
                if (seen.add(r * size + c)) queue.addLast(r * size + c)
            }
        }
        return seen
    }

    internal const val Alphabet = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    private const val Vowels = "АОУЫЭЕЁИЮЯ"
    private const val AlwaysHard = "ЖШЦ"
    private const val AlwaysSoft = "ЧЩЙ"
    private const val Softeners = "ЕЁИЮЯЬ"
    /** Distractor letters: common letters a child can tell apart. */
    private const val DistractorLetters = "АБВГДЕЖЗИКЛМНОПРСТУФХЧШЭЮЯ"
    private val PopSounds = listOf("Р", "Л", "С", "Ш", "М", "К")
    private val ColorAdjectives = mapOf(
        "RED" to ("красная" to "красное"), "BLUE" to ("синяя" to "синее"), "YELLOW" to ("жёлтая" to "жёлтое"), "GREEN" to ("зелёная" to "зелёное"),
        "ORANGE" to ("оранжевая" to "оранжевое"), "PURPLE" to ("фиолетовая" to "фиолетовое"), "PINK" to ("розовая" to "розовое")
    )
    /** PARKING spot colour in the neuter form, «красное». */
    fun spotAdjective(color: String): String = ColorAdjectives[color]?.second ?: color
    fun carLabel(color: String): String = "${ColorAdjectives[color]?.first ?: color} машинка"

    private data class Shape(val key: String, val shape: String, val color: String, val label: String)
    private val Shapes: List<Shape> = run {
        val shapes = listOf("star" to ("звезда" to true), "circle" to ("круг" to false), "square" to ("квадрат" to false), "triangle" to ("треугольник" to false))
        val colors = listOf("blue" to ("синий" to "синяя"), "red" to ("красный" to "красная"), "green" to ("зелёный" to "зелёная"),
            "yellow" to ("жёлтый" to "жёлтая"), "purple" to ("фиолетовый" to "фиолетовая"))
        shapes.flatMap { (shape, noun) -> colors.map { (color, adjective) ->
            Shape("shape_${shape}_$color", shape, color, "${if (noun.second) adjective.second else adjective.first} ${noun.first}")
        } }
    }

    /** Draft option before ids and positions are assigned. */
    private data class Draft(
        val label: String? = null, val key: String? = null, val groupKey: String? = null, val sequenceIndex: Int? = null,
        val isTarget: Boolean? = null, val cellIndex: Int? = null, val correct: Boolean = false
    )

    private class Context(val config: GameExerciseConfig, val level: Int, val random: Random) {
        fun pick(level1: Int, level2: Int, level3: Int) = when (level) { 1 -> level1; 2 -> level2; else -> level3 }

        fun uuid(): String {
            val msb = (random.nextLong() and -0xF001L) or 0x4000L // version 4
            val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE // IETF variant
            return UUID(msb, lsb).toString()
        }

        /** Assigns fresh ids and positions 1..n in the given order. */
        fun finish(drafts: List<Draft>, gameConfig: GameExerciseConfig, choiceLike: Boolean = false): GeneratedExercise {
            val options = drafts.mapIndexed { index, draft ->
                ExerciseSnapshotOption(uuid(), draft.label, null, null, draft.key, index + 1, draft.groupKey, draft.sequenceIndex, draft.isTarget, draft.cellIndex)
            }
            val correct = if (choiceLike) options[drafts.indexOfFirst { it.correct }].id else null
            return GeneratedExercise(options, correct, gameConfig)
        }

        fun picture(p: PoolPicture, correct: Boolean = false, isTarget: Boolean? = null, cellIndex: Int? = null, groupKey: String? = null) =
            Draft(label = p.word, key = p.key, correct = correct, isTarget = isTarget, cellIndex = cellIndex, groupKey = groupKey)

        fun category(minSize: Int = 1): String {
            config.category?.let { key ->
                if (key !in PicturePool.Categories) throw GenerationException("Неизвестная категория картинок")
                return key
            }
            val eligible = PicturePool.Categories.keys.filter { PicturePool.inCategory(it).size >= minSize }
            if (eligible.isEmpty()) throw GenerationException("Нет категории с $minSize картинками")
            return eligible.random(random)
        }

        /** Pool pictures limited by the content's category constraint (if any). */
        fun pool(): List<PoolPicture> = config.category?.let { PicturePool.inCategory(category()) } ?: PicturePool.Pictures

        fun withCategory(category: String) = config.copy(category = category, categoryLabel = PicturePool.categoryLabel(category))

        fun requireEnough(actual: Int, needed: Int) {
            if (actual < needed) throw GenerationException("Для этих условий не хватает картинок")
        }

        /** [count] distinct numbers including [answer] within [min]..[max]: up to two [preferred] ones, then near ones; sorted ascending. */
        fun numbers(answer: Int, count: Int, min: Int, max: Int, preferred: List<Int> = emptyList()): List<Int> {
            val good = preferred.filter { it in min..max && it != answer }.distinct().shuffled(random).take(2)
            val near = (min..max).filter { it != answer && it !in good }.sortedBy { kotlin.math.abs(it - answer) + random.nextDouble() * 2.5 }
            val values = (listOf(answer) + good + near).take(count)
            if (values.size < count) throw GenerationException("Не удалось подобрать варианты ответа")
            return values.sorted()
        }

        fun numberOptions(answer: Int, values: List<Int>, gameConfig: GameExerciseConfig) =
            finish(values.map { Draft(label = it.toString(), correct = it == answer) }, gameConfig, choiceLike = true)

        // ---------- existing types ----------

        fun oddOne(): GeneratedExercise {
            val total = pick(4, 5, 6)
            val main = category(total - 1)
            val group = PicturePool.inCategory(main).shuffled(random).take(total - 1)
            requireEnough(group.size, 2)
            val odd = PicturePool.Pictures.filter { it.category != main }.random(random)
            val drafts = (group.map { picture(it) } + picture(odd, correct = true)).shuffled(random)
            return finish(drafts, withCategory(main), choiceLike = true)
        }

        fun count(): GeneratedExercise {
            val answer = config.countValue?.also { if (it !in 1..10) throw GenerationException("Количество предметов должно быть от 1 до 10") }
                ?: random.nextInt(1, pick(5, 8, 10) + 1)
            val hasPicture = !config.imageAssetKey.isNullOrBlank() || config.imageAssetId != null
            val key = if (hasPicture) config.imageAssetKey else pool().random(random).key
            val values = numbers(answer, pick(3, 4, 4), 1, 10)
            return numberOptions(answer, values, config.copy(countValue = answer, imageAssetKey = key))
        }

        fun memory(): GeneratedExercise {
            val pictures = pool().shuffled(random).take(pick(3, 4, 6))
            requireEnough(pictures.size, 2)
            return finish(pictures.map { picture(it) }, config)
        }

        fun shadow(): GeneratedExercise {
            val size = pick(3, 4, 4)
            val source = when {
                config.category != null -> pool()
                level == 3 -> PicturePool.inCategory(category(4))
                else -> PicturePool.Pictures
            }
            val pictures = source.shuffled(random).take(size)
            requireEnough(pictures.size, 2)
            val category = pictures.map { it.category }.distinct().singleOrNull()
            return finish(pictures.map { picture(it) }, if (category != null && (config.category != null || level == 3)) withCategory(category) else config)
        }

        fun remember(): GeneratedExercise {
            val size = pick(4, 6, 9)
            if (config.category == ShapeCategory) {
                val target = Shapes.random(random)
                val similar = Shapes.filter { it != target && (it.shape == target.shape || it.color == target.color) }.shuffled(random)
                val others = Shapes.filter { it != target && it !in similar }.shuffled(random)
                val distractors = (similar + others).take(size - 1)
                val drafts = (distractors.map { Draft(label = it.label, key = it.key) } + Draft(label = target.label, key = target.key, correct = true)).shuffled(random)
                return finish(drafts, config, choiceLike = true)
            }
            val pictures = pool().shuffled(random).take(size)
            requireEnough(pictures.size, 3)
            val drafts = pictures.mapIndexed { index, p -> picture(p, correct = index == 0) }.shuffled(random)
            return finish(drafts, config, choiceLike = true)
        }

        fun syllables(): GeneratedExercise {
            if (config.syllables.isNotEmpty()) return GeneratedExercise(emptyList(), null, config)
            val fixed = PicturePool.byKey(config.imageAssetKey)
            if (fixed == null && (config.imageAssetKey != null || config.imageAssetId != null)) throw GenerationException("Для своей картинки укажите слоги")
            val range = when (level) { 1 -> 1..2; 2 -> 2..3; else -> 3..6 }
            val word = fixed ?: PicturePool.Pictures.filter { it.syllables.size in range }.random(random)
            return GeneratedExercise(emptyList(), null, config.copy(syllables = word.syllables, imageAssetKey = word.key))
        }

        fun wordBuild(): GeneratedExercise {
            val fixed = PicturePool.byKey(config.imageAssetKey)
            if (fixed == null && (config.imageAssetKey != null || config.imageAssetId != null)) throw GenerationException("Для генерации выберите картинку из набора Oyla")
            val range = when (level) { 1 -> 3..3; 2 -> 4..5; else -> 6..6 }
            val picture = fixed ?: PicturePool.Pictures.filter { it.word.length in range }.random(random)
            val word = picture.upperWord
            val distractors = DistractorLetters.filter { it !in word }.toList().shuffled(random).take(2)
            val drafts = word.mapIndexed { index, letter -> Draft(label = letter.toString(), sequenceIndex = index + 1) } +
                distractors.map { Draft(label = it.toString()) }
            return finish(drafts.shuffled(random), config.copy(imageAssetKey = picture.key))
        }

        fun pop(): GeneratedExercise {
            val total = pick(6, 8, 9)
            val wantedTargets = pick(3, 4, 4)
            fun split(sound: String) = PicturePool.Pictures.partition { it.upperWord.contains(sound) }
            val sound = config.sound?.trim()?.uppercase()?.also {
                if (it.length != 1 || it[0] !in Alphabet) throw GenerationException("Звук должен быть одной русской буквой")
            } ?: PopSounds.filter { split(it).let { (t, o) -> t.size >= wantedTargets && o.size >= total - wantedTargets } }.random(random)
            val (targets, others) = split(sound)
            val chosenTargets = targets.shuffled(random).take(wantedTargets)
            val chosenOthers = others.shuffled(random).take(total - chosenTargets.size)
            if (chosenTargets.isEmpty()) throw GenerationException("Нет картинок со звуком $sound")
            requireEnough(chosenTargets.size + chosenOthers.size, 3)
            if (chosenOthers.isEmpty()) throw GenerationException("Нужны картинки без звука $sound")
            val drafts = (chosenTargets.map { picture(it, isTarget = true) } + chosenOthers.map { picture(it, isTarget = false) }).shuffled(random)
            return finish(drafts, config.copy(sound = sound))
        }

        fun sort(): GeneratedExercise {
            val perGroup = pick(2, 3, 4)
            var labels = config.groupLabels.map { it.trim() }
            val groups: Pair<List<PoolPicture>, List<PoolPicture>>
            if (labels.isEmpty()) {
                val (a, b) = PicturePool.Categories.keys.toList().shuffled(random).take(2)
                labels = listOf(PicturePool.Categories.getValue(a), PicturePool.Categories.getValue(b))
                groups = PicturePool.inCategory(a) to PicturePool.inCategory(b)
            } else if (labels.size == 2 && labels.all { it.length == 1 && it.uppercase()[0] in Alphabet }) {
                val (a, b) = labels.map { it.uppercase() }
                if (a == b) throw GenerationException("Звуки групп должны различаться")
                groups = PicturePool.Pictures.filter { it.upperWord.contains(a) && !it.upperWord.contains(b) } to
                    PicturePool.Pictures.filter { it.upperWord.contains(b) && !it.upperWord.contains(a) }
            } else {
                val keys = labels.map { PicturePool.categoryKeyFor(it) }
                if (labels.size != 2 || keys.any { it == null } || keys[0] == keys[1]) throw GenerationException("Подписи групп должны быть двумя буквами или двумя разными категориями")
                groups = PicturePool.inCategory(keys[0]!!) to PicturePool.inCategory(keys[1]!!)
            }
            val a = groups.first.shuffled(random).take(perGroup); val b = groups.second.shuffled(random).take(perGroup)
            if (a.isEmpty() || b.isEmpty()) throw GenerationException("Для одной из групп нет картинок")
            val drafts = (a.map { picture(it, groupKey = "A") } + b.map { picture(it, groupKey = "B") }).shuffled(random)
            return finish(drafts, config.copy(groupLabels = labels))
        }

        // ---------- new types ----------

        fun math(): GeneratedExercise {
            val requested = config.mathOp?.trim()?.ifEmpty { null } ?: "+"
            if (requested !in MathOps) throw GenerationException("Действие должно быть +, -, × или ±")
            val op = if (requested == "±") listOf("+", "-").random(random) else requested
            val (a, b) = if (config.operandA != null && config.operandB != null) config.operandA to config.operandB else when (op) {
                "+" -> { val sum = random.nextInt(2, pick(5, 10, 20) + 1); val x = random.nextInt(1, sum); x to sum - x }
                "-" -> { val x = random.nextInt(2, pick(5, 10, 20) + 1); x to random.nextInt(1, x) }
                else -> random.nextInt(2, pick(3, 5, 9) + 1) to random.nextInt(2, pick(3, 5, 5) + 1)
            }
            if (a < 0 || b < 0 || a > 20 || b > 20) throw GenerationException("Числа примера должны быть от 0 до 20")
            val answer = when (op) { "+" -> a + b; "-" -> a - b; else -> a * b }
            if (answer < 0) throw GenerationException("Ответ не может быть отрицательным")
            val hasPicture = !config.imageAssetKey.isNullOrBlank() || config.imageAssetId != null
            val key = if (hasPicture) config.imageAssetKey else PicturePool.Pictures.random(random).key
            val preferred = if (op == "×") listOf(a + b, answer + a, answer - b) else listOf(answer + 1, answer - 1)
            val values = numbers(answer, 4, if (op == "-") 0 else 1, maxOf(answer + 4, 6), preferred)
            return numberOptions(answer, values, config.copy(mathOp = op, operandA = a, operandB = b, imageAssetKey = key))
        }

        fun pairs(): GeneratedExercise {
            val range = when (level) { 1 -> 2..3; 2 -> 2..4; else -> 3..5 }
            val a = config.operandA ?: range.random(random)
            val b = config.operandB ?: range.random(random)
            if (a !in 1..6 || b !in 1..6) throw GenerationException("Стаканов и тарелок должно быть от 1 до 6")
            val answer = a * b
            val values = numbers(answer, 4, 1, answer + 6, listOf(a + b, answer + 1, answer - 1, maxOf(a, b)))
            return numberOptions(answer, values, config.copy(operandA = a, operandB = b))
        }

        fun parts(): GeneratedExercise {
            val fixed = PicturePool.byKey(config.imageAssetKey)
            if (fixed == null && config.imageAssetKey != null) throw GenerationException("Выберите картинку из набора Oyla")
            val target = fixed ?: when {
                config.category != null -> pool()
                level == 3 -> PicturePool.inCategory(category(4))
                else -> PicturePool.Pictures
            }.random(random)
            val others = PicturePool.Pictures.filter { it.key != target.key }
            val distractors = when (level) {
                1 -> others.filter { it.category != target.category }.shuffled(random).distinctBy { it.category }.take(3)
                2 -> others.shuffled(random).take(3)
                else -> others.filter { it.category == target.category }.shuffled(random).take(3)
            }
            requireEnough(distractors.size, 3)
            val drafts = (distractors.map { picture(it) } + picture(target, correct = true)).shuffled(random)
            return finish(drafts, config.copy(imageAssetKey = target.key), choiceLike = true)
        }

        fun typeWord(): GeneratedExercise {
            val (word, key) = chosenWord(when (level) { 1 -> 3..3; 2 -> 4..5; else -> 6..12 }) { true }
            val distinct = word.toSet()
            val keys = maxOf(pick(8, 10, 12), distinct.size)
            val letters = distinct + DistractorLetters.filter { it !in distinct }.toList().shuffled(random).take(keys - distinct.size)
            val drafts = letters.sortedBy { Alphabet.indexOf(it) }.map { Draft(label = it.toString()) }
            return finish(drafts, config.copy(word = word, imageAssetKey = key))
        }

        fun phonetics(): GeneratedExercise {
            val (word, key) = chosenWord(when (level) { 1 -> 3..4; 2 -> 4..5; else -> 5..7 }) { isPhoneticWord(it.upperWord) }
            if (!isPhoneticWord(word)) throw GenerationException("Для раскраски звуков нужно слово без Ь и Ъ")
            val palette = if (level == 1) listOf(Vowel, Consonant) else listOf(Vowel, Hard, Soft)
            val drafts = word.indices.map { index -> Draft(label = word[index].toString(), groupKey = phoneticClass(word, index, level)) }
            return finish(drafts, config.copy(word = word, imageAssetKey = key, groupLabels = palette))
        }

        /** TYPE_WORD/PHONETICS word: the content's word, else its pool picture, else a pool word of [lengths]. */
        fun chosenWord(lengths: IntRange, allowed: (PoolPicture) -> Boolean): Pair<String, String?> {
            config.word?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { word ->
                if (word.length !in 2..12 || word.any { it !in Alphabet }) throw GenerationException("Слово должно состоять из 2–12 русских букв")
                val key = config.imageAssetKey ?: PicturePool.byWord(word)?.key
                return word to key
            }
            PicturePool.byKey(config.imageAssetKey)?.let { return it.upperWord to it.key }
            if (config.imageAssetKey != null || config.imageAssetId != null) throw GenerationException("Для своей картинки укажите слово")
            val candidates = PicturePool.Pictures.filter { it.word.length in lengths && allowed(it) }
            if (candidates.isEmpty()) throw GenerationException("Нет подходящих слов")
            return candidates.random(random).let { it.upperWord to it.key }
        }

        fun labelScene(): GeneratedExercise {
            val size = pick(3, 4, 6)
            val pictures = pool().distinctBy { it.word }.shuffled(random).take(size)
            requireEnough(pictures.size, 2)
            val cells = (0..8).shuffled(random)
            return finish(pictures.mapIndexed { index, p -> picture(p, cellIndex = cells[index]) }, config)
        }

        fun basket(): GeneratedExercise {
            val container = config.container?.trim()?.lowercase()?.ifEmpty { null } ?: Container
            if (container !in GameExerciseConfig.Containers) throw GenerationException("Ёмкость должна быть basket или bottle")
            val food = PicturePool.Pictures.filter { if (container == Bottle) it.category == "fruit" else it.category in PicturePool.FoodCategories }
            val requirements = if (config.requirements.isNotEmpty()) {
                config.requirements.map { need ->
                    val p = PicturePool.byKey(need.imageAssetKey) ?: throw GenerationException("Выберите еду из набора Oyla")
                    if (need.count !in 1..3) throw GenerationException("Количество в задании должно быть от 1 до 3")
                    BasketNeed(p.key, p.word, need.count)
                }.also { needs ->
                    if (needs.size !in 1..4 || needs.map { it.imageAssetKey }.distinct().size != needs.size) throw GenerationException("В задании должно быть от 1 до 4 разных видов еды")
                }
            } else {
                val kinds = food.shuffled(random).take(pick(2, 3, 4))
                requireEnough(kinds.size, pick(2, 3, 4))
                val maxCount = pick(2, 3, 3)
                kinds.map { BasketNeed(it.key, it.word, random.nextInt(1, maxCount + 1)) }
            }
            val needed = requirements.map { it.imageAssetKey }.toSet()
            val distractorKinds = food.filter { it.key !in needed }.shuffled(random).take(when (level) { 1 -> 1; 2 -> 2; else -> random.nextInt(3, 5) })
            val surplus = if (level < 3) listOf(requirements.random(random).imageAssetKey) else emptyList()
            val drafts = requirements.flatMap { need -> List(need.count) { PicturePool.byKey(need.imageAssetKey)!! } }
                .plus(surplus.map { PicturePool.byKey(it)!! })
                .plus(distractorKinds)
                .map { picture(it) }.shuffled(random)
            return finish(drafts, config.copy(container = container, requirements = requirements))
        }

        fun categoryHouse(): GeneratedExercise {
            val cells = pick(4, 6, 9); val extra = pick(4, 5, 5)
            val main = category(cells)
            val targets = PicturePool.inCategory(main).shuffled(random).take(cells)
            requireEnough(targets.size, 2)
            val others = PicturePool.Pictures.filter { it.category != main }.shuffled(random).take(extra)
            val drafts = (targets.map { picture(it, isTarget = true) } + others.map { picture(it, isTarget = false) }).shuffled(random)
            return finish(drafts, withCategory(main))
        }

        fun missing(): GeneratedExercise {
            val size = pick(5, 6, 8); val gone = pick(1, 2, 3)
            val pictures = pool().shuffled(random).take(size)
            requireEnough(pictures.size, gone + 2)
            val cells = (0..8).shuffled(random)
            val drafts = pictures.mapIndexed { index, p -> picture(p, isTarget = index < gone, cellIndex = cells[index]) }.shuffled(random)
            return finish(drafts, config)
        }

        fun parking(): GeneratedExercise {
            val cars = pick(3, 5, 7); val colorCount = pick(3, 4, 5)
            val colors = GameExerciseConfig.ParkingColors.shuffled(random).take(colorCount)
            val spots = (colors + List(cars - colorCount) { colors.random(random) }).shuffled(random)
            val drafts = spots.shuffled(random).map { Draft(label = carLabel(it), groupKey = it) }
            return finish(drafts, config.copy(groupLabels = spots))
        }

        fun arrows(): GeneratedExercise {
            val size = config.gridSize?.also { if (it !in 4..8) throw GenerationException("Размер поля должен быть от 4 до 8") } ?: pick(4, 5, 6)
            val cellCount = size * size
            val start = config.startCell?.also { if (it !in 0 until cellCount) throw GenerationException("Клетка старта вне поля") } ?: 0
            val fixedStones = config.blockedCells.distinct().also { stones ->
                if (stones.any { it !in 0 until cellCount || it == start }) throw GenerationException("Камни должны лежать на поле и не на старте")
            }
            val candies = pick(1, 2, 3); val stones = pick(0, 2, 4)
            val diagonals = config.diagonals || level >= 2
            fun neighbour(cell: Int) = kotlin.math.abs(cell / size - start / size) <= 1 && kotlin.math.abs(cell % size - start % size) <= 1
            repeat(500) {
                val blocked = (fixedStones.ifEmpty { (0 until cellCount).filter { it != start }.shuffled(random).take(stones) }).toSet()
                val reachable = reachableCells(size, start, blocked, diagonals)
                val free = (0 until cellCount).filter { it != start && it !in blocked && !neighbour(it) && it in reachable }.shuffled(random)
                if (free.size >= candies) {
                    val drafts = free.take(candies).mapIndexed { index, cell -> Draft(label = "Конфета ${index + 1}", key = CandyKey, cellIndex = cell) }
                    return finish(drafts, config.copy(gridSize = size, startCell = start, blockedCells = blocked.sorted(), diagonals = diagonals))
                }
                if (fixedStones.isNotEmpty()) throw GenerationException("Конфетам не хватает свободных клеток")
            }
            throw GenerationException("Не удалось расставить конфеты")
        }
    }
}
