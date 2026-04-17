# Merge Engine Changelog

## 0.2.12

- Added rename tracking entries (`!rename#` / `!renamed#`) to origin map for @ModifySignature, enabling IDE version walking without opening files
- Added `getRenameOldName`, `getRenameNewName`, `getMembersForFile` query methods to `OriginMap`
- Added `resolveAllMembers` method to `OriginResolver`
- **BUG FIX** `@ModifySignature` without `@OverwriteVersion` no longer records the current-version file as origin; origin now points to the inherited body in the base version, matching the body provenance semantics
- **Origin map flags** Origin map TSV entries gained an optional third column carrying `TRUESRC`, `OVERWRITE`, `SHADOW`, `MODSIG`, `NEW` tokens. Backward compatible with 2-column files. New `OriginFlag` enum centralises the wire-format tokens so IDE consumers never duplicate strings
- **File-level TRUESRC** File-level origin entries also carry `TRUESRC` when the current version owns the trueSrc file; `isFileInTrueSrc(rel)` query exposes it
- New `OriginNavigation` object owns version-walking logic: upstream/downstream member and class walks, all-versions aggregation, and `@ModifySignature` rename-chain following. Callers pass in an ordered `List<OriginMap?>` and receive hits with version index, post-rename key, and flag set
- New `MergeEngine.synthesizeFromTrueSrc` API synthesises an in-memory origin map by walking a trueSrc directory and running the brand-new-class merge path per file. Used as a fallback for versions without a generated origin map (base version, or unbuilt patched versions)
- Brand-new-class merge emits `TRUESRC NEW` on every member; `!hasTriggers` copy emits `TRUESRC` only (per-member annotation info is not inferred where the engine intentionally skips base parsing)

## 0.2.11

- `ResourcePatchConfig.fromDirectory()` now accepts an optional `filename` parameter (default unchanged)
- Added `ResourcePatchConfig.DEFAULT_FILENAME` constant

## 0.2.10

- `@OverwriteInheritance` renamed to `@OverwriteTypeDeclaration`

## 0.2.9

- Version pattern now accepts single-number versions (e.g. "26") and any number of dot-separated parts, supporting the upcoming Minecraft version scheme

## 0.2.8

- Added shared descriptor resolution utilities to `MemberDescriptor`: `parseDescriptor()`, `resolveInitAmbiguity()`, `matchesParams()`
- Both engine and IDE now use these shared functions instead of independent implementations

## 0.2.7

- Added `CachedOriginMap` for file-modification-aware caching of origin maps

## 0.2.6

- Added `ResourcePatchConfig` and `ResourcePatchEngine` for resource patching (delete/move operations from `multiversion-resources.json`)

## 0.2.5

- Added `EngineConfig` data class with `fromJson()`/`toJson()`/`fromFile()`/`toFile()` as the single source of truth for config serialization

## 0.2.4

- Retain multiversion annotations (@ShadowVersion, @OverwriteVersion, @ModifySignature) in patchedSrc output
- Retain multiversion imports in patchedSrc
- Retain class-level processing annotations (@DeleteMethodsAndFields, @OverwriteTypeDeclaration) in patchedSrc