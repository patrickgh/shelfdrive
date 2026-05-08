# Google Play Store Listing

This document contains user-facing copy for publishing ShelfDrive on Google Play.
It is written for people browsing the Play Store, not for developers reading the
GitHub README.

## Asset Requirements Used

Source: Google Play Console Help, "Add preview assets to showcase your app":
https://support.google.com/googleplay/android-developer/answer/9866151

- Android Automotive OS portrait screenshots: `800 x 1280`
- Android Automotive OS landscape screenshots: `1024 x 768`
- Format: JPEG or 24-bit PNG without alpha
- Minimum AAOS screenshot set when provided: 2 portrait and 2 landscape
- App icon: `512 x 512`, 32-bit PNG with alpha, maximum file size 1024 KB

Prepared files:

- `docs/play-store/shelfdrive-play-icon-512.png`
- `docs/play-store/shelfdrive-feature-graphic.png`
- `docs/play-store/screenshots/aaos-portrait-01-library.png`
- `docs/play-store/screenshots/aaos-portrait-02-now-playing.png`
- `docs/play-store/screenshots/aaos-portrait-03-settings.png`
- `docs/play-store/screenshots/aaos-landscape-01-library.png`
- `docs/play-store/screenshots/aaos-landscape-02-now-playing.png`
- `docs/play-store/screenshots/aaos-landscape-03-settings.png`

## Main Store Listing - German

### App Name

ShelfDrive

### Short Description

Höre deine Audiobookshelf-Hörbücher direkt im Auto.

### Full Description

ShelfDrive bringt deine selbst gehostete Audiobookshelf-Bibliothek auf Android
Automotive OS. Die App verbindet dein Fahrzeug mit deinem eigenen
Audiobookshelf-Server und zeigt deine Hörbücher in der nativen Medienoberfläche
des Autos.

Statt einer ablenkenden eigenen Player-Oberfläche nutzt ShelfDrive die
Bedienelemente, die im Fahrzeug bereits vorhanden sind: Bibliothek, Suche,
Cover, Wiedergabe, Pause, Spulen, Sprungtasten und Fortschrittsanzeige erscheinen
im vertrauten Medienbereich deines Autos.

Mit ShelfDrive kannst du:

- Hörbücher aus deiner Audiobookshelf-Bibliothek im Auto durchsuchen
- zuletzt gehörte Titel schnell fortsetzen
- nach Hörbüchern suchen
- nach Autoren browsen
- Cover und Metadaten aus deinem Serverkatalog anzeigen
- MP3- und M4B-Hörbücher über die Fahrzeug-Mediensteuerung abspielen
- 15 Sekunden zurück oder vor springen
- die Wiedergabegeschwindigkeit anpassen
- deinen Hörfortschritt mit Audiobookshelf synchronisieren
- optional beim Pausieren 15 Sekunden zurückspringen, damit der Wiedereinstieg leichter fällt

Wichtig: ShelfDrive ist für Nutzer gedacht, die bereits einen eigenen
Audiobookshelf-Server betreiben. Die App enthält keine Hörbücher und keinen
öffentlichen Katalog. Du brauchst eine erreichbare Audiobookshelf-Instanz,
deine Serveradresse und deine Zugangsdaten.

ShelfDrive speichert Zugangsdaten verschlüsselt auf dem Gerät und verbindet
sich nur mit dem Server, den du in den Einstellungen einträgst. Es gibt keine
Werbung, keine Analyse-SDKs und kein Tracking.

ShelfDrive ist ein unabhängiges Projekt und nicht mit Audiobookshelf verbunden.

### Screenshot Captions

- Hörbücher im Fahrzeug durchsuchen
- Aktuelle Wiedergabe mit Cover, Fortschritt und Sprungtasten
- Server verbinden, Katalog synchronisieren und Wiedergabe einstellen
- Landscape-Ansicht der Hörbuchbibliothek
- Landscape-Ansicht der aktuellen Wiedergabe

## Main Store Listing - English

### App Name

ShelfDrive

### Short Description

Listen to your Audiobookshelf library in the car.

### Full Description

ShelfDrive brings your self-hosted Audiobookshelf library to Android Automotive
OS. Connect your vehicle to your own Audiobookshelf server and browse your
audiobooks through the car's native media experience.

ShelfDrive avoids a distracting custom player interface. It uses the media
controls already built into the vehicle: library browsing, search, cover art,
playback, pause, seeking, skip controls and listening progress all appear in the
car's familiar media area.

With ShelfDrive you can:

- browse audiobooks from your Audiobookshelf library in the car
- quickly continue recently played titles
- search your audiobook catalog
- browse by author
- show cover art and metadata from your server catalog
- play MP3 and M4B audiobooks through the vehicle media controls
- jump 15 seconds backward or forward
- adjust playback speed
- sync listening progress back to Audiobookshelf
- optionally rewind 15 seconds when pausing, so resuming starts with a short recap

Important: ShelfDrive is for people who already run their own Audiobookshelf
server. The app does not include audiobooks and does not provide a public
catalog. You need a reachable Audiobookshelf instance, your server address and
your login credentials.

ShelfDrive stores credentials encrypted on the device and connects only to the
server URL you configure in Settings. It includes no ads, no analytics SDKs and
no tracking.

ShelfDrive is an independent project and is not affiliated with Audiobookshelf.

### Screenshot Captions

- Browse your audiobooks in the car
- Now playing with cover art, progress and skip controls
- Connect your server, sync your catalog and adjust playback
- Landscape view of your audiobook library
- Landscape view of now playing

## Play Console Field Suggestions

### App Category

Music & Audio

### Tags

Audiobooks, Media player, Automotive

### Content Rating Notes

The app itself contains no media catalog. Any audiobook content is supplied by
the user's own Audiobookshelf server.

### Target Audience

Adults and general users who own or use an Android Automotive OS vehicle and
already operate an Audiobookshelf server.

### App Access

Restricted functionality requires a self-hosted Audiobookshelf server and valid
login credentials. For review, provide a demo server URL, username and password
in Play Console's App access section if the reviewer cannot evaluate the app
with an existing Audiobookshelf account.

Suggested reviewer note:

```text
ShelfDrive requires an Audiobookshelf server. Please use the provided demo
server URL and credentials to sign in from Settings, then run "Sync now" to load
the catalog. The media library appears in the Android Automotive OS media host.
```

### Privacy Policy

A privacy policy URL is recommended before public release. It should state that
ShelfDrive stores server URL, username, password/token data and local playback
state on the device, communicates with the user-configured Audiobookshelf server,
and includes no advertising, analytics SDKs or tracking SDKs.

### Data Safety Draft

- Data collected by the developer: none, unless a separate diagnostics upload
  endpoint is configured and used.
- Data shared with third parties: none by the app developer.
- Data processed locally on device: server URL, credentials/tokens, catalog
  metadata, artwork cache, playback state and cache data.
- Data sent over the network: requests to the user-configured Audiobookshelf
  server for authentication, catalog sync, artwork, streaming and progress sync.
- Security practices: credentials are stored encrypted on the device; Android
  backup is disabled for app data.
- Account deletion: account/session data can be removed from the app by logging
  out or clearing app data.

### Release Notes - Initial Public/Internal Listing

```text
Initial Android Automotive OS release for connecting a vehicle media host to a
self-hosted Audiobookshelf server. Supports library browsing, search, playback,
cover art, 15-second skip controls, playback speed control and progress sync.
```

## Notes Before Submission

- Provide reviewer credentials if the track is not limited to testers who already
  have access to a suitable Audiobookshelf server.
- Do not upload screenshots that expose a private server URL or real credentials.
- If a public release is planned, add a hosted privacy policy URL first.
