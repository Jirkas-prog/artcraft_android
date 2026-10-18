# Attribution

Craft Hub is an independent Android launcher for the Craft App Suite. Its Java implementation and original four-tile icon are MIT licensed. It is inspired by the function, typography and palette of [storytold/craft-launcher](https://github.com/storytold/craft-launcher), revision `9cef5d2a0f207da50d31547a726057ea40f7e5b4`. Upstream code licenses are preserved in `licenses/launcher-upstream-*`.

The protected ArtCraft "A" mark and wordmark are **not bundled**. The original launcher's brand license requires distributed modified versions to use a different name and mark. References to ArtCraft Launcher identify the upstream project; they do not imply endorsement.

| Bundled asset | Author / origin | License |
|---|---|---|
| `launcher/res/drawable/hub_icon.xml` | Craft Hub contributors; original four-tile icon | MIT |
| `launcher/res/drawable/*craft.png` | ArtCraft Team and the respective Craft app contributors; copied unchanged from `storytold/craft-launcher/assets/app-icons/` | MIT OR Apache-2.0, notices in `licenses/suite/<app>/` |
| `launcher/assets/fonts/Inter-Variable.ttf` | Inter Project Authors (Rasmus Andersson), via upstream launcher; Google Fonts Inter 4.001 | SIL OFL 1.1, adjacent `OFL-Inter.txt` |
| `launcher/assets/fonts/Archivo-Variable.ttf` | Archivo Project Authors (Omnibus-Type), via upstream launcher; Google Fonts Archivo 2.001 | SIL OFL 1.1, adjacent `OFL-Archivo.txt` |
| `launcher/assets/fonts/GeistMono-Variable.ttf` | Geist Project Authors (Vercel), via upstream launcher; Google Fonts Geist Mono 1.701 | SIL OFL 1.1, adjacent `OFL-GeistMono.txt` |
| `apk/*craft-*.apk` | Corresponding `storytold/<app>` repository; revisions in `sources.json`; Android integration by `MostafaXS1/artcraft-android` plus documented local changes | Upstream licenses and notices in `licenses/suite/`; Android builder MIT notice included |

Craft app names remain their authors' product names. The APKs are locally signed community builds, not official Android releases. License files for app icons and this launcher are also bundled in the launcher APK under `assets/licenses/`.
