# Contributing

Thanks for helping. Bug reports, translations and pull requests are all welcome.

## Before you start
- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- The app stays offline: no network access, no analytics, no ads, and no proprietary dependencies.
- Don't copy code, text, images or icons from other apps. Importers may read other apps' file formats, but must be written independently.

## Building and testing
```
./gradlew :composeApp:installDebug          # debug build on a connected device or emulator
./gradlew :composeApp:testDebugUnitTest     # unit tests
```
Debug builds install as a separate app (`in.amrahul.moneytracker.debug`), next to a release install. See [README.md](README.md) for the layout and core rules.

## Pull requests
- Keep each PR focused, and add or update tests for behaviour changes.
- New user-facing text goes through `tr("…")`. English is the key; translations live in `composeApp/src/androidMain/assets/i18n/`.
- Database changes need a migration (`composeApp/src/commonMain/sqldelight/.../N.sqm`) so old backups keep restoring.

## Sign-off (DCO)
By contributing, you certify the [Developer Certificate of Origin](https://developercertificate.org/): you wrote the change or have the right to submit it under this project's license. Sign off each commit with `git commit -s`, which adds:

```
Signed-off-by: Your Name <you@example.com>
```

## License
Contributions are licensed under the [GNU GPL v3.0](LICENSE), like the rest of the project.
