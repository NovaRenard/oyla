# Новые игры и генерация вариантов — контракт сервер ↔ планшет ↔ веб

Идеи — в `docs/exercise-ideas.md`. Этот файл — точный формат данных, по которому реализуются сервер, Android и веб.

## 1. Генерация вместо хардкода

- `GameExerciseConfig` (сервер, хранится в `content_exercises.game_config` JSONB) получает поля `generate: Boolean = false` и `level: Int? = null` (1..3, по умолчанию 1).
- Если `generate == true`, то при создании занятия (`ContentService.snapshotTemplate` → снимок упражнения) сервер **не копирует** варианты из БД, а генерирует новый конкретный вариант: `options`, `correctOptionId`, `gameConfig` (с заполненными полями ниже). Каждое занятие получает свой случайный вариант. ID вариантов — новые UUID-строки.
- Поля, уже заданные в контенте, считаются **ограничениями** генератора: например, `category` у ODD_ONE («Что лишнее: животные») или `imageAssetKey` у COUNT («Сколько рыбок?») — фиксируются, остальное случайно.
- Генератор — чистая функция `(type, config, random) → GeneratedExercise` в `server/.../service/GameGenerator.kt`, тестируется с фиксированным `Random(seed)`.
- Пул картинок — `server/.../service/PicturePool.kt`: ключ APK-картинки, слово (строчными), категория, слоги.

### Пул картинок (ключ → слово, категория, слоги)

Категории: `fruit` «Фрукты», `vegetable` «Овощи», `food` «Еда», `animal` «Животные», `transport` «Транспорт», `thing` «Вещи», `nature` «Природа».

| ключ | слово | кат. | слоги |
|---|---|---|---|
| exercise_apple | яблоко | fruit | ЯБ-ЛО-КО |
| exercise_banana | банан | fruit | БА-НАН |
| exercise_pear | груша | fruit | ГРУ-ША |
| exercise_grapes | виноград | fruit | ВИ-НО-ГРАД |
| exercise_orange **(новая)** | апельсин | fruit | А-ПЕЛЬ-СИН |
| exercise_lemon **(новая)** | лимон | fruit | ЛИ-МОН |
| exercise_strawberry **(новая)** | клубника | fruit | КЛУБ-НИ-КА |
| exercise_carrot | морковь | vegetable | МОР-КОВЬ |
| exercise_tomato | помидор | vegetable | ПО-МИ-ДОР |
| exercise_onion | лук | vegetable | ЛУК |
| exercise_cucumber **(новая)** | огурец | vegetable | О-ГУ-РЕЦ |
| exercise_potato **(новая)** | картошка | vegetable | КАР-ТОШ-КА |
| exercise_cake | торт | food | ТОРТ |
| exercise_cheese | сыр | food | СЫР |
| exercise_juice | сок | food | СОК |
| exercise_candy **(новая)** | конфета | food | КОН-ФЕ-ТА |
| exercise_bear | медведь | animal | МЕД-ВЕДЬ |
| exercise_cat | кот | animal | КОТ |
| exercise_cow | корова | animal | КО-РО-ВА |
| exercise_crayfish | рак | animal | РАК |
| exercise_dog | собака | animal | СО-БА-КА |
| exercise_duck | утка | animal | УТ-КА |
| exercise_elephant | слон | animal | СЛОН |
| exercise_fish | рыба | animal | РЫ-БА |
| exercise_fox | лиса | animal | ЛИ-СА |
| exercise_frog | лягушка | animal | ЛЯ-ГУШ-КА |
| exercise_mouse | мышь | animal | МЫШЬ |
| exercise_owl | сова | animal | СО-ВА |
| exercise_rabbit | заяц | animal | ЗА-ЯЦ |
| exercise_boat | лодка | transport | ЛОД-КА |
| exercise_bus | автобус | transport | АВ-ТО-БУС |
| exercise_car | машина | transport | МА-ШИ-НА |
| exercise_plane | самолёт | transport | СА-МО-ЛЁТ |
| exercise_rocket | ракета | transport | РА-КЕ-ТА |
| exercise_ball | мяч | thing | МЯЧ |
| exercise_cup | чашка | thing | ЧАШ-КА |
| exercise_drum | барабан | thing | БА-РА-БАН |
| exercise_hat | шапка | thing | ШАП-КА |
| exercise_house | дом | thing | ДОМ |
| exercise_key | ключ | thing | КЛЮЧ |
| exercise_lamp | лампа | thing | ЛАМ-ПА |
| exercise_spoon | ложка | thing | ЛОЖ-КА |
| exercise_umbrella | зонт | thing | ЗОНТ |
| exercise_flower | цветок | nature | ЦВЕ-ТОК |
| exercise_moon | луна | nature | ЛУ-НА |
| exercise_rose | роза | nature | РО-ЗА |
| exercise_sun | солнце | nature | СОЛН-ЦЕ |
| exercise_tree | дерево | nature | ДЕ-РЕ-ВО |

Шесть новых картинок рисуются на Android (vector drawable в стиле существующих `exercise_*`) и регистрируются в `ExerciseAssets.kt`.

## 2. Новые поля DTO (сервер `ExerciseDtos.kt`/`ContentDtos.kt` и Android `SessionDtos.kt`, все со значениями по умолчанию)

`GameExerciseConfigDto` (+ серверная `GameExerciseConfig`):

| поле | тип | для чего |
|---|---|---|
| `generate` | Boolean = false | вариант генерируется при старте занятия |
| `level` | Int? | 1..3 сложность |
| `mathOp` | String? | MATH: `+`, `-`, `×`; в контенте ещё `±` (смешанно); в сессии всегда конкретный |
| `operandA`, `operandB` | Int? | MATH: числа примера; PAIRS: сколько стаканов (A) и тарелок (B) |
| `word` | String? | TYPE_WORD, PHONETICS: слово ЗАГЛАВНЫМИ |
| `category` | String? | ключ категории (ODD_ONE, SHADOW, CATEGORY_HOUSE…) |
| `categoryLabel` | String? | подпись категории для ребёнка («Фрукты») |
| `requirements` | List<BasketNeedDto> = [] | BASKET: что положить |
| `container` | String? | BASKET: `basket` или `bottle` |
| `gridSize` | Int? | ARROWS: сторона поля |
| `startCell` | Int? | ARROWS: клетка старта (индекс = row*gridSize+col) |
| `blockedCells` | List<Int> = [] | ARROWS: камни |
| `diagonals` | Boolean = false | ARROWS: доступны диагональные стрелки |
| `sound` | String? | POP/SORT-генерация: звук (буква) |

`BasketNeedDto(imageAssetKey: String, label: String, count: Int)`.

`ExerciseOptionDto` (сессия) и `ExerciseSnapshotOption` получают `cellIndex: Int? = null`. Флаг `isTarget` теперь заполняется не только для POP, но и для CATEGORY_HOUSE и MISSING.

## 3. Новые виды (`ActivityType`)

Ходы ребёнка: «выбор» = `onChoice(optionId)` (одна попытка, choice-like, судится по `correctOptionId`); «ход» = `onMove(selectedOptionId, secondaryValue)` (многошаговый, судится на клиенте мгновенно и шлётся очередью; сервер перепроверяет).

| вид | суть | данные варианта | ход ребёнка | проверка сервера | прогресс (done/total) |
|---|---|---|---|---|---|
| `MATH` | пример на картинках: `A op B = ?` | `mathOp`, `operandA`, `operandB`, `imageAssetKey` (предмет); options = 4 числа (label), один правильный | выбор числа | choice-like | 0/1 → 1/1 |
| `PAIRS` | A стаканов и B тарелок, сколько пар? Ребёнок может соединять пары линиями (только визуально), затем выбирает число | `operandA`, `operandB`; options = 4 числа | выбор числа | choice-like | как выбор |
| `PARTS` | 4 увеличенных фрагмента одной картинки, угадай что это | `imageAssetKey` = загаданная картинка; options = 4 картинки | выбор картинки | choice-like | как выбор |
| `TYPE_WORD` | картинка + клетки, крупная экранная клавиатура | `word`, `imageAssetKey`; options = клавиши (label = буква, уникальные) | ход `(keyId, null)` | `key.label == word[done]`; повторное нажатие той же клавиши допустимо (буквы могут повторяться) | правильных нажатий / длина слова |
| `PHONETICS` | раскрась звуки: гласный — красный, твёрдый — синий, мягкий — зелёный | `word`; `groupLabels` = палитра: L1 `["V","C"]`, L2+ `["V","H","S"]`; options = буквы по порядку (label, groupKey) | ход `(letterId, colorKey)` | `colorKey ∈ groupLabels`, `letter.groupKey == colorKey`, буква ещё не раскрашена | раскрашено / букв |
| `LABEL_SCENE` | сцена 3×3 с предметами, снизу слова; перетащи слово к предмету | options = предметы: `localImageAssetKey`, `label` = слово, `cellIndex` 0..8 | ход `(wordOptionId, objectOptionId)` | равны и ещё не подписан | подписано / предметов |
| `BASKET` | корзинка (или бутылка), вокруг еда; сверху задание «2 банана, 1 яблоко» | `requirements`, `container`; options = экземпляры еды (`localImageAssetKey`, label) | ход `(itemId, null)` = положил в корзину | предмет не положен, его `localImageAssetKey` есть в `requirements`, и такого вида положено меньше, чем нужно | положено нужного / сумма count |
| `CATEGORY_HOUSE` | домик: на крыше категория, внутри N ячеек; перетащи только предметы категории | `category`, `categoryLabel`; options = предметы, `isTarget` = из категории | ход `(itemId, null)` | как POP: `isTarget == true` и ещё не положен | положено / целей |
| `MISSING` | две сцены: на второй пропало несколько предметов; выбери снизу всё пропавшее | options = предметы первой сцены, `cellIndex` = место, `isTarget` = пропал | ход `(optionId, null)` | как POP | найдено / пропавших |
| `PARKING` | парковка с цветными местами и машинки; поставь каждую на место своего цвета | `groupLabels` = цвета мест по порядку; options = машинки, `groupKey` = цвет. Цвета: `RED BLUE YELLOW GREEN ORANGE PURPLE PINK` | ход `(carId, colorKey)` | `colorKey ∈ groupLabels`, `car.groupKey == colorKey`, машинка ещё не поставлена | поставлено / машинок |
| `ARROWS` | поле-сетка, Ойлик на старте, конфеты; расставь стрелки и нажми «Поехали» | `gridSize`, `startCell`, `blockedCells`, `diagonals`; options = конфеты по порядку (`position`), `cellIndex` | ход `(candyId, null)` когда Ойлик дошёл до очередной конфеты, `("CRASH", null)` если врезался/ушёл за поле/зациклился | candyId = первая несобранная по `position` → верно; `CRASH` → неверно | собрано / конфет |

`labelFor` (журнал специалиста): TYPE_WORD → буква; PHONETICS → «Б → синий»; LABEL_SCENE → «слово → предмет»; PARKING → «машинка → красное место»; ARROWS → «Конфета 1» / «Врезался»; остальные — label варианта.

### Уровни генераторов

| вид | L1 | L2 | L3 |
|---|---|---|---|
| MATH `+` | суммы до 5 | до 10 | до 20 |
| MATH `-` | от чисел до 5 | до 10 | до 20 |
| MATH `×` | 2..3 × 2..3 | 2..5 × 2..5 | 2..9 × 2..5 |
| PAIRS | A,B ∈ 2..3 | 2..4 | 3..5 |
| PARTS | варианты из разных категорий | смешанно | из той же категории |
| TYPE_WORD | 3 буквы, 8 клавиш | 4–5 букв, 10 клавиш | 6+ букв, 12 клавиш |
| PHONETICS | 3–4 буквы, 2 цвета | 4–5 букв, 3 цвета | 5–7 букв, 3 цвета (без слов с Ь/Ъ) |
| LABEL_SCENE | 3 предмета | 4 | 6 |
| BASKET | 2 вида, по 1–2 | 3 вида, по 1–3 | 4 вида, по 1–3; + 3–4 лишних вида |
| CATEGORY_HOUSE | 4 ячейки + 4 лишних | 6 + 5 | 9 + 5 (категория с ≥ 9 предметами) |
| MISSING | 5 предметов, пропал 1 | 6, пропало 2 | 8, пропало 3 |
| PARKING | 3 машинки, 3 цвета | 5 машинок, 4 цвета | 7 машинок, 5 цветов |
| ARROWS | 4×4, 1 конфета, без камней, без диагоналей | 5×5, 2 конфеты, 2 камня, диагонали | 6×6, 3 конфеты, 4 камня, диагонали (путь проверяется BFS) |

Генераторы для существующих видов (включаются флагом `generate` у контента): ODD_ONE (3 из категории + 1 из другой; L1 4 карточки), COUNT (число 1..5 / 1..8 / 1..10, картинка случайная или из контента; 3–4 варианта), MEMORY (3/4/6 пар), SHADOW (3/4/4 картинки из одной категории на L3), REMEMBER (4/6/9 картинок), SYLLABLES (слово с 1–2 / 2–3 / 3+ слогами), WORD_BUILD (3 / 4–5 / 6 букв + 2 лишние), POP (`sound` — буква; цели = слова с этой буквой; 6/8/9 шариков), SORT (если `groupLabels` — две буквы, сортировка по звуку; если названия категорий — по категории).

## 4. Миграция `V14__generated_games.sql`

- Расширить `chk_content_exercises_type` новыми видами.
- Все SYSTEM-упражнения видов ODD_ONE, COUNT, MEMORY, SHADOW, REMEMBER, SYLLABLES, WORD_BUILD, POP, SORT перевести на `generate: true` (ограничения — из названия: категория, картинка, звук, группы).
- Новые SYSTEM-упражнения `00000000-0000-0000-0000-0000000007xx` для новых видов по уровням.
- Новые SYSTEM-шаблоны `…906` «Математика», `…907` «Буквы и слова», `…908` «Внимание и логика», `…909` «Перетаскивание».
- То же самое — в `InMemoryContentRepository`.
