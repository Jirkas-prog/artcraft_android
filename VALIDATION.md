# Ověření sestavení

## Aktualizace ovládání touch1 — 18. 10. 2026

- Všech 12 Android obalů má vyšší versionCode a příponu verze `-touch1`; přesné hodnoty jsou v [android-port/release.json](android-port/release.json). Podpisový certifikát zůstává stejný pro aktualizaci existujících instalací.
- Společný zdroj rozpoznávání gest: 11 automatických testů, `cargo fmt --check` a Clippy se zakázanými varováními. Ověřen je krátký dotyk, posun bez stisku tlačítka, hranice podržení 500 ms, současný zoom/posun, přechody mezi jedním a dvěma prsty, zrušení, ztráta fokusu, neplatné souřadnice a průchod vstupu pera.
- Dva regresní testy byly také spuštěny proti dočasně vráceným chybným implementacím: opakované započítání počátečního posunu a skok výběru ke středu dvou prstů. Obě vadné varianty test správně odmítl. Finální testy prošly v čistém adresáři sestavení.
- Skutečné ARM64 APK byly testovány na Androidu 15 / API 35 Google APIs x86_64 s překladem ARM64, v okně 1600 × 1000 / 240 dpi. Samostatná QA instrumentace doručovala události prstů, výsledek byl kontrolován ve vykreslené aplikaci.
- PdfCraft: posun dokumentu bez výběru, zoom 38 % → 60 % se současným posunem, označení textu po podržení.
- GridCraft: posun tabulky bez změny aktivní buňky, plynulé přiblížení 100 % → 160 % se současným posunem a výběr oblasti po podržení.
- CADCraft: posun ukázkového výkresu; při kombinaci zoomu 1,3× a posunu kontrolní bod skončil na očekávaných souřadnicích (548, 558); podržením vybráno 21 objektů.
- WordCraft: posun dokumentu, zoom 100 % → 130 % s posunem a výběr 17 slov po podržení.
- PhotoCraft: posun se zvoleným štětcem nezanechal tah; kombinovaný zoom/posun umístil kontrolní bod na očekávané souřadnice (612, 543). Po zvolení obdélníkového výběru podržení a tažení vytvořilo výběr.
- DeckCraft a DesignCraft: ověřena instalace a vykreslení úvodní obrazovky. U EffectCraftu, FilmCraftu, SoundCraftu, VectorCraftu a LightCraftu nebylo vizuální ověření dokončeno: emulátor začal vracet černý obraz a chyby grafického backendu `invalid color buffer`; následně přestal vykreslovat i dříve ověřený GridCraft. Běžící proces se nepovažuje za úspěšný test UI. Tyto aplikace prošly sestavením a kontrolou APK, jejich gesta ještě vyžadují kontrolu na zařízení.
- [android-port/verification.json](android-port/verification.json) zaznamenává kontrolu finálních APK: podpis, verze, SHA-256, ARM64, úplnost nativních závislostí a nejméně 16KB zarovnání všech ELF LOAD segmentů. Skript odmítá neúplnou sadu.

Testovací instrumentace není distribuována v katalogu. Zkoušky zahrnovaly vlastní syntetické dokumenty, nikoli uživatelské soubory. Úplné upstream CI všech aplikací ani fyzický Galaxy Tab S9 / S Pen nebyly testovány. Podržení používá aktivní nástroj; pro výběr v grafických editorech musí být zvolen nástroj výběru.

## Původní Craft Hub 1.0.1

Datum: 18. 10. 2026. Craft Hub 1.0.1 (`10001`), Android SDK 35. Následující kontroly se vztahují k původnímu vydání launcheru.

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
