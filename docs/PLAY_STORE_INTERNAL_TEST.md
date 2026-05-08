# ShelfDrive Play Store Internal Test

This checklist prepares ShelfDrive for installation on an Android Automotive OS vehicle through a Google Play internal test track.

## 1. Decide The Package ID Before The First Upload

The current Android package/application ID is:

```text
io.shelfdrive.app
```

After the first upload to Google Play, this ID is effectively permanent for the app listing. Only upload the first bundle once you are comfortable owning this ID long-term.

## 2. Create An Upload Key

Create a local upload keystore and keep it outside version control. One possible command is:

```bash
keytool -genkeypair -v \
  -keystore shelfdrive-upload.jks \
  -alias shelfdrive-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and fill in the real values:

```properties
storeFile=shelfdrive-upload.jks
storePassword=your-store-password
keyAlias=shelfdrive-upload
keyPassword=your-key-password
```

Both `keystore.properties` and `*.jks` are ignored by git.

## 3. Build The Release Bundle

Run the release checks and bundle build:

```bash
./gradlew lintDebug testDebugUnitTest assembleRelease bundleRelease
```

The Play upload artifact is the Android App Bundle:

```text
app/build/outputs/bundle/release/app-release.aab
```

Use the AAB for Google Play. APKs are useful for local debugging, but new Play Store apps are published with App Bundles.

## 4. Create The Play Console App

Create a new app in Play Console:

- App name: ShelfDrive
- App type: App
- Price: Free, unless you explicitly want a paid listing
- Category: Audio or Music/Audio, depending on the available Play Console taxonomy

Enable Play App Signing when prompted. Google Play keeps the app signing key and your local key becomes the upload key.

## 5. Enable Android Automotive OS

In Play Console, go to:

```text
Setup > Advanced settings > Form factors
```

Add Android Automotive OS and complete the Automotive requirements:

- Upload Android Automotive OS screenshots for every store listing.
- Confirm the Android Automotive OS review policy.
- Use the dedicated Android Automotive OS track. Media apps are not distributed to AAOS through the normal mobile track.

## 6. Create The Internal Test

Go to:

```text
Testing > Internal testing
```

Create a tester list and add the Google account used in the vehicle. Upload the signed release AAB to the Android Automotive OS internal test track and roll out the release.

Internal testing is intended for fast QA distribution. Google documentation states that internal tests can include up to 100 testers and that Android Automotive OS form-factor review is not blocking for internal testing.

## 7. Install On The Vehicle

Open the opt-in link with the tester Google account. On the vehicle, use the same Google account in the Play Store, then install ShelfDrive from the test listing.

If the app does not appear immediately:

- Wait a few minutes; first-time test links can take a few hours.
- Confirm the vehicle uses the same Google account.
- Confirm the release was uploaded to the Android Automotive OS track, not only the mobile internal track.
- Confirm the version code is higher than any previously installed or uploaded build.

## Current Project Notes

- `android.hardware.type.automotive` is declared as required.
- The manifest declares `android:appCategory="audio"`.
- `res/xml/automotive_app_desc.xml` declares media support.
- Cleartext HTTP is currently allowed for private Audiobookshelf LAN servers. Prefer HTTPS for broader testing or public release.
- Release signing is configured only when `keystore.properties` exists, so debug builds keep working without local release secrets.
- If `keystore.properties` is missing, `bundleRelease` can still produce an unsigned AAB for compile verification. Google Play requires a bundle signed with your upload key.
