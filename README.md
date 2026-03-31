### Multiversion
Multiversion is a plugin meant to facilitate developing Minecraft mods for multiple versions of the game at once.
It works by layering source sets on top of each other, from the oldest version given to the newest.

What that means is that you can write the full 1.20.1 version of a mod using Architectury to add support for both Fabric and Forge/NeoForge, and the only thing you have to do to add support for 1.21.1 (NeoForge and Fabric) is re-write the classes that broke between versions.
The plugin also automatically sets up mod publishing to CurseForge and Modrinth if given the appropriate Gradle properties.

This repository includes a template mod, the Gradle plugin, the Gradle settings plugin, and an IntelliJ IDEA plugin.

AI DISCLAIMER: A few parts of this project were written/assisted by AI. Specifically the documentation and debugging.

---

### Applying the plugins

Add the repository and apply the settings plugin in `settings.gradle`. The settings plugin automatically registers the rest of the required repositories (Fabric, Architectury, Forge, NeoForge) so you don't need to list them manually.

```groovy
pluginManagement {
    repositories {
        maven { url = uri("https://maven.hoshinofw.net/releases") }
        gradlePluginPortal()
    }
}

plugins {
    id "com.github.hoshinofw.multiversion.multiversion-settings" version "0.2.0"
}
```

Then apply the main plugin in the root `build.gradle`. The annotations `compileOnly` dependency is injected automatically into every subproject. No additional setup is needed.

```groovy
plugins {
    id "com.github.hoshinofw.multiversion.multiversion" version "0.2.0"
}

repositories {
    mavenCentral()
    // any additional repositories your mod needs
}
```

If your resource files contain property placeholders (e.g. `${mod_license}` in `fabric.mod.json`), declare them via `multiversionResources` so they get substituted at build time:

```groovy
multiversionResources {
    replaceProperties = [
        "mod_license"     : mod_license.toString(),
        "mod_description" : mod_description.toString(),
        "mod_author"      : mod_author.toString()
    ]
}
```

#### Subproject dependency resolution

The plugin exposes utility methods for cleanly routing dependencies by platform and Minecraft version inside a `subprojects {}` block, without needing to hard-code project path strings.

```groovy
import com.github.hoshinofw.multiversion.util.GeneralUtil

subprojects {
    dependencies {
        // loader-based routing
        if (GeneralUtil.isCommon(project)) {
            // Common module deps
        }
        if (GeneralUtil.isFabric(project)) {
            // Fabric api is handled automatically
            // Other fabric-specific deps
        }
        if (GeneralUtil.isForge(project)) {
            // forge-specific deps
        }
        if (GeneralUtil.isNeoForge(project)) {
            // neoforge-specific deps
        }

        // version-based routing
        if (GeneralUtil.isMcVersion(project, "1.20.1")) {
            // 1.20.1-only deps
        }
        if (GeneralUtil.isMcVersion(project, "1.21.1")) {
            // 1.21.1-only deps
        }
    }
}
```

`GeneralUtil.mcVersion(project)` returns the version string (e.g. `"1.20.1"`) if you need it for dynamic version-specific logic.

---

### Module configuration and patching

The `multiversionModules` closure declares which module names exist in the project, what loader type each belongs to, and which should have patchedSrc generation applied across versions.

```groovy
multiversionModules {
    common   = ['common', 'api']
    fabric   = ['fabric']
    forge    = ['forge']
    neoforge = ['neoforge']
    patchModules = ['common', 'fabric', 'forge', 'neoforge']
}
```

This closure is **optional** but **required for patching to occur**. Without it, all standard modules (common, fabric, forge, neoforge) still receive their Architectury and Loom configuration based on their directory name, but no patchedSrc generation takes place.

#### Loader type groups

Each key (`common`, `fabric`, `forge`, `neoforge`) declares a list of module names that receive the corresponding Architectury/Loom configuration:

| Key | Configuration applied |
|---|---|
| `common` | Fabric loader with Architectury annotation transforms, no platform Loom |
| `fabric` | Fabric Loom |
| `forge` | Forge Loom |
| `neoforge` | NeoForge Loom |

Non-standard module names (like `api` in the example above) must be assigned to a group to receive any Architectury setup. Standard names (`common`, `fabric`, `forge`, `neoforge`) always receive their default setup even if omitted from the closure.

#### `patchModules`

Lists the module names that get patchedSrc generation. Each name in this list must have corresponding directories under at least one version folder (e.g. `1.21.1/fabric/`). The plugin patches each module group independently: `fabric` versions are layered against each other, `forge` versions against each other, and so on.

Modules not in `patchModules` (such as `api` in the example) still receive full Minecraft/Architectury configuration but are not patched across versions.

If a module is declared in `patchModules` but is missing from a particular version folder, a warning is logged and that version is skipped for the module. No error is thrown.

#### Root-level shared source

Each module may optionally have a root-level shared source directory named after the module itself. Code placed there is inherited by every version of that module as the base layer:

```
common/src/main/java/        ← shared across all versions of 'common'
fabric/src/main/java/        ← shared across all versions of 'fabric'
api/src/main/java/           ← shared across all versions of 'api' (if it exists)
1.20.1/common/src/main/java/ ← version-specific
1.21.1/common/src/main/java/ ← patches 1.20.1
```

These directories are optional. If absent, the oldest version's sources serve as the base.

---

### Compile-time mixins

Version-specific classes support a set of Mixin-style annotations that let you write targeted patches instead of copying entire classes between versions. The Gradle plugin merges the version layers at build time with no runtime overhead.

#### `@OverwriteVersion`
Replaces a method or field from the previous version. Equivalent to `@Overwrite` in Mixin.

```java
@OverwriteVersion
public static void init() {
    LOGGER.info("Initialized: version=1.21.1");
}
```

#### `@ShadowVersion`
Declares a reference to a method or field that exists in the previous version, without replacing it. Equivalent to `@Shadow` in Mixin. Useful when a new method needs to call something from the base version.

Since shadow declarations are stubs, version classes can be marked `abstract` to avoid writing placeholder bodies. The merged output in patchedSrc keeps the original class modifier from the base version, so declaring your version class as `abstract` does not make the compiled class abstract.

```java
public abstract class MyClass {
    @ShadowVersion public abstract static Logger LOGGER;
    @ShadowVersion public abstract static void existingMethod();

    // New method that calls into the base version
    public void newMethod() {
        existingMethod();
    }
}
```

#### `@DeleteMethodsAndFields`
Removes specific methods or fields from the previous version. Useful when a method signature changes or something is no longer needed.
Use a bare name for unambiguous targets, or `methodName(ParamType1, ParamType2)` to disambiguate overloads.

```java
@DeleteMethodsAndFields({"oldMethod", "renamedMethod(int, String)"})
public class MyClass { ... }
```

#### `@DeleteClass`
Removes the entire class from the merged output. Useful when a class is no longer needed in a newer version.

```java
@DeleteClass
public class ObsoleteHelper { ... }
```

The class is removed from `patchedSrc` at build time. It can be reintroduced in a later version layer without issue.

---

### Resource patching

Place a `multiversion-resources.json` file inside a version's resource directory (e.g. `1.21.1/common/src/main/resources/multiversion-resources.json`) to declare structural operations on the merged resource output. It is a build-time instruction file and is never included in the built mod.

Operations are **cumulative**: a `delete` or `move` declared in an older version carries forward into all subsequent versions, matching how `@DeleteClass` carries forward through the class layer system.

```json
{
  "delete": [
    "assets/mymod/models/item/removed_item.json",
    "data/mymod/recipes/old_folder/"
  ],
  "move": [
    { "from": "assets/mymod/textures/block/old_name.png",
      "to":   "assets/mymod/textures/block/new_name.png" }
  ]
}
```

#### `delete`
Removes a file or directory from the merged output. A path ending with `/` is treated as a directory and removes everything under it. If a later version re-provides the file in its own source tree, the deletion is skipped. The newer source always wins.

#### `move`
Copies a file from `from` to `to` within the merged output, then removes the original path. If `from` no longer exists (already moved or deleted by an earlier operation), the entry is silently skipped. If the current version explicitly provides a file at `to`, the moved content is discarded and the explicitly provided file is kept. Only the old path is removed.

---

### Setup

The Gradle and settings plugins are published to `https://maven.hoshinofw.net/releases`. The annotations are injected automatically by the Gradle plugin as a `compileOnly` dependency. No manual setup is required.

The mod template in this repository is the recommended starting point. For a larger real-world example of the plugin in use, see the source code for [Buildstone Toolkit](https://github.com/HoshinOFW/Buildstone-Toolkit).

#### Adding a new Minecraft version

The `initVersion` task scaffolds the directory structure for a new version:

```bash
./gradlew initVersion --minecraft_version=1.21.1 --modLoaders=fabric,neoforge
```

`--modLoaders` is a comma-separated list of any combination of `fabric`, `forge`, and `neoforge`.

This creates the following structure:

```
<minecraft_version>/
├── common/
│   ├── src/main/java/
│   └── src/main/resources/
├── fabric/          # if requested
│   ├── src/main/java/
│   └── src/main/resources/
├── neoforge/        # if requested
│   ├── src/main/java/
│   └── src/main/resources/
└── gradle.properties
```

The generated `gradle.properties` is pre-filled with `minecraft_version` and `enabled_platforms`. You still need to add the loader-specific version properties before the project will build:

| Property | Required for |
|---|---|
| `fabric_api_version` | Fabric |
| `forge_version` | Forge |
| `neoforge_version` | NeoForge |

Properties shared across all versions (`mod_id`, `mod_name`, `mod_version`, `archives_name`, `maven_group`) belong in the root `gradle.properties` and are inherited automatically.

---

### Publishing

The Gradle plugin can publish all built mod JARs to CurseForge and Modrinth in one task. Publishing is opt-in. If the required properties are absent, the plugin skips configuration entirely and nothing breaks.

#### `gradle.properties`

These go in the root `gradle.properties`:

| Property | Description |
|---|---|
| `mod_name` | Display name of the mod |
| `mod_version` | Version string, e.g. `1.0.0` |
| `release_channel` | One of `alpha`, `beta`, or `stable` |
| `mod_client` | `true` or `false` |
| `mod_server` | `true` or `false` |
| `curseforge_id` | Numeric project ID from the CurseForge project page |
| `modrinth_id` | Project slug or ID from Modrinth |
| `curseforge_dependencies` | Comma-separated CurseForge project IDs for required dependencies |
| `modrinth_dependencies` | Comma-separated Modrinth slugs for required dependencies |

Leave `curseforge_dependencies` or `modrinth_dependencies` empty if there are none.

#### Environment variables

| Variable | Description |
|---|---|
| `CURSEFORGE_TOKEN` | CurseForge API token |
| `MODRINTH_TOKEN` | Modrinth API token |

These are read at publish time. They are never required for building.

#### `changelog.md`

Place a `changelog.md` file at the root of the project. Its contents are sent as the release description to both CurseForge and Modrinth. If the file is absent, the upload proceeds with a placeholder message.

#### Running a publish

Collect the JARs, then publish:

```bash
./gradlew collectBuildsAll
./gradlew publishAllSafe -PPUBLISH_RELEASE=true
```

`collectBuildsAll` triggers the build for every version/loader subproject and copies the resulting JARs into `builds/<mod_version>/<minecraft_version>/<loader>/`. You can also collect a single subproject by running its `collectBuilds` task directly, e.g. `./gradlew :1.21.1:neoforge:collectBuilds`.

Without `-PPUBLISH_RELEASE=true` the publish task runs in dry-run mode and does not upload anything. This is useful for checking that credentials and properties are wired correctly before committing to a release.

The collected JARs are placed under `builds/<mod_version>/<minecraft_version>/<loader>/` and are what gets uploaded. You can inspect them before publishing.

---

### IDE plugin

An IntelliJ IDEA plugin is available on the JetBrains Marketplace. It is strongly recommended. Without it you will run into false navigation and duplicate class errors caused by the multiple source sets.

It provides:
- Go to Declaration navigating to the correct original source file and line rather than the generated merged output
- Suppression of duplicate class and false initialization errors that result from the version-specific source structure
- `@DeleteMethodsAndFields` descriptor validation and Ctrl+Click navigation to the target method or field

---

### Credits

- [Architectury](https://github.com/architectury/architectury-loom): Handles all platform-specific build logic. Multiversion wraps around it and would not function without it.
- [mod-publish-plugin](https://github.com/modmuss50/mod-publish-plugin) by modmuss50: the plugin powering CurseForge and Modrinth publishing. The publishing functionality in Multiversion is a thin configuration layer on top of it.