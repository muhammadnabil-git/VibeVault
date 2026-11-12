# 7.7.1

## Navigation
- The old top tab bar is gone. **VV (Home) is the only main screen.** Search (the search bar), Download, Media and History open
  as their own pages from VV (History is the 5th shortcut after Identify, Radio, Download, Media). Back (button or gesture)
  returns to VV from any of them; Identify, Radio and Settings also return to VV.

## VV dashboard
- "Continue listening" is replaced by **Videos** (every saved/imported video).
- Audio cards show the **cover inside the file; if there is none, the artist's photo** (looked up once on Deezer by artist
  name, cached on the phone, can be switched off in Settings > Behavior > Artist pictures).

## Media / History
- New third view, **Sliders**: Audio and Video on ONE page as two swipeable rows (View menu: List / Grid / Sliders), in both
  Media and History. The old grid on/off choice is migrated.

## Radio / Detector / Settings
- Radio redesigned in the brand style (gradient station card with pulsing ON AIR dot, round controls, pill play button,
  card station list). Radio, Detector and Settings now show the faint logo watermark like the other pages.

## Player
- The logo is now a faint watermark with no frame; it can no longer cover the play button. The song name sits at the top.

## Widgets (4 shapes)
Wide bar (4x1) · Square card (2x2) · Round play button (1x1) · Quick dashboard (4x2: now playing + Identify / Radio /
Search / Media / History shortcuts that open that page).

versionName 7.7.1 (versionCode 100). applicationId unchanged.

---

# 7.7.0

## Security & stability (Phase 1)
See SECURITY_AUDIT.md and RELEASE.md. Signing passwords removed from source; CI uses secrets; MediaStore saves are
transactional (no orphan/pending files); 8 unsafe `!!` removed; WebView bridge restricted to the current https page; the
exported playback service no longer opens arbitrary `file://` paths for other apps; BootReceiver not exported;
storage is checked before a download starts and before a file is copied to Downloads.

## Design (Phase 2)
- New VibeVault identity: VV emblem (adaptive, monochrome, notification, splash, in-app) + brand palette theme (default for new users).
- `DesignSystem.kt`: spacing/radius/type tokens and reusable components (card, section header, buttons, chips, icon tile,
  empty / loading / error states, skeleton, logo).
- New **Home** dashboard (first tab): greeting, search, running-download card, shortcuts, Continue listening, Recently played,
  Recently downloaded, Smart playlists, Library shortcuts; every section can be switched off ("Customize").
- Player: new artwork card; Sleep timer, Equalizer and Lyrics buttons.
- Tablets / landscape: content is centred in a readable column (max 840 dp).

## New features (Phase 4)
Sleep timer (minutes or end of file) · Equalizer with the device's presets and per-band sliders · Recently played ·
Continue listening on Home · Smart playlists · Duplicate-media detection (Storage screen) · Backup & restore (merging JSON file) ·
Playlist sharing (titles + web links) · Home-screen playback widget · "Report a problem" (prefilled email, no personal data) ·
Lyrics shortcut (web search in the in-app browser; lyrics are not copied into the app).

## Detector (earlier in this release cycle)
Apple Music / Anghami lookup rewritten (parallel tracks, regional stores, length-based matching for Arabic titles).

versionName 7.7.0 (versionCode 99). applicationId unchanged (com.example.ytsaver).
