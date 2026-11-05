# Транспорт: памятка для Claude

Машины **не встроены в игру**. Каждая машина — папка `game/transport/<имя>/` у пользователя (модель + текстуры + настройки
+ звуки), игра читает её при запуске и по `/vehicle reload`. Код транспорта для новой машины **не трогать**.

## Пользователь прислал модель и просит «добавь машину»

1. Сделать папку машины (имя папки = имя машины, латиницей, без пробелов) и положить туда модель под этим именем:
   ```
   mkdir -p /tmp/transport/<имя> && cp "<загрузка>" /tmp/transport/<имя>/<имя>.obj   # или .bbmodel / .json (Blockbench Java)
   ```
   OBJ: рядом `.mtl` и `.png`, если прислали. Blockbench `.json` — тогда настройки в `vehicle.json`, не в `<имя>.json`.
2. Проверить (реальный код игры без OpenGL, секунды):
   ```
   sh tools/vehicle_check/check.sh /tmp/transport/<имя>
   ```
   Отчёт: модель и настройки, скорость, звуки, текстуры, части, оси дверей, сиденья и их двери, места для блоков, подвеска,
   тест езды. `ALL OK` / `PROBLEMS`; строки `WARNING` пересказать пользователю (это свойства модели).
   Если в отчёте **нет `seat`-групп / багажник назван не `trunk` / pivot двери не на петле** — это чинится настройками (ниже),
   модель не править. Посмотри `part …` (габариты частей в координатах машины) и `opens:` (pivot и угол каждой крышки).
3. Если нужны настройки — написать `/tmp/transport/<имя>/<имя>.json` (ключи — в `game/transport/_template.json`, он же
   `Transports.TEMPLATE`; таблица — README, раздел «Транспорт»), прогнать проверку ещё раз.
4. Отдать пользователю **архив этой папки** (`zip -r <имя>.zip <имя>`): он распаковывает его в `game\transport\` и набирает
   `/vehicle reload`. Сборка игры для этого не нужна.

В проект машины не добавлять (встроенных машин нет по просьбе пользователя).

**Редактор машин (Left Alt + End)** пишет настройки в `<имя>.json` (если `<имя>.json` — модель Blockbench, то в `vehicle.json`, и он главнее ключа `vehicle` внутри модели): скорость, места (`seats` — порядок = номера мест, не сортируется), точки блоков (`blocks`), размер (`scale`, `width`, `height`, `length`), подвеска, `stepHeight`, `weight`, `waterDrag`, `handling` (дрифт, полицейский разворот, переворот). Если пользователь просит «поменяй скорость / вес / места», это те же ключи — можно писать руками. Проверка: `sh tools/vehicle_check/check.sh <папка>`, физика (дрифт, переворот, толкание): `sh tools/vehicle_check/handling.sh`, настройки и камера: `sh tools/vehicle_check/editor.sh`.

## Что игра ищет в модели

Имена групп (Blockbench) или объектов `o` / групп `g` (OBJ), регистр не важен. Вложенные группы едут с ближайшим родителем с ролью.

| Имя | Роль |
|---|---|
| `wheel*` (`koleso*`) | колесо; ≥ 2 колёс = машина. Крутится по X вокруг середины; передние (меньший z) поворачивают |
| `door*`, `hood`, `trunk`, `bonnet`, `boot`, `hatch`, `lid`, `kapot*`, `bagazh*`, `dver*` | открывается вокруг pivot: тонкая по X — дверь (вокруг Y наружу), тонкая по Y — крышка (вокруг X вверх) |
| `steer*`, `*steering*`, `rul` | руль: вокруг самой тонкой оси; крутится только то, что ближе 0,5 блока к pivot |
| `seat*` (`sid*`) | сиденье: верх подушки = грань вверх с наибольшей площадью; водитель — ближе к рулю; дверь — ближайшая боковая |
| `block`, `block1`…`block4` | место для блока; в OBJ — кубик-метка (не рисуется), блок встаёт на середину его низа |
| остальное | кузов |

Перед — **-Z**, +Y вверх, 1 единица = 1 блок (Blockbench: 16 px). Иначе — `turn` / `scale` / `objUnitsPerBlock` в настройках.
**Pivot в OBJ нет** — ставятся сами (`VehicleModel.autoPivots`): колесо/руль — середина, дверь — передний край снаружи,
капот — задний край сверху, багажник — передний край сверху. Свои — `"parts": {"door1": {"pivot": [x,y,z], "angle": 80}}`
(отрицательный угол — в другую сторону). **Все координаты в настройках — в системе машины**, как их печатает проверка:
ноль между осями на земле, +x вправо, +y вверх, -z вперёд, блоки (уже с учётом `scale`/`turn`).

**Нестандартные имена** (`bag`, `bagazhnik`, `kapot`…): `"roles": {"bag": "trunk"}` — группа считается этой ролью (угол
открывания берётся по роли, вложенные группы едут с ней). **Нет групп сидений**: игра ставит 4 места возле руля сама; точнее —
`"seats": [[x,y,z],...]` (середина верха подушки; высоту подушек/пола смотри `Probe`-ом по граням вверх или по габаритам
`part …`; у машин 0,55–0,75). Габарит машины по ширине считается без дверей (торчащие куски дверей не расширяют хитбокс).

**Текстуры OBJ** (`ObjLoader`): `map_Kd` из `.mtl` → `<материал>.png` → `<объект>.png` → `<объект без номера>.png`
(`wheel1` → `wheel.png`) → `<имя файла>.png` → цвет `Kd`. Прозрачность `d` из `.mtl` сохраняется.

## Частые проблемы моделей

- `NOT A VEHICLE` — нет частей `wheel…` (всё одним объектом). Попросить разложить по объектам/группам; файл без просьбы не переписывать.
- Колесо крутится «восьмёркой» — у Blockbench-группы pivot не в центре колеса (у OBJ центр берётся по габаритам — такого нет).
- Дверь открывается внутрь — pivot двери не на петле; в настройках `parts.doorN.pivot` или `angle` со знаком минус.
- Блок торчит из багажника / крыши — pivot `block1` слишком высоко (проверка пишет WARNING).
- Модель повёрнута / огромная — `turn: 180`, `objUnitsPerBlock: 100` (сантиметры) и т. п.

## Где что в коде

- `vehicle/Transports.java` — папки `game/transport` (rescan, `README.txt` и `_template.json` пишутся при запуске).
- `vehicle/VehicleType.java` — одна машина: поиск файлов, чтение настроек (км/ч → блоки за тик: /72; км/ч/с → /72/20), звуки.
- `vehicle/VehicleModel.java` — разбор модели (роли, `prepare` = scale/turn, `autoPivots`, сиденья, двери сидений, коллизия, `chunks` для прицела).
- `vehicle/Vehicle.java` — физика (по `type()`), подвеска, анимация частей, автопилот NPC. **Дрифт / полицейский разворот**: `speed` — вперёд, `lat` — боком (вправо), `drift` 0..1 — доля, на которую скорость остаётся в мире, пока нос поворачивает (`rd = dyaw * drift`), `grip` / `driftGrip`, `police` — тики полицейского разворота; `slip` — для следов и дыма. **Переворот**: `rollOver` копит `rollRisk` (боковая сила против `type().rollover * stability`), `startFlip`, `flip` (±360, к `roll` в `toWorld` / `baseMatrix` / `bodyPointAt`, подъём `lift`), `flipImpact` / `flipLanded` читает `Vehicles.rollOverHurt`. **Толкание**: `push` (скорость, задаёт `Vehicles.pushTarget`).
- `vehicle/Surfaces.java` — покрытие под машиной (`under`, по имени блока), множители `VehicleType.surfGrip` / `surfSpeed`, применяются в `Vehicle.tick` (`sGrip`, `traction`, `loose`).
- Фары: `VehicleType.Light` / `effectiveLights()`, `Vehicle.lightsOn` (клавиша N, `Vehicles.toggleLights`), свет — `DynamicLights.addVehicleLights` (три точки динамического света вдоль луча, ярче к концу; `MAX_DYN` 16, массивы в `terrain.fsh`); рисованных лучей нет.
- Двери в редакторе: `VehicleModel.Part.geoSign` (знак угла по геометрии; в настройках угол относительный), `parts.<имя>.angle/pivot`.
- Таран: `Vehicle.smashable` / `smashTargets` (+ в `blocked` такие блоки не преграда на скорости ≥ `smashSpeed`), ломает `Vehicles.smash` через `Session.harvest`.
- Взрыв: `Vehicles.explodeKey` / `explode` / `wreckEffects` (Ctrl + End, `Keys.VEHICLE_EXPLODE`; без машины рядом — селфи), состояние `Vehicle.wrecked` / `wreckAge` / `charred` / `dmgTurn` / `dmgOpen` (`wreck(seed)`, `repair()`), поворот частей в `partAnim`, чернота в `EntityRenderer.vehicles`, огонь и дым `wreckFlame` / `wreckSmoke`, свет `DynamicLights`. Взрыв посреди переворота: `wreckFrom` / `wreckTarget`. В записи (Recording v6): `Frame.vflip` / `vslip` / `vflags` (бит 0 фары, бит 1 взрыв), проигрывание — `Vehicles.replay(..., flip, slip, flags, snap)`.
- `vehicle/VehicleCamera.java` — кинематографичная камера (Right Alt + F8), без GL; вызывается из `Session.render`.
- `gui/VehicleEditor.java` — редактор (Alt + End): таблица `PARAMS` = ползунки, места / блоки живут в `VehicleType.seats` / `blocks` (+ в `VehicleModel`, чтобы видеть сразу), `VehicleType.save()` / `rebuild()` / `resize()`; метки в мире — `Vehicles.marks` (рисует `Session.vehicleMarks`).
- Следы шин и дым — `EntityRenderer.skidMark` / `skidMarks` / `tireSmoke` (зовёт `Vehicles.tireEffects`); руки при толкании — `gun/PushArms` (от первого лица), `PlayerPose.push` (от третьего).
- `vehicle/Vehicles.java` — машины мира: посадка, клики, NPC, сцены, коллизии, камера, **звуки** (`sounds()`), сохранение `vehicles.txt`, предмет Car, `/vehicle`.
- `audio/Audio.java` — микшер на `javax.sound` (без библиотек): WAV, громкость, высота, панорама, петли. Без звуковой карты молчит.
  Тест без устройства: `Audio.offline = true`, `Audio.mix(...)`.
- `model/ObjLoader` (объекты → кости, текстуры по именам), `model/BlockbenchLoader` (`.bbmodel`, Java `.json` с `groups`), `Models.loadFile`.
- Рендер: `render/StaticMesh` + `ModelBatch.drawStatic` (`uUseModel` в `model.vsh`), кэш в `EntityRenderer.vehicles()` по `Transports.version()`.
- Встроено в: `Session`, `EntityRenderer`, `world/Collision.collect` (через `Vehicles.colliders`), `Actor` (`DRIVE`), `NpcManager`, `Recording` (v3), `Track`, `NpcHub`, `Keys.HORN`.
- `tools/vehicle_check/bump.sh <папка>` — насколько «трясёт»: машина едет по ступенчатым холмам и склонам, печатаются скачки кузова и носа за тик (сравнивай до/после правок `Vehicle.java`; параметры сглаживания — `SM_*`, `ANGLE_FOLLOW`). Физика: `Vehicle.gy` — высота опоры (по ней земля и столкновения), `Vehicle.y` — показываемая высота (фильтр `smoothHeight`); `groundAt` — земля как пологие рампы у ступенек.
- `tools/vehicle_check/` — офлайн-проверка папки машины (заглушки мира в `stubs/`; если `Vehicle.java` начнёт использовать новые методы World/Block, дописать их туда).

**1.14_61.** Пули: `Vehicles.bulletHits` (все детали по лучу, треугольники как в `pick`, своя машина стрелка пропускается) → `Ballistics.fire` (пропускающая деталь — `EntityRenderer.vehicleImpact(..., true)` и луч летит дальше; останавливающая — конец луча). Какие детали останавливают — `VehicleType.bullets` / `stopsBullets(part)` (json: `parts.<имя>.stopsBullets`), страница *Bullets* в `gui/VehicleEditor` (undo через `Snap.bullets`). Попутчики: `Vehicles.npcFollow` (из `Actor.tickAI` при FOLLOW), `Actor.rideAlong`, `checkRiders` не выкидывает попутчика. Смерть водителя дубля: `Vehicles.actorDied` → `Vehicle.driverKilled` (дубли пассажиров машину не двигают, `Vehicle.tick` не тормозит ручником, пока катится; сброс — `repairForScene`, игрок/NPC за рулём). Выход: `EXIT_SAFE_TICKS` (`PlayerStats.safeUntil`, `Actor.exitGrace`). Проверка в игре — `SCENARIO=tools/npc_check/vehicles/Scenario.java OUT=/tmp/dc_npcv sh tools/npc_check/run.sh`.
