# Annotations Changelog

## 0.5.0

- New `@ModifyClass(Class<?>)` class-level annotation: marks a class as a modifier of a target class from an upstream version, enabling cross-name and cross-package modifiers as well as multi-file (sibling) modification of a single target. Optional class-literal value defaults to a self-target sentinel (`@ModifyClass` alone means "modify my same-named upstream counterpart")

## 0.4.10

- `@OverwriteInheritance` renamed to `@OverwriteTypeDeclaration`

## 0.4.9

- Current release