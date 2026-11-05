# citygen — генератор городов для DerpCraft (Python)

Инструмент для Claude и людей: пишет постройки прямо в сохранение DerpCraft, без запуска игры.

- `derpcraft_world.py` — библиотека: читает и пишет мир (блоки, таблички, двери), делает резервные копии, рисует карту сверху и вид сбоку в PNG.
- `citygen.py` — процедурный город: сетка улиц с проспектами, разметкой, переходами, тротуарами, фонарями, деревьями и табличками с названиями; небоскрёбы в центре, кирпичные дома и магазины в середине, частные дома с садами на окраинах, парки с прудами, площадь с фонтаном, парковки.

Требования: Python 3.9+, `pip install numpy pillow` (Pillow нужен только для картинок).

## Правила (важно)

1. **Игра должна быть закрыта** (или хотя бы выйти из мира в главное меню). Иначе игра при следующем сохранении затрёт правки.
2. Мир должен быть **хотя бы раз открыт в игре** — тогда в папке мира появляется `blockids.txt` (имена блоков → id). Без него инструмент откажется работать.
3. Каждая запись делает резервную копию изменённых файлов регионов в `<мир>/region_backup/<время>/`. Откат: `python citygen.py --world ... --undo`.
4. Лучше всего строить в **плоском мире** (*Create New World → World Type: Superflat*): уровень улиц берётся из слоёв мира автоматически. В обычном мире город выравнивает землю на своей площади (уровень — медиана высоты вокруг центра, или `--ground Y`); ещё не сгенерированные чанки на площади города заполняются ровной землёй.

## Быстрый старт

```bat
cd DerpCraft\tools\citygen
python citygen.py --world "..\..\game\saves\My City" --size 256 --seed 7 --map city.png
```

Потом открыть мир в игре: игрок появится на центральной площади, лицом к фонтану.

| Параметр | Что делает |
|---|---|
| `--world PATH` | папка мира (`game/saves/<имя>`) |
| `--size N` | сторона квадрата города в блоках, 64…1024 (по умолчанию 192) |
| `--center X Z` | центр города (по умолчанию — сохранённая позиция игрока) |
| `--seed N` | сид; один сид — один и тот же город. Без него случайный (печатается) |
| `--ground Y` | уровень улиц (по умолчанию: плоский мир — верхний слой, иначе высота рельефа) |
| `--map city.png` | карта сверху |
| `--dry-run` | построить только в памяти (вместе с `--map` — предпросмотр), мир не меняется |
| `--no-player` | не переносить игрока в центр города |
| `--undo` | вернуть последнюю резервную копию и выйти |

Скорость: город 512×512 (≈600 зданий) строится примерно за секунду.

## Как работать Claude (порядок действий)

1. Найти мир: `game/saves/<имя>/level.properties`. Посмотреть его: `python derpcraft_world.py "<мир>" info`.
2. Предпросмотр: `python citygen.py --world "<мир>" --size 256 --seed 1 --dry-run --map plan.png` и **посмотреть картинку** (Read). Подобрать сид и размер.
3. Проверить высоты и крыши видом сбоку (после записи или на тестовой копии мира):
   `python derpcraft_world.py "<мир>" side X0 Z0 X1 Z1 side.png --y0 60 --y1 200`.
4. Записать (без `--dry-run`), сказать пользователю открыть мир. Если что-то не так — `--undo`.
5. Свои постройки — через API ниже. После записи всегда смотреть `map`/`side` и при необходимости `get x y z`.

Нет игры для теста? Сделай тестовый мир: папка с `level.properties` (`generator=flat`, `flatLayers=bedrock,2*dirt,grass`) и `blockids.txt`, скопированным из любого мира этой версии игры.

## Осмотр мира из командной строки

```
python derpcraft_world.py <мир> info                    # генератор, позиция игрока, файлы регионов
python derpcraft_world.py <мир> blocks stairs           # имена блоков (с фильтром)
python derpcraft_world.py <мир> get 10 64 -5            # блок и meta в точке
python derpcraft_world.py <мир> map X0 Z0 X1 Z1 out.png [--scale 4]
python derpcraft_world.py <мир> side X0 Z0 X1 Z1 out.png [--y0 60 --y1 200 --facing north|east]
```

## API для своих построек

```python
import sys; sys.path.insert(0, "DerpCraft/tools/citygen")
from derpcraft_world import World, META, stairs
from citygen import Frame, tree, lamp, tower, midrise, house, park, plaza

w = World("game/saves/My City", ground=64)   # ground — только для обычных миров с несгенерированными чанками
g = w.flat_ground()                          # для плоского мира: y верхнего слоя

w.set(x, y, z, "stone_bricks")               # блок; meta вторым аргументом или "oak_stairs:2"
w.fill(x0, y0, z0, x1, y1, z1, "glass")      # куб (углы включительно), only_air=True — не трогать существующее
w.walls(x0, y0, z0, x1, y1, z1, "bricks")    # четыре стены коробки
w.replace(x0, y0, z0, x1, y1, z1, "stone", "andesite")
w.get_name(x, y, z)  -> ("oak_stairs", 2);   w.top_y(x, z) -> высота столбца
w.set_door(x, y, z, "oak_door", side="north", hinge_right=False)   # 2 блока; side — сторона блока, где створка
w.set_wall_sign(x, y, z, "north", ["Main St", "", "", ""])        # табличка на стене с севера, текст смотрит на юг
w.set_standing_sign(x, y, z, 8, ["Hello"])                        # на столбе: 0 текст на юг, 4 запад, 8 север, 12 восток
w.set_player(x, y, z, yaw=180)               # где появится игрок (yaw 0 = смотрит на юг, 180 = на север)
w.save()                                      # запись + резервная копия; ничего не пишется до save()
w.render_map("map.png", x0, z0, x1, z1, scale=4)
w.render_side("side.png", x0, z0, x1, z1, y0, y1, facing="north")
```

**Frame — координаты участка**, чтобы двери и ступени смотрели куда надо. Стоим на улице лицом к участку:
`u` — слева направо вдоль улицы (0…W−1), `v` — от края улицы вглубь (0…D−1), `y` — мировой.

```python
f = Frame(w, x0, z0, x1, z1, front="north")    # улица к северу от участка
f.fill(0, g, 0, f.W - 1, g, f.D - 1, "stone_bricks")
f.walls(0, g + 1, 0, f.W - 1, g + 3, f.D - 1, "white_concrete")
f.door(f.W // 2, g + 1, 0, "oak_door")         # дверь в передней стене, створка снаружи
f.stairs(u, y, v, "oak_stairs", high="back")   # высокая часть ступени: front/back/left/right
f.xz(u, v) -> (x, z);  f.dir("left") -> "west";  f.sub(u0, v0, u1, v1) -> Frame
```

Готовые генераторы (все в локальных координатах `Frame`): `tower(f, y0, H, rng, stats)`, `midrise(f, y0, floors, rng, stats, shop)`, `house(f, y0, rng, stats)`; на мировых координатах: `park(...)`, `plaza(...)`, `parking(...)`, `tree(w, x, y, z, rng, "oak"|"birch"|"spruce"|"dark_oak")`, `lamp(w, x, y, z)`. `stats` — словарь со счётчиками, проще всего взять из `build_city`. Весь город целиком: `build_city(w, cx, cz, size, ground_y, seed)`.

## Блоки и meta

Имена — как в игре (`python derpcraft_world.py <мир> blocks`). Цвета — отдельными блоками: `red_concrete`, `light_blue_stained_glass`, `white_wool`, `orange_terracotta`, `gray_stained_glass_pane`, `blue_carpet`. Ступени и плиты: `oak_stairs`, `stone_brick_slab`, `quartz_stairs`, `white_concrete_slab`…

| Блок | meta |
|---|---|
| ступени `*_stairs` | высокая сторона: 0 юг, 1 запад, 2 север, 3 восток; +4 — перевёрнутые. `stairs("north")` |
| плиты `*_slab` | 0 нижняя, 1 верхняя |
| брёвна, `quartz_pillar`, `purpur_pillar`, `hay_block`, `bone_block` | 0 вертикально, 1 вдоль X, 2 вдоль Z |
| `furnace`, `pumpkin`, `jack_o_lantern`, `dispenser`, `dropper` | лицевая сторона: 0 юг, 1 запад, 2 север, 3 восток |
| `torch`, `redstone_torch` | 0 на полу; на стене с: 1 юга, 2 запада, 3 севера, 4 востока |
| `wall_sign` | доска на стороне: 0 север, 1 восток, 2 юг, 3 запад (текст — в противоположную) |
| `standing_sign` | поворот 0…15 (0 текст на юг, 4 запад, 8 север, 12 восток) |
| двери `*_door` | нижняя: сторона створки 0 север, 1 восток, 2 юг, 3 запад, +4 открыта; верхняя: 8 + (1 если петли справа). Проще `set_door` |
| стекло, панели, заборы, стены | без meta: соединяются сами |

Свет: `glowstone`, `sea_lantern`, `lit_redstone_lamp`, `torch`, `jack_o_lantern` (освещение мир считает сам при загрузке).

## Ограничения

- Высота мира 0…255.
- Текст табличек — только латиница и символы до кода 255 (шрифт игры), до 15 символов в строке.
- Сундуки и печи ставятся пустыми (содержимое не задаётся).
- В обычном мире края города — ровная земля рядом с рельефом, без плавного перехода.

## Формат сохранения (для справки)

`region/r.<rx>.<rz>.dcr`: заголовок 1024 × (int offset, int length), big-endian, индекс `(cz&31)*32 + (cx&31)`; блобы сжаты zlib, выровнены по 4096. Чанк: `int version=2`, 256 байт температуры, 256 влажности, `ushort` маска секций, на каждую секцию 4096 × `ushort` (`(id<<4)|meta`, индекс `(y<<8)|(z<<4)|x`), затем `int` число блок-сущностей и для каждой: `int key=(y<<8)|((z&15)<<4)|(x&15)`, `writeUTF type`, `short` пар, пары `writeUTF key` + `int длина` + UTF-8. Свет не хранится. Формат сверен с `RegionStorage.java` в обе стороны: Python пишет — код игры читает, и наоборот.
