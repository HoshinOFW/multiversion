# Merge Engine Changelog

## 0.2.13

- **Origin map wire format v2** Header line `# multiversion-originmap v2` at the top of every `_originMap.tsv`. Member values are compact `V:S:L:C` (version index, sibling index inside the target's `@ModifyClass` routing, 1-based line, 1-based col); file values are `V:S`. Old (v1) files are refused with a clear error instructing the user to run `generateAllPatchedSrc`
- **Decl column** Member entries gain an optional inline decl segment `V:S:L:C|S:L:C`. Decl is present only when the member carries `@OverwriteVersion`, `@ModifySignature`, or is new in this version, and points at the member's declaration in the current version's trueSrc. Fixes bodyless-member navigation (clicking `@ModifySignature` rows in the Alt+Shift+V popup used to land on the class line)
- **Typed `CompactPos`** New data class; new getters `getMemberBody`, `getMemberDecl`, `getFileOrigin`. `OriginMap.parseValue` removed; consumers go through `OriginResolver`
- **`OriginResolver` constructor** Now takes `versions: List<String>` + `moduleName: String` + `routingFor: (Int) -> ClassRoutingMap?` so it can expand `(V, S)` back to a sibling rel + trueSrc path. New `resolveMemberDeclaration(rel, key, currentVersionIdx)` returns the decl position when present
- **`OriginNavigation` flag filter** Walk / nearest / hasMember / hasClass functions take a `filter: Set<OriginFlag>`. Empty set matches any entry (trueSrc or purely inherited) for existence checks. Non-empty set matches entries whose flags intersect. Three pre-built constants: `DECLARATION_FLAGS = {OVERWRITE, MODSIG, NEW}` for gutter arrows / keybinds, `SIGNATURE_FLAGS = {MODSIG, NEW}` for canonical-declaration lookups, `ANY_DECLARATION_FLAGS = {OVERWRITE, SHADOW, MODSIG, NEW}` for "any trueSrc declaration". `MemberVersionView.Inherited` / `ClassVersionView.Inherited` no longer carry the raw origin string.
- **`OriginNavigation.hasMember` / `hasClass`** New dedicated early-exit entry points, separate from `nearestMember` / `nearestClass` so boolean callers don't allocate a hit record. `hasTrueSrcMember` / `hasTrueSrcClass` removed; all callers migrated.
- **`ClassRoutingMap` alphabetical ordering** Modifier lists kept sorted in memory so `getModifiers(target)` matches the sidecar order the engine's sibling-index encoding relies on. New helpers `indexOfModifier(target, modifier): Int` and `modifierAtIndex(target, idx): String?`
- **Version-index plumbing** `versionUpdatePatchedSrc`, `mergeFileContent(ToFile)`, `mergeFileFromFiles`, `fileUpdatePatchedSrc`, `siblingGroupUpdatePatchedSrc`, `synthesizeFromTrueSrc` all take `versionIdx: Int` (and `baseVersionIdx` where relevant) instead of `currentSrcRelRoot` / `baseRelRoot` strings
- **`baseVersionUpdatePatchedSrc`** New public entry point. Synthesises an `_originMap.tsv` from the base (oldest) version's trueSrc and writes it atomically, plus writes permissive routing sidecars for any `@ModifyClass` groups. The base version now has a real origin map like every other version; in-memory synthesis is reserved for unbuilt downstream versions
- **Loud synthesis** `synthesizeFromTrueSrc` now returns `SynthesisResult(map, failures)` and takes a `tolerateParseErrors` flag. Gradle calls with `false` so parse errors during a full build fail loudly; the IDE cache calls with `true` (unbuilt versions may have mid-edit files). Catches `Throwable` so JavaParser `AssertionError` on malformed input is captured into `failures` instead of crashing
- **BUG FIX** Virtual-target origin rewrite no longer overwrites inherited body positions. `@ShadowVersion` members in a `@ModifyClass` group now keep their body origin pointing at the upstream declaration rather than being rewritten to the current-version shadow-stub position. Find Usages and Go To Declaration on shadow members now land on the real upstream body

## 0.2.12

- **`@ModifyClass` support** New `ModifyClassPreMerge` virtual pre-merge phase: parses trueSrc for `@ModifyClass` annotations, resolves targets via class-literal + import / same-package + shrinking-prefix file IO check against v-1 patchedSrc and current trueSrc, groups siblings by target, validates every contract (orphan target, inner-class placement, inner-class target, one-overwrite rule, class-level annotation sync, multiversion-class-annotation presence sync, import simple-name collisions), and produces virtual target compilation units fed into the existing forward merge
- **`ClassRoutingMap`** New data class beside `EngineConfig`. Forward (`getModifiers`) + backward (`getTarget`) in-memory indexes, bulk load/write for full builds (`fromPatchedSrcDir`, `writeSidecars`, `pruneStaleSidecars`), and a nested `Sidecars` object for per-target I/O used by the file-level sibling-group entry and IDE refactor plumbing (`writeOne`, `renameTarget`, `renameModifier`). Mirrors the `OriginMap` API pattern so consumers never manipulate `.routing` files directly
- **`ModifyClassPreMerge.synthesizeRoutingFromTrueSrc(trueSrcDir)`** Permissive routing scan, no validation, used by the IDE cache as a fallback for versions without generated sidecars (base version, unbuilt versions). Mirrors `MergeEngine.synthesizeFromTrueSrc`
- **`ModifyClassPreMerge.buildVirtualTargetFromContents`** In-memory content-based peer of the directory-walking pre-merge. Runs in-group validation (one-overwrite, annotation sync, import collisions, class-declaration sync under `@OverwriteTypeDeclaration`). Orphan and inner-class-target checks are out of scope here (require cross-version visibility; handled by the full-version entry and IDE inspections)
- **`MergeEngine.siblingGroupUpdatePatchedSrc`** New public entry point at file-level sibling-group scope. Peer to `mergeFileContentToFile` / `fileUpdatePatchedSrc`. Takes in-memory sibling contents + base content, runs pre-merge + merge for one target group, writes the merged class, patches the origin map, updates the routing sidecar. Does not cascade — callers loop this per affected version. Used by IDE listeners for per-save `@ModifyClass` edits; Gradle's full-version `versionUpdatePatchedSrc` path is untouched
- **`True2PatchMergeEngine.processVersion` integration** Calls pre-merge first; skips modifier rels in the trueSrc walk; merges each virtual target via new `mergeVirtualTarget` / `mergeVirtualTargetContent` that post-process TRUESRC origin entries to rewrite path + position to the real sibling source (since the virtual CU's positions are re-parsed against the printed text and meaningless for navigation); deletes orphan modifier file copies from patchedOutDir; writes and prunes routing sidecars; skips virtual target rels in the inherited-origins phase
- **Shared `JavaParserHelpers`** New engine-internal file consolidating the JavaParser instance, position helpers, descriptor wrappers, and type-aware member accessors that were previously duplicated across `True2PatchMergeEngine` and `ModifyClassPreMerge`
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