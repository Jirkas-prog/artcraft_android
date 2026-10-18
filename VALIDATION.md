# Ověření sestavení

Datum: 18. 10. 2026. Craft Hub 1.0.1 (`10001`), Android SDK 35.

## Dokončené kontroly

- 48 automatických kontrol skutečného parseru katalogu a downloaderu: neplatné adresy, přesměrování, podpisový otisk, duplicity, číselné rozsahy, hluboké JSON, pořadí vlastní aktualizace, snížení verze, chybný hash/velikost, zrušení a úklid souborů.
- Sestavení launcheru a kontrola jeho podepsaného release APK pomocí Android SDK `apksigner` a `aapt2`.
- U všech 13 aktuálních APK kontrola velikosti, SHA-256, názvu balíčku, verze a společného podpisového certifikátu. Nativní knihovny 12 aplikací jsou ARM64; při jejich sestavení byly ověřeny závislosti a 16KB zarovnání ELF.
- Spuštění launcheru na Androidu 15 / API 35 v izolovaném emulátoru AOSP ATD x86_64. Žádný pád aplikace v protokolu AndroidRuntime.
- Zobrazení ve třech, dvou i jednom sloupci (1600 × 1000, 1000 × 1600 a zúžené okno 800 × 1000; hustota 240 dpi), včetně změny orientace za běhu.
- Uložený katalog při nedostupné síti, odmítnutí neplatného zdroje, zakázání instalace ARM64 aplikací na zařízení bez této architektury.
- Vysvětlení chybějícího oprávnění k instalaci; na ATD bez aplikace Nastavení také náhradní informační dialog.
- Odmítnutí oprávnění k oznámením neblokuje zahájení fronty. Při chybě HTTP zůstane fronta uložená i po ukončení a novém spuštění procesu; lze ji zrušit. Pro tento scénář byl do emulátoru vložen testovací uložený katalog, bez změny distribuovaného APK.
- **Vlastní aktualizace 0.9.9 (`9999`) → 1.0.1 (`10001`) přes skutečný veřejný katalog a APK v tomto repozitáři.** Spuštěna tlačítkem Aktualizovat vše, stažení a instalaci provedl Craft Hub. Android eviduje `cz.jirkas.crafthub` jako instalátor. Po opětovném otevření je verze aktuální, fronta prázdná a uložená instalační relace odstraněná. V této konfiguraci Android další potvrzení nevyžadoval.
- Všech 13 aktuálních veřejných APK adres vrací HTTP 200 a očekávanou velikost. Hlavní APK bylo z veřejné adresy také celé staženo a jeho SHA-256 souhlasí s katalogem: `7d7ccd9bad93bb48785f26b249673bc3cb61b8d3a002d6c3eca1f405518831ea`.

## Rozsah a omezení

Emulátor x86_64 nenahrazuje Galaxy Tab S9. Na fyzickém tabletu dosud nebyly ověřeny dotykové ovládání, S Pen, grafické ovladače, práce se soubory, zvuk ani vlastní pracovní postupy 12 nativních aplikací. První instalace a systémové dialogy se mohou mezi Androidem a Samsung One UI lišit. Podrobnosti k portům obsahuje [APK-NOTES.txt](APK-NOTES.txt).

Testovací sestavení `0.9.9` slouží pouze pro kontrolu přechodu na vydané APK a není součástí veřejného katalogu. Aplikace pro používání nevyžaduje root.
