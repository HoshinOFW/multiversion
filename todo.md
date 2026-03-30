- Add more IDE support:
  - Refactoring across versions
  - Navigation stuff through extra buttons?
  - Implement some gradle tasks to update patchedSrc in smaller ways and have the IDE call them in the background. Ex: On file save, run a task to update patchedSrc. 

- Performance:
  - Add better caching control to make sure that gradle knows files havent changed and only apply changes to patchedSrc.