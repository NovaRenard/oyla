package kz.oyla.server.service

/** One labelled APK picture: [word] in lower case, its [category] key and upper-case [syllables]. */
data class PoolPicture(val key: String, val word: String, val category: String, val syllables: List<String>) {
    val upperWord: String get() = word.uppercase()
}

/**
 * The labelled picture pool every generator draws from. Each key is a drawable shipped inside the
 * Android APK (see `ExerciseAssets.kt`); the table mirrors docs/game-generation-contract.md.
 */
object PicturePool {
    /** Category key → child-facing label, in display order. */
    val Categories: Map<String, String> = linkedMapOf(
        "fruit" to "Фрукты", "vegetable" to "Овощи", "food" to "Еда", "animal" to "Животные",
        "transport" to "Транспорт", "thing" to "Вещи", "nature" to "Природа"
    )

    /** Categories a basket (or a juice bottle) is filled from. */
    val FoodCategories = setOf("fruit", "vegetable", "food")

    val Pictures: List<PoolPicture> = listOf(
        p("exercise_apple", "яблоко", "fruit", "ЯБ-ЛО-КО"),
        p("exercise_banana", "банан", "fruit", "БА-НАН"),
        p("exercise_pear", "груша", "fruit", "ГРУ-ША"),
        p("exercise_grapes", "виноград", "fruit", "ВИ-НО-ГРАД"),
        p("exercise_orange", "апельсин", "fruit", "А-ПЕЛЬ-СИН"),
        p("exercise_lemon", "лимон", "fruit", "ЛИ-МОН"),
        p("exercise_strawberry", "клубника", "fruit", "КЛУБ-НИ-КА"),
        p("exercise_carrot", "морковь", "vegetable", "МОР-КОВЬ"),
        p("exercise_tomato", "помидор", "vegetable", "ПО-МИ-ДОР"),
        p("exercise_onion", "лук", "vegetable", "ЛУК"),
        p("exercise_cucumber", "огурец", "vegetable", "О-ГУ-РЕЦ"),
        p("exercise_potato", "картошка", "vegetable", "КАР-ТОШ-КА"),
        p("exercise_cake", "торт", "food", "ТОРТ"),
        p("exercise_cheese", "сыр", "food", "СЫР"),
        p("exercise_juice", "сок", "food", "СОК"),
        p("exercise_candy", "конфета", "food", "КОН-ФЕ-ТА"),
        p("exercise_bear", "медведь", "animal", "МЕД-ВЕДЬ"),
        p("exercise_cat", "кот", "animal", "КОТ"),
        p("exercise_cow", "корова", "animal", "КО-РО-ВА"),
        p("exercise_crayfish", "рак", "animal", "РАК"),
        p("exercise_dog", "собака", "animal", "СО-БА-КА"),
        p("exercise_duck", "утка", "animal", "УТ-КА"),
        p("exercise_elephant", "слон", "animal", "СЛОН"),
        p("exercise_fish", "рыба", "animal", "РЫ-БА"),
        p("exercise_fox", "лиса", "animal", "ЛИ-СА"),
        p("exercise_frog", "лягушка", "animal", "ЛЯ-ГУШ-КА"),
        p("exercise_mouse", "мышь", "animal", "МЫШЬ"),
        p("exercise_owl", "сова", "animal", "СО-ВА"),
        p("exercise_rabbit", "заяц", "animal", "ЗА-ЯЦ"),
        p("exercise_boat", "лодка", "transport", "ЛОД-КА"),
        p("exercise_bus", "автобус", "transport", "АВ-ТО-БУС"),
        p("exercise_car", "машина", "transport", "МА-ШИ-НА"),
        p("exercise_plane", "самолёт", "transport", "СА-МО-ЛЁТ"),
        p("exercise_rocket", "ракета", "transport", "РА-КЕ-ТА"),
        p("exercise_ball", "мяч", "thing", "МЯЧ"),
        p("exercise_cup", "чашка", "thing", "ЧАШ-КА"),
        p("exercise_drum", "барабан", "thing", "БА-РА-БАН"),
        p("exercise_hat", "шапка", "thing", "ШАП-КА"),
        p("exercise_house", "дом", "thing", "ДОМ"),
        p("exercise_key", "ключ", "thing", "КЛЮЧ"),
        p("exercise_lamp", "лампа", "thing", "ЛАМ-ПА"),
        p("exercise_spoon", "ложка", "thing", "ЛОЖ-КА"),
        p("exercise_umbrella", "зонт", "thing", "ЗОНТ"),
        p("exercise_flower", "цветок", "nature", "ЦВЕ-ТОК"),
        p("exercise_moon", "луна", "nature", "ЛУ-НА"),
        p("exercise_rose", "роза", "nature", "РО-ЗА"),
        p("exercise_sun", "солнце", "nature", "СОЛН-ЦЕ"),
        p("exercise_tree", "дерево", "nature", "ДЕ-РЕ-ВО")
    )

    /** The six pictures added together with the generators (drawn on Android). */
    val NewKeys = setOf("exercise_orange", "exercise_lemon", "exercise_strawberry", "exercise_cucumber", "exercise_potato", "exercise_candy")

    private val byKey: Map<String, PoolPicture> = Pictures.associateBy { it.key }

    fun byKey(key: String?): PoolPicture? = key?.let { byKey[it] }
    fun byWord(word: String?): PoolPicture? = word?.let { value -> Pictures.firstOrNull { it.upperWord == value.trim().uppercase() } }
    fun inCategory(category: String): List<PoolPicture> = Pictures.filter { it.category == category }
    fun categoryLabel(category: String?): String? = category?.let { Categories[it] }
    /** Accepts a category key («fruit») or its label («Фрукты»), case-insensitively. */
    fun categoryKeyFor(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Categories.keys.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?: Categories.entries.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.key
    }

    private fun p(key: String, word: String, category: String, syllables: String) = PoolPicture(key, word, category, syllables.split('-'))
}
