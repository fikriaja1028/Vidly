# Changelog - Vidly (PlayTube Modified Version)

This document summarizes all updates applied on top of the original `PlayTube-main` source code.

---

## [1.0.0] - Update 1: Initial Bug Fixes + Fullscreen Button

### Security & Privacy

1. **Fake PoToken system removed entirely.**  
   Previously, every stream request included a fake token that caused recurring `403` playback errors. Requests are now sent without a token, which is the correct behavior without a genuine BotGuard implementation.

2. **Database updates no longer wipe user data.**  
   When the app updates to a newer version, your watch history and other data stay safe-they are no longer automatically erased.

3. **Cloud backup is locked down.**  
   The database and app preferences are now **excluded** from Google Drive backups and device transfers. Watch history and interest profiles remain on your device only.

4. **MediaSession is locked to this app.**  
   Other apps can no longer connect and take control of the video player from the outside.

### Critical Fixes (P0)

5. **Endless recovery loop halted.**  
   If a network error occurs (other than 403), the app will now retry with a delay and a maximum of 3 attempts-no more infinite looping.

6. **Chunked downloads now require HTTP 206.**  
   If a server ignores the `Range` request and sends a `200 OK` (full file), the app rejects it. This prevents downloaded files from becoming corrupted.

7. **Resume download accuracy improved.**  
   Resuming interrupted downloads is now much more accurate: the last position is re-read from the database, checkpoints are saved every 512 KB, and progress never exceeds 100%.

8. **Backup restore no longer "falsely succeeds".**  
   The app now scans the entire backup zip file, ensures `backup.json` actually exists, and limits decompression to a maximum of 256 MB to protect against zip-bomb attacks.

9. **Stale state is cleaned up during auto-advance.**  
   When autoplay moves to the next video, SponsorBlock data from the previous video is properly discarded so it doesn't mix with the new one.

### Stability & Other Quality Fixes

10. **Thread-safety for downloads.**  
    Download data accessed from multiple places is now safe from concurrency crashes.

11. **YouTube Takeout import fixed.**  
    CSV files with quoted fields are now parsed correctly. Timestamps for older Android versions (below 8.0) are also parsed properly, so your history order isn't messed up.

12. **Smart proxy handling.**  
    The app no longer forces a proxy for local addresses (like home IPs or `localhost`), so accessing devices on your LAN isn't blocked.

13. **Various minor fixes.**  
    - Unknown enums in the database no longer crash the app.  
    - Video ID extraction from URLs is now more precise.  
    - Version update checks from GitHub don't crash if fields are empty.  
    - User interest calculation now runs every 24 hours (previously it almost never ran).  
    - Download filenames are sanitized before saving to MediaStore.  
    - A single tap on the player now only triggers if you lift your finger without dragging (dragging for volume/brightness no longer opens/closes the control overlay).

### New Features:
   - YouTube‑style fullscreen button – bottom‑right corner, immersive landscape mode.
   - Playback Queue Management – explicit queue, drag‑to‑reorder, shuffle, repeat modes.
   - Audio‑only downloads – best available audio track saved as .m4a/.opus in the Music folder.
   - Subtitle downloads – download WebVTT subtitles per language.
   - Private (Incognito) mode – browse without leaving any trace; all history from the session is purged on exit.
   - Biometric app lock – full‑screen lock with fingerprint/face unlock (falls back gracefully if no authenticator is registered).

---

## [1.0.1] - Update 2: New Features, Loops, Shorts & Quality Fixes

This single update bundles **2 major new features** along with comprehensive fixes for broken characters, UI scroll in fullscreen, download categorization, Shorts personalization, and the existing subtitle/720p/quality issues.

---

### New Features

#### A. Loop Controls (Video & Playlist)
- **Loop Video** - You can now loop the current video. When the video ends, it restarts automatically from the beginning without reloading. Turn it on in **Settings → Playback Loops → Loop video**, or quickly from the **player Settings sheet** (gear icon → Loop video). It uses the player's native repeat mode, so it's smooth and doesn't cause extra buffering. A small Snackbar gives feedback when you toggle it.
- **Loop Playlist** - When playing a playlist, you can now loop the whole playlist. After the last video finishes, it automatically goes back to the first video. Works together with shuffle - if shuffle is on, the next video is picked randomly from the unplayed ones. For single-video playlists, the same video simply replays. Even if autoplay is off, a looping playlist will still continue - perfect for keeping a music or study playlist running. Toggle in **Settings → Playback Loops → Loop playlist** or in the **player Settings sheet**.

#### B. YouTube Shorts
- **New Shorts tab** in the bottom navigation bar (between Home and Subscriptions). Tap it to open the Shorts feed.
- **Vertical swipe feed** - swipe up or down to go to the next Short, just like on YouTube/TikTok. Videos auto-play when they become visible and pause when you swipe away.
- **Full-screen vertical player** - videos are displayed full-screen in portrait, cropped to fill (zoom) so they look native to Shorts. A subtle gradient makes the text readable.
- **Tap to play/pause** - tap the video to pause, tap again to resume. A quick play/pause icon flashes in the center.
- **Info overlay** - at the bottom left you see the channel avatar, channel name, video title, and upload date. At the bottom right you have action buttons.
- **Actions** - Like (with liked state), Share (opens the full player), and More (opens the full player for comments/quality etc.). Liking is saved to your favorites and shows a Snackbar.
- **Personalized feed** - the Shorts feed now uses **user interests** and **search-based fallbacks** to deliver more relevant and reliable content.
- **Looping** - each Short loops automatically (like real Shorts), so you can watch it again without swiping.
- **Deep links** for `youtube.com/shorts/...` now open correctly in the Shorts player. From a Short you can tap the channel name/avatar to go to the channel page, or tap Share/More to open the regular player for that video.

#### C. Categorized Downloads
- The Downloads page is now neatly organized into **separate "Video" and "Audio" sections**, making it easier to browse and manage your downloaded files.
---

### Fixes (Quality, Subtitles, UI & Organization)

#### 1. Garbled Characters Fixed Everywhere
- All occurrences of garbled text (like `â€¢`, `Ã¢â‚¬Â¢`) are now replaced with proper characters (`•`, `…`, `-`) across **video metadata, library entries, player descriptions, and subtitles**.
- Downloaded subtitles are saved in clean UTF-8 format (without BOM), so they can be opened in any text editor.

#### 2. Downloading 720p Now Gives You True 720p
- When you select `720p` for download, the app now actually fetches the video at 720p resolution.
- Previously, on some videos, the app silently downloaded `360p` (or lower) without telling you, resulting in blurry files. This no longer happens.

#### 3. Video Quality No Longer Fluctuates Randomly (Auto Quality Stable)
- The Auto quality mode is now much more stable. Resolution will no longer jump up and down (e.g., 720p ↔ 480p) every few seconds.
- Quality downgrades now only happen when **two conditions** are met simultaneously: the video buffer is almost empty **and** the internet speed is very low.
- There is also a 15-second cooldown to prevent rapid back-and-forth switching (flapping).

#### 4. Fullscreen UI Scroll Fix
- Selection sheets (Quality, Subtitles, Settings, etc.) are now **fully scrollable in fullscreen/landscape mode**, ensuring all options are reachable even when the screen is rotated.

---

## [1.0.2] - Update 3: New Features, Shorts & Quality Fixes

### New Features:
- **Hold for 2x Speed**: Hold the screen to speed up video playback to 2x. Available in both Shorts and the main Player.
- **Smart Speed Revert**: Video speed automatically returns to the user's original setting (e.g., 0.5x or 1.25x) after lifting their finger from the screen, instead of resetting to 1x.
- **Smart Navigation**:
    - Tapping an already active tab icon will trigger a **Refresh/Reload** of the content.
    - Tapping a tab icon while on a sub-page will perform a **Pop to Root** (return to the tab's main page).
- **Immersive Shorts UI**: Hides the Top App Bar (Vidly logo, Search, Incognito) specifically on the Shorts page for a cleaner, more immersive experience.

### Fixed
- **Background Playback Shorts**: Fixed a bug where Shorts videos continued playing after navigating away to another page. Added lifecycle management to automatically pause the player when navigating out.
- **Audio Conflict**: Added an auto-pause function for the main player when entering the Shorts page to prevent audio clashes.
- **Shorts Algorithm**: Fixed the search filter and Shorts feed to properly display Portrait videos (duration ≤ 60 seconds) instead of long Landscape videos.
- **UI Overlap**: Fixed the Shorts UI layout (channel name, action buttons) that was previously obscured by the Bottom Navigation Bar.

### Changed
- Optimized gesture detector to better distinguish between tap, double tap, and long press with improved accuracy and no conflicts.
- Cleaned up warnings related to `UnstableApi` in the Media3 library.
