# Craft Hub pro Android

Jeden instalátor a launcher pro Craft App Suite na Android tabletu. Výchozí zdroj je tento veřejný repozitář, větev **main**. Aplikace načítá `catalog.json`, zkontroluje dostupné verze, stáhne podepsané APK ze složky `apk/` a předá je systémovému instalátoru. Nainstalované aplikace lze z Craft Hubu rovnou otevřít.

**[Stáhnout Craft Hub](https://raw.githubusercontent.com/Jirkas-prog/artcraft_android/main/apk/craft-hub-10001.apk)**

## Instalace a používání

1. Nainstalujte pouze hlavní APK **Craft Hub**. Vyžaduje Android 10 nebo novější; cílovým tabletem je Samsung Galaxy Tab S9.
2. Otevřete Craft Hub a zvolte **Nainstalovat vše**, případně jednotlivou aplikaci.
3. Při první instalaci povolte systému instalaci neznámých aplikací ze zdroje Craft Hub. Potvrďte dialogy systémového instalátoru.
4. Tlačítko **Otevřít** spouští aplikaci. Funguje i bez připojení k internetu, pokud je katalog uložený.
5. **Obnovit** načte aktuální katalog; **Aktualizovat vše** spustí aktualizaci nainstalovaných aplikací, včetně samotného Craft Hubu.

Jeden klik zahájí celou frontu. První instalace vyžadují systémová potvrzení. U dalších aktualizací Android může potvrzení vynechat, pokud to jeho pravidla dovolí a Craft Hub je evidovaný instalátor, nebo aktualizuje sám sebe. Aplikace vždy obsluhuje i variantu, kdy Android potvrzení vyžádá. Žádný root ani obcházení instalátoru není potřeba.

Stahování běží jako oznámená služba i při přepnutí do jiné aplikace. Přerušenou frontu lze znovu spustit tlačítkem **Pokračovat**; již nainstalované verze se přeskočí. Vlastní aktualizace je poslední, protože Android při nahrazení APK ukončí starý proces. Poté Craft Hub znovu otevřete.

## Obsah

CADCraft, DeckCraft, DesignCraft, EffectCraft, FilmCraft, GridCraft, LightCraft, PdfCraft, PhotoCraft, SoundCraft, VectorCraft a WordCraft. Tyto aplikace jsou experimentální komunitní ARM64 porty. Jejich funkčnost a dotykové ovládání je potřeba ověřit na konkrétním tabletu; související poznámky jsou v [APK-NOTES.txt](APK-NOTES.txt).

Samostatná AI aplikace ArtCraft ani původní desktopový ArtCraft Launcher nemají v této sadě Android APK. Craft Hub je samostatná nativní Android implementace role launcheru, inspirovaná [ArtCraft Launcherem](https://github.com/storytold/craft-launcher); nepoužívá jeho chráněné logo a není oficiálním produktem ArtCraft týmu. Zachovává styl a povolené ikony jednotlivých aplikací.

## Jak vznikají aktualizace

Launcher aktualizuje **publikované APK**. Sám nekompiluje nové Git zdroje. Správce nejprve sestaví nové APK, zvýší jejich `versionCode`, aktualizuje katalog pomocí přiloženého nástroje a odešle APK spolu s katalogem do `main`. Další kontrola v launcheru pak nabídne nové verze.

- [Postup sestavení a publikování](BUILDING.md)
- [Katalog a přesné kontrolní součty](catalog.json)
- [Revize zdrojů sestavených aplikací](sources.json)
- [Licence a původ použitých prvků](ATTRIBUTION.md)

V nastavení **Zdroj** lze vybrat jiný veřejný GitHub repozitář se stejným formátem katalogu ve větvi `main`. APK musí být podepsané stejným důvěryhodným klíčem jako tato sada. Přenos probíhá přes HTTPS; před instalací se ověřuje velikost, SHA-256, identifikátor balíčku, číselná verze a podpis. Návrat na starší verzi se nenabízí. Jinak podepsané instalace se automaticky neodinstalovávají a jejich data se nemažou.

Otisk veřejného podpisového certifikátu SHA-256: `f28bb27cd24137bb5f89af5dc377c6f67dbf214e9b9f5125b7fb14af1bce536e`.
Soukromý podpisový klíč ani heslo nejsou součástí repozitáře.
