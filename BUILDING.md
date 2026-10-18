# Sestavení a publikování

## Craft Hub

Nativní Java aplikace používá pouze Android SDK; nemá externí knihovny za běhu, Gradle, webview ani server. Potřebujete Python 3.11+, JDK 17 nebo novější, Android SDK platformu 35 a build-tools 35.0.0. `ANDROID_HOME` ukazuje na SDK.

```bash
export ANDROID_HOME=/opt/craft-android/sdk
export CRAFT_SIGNING_KEY=/cesta/mimo/repozitar/artcraft-builder.jks
export CRAFT_SIGNING_PASSWORD_FILE=/cesta/mimo/repozitar/.keystore-password
python3 tools/build_launcher.py
```

Heslo v souboru je heslo keystore; privátní klíč používá stejné heslo. Výsledek je v ignorované složce `build/`. Pro aktualizace používejte **stejný klíč** jako pro již vydané APK. Nový náhodný klíč neumožní aktualizovat stávající instalace. Heslo se nepředává v příkazové řádce ani nevypisuje do protokolu.

Verze launcheru je v `launcher/version.json`. Při každém publikovaném sestavení zvyšte `versionCode` a odpovídajícím způsobem `versionName`. Testovací starší sestavení lze vytvořit přes `--version-code 9999 --version-name 0.9.9`; nepatří do publikovaného katalogu.

## Testy

```bash
sudo apt-get install libandroid-json-java  # pouze pro testy v hostitelské JVM
python3 tools/test.py
```

Test spouští skutečný parser a downloader. Zahrnuje neplatné zdroje a přesměrování, číselné rozsahy, cizí podpis, duplicity, pořadí vlastní aktualizace, návrat na starší verzi, poškozené nebo zkrácené APK a úklid po zrušení. Síťové odpovědi jsou v testu řízené; aplikace nemá žádný testovací HTTP server ani výjimku z HTTPS kontroly.

Před vydáním ověřte i Android běh: první instalaci, odmítnuté oprávnění, potvrzení/zrušení instalátoru, přepnutí do pozadí, restart aplikace, hromadnou aktualizaci a vlastní aktualizaci ze staršího APK. Hostitelský test nenahrazuje systémový instalátor.

## APK sady a katalog

Craft aplikace byly sestaveny z revizí v `sources.json` pomocí komunitního [artcraft-android builderu](https://github.com/MostafaXS1/artcraft-android). Jejich původní sestavení a pracovní prostředí zůstává samostatně; tento repozitář hostuje hotové APK a zdroj launcheru.

Import nových, již podepsaných APK:

```bash
python3 tools/publish_catalog.py /slozka/s/novymi-apk
python3 tools/publish_catalog.py build/craft-hub-10001.apk
python3 tools/test.py
```

Nástroj ověřuje podpis, metadata i architekturu, kopíruje APK do `apk/<aplikace>-<versionCode>.apk` a vytváří `catalog.json`, `SHA256SUMS.txt` a vložený offline katalog. Odmítne cizí certifikát, snížení verze nebo změnu obsahu již vydané číselné verze. Identifikátor balíčku se při aktualizaci nesmí změnit. Pokud přestavujete Craft APK se stejným názvem verze, zvyšte také jejich Android `version_code` v příslušném manifestu před kompilací.

Katalog a nová APK odešlete v **jednom commitu** do `main`, aby se neobjevily odkazy na dosud neexistující soubory. Starší soubory mají neměnné cesty, takže dříve načtený katalog dál funguje. APK jsou v běžném Gitu, každé pod 100 MiB; nejde o Git LFS ukazatele. Historie binárních verzí bude repozitář postupně zvětšovat. Pro výrazně větší APK bude nutné upravit katalog/downloader pro release assets a limity.

Při vydání nové verze Craft Hubu aktualizujte také přímý instalační odkaz v README. Publikování je běžný `git add`, `git commit`, `git push origin main`; skript sám nic neodesílá.

Nikdy nepřidávejte keystore, hesla, přístupové tokeny nebo obsah soukromých uživatelských dokumentů do Gitu. `.gitignore` chrání běžné názvy podpisových souborů; klíč uchovávejte mimo pracovní kopii.
