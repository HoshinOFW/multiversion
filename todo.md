## @ModifyClass Implementation

### Status

**Done**
1. `@ModifyClass` annotation (annotations module).
2. `ClassRoutingMap` in merge-engine with per-target line-oriented sidecar read/write and stale-sidecar pruning.
3. `ModifyClassPreMerge` in merge-engine: parses trueSrc for `@ModifyClass`, resolves FQN via imports/package + shrinking-prefix file-IO check against v-1 patchedSrc + current trueSrc, validates all contracts (orphan, inner-class placement, inner-class target, one-overwrite, class-level annotation sync, import simple-name collisions), produces virtual target CUs with pre-print member-source positions recorded.
4. `True2PatchMergeEngine.processVersion` integrates pre-merge: skips modifier rels in the trueSrc walk, merges each virtual target via `mergeVirtualTarget` that post-processes TRUESRC origin entries to rewrite path + position to the real sibling source, deletes orphan modifier copies, writes + prunes routing sidecars, skips virtual target rels in the inherited-origins phase.
5. Gradle `main.java.exclude("**/*.routing")` so sidecars never enter compile or sourcesJar.
6. Shared `JavaParserHelpers` (engine-internal): parser instance, position helpers, descriptor wrappers, type-aware accessors. Used by both `True2PatchMergeEngine` and `ModifyClassPreMerge`.
7. `ModifyClassPreMerge.synthesizeRoutingFromTrueSrc(trueSrcDir)` + `MergeEngine.synthesizeRoutingFromTrueSrc` public wrapper. Permissive, engine-owned, no PSI.
8. `MergeEngineCache` extended with `ClassRoutingMap` loading (sidecar primary, synthesized fallback for unbuilt versions), invalidation on `_originMap.tsv` mtime, query helpers in both directions.
9. `AnnotationFqns.kt` gains `MODIFY_CLASS_FQN`.
10. IDE navigation: `NavigationContext` carries per-version routing + caret-rel-translated-to-target-rel. `openMemberHit` / `openClassHit` use origin-map values to land on the real sibling file. `ShowAllVersionsPopupAction` fans out one row per sibling in multi-modifier versions.
11. IDE refresh listeners: `PatchedSrcUpdater.updatePatchedSrcWithCascade` is a per-module dispatch. Each module's routing view decides between the existing single-file fast path (`MergeEngine.mergeFileContentToFile`) and the new file-level sibling-group entry (`MergeEngine.siblingGroupUpdatePatchedSrc`). Downstream propagates at the resolved target rel (not the edited rel) for cross-name modifier support. Gradle's full-version `MergeEngine.versionUpdatePatchedSrc` is untouched and never called from the IDE listeners.
12. IDE refactor plumbing: `MoveListener` delegates sidecar maintenance to `ClassRoutingMap.Sidecars.renameTarget` / `renameModifier`. IDE never opens or writes `.routing` files directly — same pattern as how it accesses the origin map through `OriginMap`, not raw TSV I/O.
13. IDE inspections: `MissingExplicitModifyClassInspection` (yellow + quick fix), `OrphanModifyClassTargetInspection` (red, covers missing target + inner-class target), `InnerClassModifyClassInspection` (red).

**Remaining**
14. mod-template test examples: single modifier, multiple siblings, cross-package, expected-to-fail orphan / conflict / inner-class cases committed as permanent regression examples.

### What it is

`@ModifyClass` marks a class that modifies another class from a previous version. Currently, class matching across versions is always by file name (Foo.java in v1 matches Foo.java in v2). `@ModifyClass` changes this in two ways:

1. **Cross-name and cross-package targeting**: `@ModifyClass(Foo.class)` on `FooNetworkPatch.java` targets the upstream class `Foo`, regardless of the modifier's filename or package. The target is a `Class<?>` literal, not a string. This gives native IntelliJ goto-declaration, find-usages, and rename-refactor support on the target reference, plus compile-time enforcement that the target actually exists. `@ModifyClass` with no argument is shorthand for `@ModifyClass(ThisClass.class)`, preserved as the backwards-compatible implicit form.

2. **Multiple modifiers per target**: Multiple files in one trueSrc version can target the same upstream class (for example `FooNetworkPatch.java` and `FooBehaviorPatch.java` both carrying `@ModifyClass(Foo.class)`). These are called **sibling modifiers**. They are virtually merged into one target class in memory before the existing forward merge runs.

Hard errors:
- `@ModifyClass` placed on an inner class. Inner-class support is deferred; for now the engine rejects it.
- `@ModifyClass(Foo.class)` with no upstream `Foo` in any earlier version. Creating new classes uses a plain declaration with no annotation.

### One-overwrite-per-version rule

Within a single trueSrc version, across all sibling modifiers of a target, each member signature has at most one **defining** file. Defining means the member carries `@OverwriteVersion`, `@ModifySignature`, or is a brand-new addition. `@ShadowVersion` references are pure pointers and may be duplicated freely across siblings.

This keeps every member's version lifeline one-dimensional: walking up or down through versions always lands on exactly one modifier file per version at the member level. Class-level navigation can still surface multiple modifier files per version through the show-all-versions popup.

The merge engine fails the build hard on any violation, listing all offending files. The IDE surfaces live violations as red-error inspections (see Todo after).

### Class-level annotation synchronization

All siblings targeting the same class in one trueSrc version must agree on the multiversion class-level statements that actually reach the merged output:
- **Presence** of `@OverwriteTypeDeclaration`, `@DeleteMethodsAndFields`, and `@DeleteClass` — either every sibling has a given annotation or none do.
- `@DeleteMethodsAndFields` descriptors must match as a set when present on all.
- **Class declaration** (extends, implements, type parameters, modifiers) only when `@OverwriteTypeDeclaration` is present on all siblings. Without `@OverwriteTypeDeclaration` the forward merge preserves the base version's declaration unconditionally, so extension-sibling declarations are cosmetic (`public class FooExt` is fine targeting `public abstract class Foo`) and not checked.

Any mismatch is a hard engine error and a red IDE inspection (see Todo after).

### Central solution: per-target routing sidecars

For every target class whose modifiers are not all same-named, the gradle plugin writes a sidecar next to the merged output class in patchedSrc. Plain text, one modifier rel path per line, alphabetical:

```
# patchedSrc/.../com/example/Foo.java.routing
com/example/FooBehaviorPatch.java
com/example/FooNetworkPatch.java
```

Chosen over a single large JSON/TSV so that editing one modifier only invalidates its one sidecar, and parsing is a trivial line-read. Targets whose single modifier is same-named rely on implicit routing (no sidecar).

The patchedSrc sourceSet excludes `**/*.routing` so the files never enter the compile classpath or the jar.

**At IDE runtime:** sidecar files are the single source of truth, produced by the merge engine. The IDE cache loads them lazily and invalidates when `_originMap.tsv` mtime changes (same task rewrites both, so this is a correct coarse proxy; the cache is shaped so it can move to per-sidecar mtime later without disturbing call sites). For versions with trueSrc but no sidecars (base version, unbuilt versions), the cache falls back to engine-synthesized routing via `ModifyClassPreMerge.synthesizeRoutingFromTrueSrc` — mirrors the existing `MergeEngine.synthesizeFromTrueSrc` pattern for origin maps. No IDE-side PSI scanning for `@ModifyClass`; the edit-vs-rebuild lag window is handled by the same listeners that keep patchedSrc fresh.

**Extensibility for inner classes:** inner classes will eventually get their own sidecar entries (e.g. `Foo$Inner.java.routing`) living alongside the outer class's sidecar. Current format needs no change to accommodate this later.

### Virtual pre-merge in the engine

The pre-merge lives in the merge-engine module (alongside the forward merge; engine owns all merge and OriginMap logic per `Engine vs IDE split`). It is **virtual**: no temp files, no disk roundtrip. The engine's merge entry points gain in-memory overloads so pre-merged CompilationUnits feed directly into the existing pipeline:

- Scan each version's trueSrc CompilationUnits for `@ModifyClass`, resolving each class literal to an FQN via the modifier file's imports (or same-package default).
- Group siblings by target FQN. Validate: no orphan target, no inner-class placement, one-overwrite rule across members, full class-level annotation synchronization. All validation failures are hard errors with all offending paths.
- Produce a virtual target `CompilationUnit` per target: union siblings' imports (simple-name collisions across different packages are a hard error), combine members (conflict-free by construction thanks to the one-overwrite rule), adopt the agreed class declaration and class-level annotations.
- Hand each virtual CU to the forward merge at its target rel path. OriginMap entries for pre-merged members point at the **real** source file (e.g. `FooNetworkPatch.java`), not the virtual target. This preserves goto-declaration and find-usages accuracy.
- Processing order across siblings is alphabetical by source filename. Order is cosmetic only (affects member layout in the merged class) because the one-overwrite rule forbids conflicts.

### Breaking points and fixes

Severity: MAJOR (significant rework), MODERATE (targeted changes), MINOR (trivial).

Most of what originally looked MAJOR in the engine collapses to MODERATE thanks to the virtual pre-merge: the existing merge pipeline still sees a single CompilationUnit at the target rel path, so `mergeContent` / `collectInheritedOrigins` don't need `sourceRel` vs `targetRel` splits.

#### Annotations module

**`@ModifyClass`** -- NEW
- `@Target(TYPE)`, `@Retention(SOURCE)`.
- Single optional `Class<?> value()`, defaulting to a self-sentinel (meaning "implicit same-name target").
- No string form.
- Engine rejects placement on inner classes.

#### Merge engine

**Pre-merge subsystem** -- NEW (see section above)
- Parses and groups siblings, validates all contracts, produces virtual target CUs.
- Also exposes `synthesizeRoutingFromTrueSrc(trueSrcDir)` for the IDE cache's fallback path on versions without a generated sidecar set (base version, unbuilt versions). Permissive: no existence / inner-class / one-overwrite checks — purely a routing read for navigation. Mirrors `MergeEngine.synthesizeFromTrueSrc`.

**`ClassRoutingMap`** -- NEW
- Data class beside `EngineConfig` with forward (`getModifiers`) + backward (`getTarget`) in-memory indexes, bulk load/write (`fromPatchedSrcDir`, `writeSidecars`, `pruneStaleSidecars`), and a nested `Sidecars` object for per-target I/O used by `siblingGroupUpdatePatchedSrc` and IDE refactor plumbing (`writeOne`, `renameTarget`, `renameModifier`).

**`True2PatchMergeEngine.processVersion()`** -- MODERATE
- Run pre-merge first to build the virtual target map for this version.
- Iterate targets (not modifier files directly). For targets with modifiers, hand the virtual CU to the merge; for plain trueSrc files without `@ModifyClass`, behavior is unchanged.
- Emit per-target routing sidecars alongside output.

**`True2PatchMergeEngine.mergeFile()` / `mergeContent()`** -- MODERATE
- Accept an in-memory virtual `CompilationUnit` in addition to the File-based path.
- `rel` semantics remain "target rel path"; correct for both direct trueSrc files and virtual pre-merged CUs.

**`True2PatchMergeEngine.collectInheritedOrigins()`** -- MODERATE
- Today checks raw `File(currentSrcDir, rel).exists()` to detect inherited files. With `@ModifyClass`, a target may be modified by differently-named files. Consult the pre-merge routing instead of raw file existence.

#### Gradle plugin

**`MultiversionPatchedSourceGeneration.patchModuleGroup()`** -- MODERATE
- Detect `@ModifyClass` modifier files during file collection.
- Exclude modifier files from direct emission to patchedSrc; they're consumed by the pre-merge only.
- Hand the merge engine the full set of modifier files per version so pre-merge has everything.
- Override detection ("if version k has target T, exclude T from earlier layers") uses the routing-derived target FQN, not modifier filenames.
- Write per-target `.routing` sidecars next to merged outputs.
- Ensure the patchedSrc sourceSet excludes `**/*.routing`.

#### IDE plugin -- navigation

**`MergeEngineCache`** -- MODERATE
- Extend to load and cache `ClassRoutingMap` per module root, invalidating on `_originMap.tsv` mtime (coarse; sidecars are written atomically with the origin map in the same task).
- For module roots without a generated origin map (base version, unbuilt), fall back to `ModifyClassPreMerge.synthesizeRoutingFromTrueSrc`. Cached separately and keyed on trueSrc dir mtime, same shape as `syntheticOriginMapForModuleRoot`.
- Expose helpers: "what modifier files target class X in version v" and "what class does this modifier target".
- Structure the call sites so switching from coarse (origin-map-mtime) to fine (per-sidecar-mtime) invalidation later is a local change inside the cache.

**`VersionMemberUtil.findAdjacentVersionClass()`** -- MODERATE
- Upstream: unchanged (patchedSrc already holds the merged file at the target rel path).
- Downstream: consult routing to find modifier file(s) for the target class in the next version.

**`VersionMemberUtil.findAllVersionClasses()`** -- MODERATE
- Per version, look up routing for modifier files. For versions without modifiers for that target, fall back to same-rel-path lookup.

**`VersionModuleUtil.findCorrespondingFile()`** -- MODERATE
- Use routing for cross-name matching.

**`ShowAllVersionsPopupAction`** -- MODERATE
- Show one entry per modifier file per version when siblings exist. Popup model already supports this.

**Member-level navigation** -- NOT BROKEN
- OriginMap already points at the defining modifier per member. The one-overwrite rule guarantees no ambiguity at member level, so no popup or fallback is needed there.

#### IDE plugin -- refactoring

**Rename of the target class** -- NOT BROKEN (thanks to class-literal value)
- `@ModifyClass(Foo.class)` is a real class reference; IntelliJ's native rename propagation updates it automatically across all modifier files. No plugin work.

**`MultiversionMoveListener` move propagation** -- MODERATE
- Today moves the same rel path in all versions. For modifiers at different paths, consult routing to find all related files.

**`MultiversionMoveListener.updateTsvForRename()`** -- MODERATE
- OriginMap TSV entries for modifier members are keyed by target rel path. Look up routing before patching.

**Refresh listeners for modifier edits** -- MODERATE
- `OnSaveListener` and `PsiStructureListener` currently trigger `MergeEngine.mergeFileContentToFile` / `fileUpdatePatchedSrc` at the edited file's rel path. That is wrong when the edited file is a modifier: editing `FooPatch.java` must regenerate the merged **target** `Foo.java` (running pre-merge for the whole sibling group), not a standalone merge at `FooPatch.java`'s rel path.
- Every IDE-initiated merge operation (on-save, structural change, move cascade, rename cascade, delete cascade) consults the routing cache: if the edited/moved/deleted file is a modifier, operate on the target rel (and the whole sibling group) instead.
- Edits to `@ModifyClass` itself, class-level annotations, and member bodies are all covered by this single redirect.

#### IDE plugin -- inspections

Each engine hard error has a matching live IDE inspection so violations surface at edit time instead of at build time.

**Missing explicit `@ModifyClass` warning** -- NEW
- Yellow warning on a file that participates in a multi-file modification set but lacks an explicit `@ModifyClass` annotation (typical case: `Foo.java` with no annotation while `FooPatch.java` has `@ModifyClass(Foo.class)` in the same version).
- Quick fix: add `@ModifyClass` (or `@ModifyClass(Foo.class)` if the class name differs from the target).

**Orphan target error** -- NEW
- Red error on `@ModifyClass(Foo.class)` when `Foo` is not present in any earlier version's patchedSrc. Existence in the current version's patchedSrc (the one being produced from this trueSrc) does not count; the target must already exist upstream.
- Paired with the engine hard error of the same name.

**Inner-class placement error** -- NEW
- Red error on `@ModifyClass` when the annotation sits on a non-top-level class declaration. Inner-class support is deferred.
- Paired with the engine hard error of the same name.

#### Not broken (works as-is)

- **`findMatchingMember()`** -- matches within a class by name + params, agnostic to how the class was found.
- **OriginMap / PatchedSrcGotoDeclarationHandler** -- keyed by output path; works since origin entries point at the real modifier source.
- **PatchedSrcFindUsagesHandler** -- scans full patchedSrc directories, path-agnostic.
- **`isOverriddenDownstream()`** -- transitively fixed via `findAllVersionClasses`.
- **`DescriptorAnnotationSupport`** -- resolves descriptors within a class, path-agnostic.
- **Target-class rename refactor** -- handled by IntelliJ's native rename on the class-literal reference inside `@ModifyClass(Foo.class)`.

### Implementation order

Items 1-13 are done (see Status). Remaining:

14. **mod-template test examples**: single modifier, multiple siblings, cross-package, expected-to-fail orphan / conflict / inner-class cases committed as permanent regression examples.

### Todo after

IDE integration for the other class-level annotations (`@OverwriteTypeDeclaration`, `@DeleteMethodsAndFields`) once the core `@ModifyClass` system is in place.

**Red-error inspection: sibling class-level annotation mismatch**
- Fires when siblings targeting the same class in the same trueSrc version disagree on:
  - The class declaration (extends, implements, type parameters, modifiers).
  - `@OverwriteTypeDeclaration` presence or content.
  - `@DeleteMethodsAndFields` presence or descriptors.
- Scoped to same-version siblings only. Cross-version disagreement is expected and handled elsewhere.

**Quick fixes on the mismatch inspection**, in priority order:
1. **Make others match** (primary, shown first) -- take this file's class-level annotation state and propagate it to every sibling in the same version.
2. **Match to others** -- adopt the sibling consensus into this file.

Both quick fixes operate across all sibling files in the same trueSrc version only.

**Refresh listener integration:** the main plan already wires modifier edits through routing to refresh patchedSrc, so class-level annotation edits are covered without extra work as long as the listener treats any trueSrc text edit as a potential target refresh.

---


### Known limitations (user-visible; documented in README):

- **`@ModifyClass` add / remove needs a full refresh**: the IDE's per-save incremental updater reads its `@ModifyClass` routing from the cache built by the last full build (`generatePatchedJava`). Adding or removing `@ModifyClass` on a file in the IDE changes the routing, but the cache doesn't reflect that until the next full build, so the first save cycle after the annotation change produces stale patchedSrc for the affected group. **Workaround**: run `generatePatchedJava` (or `generateAllPatchedSrc`) whenever a `@ModifyClass` addition or removal creates or dissolves a sibling group. Edits *within* an established group (method bodies, `@OverwriteVersion` / `@ShadowVersion` flips, signature changes) don't need this — incremental updates handle them.

### Observed Bugs:

- Navigation between members without a body is not working.
  - At the moment it just defaults me to the class declaration of the current version.
  - On fields it takes to itself, and on methods it takes me to the class declaration. Both in the same version.
  - What is weird is that the visuals are working, the arrows appear when they should.            
  - So, a fix and a change in the feature itself:
    - The change: The arrows and keybinds show and act on @OverwriteVersion and @ModifySignature only.        
    - The arrow only appears if one of those two is present upstream/downstream respectively, and both the arrow and keybind both only navigate between base, MODSIG, and OVERWRITE.
    - The fix: The Alt Shift V popup SHOULD STILL SHOW ALL VERSIONS, and needs to let you navigate to @ModifySignature instead of only @OverwriteVersion.
      - My best guess to why navigation only works to @OverwriteVersion is because the others do not have an encoded position in originMap at all.
      - They have the flags, but they point back to member body.
      - The fix: add more info to originMap and start using more compact representations.
      - At the moment, originMap holds a whole string even though it doesn't have to at all.
      - OriginMap should now look like this: 
        - KEY  version:sibling_index:line:col  sibling_index:line:col  FLAGS
        - The version:sibling_index:line:col is a compact representation of the current originMap target.
          - It points to the body's declaration, AKA the most recent @OverwriteVersion in the upstream.
          - version is obvious
          - sibling_index is the index of MutableList<String> forward map in ClassRoutingMap, accessible via MergeEngineCache.
          - line:col are what you expect, they point to the exact declaration.
        - the sibling_index:line:col is an optional entry that appears if there is either @ModifySignature or @OverwriteVersion on the member, or if the member is new to the version.
          - In a given trueSrc, there can only be 1 place where a member is either @ModifySignature or @OverwriteVersion or both or new. So this field can be always present alongside those.
          - This entry is what allows the IDE to find @ModifySignature members when the previous originMap never provided their location.
          - the syntax is the same as the previous entry, just without the version, because we know it is THIS version.
          - This field, along with the flags, is not inherited by downstream originMap.
        - FLAGS are the already existing flags.
      - This new system allows the IDE to retain existing behavior while also giving it enough information to find @ModifySignature members.
      - It is extremely important that all of this happens on the merge engine (read and write), and that the merge engine exposes the appropriate abstract query for the IDE.

- int bro in ModTemplateMod 1.21.1 should have an IDE error because it is a new member present in both ModTemplateMod and ModTemplateModExtension.
- **JavaParser `AssertionError` crashes routing synthesis when a trueSrc file is mid-edit.** `ModifyClassPreMerge.parseAll` uses `parser.parse(content).result.orElse(null)` to guard against parse failures, but JavaParser's generated grammar throws `AssertionError` (via `TokenRange`) on some malformed inputs, and `AssertionError` bypasses `orElse`. The crash propagates up through `synthesizeRoutingFromTrueSrc` into whichever caller triggered routing (observed: `MissingExplicitModifyClassInspection` during daemon inspection). Fix scope: the IDE synthesis path should tolerate unparseable files (skip + continue); the Gradle build path (`processVersion`) should still fail loudly. Simplest shape is a `tolerateParseErrors` flag on `parseAll`, wrapping the parse call in `try { ... } catch (_: Throwable)` when true.
- I got a related error from exploring 1.21.11/.../ModTemplateMod while exploring FabricModTemplateMod, which references init, an old version of init2.
  - Is this because all references of a method are loaded in memory and analyzed?
  - This could tank performance badly, the only files on memory should be all versions of the classes you have open in your editor. 
  - There is also another issue: FabricModTemplateMod references init() which does not exist in 1.21.11, only init2() does. 
    - Any reference search should not have found it
    - How did the file ever get analyzed
- Deleting classes is not always updating patchedSrc.
- Neoforge truSrc 1.21.1 module has trueSrc common available as a sourceSet. It should not, every module should only see patchedSrc of the other modules
  - In this case the symptom was "Attribute value must be constant" for a final but not initialized @ShadowVersion in common trueSrc, while in patchedSrc the final field is obviously initialized, neoforge trueSrc saw the common trueSrc, not the patchedSrc as it should.


### Test:
- Everything IDE and merge after additions and refactors.
- Navigation in mod template to see if bugs got fixed
- Test refactoring support for all classes.
- Test class types such as record, interface, etc. Make sure they work properly
- Test rename refactor for constructors.

### Engine + Annotations:
- Memoization for member walking via OriginMap.

- Add support for inner classes.
- Add support for records.
- Add enum constants to merge support.
  - Enum classes don't need to have any enum constants if the version they overwrite already does.
    - To avoid something too complicated, this can be done via @ShadowVersion NONE Enum Constant being a placeholder for having no enum constants and not needing to reference them.
    - Remember to add documentation for this.
  - Also add refactor support and all other IDE operations. Enum constants are now just another member like any other, so they should be included in logic everywhere.
- Create and enforce @ModifyClass to make modifications explicit.
  - the target is implicit via class name, although the annotation can optionally also take a string parameter to have a separate target name.
    - This way the developer has some control over routing.
  - TBD: How does ordering work for walking up and down using keybinds/buttons when there's multiple extension classes in a single version?


### Gradle:
- Add more configuration:
  - mixin config path + adding more mixin configs.
  - Access transformers when architectury is turned on.
- Add support for more types of mappings in architectury gradle.
- The keybinds should work on where the user's cursor is, not the selected text.

### IDE:

- Remove ambiguity between absent member and deleted member. Different entries messages in the alt shift v popup.
- Add color coding for the alt shift v popup.
- @OverwriteVersion and @ShadowVersion but can't resolve the upstream member that is being shadowed/overwritten should show an error
- Walking upstream and downstream shouldn't necessarily find the member if it is @ShadowVersion. 
  - By default, it should be only @OverwriteVersion. Maybe the Shift + Alt + V popup can have a little section for IDE navigation configuration
    - Another option should be which member types (Type is their annotations in that version) should be shown.
      - User should be able to say: "I only wanna see new member bodies, no @ShadowVersion". Same for other annotations
    - 
- Examine more ways the IDE currently routes through both trueSrc and patchedSrc, while it should only do patchedSrc then remap
  - Essentially replicate what was already done for findUsages but for other IDE features.
- IDE should refresh the visual file tree after a file is added/removed from patchedSrc.
  - Suuper low priority, arguably not worth it unless it is possible to refresh only that patchedSrc directory.
- IDE: When you add the @ModifySignature to a method, the IDE should scan future versions that still reference the old signature.
  - @ModifySignature has a widget next to it that appears when there are existing references to the old signature
  - A dialogue should open up with a warning and an option to refactor.
  - Clicking refactor should open the same menu as clicking Safe Delete usually does. It lists the references across files (across versions in this codebase) and lets you edit them.
  - Maybe there could also be some other button to attempt the refactor automatically. It would change all references to the old method in downstream methods to the new method signature. 

### IDE tech debt: migrate to centralized upstream-member walker

Everything in the plugin that looks up "the same member / class in some other version" should go through the engine's `OriginNavigation` walkers + the IDE's `MemberLookup` helper. The walker is extension / sibling-aware (routing), rename-chain aware, and takes a `Set<OriginFlag>` filter so callers can ask for the exact semantics they need (empty = existence, `SIGNATURE_FLAGS` = canonical declaration, `DECLARATION_FLAGS` = real body, `ANY_DECLARATION_FLAGS` = anything trueSrc-declared).

Already migrated:
- `MissingExplicitAnnotationInspection` — uses `MemberLookup.memberExistsUpstream` (empty filter).
- `MissingOriginalAnnotationsInspection` — uses `MemberLookup.findSignatureAnchor` (`SIGNATURE_FLAGS`) and `MemberLookup.findLifetimeDeclarations` (`ANY_DECLARATION_FLAGS`).
- Gutter arrows / Alt+Shift+W/S — use `DECLARATION_FLAGS`.
- Alt+Shift+V popup — uses `allMemberVersions` (unfiltered, shows all versions).

Still on the naive `findPreviousVersionClass` (not routing-aware; picks same-rel file even when the upstream version's declaration lives in a different-named extension):
- `PriorVersionMemberHighlightFilter` — suppresses false "missing body" / "must be initialised" errors. Should ask the walker "is this member declared anywhere upstream" instead of opening an arbitrary upstream file and reading it.
- `DescriptorReferences` — descriptor string references inside `@DeleteMethodsAndFields` / `@ModifySignature`. Uses the previous-version class to resolve descriptor targets; would want the walker so it handles extensions and rename chains correctly.
- `ModifySignatureInspection` — checks "does the new name collide with an existing member in the previous version". Same story.

When migrating, use `MemberLookup.memberExistsUpstream` for yes/no questions and `findSignatureAnchor` when you actually need a PSI to read from. If the call site needs a _class_ rather than a member, a parallel class-level helper in `MemberLookup` should be added (not yet needed).

---

### Ambitious:
Make the one-way flow two-way. The current system where the primary interaction is the patch classes is elegant, but you can't see the whole behavior of a class at once. The goal is a dual system where the developer can open a "version view" showing the fully merged class and edit it directly, with changes reverse-engineered back into slim version source.

**Flow:**
virtual version src -reverse engine-> true version src -forward engine-> patchedSrc -refresh-> virtual version src

This is a well-defined event loop with no cycle risk. Virtual version src and patchedSrc at a given version are the same content, the difference is interaction intent. Editing true version src directly still works and round-trips back via file-watch.

**Prerequisite:** Comments and Javadoc preserved through the forward merge, otherwise edits in the version view are silently lossy on the first round-trip.

**Components:**

*Reverse engine (merge-engine module):*
Structural AST diff on JavaParser nodes. Takes the edited merged state and the "base" merged state from the version below, outputs the minimal version-layer declarations:
- Member in edit but not base -> new member in version source
- Member in both but bodies differ -> `@OverwriteVersion`
- Member in base but not edit -> `@DeleteMethodsAndFields` entry
- Inheritance differs -> `@OverwriteTypeDeclaration`
- Unchanged from base -> emit nothing

The merge engine is already a shared library with single-file entry points, so the reverse engine can live alongside the forward engine.

*Version view document (IDE plugin):*
A `LightVirtualFile` opened by "Open Version View" action. Displays the fully merged class with full Java IDE support via patchedSrc infrastructure. Active version and target module stored as metadata. Never directly user-accessible as a real file.

*Edit interceptor (IDE plugin):*
`FileDocumentManagerListener.beforeDocumentSaving` scoped to version view documents. On save: read document content, compute base merged state via forward engine, invoke reverse engine, hand output to write-back logic, refresh version view.

*Write-back logic (IDE plugin):*
Applies reverse engine output to real version source via PSI mutations. Uses originMap to locate correct source files. Triggers single-file forward merge to update patchedSrc.

*Conflict handling:*
If an edited member is overridden by a higher version, warn that the change will be shadowed. User chooses to also update the higher version or leave it.

**Design decisions:**
- Shared root source becomes a shadowBundled dependency (common API that never touches minecraft classes). Plugin can add utilities, e.g. `multiversionModules { common { include = ['api']; common() } }`.
- Version view only shows upstream (older version) members, not downstream. Upstream members are read-only with grey background. Hovering triggers overwrite mode, which adds the member to true version src with appropriate annotations.
- Comments in member bodies propagate. Comments outside member bodies stay local to their source (true src or virtual src). Javadocs stay attached to their members throughout.
- Member reordering in virtual view is ignored by the reverse engine. Virtual view uses in-place PSI mutation (not full file rewrite) to avoid IntelliJ's reload dialog during edits.
- Member renames are treated as delete + add. For `@ModifySignature`, use the rename tool, IntelliJ's edit signature feature, or a plugin button.

  
### Ambitious #2:
- Create a database of boilerplate porting situations. Create an automatic system that applies simple ports when necessary
  - https://github.com/neoforged/.github/tree/main/primers seems promising as a place to scrape from.
  - Example: BlockEntity load and saveAdditional changing signature slightly between 1.20.1 and 1.21.1

### Ambitious #3 
- "n usages" code vision overwrite to use the custom findUsages logic.
  - I need to overwrite a DaemonBoundCodeVisionProvider registered by the IDE.
  - Difficult because of version-specific problems.
  - In a perfect world the number returned matches the one given by the custom findUsagesLogic.

### Ambitious #4 
- Move all minecraft-specific logic into a new gradle plugin to make this project extensible beyond minecraft.
- The minecraft gradle plugin is then a thinner wrapper that contains mostly architectury and publishing preconfiguration.

### Ambitious #5
Redirect patchedSrc errors to trueSrc.
- Errors in this class:
  - Transferred to the proper reference/member/keyword in trueSrc.
- Errors in other classes:
  - This is more complex, as IDEA does not do code analysis for unopened files.
  - Maybe there can be a button to press to do a full version code analysis (which would refresh patchedSrc, and run a code analysis there).
  - There can be a more automatic system that on filesave finds references to the class (the type and its members) and runs analysis on all files.
    - This seems computationally expensive and not worth it