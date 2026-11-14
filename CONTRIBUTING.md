# Contributing

Thanks for helping. Bug reports, translations and pull requests are all welcome.

## Before you start
- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- The app stays offline: no analytics, no ads and no proprietary dependencies. The only network uses are the daily exchange-rate update for other currencies (currency codes only, to api.frankfurter.dev, can be turned off) and the opt-in web dashboard, which serves the local Wi-Fi only; don't add others without discussing it first.
- Read [GLOSSARY.md](GLOSSARY.md) for the words the app uses (an *entry*, not a transaction; a *repeat*, not a recurring transaction), and [docs/adr/](docs/adr/) for decisions that look odd but are deliberate, such as refunds being stored as negative expenses. Add a record when you make a decision like that.
- Don't copy code, text, images or icons from other apps. Importers may read other apps' file formats, but must be written independently.

## Building and testing
```
./gradlew :composeApp:installDebug          # debug build on a connected device or emulator
./gradlew :composeApp:testDebugUnitTest     # unit tests
./gradlew :composeApp:verifyRoborazziDebug  # screenshots match composeApp/src/androidUnitTest/screenshots
./gradlew :composeApp:recordRoborazziDebug  # re-record them after an intended visual change
```
Debug builds install as a separate app (`in.amrahul.moneytracker.debug`), next to a release install. See [README.md](README.md) for the layout and core rules.

Code is grouped by feature, with each layout's screens in `classic/` and `fresh/` inside the feature ([ADR 0018](docs/adr/0018-package-by-feature.md)). To keep `git blame` past the commits that moved files, run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once.

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
