# Shared Gradle API Changelog

## 0.1.0

- Initial release
- Contains `MultiversionModulesExtension`, the shared DSL extension for declaring modules, loader types, architectury opt-ins, and patch modules
- Adds `versionPattern` field for custom version directory regex
- Adds `versions` field for explicit ordered version lists (bypasses filesystem scanning)