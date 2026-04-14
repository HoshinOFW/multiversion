# Merge Engine Changelog

## 0.2.5

- Added `EngineConfig` data class with `fromJson()`/`toJson()`/`fromFile()`/`toFile()` as the single source of truth for config serialization

## 0.2.4

- Retain multiversion annotations (@ShadowVersion, @OverwriteVersion, @ModifySignature) in patchedSrc output
- Retain multiversion imports in patchedSrc
- Retain class-level processing annotations (@DeleteMethodsAndFields, @OverwriteInheritance) in patchedSrc