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

### 1. Byg appen
Hver gang der skubbes til `main`, bygger GitHub Actions appen (fanen **Actions**).
Den laver altid en test-udgave (`.apk`) og, når upload-nøglen er sat op, også udgaven til Google Play (`.aab`).

**Upload-nøgle** (én gang). Kør på din computer (kræver Java):

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -i upload.jks | pbcopy        # macOS: kopierer filen som tekst
```

Læg fire hemmeligheder i repoet under Settings → Secrets and variables → Actions:

| Navn | Værdi |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | teksten fra base64-kommandoen |
| `SIGNING_STORE_PASSWORD` | adgangskoden du valgte |
| `SIGNING_KEY_ALIAS` | `upload` |
| `SIGNING_KEY_PASSWORD` | adgangskoden du valgte |

Gem `upload.jks` og adgangskoden et sikkert sted. Mister du dem, kan Google nulstille nøglen.

Alternativ: åbn projektet i Android Studio → Build → Generate Signed App Bundle.

### 2. Test i emulator (valgfrit, anbefales)
Android Studio → Device Manager → opret en *Automotive*-enhed (eller Polestars emulator) →
træk `.apk`-filen over i emulatoren → åbn Medier og vælg DR Lyd.

### 3. Google Play (internt testspor, ingen godkendelse)
1. Opret udviklerkonto på play.google.com/console (engangsbeløb, 25 USD, kræver ID-bekræftelse).
2. **Opret app** → navn "DR Lyd (privat)" → App, Gratis.
3. Opsætning → Avancerede indstillinger → **Formfaktorer** → tilføj **Android Automotive OS**.
4. Test og udgiv → Test → **Intern test** (vælg sporet for Android Automotive OS) →
   opret testerliste med din egen Gmail.
5. Opret udgivelse → upload `.aab`-filen fra Actions → udgiv.
6. Åbn tilmeldingslinket fra testsiden på telefonen og tryk **Bliv tester**.
7. I bilen: Play Butik (logget ind med samme Google-konto) → søg "DR Lyd (privat)" → installer.

Næste version: skub kode til `main`, upload den nye `.aab` i samme spor. Bilen opdaterer selv.

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
