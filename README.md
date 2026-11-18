# ALBCore

Base framework for the ALB plugins (ALBLevel, ALBSkills, ...) on **Paper 26.3 / Java 25**, written in Kotlin.

- **GUI**: chest menus with pages, per-player items, click actions and item-input slots (Bedrock-safe).
- **Commands**: a Kotlin builder on Paper's Brigadier API with typed, optional and greedy arguments, subcommands, validators and tab completion.
- **Database**: SQLite or MySQL through HikariCP. Every query is async, and versioned schema migrations are built in.
- **Player data**: per-player key/value data stored in YAML, SQLite or MySQL (switch in config).
- **Hooks**: PlaceholderAPI (`%alb_*%`), Vault economy, PacketEvents.
- **Utilities**: colours (MiniMessage + `&` codes), messages, items, heads, number formatting, sounds, Discord webhooks.

Full documentation: [`docs/index.html`](docs/index.html) (can be served with GitHub Pages from the `docs/` folder).
Upgrading from 0.1.0: [`MIGRATION.md`](MIGRATION.md).

## Build

Requires JDK 25.

```bash
./gradlew build                 # tests + build/libs/alb-core-<version>.jar (the server plugin)
./gradlew publishToMavenLocal   # API jar for child plugins: com.aayan:alb-core:<version>
```

To also run the database tests against MySQL, set `ALBCORE_TEST_MYSQL_HOST` (and optionally
`_PORT`, `_DATABASE`, `_USER`, `_PASSWORD`) before `./gradlew test`. Use a throwaway database.

## Install on a server

1. Put `alb-core-<version>.jar` in `plugins/`.
2. Start the server once, then edit `plugins/ALBCore/config.yml` (database type, MySQL login, player-data storage).
3. Restart. `/albcore info` shows the database, player-data storage and hooks.

`config.yml` holds passwords and webhook URLs: keep it on the server, never commit it.

## Use it in a plugin

`build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.252-stable")
    compileOnly("com.aayan:alb-core:1.0.0")
    compileOnly(kotlin("stdlib")) // ALBCore ships the Kotlin runtime; do not shade your own
}

kotlin { jvmToolchain(25) }
```

`gradle.properties`: `kotlin.stdlib.default.dependency=false`

`plugin.yml`: `depend: [ALBCore]` and `api-version: '26.3'`.

```kotlin
override fun onEnable() {
    val db = ALBCore.instance.database
    db.migrate(name) {
        version(1, "Create levels table") {
            execute("CREATE TABLE ${table("levels")} (uuid CHAR(36) NOT NULL PRIMARY KEY, level INT NOT NULL)")
        }
    }
}
```

## License

Private project by Aayan.
