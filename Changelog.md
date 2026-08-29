# Changelog — PlayTube (Modified Version)

This document summarizes two rounds of updates applied on top of the original `PlayTube-main` source code:

- **Update 1** — Fixes for all identified bugs/audit findings (security, privacy, data integrity, stability) + a **YouTube-style fullscreen button**.
- **Update 2** — Additional features: **playback queue management**, **audio & subtitle downloads**, and **private mode + biometric app lock**.

Document map:

| Document | Content |
|---|---|
| `Changelog.md` (this file) | Summary of changes per update, for quick reading |
| `Details.md` | Complete list of modified / deleted / added files per update |
| `FIXES.md` | In-depth technical details for each fix (markers like `FIX(BUG #N)` / `FEATURE` are also present in the code) |

---

## UPDATE 1 — Bug Fixes + Fullscreen Button

### Security & Privacy

1. **Fake PoToken system removed entirely.** Previously, every `googlevideo.com` stream request included the `X-Goog-Po-Token` header containing a fake timestamp string (`"po_token_<timestamp>"`). These invalid tokens were the root cause of recurring **403 errors** during playback. The interceptor in `NetworkModule.kt` has been cleaned up; `PoTokenProvider.kt` and `potoken/PoTokenGenerator.kt` have been **deleted**; and the fake `X-Goog-Visitor-Id` header has also been removed from `YouTubeHttpDataSource.kt`. Requests are now sent without a token — which is the correct behavior without a genuine BotGuard implementation.

2. **Database upgrades no longer wipe user data.** The blanket `fallbackToDestructiveMigration()` in `DatabaseModule.kt` has been replaced with `fallbackToDestructiveMigrationOnDowngrade()` — version upgrades (e.g., from v9 to v10) will no longer completely clear the entire watch history.

3. **Cloud backup is locked down.** `AndroidManifest.xml` now uses `allowBackup="false"`, and the previously empty TODO `backup_rules.xml` / `data_extraction_rules.xml` have been rewritten with actual rules: the Room database and DataStore are **excluded** from Google backups and device transfers. Watch history and interest profiles remain on the device.

4. **MediaSession is locked to the app.** `PlaybackService.kt` now verifies `onConnect`: only the PlayTube package itself is allowed to control the player. Previously, **any arbitrary app** could bind to the MediaController and control/inject media items.

### Critical Bug Fixes (P0)

5. **Endless recovery loop halted.** In `PlaybackManager.kt`, only HTTP 403 triggers recovery re-extraction; other network errors are routed to a backoff retry path in the ViewModel. `PlayerViewModel.kt` limits `recoverExpiredUrl()` to a maximum of **3 attempts** with backoff; the counter resets on successful playback / new video. The mechanical retry (`scheduleRetry`), which was previously dead code, is now properly connected.

6. **Chunked downloads now require HTTP 206.** `ParallelDownloader.kt` rejects `200 OK` responses (proxies that ignore `Range`) — previously, the full body was written into a 4 MB chunk slot, **corrupting the final downloaded file**.

7. **Resume download accuracy.** Retries now re-read chunk checkpoints from the database (instead of using stale in-memory progress), checkpoints are triggered every 512 KB interval (the old condition `% 512KB == 0` was almost never met), progress no longer exceeds 100%, and stale chunk maps (new URL with different sizes) are detected and rebuilt. Added `MissionDao.getChunkById()` / `deleteChunksForMission()`.

8. **Backup restore no longer "falsely succeeds".** `DataManagerRepositoryImpl.kt` now scans **all** zip entries for `backup.json` (previously only checked the first entry), fails with a clear message if absent, and imposes a **256 MB** decompression limit (zip-bomb/OOM protection). The `CANCELLED` worker status is now handled, so the import UI never gets stuck forever.

9. **Stale state is cleaned up during auto-advance.** Switching videos via autoplay now: disposes of the previous video's SponsorBlock segments, fetches segments for the new video, and resets quality state — previously, the next video would incorrectly use the previous video's SponsorBlock segments.

### Stability & Quality Fixes

10. **Thread-safety** — `activeMissions` / `lastUpdateMap` in `VideoDownloadService.kt` are now `ConcurrentHashMap` with a `@Volatile` foreground flag (previously mutated from both the main thread AND IO coroutines simultaneously); the cookie jar in `NetworkModule.kt` is now thread-safe as well.
11. **Takeout import fixed** — `ImportWorker.kt`: the CSV parser now respects quoted fields (commas inside quotes no longer shift columns), and timestamps with API < 26 are parsed correctly (previously all fell to "now", breaking chronological order on Android 7/7.1).
12. **Smart proxy** — `DynamicProxySelector.kt` bypasses the proxy for loopback, private RFC1918 IPs, link-local, and `.local` hosts (previously, LAN hosts were forcibly routed through the proxy); `connectFailed()` is now logged.
13. **Low-level fixes** — `Converters.kt` (unknown enums no longer crash on DB read), `VideoUtils.kt` (`extractVideoId` now matches `?v=`/`&v=` exactly, not arbitrary substrings), `UpdateResponse.kt` + `UpdateRepositoryImpl.kt` (null fields from GitHub Release no longer crash deserialization), `NewPipeInitializer.kt` (`@Volatile` flag), `UpdateUserInterestsUseCase.kt` (interest decay now runs every 24 hours — previously the `millis % 50 == 0` condition was almost never true), `DownloadRepositoryImpl.kt` (removed no-op `cancelUniqueWork` call, sanitized MediaStore filenames), `VideoPlayerGestureDetector.kt` (single-tap now only triggers after the finger is lifted without dragging — dragging for volume/brightness no longer opens/closes the control overlay).

### FEATURE: YouTube-Style Fullscreen Button

14. **Fullscreen button in the bottom-right corner of the player control overlay** (`PlayerScreen.kt`) — placed exactly like the YouTube app, with `Fullscreen` / `FullscreenExit` icons based on orientation.
    - Entering fullscreen: rotates to `SENSOR_LANDSCAPE` (allows tilting left/right like YouTube); status bar & navigation bar automatically hide (immersive 16:9 mode).
    - Exiting fullscreen: returns to portrait, system bars automatically restore.
    - The swipe-up gesture on the player area now toggles the same behavior (previously it forced a fixed orientation and couldn't be undone with the same gesture).

---

## UPDATE 2 — Additional Features

### Feature 1: Playback Queue Management

- **`QueueManager.kt` has been rewritten** into a true queue engine: a user-owned explicit queue, reactive StateFlow, *play next* (insert at front), *add to queue* (append at back), remove items, **drag-to-reorder**, shuffle, and repeat (OFF / ALL / ONE with wrap-around; repeat ONE is natively applied in ExoPlayer).
- **`QueueSheet.kt` (new file)** — YouTube-style queue screen: long-press and drag to reorder (live swapping with haptic feedback), clear/delete buttons per item, header containing shuffle + repeat + clear queue.
- **`PlayerViewModel.kt`** — `playNext()`/`playPrevious()` now prioritize: 1) explicit queue → 2) playlist order (shuffle-aware, repeat-all wrap) → 3) related autoplay. Playlist indices are recalculated synchronously to ensure fast skips don't miss the mark, and autoplay never repeats the same video.
- **`PlayerScreen.kt`** — a `QueueMusic` button in the top control pill opens the queue sheet.
- **`PlayerComponents.kt` + `VideoItemComponents.kt`** — related videos and any video row now have "Play next" and "Add to queue" actions in their menu (optional; other screens remain unaffected).

### Feature 2: Audio & Subtitle Downloads

- **Audio-only** — `VideoDownloadService.kt` now supports audio missions (`videoUrl=null`, `quality="Audio"`): selects the best audio track, downloads without muxing, and renames to `.m4a`/`.opus`. `DownloadRepositoryImpl.kt` exports audio to the MediaStore **Audio** collection in `Music/PlayTube` (video remains in `Movies/PlayTube`). The quality selection sheet (`SelectionSheets.kt`) now includes an **"Audio only (best available)"** section.
- **Subtitle** — **`DownloadSubtitleUseCase.kt` (new file)**: fetches subtitle tracks (rewrites YouTube URLs from `fmt=json3/srv3/ttml` to `fmt=vtt`), validates the WebVTT payload, and saves to `Downloads/PlayTube` via MediaStore on Android 10+, or the app's external directory below that (without storage permission). `SubtitleSelectionSheet` now has a download button per language, with Snackbar feedback.

### Feature 3: Private Mode + Biometric App Lock

- **Private mode (incognito)** — `PreferencesManager.kt` stores the session start timestamp; while the session is active, browsing leaves no trace; when the session is **turned off**, `LibraryRepository.purgeDataSince()` (interface + implementation) deletes **all** watch history and search history recorded since the session started (`HistoryDao.deleteHistorySince` / `SearchHistoryDao.deleteSearchHistorySince`). A "Private session" toggle is available in `SettingsScreen.kt` (History & Privacy group).
- **Biometric app lock** — `MainActivity.kt` displays a full-screen lock gate via `BiometricPrompt` (`BIOMETRIC_WEAK` + device credentials on Android 11+), automatically prompts when locked, re-locks every time the app leaves the foreground (`ON_PAUSE`), and **gracefully auto-unlocks** if the device has no registered authenticator so users aren't locked out. An "App lock (biometric)" toggle is available in `SettingsScreen.kt`; dependency `androidx.biometric:biometric:1.1.0` has been added to `libs.versions.toml` + `app/build.gradle.kts`, and the `USE_BIOMETRIC` permission has been added to the manifest.

---

## Summary Statistics

| Metric | Count |
|---|---|
| Unique source files modified (both updates) | 38 |
| Files deleted | 2 (fake PoToken system) |
| New files added | 2 (`QueueSheet.kt`, `DownloadSubtitleUseCase.kt`) |
| Bugs/security fixes (Update 1) | 13 groups + 1 fullscreen feature |
| New features (Update 2) | 3 |

## Remaining Notes (suggestions for future work)

- Genuine PoToken BotGuard integration (bgutil-style) is not yet implemented — requests are currently sent **without** a token, which is the safest/correct behavior at this time.
- `workers/DownloadWorker.kt` (774 lines) is dead code retained for reference; it is safe to delete in a future cleanup.
- `SimpleCache` is still lazily built on the playback looper (`di/PlayerModule.kt`) — minor jank on the first playback on some devices.
- The ExoPlayer instance is intentionally a process-lifetime singleton; `release()` is never called.