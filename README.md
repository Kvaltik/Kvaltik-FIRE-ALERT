# 🚒 Kvaltík FIRE ALERT – Android

**Verze 1.1.0** · `cz.kvaltik.firealert` · Android 8.0+ (minSdk 26)

Samostatná aplikace pro přehled **veřejně publikovaných zpráv o hasičských zásazích** v ČR.

## Co již obsahuje
- 📰 Načítání skutečných veřejných článků přes Google News RSS podle vybraného kraje; zdroj a čas publikace u každého článku.
- 📍 Výběr všech 14 krajů + celé ČR.
- 🗺️ Mapu oblasti OpenStreetMap (bez vymyšlených bodů zásahů).
- 🚒 Vlastní seznam stanic s odkazy na **veřejné stránky** jednotek / stanic.
- ⭐ Uložení kraje, stanic a posledních zpráv offline.
- 🔔 Volitelná místní upozornění na **nově publikované články při otevřené aplikaci**.
- 🔄 Kontrolu novinek každých 10 minut **pouze při otevřené aplikaci**.
- 🌙 Tmavé české rozhraní a načítací obrazovku.
- 🔗 Odkazy na oficiální stránky HZS ČR a POŽÁRY.cz.

**Důležité:** Nejde o oficiální poplachový systém, neposkytuje napojení na KOPIS, neukazuje úplný aktuální stav výjezdů ani živé polohy zásahů. Zpravodajství mívá zpoždění, někdy i zásadní. Mapa neukazuje místa incidentů. Při nebezpečí volejte 150/112. Chcete-li dostávat oficiální poplach své JPO, musí autorizované zapojení zařídit velitel a provozovatel kompatibilního svolávacího systému.

## Aktualizace aplikace (v1.1.0)
- Aplikace po spuštění automaticky kontroluje nejnovější vydání z `https://api.github.com/repos/Kvaltik/Kvaltik-FIRE-ALERT/releases/latest`.
- V **Nastavení → Aktualizace aplikace** lze kontrolu spustit ručně, zobrazit poznámky a zvolit „Stáhnout a nainstalovat“ nebo „Připomenout při dalším spuštění“.
- APK se stahuje přes systémový DownloadManager; poté se nabízí jeho instalace přes systémový Android instalátor (uživatel musí potvrdit).
- **Android neumožňuje této neprivilegované aplikaci bez potvrzení nainstalovat APK.** V nastavení systému může být nutné povolit „Instalace neznámých aplikací“.
- **Zásadní pro aktualizace:** všechna vydaná APK musí být podepsána **stejným release klíčem**, jinak Android instalaci přes starou verzi odmítne. GitHub Actions debug buildy používají různá výchozí ladicí podpisová data na jednotlivých runnerech a **nejsou vhodné pro aktualizace**. Přechod ze starého testovacího APK může vyžadovat odinstalaci, a tím se mohou smazat uložená data.
- Nyní musí správce projektu nejdřív založit GitHub Release s tagem `v1.1.0` a přiloženým podepsaným `.apk`, aby kontrola mohla najít aktualizaci.

## Jak získat APK zdarma přes GitHub Actions

1. Na GitHubu vytvořte **nový repozitář** `Kvaltik-FIRE-ALERT`.
2. Rozbalte tento ZIP a **nahrajte celý obsah** (včetně skryté složky `.github`) do kořene repozitáře. Lze přes web tlačítkem **Add file → Upload files**; pro správný přenos `.github` je spolehlivější `git push`.
3. Otevřete repozitář → **Actions** → **Sestavit APK**. Workflow se spouští automaticky při push do `main` či `master`; případně použijte **Run workflow**.
4. Po zeleném výsledku otevřete daný běh a v části **Artifacts** stáhněte `Kvaltik-FIRE-ALERT-Android-APK`.
5. Stažený ZIP rozbalte. Uvnitř je `app-debug.apk`. Ten lze do mobilu nainstalovat po povolení instalace z daného zdroje.

Alternativně v Android Studio otevřít projekt → **Build → Build APK(s)**. Je požadováno JDK 17, Gradle 8.11.1, Android SDK 35 a Android Gradle Plugin 8.9.2.

APK z automatického workflow je **debug build** pro testování. Pro distribuovanou produkční aplikaci by měl být podepsán vlastním release klíčem (nikdy jej neukládat do veřejného repozitáře).

## Ochrana soukromí
Neobsahuje registraci ani analytiku. Oblíbené stanice a nastavení zůstávají lokálně v aplikaci. Při načítání zpráv se mobil připojí ke službě Google News a při zobrazení mapy k OpenStreetMap. Otevřené články se zobrazují v prohlížeči uživatele. App vyžaduje internet; o notifikace požádá pouze při zapnutí.

## Případné rozšíření
Pro skutečná ověřená data o výjezdech a zabezpečené notifikace by byl nutný **schválený poskytovatel API / integrace** a serverová část. Toto MVP si na takové napojení **nehraje**.

## Automatické vydávání podepsaných aktualizací

Pro produkční aktualizace nastav v repozitáři GitHub → Settings → Secrets and variables → Actions čtyři tajné hodnoty: `FIRE_ALERT_KEYSTORE_BASE64` (Base64 obsah bezpečně uloženého `.jks`), `FIRE_ALERT_STORE_PASSWORD`, `FIRE_ALERT_KEY_ALIAS`, `FIRE_ALERT_KEY_PASSWORD`. **Nikdy nevkládej `.jks` ani hesla do repozitáře.** Klíč si bezpečně zálohuj: při jeho ztrátě nebude možné aktualizovat původní instalace.

Zvyš `versionCode` i `versionName` v `app/build.gradle`, commitni změny a vytvoř tag odpovídající verzi (např. `v1.1.0`). Workflow `release-apk.yml` sestaví podepsané APK a připojí jej k veřejnému GitHub Release. Aplikace pak při dalším spuštění vydání rozpozná.

**Pozor:** Stávající testovací APK v1.0.0 je podepsáno jiným klíčem; nelze zaručit přímou instalaci nového release přes něj. V případě odinstalace původní aplikace se mohou ztratit lokální data.

## Vydání automatických aktualizací – podpis
Pro skutečnou instalaci aktualizací je v GitHub repozitáři workflow `.github/workflows/release-apk.yml`. Před prvním vydáním je nutné bezpečně nastavit **GitHub Actions Secrets** `APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`, `APK_KEY_ALIAS`, `APK_KEY_PASSWORD`.

Podpisový klíč vytvoří správce jednorázově pomocí `keytool -genkeypair -v -keystore firealert-release.jks -keyalg RSA -keysize 3072 -validity 10000 -alias firealert`. Hodnotu `APK_KEYSTORE_BASE64` získá příkazem `base64 -w0 firealert-release.jks` (Linux; na Windows lze převést PowerShellem). **Privátní klíč ani přístupové heslo se nikdy nevkládají do GitHub repozitáře či veřejných souborů.** Záloha klíče je nezbytná, protože bez stejného podpisu nebude Android akceptovat aktualizaci.

Po nastavení secrets lze vytvořit tag `v1.1.0` a workflow podepíše, ověří a připojí APK k **GitHub Release**. Další vydání vytvoř jako nový tag se zvýšenými `versionCode` a `versionName` v `app/build.gradle`. Aplikace prohlíží nejnovější vydání při spuštění a nabídne instalaci dostupného APK.