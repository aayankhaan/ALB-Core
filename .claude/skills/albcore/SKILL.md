---
name: albcore
description: ALBCore API and conventions for Skyabyss plugins on Paper 26.3 / Java 25 (Kotlin). Use when writing or changing code in ALBCore or in any plugin built on it (ALBLevel, ALBSkills, other ALB plugins). Covers GUIs, commands, database tables and migrations, player data, PlaceholderAPI/Vault hooks, item/colour/number utilities, threading rules, and the Gradle/plugin.yml setup for child plugins.
---

# ALBCore

ALBCore is the base plugin for every ALB plugin on the Skyabyss server (dungeon + skyblock, Java and
Bedrock players through Geyser/Floodgate). Child plugins depend on it and use its API instead of
writing their own GUI, command, database or player-data code.

- Repo: `Skyabyss/ALB-Core` (git, https://github.com/aayankhaan/ALB-Core). Kotlin in `src/main/kotlin/com/aayan/albcore`.
- Version 1.3.0. Paper API `io.papermc.paper:paper-api:26.3.build.252-stable`, Java 25, Kotlin 2.4.21, Gradle 9.9.1 wrapper.
- Human docs: `docs/index.html`.
- Project design (wins over the old `Cloode/` docs): `Skyabyss/DECISIONS.md`, section 4 lists the plugins and how they connect.

Planned child plugins: ALBSkills, ALBItems, ALBDungeon, ALBQuests, ALBIslands, ALBChat, ALBRewards. All share
one MySQL database (eco plugins use the same server with their own tables).

Planned for ALBCore itself (add them here, never as private copies in a child plugin): coins as the Vault
economy provider, the NPC system (entity + optional ModelEngine model + click handler), Bedrock forms
(Floodgate) for confirms and short lists, and an event bus between ALB plugins.

## Child plugin setup

`build.gradle.kts`:
```kotlin
plugins { kotlin("jvm") version "2.4.21" }
repositories {
    mavenLocal()   // ALB-Core: ./gradlew publishToMavenLocal
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.252-stable")
    compileOnly("com.aayan:alb-core:1.3.0")
    compileOnly(kotlin("stdlib"))   // ALBCore ships the Kotlin runtime: never shade Kotlin in a child plugin
}
kotlin { jvmToolchain(25) }
```
`gradle.properties`: `kotlin.stdlib.default.dependency=false`

`plugin.yml`: `api-version: '26.3'`, `depend: [ALBCore]`. Do not declare commands in plugin.yml (use `CommandUtil`).

`onEnable` must start with this check, **before** touching any ALBCore class. If ALBCore failed to
start (e.g. database unreachable) it disables itself and its classes can no longer be loaded, so
`ALBCore.instance` would throw `NoClassDefFoundError`:
```kotlin
if (!server.pluginManager.isPluginEnabled("ALBCore")) {
    logger.severe("ALBCore is not running (database error?). Disabling.")
    server.pluginManager.disablePlugin(this)
    return
}
val core = ALBCore.instance
```

## Hard rules

1. **No database work on the main thread.** Every `Database` method except `migrate` returns a
   `CompletableFuture` and runs on ALBCore's DB threads. Never call `.join()`/`.get()` on the main
   thread. Use `future.thenSync(plugin) { }` (from `com.aayan.albcore.utils`) to touch players,
   worlds, inventories or the Bukkit API with the result.
2. **`db.migrate(...)` only in `onEnable`** (it blocks). Never edit or renumber a shipped migration
   version; add a new version. On MySQL keep one DDL statement per version (DDL auto-commits).
3. **Table names always through `db.table("name")`** (or `table("name")` inside a migration). They add
   the configured prefix (default `alb_`). Start every name with the plugin's short name so the seven
   plugins never collide: `skills_`, `items_`, `dungeon_`, `quests_`, `islands_`, `chat_`, `rewards_`
   (e.g. `table("skills_players")` gives `alb_skills_players`). The migration owner is the plugin name.
4. **SQL must run on SQLite and MySQL.** Use `db.dialect.upsert(...)`, `dialect.autoIncrementPrimaryKey`,
   `dialect.longTextType`; store UUIDs as `CHAR(36)` (binding a `UUID` param does this); use
   `BIGINT` for epoch millis; `sqlite("...")`/`mysql("...")` in migrations for backend-only SQL.
5. **Bind values with `?` params**, never string-concatenate player input into SQL.
6. **GUIs: one `GuiBuilder` per player per open, main thread only, chest GUIs only** (Bedrock shows
   chests fine; anvil/sign/book GUIs do not translate well).
7. **Escape player-written text** with `ColorUtil.escape(text)` before `ColorUtil.parse`/`MessageUtil`,
   or players can inject MiniMessage tags (click events).
8. **Placeholder handlers (`PAPIHook.register`) run on any thread**: read cached/thread-safe data only.
9. **No credentials or webhook URLs in code or git.** They live in the server's config files.
10. Register commands, placeholders, prices and packet listeners in `onEnable`; ALBCore removes
    placeholders, prices and packet listeners automatically when your plugin disables.

## Database (`com.aayan.albcore.database`)

```kotlin
val db: Database = ALBCore.instance.database      // type: DatabaseType.SQLITE | MYSQL, dialect: SqlDialect
db.migrate("ALBLevel") {                          // owner = stable id (plugin name); returns reached version
    version(1, "Create players table") {
        execute("""CREATE TABLE ${table("level_players")} (
            uuid CHAR(36) NOT NULL PRIMARY KEY,
            level INT NOT NULL DEFAULT 1,
            xp BIGINT NOT NULL DEFAULT 0)""")
    }
    version(2, "Index level") { execute("CREATE INDEX ${table("level_players_level")} ON ${table("level_players")} (level)") }
}
```
Async API (all `CompletableFuture`):
- `update(sql, vararg params): Int` · `query(sql, vararg params) { rs -> T }: List<T>` · `queryFirst(...) { rs -> T }: T?`
- `batch(sql, rows: List<List<Any?>>): IntArray` (one transaction) · `insertAndGetKey(sql, vararg params): Long?`
- `withConnection { conn -> T }` · `transaction { conn -> T }` (commit on return, rollback on throw)
- `schemaVersion(owner): Int`
- Blocking helpers for use **inside** `withConnection`/`transaction`/migrations: `conn.update`, `conn.query`,
  `conn.queryFirst`, `conn.batch`, `conn.insertAndGetKey`, `conn.execute`, `conn.inTransaction`,
  `rs.getUuid(col)`, `rs.getIntOrNull(col)`, `rs.getLongOrNull(col)`.
- `db.dialect.upsert(table, keyColumns, valueColumns)` gives an insert-or-update with `?` for keys then values.
  `db.dialect.insertIgnore(table, columns)`.
- Param binding: `UUID` becomes its string, enums their `name`, `null` is SQL NULL.
- Failed tasks are logged by ALBCore with their SQL and complete the future exceptionally.
- SQLite runs every task on one thread in submit order. MySQL runs tasks in parallel, so do not
  assume ordering between separate calls; chain with `thenCompose` or use one `transaction`.

Typical load/save pattern for a plugin's own per-player table:
```kotlin
private val cache = ConcurrentHashMap<UUID, LevelData>()
// AsyncPlayerPreLoginEvent (async thread): blocking is OK here
val row = db.queryFirst("SELECT level, xp FROM ${db.table("level_players")} WHERE uuid = ?", uuid) {
    LevelData(it.getInt("level"), it.getLong("xp"))
}.get(10, TimeUnit.SECONDS) ?: LevelData(1, 0)
cache[uuid] = row
// PlayerQuitEvent: save async, then drop from cache
val upsert = db.dialect.upsert(db.table("level_players"), listOf("uuid"), listOf("level", "xp"))
cache.remove(uuid)?.let { db.update(upsert, uuid, it.level, it.xp) }
```
`Futures.kt`: `future.thenSync(plugin) { value -> ... }` (main thread, returns a future; skipped if the
plugin is disabled), `future.logFailure(logger, "message")`.

## Player data (`com.aayan.albcore.data`)

Small per-player values in a YAML document per player, stored in `yaml`, `sqlite` or `mysql`
(config `player-data.storage`). Loaded before join (off-thread), cached, autosaved, saved on quit.
Use it for simple values (settings, counters, flags). Use your own table for anything you need to
query across players (leaderboards, island values).
```kotlin
PlayerData.getInt(player, "skills.mining.level", 1)    // also getString/Long/Double/Boolean/StringList/get/contains
PlayerData.set(player, "settings.mute-announcements", true)   // memory only; saved in background
PlayerData.increment(player, "stats.kills")             // atomic, returns new value (Long?) ; Double overload
PlayerData.of(player)?.keys("skills")                   // PlayerDataHolder? for loaded players
PlayerData.fetch(uuid).thenSync(plugin) { holder -> }   // any player; offline copy is read-only
PlayerData.edit(uuid) { holder -> holder.set("x", 1) }  // any player, saved; lambda runs off main thread
PlayerData.save(player)                                 // force save now (future)
```
Paths are dotted YAML paths. Namespace them per plugin (`skills.*`, `level.*`). Setting `null` removes.
`set` on an unloaded player (e.g. an NPC) logs a warning and does nothing.

## GUI (`com.aayan.albcore.gui`)

Two levels: `GuiBuilder` in code, or YAML menus (`Menus`, next section) that admins can restyle.
Every ALB menu follows one theme (`GuiTheme`, look in `plugins/ALBCore/menus/common.yml`, texts in
`plugins/ALBCore/lang.yml` `gui.*`): cyan frame on the edge, header in slot 4, back/close at the bottom
centre (`GuiLayout.bottom(rows)`), previous/next page one slot left/right of it. The `alb-gui-theme`
skill in the Skyabyss folder has the full look and text rules.

```kotlin
GuiBuilder("&8ʜᴏᴍᴇꜱ", 6)
    .frame()                                       // theme panes on the edge (GuiFill.ALL = every slot); under everything else
    .header(headerItem)                            // slot 4, no action
    .list(GuiLayout.inner(6), ListLayout.CENTERED, entries = { homesOf(player) }) { viewer, home ->
        GuiItem.button(icon(home)) { click ->      // click sound, then the action; entries re-read on refresh()
            if (click.isRight) manage(click.player, home) else teleport(click.player, home)   // left click must work (Bedrock)
        }
    }
    .button(GuiLayout.bottom(6) + 3, addItem) { click -> askName(click.player) }   // GuiClick: player, type, slot, gui, page
    .pageButtons()                                 // theme previous/next, shown only when there is such a page
    .backOrClose(back)                             // theme back button, or close when back == null
    .pagedTitle()                                  // "title (2/3)" (lang gui.paged-title) when > 1 page
    .open(player)                                  // or open(player, page)
```
Lower level (still supported): `global { item(slot) { player, ctx -> GuiItem(item) { p, c -> } } }`,
`page { }` (fixed pages), `setItem(slot, { player -> item }) { player -> }`, `setItems(...)`,
`allowItemPlacement(19..25) { item -> filter }` (input slots), `onOpen`, `onClose`, `refreshEvery(ticks)`.

- Clicks: a per-player cooldown (config `gui.click-cooldown-ms`, default 100; per GUI `clickCooldown(ms)`)
  and the second half of a double-click are ignored for every action. `gui.click` / `PageContext.click`
  is the `GuiClick` being handled (`isLeft`, `isRight`, `isShift`, `isDrop` = Q).
- Sounds: `GuiSounds.click/toggled(player, on)/denied/success` (config `gui.sounds`). `button` and
  `GuiItem.button` play the click sound unless `sound = false` (toggles play `toggled` themselves).
- Theme pieces: `GuiTheme.frame()`, `corner(bottom)`, `filler(slot, rows)`, `pane(item)` (no tooltip),
  `button("back" | "close" | "previous" | "next", "page" to n)`, `pagedTitle(...)`,
  `text("status-on" | "status-off" | "choice-current", "value" to x)`, `lines("toggle-hint" | "cycle-hint" | "open-hint")`.
- `GuiLayout`: `HEADER`, `bottom(rows)`, `inner(rows)`, `isEdge`, `corners`, `pageCount`, `centered(slots, n)`.
- Page changes refresh in place; when the title shows the page (`{page}`/`{pages}` or `pagedTitle()`), the
  GUI re-opens with the new title (input-slot items kept, `onOpen`/`onClose` not run).
- `GuiBuilder.refreshOpen { player -> inTeam(player) }` redraws everyone's open ALB GUI that matches.
- `PageContext`: `currentPage`, `totalPages`, `hasNextPage`, `hasPreviousPage`, `nextPage()`,
  `previousPage()`, `goTo(i)`, `refresh()`, `click`, `gui`. `GuiBuilder`: `refresh()`, `goToPage(i)`,
  `close()`, `isOpen()`, `viewer`, `rows`, `getItems(...)`, `addItem(s)`, `clearItems(range)`.
- Items in input slots survive `refresh()`; return them to the player in `onClose` (ALBCore does not).
  All other slots are locked (shift-click, double-click, number keys and drags are handled).

## YAML menus (`com.aayan.albcore.menu`)

Each plugin keeps its menus in `plugins/<Plugin>/menus/<id>.yml` (format: header of ALBCore's
`menus/common.yml`). Built-in ones ship in the jar as `src/main/resources/menus/<id>.yml` and are
copied when missing; bump `revision:` in the jar copy when you redesign one (the server copy is then
kept as `<id>.old.yml` and replaced). Any other `.yml` an admin drops there is a custom menu.

```kotlin
val lang = Lang(this)
val menus = Menus(this, builtIn = listOf("homes"), lang = lang)          // onEnable; {prefix} comes from lang
menus.function("sethome", permission = "homes.set") { click -> askName(click.player) }   // "[function] sethome" in any menu
menus.opener("homes") { player, back -> openHomes(player, back) }        // "[open] homes" runs your code

fun openHomes(player: Player, back: ((Player) -> Unit)?) {
    val def = menus.get("homes")
    menus.open(player, def, MenuContext(
        back = back,                                                     // "[back]" target (null: close)
        placeholders = { viewer -> listOf("count" to homesOf(viewer).size, "max" to maxHomes(viewer)) },
        entries = { homesOf(player).map { home ->                        // list.slots area, paged
            MenuEntry({ viewer -> def.entry("home", home.world, ItemStack(Material.RED_BED), listOf("name" to home.name), viewer = viewer) }) { click ->
                teleport(click.player, home)
            }
        } },
        functions = mapOf("toggle-public" to MenuFunction(               // this menu's own function; glow when on
            decorate = { Decoration(glow = isPublic(it)) },
        ) { click -> togglePublic(click.player); GuiSounds.toggled(click.player, isPublic(click.player)); click.gui.refresh() }),
    ))
}
```
- Actions, run in order: `[close]`, `[back]`, `[open] <menu>` / `[open] <plugin>:<menu>`, `[next-page]`,
  `[previous-page]`, `[refresh]`, `[function] <id>`, `[player] <cmd>`, `[console] <cmd>`, `[message] <text>`,
  `[sound] <key> [volume] [pitch]`. No `[sound]` = theme click sound. Page buttons hide when there is no page.
- Item `permission:` + `locked:` (`hide: true`, or a look and actions for players without it). Several
  items may share a slot; the first one the viewer can see shows. `PLAYER_HEAD` without item-data shows
  `MenuContext.head` or the viewer's head. `item-data: "data:<base64>"` keeps exact items (Nexo).
- Placeholders `{player}`, `{prefix}`, `{page}`, `{pages}`, plus `MenuContext.placeholders`; a lore line
  that is only a list placeholder becomes one line per value. PlaceholderAPI works in names and lore.
- `menus.build(player, id, ctx)` gives the `GuiBuilder` unopened (add more, then `open`).
- Menus found at run time (add-ons): `menus.router { player, id, back -> opened }` is asked first for every
  `[open] <id>`; `menus.functionRouter { click, id, back -> keepGoing }` runs `[function] <id>` that no
  context or registered function knows (return false to stop the item's other actions).
- `MenuDef` data: `title`, `rows`, `fill`, `items`, `listSlots`, `listLayout`, `templates`, `icons`,
  `item(id, placeholders)`, `entry(template, iconKey, fallback, placeholders)`, `iconOr(key, material)`,
  `save()`, `reset()`.

### Shared menu editor (`MenuEditor`, 1.3.0)
One in-game editor for every plugin's YAML menus (the one ALBSkyblock started with). ALBCore only gives
the screens; the plugin decides who may open it (its own admin command/permission) and what it offers.
```kotlin
val editor = MenuEditor.forMenus(this, menus,
    functions = { menuId -> listOf(EditorFunction("toggle-chat", Material.LEVER, "&#7DD3FCToggle chat")) },
    exit = { openAdminHub(it) })                                          // menus list back button (null: close)
editor.list(player)                                                      // or editor.edit(player, "settings")
```
- Full control: `MenuEditor(MenuEditorSpec(plugin, all, find, tryOpen, functions, exit, custom, notOpenable))`.
  `custom = CustomMenuSupport(create, delete, commandPrefix = "/is ", commandProblem)` adds "+ New menu"
  and a command per custom menu; without it admins edit the plugin's menus only.
- Edits items (item from hand, name, lore, glow, actions, permission + locked look, copy, delete),
  rearrange, list slots, entry looks and icons, title, rows, filler, reset. ALBCore's shared look
  (`menus/common.yml`) is listed too. Every change saves to the file at once.
- Texts: ALBCore `lang.yml` `menu-editor.*`.

## Lang (`com.aayan.albcore.lang`)

```kotlin
val lang = Lang(this)                               // plugins/<Plugin>/lang.yml, copied from the jar
lang.send(player, "home.created", "name" to home.name)
lang.raw("home.title")   lang.lines("home.lore", "warps" to listOf("&7Spawn"))   lang.component(key)
```
- `{prefix}` = the file's `prefix` key. Placeholder values are escaped (single values only; list values
  are code-built lines). `{{x}}` stays the literal `{x}`. Missing keys fall back to the jar, then the key.
- A text may start with `[chat]`, `[actionbar]`, `[title]`, `[bossbar]` or `[off]` (`MessageMode`).
  `lang.titleTimes`, `bossBarSeconds`, `bossBarColor`. PlaceholderAPI works in `send` to players.
- Bump `lang-version` in the jar file when you redesign the texts: the server file is kept as
  `lang-old-<n>.yml` and replaced. New keys are added to the server file; removed keys are dropped.
- `lang.duration(ms)` uses your `time.hours` / `time.minutes` / `time.seconds` keys (`{h} {m} {s}`).
- `addSource(file, defaults)` reads some top-level sections from another file (add-ons).
  `keys`, `text`, `setText`, `setLines`, `setMode` let a plugin edit texts in game.

## Chat input (`com.aayan.albcore.input`)

"Type it in chat" questions (Bedrock-safe; never use anvil or sign input):
```kotlin
ChatInput.ask(plugin, player, prompt = "&7Type a name for the home.", onCancel = { openHomes(it, null) }) { text ->
    if (!Regex("[A-Za-z0-9_]{1,16}").matches(text)) { lang.send(player, "home.bad-name"); false }   // keep asking
    else { createHome(player, text); openHomes(player, null); true }                                // done
}
```
- Closes the open menu (`closeInventory = false` to keep it), sends the prompt and the cancel hint
  (ALBCore lang `chat-input.*`), cancels the chat message (LOWEST priority), runs the handler on the main
  thread. Cancel words (default `cancel`) and the timeout (`timeoutSeconds`, default 60) run `onCancel`.
- `cancelWords = listOf(...)` replaces ALBCore's cancel words for one question; `emptyList()` hands every
  message to the handler (for callers that handle "cancel" themselves).
- `isWaiting(player)`, `cancel(player)` (runs onCancel), `close(player)` (silent). A new question
  replaces an open one. Questions end silently on quit and when the asking plugin disables.
- The text is raw player input: `ColorUtil.escape` it before showing it, validate it before saving.
  Chat plugins must ignore cancelled chat events, or they would broadcast answers.

## Commands (`com.aayan.albcore.commands`)

```kotlin
CommandUtil.registerCommand(this, "island") {          // in onEnable; aliases/description/permission here
    aliases = mutableListOf("is")
    playerOnly = true
    action { sender, _ -> openIslandMenu(sender as Player) }        // runs when no subcommand is typed
    subcommand("invite") {
        arg("player", ArgType.PLAYER)
        action { sender, args -> invite(sender as Player, args.player("player")) }
    }
    subcommand("setlevel") {
        permission = "albl.admin"                                     // also hides it from tab completion
        arg("target", ArgType.OFFLINE_PLAYER)
        arg("level", ArgType.INT) { validate({ _, v -> v in 1..100 }) { MessageUtil.send(it, "&c1-100") } }
        arg("reason", ArgType.TEXT) { optional = true }               // TEXT = rest of the line, must be last
        action { sender, args -> setLevel(args.offlinePlayer("target"), args.int("level"), args.stringOrNull("reason")) }
    }
}
```
Arg types: `STRING`, `TEXT`, `INT`, `LONG`, `DOUBLE`, `BOOLEAN`, `PLAYER` (exact online name),
`OFFLINE_PLAYER` (cached, never blocks). Custom: `object SkillArg : ArgType<Skill>("skill") { override fun parse(raw: String) = Skill.byId(raw); override fun suggestions() = Skill.ids }`
then `args.get<Skill>("skill")`. `arg { suggest { listOf(...) }; onInvalid { s, raw -> }; optional = true }`.
Builder: `onMissingArgs { sender, usage -> }`, `onUnknownSubcommand { sender, options -> }`,
`noPermissionMessage`, `playerOnlyMessage`.

## Hooks (`com.aayan.albcore.hooks`)

- Each ALB plugin publishes its own placeholders under its own identifier:
  `PAPIHook.register(plugin, "albskills", "total") { player -> "${totalOf(player)}" }` gives `%albskills_total%`
  (identifier `a-z0-9`, no `_`; key `a-z0-9_`). `PAPIHook.register(plugin, "level") { }` uses the shared `%alb_level%`.
  Expansions are created on first use and removed when the plugin disables. `PAPIHook.registeredPlaceholders()`.
  `PAPIHook.parse(player, text)`, `PAPIHook.isEnabled()`.
- `VaultHook.getBalance/has/deposit/withdraw(offlinePlayer, amount)`, `format(amount)`, `isEnabled()`.
  Main thread unless the economy plugin is async-safe.
- `PacketEventsHook.register(plugin, listener)` (only when `isEnabled()`).

## Utilities (`com.aayan.albcore.utils`, `logging`, `config`)

- `ColorUtil.parse(text)`, `parseWithPAPI(player, text)`, `toPlainText(text)`, `escape(text)`.
  Text accepts MiniMessage and `&c`, `&l`, `&#rrggbb`, `&x&r&r&g&g&b&b`.
- `MessageUtil.send(sender, text)`, `sendWithPAPI(player, text)`, `actionBar`, `sendTitle(player, title, subtitle, fadeIn, stay, fadeOut)` (ms), `broadcast(text, permission?)`.
- `ItemBuilder(Material.X).name("&b..").lore("..", "..").addLore(list).amount(n).enchant(e, lvl).flag(..).glow().unbreakable().data(key, type, value).ignoreAlbLore().meta { }.build()`
  (non-italic by default; pass a player to `name(player, text)`/`lore(player, ...)` for placeholders).
- `BedrockUtil.isBedrock(player)` (Floodgate/Geyser by reflection; false without them): send Bedrock
  players plain text instead of glyphs, hover text or text displays.
- `HeadUtil.fromBase64(texture)`, `fromPlayerName(name)`, `fromPlayer(offlinePlayer)`.
- `NumberUtil.formatNumber(1500L) == "1.5K"`, `formatNumber(1234.5) == "1.23K"`, `formatComma(1234567) == "1,234,567"`, `parseNumber("2.5m") == 2500000L` (null if invalid).
- `SoundUtil.play(player, "minecraft:entity.experience_orb.pickup", volume, pitch)`, `broadcast(...)`.
- `AnimationUtil.animateTitleNumber(player, title = "&a+%amount%", to = 500)`.
- `ShulkerUtil.process(items) { canSell(it) }` splits shulker contents into sell/return lists.
- `PriceRegistry.set(plugin, material, price)` / `set(plugin, material) { player -> price }`, `getAverage(material, player)`.
- `DiscordLogger.on("staff").warning(title, message)` (webhooks named in ALBCore `config.yml` `discord.webhooks`; mentions are disabled).
- `ConfigFile(plugin, "levels.yml")`: `.config` (YamlConfiguration with jar defaults), `.reload()`, `.save()`.

Bedrock note: Java-only formatting (custom fonts, hover/click events) may not show on Bedrock;
keep menus readable from item names and lore alone.

## Working on ALBCore itself

- Build: `./gradlew build` (tests + shaded jar `build/libs/alb-core-<version>.jar`), API for child plugins:
  `./gradlew publishToMavenLocal`. Hikari is shaded and relocated to `com.aayan.albcore.libs.hikari`;
  Kotlin stdlib is shaded **unrelocated** on purpose (children use it, and the API exposes Kotlin function
  types). Paper 26.3 already bundles the MySQL and SQLite JDBC drivers, Gson and SLF4J; do not shade them.
- Tests: JUnit 6 in `src/test/kotlin`. `DatabaseContractTest` runs on SQLite always and on MySQL when
  `ALBCORE_TEST_MYSQL_HOST` (+ `_PORT`, `_DATABASE`, `_USER`, `_PASSWORD`) is set.
- Local server: `Skyabyss/test_server` (Paper 26.3 build 252, port 25599). Copy the jar to `plugins/`.
- Keep public API changes in `docs/index.html` and this file in sync.
- Docs are hosted at https://aayankhaan.github.io/ALB-Core/ (GitHub Pages serving `docs/` on `master`).
- Release: set `version` in `build.gradle.kts`, merge to `master`, then push tag `v<version>`;
  `.github/workflows/release.yml` builds, tests and publishes the GitHub Release with the jars.
- Keep the code Bukkit-free where possible (Database, PlayerDataService, CoreConfig take a `Logger`
  and plain settings) so it stays unit-testable.
