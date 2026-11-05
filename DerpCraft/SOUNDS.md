# Звуки Minecraft (`game\sounds`)

Папку `sounds` из ассетов 1.12 кладите в `game\sounds` как есть. Любой файл можно проверить в игре командой `/sound <папка/имя>` без номера
(например `/sound mob/zombie/say`, `/sound random/levelup`); `/sound list [фильтр]` показывает найденные. Играет случайный вариант из `имя`, `имя1`, `имя2`...
Из кода: `Sfx.at(session, "папка/имя", x, y, z, громкость)` (в мире, затухает с расстоянием) или `Sfx.play("папка/имя", громкость, высота)`.

| Папка | Файлов | Подключено |
|---|---|---|
| `ambient/cave` | 18 | пещерный фон под землёй |
| `ambient/weather` | 11 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `block/brewing_stand` | 2 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `block/chest` | 4 | сундуки |
| `block/chorus_flower` | 7 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `block/enchantment_table` | 3 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `block/end_portal` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `block/enderchest` | 2 | сундук Края (по названию окна) |
| `block/fence_gate` | 4 | калитки |
| `block/furnace` | 5 | горящая печь |
| `block/iron_door` | 8 | железные двери (через `setOpen`) |
| `block/iron_trapdoor` | 8 | железные люки |
| `block/shulker_box` | 2 | шалкер (по названию окна) |
| `block/waterlily` | 4 | установка кувшинки |
| `block/wooden_door` | 8 | открытие/закрытие дверей |
| `block/wooden_trapdoor` | 8 | люки |
| `damage` | 5 | урон и падение игрока |
| `dig` | 28 | ломание/установка блоков (`dig/<материал>`) |
| `enchant/thorns` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/armorstand` | 8 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/boat` | 14 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/bobber` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/cow` | 3 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/endereye` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/guardian` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/horse` | 5 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/itemframe` | 19 | рамки: поставить/сломать/предмет/поворот |
| `entity/leashknot` | 6 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/painting` | 7 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/player/attack` | 24 | удар по мобу (`strong`, в прыжке `crit`) |
| `entity/player/hurt` | 7 | урон от утопления и огня |
| `entity/rabbit` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/shulker` | 34 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/shulker_bullet` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/snowman` | 6 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/squid` | 12 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `entity/witch` | 18 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `fire` | 2 | огонь рядом, поджог |
| `fireworks` | 7 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `item/armor` | 36 | надевание брони |
| `item/bottle` | 8 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `item/bucket` | 12 | вёдра |
| `item/elytra` | 1 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `item/hoe` | 4 | мотыга по земле |
| `item/shield` | 5 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `item/shovel` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `item/totem` | 1 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `liquid` | 9 | всплеск, плавание, лава/вода рядом |
| `minecart` | 2 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `mob/bat` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/blaze` | 9 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/cat` | 15 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/chicken` | 8 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/cow` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/creeper` | 5 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/enderdragon` | 15 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/endermen` | 17 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/evocation_illager` | 15 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/ghast` | 16 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/guardian` | 29 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/horse` | 32 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/horse/donkey` | 9 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/horse/skeleton` | 8 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/horse/zombie` | 8 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/husk` | 12 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/illusion_illager` | 13 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/irongolem` | 10 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/llama` | 22 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/magmacube` | 13 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/parrot` | 28 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/pig` | 9 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/polarbear` | 18 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/polarbear_baby` | 4 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/rabbit` | 13 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/sheep` | 9 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/silverfish` | 12 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/skeleton` | 12 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/slime` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/spider` | 9 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/stray` | 14 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/vex` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/villager` | 17 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/vindication_illager` | 10 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/wither` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/wither_skeleton` | 13 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/wolf` | 20 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/zombie` | 22 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/zombie_villager` | 6 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `mob/zombiepig` | 11 | голос / урон / смерть / шаги моба (`say`, `hurt`, `death`, `step`) |
| `music/game` | 12 | музыка в игре |
| `music/game/creative` | 6 | музыка в творческом режиме |
| `music/game/end` | 3 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `music/game/nether` | 4 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `music/menu` | 4 | музыка в меню |
| `note` | 13 | нотный блок (ПКМ меняет ноту) |
| `portal` | 3 | рядом с порталом |
| `random` | 36 | взрыв, еда, подбор, клик, стекло, лук, поломка, выстрел и т. д. |
| `records` | 12 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `step` | 40 | шаги игрока |
| `tile/piston` | 2 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |
| `ui/toast` | 3 | — (в игре нет такой функции; путь готов, вызывать `Sfx.at(...)`) |

Не подключены, пока нет самих функций в игре: погода (`ambient/weather`), лодки, вагонетки, поршни, пластинки и проигрыватель, зелья, опыт (`random/levelup`, `random/orb`), эндер-глаз, фейерверки, надгробные/`entity/*` мобы без существ в игре, элитры, тотем, щит.

## Настройки звука (Options → Music & Sounds...)
Как в оригинале: Master Volume, Music, Jukebox/Noteblocks, Weather, Blocks, Hostile Creatures, Friendly Creatures, Players, Ambient/Environment.
Хранятся в `options.txt` (`soundCategory_master`, `_music`, `_record`, `_weather`, `_block`, `_hostile`, `_neutral`, `_player`, `_ambient`).
Категория звука определяется по его пути — `Sfx.category(имя)` (папка `mob/` делится на враждебных и мирных по `HOSTILE_MOBS`); звуки интерфейса (клики) — только мастер.
Master действует на всю игру, в том числе на оружие и машины; остальные категории — на звуки из `game\sounds`.
