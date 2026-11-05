# Моды для DerpCraft

Мод — это обычный `.jar`, который кладётся в `game\mods\`. Игра находит его при запуске через стандартный Java `ServiceLoader`, вызывает `init()` и даёт `ModContext`. Через него регистрируется всё нужное. Встроенные блоки и команды игры регистрируются через тот же API, так что мод может то же, что и сама игра.

**С версии 1.7 мод может всё, даже то, чего нет в API:** менять код самой игры (патчи, как Mixin в Fabric/Forge), слушать события почти на всё, добавлять своих существ, снаряды, взрывы и генерацию мира. Коротко:

| Что нужно | Чем | Раздел |
|---|---|---|
| поменять любое поведение игры | `patches(Patcher)` — вставка своего кода в любой метод игры | [Изменение кода игры](#изменение-кода-игры-патчи-17) |
| реагировать на урон, смерть, взрывы, клавиши, окна, команды… | `ctx.on(Events.X.class, ...)` | [События](#события-17) |
| мобы, животные, монстры | `ctx.registerMob(...)` + `Model` | [Существа](#существа-17) |
| бросаемое и стреляющее, взрывы | `ctx.registerProjectile(...)`, `game.explode(...)` | [Снаряды и взрывы](#снаряды-и-взрывы-17) |
| руды, постройки в новых чанках | `ctx.addOre(...)`, `ctx.onChunkGenerate(...)` | [Генерация мира](#генерация-мира-17) |

Готовый пример со всем этим — `examples\Creatures`.

## Минимальный мод

```java
package mymod;

import derpcraft.api.*;
import derpcraft.block.Block;
import derpcraft.block.Dir;

public class MyMod implements DerpMod {
    @Override public String id() { return "mymod"; }          // пространство имён и папка текстур

    @Override public void init(ModContext ctx) {
        // новый блок: текстура из jar мода assets/mymod/textures/blocks/ruby_block.png
        Block ruby = ctx.registerBlock("ruby_block")
                .texture("mymod:ruby_block")
                .light(10)
                .displayName("Ruby Block")
                .register();

        // команда /tower <высота>
        ctx.addCommand("tower", "<height>  builds a ruby tower", (game, args) -> {
            int h = args.length > 0 ? Integer.parseInt(args[0]) : 10;
            int x = (int) game.playerX() + 3, y = (int) game.playerY(), z = (int) game.playerZ();
            game.fill(x, y, z, x + 2, y + h, z + 2, ruby);
        });

        // событие: игрок ставит блок
        ctx.onBlockPlace((game, e) -> {
            if (e.block.name.equals("dirt")) e.cancel();
        });

        // клавиша H (коды GLFW): мгновенно сделать полдень
        ctx.addKey("Set noon", 72, game -> game.setTime(6000));

        // своя надпись на экране
        ctx.onHudRender((game, hud) -> hud.text("MyMod", 4, 4, 0xFFFFFF55));
    }
}
```

## Что можно регистрировать (`ModContext`)

| Метод | Что делает |
|---|---|
| `registerBlock(name)` → `BlockBuilder` | новый блок, итоговое имя `modid:name` |
| `addCommand(name, usage, handler)` | чат-команда `/name` (и `//name`) |
| `onTick(handler)` | 20 раз в секунду, пока открыт мир |
| `onBlockPlace(handler)` / `onBlockBreak(handler)` | перед установкой/разрушением, можно отменить `e.cancel()` или подменить `e.block`, `e.meta` |
| `onChat(handler)` | сообщения чата (не команды) |
| `onWorldLoad(handler)` | мир загружен |
| `onHudRender(handler)` | рисование поверх экрана: `hud.text`, `hud.rect`, `hud.blockIcon` |
| `addKey(description, glfwKey, action)` | своя клавиша в игре: `glfwKey` — клавиша по умолчанию, игрок может переназначить её в *Controls...* *(1.10)* |
| `registerItem(name, texture, tab)` → `Item` | новый предмет (вызывать из `initItems`) |
| `addRecipe(line)` | рецепт крафта или печи (вызывать из `initItems`) |
| `onFrame(handler)` | **каждый кадр** (с временем кадра в секундах) — для плавной анимации камеры *(1.3)* |
| `onCameraPathEnd(handler)` | путь камеры доиграл до конца *(1.3)* |
| `onSceneEnd(handler)` | сцена NPC-актёров доиграла *(1.4)* |
| `addGradingPreset(name, text)` / `addGradingPreset(name, MyMod.class, "/assets/mymod/grading/x.txt")` | свой пресет цветокоррекции — появляется в редакторе F6 рядом со встроенными *(1.3)* |
| `item(name)` → `ItemBuilder` | новый предмет одной цепочкой, с обработчиком ПКМ *(1.5)* |
| `addMachineRecipe(type, in1, in2, out, count, ticks, xp)` | рецепт для своих машин *(1.5)* |
| `addItemTag(tag, items...)` | свой тег для рецептов (`#tag`) *(1.5)* |
| `onPlayerTick(h)`, `onBlockInteract(h)`, `onItemUse(h)` | каждый тик; ПКМ по любому блоку; ПКМ любым предметом — можно отменить *(1.5)* |
| `config()` / `saveConfig()` | файл настроек мода `game\config\<modid>.txt` *(1.5)* |
| `log(msg)` | вывод в консоль с именем мода |

## BlockBuilder

```java
ctx.registerBlock("marble").texture("mymod:marble").register();                  // обычный куб
ctx.registerBlock("marble_column").textures("mymod:col_top", "mymod:col_side", "mymod:col_top").pillar().register();
ctx.registerBlock("marble_slab").texture("mymod:marble").shape(Block.Shape.SLAB).register();
ctx.registerBlock("marble_stairs").texture("mymod:marble").shape(Block.Shape.STAIRS).register();
ctx.registerBlock("lamp").texture("mymod:lamp").light(15).register();
ctx.registerBlock("hedge").texture("leaves_oak").cutout().tint(Block.Tint.FOLIAGE).register();
ctx.registerBlock("tinted_glass").texture("mymod:tinted_glass").translucent().register();
ctx.registerBlock("rose").texture("mymod:rose").shape(Block.Shape.CROSS).register();     // растение крестом
ctx.registerBlock("console").face(Dir.NORTH, "mymod:console_front").face(Dir.EAST, "mymod:console_side")
        .face(Dir.UP, "mymod:console_top").face(Dir.DOWN, "mymod:console_top").facesPlayer().register();
```

Формы: `CUBE, SLAB, STAIRS, CROSS, TORCH, PANE, FENCE, WALL, CARPET, SNOW_LAYER, CACTUS`.
Имена текстур без двоеточия — это текстуры Minecraft 1.12.2 (`stone`, `planks_oak`…), с двоеточием — из твоего мода.
Для своей формы можно унаследоваться от `derpcraft.block.Block` и переопределить `boxes(meta, conn)` (набор прямоугольных элементов, как в JSON-моделях Minecraft), `textureName(face, meta)` и `placementMeta(...)`, а потом зарегистрировать через `Blocks.register(...)`.

Номера блоков в сохранениях привязаны к именам (`saves/<мир>/blockids.txt`), поэтому добавление и удаление модов не ломает миры.

Цветной свет: `.light(15).lightColor(0x40FF60)` — зелёная лампа; `.lightFilter(0xFF3030)` — блок окрашивает проходящий свет (как витраж).

Для выживания: `.hardness(1.5f)`, `.tool(Item.ToolType.PICKAXE, 1, true)` (нужна каменная кирка), `.drops("mymod:ruby", 1, 3)`, `.tab("decoration")` — вкладка креатива.

## Функциональные блоки без лишнего кода *(1.5)*

Всё задаётся в той же цепочке `registerBlock(...)`, без наследования. Окна, сохранение, выпадение, анимация «горения» — уже внутри.

```java
// хранилища любого размера: окно само подстраивается
ctx.registerBlock("crate").texture("mymod:crate").storage(5).register();            // 1 ряд из 5
ctx.registerBlock("cabinet").texture("mymod:cab").facesPlayer().storage(12, "Cabinet").register();   // 2 x 6
ctx.registerBlock("locker").texture("mymod:locker").storage(30).register();         // 4 x 8, последний ряд по центру

// «постоянное» хранилище: сломал — содержимое осталось в предмете, поставил в другом месте — всё на месте
ctx.registerBlock("suitcase").texture("mymod:case").facesPlayer()
   .box(2, 0, 5, 14, 10, 11).box(6, 10, 7, 10, 12, 9)                               // своя модель
   .storage(9, "Suitcase").persistent().register();

// печка и свои машины
ctx.registerBlock("oven").texture("mymod:oven_side").face(Dir.NORTH, "mymod:oven_front")
   .facesPlayer().furnace().litFace("mymod:oven_front_on").register();             // как ванильная печь
ctx.registerBlock("grinder").texture("mymod:grinder").facesPlayer()
   .machine("mymod:grinding").cookTime(100).litFace("mymod:grinder_on").register(); // свои рецепты
ctx.registerBlock("alloy").texture("mymod:alloy").machine("mymod:alloying").twoInputs().register();
ctx.registerBlock("solar_oven").texture("mymod:solar").furnace().noFuel().cookTime(400).register();
// в initItems:
ctx.addMachineRecipe("mymod:grinding", "cobblestone", null, "gravel", 1, 0, 0.1f);
ctx.addMachineRecipe("mymod:alloying", "iron_ingot", "coal*2", "mymod:steel", 1, 0, 0.7f);

// таблички и экраны
ctx.registerBlock("street_sign").texture("mymod:sign_edge").face(Dir.NORTH, "mymod:sign_board")
   .sign(4).textColor(0xFFFFFF).register();          // современная табличка: на стене или на столбике
ctx.registerBlock("screen").texture("mymod:screen").facesPlayer()
   .textDisplay(3).textColor(0x40FF60).textGlow().register();   // светящийся текст на лицевой стороне
```

| Метод `BlockBuilder` | Что делает |
|---|---|
| `storage(слотов[, заголовок])` | хранилище на 1–54 слота. Окно раскладывается само: до 9 — один ряд, дальше ровные ряды (12 = 2×6, 30 = 4×8), кратные 9 — ванильной текстурой сундука из ресурспака |
| `persistent()` | содержимое (и данные, и текст) остаётся в выпавшем предмете — чемоданы, сейфы, шалкеры. В подсказке предмета видно, что внутри. В креативе заполненный блок тоже выпадает |
| `slotFilter((slot, stack) -> ...)` | какие предметы принимает слот |
| `furnace()` | печь на ванильных рецептах |
| `machine("тип")` | машина как печь на своих рецептах; `cookTime(тиков)`, `twoInputs()`, `noFuel()`, `fuelEfficiency(x)`, `litFace(текстура)` / `litLight(уровень)` — светится и меняет лицо, пока работает |
| `sign(строк)` | табличка-панель: при установке на стену висит на стене, на землю — стоит на столбике и смотрит на игрока. NORTH-текстура — лицо доски |
| `textDisplay(строк)` | текст на лицевой стороне блока (экраны, вывески, ценники) |
| `textColor(rgb)`, `textGlow()`, `textScale(x)`, `maxTextWidth(px)`, `textArea(cx, cy, z, ширина)`, `noEditOnPlace()` | оформление текста; длинные строки сами сжимаются по ширине |
| `box(x0, y0, z0, x1, y1, z1[, текстура])` | своя модель из элементов (пиксели 0–16, как JSON-модели), поворачивается вместе с `facesPlayer()` |
| `data()` | сохраняемые данные блока (`DataTag`) без других функций |
| `onUse((block, game) -> bool)` | ПКМ. `true` — обработано, `false` — дальше откроется окно хранилища/машины/текста |
| `onPlaced`, `onRemoved`, `onTick`, `onRandomTick`, `onScheduledTick`, `onStep`, `canPlace` | остальные события блока |
| `replaceable()`, `climbable()`, `needsSupport()`, `unbreakable()`, `opacity(n)` | свойства |

В обработчик приходит **`BlockContext`**: `x() y() z()`, `block()`, `meta()/setMeta()`, `facing()`, `data()` (сохраняемый `DataTag`), `inventory()` (слоты), `text()/setText(line, s)`, `working()` (машина работает), `scheduleTick(n)`, `drop(stack)`, `setBlock(block, meta)`, `breakBlock(drops)`, `light()`, `openGui()` и `game()`.

```java
// сейф с замком: Shift + ПКМ пустой рукой — закрыть/открыть
ctx.registerBlock("safe").texture("mymod:safe").facesPlayer().storage(27).persistent()
   .onUse((block, game) -> {
       DataTag d = block.data();
       if (game.playerSneaking() && game.heldItem() == null) { d.put("locked", !d.getBool("locked")); return true; }
       if (d.getBool("locked")) { game.chat("Locked"); return true; }
       return false;                       // откроется окно хранилища
   }).register();

// батут
ctx.registerBlock("trampoline").texture("mymod:trampoline").box(0, 0, 0, 16, 6, 16)
   .onStep(block -> block.game().pushPlayer(0, 0.9, 0)).register();
```

**`DataTag`** — простое хранилище ключ→значение: `getInt/getFloat/getString/getBool/getTag`, `put(...)`, `add(key, n)`, `remove`, `keys()`. Есть у блоков (`block.data()`), у предметов (`stack.tag()` — переживает сохранение, стаки с разными тегами не складываются, `"name"` меняет название предмета), у мира (`game.worldData("mymod")`) и у мода (`ctx.config()`).

## Свои окна: Menu *(1.5)*

```java
InventoryApi player = game.playerInventory();
game.openMenu(Menu.create("Vending Machine", 176, 166)
    .label(8, 20, () -> "4 Cookies: 1 gold", 0x404040)
    .button(110, 16, 58, 14, () -> "Buy", () -> { if (player.remove("gold_ingot", 1) == 1) game.giveItem(game.createItem("cookie", 4)); })
    .slot(myInv, 0, 56, 35)                        // слот любого InventoryApi
    .slot(myInv, 1, 116, 35, Menu.OUTPUT)          // только забрать
    .progress(79, 34, Menu.ARROW, () -> progress)  // стрелка / пламя / полоса
    .playerInventory()                             // инвентарь игрока внизу
    .onTick(() -> ...).onClose(() -> ...));
```

Окно рисуется в ванильном стиле, клики по слотам работают как в сундуках (Shift, двойной клик, 1–9, Q). Инвентарь для окна: `game.createInventory(n)`; сохранить — `inv.save()` в `DataTag`, загрузить — `inv.load(text)`.

## Предметы и рецепты

Метод `initItems(ctx)` в моде вызывается, когда все блоки, предметы и ванильные рецепты уже созданы (для каждого блока предмет создаётся сам).

```java
@Override public void initItems(ModContext ctx) {
    Item ruby = ctx.registerItem("ruby", "ruby", "misc");          // assets/mymod/textures/items/ruby.png
    Item pie = ctx.registerItem("berry_pie", "berry_pie", "food");
    pie.food = 8; pie.saturation = 0.6f;                           // еда
    ctx.addRecipe("S mymod:ruby_block 1 | ### ### ### | #=mymod:ruby");   // форма 3x3
    ctx.addRecipe("L mymod:ruby 9 | mymod:ruby_block");                  // без формы
    ctx.addRecipe("F mymod:ruby_ore mymod:ruby 1.0");                    // печь: вход, выход, опыт
}
```

Короче, с 1.5 — `ItemBuilder`:

```java
ctx.item("magic_wand").texture("magic_wand").durability(32).tooltip("Turns stone into gold")
   .onUse((game, stack, block) -> {               // block = блок под прицелом или null
       if (block == null || !block.block().name.contains("stone")) return false;
       block.setBlock(game.block("gold_block"), 0);
       if (stack.damageItem(1)) game.setHeldItem(null);
       return true;
   }).register();
ctx.item("steel_ingot").texture("steel_ingot").tab("misc").register();
ctx.item("pizza").texture("pizza").food(8, 0.8f).maxStack(16).register();
ctx.item("coal_pellet").texture("pellet").fuel(400).register();
ctx.item("steel_sword").texture("steel_sword").tool(Item.ToolType.SWORD, 2, 1, 900).attackDamage(7).register();
```

**Предметы в руках NPC** *(1.9)*. Предмет с `throwsProjectile(...)` или `shootsProjectile(...)` NPC использует сам: агрессивный (или защищающий игрока) NPC, увидев цель на расстоянии от 3 блоков до дальности предмета, бросает/стреляет вместо удара. `npcUse(дальность, кулдаун)` меняет дальность и паузу между использованиями, а `npcUse(дальность, кулдаун, handler)` — своё действие, например огнестрел:

```java
ctx.item("pistol").texture("pistol").shootsProjectile("mymod:bullet", 3.5f, null, 8)
   .npcUse(30, 12, (game, npc, x, y, z, tx, ty, tz) -> {        // откуда (глаза NPC) и куда (цель)
       game.shoot("mymod:bullet", x, y, z, tx - x, ty - y, tz - z, 3.5f);
       // здесь же — своя вспышка, отдача, звук (через события / патчи)
   }).register();
```

Снаряд, выпущенный NPC, не задевает его самого, а урон засчитывается этому NPC (стража и фракции реагируют правильно).

**Инструменты** *(1.14)*. `tool(тип, уровень, скорость, прочность)` — уровень 0 дерево/золото, 1 камень, 2 железо, 3 алмаз; он проверяется для любого типа, не только для кирки: блок с `.tool(Item.ToolType.AXE, 2, true)` добывается только железным топором и лучше. Мультиинструмент (paxel) — `alsoTool(...)`: скорость и добыча берутся по лучшему подходящему типу, работают и действия по ПКМ (вспашка мотыгой, стрижка ножницами).

```java
ctx.item("steel_paxel").texture("steel_paxel")
   .tool(Item.ToolType.PICKAXE, 2, 7, 1200).alsoTool(Item.ToolType.AXE, Item.ToolType.SHOVEL).attackDamage(5).register();
```

Прочность тратится как в 1.12: инструмент −1 за блок, меч −2 за блок и −1 за удар, другие инструменты −2 за удар, ножницы — только на листве, паутине, шерсти, лозе и траве. Предметы-не-инструменты с прочностью (лук, огниво, броня) от копания и ударов больше не изнашиваются. Блок с `requiresTool`, но без типа инструмента, добывается любым инструментом. NPC с инструментом в руке ломают блоки быстрее (*Break*).

Также: `armor(слот, очки, прочность)`, `leaves("bowl")` (что остаётся после использования), `consumeOnUse()`, `hidden()`, `vanillaTexture("stick")`. Вкладка креатива, которой нет в игре, попадает в «Разное».

В рецептах можно писать теги `#planks`, `#logs`, `#wool`, `#coal`, `#wooden_slab`, `#stone_tool`, `#sand`; точка в форме — пустая клетка.
Поля `Item`: `maxStack`, `maxDamage`, `tool`/`toolLevel`/`toolSpeed`, `food`/`saturation`, `fuel` (тики горения в печи), `armorSlot`/`armorPoints`. Своё поведение по ПКМ — унаследоваться от `Item` и переопределить `use(session, stack, hit)`.

## Рабочие блоки

У `Block` есть крючки, которые можно переопределить: `onUse` (ПКМ: открыть окно, переключить), `onPlaced` / `onRemoved`, `canPlaceAt`, `randomTick` (рост), `scheduledTick` (таймеры через `world.scheduleTick`), `drops(meta, random)`, `createBlockEntity()` — данные блока (содержимое, текст), сохраняются в чанке. Свой тип данных: унаследоваться от `BlockEntity`, реализовать `save/load` (пары ключ=значение) и зарегистрировать `BlockEntity.register("mymod:safe", MySafe::new)`. Готовые окна: `session.openChest(entity, "Title")`, `openStorage`, `openFurnace`, `openCrafting`.

## GameApi — доступ к игре из обработчиков

`getBlock / getMeta / setBlock(x, y, z, block[, meta])`, `fill(...)` (быстро, с отменой через `//undo`), `block("name")`, `playerX/Y/Z`, `playerYaw/Pitch`, `teleport`, `setRotation`, `time / setTime`, `chat("§aцветной текст")`, `runCommand("time set night")`.

С версии 1.3 ещё:

| Метод | Что делает |
|---|---|
| `camera()` → `CameraApi` | камера для съёмки (ниже) |
| `npcs()` → `NpcApi` | NPC, запись актёров и сцены (ниже) *(1.4)* |
| `graphics()` → `GraphicsApi` | тени, RTX, цветной свет, цветокоррекция (ниже) |
| `view() / setView(0..2)` | первое лицо / сзади / спереди (F5) |
| `hudHidden() / setHudHidden(bool)` | F1 |
| `gameMode() / setGameMode("survival")` | режим игры |
| `dropItem(x, y, z, "diamond", count)` | выбросить предмет в мир |
| `setItemPhysics(bool)`, `setClickPickup(bool)` | опции из *Gameplay* |
| `swingArm()` | взмах рукой |
| `screenshot()` | скриншот как F2 |

С версии 1.5:

| Метод | Что делает |
|---|---|
| `schedule(тиков, задача)` | выполнить позже в игровом потоке |
| `worldData("mymod")` | сохраняемые данные мира для мода |
| `blockAt(x, y, z)` → `BlockContext` | любой блок как контекст (данные, слоты, текст) |
| `blockData(x, y, z)` | данные мод-блока или `null` |
| `inventoryAt(x, y, z)` | слоты **любого** контейнера: сундук, печь, воронка, мод-хранилища и машины — для своих труб, насосов, сортировщиков |
| `createInventory(n)`, `playerInventory()` | |
| `openMenu(menu)` | своё окно |
| `heldItem()`, `setHeldItem(stack)`, `giveItem(stack)`, `createItem("diamond", 3)`, `playerSneaking()`, `pushPlayer(dx, dy, dz)` | игрок |

С версии 1.7:

| Метод | Что делает |
|---|---|
| `creatures()`, `creaturesNear(x, y, z, r)`, `targetedCreature()` | существа мира (`Creature`) |
| `spawnCreature("mymod:ghoul", x, y, z)` | поставить существо |
| `setCreatureSpawning(bool)` | естественное появление существ в этом мире |
| `shoot("mymod:bomb", x, y, z, dx, dy, dz, скорость)`, `shootFromPlayer("mymod:bomb", скорость)` | снаряды |
| `explode(x, y, z, сила, ломатьБлоки)` | взрыв (TNT = 4) |
| `hurtPlayer(урон, "причина")`, `playerHealth()`, `setPlayerHealth(hp)` | здоровье игрока |
| `isDay()`, `lightAt(x, y, z)` | день / ночь, освещённость 0..15 |

Все обработчики вызываются в игровом потоке, синхронизация не нужна.

## Изменение кода игры (патчи) *(1.7)*

Если для задачи нет API — мод меняет сам код игры, ничего не переписывая. Игра запускается через `derpcraft.Launch` (это делает `Play.bat`): классы игры загружаются особым загрузчиком, и перед загрузкой в них вставляется код модов. Так работает Mixin в Fabric и Forge. Здесь своя маленькая система на библиотеке ASM, внутри игры.

```java
@Override public void patches(Patcher p) {
    // в начало метода: поменять аргумент (голод в 2 раза медленнее)
    p.head("derpcraft.game.PlayerStats", "addExhaustion", c -> c.setArg(0, c.argFloat(0) * 0.5f));

    // в начало метода: отменить его (вернуть свой результат)
    p.head("derpcraft.game.PlayerStats", "damage", c -> { if ("fall".equals(c.arg(1))) c.cancel(false); });

    // перед каждым return: прочитать / заменить результат
    p.tail("derpcraft.game.PlayerStats", "needsFood", c -> c.setReturn(true));

    // заменить метод целиком
    p.replace("derpcraft.entity.Mob", "isDay", c -> c.setReturn(false));      // монстры думают, что всегда ночь

    // перед вызовом другого метода внутри метода: поменять его аргументы или пропустить вызов
    p.beforeCall("derpcraft.game.Session", "tick", "Session", "randomTicks", c -> c.cancel());

    // заменить число или строку в коде метода
    p.constant("derpcraft.game.PlayerStats", "damage", 20, 5);     // неуязвимость после удара 5 тиков вместо 20
}
```

| Метод `Patcher` | Когда срабатывает хук | Что может хук (`Call c`) |
|---|---|---|
| `head(класс, метод, хук)` | в начале метода | `c.arg(i)`, `c.setArg(i, v)`, `c.cancel()` / `c.cancel(результат)` |
| `tail(класс, метод, хук)` | перед каждым `return` | `c.returnValue()`, `c.setReturn(v)` |
| `replace(класс, метод, хук)` | вместо метода | `c.setReturn(v)` |
| `beforeCall(класс, метод, классВызова, методВызова, хук)` | перед вызовом внутри метода | `c.target()` — у кого вызывают, аргументы вызова, `c.cancel(результат)` — пропустить вызов |
| `constant(класс, метод, было, стало)` | при загрузке | заменяет `int / long / float / double / String` |

- Метод указывается именем (`"tick"` — все перегрузки) или с типами параметров: `"setBlock(int,int,int,Block,int)"`. Конструктор — `"<init>"` (только `tail`, `beforeCall`, `constant`).
- В хуке: `c.self()` — объект, чей метод выполняется (`c.self(Player.class)`), `c.argInt(i) / argFloat / argDouble / argBool`, а также `c.field("имя")`, `c.setField("имя", v)`, `c.invoke("метод", ...)` — доступ даже к приватным полям и методам. Для любых объектов есть `Call.get(obj, "поле")`, `Call.set(...)`, `Call.call(...)`.
- Исходники игры лежат в `src\main\java` — по ним видно, что и где патчить.
- В `patches()` нельзя трогать классы игры — только регистрировать патчи (классы должны загрузиться уже изменёнными). Ошибка в хуке пишется в лог и не роняет игру; патч, который ничего не нашёл (опечатка в имени), — тоже в лог и в список ошибок модов.
- Если запустить игру не через `Launch` (например, напрямую `derpcraft.DerpCraft`), патчи не применятся, и игра об этом скажет.

## События *(1.7)*

Любое событие из `Events` слушается одной строкой. Если у события есть `cancel()`, его можно отменить; поля без `final` можно менять.

```java
ctx.on(Events.PlayerDamage.class, (game, e) -> { if (e.cause.equals("fall")) e.amount /= 2; });
ctx.on(Events.EntityDeath.class, (game, e) -> { if (e.creature != null) e.drops.add(game.createItem("diamond", 1)); });
ctx.on(Events.KeyPress.class, (game, e) -> { if (e.key == 71) { game.explode(game.playerX(), game.playerY(), game.playerZ() + 5, 3, true); e.cancel(); } });
```

| Событие | Когда | Что можно |
|---|---|---|
| `PlayerDamage` | игрок получает урон (`cause`: fall, lava, fire, drown, starve, cactus, void, suffocate, npc, mob, explosion, projectile) | изменить `amount`, отменить |
| `PlayerDeath` | игрок умер | изменить `message` |
| `PlayerRespawn` | игрок возродился | |
| `EntityDamage` | урон существу (`creature`) или NPC (`npc`); кто бил — `byPlayer`, `attacker`, `attackerNpc` | изменить `amount`, отменить |
| `EntityDeath` | смерть существа или NPC | изменить `drops`, `xp` |
| `CreatureSpawn` | появление существа (`natural` — само по себе) | отменить |
| `Explosion` | взрыв | изменить `power`, список `blocks`, `breakBlocks`, отменить |
| `ProjectileHit` | снаряд попал в блок / существо / NPC / игрока | отменить (летит дальше) |
| `KeyPress`, `MouseClick` | клавиша / кнопка мыши в игре (физическая, до переназначения клавиш) | отменить (игра не отреагирует) |
| `ScreenOpen` | открывается окно (`name`: Inventory, NpcHub, Pause…) | отменить |
| `ItemPickup` | игрок подбирает предмет | отменить |
| `CommandRun` | перед любой командой | отменить |
| `WorldClose` | мир сохраняется и закрывается | |
| `BlockPlace`, `BlockBreak`, `BlockInteract`, `ItemUse`, `Chat` | как `onBlockPlace` и т.д. | отменить / изменить |

## Существа *(1.7)*

```java
ctx.registerMob("ghoul").model(Model.humanoid().armsForward()).texture("ghoul")
   .hostile().health(20).damage(3).speed(0.9f).burnsInSun().opensDoors()
   .drops("rotten_flesh", 0, 2).xp(5)
   .spawnsNaturally("dark", 1, 3)          // ночью и в темноте, группами 1–3
   .spawnEgg(0x3f7a4a)                     // яйцо призыва "mymod:ghoul_spawn_egg"
   .register();
```

| `MobBuilder` | |
|---|---|
| `model(Model)`, `texture("name")`, `size(ширина, высота)`, `scale(k)` | вид; текстура `assets/<modid>/textures/entity/<name>.png` |
| `health`, `damage`, `speed` (1 = шаг игрока), `reach`, `attackCooldown`, `knockbackResistance` | характеристики |
| `hostile()` / `neutral()` / `passive()`, `aggroRange(блоков)`, `fleesWhenHurt()` | нападает на игрока (в выживании) / отвечает на удар / убегает |
| `shoots("mymod:arrow", скорость, интервал, дальность)` | стреляет вместо удара |
| `temptedBy("carrot")`, `noWander()`, `noLookAtPlayer()`, `opensDoors()`, `burnsInSun()`, `fireproof()`, `sinks()` | поведение |
| `drops(предмет, мин, макс[, шанс])`, `xp(n)` | добыча |
| `spawnsNaturally("dark" / "light" / "any", мин, макс, блоки...)`, `spawnLimit(сколько, шанс)`, `spawnsUnderground()` | естественное появление рядом с игроком |
| `spawnEgg(цвет)` | яйцо призыва |
| `onTick`, `onHurt`, `onDeath`, `onInteract` (ПКМ) | свой код |

**Модель** — как `ModelRenderer` в Minecraft 1.12: части с точкой вращения и коробками, координаты в пикселях, ось y вниз, земля на y = 24. Модели из Blockbench («Modded Entity») и старых модов переносятся число в число. Готовые: `Model.humanoid()` (любой скин игрока), `Model.quadruped(6)` (как свинья, `pig.png`), `Model.quadruped(12)` (как корова), `Model.cube(8)`. Анимации частей: `HEAD` (смотрит), `LEG_A / LEG_B`, `ARM_A / ARM_B` (качаются при ходьбе в противофазе), `TAIL`, `WING_L / WING_R`, `BODY`.

```java
Model crab = Model.create(64, 32)
    .part("body", 0, 19, 0).box(-5, -3, -4, 10, 5, 8, 0, 0)
    .part("claw_right", -6, 18, -3).anim(Model.ARM_A).box(-2, -1, -3, 3, 3, 4, 36, 0)
    .part("legs_right", -4, 21, 0).anim(Model.LEG_A).box(-3, 0, -3, 2, 3, 6, 0, 16);
```

Существа ходят с поиском пути, плавают, получают урон от падения, лавы, огня, горят на солнце, отлетают от ударов (критический удар в прыжке ×1.5), умирают с анимацией. Сохраняются в мире (`mobs.txt`); если мод удалить, его существа не пропадают из файла и вернутся вместе с модом. **NPC и существа:** враждебные существа нападают на NPC рядом, NPC со стражей *defends you* защищают игрока и от существ. Команды: `/mob summon <тип> [кол-во]` (или `/summon`), `/mob list`, `/mob kill [тип|all]`, `/mob types`, `/mob spawning on|off`, `/mob explode [сила]`.

`Creature` (из событий и `game.creatures()`): `type()`, `x()/y()/z()`, `health() / setHealth`, `hurt`, `kill`, `remove`, `teleport`, `push`, `customName / setCustomName`, `attackPlayer()`, `attack(другое)`, `calm()`, `walkTo(x, y, z)`, `data()` (сохраняется), `persistent()`.

## Снаряды и взрывы *(1.7)*

```java
ctx.registerProjectile("bomb").item("mymod:bomb").gravity(0.04f).spins().bounces(0.45f, 50).explodes(3, true).register();
ctx.registerProjectile("pebble").item("cobblestone").size(0.12f).damage(4)
   .onHit((game, hit) -> { if (hit.creature != null) hit.creature.push(0, 0.4, 0); }).register();

// в initItems: предмет, который бросают (как снежок) или которым стреляют
ctx.item("bomb").texture("bomb").throwsProjectile("mymod:bomb", 0.9f).register();
ctx.item("slingshot").texture("slingshot").shootsProjectile("mymod:pebble", 1.6f, "cobblestone", 8).register();
```

`ProjectileBuilder`: `item(вид)` или `model(модель, текстура)`, `size`, `gravity`, `drag`, `damage`, `knockback`, `ignites(тиков)`, `explodes(сила, ломать)`, `bounces(упругость, fuse)`, `dropsOnLand(предмет)`, `spins()`, `onHit`, `onTick`. Взрыв — `game.explode(x, y, z, сила, ломатьБлоки)`: лучи как в Minecraft, блоки ломаются по прочности, существа, NPC и игрок получают урон и отлетают.

## Генерация мира *(1.7)*

```java
ctx.addOre("mymod:ruby_ore", "stone", 5, 2, 4, 32);       // жилы до 5 блоков, 2 на чанк, высота 4..32
ctx.onChunkGenerate(chunk -> {                              // любая своя генерация, на каждый новый чанк
    var r = chunk.random();
    if (r.nextInt(20) != 0) return;
    int x = r.nextInt(16), z = r.nextInt(16), y = chunk.topY(x, z) + 1;
    for (int i = 0; i < 4; i++) chunk.setBlock(x, y + i, z, chunk.block("cobblestone"));
});
```

Обработчик работает в рабочих потоках генерации: трогать можно только этот чанк (`ChunkData`: локальные координаты 0..15, `getBlock / setBlock / topY / random() / seed()`).

## Ядро и производительность *(1.5)*

- **Зависимости модов**: `String[] dependencies()` в `DerpMod` — моды загружаются в правильном порядке, при нехватке зависимости мод пропускается с сообщением.
- **Ноль накладных расходов для ванилы**: функции мод-блоков живут в отдельном классе `ModBlock`; обычные блоки их не проверяют. События вызываются только если на них кто-то подписан.
- **Тикают только нужные блоки**: машины и блоки с `onTick`. Хранилища, таблички и блоки с данными не тикают вовсе.
- Тип данных блока кэшируется — мир больше не создаёт временный объект на каждый блок каждый тик (это ускорило и ванильные сундуки и печи).
- Текст табличек и экранов рисуется только для видимых блоков рядом с камерой, а сама модель — частью меша чанка.
- Ошибка в обработчике мода пишется в консоль и не роняет игру.

## CameraApi — камера из мода *(1.3)*

Та же камера, что и в панели F9. Расстояния — в блоках (= метрах), углы — в градусах, фокусное — в мм. Плавные величины (наклон, зум, фокус) сами доезжают до заданного значения; варианты `...Now` ставят сразу.

| Группа | Методы |
|---|---|
| Режим | `mode()` → `"player"`, `"free"`, `"orbit"`, `"path"`; `playerView()`, `freeCamera()`, `freeCamera(x, y, z, yaw, pitch)` |
| Позиция | `x() y() z() yaw() pitch()` (текущая камера в любом режиме); `setPosition`, `setRotation`, `lookAt(x, y, z)`, `setFlySpeed(блоков/с, плавность)` |
| Орбита | `orbit(x, y, z, радиус, высота, градусов/с)`, `orbitPlayer(радиус, высота, градусов/с)` (центр следует за игроком) |
| Штатив | `setTripod(true)` — камера стоит, игрок управляется |
| Объектив | `focalLength()`, `setFocalLength(мм)` (0 = FOV из настроек), `setFocalLengthNow`, `roll()`, `setRoll`, `setRollNow`, `setAnamorphic(1..2)`, `setLetterbox(2.39f)` (0 = нет), `setDistortion(-1..1)`, `setChromaticAberration(0..1)`, `setShake(сила, скорость)` |
| Фокус | `setDepthOfField(bool)`, `setAperture(f)`, `setAutofocus("center" / "player" / "manual")`, `setAutofocusSpeed`, `setFocusDistance(блоков)`, `focusDistance()`, `setBokehScale` |
| Эффекты | `setFlares(сила, длина, "blue" / "amber" / "neutral" / "purple")`, `setGhosts`, `setMotionBlur(0..1)`, `setGrid(bool)` |
| Пути | `addKeyframe()` (текущая камера), `addKeyframe(x, y, z, yaw, pitch, roll, мм, фокус)`, `keyframeCount()`, `removeLastKeyframe()`, `clearKeyframes()`, `playPath(секунд, повтор, "linear" / "smooth" / "in" / "out", равномерно)`, `stopPath()`, `pathPlaying()`, `pathProgress()`, `samplePath(t)` |
| Сохранение | `save()` — настройки камеры и путь текущего мира |

```java
// /dolly: проезд камеры на 10 блоков вперёд за 6 секунд
ctx.addCommand("dolly", "dolly shot", (game, a) -> {
    CameraApi cam = game.camera();
    double yr = Math.toRadians(cam.yaw());
    cam.clearKeyframes();
    cam.addKeyframe(cam.x(), cam.y(), cam.z(), cam.yaw(), cam.pitch(), 0, 0, 0);
    cam.addKeyframe(cam.x() - Math.sin(yr) * 10, cam.y(), cam.z() + Math.cos(yr) * 10, cam.yaw(), cam.pitch(), 0, 0, 0);
    cam.playPath(6, false, "smooth", true);
});
ctx.onCameraPathEnd(game -> { game.chat("Cut!"); game.camera().playerView(); });

// плавный наезд зумом — каждый кадр, а не 20 раз в секунду
ctx.onFrame((game, dt) -> {
    CameraApi cam = game.camera();
    if (zooming) cam.setFocalLengthNow(cam.focalLength() * (float) Math.exp(dt * 0.25));
});
```

## NpcApi — NPC, актёры, сцены и фракции *(1.4, 1.6, 1.8, 1.9, 1.12)*

NPC адресуются по имени. Скины: `"Steve"`, встроенные образы (`Alex`, `Suit`, `Worker`, `Doctor`, `Police`, `Red Hoodie`, `Farmer`, `Knight`, `Villain`, `Kid`) и файлы из `game\skins\` (имя без `.png`).

| Методы | Что делает |
|---|---|
| `list()`, `exists(name)` | все NPC мира |
| `spawn(name, x, y, z, yaw)` → имя | поставить NPC (если имя занято — добавится номер) |
| `remove(name)`, `teleport(name, x, y, z, yaw)`, `position(name)` | |
| `skins()`, `setSkin(name, skin)` | скины |
| `setBehavior(name, "stand" / "look" / "wander" / "follow")` | поведение |
| `setPose(name, "normal" / "sit" / "sneak" / "wave")` | поза |
| `setHeldItem(name, "diamond_sword")`, `setNameTag(name, bool)` | предмет в руке, имя над головой |
| `say(name, text)` | реплика в чат «<name> text» |
| `startRecording(name)`, `stopRecording()`, `recording()`, `takeSeconds(name)` | запись игрока как актёра |
| `playScene()`, `stopScene()`, `scenePlaying()`, `resetScene()`, `sceneTime()`, `sceneLength()` | сцена из всех дублей |
| `setSceneLoop(bool)`, `setSceneCamera(bool)` | повтор, запуск пути камеры вместе со сценой |
| *1.6:* `currentScene()`, `setCurrentScene(scene)`, `scenes()`, `scenesOf(name)`, `takeSeconds(name, scene)` | именованные сцены |
| *1.6:* `playScene(scene)`, `stopScene(scene)`, `scenePlaying(scene)`, `setWalkToMarks(bool)` | актёры идут к стартовым точкам, играют, потом возвращаются к своему ИИ |
| *1.6:* `setFaction(name, "aggressive" / "neutral" / "friendly")`, `faction(name)`, `setFactionAttacks(a, b, bool)`, `setAttackCreative(bool)` | фракции и кто на кого нападает |
| *1.6:* `setAggroRange(name, blocks)`, `setOnHit(name, "fight" / "flee")`, `setGuard(name, "ignore" / "defend" / "flee")` | реакции |
| *1.6:* `setFollowDistance(name, start, stop)`, `setAbilities(name, doors, breakBlocks)` | следование, двери, ломание блоков |
| *1.6:* `setHealth`, `health`, `setDamage`, `setRespawn`, `hurt`, `kill`, `revive`, `dead`, `attack(name, target)` | бой (`attack(name, null)` — на игрока) |
| *1.8:* `groups()`, `groupMembers(g)`, `createGroup(name)`, `copyGroup(g, withTakes)`, `renameGroup`, `deleteGroup`, `groupOf(name)`, `moveToGroup(name, g)` | группы сцен (группа = сцена со своими NPC) |
| *1.8:* `setGroupEnabled(g, bool)`, `setEnabled(name, bool)`, `enabled(name)`, `setSceneEnabled(name, bool)` | убрать из мира / вернуть; не участвовать в сцене |
| *1.8:* `playScene(scene, List.of("Bob", "Alice"))` | сцена только с некоторыми NPC |
| *1.9:* `setPose(name, "Sit" / "Bow" / своя поза)`, `poses()` | позы из библиотеки (встроенные и из редактора поз, `game\poses\`) |
| *1.9:* `setEmotion(name, "happy" / "angry" / "sad" / "surprised" / "scared" / "eyes closed" / "normal")` | лицо |
| *1.9:* `setOffhandItem(name, item)`, `setArmor(name, slot 0..3, item)`, `setScale(name, size)` | левая рука, броня/шапка, размер |
| *1.9:* `setBehavior(name, "patrol" / "seat" / "watch" / "ride")`, `addWaypoint`, `clearWaypoints`, `setSeat(name, x, y, z)`, `setRide(name, target)` | патруль, сесть, ехать на другом NPC |
| *1.14:* `setBehavior(name, "drive")` | сесть в ближайшую машину (48 блоков) и ехать: по точкам `addWaypoint`, без точек — за игроком |
| *1.9:* `takeCount(name, scene)`, `setActiveTake(name, scene, i)`, `setTakeTiming(name, scene, offsetTicks, speed)` | дубли и таймлайн |
| *1.9:* `addPoseKey(name, scene, tick, pose, curve, blendTicks)`, `playSceneFrom(scene, tick)` | ключи поз (`linear` / `smooth` / `ease in` / `ease out` / `instant`), запуск с середины |
| *1.12:* `models3d()`, `addModel(name, model, part, x, y, z, turnX, turnY, turnZ, size)`, `clearModels(name)` | 3D-модели из `game/3d` на частях тела (`head`, `body`, `right arm`, `left arm`, `right leg`, `left leg`), сдвиг в пикселях (x вправо, y вверх, z вперёд) |

```java
// массовка: 8 случайных прохожих вокруг игрока
NpcApi npcs = game.npcs();
for (int i = 0; i < 8; i++) {
    String n = npcs.spawn("Extra", game.playerX() + i - 4, game.playerY(), game.playerZ() + 6, 180);
    npcs.setSkin(n, npcs.skins().get(i % npcs.skins().size()));
    npcs.setBehavior(n, "wander");
    npcs.setNameTag(n, false);
}
ctx.onSceneEnd(g -> g.chat("Cut!"));

// 1.6: банда нападает на деревню, стража защищает игрока
npcs.setFactionAttacks("aggressive", "neutral", true);
String boss = npcs.spawn("Bandit", x, y, z, 0);
npcs.setFaction(boss, "aggressive"); npcs.setAggroRange(boss, 12); npcs.setHeldItem(boss, "iron_sword");
String guard = npcs.spawn("Guard", x + 4, y, z, 180);
npcs.setFaction(guard, "friendly"); npcs.setGuard(guard, "defend"); npcs.setHealth(guard, 40);
npcs.playScene("ambush");   // все с дублем "ambush" идут на места и играют
```

```java
// 1.9: сидит, грустит, через 2 с встаёт и кланяется
npcs.setPose("Bob", "Sit"); npcs.setEmotion("Bob", "sad");
npcs.addPoseKey("Bob", "finale", 40, "Bow", "smooth", 15);
npcs.setTakeTiming("Alice", "finale", 20, 1.0f);   // Alice входит на секунду позже
npcs.playSceneFrom("finale", 0);
```

Блок сцены (`minecraft:scene_block`) хранит настройки в данных блока: `scene`, `trigger` (`click` / `near` / `npc` / `lever`), `who` (для `npc`: `cast`, `any` или id NPC), `radius`, `repeat` (по умолчанию true) — их можно задать из мода через `game.blockAt(x, y, z).data()`.

## GraphicsApi — шейдеры и цветокоррекция *(1.3)*

| Методы | Что делает |
|---|---|
| `shadows() / setShadows(0..4)` | тени: выкл, Low … Ultra |
| `rayTracing() / setRayTracing(bool)`, `setRayTracingQuality(0..2)` | RTX |
| `coloredLight() / setColoredLight(bool)` | цветной свет |
| `cinematicPipeline() / setCinematicPipeline(bool)` | HDR, bloom, цветокоррекция |
| `wavingPlants() / setWavingPlants(bool, ветер)` | колышущаяся листва и трава *(1.14)*, сила ветра 0–3 (1 — по умолчанию) |
| `waterWaves() / setWaterWaves(bool, высота)` | волны, отражение неба и блики на воде *(1.14)* |
| `godRays() / setGodRays(bool, сила)` | лучи солнца; с тенями — объёмный свет *(1.14)* |
| `shaderPresets()`, `setTransition(fromShader, fromColor, toShader, toColor, секунды)`, `toggleTransition()`, `transitionRunning()` | переходы между пресетами шейдеров и цвета (как Left Alt + ,) *(1.14)* |
| `reflections() / setReflections(0..2, сила)` | отражения: 0 выкл, 1 вода, 2 вода + глянцевые блоки (сила блочных) *(1.14)* |
| `gradingPresets()`, `loadGradingPreset(name)`, `gradingName()` | пресеты (встроенные, сохранённые и из модов) |
| `grading(key)`, `setGrading(key, value)` | одно значение: `exposure`, `temperature`, `tint`, `tonemap` (0 none, 1 cinematic, 2 filmic, 3 reinhard, 4 aces), `contrast`, `saturation`, `vibrance`, `lift`, `gamma`, `gain`, `shadowHue`, `shadowAmount`, `highlightHue`, `highlightAmount`, `bloom`, `bloomThreshold`, `emissive`, `vignette`, `grain`, `lutStrength` |
| `setLut("film.cube")` | LUT из `game\luts\` (`""` — без LUT) |
| `gradingText()`, `applyGrading(text)` | вся цветокоррекция в формате файла пресета |

```java
// плавно «остудить» картинку к ночи (nightLook включается своей командой или клавишей)
ctx.onFrame((game, dt) -> {
    GraphicsApi g = game.graphics();
    float temp = g.grading("temperature");
    if (nightLook && temp < 9500) g.setGrading("temperature", Math.min(9500, temp + dt * 1000));
});
```

Пресет цветокоррекции из jar мода — обычный текстовый файл, как в `game\grading\`:
```
tonemap=2
temperature=5200
saturation=0.9
vignette=0.35
```

## Пример: Creatures *(1.7)*

`examples\Creatures` — всё новое из 1.7: упырь (враждебный, горит на солнце, приходит ночью), бандит (стреляет камнями издалека), кабан (убегает, идёт за морковкой, дропает свинину), краб (своя модель по частям, нейтральный, живёт на пляжах), бомба (бросается, отскакивает и взрывается), рогатка (стреляет булыжником), рубиновая руда в новых чанках, события (перо в руке отменяет урон от падения, с упырей иногда падает рубин, сообщение о взрыве), команда `/ghouls [n]` и патч кода игры: голод тратится в 2 раза медленнее.

## Пример: ModernBlocks *(1.5)*

`examples\ModernBlocks` — мод только на новом API, без единого наследования:
- **Crate** (5 слотов), **Cabinet** (12), **Locker** (30) — хранилища разных размеров;
- **Suitcase** (9, своя модель) и **Safe** (27, замок по Shift + ПКМ) — постоянные: ломаешь, содержимое в предмете;
- **Street Sign** — современная табличка на стену или на столбик, **LED Screen** — светящийся текст;
- **Grinder**, **Alloy Furnace** (два входа, сталь), **Solar Oven** (без топлива) — машины со своими рецептами и подсветкой;
- **Trampoline** (`onStep`), **Vending Machine** (своё окно `Menu`, счётчик продаж в данных блока);
- предметы **Steel Ingot** и **Magic Wand** (`ItemBuilder.onUse`), рецепты крафта.

Собрать: `examples\ModernBlocks\build-mod.bat` (после `build.bat`).

## Пример: CineMod

В папке `examples\CineMod` лежит готовый мод с исходниками, который использует новое API:
- `/dolly [секунд] [блоков]` — проезд вперёд, `/crane [секунд]` — подъём камеры с наклоном вниз, в конце — «Cut!» и возврат к игроку;
- `/orbitme [радиус]` — орбита вокруг игрока в режиме штатива (можно ходить, камера следует);
- `/look <пресет>` и `/look list` — цветокоррекция; мод добавляет пресеты *Cine Dusk* (из файла в jar) и *Cine Bleach*;
- **H** — «документальный» объектив: 50 мм, f/2, автофокус на игрока, лёгкие блики и тряска;
- **N** — медленный наезд зумом до 120 мм (каждый кадр через `onFrame`);
- `/extras [кол-во]` — массовка из бродящих NPC в случайных образах, `/action` — сцена NPC вместе с путём камеры, в конце «Cut!».

Собрать: сначала игра (`build.bat`), потом `examples\CineMod\build-mod.bat` — jar сам окажется в `game\mods\`.

## Как собрать мод

Структура jar:
```
mymod.jar
├── mymod/MyMod.class
├── META-INF/services/derpcraft.api.DerpMod      ← одна строка: mymod.MyMod
└── assets/mymod/textures/blocks/ruby_block.png ← текстуры (любое разрешение, .mcmeta-анимации тоже работают)
```

Пример `build.gradle` для отдельного проекта мода (рядом с папкой DerpCraft):
```groovy
plugins { id 'java' }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
dependencies {
    compileOnly fileTree('../DerpCraft/build/install/DerpCraft/lib') { include 'DerpCraft-*.jar' }
}
```
Проще всего скопировать `examples\CineMod` и переименовать. `gradlew jar`, затем положи jar в `DerpCraft\game\mods\` и перезапусти игру. Загруженные моды видны на титульном экране (*Mods: N*), ошибки загрузки выводятся в консоль.
