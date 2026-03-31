### Main:
- make static abstract possible. If possible via vanilla keywords, if not via a new annotation @Abstract that does the same thing.
- Make annotations package version that is applied in main-gradle switchable via gradle.properties instead of hard-coded.
- Add support for more types of mappings.
- Add more configuration:
  - mixin config path.
  - multiversion_resources.json path

### Add more IDE support:
- Navigation stuff through extra buttons next to shadowed or overwritten fields/methods/target classes? Kinda like how mixin adds a button to go to the original class/method/field
- Implement some gradle tasks to update patchedSrc in smaller ways and have the IDE call them in the background. 
  - Ex: On file save, run a task to update patchedSrc. 
  - The smaller ways would be just updating the class across versions for the file that was saved, for example.

### Performance:
- Add better caching control to make sure that gradle knows files haven't changed and only apply changes to patchedSrc.

### Ambitious:
- Create a database of boilerplate porting situations. Create an automatic system that applies simple ports when necessary