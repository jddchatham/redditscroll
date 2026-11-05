# RedditScroll

Subreddit scroller using Reddit's Atom feeds (JSON API is IP-blocked anonymously).

## Features
20 category pills · hot/new/top listings · search.rss · paywall-free

## Build (fully inside Termux, no Android Studio)

Toolchain: `aapt2`, `ecj`, `dx`, `zipalign`, `apksigner` (Termux packages), plus an API-33 `android.jar`.

```sh
./build.sh
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed; a keystore is auto-generated on first build).

Pure framework Java, no dependencies, no Gradle, minSdk 21.

## Linux native port

A GTK3 + WebKit2 desktop version lives in `linux-native/`.

```sh
./linux-native/redditscroll        # tall phone-like window
./linux-native/redditscroll --normal
```

Requirements: Python 3, `gobject-introspection`, GTK3, WebKit2GTK 4.1 (package names vary by distro, e.g. `python3-gi`, `gir1.2-webkit2-4.1` on Debian/Ubuntu).

It also installs a `.desktop` entry and a `redditscroll` command in `~/.local/bin` if you run the launcher from there.
