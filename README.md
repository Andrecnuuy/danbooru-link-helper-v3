# Danbooru Link Helper

Android app that turns a Twitter/X or Pixiv artist profile into a clean list of post links for Danbooru extraction:

1. Scans the artist's posts, images and videos (no downloading, links only).
2. Removes links that already exist on Danbooru (looked up through the artist tag, using your API key).
3. Removes links that are already in your own Danbooru media assets (stored locally, refreshed on demand).
4. Gives you the remaining links to copy.

## Build the APK

**Option A, GitHub Actions (no setup on your side)**
1. Create an empty GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the repository's **Actions** tab. The *Build APK* workflow runs on every push (or press *Run workflow*).
3. When it finishes, open the run and download the **DanbooruLinkHelper-apk** artifact. Unzip it and install the `.apk` on your phone (allow "install unknown apps" when asked).

**Option B, Android Studio**
Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then *Build > Build APK(s)*, or run `./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`.

Every build is signed with the same bundled key, so a new APK installs over the old one without losing your data.

## First run

1. **Danbooru API card**: enter your username and API key, press *Test connection*.
2. **Cookies** (same card): import a `cookies.txt` (see below). X scans need it; for Pixiv it unlocks login-only and R-18 works.
3. **Media asset check**: press *Fetch new* once. The first run walks all your uploads; later runs stop where the last one stopped.
4. **Artist link**: paste a profile link, *Scan posts*.
5. **Artist post check**: *Fetch artist posts*, then *Purge*. This also lists the artist's other X and Pixiv accounts (see *Several sources* below).
6. **Media asset check**: *Check*, then *Purge*.
7. **Links**: *Copy* (or *Save .txt*).
8. **Similar images** tab: optional, finds the same picture across accounts (see below).

*Clear / new artist* resets steps 1 to 8 and keeps your key, cookies and stored media asset links.

## cookies.txt

Same Netscape format as yt-dlp and gallery-dl. Export it from a browser where you are logged in to x.com and/or pixiv.net (for example with a "Get cookies.txt" extension), put the file on the phone, then press *Import cookies.txt* (or *Paste* the text).

- Each import **replaces** the previous cookies. Import again whenever they expire; the card shows when the login cookies expire.
- Only x.com, twitter.com and pixiv.net cookies are kept. The rest of the file is discarded.
- A file with none of those sites is rejected and your old cookies stay.
- Cookies are stored in the app's private storage (not included in backups). `auth_token` and `PHPSESSID` give full access to the account, so treat the file like a password.

## How each scan works

No browser window is involved anywhere. Both sites are asked directly over HTTPS with your imported cookies.

- **X** (needs a cookies.txt with `auth_token` and `ct0`): the app asks X the same way the X website does when you open a profile's *Media* tab, the way gallery-dl does. It pages through the whole media timeline, 100 posts per request. Photos, videos and GIFs are all included. X changes these internal calls from time to time, so nothing is hard-coded: the app looks up X's current query IDs by itself (and remembers them), adds feature flags when X asks for them, waits when X rate-limits, and if a scan is stopped or fails halfway it keeps what it already found.
  - If X answers **404**, X changed an ID or started demanding its `x-client-transaction-id` header (not implemented). The Stats log shows X's exact answer. Settings has two optional boxes for entering the query IDs by hand.
  - X shows a protected account's posts only if the logged-in account follows it.
- **Pixiv**: one request to Pixiv's work list for the user, sent with your Pixiv cookies. Pixiv hides R-18 works from sessions it doesn't recognise as logged in, and from logged-in accounts that have R-18 display turned off in the Pixiv settings. If a scan finds fewer works than the profile page shows in your browser, check that the cookies come from the account you are logged in with there, and that R-18 display is on.
- **Include**: *Images + video*, *Images only* or *Video only*. For X the filter applies while reading the timeline. For Pixiv, "video" means animated works (ugoira); to tell them apart the app asks Pixiv for work details (48 works per request), and if that fails it keeps everything and says so.
- **Matching**: X posts match by status id, and also by image file key, so images Danbooru stored under an image URL still match. Pixiv matches by artwork id, whether Danbooru has the artwork page or the `i.pximg.net` file as the source.

## Working with the links

- **Tap** a link: a menu with *Preview*, *Open link* and *Delete this link*.
- **Preview** shows the picture and the post date (and how many pictures are in the post). For a video it shows the poster with a *Play video* button, so the video is only downloaded when you ask. For a Pixiv animation it shows the still image. If nothing can be loaded you still get the date and the *Open link* / *Delete* buttons.
- **Long-press** a link to start selecting. In selection mode a tap ticks or unticks, and the bottom bar has *Delete*, *Between* (tick two links, press it, and every link in between is ticked), *All* and *Invert*. The back button leaves selection mode.
- *Undo* (next to the status line under the Links card) brings back the last links you deleted by hand.
- **Grid**: the *Grid* chip in the Links card shows 3 columns of thumbnails with the post date, a ▶ mark on videos and a ×N mark on multi-picture posts, which makes unwanted posts easy to spot. Thumbnails are loaded only for what is on screen, a few at a time, and kept on disk (up to 80 MB). *Small thumbnails* (on by default) loads the tiniest size: about 150 px from X, 250 px from Pixiv. Pixiv thumbnails are fetched 48 at a time rather than one by one.

## Several sources

The link you paste is the first *source*. The artist usually has more accounts, and the Danbooru artist's URL list knows them.

- **Finding them**: *Fetch artist posts* (or *Find other platforms* in the same card) reads the artist's URL list on Danbooru and lists every other X and Pixiv account in it. Entries marked inactive with a `-` (deleted, banned or dead accounts) are ignored, and so is everything that is not X or Pixiv (Fanbox and so on). X accounts given as `x.com/i/user/<number>` are understood too (you can paste one as the artist link as well).
- **Adding them**: tap *Add* on one account, or *Add all*. Each one is scanned like the pasted link (one after the other) and shows up as a chip in the Links card: `1 · X @handle (120)`. If two entries turn out to be the same X account (a handle and its numeric id), the second is skipped.
- **Pixiv "stacc" links** (`pixiv.net/stacc/<name>`) carry no user id. The app tries to turn them into one by asking Pixiv where the page leads; if that does not work it says so, and you can paste the account's `pixiv.net/users/<number>` link instead.
- **Every source keeps its own state.** Delete links in source 1, switch to source 2 and back, and the deletions are still there (also across app restarts). *Purge* in the Danbooru and media asset cards works on all sources at once. *Undo* brings back the last deletion, wherever it was made.
- **Copy / save**: with more than one source, two chips choose between *This source* and *All sources* (every link once).
- *Remove this source* takes the shown source out of the view (add it again from the artist check to rescan).

## Similar images

The **Similar images** tab (under the media asset card) looks for pictures that are the same artwork, on one site or across sites, among all sources.

- **How**: for every picture it downloads a small copy (about 60 KB: 680 px from X, 540 px from Pixiv, not the full image), shrinks it to a 32x32 gray fingerprint plus 4x4 average colours, and compares the fingerprints. Two pictures must have the same shape, similar colours, and a matching light/dark layout, both overall and in the quarters of the picture (one quarter may differ, so a signature in a corner does not hide a copy). The shown percentage is the correlation of the layouts. Fingerprints are remembered on the phone, so a second run only downloads new pictures. Before a run the tab tells you how many pictures it will download and about how many MB that is.
- **Match level**: 80% (default), 85%, 90% or 95%. If you get too many false pairs, raise it. A pair of heavily edited versions may need a lower level.
- **Side by side**: each pair shows the two pictures with their real pixel sizes ("Left is 3000×2000, right is 1200×800"), the site and name, and an *Open post* button. The picture with more pixels is always on the left; when the sizes are equal Pixiv goes left of Twitter, then the earlier source.
- **Deleting**: tap a picture to mark it (tap again to unmark), or use *Mark all left* / *Mark all right*, then *Delete marked*. Deleting removes the whole post from the Links (a post with several pictures goes as a whole), so it shows in the Links tab at once and *Undo* works. If the marks would leave a whole group of look-alikes with no copy at all, the app asks first. Pairs whose posts you delete in the Links tab disappear from this tab too.
- **Limits**: only the first page of a Pixiv work is compared. Videos are not compared. Pictures that were cropped, mirrored, or recoloured will not match. The same artwork in a different pose or a sketch/final pair is not a match.

## When something misbehaves

Open **Stats**. The activity log at the bottom shows each Danbooru search, HTTP errors and retries. The Danbooru calls are all in `Danbooru.kt`; which records count as "my media assets" and where their links come from is isolated in `MediaAssetFetcher.kt` (`AssetApi` at the top).

## Tests

`core-test/run.sh` compiles the pure-Kotlin parts (`Core.kt`, `Cookies.kt`, `Twitter.kt`, `PixivScanner.kt`, `Previews.kt`, `Session.kt`, `ArtistUrls.kt`, `Similarity.kt`) with the Kotlin compiler bundled in Gradle and runs the link parsing, artist-URL classification, matching, cookie, X response, Pixiv response, session-saving and picture-similarity checks. It needs no Android SDK. The X checks use sample responses written from memory of X's format, not live ones, and the Danbooru artist-URL reader is written against the response shapes Danbooru is known to use, not a live answer.
