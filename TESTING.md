# Tests

JVM unit tests are in `app/src/test/java/com/example/ytsaver/`:
- `DuplicateFinderTest` — duplicate rules, newest kept, imported device files untouched.
- `SmartPlaylistsTest` — weekly / music / video / biggest lists, empty lists hidden.
- `FormatAndShareTest` — countdown format, `sourceId`, playlist share text never contains local file paths.
- `TrackArtNameTest` — artist taken from a file name (first artist, "feat." removed).
- `SongMatchTest` — Anghami / Apple Music title+artist matching (regression tests for the detector fix).

Run: `gradle testDebugUnitTest`

**Status: these tests were written but NOT executed** — the environment that produced this release had no Gradle/Android SDK.
Run them (and `gradle assembleRelease`) before shipping; fix anything they reveal.

## Manual QA checklist (not automated)
Install over an existing 7.6.x (data, playlists, history, theme kept) · first launch + permissions · Home sections and
Customize · search → download → History · pause/resume/retry download, airplane-mode recovery · low-storage message ·
play MP3/MP4, lock screen, headphone unplug, Bluetooth auto-play · sleep timer / equalizer / lyrics button ·
playlists: create, share, schedule · backup → reinstall → restore · widget play/pause/next · vault lock on background ·
Arabic RTL + English · phone and tablet · Android 10, 13, 14, 15.
