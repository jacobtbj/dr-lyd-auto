# DR Lyd til Polestar 3 (Android Automotive)

Privat app, der giver DR's live-radio og podcasts i bilens egen medieafspiller.
Fanerne følger DR Lyd: **Hjem · Udforsk · Live · Din lyd**, og tandhjulet åbner indstillingerne
(kun når bilen holder stille).

## Ret kanaler uden ny app

Alt, der kan gå i stykker, ligger i `config/config.json` her i repoet:
stream-adresser, kanalnavne, kategorier under Udforsk og hvad der er Fremhævet på Hjem.

- Ret filen direkte på GitHub (blyant-ikonet) og gem.
- Bilen henter den nye fil inden for 6 timer, eller med det samme under
  Indstillinger → Kanal-opsætning → **Hent nu**.
- Hver kanal kan have flere adresser (`urls`). Fejler den første, prøver bilen den næste.
  Fejler alle, henter bilen selv en frisk opsætning og prøver igen.
- En ødelagt fil afvises, så bilen bliver ved med at bruge den sidste gode.
- Repoet skal være **offentligt**, ellers kan bilen ikke læse filen.

Podcasts hentes fra DR's offentlige feeds: `https://api.dr.dk/podcasts/v1/feeds/<navn>`.
Et nyt program tilføjes i bilen ved at indsætte dr.dk-linket eller navnet under Indstillinger.

## Første gang: få appen i bilen

### 1. Byg appen på din egen computer
Projektet bygges lokalt i Android Studio (gratis). Der bruges ingen GitHub Actions.

1. Installer Android Studio fra developer.android.com/studio.
2. File → Open → vælg mappen `dr-lyd-auto`. Vent til Android Studio har hentet det, den skal bruge.
3. **Test-udgave:** Build → Build App Bundle(s) / APK(s) → Build APK(s).
4. **Udgave til Google Play:** Build → Generate Signed App Bundle or APK → Android App Bundle →
   *Create new…* for at lave en upload-nøgle første gang → vælg **release**.
   Filen havner i `app/release/app-release.aab`.

Gem upload-nøglen (`.jks`-filen) og adgangskoden et sikkert sted. Mister du dem, kan Google nulstille nøglen.
Hver ny version til Play skal have et højere versionsnummer: sæt `VERSION_CODE` (fx 2, 3, 4 …)
som miljøvariabel, eller ret `versionCode` i `app/build.gradle.kts`.

### 2. Test i emulator (valgfrit, anbefales)
Android Studio → Device Manager → opret en *Automotive*-enhed (eller Polestars emulator) →
kør appen med den grønne afspil-knap → åbn Medier og vælg DR Lyd.

### 3. Google Play (internt testspor, ingen godkendelse)
1. Opret udviklerkonto på play.google.com/console (engangsbeløb, 25 USD, kræver ID-bekræftelse).
2. **Opret app** → navn "DR Lyd (privat)" → App, Gratis.
3. Opsætning → Avancerede indstillinger → **Formfaktorer** → tilføj **Android Automotive OS**.
4. Test og udgiv → Test → **Intern test** (vælg sporet for Android Automotive OS) →
   opret testerliste med din egen Gmail.
5. Opret udgivelse → upload `app-release.aab` → udgiv.
6. Åbn tilmeldingslinket fra testsiden på telefonen og tryk **Bliv tester**.
7. I bilen: Play Butik (logget ind med samme Google-konto) → søg "DR Lyd (privat)" → installer.

Næste version: byg en ny `.aab` med højere versionsnummer og upload den i samme spor. Bilen opdaterer selv.

## Struktur

| Fil | Indhold |
|---|---|
| `DrLydService.kt` | Afspilleren bilen taler med, genoptagelse, reserve-adresser |
| `Catalog.kt` | Fanerne og alt indhold under dem |
| `Feeds.kt` | Henter og læser podcast-feeds |
| `Config.kt` | Fjernstyret opsætning og dine indstillinger |
| `Progress.kt` | Hvor langt du er i hvert afsnit |
| `ArtProvider.kt` | Covers og kanal-logoer til bilskærmen |
| `SettingsActivity.kt` | Indstillingsskærmen |

Privat projekt. Indholdet tilhører DR og må ikke distribueres.
