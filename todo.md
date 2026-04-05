### Observed Bugs:


### Test:
- Test refactoring support for all classes.
- Test class types such as record, interface, etc. Make sure they work properly
- Test rename refactor for constructors.

### Implementation Plans:
- **Shared MergeEngine library** - extract the patching engine out of main-gradle into a standalone Kotlin library
  that both the Gradle plugin and the IDE plugin can depend on.

  99% of the gradle plugin is javaParser and IO.
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

  Phase 1 - Extract engine: COMPLETE
  - Create `merge-engine/` subproject, published to the same Maven repo
  - Port `MethodMergeEngine.groovy` to Kotlin, expose a clean public API (`MergeEngine.processVersion(...)`)
  - Replace `GradleException` with `MergeException : RuntimeException`
  - Extend the engine to also accept a single file path so a save of `Foo.java` only re-merges that one file
    across versions, not the entire source directory
  - Update Gradle plugin to call the library instead of the inlined Groovy class
  - Bundle the library JAR in the IDE plugin.

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

- **IDE Major Features**

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

---

### Gradle:

- Add support for only publishing to either curseforge or modrinth. Can be done via missing curseforge_id or modrinth_id. 
- Add more configuration:
  - mixin config path.
  - Access transformers when architectury is turned on.
  - multiversion_resources.json path
- Add an easy way to wire all versioned tasks to one main task. It should be possible to wire all :mc_version:fooTask into one :fooTask, and/or all :mc_version:module:fooTask into :fooTask
- Add support for more types of mappings in architectury gradle.
- Add a @ModifySignature annotation that allows you to modify the signature of a method/field.
  - Takes in the target upstream (older) method. Same syntax as @DeleteMethodsAndFields.
    - The upstream method reference must be added to also be the target of rename and move refactors.
  - If no @OverwriteVersion is given, the original method body/field declaration is still kept.
  - This annotation would join @OverwriteVersion and @ShadowVersion as the main 
- Add enum constants to refactoring support. 
- Add support for inner classes. 
- Add support for records.
- Enum classes don't need to have any enum constants if the version they overwrite already does.
  - To avoid something too complicated, this can be done via @ShadowVersion NONE Enum Constant being a placeholder for having no enum constants and not needing to reference them.
  - Remember to add documentation for this.
- Create and enforce @ModifyClass to make modifications explicit. 
  - the target is implicit via class name, although the annotation can optionally also take a string parameter to have a separate target name.
- Add better caching control to make sure that gradle knows files haven't changed and only apply changes to patchedSrc.

---

### IDE:

- Suppress `Variable variableName might not have been initialized` errors from the IDE when the variable `variableName` is annotated with @ShadowVersion. 
- Add IDE suggestions via a small warning, such as `Missing original annotations` on hover for methods/fields that have @ShadowVersion and the original is also @Foo. The warning would have a button you can use to copy the annotations over.
- Add more IDE suggestions such as: `Method found in original class, but no @ShadowVersion or @OverwriteVersion provided` which would then have a button to add @ShadowVersion
  - The same thing for @ModifyClass when that gets added.
- Redirect all errors from patchedSrc to their code origins. In the problems page, but also when exploring the original code.
  - Related error warning should propagate between versions as well. If Foo in 1.21.1 is broken, a related error message should show up in Foo 1.20.1 .
- Navigation stuff through extra buttons next to shadowed or overwritten fields/methods/target classes? Kinda like how mixin adds a button to go to the original class/method/field
- Correct the usages search for classes/fields/methods. At the moment it still shows patchedSrc as usages. 
  - In reality, it should: Search only patchedSrc, but use originMap to find the original declaration and show only those. 
  - This is correct because you search patchedSrc which is the source of truth, but parse to real code declaration for development environment.
  - The key idea is to keep patchedSrc as truth, but always point to original code in dev environment.
  - Compilation should always just work with patchedSrc.
- Expand the previous point to other applications. patchedSrc is truth for all indexing and searching, originMap provides the DX/UX experience by showing the developer the class that contributed.
  - The key idea is that all searching it doing through patchedSrc, then routed back to its real reference.
- Add IDE navigation: 
  - A button on every annotation that takes you to the version it is overwriting or shadowing.
  - A keybind to go up or down a version. Literally Ctrl+Shift+w or s or up or down.
    - Variation to just go up or down a version, and a variation to do that for the target of the annotation.
    - There is also a button next to the multiversion annotation that does the same thing as the.
  - A keybind that opens up a small menu showing you all the versions the class you're in or method/field you're targeting has. Lets you navigate between them and create net versions.
- Once the common library merge engine is implemented, implement it into the IDE so that on file save patchedSrc is updated for all versions
  - For this, use the single file path merge part of the engine, don't recalculate all patchedSrc.

---

### Ambitious:
- Add a way to view and play with patchedSrc directly, and the changes propagate to the correct version.
  - Make the one-way flow two-way.
  - Proposal: I can open the 1.20.1 version of a class in a special mode where I basically just open patchedSrc.
    - Any changes I make are reverse-engineered to the main java src for the version. 
      - For example, changing a method adds it with @OverwriteVersion to the code for the version.
  - The reason this could be very useful is because you can essentially interact with the complete version level of the class.
    - The version source code remains slim, but you can see the whole version classes at once.
  - This is still just an extension of current behavior.
  - Some things are missing before this:
    - Reimplement comments in patchedSrc.
    - Implement javadocs in patchedSrc.
  
I am essentially envisioning a reversal of the current process. By keeping both sources, it can be up to the developer how they choose to code.
The IDE is currently supporting one. All thats needed is to add the functionality to the merge engine, isolate the engine, then implement everything in the IDE.
I don't believe users should directly access patchedSrc. A new type of file editing wrapper would need to be created.
Where you edit a virtual file where the edits are parsed into the to-be-patched modules, then patched into patchedSrc.
That is essentially the role of the reverse engine, to look at an edited file, compare it to the version above and below it, then spit out a slim changes-only file.

  **Feasibility: Medium-High.** The forward engine already solves the hard structural problem. The reverse direction is a well-defined inversion of it, with the main complexity sitting in the virtual document infrastructure and the write-back attribution logic.

  **Prerequisites:**
  - Shared MergeEngine library extracted (see Implementation Plans)  reverse engine lives alongside the forward engine in the same module.
  - Comments and Javadoc preserved through the forward merge, otherwise edits made in the version view are silently lossy on the first round-trip.
  - Single-file merge entry point (Phase 1 of MergeEngine plan)  needed to efficiently regenerate patchedSrc after a write-back.

  **Components:**

  *Reverse engine (merge-engine module):*
  Takes two ASTs  the edited merged state and the "base" merged state produced by the version below  and outputs the minimal set of version-layer declarations needed to reproduce the edited state from the base. This is a structural diff on JavaParser ASTs. Each diff item maps to exactly one annotation + declaration:
  - Method/field present in edit but not in base → add to version source (new member)
  - Method/field present in both but bodies differ → add to version source with `@OverwriteVersion`
  - Method/field present in base but absent from edit → add `@DeleteMethodsAndFields` entry
  - Inheritance clause differs → add class declaration with `@OverwriteInheritance`
  - Method/field unchanged from base → emit nothing (inherited, no version source needed)

  *Version view document (IDE plugin):*
  A `LightVirtualFile` backed virtual document opened by a dedicated action ("Open Version View"). It displays the fully merged class for a specific version, with full Java IDE support (highlighting, completion, navigation) via the existing patchedSrc infrastructure. It is never directly user-accessible as a real file. The active version and target module are stored as metadata on the virtual file.

  *Edit interceptor (IDE plugin):*
  A `FileDocumentManagerListener.beforeDocumentSaving` listener scoped to version view documents. On save, it reads the current document content and the base merged state (computed via the forward engine for the version below), invokes the reverse engine, and hands the output to the write-back logic. The version view is then refreshed by re-running the forward merge for the edited version.

  *Write-back logic (IDE plugin):*
  Takes the reverse engine output and applies it to the real version source file using PSI mutations  adding, replacing, or removing declarations and their annotations. Uses the originMap to locate the correct source file for each member being modified. After write-back, triggers a single-file forward merge to update patchedSrc.

  *Conflict handling:*
  If a member being edited in a version view is also overridden by a version above, the write-back flags a warning: the change will be shadowed by the higher version. The user is presented the choice to also update the higher version or leave it.

  **Open questions:**
  - How to handle members that originate from the shared root source (not any specific version)  write-back target would be the shared root directory, not a version module.
  - Whether the version view should be read-only for members known to come from a higher version layer (since editing them there is a no-op).
  
  **Answers**
  - The shared root source stops being the base to patch into versions, and becomes a simple shadowBundled dependency. 
    - It works for code that works as a common api for all versions that doesn't need any minecraft classes and therefore never breaks
    - The plugin can add some utilities for setting up this flow. 
      - Maybe in multiversionModules can be refactored to {common { include = ['api'];  autoArchitectury(); common()}} where api is the name of the non-minecraft module to be bundled. 
  - The version view doesn't see any members from a downstream (higher version, current is 1.20.1, higher version is 1.21.1) layer. It only sees them from upstream layers.
    - The members from an upstream layer are read-only, with a slight grey background. Hovering them lets you trigger overwrite mode, where you edit the member and it stops being read-only.
    - Depending on what edits you did, the member then gets added to true version src with the body you gave it and the corresponding annotations.

Main deliberation: 
  I am strongly considering revamping the whole system.
  I want to turn patchedSrc from just the source truth of the version into the primary interaction method of the developer.
  I think the current system where the primary interaction is the patch classes is elegant, but it has a key problem:
You can't see the whole behavior of a class at once. From a DX experience, having everything that class does in one place is essential.
Proposed new method: Dual system. Keep true version src -> merge engine -> patchedSrc system.
Version src becomes the source of truth of what code changed in that version, while patchedSrc is created from it.
Instead, you add something new at the start:
virtual version src, generated from current version src
So the final flow is this: 
virtual version src <--merge engine--> true version src -> merge engine -> patchedSrc

For efficiency purposes, you could make the process more circular:
virtual version src -merge-> true version src -merge-> patchedSrc -merge-> virtual version src

An edit in any of these sources propagates through the other two one-directionally. 
For this to happen, patchedSrc needs to retain comments, javadocs, and member ordering ideally.

Virtual version src and patchedSrc at a given version are the same content, the only difference is interaction intent. 
The circular flow is therefore a well-defined event loop with no cycle risk: 
user edits virtual src → reverse engine diffs against the version-below merged state → writes minimal annotation changes into true version src → forward engine regenerates patchedSrc → virtual src refreshes. 
The reverse engine is a structural AST diff on JavaParser nodes, producing the same annotation vocabulary the user already writes by hand (@OverwriteVersion, @DeleteMethodsAndFields, @OverwriteInheritance, etc.). 
The common cases (add, change, delete a member, change inheritance) are all straightforward inversions of what the forward engine already does. The shared MergeEngine library is the hard prerequisite without it the reverse engine has no home that both plugins can reach.

The DX shift is significant: the mental model moves from "I write patches" to "I see the whole class and the system tracks what changed." 
Upstream members (from older versions) appear in the virtual view with a grey background and are read-only; 
hovering lets you trigger an overwrite mode that adds the member to true version src with the appropriate annotation. 
Editing true version src directly still works and round-trips back into the virtual view via a file-watch trigger. 
The main open risk is the file-open-while-regenerating problem: if the virtual src file is rewritten on disk while the user has it open, IntelliJ's reload dialog appears mid-edit. 
The clean mitigation is surgical PSI mutation of the open document instead of a full file rewrite, which is more complex but avoids the interruption entirely.

Comments created in the virtual view stay in the virtual view. Javadocs stay attached to their members throughout all sources
Comments in member bodies stay in member bodies and propagate. Comments outside member bodies don't propagate, they stay in true src or virtual src.
Member reordering in the virtual view is probably best ignored by the reverse engine.
However, the virtual view should ideally not be constantly rewritten over and over, and members are modified in place. 
That way developers can still reorder them as they want.
Member renames are treated as delete + add. If you want an @OverwriteSignature, you must use the rename tool, the intellij edit signature feature, or a special button added by the plugin.

  
### Ambitious #2:
- Create a database of boilerplate porting situations. Create an automatic system that applies simple ports when necessary
  - https://github.com/neoforged/.github/tree/main/primers seems promising as a place to scrape from.