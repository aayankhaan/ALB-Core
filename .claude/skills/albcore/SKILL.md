---
name: albcore
description: ALBCore API and conventions for Skyabyss plugins on Paper 26.3 / Java 25 (Kotlin). Use when writing or changing code in ALBCore or in any plugin built on it (ALBLevel, ALBSkills, other ALB plugins). Covers GUIs, commands, database tables and migrations, player data, PlaceholderAPI/Vault hooks, item/colour/number utilities, threading rules, and the Gradle/plugin.yml setup for child plugins.
---

# ALBCore

ALBCore is the base plugin for every ALB plugin on the Skyabyss server (dungeon + skyblock, Java and
Bedrock players through Geyser/Floodgate). Child plugins depend on it and use its API instead of
writing their own GUI, command, database or player-data code.

- Repo: `Skyabyss/ALB-Core` (git, https://github.com/aayankhaan/ALB-Core). Kotlin in `src/main/kotlin/com/aayan/albcore`.
- Version 1.0.0. Paper API `io.papermc.paper:paper-api:26.3.build.252-stable`, Java 25, Kotlin 2.4.21, Gradle 9.9.1 wrapper.
- Human docs: `docs/index.html`. Breaking changes from 0.1.0: `MIGRATION.md`.
- Project design (wins over the old `Cloode/` docs): `Skyabyss/DECISIONS.md`, section 4 lists the plugins and how they connect.

Planned child plugins: ALBSkills, ALBItems, ALBDungeon, ALBQuests, ALBIslands, ALBChat, ALBRewards. All share
one MySQL database (eco plugins use the same server with their own tables).

Planned for ALBCore itself (add them here, never as private copies in a child plugin): coins as the Vault
economy provider, the NPC system (entity + optional ModelEngine model + click handler), Bedrock forms
(Floodgate) for confirms and short lists, language files, and an event bus between ALB plugins.

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
    compileOnly("com.aayan:alb-core:1.0.0")
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

```kotlin
val gui = GuiBuilder("&8Skills", 6)           // title supports & codes, MiniMessage, PAPI; rows 1..6
gui.global {                                  // shown on every page
        item(45) { _, ctx -> GuiItem(prevItem) { _, c -> c.previousPage() } }
        item(53) { _, ctx -> GuiItem(if (ctx.hasNextPage) nextItem else filler) { _, c -> c.nextPage() } }
    }
    .page { item(10..16) { player, ctx -> GuiItem(skillIcon(player)) { p, c -> openSkill(p); c.refresh() } } }
    .page { item(10) { _, _ -> GuiItem(pageTwoIcon) } }
    .setItem(49, { player -> infoItem(player) }) { player -> player.closeInventory() }   // fixed slot
    .allowItemPlacement(19..25) { item -> item.type == Material.DIAMOND }               // input slots + filter
    .onClose { player -> giveBack(player, gui.getItems(19..25)) }   // separate statement, so `gui` exists
    .refreshEvery(20)                                                                    // ticks, only while open
gui.open(player)
```
`PageContext`: `currentPage`, `totalPages`, `hasNextPage`, `hasPreviousPage`, `nextPage()`,
`previousPage()`, `goTo(i)`, `refresh()`. `GuiBuilder`: `refresh()`, `goToPage(i)`, `close()`, `isOpen()`,
`viewer`, `getItems(...)`, `addItem(s)`, `clearItems(range)`. Items in input slots survive `refresh()`;
return them to the player in `onClose` (ALBCore does not). All other slots are locked (shift-click,
double-click, number keys and drags are handled).

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

- Build: `./gradlew build` (tests + shaded jar `build/libs/alb-core-1.0.0.jar`), API for child plugins:
  `./gradlew publishToMavenLocal`. Hikari is shaded and relocated to `com.aayan.albcore.libs.hikari`;
  Kotlin stdlib is shaded **unrelocated** on purpose (children use it, and the API exposes Kotlin function
  types). Paper 26.3 already bundles the MySQL and SQLite JDBC drivers, Gson and SLF4J; do not shade them.
- Tests: JUnit 6 in `src/test/kotlin`. `DatabaseContractTest` runs on SQLite always and on MySQL when
  `ALBCORE_TEST_MYSQL_HOST` (+ `_PORT`, `_DATABASE`, `_USER`, `_PASSWORD`) is set.
- Local server: `Skyabyss/test_server` (Paper 26.3 build 252, port 25599). Copy the jar to `plugins/`.
- Keep public API changes in `MIGRATION.md`, `docs/index.html` and this file in sync.
- Docs are hosted at https://aayankhaan.github.io/ALB-Core/ (GitHub Pages serving `docs/` on `master`).
- Release: set `version` in `build.gradle.kts`, merge to `master`, then push tag `v<version>`;
  `.github/workflows/release.yml` builds, tests and publishes the GitHub Release with the jars.
- Keep the code Bukkit-free where possible (Database, PlayerDataService, CoreConfig take a `Logger`
  and plain settings) so it stays unit-testable.
