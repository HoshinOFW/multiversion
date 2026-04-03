### Observed Bugs:


### Test:
- Test refactoring support for all classes.
- Test class types such as record, interface, etc. Make sure they work properly
- Test rename refactor for constructors.

### Main:

- Create and enforce @ModifyClass to make modifications explicit. 
  - the target is implicit via class name, although the annotation can optionally also take a string parameter to have a separate target name.
- Add enum constants to refactoring support. 
- Add support for more types of mappings in architectury gradle.
- Add support for inner classes. 
- Add support for records.
- Enum classes don't need to have any enum constants if the version they overwrite already does.
  - To avoid something too complicated, this can be done via @ShadowVersion NONE Enum Constant being a placeholder for having no enum constants and not needing to reference them.
  - Remember to add documentation for this.
- Add more configuration:
  - mixin config path.
  - Access transformers when architectury is turned on.
  - multiversion_resources.json path
- Detach from architectury. Make automatic architectury support optional, off by default.
  - This is essentially done by removing the patchModules must be a subset of other declared classes in multiversionModules.
  - Instead, patchModules is just patchModules, and the other variables in the closure are used to define which modules to give automatic architectury support for. They are also renamed for clarity.
  - Architectury API mod is then optional, on if the mod version is provided in version gradle.properties. If it is not, throw a warning in the log. Warning can be turned off in the multiversionModules closure.

### Add more IDE support:
- Navigation stuff through extra buttons next to shadowed or overwritten fields/methods/target classes? Kinda like how mixin adds a button to go to the original class/method/field
- Implement some gradle tasks to update patchedSrc in smaller ways and have the IDE call them in the background. 
  - Ex: On file save, run a task to update patchedSrc. 
  - The smaller ways would be just updating the class across versions for the file that was saved, for example.
- Suppress `Variable variableName might not have been initialized` errors from the IDE when the variable `variableName` is annotated with @ShadowVersion. 
- Correct the usages search for classes/fields/methods. At the moment it still shows patchedSrc as usages. 
  - In reality, it should: Search only patchedSrc, but use originMap to find the original declaration and show only those. 
  - This is correct because you search patchedSrc which is the source of truth, but parse to real code declaration for development environment.
  - The key idea is to keep patchedSrc as truth, but always point to original code in dev environment.
  - Compilation should always just work with patchedSrc.
- Expand the previous point to other applications. patchedSrc is truth for all indexing and searching, originMap provides the DX/UX experience by showing the developer the class that contributed.
  - The key idea is that all searching it doing through patchedSrc, then routed back to its real reference.
- Add IDE suggestions via a small warning, such as `Missing original annotations` on hover for methods/fields that have @ShadowVersion and the original is also @Foo. The warning would have a button you can use to copy the annotations over.
- Add more IDE suggestions such as: `Method found in original class, but no @ShadowVersion or @OverwriteVersion provided` which would then have a button to add @ShadowVersion
  - The same thing for @ModifyClass when that gets added.
- Redirect all errors from patchedSrc to their code origins. In the problems page, but also when exploring the original code.

### IDE Major Features:

The goal is for patchedSrc to be the source of truth for all IDE queries, with originMap acting as an encoder/decoder.
The ideal flow: IDEA queries usages of `1.21.1/fabric/SomeClass#fooField` -> query is intercepted and run against patchedSrc instead -> results are remapped back to real source via originMap before being shown.

**Why a fully generalized interceptor is not achievable:**
IntelliJ has no single query bus. Each operation type has its own independent subsystem and extension point.
There is no plugin-accessible layer that sits in front of all of them.

**What is achievable - targeted implementations per operation:**

| Operation | Feasibility | Extension Point |
|---|---|---|
| Find Usages | High | `FindUsagesHandlerFactory` |
| Type/Call Hierarchy | Medium | `TypeHierarchyProvider`, `CallHierarchyProvider` |
| Rename | Done | `RenamePsiElementProcessor` |
| Move | Done | `RefactoringEventListener` |
| Inline/Extract/structural refactors | Very hard | No clean extension point |
| Error highlighting | Suppression only | `HighlightInfoFilter` |

**Find Usages implementation plan:**
1. `FindUsagesHandlerFactory` detects element is in a versioned source root
2. Resolve inverse originMap: given `SomeClass.java#fooField` in versioned source, find the corresponding PSI element in patchedSrc
3. Run native Find Usages scoped entirely to patchedSrc
4. Map each result `UsageInfo` forward through originMap back to real source before returning

The inverse originMap lookup (step 2) requires scanning the TSV for values matching the current file, or maintaining a reverse index cache.

**Note on "patchedSrc as sole source root":**
Removing versioned source roots would break error highlighting, code completion, and type resolution in versioned files.
Both source sets must remain. The consequence is classes are indexed twice, and targeted suppression (like the existing highlight filters) is the only tool for deduplication per operation.

Find Usages is the recommended first investment here - highest impact, cleanest extension point.

### Performance:
- Add better caching control to make sure that gradle knows files haven't changed and only apply changes to patchedSrc.

### Major:

**Feasibility: High.**

**Shared MergeEngine library** - extract the patching engine out of main-gradle into a standalone Kotlin library
that both the Gradle plugin and the IDE plugin can depend on.

Why this is straightforward: `MethodMergeEngine.groovy` has exactly one Gradle API import (`GradleException`),
which is trivially replaced with a custom exception. Everything else is pure JavaParser and file I/O.
The annotations library should stay separate (it is a compile-only dep for mod users; bundling JavaParser into
their compile classpath would be wrong).

Proposed module layout:
```
multiversion/
  main-gradle/                  (Gradle plugin - depends on merge-engine)
  idea-plugin/                  (IDE plugin - bundles merge-engine as a dependency)
  multiversion-annotations/     (unchanged - compile-only for mod users)
  merge-engine/                 (NEW - Kotlin, single external dep: javaparser-core)
```

**Implementation plan:**

Phase 1 - Extract engine:
- Create `merge-engine/` subproject, published to the same Maven repo
- Port `MethodMergeEngine.groovy` to Kotlin, expose a clean public API (`MergeEngine.processVersion(...)`)
- Replace `GradleException` with `MergeException : RuntimeException`
- Extend the engine to also accept a single file path so a save of `Foo.java` only re-merges that one file
  across versions, not the entire source directory
- Update Gradle plugin to call the library instead of the inlined Groovy class
- Bundle the library JAR in the IDE plugin

Phase 2 - Config bridge (required for on-save):
- Add a Gradle task that writes `build/multiversion-engine-config.json` per version module
  containing: base version path, all version module paths in order, patchedSrc output path
- IDE plugin reads this file lazily (cached, invalidated on modification) to understand project structure
  without needing to parse the Gradle DSL

Phase 3 - On-save integration in the IDE plugin:
- Implement `FileDocumentManagerListener.beforeDocumentSaving`
- Detect if the saved file is in a versioned source root (already have `getVersionedSourceRoot`)
- Read the config file to get merge parameters
- Call `MergeEngine.mergeFile(savedFile, config)` - single-file entry point added in Phase 1
- Refresh the VFS so IntelliJ picks up the updated patchedSrc content

### Ambitious:
- Create a database of boilerplate porting situations. Create an automatic system that applies simple ports when necessary