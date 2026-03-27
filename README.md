### Multiversion
Multiversion is a plugin meant to facilitate developing minecraft mods for multiple versions of minecraft at once.
It works by layering source sets on top of each other, from the oldest version given to the newest.

What that means is that you can write the full 1.20.1 version of a mod, using architectury to add support for both fabric and forge, and the only thing you have to do to add support for 1.21.1 (Neoforge and Fabric) is re-write classes that broke between versions.
With some creative class wiring you can minimize the amount of new code in each newer version quite a bit.
The plugin also automatically sets up mod publishing to curseforge and modrinth if given the appropriate gradle properties.

This repository features a template mod, as well as the gradle plugin and the gradle settings plugin.

The plugins will be uploaded to remote maven repositories in the recent future, but at the moment you will have to build the versions you want to use yourself and upload them to mavenLocal or to a libs repository directory for your project.

This repository also includes an idea plugin to aid development, it is strongly recommended as it will fix some code navigation issues.

If you want a larger example of how to effectively implement the plugin, I recommend checking the source code for my [Buildstone Toolkit](https://github.com/HoshinOFW/Buildstone-Toolkit) mod.
