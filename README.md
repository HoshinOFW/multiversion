### Multiversion
Multiversion is a plugin meant to facilitate developing Minecraft mods for multiple versions of the game at once.
It works by layering source sets on top of each other, from the oldest version given to the newest.

What that means is that you can write the full 1.20.1 version of a mod using Architectury to add support for both Fabric and Forge/NeoForge, and the only thing you have to do to add support for 1.21.1 (NeoForge and Fabric) is re-write the classes that broke between versions.
The plugin also automatically sets up mod publishing to CurseForge and Modrinth if given the appropriate Gradle properties.

This repository includes a template mod, the Gradle plugin, the Gradle settings plugin, and an IntelliJ IDEA plugin. There are also 2 libraries (merge-engine, and annotations) shared by the gradle and IDEA plugins for common operations.

This whole project is still in active development, and some things regarding resource management have not yet been thoroughly tested in production, even if the code is theoretically sound.
If you have a suggestion, please open a suggestion in the github repository issues page. I am very open to suggestions on how to improve this tool further.
However, please check todo.md to make sure it is not already planned.

AI DISCLAIMER: Parts of this project were written/assisted by AI. Especially the documentation and debugging.

---

### Applying the plugins

Add the repository and apply the settings plugin in `settings.gradle`. The settings plugin automatically registers the rest of the required repositories (Fabric, Architectury, Forge, NeoForge) so you don't need to list them manually.
You can check the changelog files in each project to see if its worth migrating to a higher version.
This project in its current state is feature-complete enough to be useful, and will be backwards-compatible for the near future. Any changes will be minimal.

```groovy
pluginManagement {
    repositories {
        maven { url = uri("https://maven.hoshinofw.net/releases") }
        gradlePluginPortal()
    }
}

plugins {
    id "com.github.hoshinofw.multiversion.multiversion-settings" version "0.6.1"
}

multiversionModules {
    architecturyCommon   = ['common']
    architecturyFabric   = ['fabric']
    architecturyNeoforge = ['neoforge']
    patchModules = ['common', 'fabric', 'neoforge']
}
```

Then apply the main plugin in the root `build.gradle`. The annotations `compileOnly` dependency is injected automatically into every subproject. No additional setup is needed. Task wiring is also declared here.

```groovy
plugins {
    id "com.github.hoshinofw.multiversion.multiversion" version "0.7.0"
}

repositories {
    mavenCentral()
    // any additional repositories your mod needs
}

multiversion.wireTask 'runClient'
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

The plugin registers a `multiversion` extension on every project, exposing utility methods for cleanly routing configuration by platform and Minecraft version. No import is needed.

`subprojects {}` iterates every subproject including version-group projects (`:1.20.1`, `:1.21.1`) which are not versioned modules at all. Guard with `multiversion.isVersionedModule()` to skip those. If your project has plain modules (not enrolled in an `architectury*` list), they do not have Loom applied either — guard Loom-specific configurations such as `modImplementation` with `multiversion.isArchEnabled()` instead:

```groovy
subprojects {
    if (!multiversion.isVersionedModule()) return

    dependencies {
        // Loom-specific configurations require isArchEnabled()
        if (multiversion.isArchEnabled()) {
            if (multiversion.isCommon()) {
                // common module deps
            }
            if (multiversion.isFabric()) {
                modImplementation "..."
            }
            if (multiversion.isForge()) {
                // Forge-specific deps
            }
            if (multiversion.isNeoForge()) {
                // NeoForge-specific deps
            }
        }

        // version-based routing works for all modules
        if (multiversion.isMcVersion("1.20.1")) {
            // 1.20.1-only deps
        }
        if (multiversion.isMcVersion("1.21.1")) {
            // 1.21.1-only deps
        }
    }
}
```

The same methods are available in individual subproject `build.gradle` files, where the guard is not needed since the file only applies to that specific module:

```groovy
// 1.21.1/fabric/build.gradle
modImplementation "..."
```

`multiversion.mcVersion()` returns the version string (e.g. `"1.20.1"`) if you need it for dynamic logic.

`multiversion.moduleType()` returns the loader type string (`"common"`, `"fabric"`, `"forge"`, or `"neoforge"`), or `null` if the project is not a recognized versioned module. Useful for switch-style dispatch:

```groovy
subprojects {
    switch (multiversion.moduleType()) {
        case "fabric":   /* ... */; break
        case "neoforge": /* ... */; break
    }
}
```

`isFabric()`, `isForge()`, etc. resolve based on the declared loader type for that module name — not the directory name. A module named `fabric-custom` declared under `architecturyFabric = ['fabric-custom']` (or `fabric = ['fabric-custom']`) returns `true` from `isFabric()`.

---

### Module configuration and patching

The `multiversionModules` closure declares which module names exist in the project, what loader type each belongs to, which should receive Architectury/Loom auto-configuration, and which should have patchedSrc generation applied across versions.

`multiversionModules` is configured in **`settings.gradle`** (not `build.gradle`), because the settings plugin uses it to decide which subprojects to include. The main build plugin then reads the same extension automatically.

`multiversionModules` is **required** — without it the plugin does not configure any subprojects.

#### Plain loader-type lists

`common`, `fabric`, `forge`, and `neoforge` declare module names for routing purposes only. They determine what `isFabric()`, `moduleType()`, etc. return, and which modules receive `multiversionResources` processing and patchedSrc generation. No Loom or Architectury configuration is applied to these modules — dependency and build setup is left entirely to you.

```groovy
// settings.gradle
multiversionModules {
    common   = ['common', 'api']  // 'api' is plain: no Loom, you manage its build
    fabric   = ['fabric']
    neoforge = ['neoforge']
    patchModules = ['common', 'api', 'fabric', 'neoforge']
}
```

#### Architectury/Loom opt-in lists

`architecturyCommon`, `architecturyFabric`, `architecturyForge`, and `architecturyNeoforge` opt modules into full Loom + Architectury auto-configuration:

| Key | Configuration applied |
|---|---|
| `architecturyCommon` | Fabric loader, Architectury annotation transforms, no platform Loom. Shadow-bundled into platform modules. |
| `architecturyFabric` | Fabric Loom, fabric-loader, Fabric API |
| `architecturyForge` | Forge Loom |
| `architecturyNeoforge` | NeoForge Loom |

Listing a module in an `architectury*` list **implicitly declares it in the matching plain list** as well. There is no need to list it twice.

```groovy
// settings.gradle
multiversionModules {
    // All three modules use Loom -- plain lists are implied:
    architecturyCommon   = ['common']
    architecturyFabric   = ['fabric']
    architecturyNeoforge = ['neoforge']
    patchModules = ['common', 'fabric', 'neoforge']
}
```

Only `architecturyCommon` modules are shadow-bundled into platform modules. A plain `common` module is treated as an external dependency.

#### Version discovery configuration

By default the settings plugin scans the project root for directories matching `^\d+(\.\d+){1,3}$` (e.g. `1.20.1/`, `1.21.1/`). Two optional fields let you customize this:

```groovy
// settings.gradle
multiversionModules {
    // Use a custom regex for version directory names
    versionPattern = '^\\d+\\.\\d+\\.\\d+$'

    // Or provide an explicit ordered version list (skips filesystem scanning)
    versions = ['1.20.1', '1.21.1']

    architecturyFabric = ['fabric']
    patchModules = ['fabric']
}
```

When `versions` is set, only those directories are used (in the given order). When module lists are populated, the settings plugin only includes subdirectories whose names match a declared module, instead of including every directory that contains source code.

#### `patchModules`

Lists the module names that get patchedSrc generation. Fully independent of the loader-type and architectury lists — any declared module can be patched. Each name must have corresponding directories under at least one version folder (e.g. `1.21.1/fabric/`). The plugin patches each module group independently: `fabric` versions are layered against each other, `forge` versions against each other, and so on.

If a module is declared in `patchModules` but is missing from a particular version folder, a warning is logged and that version is skipped for the module. No error is thrown.

#### `wireTask`

Wires all `:mc_version:module:taskName` into a single root-level `:taskName`. Subprojects that do not have the task are silently skipped. An optional filter closure restricts which subprojects are wired.

`wireTask` is called on the `multiversion` extension in `build.gradle` (not `settings.gradle`), since it depends on build-phase project references.

```groovy
// build.gradle
multiversion.wireTask 'runClient'
multiversion.wireTask 'remapJar', { p -> p.name == 'fabric' }
```

Running `./gradlew runClient` will execute `:1.20.1:fabric:runClient`, `:1.21.1:fabric:runClient`, etc. (whichever versioned subprojects have a `runClient` task).

#### `multiversionConfiguration`

A separate root-level closure for project-wide plugin behaviour settings.

```groovy
multiversionConfiguration {
    automaticArchApi = false                              // default
    resourcesConfigPath = "multiversion-resources.json"   // default
    changelogPath = "changelog.md"                        // default
}
```

| Property | Default | Description |
|---|---|---|
| `automaticArchApi` | `false` | When `true`, the plugin reads `architectury_api_version` from `gradle.properties` and adds the Architectury API dependency automatically to every module enrolled in an `architectury*` list. When `false`, the property is ignored and the dependency is your responsibility. |
| `resourcesConfigPath` | `"multiversion-resources.json"` | Filename of the resource patch configuration file inside each version module's `src/main/resources` directory. |
| `changelogPath` | `"changelog.md"` | Path to the changelog file (relative to project root) used for Modrinth/CurseForge publishing. |

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
Replaces a method, field, or constructor from the previous version. Equivalent to `@Overwrite` in Mixin.

```java
@OverwriteVersion
public static void init() {
    LOGGER.info("Initialized: version=1.21.1");
}

@OverwriteVersion
public MyClass(String name) {
    this.name = name + "_v2";
}
```

#### `@ShadowVersion`
Declares a reference to a method, field, or constructor that exists in the previous version, without replacing it. Equivalent to `@Shadow` in Mixin. Useful when a new method needs to call something from the base version.

Shadow declarations are stubs: the merge engine ignores any body or initializer you write on them. Methods can be declared bodyless, and fields without initializers are fine. The IDE plugin suppresses the usual "missing method body" and "not initialized" errors so trueSrc stays clean.

Constructors are the one exception: Java's grammar requires a constructor body, so write `{}`.
```java
public class MyClass {
    @ShadowVersion public Logger LOGGER;
    @ShadowVersion public static Logger LOGGER;
    @ShadowVersion public static final Logger LOGGER2;

    @ShadowVersion public static void existingMethod();
    @ShadowVersion public void otherMethod();

    // Constructors need an empty body.
    @ShadowVersion public MyClass(String name) {}

    // New method that calls into the base version
    public void newMethod() {
        existingMethod();
    }
}
```

#### `@DeleteMethodsAndFields`
Removes specific methods, fields, or constructors from the previous version. Useful when a method signature changes or something is no longer needed.
Use a bare name for unambiguous targets, or `methodName(ParamType1, ParamType2)` to disambiguate overloads.
To delete a constructor, use `init` as the name. If the class has multiple constructors, include the parameter types to disambiguate.

```java
@DeleteMethodsAndFields({"oldMethod", "renamedMethod(int, String)", "init(String)", "init()"})
public class MyClass { ... }
```

`init` alone (without parentheses) is also accepted when there is only one constructor to avoid ambiguity.

#### `@OverwriteTypeDeclaration`
Replaces the base version's type declaration (including `extends` and `implements` clauses) with this version's declarations. Use when a class changes its parent, the interfaces it implements, or its record components between versions. The entire type declaration is replaced -- to remove an interface, simply omit it.

```java
// 1.20.1/common — base version
public class MyRenderer extends OldRenderer implements OldInterface { ... }

// 1.21.1/common — changes parent and drops OldInterface
@OverwriteTypeDeclaration
public class MyRenderer extends NewRenderer implements NewInterface { ... }
```

Works for classes, interfaces (extends), enums (implements), and records (implements). Annotation types have no inheritance and are unaffected.

#### `@ModifySignature`
Changes a member's signature (name, type, or parameters) while keeping the body from the base version. The string value identifies the old member in the base version using the same descriptor format as `@DeleteMethodsAndFields`. Use a bare name for fields and unambiguous methods, or `methodName(ParamType1, ParamType2)` for overloaded methods. For constructors, use `init` or `init(ParamType1, ParamType2)`.

By default, the body is inherited from the base version. If you also add `@OverwriteVersion`, the body from the new version is used instead.

```java
// Rename a method (body carries over from base)
@ModifySignature("oldMethodName")
public void newMethodName() {}

// Change parameters (body carries over from base)
@ModifySignature("process(String)")
public void process(String input, int flags) {}

// Change parameters AND rewrite the body
@ModifySignature("process(String)")
@OverwriteVersion
public void process(String input, int flags) {
    // new implementation
}

// Rename a field (initializer carries over from base)
@ModifySignature("oldFieldName")
public String newFieldName;

// Change a constructor's parameters
@ModifySignature("init(String)")
public MyClass(String name, int id) {}
```

#### `@DeleteClass`
Removes the entire class from the merged output. Useful when a class is no longer needed in a newer version.

```java
@DeleteClass
public class ObsoleteHelper { ... }
```

The class is removed from `patchedSrc` at build time. It can be reintroduced in a later version layer without issue.

#### `@ModifyClass`
Marks a class as a modifier of an upstream class. Two use cases:

**1. Split a modification of one target across multiple files.** In one trueSrc version, multiple classes carrying `@ModifyClass(Target.class)` all contribute to the same merged target. They are called **sibling modifiers** and are virtually merged before the regular forward merge runs.

```java
// 1.21.1/common/.../MyModNetworkPatch.java
@ModifyClass(MyMod.class)
public class MyModNetworkPatch {
    @OverwriteVersion public void sendPacket(...) { ... }
}

// 1.21.1/common/.../MyModBehaviorPatch.java
@ModifyClass(MyMod.class)
public class MyModBehaviorPatch {
    @OverwriteVersion public void tick() { ... }
}
```

The merged `MyMod.java` in `patchedSrc` contains both methods. Neither `MyModNetworkPatch.java` nor `MyModBehaviorPatch.java` appears in `patchedSrc` — only the merged target.

**2. Target a class whose name or package differs from the modifier.** `@ModifyClass(Foo.class)` resolves the target by class literal (not a string), so IntelliJ's native rename/goto/find-usages works on the reference and cross-package modifications are unambiguous.

**Implicit `@ModifyClass`.** A class declared at the same rel path as an upstream class but without `@ModifyClass` is treated as an implicit base extension for backwards compatibility — it participates as a sibling when other modifiers explicitly target the same class. The IDE flags this with a yellow "missing explicit @ModifyClass" warning (quick fix adds it) whenever a multi-file modification set is detected.

**One-overwrite-per-version rule.** Within a single version, across all sibling modifiers of a target, each member signature may have at most one **defining** file. A defining member is one carrying `@OverwriteVersion`, `@ModifySignature`, or declared as a brand-new member. `@ShadowVersion` references are pure pointers and may be duplicated freely across siblings. The merge engine fails the build on any violation listing all offending files; the IDE surfaces live violations as red-error inspections.

**Class-level annotation synchronization.** Siblings must agree on `@OverwriteTypeDeclaration`, `@DeleteMethodsAndFields`, and `@DeleteClass` (presence and content). Plain class declaration bits (extends, implements, type parameters, modifiers) only need to agree across siblings when `@OverwriteTypeDeclaration` is active — otherwise the base version's declaration is preserved and sibling declarations are cosmetic.

**Hard errors.**
- **Orphan target** — `@ModifyClass(Foo.class)` where `Foo` does not exist in any earlier version's patchedSrc. Creating a new class uses a plain declaration with no `@ModifyClass`.
- **Inner-class placement** — `@ModifyClass` on a nested class. Inner-class support is not yet implemented.
- **Inner-class target** — `@ModifyClass(Outer.Inner.class)` targeting a nested class. Also deferred.

Each matches a live IDE inspection (red error) so violations surface at edit time.

> **IDE caveat — run a full build after adding or removing `@ModifyClass`.** The IDE's per-save incremental updater uses routing produced by the last full build. Adding or removing `@ModifyClass` on a file (creating or dissolving a sibling group) changes the routing, but the IDE won't know about the new group until the next full `generatePatchedJava` / `generateAllPatchedSrc`. The first save cycle after such an annotation change can produce stale patchedSrc for the affected group. **Run a full build whenever you add or remove `@ModifyClass`.** Edits *inside* an already-established group (method bodies, `@OverwriteVersion` / `@ShadowVersion` flips, `@ModifySignature` renames) don't need this — incremental updates handle them.

> **Here be dragons — `@ModifyClass` in the base version is unsupported.** The base (oldest) version has no upstream to modify, so Extensions targeting a same-version Target don't have a well-defined semantic. The engine's base-version update path always writes per-file origin entries for the edited rel; it does not resolve sibling groups or rewrite Extension entries against their Target. Keep cross-name modifiers out of the base version.

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

The generated `gradle.properties` is pre-filled with `minecraft_version` and `enabled_platforms`. You still need to add the loader-specific version properties before the project will build.

Properties required for modules enrolled in an `architectury*` list:

| Property | Required for |
|---|---|
| `enabled_platforms` | All Architectury-enrolled modules (comma-separated list of loaders, e.g. `fabric,neoforge`) |
| `fabric_loader_version` | `architecturyCommon` and `architecturyFabric` modules |
| `fabric_api_version` | `architecturyFabric` modules |
| `forge_version` | `architecturyForge` modules |
| `neoforge_version` | `architecturyNeoforge` modules |
| `architectury_api_version` | Only when `multiversionConfiguration { automaticArchApi = true }` |

Properties shared across all versions (`mod_id`, `mod_name`, `mod_version`, `archives_name`, `maven_group`) belong in the root `gradle.properties` and are inherited automatically.

---

### Publishing

The Gradle plugin can publish built mod JARs to CurseForge and/or Modrinth in one task. Publishing is opt-in and per-platform: provide `curseforge_id` to publish to CurseForge, `modrinth_id` to publish to Modrinth, or both. If neither ID is set, publishing is disabled entirely. If only one is set, only that platform is configured.

#### `gradle.properties`

These go in the root `gradle.properties`:

| Property | Required | Description |
|---|---|---|
| `mod_name` | Yes | Display name of the mod |
| `mod_version` | Yes | Version string, e.g. `1.0.0` |
| `release_channel` | Yes | One of `alpha`, `beta`, or `stable` |
| `mod_client` | Yes | `true` or `false` |
| `mod_server` | Yes | `true` or `false` |
| `curseforge_id` | No | Numeric project ID from CurseForge. Omit to skip CurseForge publishing. |
| `modrinth_id` | No | Project slug or ID from Modrinth. Omit to skip Modrinth publishing. |
| `curseforge_dependencies` | No | Comma-separated CurseForge project IDs for required dependencies |
| `modrinth_dependencies` | No | Comma-separated Modrinth slugs for required dependencies |

At least one of `curseforge_id` or `modrinth_id` must be set for publishing to be enabled. Leave dependency properties empty or omit them if there are none.

#### Environment variables

| Variable | Description |
|---|---|
| `CURSEFORGE_TOKEN` | CurseForge API token (only needed if `curseforge_id` is set) |
| `MODRINTH_TOKEN` | Modrinth API token (only needed if `modrinth_id` is set) |

These are read at publish time. They are never required for building.

#### Changelog file

Place a `changelog.md` file at the root of the project (or the path configured via `multiversionConfiguration { changelogPath = "..." }`). Its contents are sent as the release description to both CurseForge and Modrinth. If the file is absent, the upload proceeds with a placeholder message.

#### Running a publish

Two publish tasks are available:

```bash
./gradlew publishAllMods
```

`publishAllMods` builds, collects, and publishes all mod JARs to configured platforms in one step. It automatically runs `collectBuildsAll` before publishing.

```bash
./gradlew publishAllSafe
```

`publishAllSafe` does the same thing but runs in **dry-run mode** by default. To actually publish, pass `-PPUBLISH_RELEASE=true`:

```bash
./gradlew publishAllSafe -PPUBLISH_RELEASE=true
```

This is useful for verifying that credentials and properties are wired correctly before committing to a release.

You can also collect JARs without publishing by running `collectBuildsAll` directly, or collect a single subproject via its `collectBuilds` task (e.g. `./gradlew :1.21.1:neoforge:collectBuilds`). Collected JARs are placed under `builds/<mod_version>/<minecraft_version>/<loader>/`.

---

### Development flow

patchedSrc is the merged source tree that the IDE uses for error checking, navigation, and compilation.

With the IDE plugin installed, patchedSrc is refreshed automatically.
The main exception to this is field and method body, which stay stale until a full version refresh is triggered.
This is not an issue while you're coding, because method/field removals (and therefore reference viability) are refreshed automatically.
If you are manually debugging patchedSrc and want to update method/field bodies without a full version refresh, saving the file manually (Usually CTRL + S in IDEA) will refresh bodies.

The base version does not have a patchedSrc Java output (its trueSrc is already the compile source), but it does have an `_originMap.tsv` that is refreshed on save just like every other version.

If you want to manually refresh all patchedSrc across all versions and modules, run the gradle task:
```bash
./gradlew generateAllPatchedSrc
```

This task also runs automatically on build, so compilation always uses the latest merged output.

---

### IDE plugin

An IntelliJ IDEA plugin is available on the JetBrains Marketplace, simply called Minecraft Multiversion Modding. It is strongly recommended. Without it you will run into false duplicate class errors and broken navigation caused by the multiple source sets.

- **Error suppression**: Hides false duplicate class errors, uninitialized field warnings on `@ShadowVersion` members, and "missing method body" errors on bodyless stubs whose counterpart exists in a previous version. The remaining quick fix on such stubs is the correct one (add `@ShadowVersion` / `@OverwriteVersion`).
- **Version navigation**: `Alt+W` / `Alt+S` to jump upstream/downstream. Context-sensitive: navigates the member at cursor, or the class if not on a member. The arrow only appears when a trueSrc version exists in that direction, potentially multiple versions away, and follows `@ModifySignature` rename chains automatically. Replaces the current tab in-place.
- **All-versions popup**: `Alt+Shift+V` shows every version of the current member or class. Labels display the multiversion annotations declared in that version (`@OverwriteVersion`, `@ShadowVersion`, `@ModifySignature`, `(new)`, `(base)`) using the editor's annotation colour; inherited and absent versions are greyed. Class-level entries show the trueSrc filename per version. Clicking a row (or pressing Enter) replaces the current tab with the chosen version; Shift+click opens it in a new tab without closing the current one.
- **Gutter icons**: Up and down arrows appear independently when there is a trueSrc version in that direction at any distance. Click to navigate.
- **Annotation validation**: Validates `@DeleteMethodsAndFields`, `@ModifySignature`, and other descriptor strings against the actual members in the previous version. Flags missing `@OverwriteVersion`/`@ShadowVersion` on members that exist in the base; the quick fix adds the annotation and its import. Also flags `@OverwriteVersion`/`@ShadowVersion` where no matching upstream member exists at all; the quick fix removes the offending annotation.
- **Refactoring**: Renames propagate across all versions and patchedSrc, including descriptor strings in annotations.
- **Find Usages**: Searches across patchedSrc and remaps results back to trueSrc. The inline "n usages" code lens may show an incorrect count due to an IntelliJ limitation, but clicking it runs the correct search.

It is also recommended to use the Minecraft Development plugin for general mod development support. The Architectury plugin can also be useful.

---

### Credits

- [Architectury](https://github.com/architectury/architectury-loom): Handles all platform-specific build logic. Multiversion wraps around it and would not function without it.
- [mod-publish-plugin](https://github.com/modmuss50/mod-publish-plugin) by modmuss50: the plugin powering CurseForge and Modrinth publishing. The publishing functionality in Multiversion is a thin configuration layer on top of it.