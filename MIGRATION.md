# Migrating from ALBCore 0.1.0 to 1.0.0

ALBCore 1.0.0 targets **Paper 26.3 on Java 25** and makes some breaking API changes.
This page lists every change a plugin built on 0.1.0 may need. Most are renames you can fix
with search and replace.

## Build and server

| Before | Now |
|---|---|
| Paper 1.21, Java 21, `api-version: '1.21'` | Paper 26.3 (build 252+), Java 25, `api-version: '26.3'` |
| Gradle 8.7, Kotlin 1.9.23, `com.github.johnrengelman.shadow` | Gradle 9.9.1 wrapper, Kotlin 2.4.21, `com.gradleup.shadow` 9.6.1 |
| Sources in `src/main/java` | Sources in `src/main/kotlin` |
| Gson shaded into the jar | Not shaded; Paper already ships Gson |
| softdepend `packetevent` (typo) | softdepend `packetevents` |

Child plugins compile against `com.aayan:alb-core:1.0.0` (run `./gradlew publishToMavenLocal` in ALB-Core).
See the README for the Gradle setup.

## Removed or renamed classes

| Before | Now | Notes |
|---|---|---|
| `utils.PlayerDataUtil` | `data.PlayerData` | Same `get`, `getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getStringList`, `set` methods. They take an `OfflinePlayer` (a `Player` still works). `load`, `unload` and `saveAll` are gone: ALBCore loads data before join and saves it in the background. `set` no longer writes to disk immediately. |
| `listeners.PlayerListener` | removed | ALBCore handles join/quit itself. |
| `utils.AveragePrice` | `utils.PriceRegistry` | `set(material, value, plugin)` becomes `set(plugin, material, price)`. `set(material, plugin) { }` becomes `set(plugin, material) { }`. |
| `hooks.PAPIExpansion` | removed | Use `PAPIHook.register(plugin, key) { player -> ... }`. |
| `gui.GuiListener` (public) | internal | ALBCore registers it. Plugins never did. |

## Hooks

| Before | Now |
|---|---|
| `PAPIExpansion.registerPAPI("key") { player -> "..." }` | `PAPIHook.register(this, "key") { player -> "..." }` for `%alb_key%`, or `PAPIHook.register(this, "albskills", "key") { }` for a plugin's own `%albskills_key%`. Keys are lower-case `a-z0-9_`. The handler may return `null`. Placeholders are removed when your plugin disables. |
| `PAPIHook.parse(player: Player, text)` | `PAPIHook.parse(player: OfflinePlayer?, text)` (source compatible) |
| `VaultHook.getMoney(player)` | `VaultHook.getBalance(player)` |
| `VaultHook.hasMoney(player, amount)` | `VaultHook.has(player, amount)` |
| `VaultHook.giveMoney(player, amount)` | `VaultHook.deposit(player, amount)` |
| `VaultHook.takeMoney(player, amount)` | `VaultHook.withdraw(player, amount)` |
| `VaultHook.isLoaded()` | `VaultHook.isEnabled()` (true only while an economy is registered) |
| `PacketEventsHook.registerListener(listener)` | `PacketEventsHook.register(plugin, listener)` |
| `PacketEventsHook.unregisterAll()` (removed **every** plugin's listeners) | `PacketEventsHook.unregisterAll(plugin)` (only yours; also automatic on disable) |
| `PacketEventsHook.isLoaded()` | `PacketEventsHook.isEnabled()` |
| `setup(...)` methods on all hooks | internal; ALBCore calls them |

## Discord logger

| Before | Now |
|---|---|
| `DiscordLogger.setup(name, url, username, avatarUrl)` | `DiscordLogger.register(name, url, username, avatarUrl)`. Username and avatar are now per webhook (before, the last `setup` call changed them for every webhook). URLs must start with `https://`. Webhooks can also be set in `config.yml` under `discord.webhooks`. |
| `DiscordLogger.isLoaded(name)` | `DiscordLogger.isRegistered(name)` |
| `send`, `embed`, `info`... returned `Unit` | They return `CompletableFuture<Boolean>` (true = Discord accepted it). |
| Payloads could ping `@everyone` | Mentions are always disabled. |

## Utilities

| Before | Now |
|---|---|
| `ColorUtil.parseToString(text)` | `ColorUtil.toPlainText(text)` |
| `ColorUtil.parseWithPAPI(player: Player, text)` | `ColorUtil.parseWithPAPI(player: OfflinePlayer?, text)` (source compatible) |
| (new) | `ColorUtil.escape(text)`: use on any player-written text before parsing it. `ColorUtil.legacyToMiniMessage(text)`. `&x&r&r&g&g&b&b` hex colours now work. |
| `MessageUtil.sendTitle(player, title, subtitle, ...)` | `subtitle` now defaults to `""`. |
| `MessageUtil.broadcast(text)` | `MessageUtil.broadcast(text, permission = null)` |
| (new) | `MessageUtil.actionBar(player, text)` |
| `ItemBuilder(item)` changed the `item` you passed in | It works on a copy. Use the result of `build()`. |
| `ItemBuilder.name(player, text)` / `lore(player, ...)` needed a player | Player is optional: `name("&bX")`, `lore("a", "b")`, `lore(listOf(...), player)`. |
| `ItemBuilder.glow()` added a hidden Unbreaking I | Uses the enchantment glint override. Items built before still have Unbreaking I. |
| (new) | `ItemBuilder.addLore`, `amount`, `unbreakable`, `data`, `meta { }`, `ItemBuilder.NO_LORE_KEY`. |
| `HeadUtil.fromBase64` (skin never showed: wrong property name) | Fixed; heads from the same texture stack. |
| `HeadUtil.fromPlayerName` (deprecated `setOwner`) | Uses a player profile. New `HeadUtil.fromPlayer(offlinePlayer)`. |
| `NumberUtil.formatNumber` | Same names. Locale independent (German servers printed `1,5K`), `999950` gives `1M` (was `1000K`), negatives are shortened, `1.50K` is now `1.5K`. A `Q` (quadrillion) suffix was added. |
| `NumberUtil.parseNumber` | Same name. Accepts `1,000` and `1_000`, uses exact decimals (`1.005k` gives 1005), and returns null when out of range. |
| (new) | `NumberUtil.formatComma(Double, decimals)` |
| `SoundUtil.play(player, key, volume, pitch)` | Optional `source` parameter. Bad keys log one warning instead of throwing. |
| `AnimationUtil.animateTitleNumber` | Same signature. Stops when the player leaves and no longer sends the last title twice. `Easing.apply(progress)` is public. |
| (new) | `utils.Futures`: `future.thenSync(plugin) { }` and `future.logFailure(logger, msg)`. |

## Commands

| Before | Now |
|---|---|
| `sealed class ArgType<T>` | `abstract class ArgType<T : Any>`: you can write your own types. |
| `ArgType.PLAYER` matched name prefixes (`Bukkit.getPlayer`) | Exact names only (`getPlayerExact`). Suggests online players by default. |
| (new) | `ArgType.INT`, `BOOLEAN`, `TEXT` (rest of the line), `OFFLINE_PLAYER` |
| (new) | `arg(name, type) { optional = true }` |
| `ParsedArgs.string/long/double/player` | Same, plus `int`, `boolean`, `offlinePlayer`, `...OrNull`, `has(name)`, `get<T>(name)` |
| `CommandBuilder.playerOnly: Boolean?` | `Boolean`. `playerOnly = true` still compiles; `= null` does not. |
| A command with subcommands always showed usage when no subcommand was typed | If it also has an `action`, that action runs (`/island` opens a menu, `/island invite` is a subcommand). Unknown words still show usage. |
| `ArgDefinition.validators` was public | internal; use `validate(check, onFail)`. |

## GUI

| Before | Now |
|---|---|
| `GuiBuilder` fields `actions`, `pages`, `globalScope`, `pageActions`, `slotFilters`, `refreshTask`, `onOpenAction`, `onCloseAction` (internal, but visible) | private |
| `currentPage` (internal var) | public, read-only. New: `totalPages`, `size`, `viewer`, `isOpen()`, `close()`. |
| `rows` was not checked | Must be 1-6; slots must be inside the GUI. |
| `refreshEvery(ticks)` started a timer right away and never restarted after reopening | Runs only while the GUI is open and restarts when it is reopened. |
| `PageContext` | New `goTo(page)` and `refresh()`. |

Bugs fixed (no code change needed):

- Click actions of the previous page no longer stay active after a page change.
- Shift-click from the player inventory could put items into **any** empty GUI slot. Now it only fills item-input slots and respects their filters.
- Double-click "collect" could pull items out of locked GUI slots.
- Number keys and the offhand key (F) could bypass item-input filters.
- When ALBCore disables, open GUIs are closed first so their items cannot be taken.

## New in 1.0.0

- `config.yml` (database, player data, webhooks) and the `/albcore` admin command (`albcore.admin`).
- `ALBCore.instance.database`: SQLite/MySQL with HikariCP, async queries, schema migrations.
- Player data storage can be `yaml`, `sqlite` or `mysql`. Use `/albcore playerdata import <type>` to move data between them.
- `config.ConfigFile` for extra YAML files in child plugins.
