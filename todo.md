## @ModifyClass Implementation

### What it is

`@ModifyClass` marks a class that modifies another class from a previous version. Currently, class matching across versions is always by file name (Foo.java in v1 matches Foo.java in v2). @ModifyClass breaks this in two ways:

1. **Different name targeting**: `@ModifyClass` can optionally take a string parameter specifying a different target class name. So `FooPatched.java` with `@ModifyClass("Foo")` targets and modifies `Foo.java` from the previous version. Without the parameter, the target is implicit via the class's own name (same as current behavior).

2. **Multiple modifiers per target**: Multiple @ModifyClass classes in one trueSrc version can target the same base class. For example, `FooNetworkPatch.java` and `FooBehaviorPatch.java` could both target `Foo.java`. They would be applied in sequence (deterministic order, e.g. alphabetical).

### Central solution: class routing map

The merge engine already writes `multiversion-engine-config.json` per patched module. A new companion file, `multiversion-class-routing.json`, should be written alongside it during `generatePatchedJava`. It maps target class rel paths to the trueSrc files that modify them.

Format:
```json
{
  "com/example/Foo.java": [
    "com/example/FooNetworkPatch.java",
    "com/example/FooBehaviorPatch.java"
  ],
  "com/example/Bar.java": [
    "com/example/BarPatch.java"
  ]
}
```

Only entries where the source file differs from the target are included (same-name classes use implicit routing as before). This file is produced by the merge engine (or Gradle plugin) during patchedSrc generation by scanning all trueSrc files for @ModifyClass annotations, and is consumed by the IDE plugin for navigation, refactoring, etc.

The merge engine should expose a `ClassRoutingMap` data class in the engine module (next to `EngineConfig`) with `fromJson`/`toJson`/`fromFile`/`toFile` methods. The IDE plugin caches it in `EngineConfigCache` alongside the engine config, invalidating on file modification timestamp.

### Breaking points and fixes

Each point is annotated with severity: MAJOR (significant rework), MODERATE (targeted changes), MINOR (trivial).

#### Merge engine

**True2PatchMergeEngine.processVersion()** -- MAJOR
Currently walks trueSrc files and assumes `rel` path matches the base and output. Must:
- Pre-scan all trueSrc files for @ModifyClass annotations to build routing (target rel -> list of modifier files).
- For each entry in the routing, merge modifiers in order onto the base at the target's rel path.
- Write output to the target's rel path, not the modifier's.
- Emit the class routing map for downstream consumers.

**True2PatchMergeEngine.mergeContent()** -- MAJOR
Uses `rel` as the origin map key. When merging FooPatched.java -> Foo.java, origin entries must be keyed under `com/Foo.java`, not `com/FooPatched.java`. The `rel` parameter must become the *target* rel, and a separate `sourceRel` must track where the modifier actually lives.

**True2PatchMergeEngine.mergeFile()** -- MAJOR
Signature assumes currentFile/baseFile/outFile share the same rel path. Must accept an explicit target rel for routing.

**True2PatchMergeEngine.collectInheritedOrigins()** -- MODERATE
Checks `File(currentSrcDir, rel).exists()` to detect inherited files. With @ModifyClass, a file might be modified by a differently-named trueSrc file. Must check the routing map instead of raw file existence.

#### Gradle plugin

**MultiversionPatchedSourceGeneration.patchModuleGroup()** -- MAJOR
Override detection logic: "if version k has file X, exclude X from earlier layers." FooPatched.java in v2 must exclude Foo.java from v1, but names don't match. Must:
- Parse @ModifyClass annotations during file collection to build the routing.
- Use routing to correctly identify which base files are overridden.
- Write output files at the target's rel path.
- Write `multiversion-class-routing.json` alongside the engine config.

#### IDE plugin -- navigation

**VersionMemberUtil.findAdjacentVersionClass()** -- MAJOR
Looks for the same `relClassPath` in the target version's trueSrc/patchedSrc. Won't find `FooPatched.java` when navigating from `Foo.java`. Must:
- Load the class routing map for the target version.
- Check if any modifier targets the current class.
- For upstream navigation (going to base), this already works (patchedSrc has the correct merged file at the target rel path).
- For downstream navigation, must consult the routing map to find the modifier file(s).

**VersionMemberUtil.findAllVersionClasses()** -- MAJOR
Same assumption: iterates versions checking the same rel path. Must use the routing map per version.

**VersionModuleUtil.findCorrespondingFile()** -- MAJOR
Maps files across versions by identical rel path. Must check routing map for cross-name matches.

**ShowAllVersionsPopupAction** -- MODERATE
Currently shows one entry per version. With multiple @ModifyClass files targeting the same class in one version, must show multiple entries for that version (one per modifier file). The popup model already supports this.

#### IDE plugin -- refactoring

**MultiversionRenameProcessor.addMatchingElements()** -- MODERATE
Matches classes by `it.name == element.name`. Renaming `Foo` won't touch `FooPatched`. Must detect @ModifyClass target and decide: rename the base class everywhere, or update the @ModifyClass annotation parameter.

**MultiversionMoveListener move propagation** -- MODERATE
Moves the same rel path in all versions. Won't find @ModifyClass files at different paths. Must consult routing map to find all related files.

**MultiversionMoveListener.updateTsvForRename()** -- MODERATE
Patches origin map TSV by rel path. @ModifyClass file's origin entries are keyed by the target path, not the source. Must look up routing before patching.

#### Not broken (works as-is)

- **findMatchingMember()** -- matches within a class by name+params, agnostic to how the class was found.
- **OriginMap / PatchedSrcGotoDeclarationHandler** -- keyed by output path, works if the merge engine writes correct entries.
- **PatchedSrcFindUsagesHandler** -- scans full patchedSrc directories, path-agnostic.
- **isOverriddenDownstream()** -- delegates to findAllVersionsOfMember, which delegates to findAllVersionClasses. Fixing findAllVersionClasses fixes this transitively.
- **DescriptorAnnotationSupport** -- resolves descriptors within a class, path-agnostic.

### Implementation order

1. **Annotation**: add `@ModifyClass` to annotations module (target = TYPE, retention = SOURCE, optional String value parameter for target name).
2. **Engine data class**: add `ClassRoutingMap` to merge-engine module with JSON serialization.
3. **Engine merge**: update `processVersion` / `mergeFile` / `mergeContent` to handle routing. This is the hardest part.
4. **Gradle plugin**: update patchModuleGroup to build routing, write the JSON file.
5. **IDE plugin**: load routing map in `EngineConfigCache`, update `findAdjacentVersionClass` / `findAllVersionClasses` / `findCorrespondingFile` to consult it.
6. **IDE refactoring**: update rename processor and move listener.
7. **Test**: add @ModifyClass examples to mod-template.

---


### Observed Bugs:

- I got a related error from exploring 1.21.11/.../ModTemplateMod while exploring FabricModTemplateMod, which references init, an old version of init2.
  - Is this because all references of a method are loaded in memory and analyzed?
  - This could tank performance badly, the only files on memory should be all versions of the classes you have open in your editor. 
  - There is also another issue: FabricModTemplateMod references init() which does not exist in 1.21.11, only init2() does. 
    - Any reference search should not have found it
    - How did the file ever get analyzed
- Deleting classes is not always updating patchedSrc.
- Going upstream from 1.21.11 ModTemplateMod class is leading me to 1.21.1 patchedSrc
  - Seems fixed, keep testing.
- @ModifySignature but no @OverwriteVersion requires @ShadowVersion and native or abstract keyword.
  - This makes sense and is intended behavior in trueSrc, but creates a compile error in patchedSrc:
    - @ModifySignature carries over the native keyword, while @ShadowVersion carries over the method body from a previous version
    - So patchedSrc ends up with native keyword + a method body. Native keyword could be intended, while method body is 100% intended in this case.
    - Therefore, my proposal: Ditch relying on native keyword or abstract keyword to avoid an IDE error.
      - Suppress "Missing method body, or declare abstract" error fully, and allow @ShadowVersion public static void foo(); 
    - Better than the alternative of adding parameters to @ModifySignature to explicitly add native or abstract keyword and just strip them if not.


### Test:
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