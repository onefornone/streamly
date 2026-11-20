# Streamly - Kotlin host for CloudStream .cs3 plugins, with a Zangetsu-style app layer

## Build the APK on GitHub (no Android Studio needed)
1. Create a GitHub repo and upload the CONTENTS of this folder (not the zip). Check that `.github/workflows/build.yml` arrived;
   browsers sometimes skip hidden folders - if it's missing, use Add file > Create new file, name it `.github/workflows/build.yml`, paste it.
2. Optional: Settings > Secrets and variables > Actions > New repository secret: `TMDB_KEY` = your own TMDB key.
3. Actions tab > "Build APK" > the latest run > Artifacts: download `streamly-debug-apk`, unzip, install the .apk.
4. If the run is red: open it, expand "Build debug APK", copy the lines starting with `e: ` (Kotlin errors), or download the `build-log` artifact.
   The first run may fail while JitPack builds the CloudStream library; just press Re-run.

## Install safety
Plugin lists carry a `fileHash` (sha256); installs and updates are checked against it. Installed versions are remembered, so loading a
repository again flags newer versions and offers one-tap Update. There is no 'install everything' button on purpose.

## Toolchain (needed by the CloudStream library v4.8.0)
AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14, compileSdk 36 - copied from a project known to build against this library.

## Built-in source
`sources/ArchiveProvider.kt` (Internet Archive films/TV) is registered at startup, so Home has content with zero plugins.

## Setup
1. Copy `local.properties.example` to `local.properties`; set `TMDB_KEY=` to YOUR OWN free TMDB v3 key
   (themoviedb.org -> Settings -> API). Without it the app still works; posters/episode stills just fall back to
   whatever the source supplies.
2. Open in Android Studio, sync, run. Install an APK (not Play Store: Play forbids downloading executable code).
3. Plugins tab -> paste a repo.json / plugins.json / .cs3 URL -> confirm -> install.
   Test with a plugin YOU build from https://github.com/recloudstream/TestPlugins (TMDB example).

## What is built
- plugins/PluginManager + PluginStubs: recognize link kinds, trust prompt, download, validate, PathClassLoader load,
  per-plugin provider tracking (same steps Zangetsu's PluginHost.kt uses; library v4.8.0).
- core/ProviderHost: home / search (all sources in parallel) / load / episodes / links.
- metadata/: TMDB client (second host fallback) + MetadataRepository (strict title match, 7-day disk cache):
  poster fallback, backdrop, synopsis, rating, year, episode titles/stills.
- data/WatchStore: resume positions, Continue Watching, My List.
- Plugin settings: PluginSettingsActivity (AppCompat/Material host) + PluginManager.openSettings (re-binds the plugin to a real
  Activity, drops duplicate providers), requiresResources support, Reset data, and the app-module stand-ins plugins need
  (DataStore, CloudStreamApp, CommonActivity, AcraApplication, Event). Plugins tab shows Settings / Reset data / Remove per plugin.
- Player: fullscreen (system bars hidden, notch area used), landscape (activity handles rotation, player never restarts;
  turning the phone sideways enters fullscreen, upright leaves it), PiP (button + automatic on Home while playing,
  window follows the video's aspect ratio, controller hidden in the window, pauses when the window is closed).
- Player gestures (ui/PlayerGestures.kt): swipe left/right to seek (screen width = 90 s, applied on release), swipe up/down on the
  left half for brightness (this window only, restored on exit) and on the right half for volume, double-tap left/right for
  -/+10 s, single tap shows/hides controls. Axis locks after ~24 px; the bottom 96 dp stays free for the controller's seek bar.
- ui/: Home (source chips, rows, continue watching), Search, Library, Detail (hero, resume, episodes with stills,
  stream picker), Player (Media3, headers/referer, subtitles, auto-failover, saves progress), Plugins.

## Not verified (no network / Android SDK in my sandbox: nothing here has been compiled)
- CloudStream API shapes used: SearchResponse.posterHeaders, Episode.name/season/episode/posterUrl/description/data,
  LoadResponse.plot, ExtractorLink.source/quality/headers/referer/type, SubtitleFile.lang/url, DubStatus.Subbed.
  A mismatch shows up as a compile error in ProviderHost.kt / DetailScreen.kt / PlayerScreen.kt.
- Gradle/AGP/Kotlin/Compose versions are reasonable picks; adjust to your Android Studio.

## Not built yet (Zangetsu has these)
Android TV UI, trackers (AniList/MAL/Simkl), cloud sync, downloads, new-episode notifications, Sub/Dub toggle,
AniList season chains, Cloudflare solver, per-source search filters, PiP play/pause buttons, fullscreen-button icon sync,
long-press 2x speed, pinch-to-zoom/aspect modes, gesture exclusion near the system back-gesture edges.

## Also unverified
- BasePlugin.filename, APIHolder.removePluginMapping, Plugin.openSettings/resources shapes (taken from Zangetsu's usage).
- If the build reports duplicate classes for DataStore / CloudStreamApp / CommonActivity / Event, the library version you
  use already ships them: delete the matching stand-in.
- Plugin settings UIs vary a lot; test each plugin's Settings button. Plugins that need CloudStream's own app resources
  or fragments may still fail.

## Notes
- AniList now rejects anonymous requests (Zangetsu's code comments say so), so anime metadata via AniList needs a
  user token; TMDB is used here instead.
- Including CloudStream library code makes your app GPL-3.0.
- Source plugins are third-party code; this project ships none and points at none.
