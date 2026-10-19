# Dotykové ovládání Android portů

PdfCraft **0.9.2-tablet2** navazuje na společná gesta `touch1`. Android rozhraní má větší záložky a zavírací tlačítka, posuvný řádek režimů a spodní navigaci dokumentu místo dlouhé pravé lišty. Hlavní ovládací plochy mají 48 bodů při výchozím měřítku rozhraní; ručně zmenšené měřítko je zmenší také. Další nástroje jsou dostupné vodorovným posunem lišty. Postranní panely se vybírají prvním tlačítkem spodní navigace.

Zadávání stránky i přesného zoomu zůstává zachované. Názvy všech stránek se už nekopírují každý snímek, ale pouze při potvrzení zadané stránky. Záložky používají původní přepínání, přesouvání i kontrolu neuložených změn. Změna se týká Android chrome PdfCraftu, nikoli všech jeho dialogů ani ostatních editorů.

V témže vydání je opravené dokončení zápisu do Android dokumentů. Společná funkce zápisu PdfCraftu po uložení pracovní kopie zavolá Android provider a teprve úspěch dovolí hlášku „Saved“ a označení dokumentu jako uloženého. Odmítnutí zápisu se vrátí jako chyba; dokument zůstane upravený pro další pokus nebo Uložit jako. Platí pro soubory otevřené systémem, výběrem v aplikaci i nové cíle. Zápis do vzdáleného či jiného SAF provideru nemá obecný příslib atomického nahrazení souboru; chování při přerušení závisí také na provideru.

Úprava `touch1` používá stejné vstupní rozpoznávání ve všech 12 Craft APK:

- Krátké klepnutí zůstává kliknutím.
- Tažení jedním prstem posouvá obsah. Pohyb větší než 8 bodů před uplynutím 500 ms zahájí posun a už nemůže přejít do výběru.
- Podržení alespoň 500 ms zahájí tažení levým tlačítkem: výběr textu/buněk/objektů nebo práci právě zvoleného nástroje.
- Dva prsty současně přibližují podle své vzdálenosti a posouvají podle pohybu středu. Zvednutí jednoho prstu nepřepne gesto na výběr.
- S Pen a myš si zachovávají okamžitý vstup. Tlak pera se předává dál.

Posun respektuje hranice dokumentu a příslušného panelu. Není-li co posouvat, pohled zůstane na hranici.

## Sestavení

Základní revize aplikací a builderu jsou v `../sources.json`. Soubor `release.json` uvádí verze **Android obalů**, nikoli nové verze upstream knihoven. Vyšší patch verze obalu zvyšuje Android versionCode o jedna; přípona `-touch1` odlišuje úpravu. `cargo-apk2` nepřipouští ruční `version_code` ani `version_name` v Android metadata.

V builderu po vygenerování launcheru a kopírování vendor knihoven, před podpisem a kompilací, spusťte:

```bash
python3 /cesta/artcraft_android/android-port/apply.py "$ROOT" "$APP"
```

`$ROOT` je kořen komunitního builderu a `$APP` např. `photocraft`. Zachovejte předchozí integrační opravy builderu (zejména inicializaci přehrávání SoundCraftu) a stejný privátní podpisový klíč. Skript:

1. Ověří a použije příslušný patch UI z `patches/`. Při neočekávaném obsahu odmítne pokračovat.
2. V Android backendu winit rozliší pero a prst. V egui-winit vypne okamžité převádění prstu na levé tlačítko.
3. Do generovaného Android obalu přidá společný `gestures/android_touch.rs` a jeho raw-input hook.
4. Nastaví explicitní verzi Android obalu z `release.json`.

Pro PdfCraft také kopíruje `tablet/pdf_chrome.rs`, `tablet/tablet_layout.rs` a `saving/save_bridge.rs` do UI crate a připojí Android zapisovací callback. Patch v `patches/pdfcraft.patch` zapíná tabletové lišty a dokončení zápisu jen na Androidu. Desktopové sestavení dál používá původní lišty a zápis.

Spuštění je opakovatelné. PhotoCraft a VectorCraft používají kopie svých speciálních adaptérů ve `source/<app>/apps/<app>-android`, ostatní generovaný shell ve stejném rozložení. PdfCraft a LightCraft již umějí současný posun a zoom; potřebují společný vstup, nikoli patch hlavního plátna.

Kompilujte release ARM64 s NDK r28c a 16KB ELF zarovnáním jako původní vydání. Hotová APK importujte přes `../tools/publish_catalog.py`; publikované cesty a versionCode nikdy nepřepisujte jiným obsahem.

## Ověření

```bash
cargo test --locked --manifest-path android-port/gestures/Cargo.toml
cargo clippy --locked --manifest-path android-port/gestures/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path android-port/tablet/Cargo.toml
cargo clippy --locked --manifest-path android-port/tablet/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path android-port/saving/Cargo.toml
cargo clippy --locked --manifest-path android-port/saving/Cargo.toml --all-targets -- -D warnings
python3 tools/test.py
python3 android-port/tests/verify_release.py /cesta/builder/output
```

Testovací crate používá stejný zdroj jako APK. Kontroluje klepnutí, posun, čas podržení, přechod jednoho/dvou prstů, zrušení, ztrátu fokusu, neplatné souřadnice a průchod pera. Test s opravdovým `egui::InputState` ověřuje současný zoom a přesný posun bez stisknutého tlačítka.

Crate `tablet` testuje stejný posuvný řádek, který používá APK, při šířkách 240, 360, 600 a 1200 bodů. Integrační testy posílají události přes produkční filtr gest: tažení posune nástroje bez kliknutí a krátké klepnutí aktivuje tlačítko.

`tests/GestureProbe.java` je samostatná QA instrumentace pro **izolovaný emulátor**, není součástí distribuovaných aplikací. Sestavení: `python3 android-port/tests/build_probe.py`. Výsledek je ignorované `build/gesture-qa.apk`. Po instalaci do testovacího emulátoru lze například použít:

```bash
adb -s emulator-5554 shell am instrument -w -e mode pinch -e x 900 -e y 600 -e dx -100 -e dy -100 -e scale 1.6 cz.jirkas.craftgestureprobe/.GestureProbe
```

Režimy jsou `tap`, `swipe`, `hold` (650 ms před tažením) a `pinch`. Souřadnice jsou fyzické pixely a musí ležet na právě testovaném obsahu. Instrumentace pouze doručuje vstup; úspěšné doručení samo o sobě nepotvrzuje správné chování aplikace, které je potřeba ověřit zvlášť.
