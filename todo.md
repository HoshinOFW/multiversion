### Observed Bugs:

- OnSaveListener and PsiStructureListener probably skip all actions when the event happens in the base version.
  - But this does not take into account that the base version still has an originMap that needs updating.
  - So this is incorrect. The merge api should still be called, and it is the merge api that should see that it is the base version and skip patchedSrc while updating originMap.
  - Of course IDE caches are then properly updated.
- The MissingOriginalAnnotationInspection at the moment does not check if the member has annotations the original did not have
  - There could be an argument in favor of making this a feature instead of a bug, but I am leaning more towards the inspection appearing whenever there is a mismatch.
- MissingOriginalAnnotationsInspection is not working for base version.
  - I guess it needs to be split into 2 inspection:
    - 1 for @ShadowVersion and OverwriteVersion that does what the "match to other versions" quick fix does but "match to declaration" instead.
    - Another inspection for the NEW/MODSIG case that says "Downstream members don't match these annotations" and has a quick fix "Make downstream annotations match"
    - This is nicer because the signature declaration remains the main entrypoint for changing annotations, with proper support.

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
  - New merge failure system especially needs testing:
    - Single-version Gradle build (`:1.21.1:common:generatePatchedJava` direct) with one bad file: task fails red, partial `_originMap.tsv` has all other files' entries, broken file untouched on disk.
    - `generateAllPatchedSrc` with one bad file in v1: v1 logs failures but doesn't throw, v2's task runs and reads v1's partial map as base, v2/Goo merges correctly (byte-equivalent to a clean build).
    - IDE save with parse error in Foo: one balloon `"Multiversion merge: N file(s) failed across M version(s)"`, idea.log shows v0 PLAIN_FILE failure + SKIPPED_UPSTREAM_FAILED for Foo in every downstream version, Goo entries unchanged everywhere.
    - Cascade scoping: edit Goo to broken state then edit unrelated Foo without fixing Goo. Foo's cascade succeeds end-to-end; Goo stays broken; the two cascades don't interact.
    - Sibling-group resilience: break group A's validation in v1; group B's Target still merges, A's siblings skipped from plain-file loop (no silent wrong output), B's `.routing` sidecar written, A's stays at last good state.
    - Sibling-group cascade containment: break a Sibling of group A in v1, save. SKIPPED_UPSTREAM_FAILED for group A's Target rel in every downstream; group B in every downstream untouched.
    - Base-version edit: edit a class in 1.20.1 (no PatchedSrc), save. First downstream picks up in-memory editedContent for the editing rel (not stale on-disk).
    - Extension cascade: edit `FooExt.java` (`@ModifyClass(Foo.class)`), save. Downstream PatchedSrc updates land at the resolved Target rel per version, not at editedRel.
    - Cache invalidation across cascade steps: after a successful Extension cascade, Alt+Shift+V popup reflects new Target locations in downstream versions immediately (no IDE restart).
    - Re-run after fix: fix the broken file from any of the above, re-run. Previously-successful files re-merge identically; previously-broken file now succeeds; everything coherent.
    - Happy-path regression: clean build, diff `_originMap.tsv` and PatchedSrc byte-for-byte against pre-refactor reference; should be identical.
- Navigation in mod template to see if bugs got fixed
- Test refactoring support for all classes.
- Test class types such as record, interface, etc. Make sure they work properly
- Test rename refactor for constructors.

### Engine + Annotations:
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
- @OverwriteVersion and @ShadowVersion but can't resolve the upstream member that is being shadowed/overwritten should show an error. This is a new inspection.
  - CannotFindTargetMemberInspection. Uses existing helpers to check if member exists current version patchedSrc.
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

### Ambitious #6
Do the version navigation truly in-place, in a way that bypasses the limitation of 1 editor open per file max.
This would reduce some situations where you have multiple versions already open, so navigation moves your around and closes tabs you wouldn't expect to.
This is heavily messing with normal IDE stuff so that's why it is in ambitious.