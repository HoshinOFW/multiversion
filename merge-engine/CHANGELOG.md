# Merge Engine Changelog

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
- Retain class-level processing annotations (@DeleteMethodsAndFields, @OverwriteInheritance) in patchedSrc