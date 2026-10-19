# iCloud Photos on Android (unofficial)

An unofficial Android web client for Apple's iCloud Photos web app.
(The app installs under the name **PhotosBridge**.)

PhotosBridge is a small, native Android shell around the iCloud Photos
website. Apple does not ship an iCloud Photos app for Android; the website
works, but living in a browser tab is clunky: tabs get closed or discarded,
uploads die quietly, downloads land in browser limbo, and there is no app
icon, no share-sheet entry, and no lock screen. PhotosBridge fixes the
friction without reimplementing anything: it is the real website, in a
purpose-built app.

This project is not affiliated with, endorsed by, or sponsored by Apple
Inc. "iCloud" and "Apple" are trademarks of Apple Inc. PhotosBridge never
contacts any server other than Apple's, contains no analytics, and does
not scrape or reverse-engineer any private API: it renders the same public
website you could open in Chrome.

## Features

- Persistent login session (you sign in only on Apple's own page; the app
  has no login form and never sees or stores your Apple password)
- Upload photos and videos, including large files, via a native file
  picker bridge with multi-select
- Create albums, manage your albums, join and contribute to shared albums
- Favorite, like, comment, download, and delete - all the website's own
  features, preserved by careful WebView plumbing (popups, JS dialogs,
  multi-window flows)
- Downloads through Android DownloadManager into your Downloads folder
- Opens iCloud Photos and shared-album links from WhatsApp, email, etc.
- Share photos straight from your gallery app into PhotosBridge
- Optional fingerprint lock for the app
- Hamburger drawer with Library / Albums shortcuts and a
  "Create / manage albums (desktop layout)" mode
- Keeps the screen awake during uploads; fit-to-screen mobile rendering
  with clean photo swiping

## Security model

- The app never asks for, sees, or stores your Apple ID password. Sign-in
  happens on Apple's page inside the WebView, exactly like a browser.
- What persists is the session cookie, kept in the app's private Android
  sandbox - the same trust model as staying signed in to a browser.
- Permissions are minimal: Internet, network state, and (optionally)
  biometrics for the app lock.
- A "Sign out / clear session" action wipes cookies and site storage.

## Get the APK

Grab `photosbridge-v0.3.apk` from the [latest release](https://github.com/amanthakkar/icloud-photos-on-android/releases)
and sideload it (Android will ask you to allow installs from your browser/files app).
The release build is signed with a debug key — fine for personal sideloading; if you'd
rather trust only your own key, build from source below (the script generates your own
keystore on first run). To update without losing your iCloud session, always install
builds signed with the *same* key over the top of each other.

## Building

The app is plain Java with framework APIs only - no AndroidX, no Gradle
required. The included build.sh compiles with a manually assembled
Android SDK (javac, d8, aapt2, zipalign, apksigner):

    ./build.sh

You will need JDK 17 and Android SDK platform 35 + build-tools 35, and
your own signing keystore (the script generates a debug one if missing).
Keystores are git-ignored: never commit yours.

## License

MIT - see LICENSE.

## Disclaimer

Personal project, provided as-is. Your use of iCloud is governed by
Apple's own terms of service. If you do not trust a WebView shell with
your session, use your browser instead - it is the same website.

---

## Part of a personal-Muse ecosystem

Built and run by [@amanthakkar](https://github.com/amanthakkar) — one person's Muses, coordinating like a small team:

- **[muse-mailbox](https://github.com/amanthakkar/muse-mailbox)** — E2E-encrypted Muse↔Muse messaging via a blind relay
- **[musebook](https://github.com/amanthakkar/musebook)** — private shared boards for a family's Muses
- **[cortex-showcase](https://github.com/amanthakkar/cortex-showcase)** — the homelab agent stack these grew out of, as an [interactive architecture tour](https://amanthakkar.github.io/cortex-showcase/)
- **[iCloud Photos on Android](https://github.com/amanthakkar/icloud-photos-on-android)** — Apple Photos on Android, built the same fix-the-friction way ← **you are here**
- **Muse gadget skills** — [Tesla + Nest](https://github.com/facebookincubator/muse-gadget-sdk/pull/222) and [Bambu Lab](https://github.com/facebookincubator/muse-gadget-sdk/pull/224) skills proposed to Meta's muse-gadget-sdk
