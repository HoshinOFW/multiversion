### Observed Bugs:

### Test:
- Add another version to mod-template to help test more complex hierarchies.
- Test refactoring support for all classes.
- Test class types such as record, interface, etc. Make sure they work properly
- Test rename refactor for constructors.

### Engine + Annotations:
- Add support for inner classes.
- Add support for records.
- Add enum constants to refactoring support.
  - Enum classes don't need to have any enum constants if the version they overwrite already does.
    - To avoid something too complicated, this can be done via @ShadowVersion NONE Enum Constant being a placeholder for having no enum constants and not needing to reference them.
    - Remember to add documentation for this.
- Create and enforce @ModifyClass to make modifications explicit.
  - the target is implicit via class name, although the annotation can optionally also take a string parameter to have a separate target name.
    - This way the developer has some control over routing.
### Gradle:

- Add more configuration:
  - mixin config path.
  - Access transformers when architectury is turned on.
  - multiversion_resources.json path
- Add an easy way to wire all versioned tasks to one main task. It should be possible to wire all :mc_version:fooTask into one :fooTask, and/or all :mc_version:module:fooTask into :fooTask\
  - Should be safe in case the task does not exist.
  - Additionally should be able to pass a filter lambda Project -> boolean, letting people configure even more.
- Add support for more types of mappings in architectury gradle.

### IDE:

- Add IDE suggestions via a small warning, such as `Missing original annotations` on hover for methods/fields that have @ShadowVersion and the original is also @Foo. The warning would have a button you can use to copy the annotations over.
- Add more IDE suggestions such as: `Method found in original class, but no @ShadowVersion or @OverwriteVersion provided` which would then have a button to add @ShadowVersion
  - The same thing for @ModifyClass when that gets added.
- Redirect all errors from patchedSrc to their code origins. In the problems page, but also when exploring the original code.
  - Related error warning should propagate between versions as well. If Foo in 1.21.1 is broken, a related error message should show up in Foo 1.20.1 .
- Navigation stuff through extra buttons next to shadowed or overwritten fields/methods/target classes? Kinda like how mixin adds a button to go to the original class/method/field
- Examine more ways the IDE currently routes through both trueSrc and patchedSrc, while it should only do patchedSrc then remap
  - Essentially replicate what was already done for findUsages but for other IDE features.
- Add IDE navigation: 
  - A button on every annotation that takes you to the version it is overwriting or shadowing.
  - A keybind to go up or down a version. Literally Ctrl+Shift+w or s or up or down.
    - Variation to just go up or down a version, and a variation to do that for the target of the annotation.
    - There is also a button next to the multiversion annotation that does the same thing as the.
  - A keybind that opens up a small menu showing you all the versions the class you're in or method/field you're targeting has. Lets you navigate between them and create net versions.
- IDE should refresh the visual file tree after a file is added/removed from patchedSrc.
  - Suuper low priority, arguably not worth it unless it is possible to refresh only that patchedSrc directory.
- IDE: When you add the @ModifySignature to a method, the IDE should scan future versions that still reference the old signature.
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
- Inheritance differs -> `@OverwriteInheritance`
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