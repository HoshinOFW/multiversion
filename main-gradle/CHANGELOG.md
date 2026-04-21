# Gradle Plugin Changelog

## 0.6.1

- Added `**/*.routing` exclusion to the patchedSrc `main.java` sourceSet so `@ModifyClass` routing sidecars produced by the merge engine never enter compilation, the output jar, or sourcesJar
- `MultiversionPatchedSourceGeneration` computes `versionIdx = versions.indexOf(patchVer)` and threads it through every engine call in place of the old path-prefix string arguments. File-level origin bookkeeping during the copy pass emits compact `V:0` entries; merged-file entries overwrite them. Aligns with merge-engine v2 compact origin-map wire format
- New `generateBaseOriginMap` task registered on the base (oldest) version of each patch module group. Calls `MergeEngine.baseVersionUpdatePatchedSrc` to synthesise and write `build/patchedSrc/_originMap.tsv` for the base. `generateAllPatchedSrc` depends on it; each first-patched version's `generatePatchedJava` depends on its base counterpart so the base map is written before anyone reads it

## 0.6.0

- `multiversionModules` extension moved to `shared-gradle-api`; now configured in `settings.gradle` instead of `build.gradle`
- `wireTask` moved from `multiversionModules` to the `multiversion` project extension (`multiversion.wireTask` in build.gradle)
- Main plugin reads the modules extension from the settings phase, with fallback for legacy build.gradle config
- Added `shared-gradle-api` as a dependency
- Added `resourcesConfigPath` to `multiversionConfiguration` for custom resource patch config filename
- Added `changelogPath` to `multiversionConfiguration` for custom publishing changelog path

## 0.5.5

- Config writer now uses engine's `EngineConfig.toFile()` instead of ad-hoc Groovy JSON serialization
- Resource patching now uses engine's `ResourcePatchConfig` and `ResourcePatchEngine` instead of local Groovy implementation
- Removed dead methods from `PatchingUtil` (`normalizeResourceDeleteEntries`, `applyResourceDeletes`)
- Version detection now delegates to engine's `VersionUtil.looksLikeVersion()` instead of inline regexes
- Deleted dead `CollectionUtil.compareMcVersions()` and all its private helpers
- Publishing to CurseForge and Modrinth is now independent: omit `curseforge_id` or `modrinth_id` to skip that platform
- Added `wireTask` DSL for aggregating versioned subproject tasks into a single root task, with optional filter

## 0.5.4

- Current release