# Settings Gradle Plugin Changelog

## 0.6.0

- `multiversionModules` extension now registered in the settings phase; users configure it in `settings.gradle`
- Project inclusion deferred to `settingsEvaluated` so the DSL block runs before discovery
- When modules are declared, only matching subdirectories are included (precise inclusion)
- Added `versionPattern` field for custom version directory regex
- Added `versions` field for explicit ordered version lists (bypasses filesystem scanning)
- Added `shared-gradle-api` as a dependency

## 0.5.0

- Initial release