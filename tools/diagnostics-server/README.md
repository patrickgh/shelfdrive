# ShelfDrive Diagnostics Server

Small local upload receiver for ShelfDrive diagnostic ZIP files.

Build and run:

```bash
docker build -t shelfdrive-diagnostics tools/diagnostics-server
docker run --rm -p 8080:8080 -v "$PWD/diagnostics-uploads:/data/uploads" shelfdrive-diagnostics
```

Use this upload URL in the app settings:

```text
http://YOUR_HOST_OR_IP:8080/upload
```

The Android app also accepts the base URL and will append `/upload`
automatically:

```text
http://YOUR_HOST_OR_IP:8080
```

Uploads use built-in Basic Authentication credentials:

```text
username: shelfdrive
password: diagnostics
```

The Android app sends those credentials automatically, so only the URL must be
entered in the app settings. You can override the server-side credentials via
`DIAGNOSTICS_BASIC_USERNAME` and `DIAGNOSTICS_BASIC_PASSWORD`, but then the app
must be changed to match.

List stored packages:

```bash
curl -u shelfdrive:diagnostics http://YOUR_HOST_OR_IP:8080/
```

Download a package from the `downloadUrl` returned by the listing:

```bash
curl -u shelfdrive:diagnostics -O http://YOUR_HOST_OR_IP:8080/uploads/PACKAGE.zip
```

All endpoints require Basic Authentication.
