# Mustard — native Android wrapper

An Android app that shows the Mustard PWA (`../website`, deployed at
`https://mustard-money.github.io/website/`) full screen in a WebView, and offers it the native
features a browser tab can't have. The first is **document scanning**: Google's ML Kit document
scanner, with the camera, edge detection, perspective correction, cropping, cleanup filters and
multi-page scans, all in a UI that Google Play services provides. The app needs no camera
permission of its own.

The page is still the deployed website, with its own service worker and offline cache, so a web
deploy reaches the app without an app update. Only changes to the native side need a new build.

How it compares to the other Android builds (all three install side by side):

| | App id | What it is |
| --- | --- | --- |
| `mobile-android` (this) | `com.mustard.mobile` | WebView + native bridge (document scanning); Android twin of `../mobile-ios` |
| `../pwa-android` | `com.mustard.pwa` | Trusted Web Activity: the PWA in Chrome, no native code, so no bridge |
| `../website/android` | `com.mustard.app` | The React Native build of the website |

## Running it

```bash
./run.sh                                  # debug build → install and open on an emulator or device
./run.sh --url http://localhost:5173/     # the same, opening the website's dev server (npm run dev)
./run.sh build                            # release APK + AAB in app/build/outputs/
```

`run.sh` finds the Android SDK and a JDK (17 or newer) itself, boots the first emulator if no
device is connected, and for `--url http://localhost:…` sets up `adb reverse` so the device's
localhost reaches this machine. Plain HTTP is allowed only for localhost and `10.0.2.2`, and only
in debug builds. The project also opens in Android Studio as is.

`PWA_URL=https://example.com/ ./run.sh` (or `-PpwaUrl=…` with Gradle) builds with another
deployment; the default is `pwaUrl` in `gradle.properties`.

Debug builds can be inspected from desktop Chrome at `chrome://inspect`.

## Using it from the website

The app defines `window.mustardNative` on pages from the PWA's origin, before the page's own
scripts run (a `mustardnativeready` event fires on `window` too). It's **the same API as the iOS
app** (`../mobile-ios`, see its README), so one code path serves both; in a browser it's
`undefined`, so feature-test:

```ts
const native = (window as any).mustardNative;
if (native?.features.documentScanner) {
  const scan = await native.scanDocument({ pdf: true });
  if (scan) {
    const pdf = await (await fetch(`data:${scan.pdf.mimeType};base64,${scan.pdf.base64}`)).blob();
    // upload pdf, or use scan.pages
  }
}
```

`scanDocument(options?)` opens Google's document scanner and resolves when the person finishes,
or with `null` if they cancel.

| Option | Default | |
| --- | --- | --- |
| `pages` | `true` | Return each page as a JPEG |
| `pdf` | `false` | Also return all pages as one PDF (the scanner's own) |
| `maxDimension` | `2000` | Longest side of each page, in pixels (200–6000) |
| `quality` | `0.8` | JPEG quality (0.1–1) |
| `pageLimit` | none | Android only: most pages per scan |
| `galleryImport` | `true` | Android only: offer picking photos instead of the camera |
| `mode` | `'full'` | Android only: `'full'` adds ML cleanup (stains, fingers, shadows) to `'filter'` (color filters) to `'base'` (crop and rotate) |

Result, as on iOS (`title` is always `null` on Android):

```ts
{
  title: string | null;
  pageCount: number;
  pages?: { mimeType: 'image/jpeg'; base64: string; width: number; height: number }[];
  pdf?: { mimeType: 'application/pdf'; base64: string };
}
```

Errors reject with an `Error` whose `code` is one of: `unsupported` (no Google Play services),
`unavailable` (the scanner module couldn't be loaded yet, e.g. offline on first use), `busy` (a
scan is already open), `encoding-failed`, `failed`.

Android also has `mustardNative.saveFile(blob, name)`, which opens the "Save to" screen and
resolves `false` if the person backs out.

- The user agent ends in `MustardAndroid/<version>`, for the server's benefit.
- Scans travel to the page as base64, so very long multi-page scans cost memory; `pageLimit` and
  `maxDimension` keep that in check.
- Underneath, the page talks to the app through `window.mustardNativeChannel` (JSON strings);
  `assets/bridge.js` turns that into `window.mustardNative`. Don't use the channel directly.

Besides the bridge, the app turns plain web features into native ones:

- `<input type="file">` opens Android's file picker; with `accept="image/*" capture="environment"`
  it opens the camera app (one photo, without edge detection).
- `<a download>` links to `blob:` and `data:` URLs (the website's exports) open Android's "Save
  to" screen through `saveFile`. A WebView would otherwise ignore them.
- `alert`, `confirm` and `prompt` show native dialogs; `confirm("Title\n\nDetails")` gets a title.
- Links outside the PWA open in a Custom Tab (or the matching app for `mailto:`, `tel:`).
- The status and navigation bars take the page's colors at the top and bottom edges, following
  the PWA's own Appearance setting.
- A connection error shows a "Try Again" screen that also reloads by itself once the network is
  back. Returning to the app checks for a new service worker, as a browser would on a fresh visit.

## Good to know

- The first scan on a device downloads the scanner module through Google Play services (a few
  seconds, with Google's own progress screen). On a device without Play services
  `features.documentScanner` is false.
- Data lives in the WebView's storage for this app. It is separate from Chrome's (and so from the
  TWA and the website in the browser) and from the React Native app's.
- The bridge is offered to the PWA's origin only (`addWebMessageListener` with an origin rule),
  so pages opened from elsewhere can't call it.

## Release signing

Without `keystore.properties`, release builds are signed with the debug key: fine for
emulators and devices, not accepted by Google Play. For a real key, create a keystore once

```bash
keytool -genkeypair -v -keystore mustard-mobile.jks -alias mustard -keyalg RSA -keysize 2048 -validity 10000
```

and describe it in `keystore.properties` next to this file (both are git-ignored; keep a backup):

```properties
storeFile=mustard-mobile.jks
storePassword=…
keyAlias=mustard
keyPassword=…
```

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each Play upload.

## What lives where

| File | Purpose |
| --- | --- |
| `gradle.properties` | `pwaUrl`, the page the app opens |
| `app/build.gradle.kts` | App id, version, signing, dependencies (ML Kit document scanner, androidx.webkit) |
| `app/src/main/kotlin/…/MainActivity.kt` | The web view and everything around it: splash, system bars, offline screen, dialogs, file picking, links |
| `app/src/main/kotlin/…/NativeBridge.kt` | The bridge's native side: document scanning and saving files |
| `app/src/main/kotlin/…/AppConfig.kt` | Start URL and which URLs count as the PWA |
| `app/src/main/assets/bridge.js` | The bridge's page side: `window.mustardNative`, download handling, bar colors |
| `app/src/main/res/values*/colors.xml` | Brand and page colors; keep in step with `website/src/constants/theme.ts` |
| `app/src/main/res/mipmap-*`, `drawable-*/splash.png` | Launcher icon and splash logo, the same as `../pwa-android` |
