# Privacy Policy for ShelfDrive

Effective date: 2026-05-08

ShelfDrive is an Android Automotive OS media app for connecting a vehicle media
host to a user-configured Audiobookshelf server.

ShelfDrive is an independent project and is not affiliated with Audiobookshelf.

Privacy contact: dsaos9632@gmail.com

## Short Version

ShelfDrive does not include advertising, analytics SDKs, tracking SDKs, or a
developer-operated content service.

ShelfDrive connects to the Audiobookshelf server URL that you enter in the app
settings. If you explicitly send diagnostics, ShelfDrive uploads a diagnostic
package to the diagnostics upload URL shown in Settings.

ShelfDrive stores account, catalog, artwork, playback, cache, diagnostics, and
settings data locally on your Android Automotive OS device so the app can sign
in to your server, show your audiobook library, play audio, and synchronize
listening progress.

## Data Stored on the Device

ShelfDrive may store the following data locally on your device:

- Audiobookshelf server URL.
- Username.
- Encrypted password or session token.
- Audiobook catalog metadata loaded from your Audiobookshelf server.
- Cover art and author artwork loaded from your Audiobookshelf server.
- Playback state, queue state, current media metadata, and listening progress.
- Cache data for catalog, artwork, and audio playback.
- App settings, including playback preferences.
- Diagnostics state, such as service startup status, restore status, upload
  status, and error messages.
- Diagnostics upload URL.

Credentials are stored using Android encrypted storage. Android backup is
disabled for ShelfDrive app data.

## Network Communication

ShelfDrive sends requests to the Audiobookshelf server URL configured by you.
These requests are used to:

- Sign in and refresh the session.
- Load library, audiobook, author, artwork, and metadata information.
- Resolve audio streams for playback.
- Synchronize listening progress.
- Check server compatibility.

ShelfDrive supports both HTTPS and HTTP for local or private Audiobookshelf
servers. HTTPS is recommended whenever your server is reachable outside a
trusted local network.

## Audiobookshelf Server Responsibility

Your Audiobookshelf server is controlled by you or by the person or organization
that provides that server to you. ShelfDrive does not control how that server
stores, logs, processes, or shares data.

If you connect ShelfDrive to a server operated by someone else, that server
operator may receive and process your login attempts, media requests, playback
requests, artwork requests, IP address, user agent, timestamps, and listening
progress according to that server operator's own policies and configuration.

## Diagnostics Upload

ShelfDrive can optionally create and upload a diagnostics package if you
explicitly start the upload. The app includes a default diagnostics upload URL
that can be changed in Settings.

The diagnostics package may include:

- App logs and diagnostic events.
- App version and build information.
- Device and runtime details.
- Authentication, connection, sync, cache, restore, and upload status.
- Error messages.

The diagnostics package does not intentionally include your saved password.

If you use diagnostics upload, the package is sent to the diagnostics upload URL
shown in Settings. The recipient of that URL controls what happens to the
uploaded file after it is received.

## Data Shared with Third Parties

ShelfDrive does not sell user data.

ShelfDrive does not share user data with advertising networks, analytics
providers, tracking providers, or developer-selected third-party content
services.

Data may be transmitted to:

- The Audiobookshelf server URL configured by you, as required for app
  functionality.
- The diagnostics upload URL shown in Settings, only if you explicitly send a
  diagnostics package.

If this privacy policy is hosted on GitHub or GitHub Pages, GitHub may process
standard web access data when you open this page. That processing is governed by
GitHub's own privacy terms.

## Data Retention and Deletion

ShelfDrive keeps local app data until it is removed by you or by Android.

You can remove data in the following ways:

- Use "Log out" in ShelfDrive settings to remove the local session and
  credentials from the vehicle.
- Use "Clear cache" in ShelfDrive settings to remove cached catalog data,
  artwork, audio cache, and locally cached progress. Login data remains saved.
- Clear app data in Android settings to remove ShelfDrive's local app data.
- Uninstall ShelfDrive to remove local app data from the device.

Data stored on your Audiobookshelf server must be managed on that server.
ShelfDrive cannot delete server logs, server-side media metadata, server-side
accounts, or server-side listening history except through the normal
Audiobookshelf API operations used by the app.

Data uploaded through diagnostics upload must be deleted by whoever controls the
configured upload endpoint.

## Children

ShelfDrive is not designed for children and does not provide child-directed
content. The app does not include its own media catalog. Any audiobook content
comes from the Audiobookshelf server configured by the user.

## Security

ShelfDrive uses Android encrypted storage for credentials and session data.
Network transport security depends on the server URL configured by the user.
HTTPS is recommended for any non-local or public network connection.

No method of storage or transmission is perfect. ShelfDrive cannot guarantee
that a user-configured server, network, vehicle system, or diagnostics upload
endpoint is secure.

## Changes to This Policy

This policy may be updated when ShelfDrive changes how it handles data. The
effective date at the top of this document indicates the current version.

## Contact

For privacy questions about ShelfDrive, contact:

dsaos9632@gmail.com
