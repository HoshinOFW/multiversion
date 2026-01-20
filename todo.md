### Urgent:
Rewrite patching logic

### Current behavior:
- Essentially overwrites classes with same signature

### Full future behavior:
- There is a @TargetClass annotation you put in 1.21.1 to and reference a class that exists in 1.20.1
  - In that class, you can @Overwrite, @Inject, @Remove, methods and fields.
  - The gradle script then builds the new classes in patchedSrc using those annotations
- The IDEA plugin will run gradle tasks in the background to update patchedSrc on method/field/class creation, deletion, signature change.
- There will be jsons for classes and resources where you can define files or folders to be excluded. 