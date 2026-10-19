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
