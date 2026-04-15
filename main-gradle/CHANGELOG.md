# Gradle Plugin Changelog

## 0.5.5

- Config writer now uses engine's `EngineConfig.toFile()` instead of ad-hoc Groovy JSON serialization
- Resource patching now uses engine's `ResourcePatchConfig` and `ResourcePatchEngine` instead of local Groovy implementation
- Removed dead methods from `PatchingUtil` (`normalizeResourceDeleteEntries`, `applyResourceDeletes`)
- Version detection now delegates to engine's `VersionUtil.looksLikeVersion()` instead of inline regexes
- Deleted dead `CollectionUtil.compareMcVersions()` and all its private helpers
- Publishing to CurseForge and Modrinth is now independent: omit `curseforge_id` or `modrinth_id` to skip that platform

## 0.5.4

- Current release