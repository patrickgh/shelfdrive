# ShelfDrive Diagnostics Server

Small local upload receiver for ShelfDrive diagnostic ZIP files.

Build and run:

```bash
docker build -t shelfdrive-diagnostics tools/diagnostics-server
docker run --rm -p 8080:8080 -v "$PWD/diagnostics-uploads:/data/uploads" shelfdrive-diagnostics
```

Deploy directly from GitHub with Docker Compose:

```bash
curl -O https://raw.githubusercontent.com/patrickgh/shelfdrive/main/tools/diagnostics-server/compose.yml
DIAGNOSTICS_DOWNLOAD_PASSWORD='change-this-download-password' docker compose up --build -d
```

The compose file builds from:

```text
https://github.com/patrickgh/shelfdrive.git#main:tools/diagnostics-server
```

Redeploy after a GitHub update:

```bash
docker compose build --pull --no-cache diagnostics
docker compose up -d
```

Uploads are stored in the named Docker volume `diagnostics-uploads`, so
recreating the container does not delete uploaded diagnostic packages.

Use this upload URL in the app settings:

```text
http://YOUR_HOST_OR_IP:8080/upload
```

The Android app also accepts the base URL and will append `/upload`
automatically:

```text
http://YOUR_HOST_OR_IP:8080
```

The Android app defaults to:

```text
https://shelfdev.mooo.com:23377/
```

Uploads use separate built-in Basic Authentication credentials from downloads.
The Android app sends the upload credentials automatically, so only the URL must
be entered in the app settings.

Default upload credentials:

```text
username: shelfdrive-upload
password: sd-upload-2026-K7mQ4p9v
```

Default download credentials:

```text
username: shelfdrive-download
password: sd-download-2026-P8wN3x2r
```

You can override the server-side credentials with:

```bash
docker run --rm -p 8080:8080 \
  -e DIAGNOSTICS_UPLOAD_USERNAME='shelfdrive-upload' \
  -e DIAGNOSTICS_UPLOAD_PASSWORD='sd-upload-2026-K7mQ4p9v' \
  -e DIAGNOSTICS_DOWNLOAD_USERNAME='shelfdrive-download' \
  -e DIAGNOSTICS_DOWNLOAD_PASSWORD='change-this-download-password' \
  -v "$PWD/diagnostics-uploads:/data/uploads" \
  shelfdrive-diagnostics
```

If you change the upload credentials on the server, the Android app must be
changed to match. Changing only the download credentials does not require an app
release.

For compatibility with older deployments, `DIAGNOSTICS_BASIC_USERNAME` and
`DIAGNOSTICS_BASIC_PASSWORD` are still accepted as upload credential overrides
when the new upload-specific variables are not set.

List stored packages:

```bash
curl -u shelfdrive-download:sd-download-2026-P8wN3x2r http://YOUR_HOST_OR_IP:8080/
```

Download a package from the `downloadUrl` returned by the listing:

```bash
curl -u shelfdrive-download:sd-download-2026-P8wN3x2r -O http://YOUR_HOST_OR_IP:8080/uploads/PACKAGE.zip
```

Upload, listing, and download endpoints require Basic Authentication. `/health`
is unauthenticated for deployment checks.
