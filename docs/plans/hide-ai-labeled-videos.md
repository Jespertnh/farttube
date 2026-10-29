# Plan: Hide videos YouTube labels as AI / "Altered or synthetic content"

Status: **Draft / proposal** — nothing implemented yet.
Branch: `feature/hide-ai-labeled-videos`

## Goal

Add a user option under **Settings → General → Hide unwanted content** to keep
videos that YouTube has labeled as AI-generated ("Altered or synthetic
content") out of the browse feeds (Home, Subscriptions, Search, Trending,
Channel). It should work the same way as the existing *Hide shorts*, *Hide
mixes* and *Hide upcoming* toggles.

## Background: how YouTube exposes the label

- Creators turn on the disclosure in YouTube Studio (Details → *Altered or
  synthetic content*). YouTube can also add the label itself.
- On long-form videos the label is shown in the **description panel**, in the
  *"How this content was made"* section. For sensitive topics a more prominent
  label is also put **on the player**. On Shorts the label is shown in the
  Shorts feed.
- **Main risk:** the regular browse/feed responses that SmartTube parses
  (`tileRenderer`, `videoRenderer`, `lockupViewModel`) do **not** appear to
  carry the label. On long-form videos it seems to exist only in the
  watch-page (`/next`) response. Phase 0 has to confirm this before anything
  is built, because it decides how expensive detection is.

## How the existing hide filters work (what we copy)

| Layer | File | Role |
|---|---|---|
| Flag storage | `MediaServiceCore/.../youtubeapi/service/internal/MediaServiceData.java` | Bit-flag `mHiddenContent` (`CONTENT_SHORTS_HOME = 1 << 3`, …, last used bit is `1 << 14`) |
| Per-group options | `MediaServiceCore/.../common/models/impl/mediagroup/MediaGroupOptions.kt` | Maps group type + flags → `removeShorts`, `removeLive`, … |
| Item filter | `MediaServiceCore/.../common/models/impl/mediagroup/BaseMediaGroup.kt` | `filter` lambda drops `ItemWrapper`s while the group is built |
| Item signals | `MediaServiceCore/.../common/models/gen/CommonHelper.kt` | `isShorts()`, `isLive()`, `isUpcoming()` from badge and overlay styles |
| Legacy filter | `MediaServiceCore/.../googlecommon/common/helpers/YouTubeHelper.java` → `filterIfNeeded()` | Second pass for v1/legacy groups |
| Watch page | `MediaServiceCore/.../next/v2/gen/WatchNextHelper.kt` → `getDescriptionPanel()` | Already finds the description engagement panel |
| Settings UI | `common/.../presenters/settings/GeneralSettingsPresenter.java` → `appendHideContent()` | Checkbox list |
| Strings | `common/src/main/res/values/strings.xml` | `hide_shorts_*`, `hide_mixes`, … |

## Phase 0 — Find out where the signal is (spike, roughly 1 day)

1. Pick about 10 known AI-labeled videos: a mix of long-form, Shorts, and
   sensitive-topic videos that carry the player label.
2. Capture raw InnerTube responses for them with the clients the app uses
   (`AppClient.TV`, `TV_LEGACY`, `WEB`):
   - `browse` (Home, Subscriptions, Channel uploads) where they appear
   - `search` for their titles
   - `next` (watch page)
   - `player`
3. Search the JSON for `howThisWasMade`, `Altered or synthetic`, `synthetic`,
   `AI_GENERATED`, and new badge, overlay or `attributedText` styles.
4. Save the trimmed responses as test fixtures under
   `MediaServiceCore/youtubeapi/src/test/resources/` (the existing `PanelApiTest`
   shows the pattern).

**Decision gate:**
- **A.** The label appears in feed items → use **Feed-level detection** only (cheap).
- **B.** It appears only in `/next` or `/player` → use **watch-page detection
  plus a cache** (below), and tell users in the UI that the filter is
  "best effort".

## Phase 1 — Detection in MediaServiceCore

### 1a. Feed-level signal (if Phase 0 finds one)
- In `CommonHelper.kt`, add `isAiLabeled()` for `VideoItem`, `TileItem`,
  `LockupItem`, plus `ItemWrapper.isAiLabeled()` that delegates to them, in the
  same style as `isShorts()`. Add any new JSON fields to the generated model
  classes (`CommonItems.kt` and friends).
- Expose it on the public model: add `boolean isAiLabeled()` to
  `mediaserviceinterfaces/.../data/MediaItem.java`, and implement it in
  `BaseMediaItem.kt` / `MediaItemImpl.kt` (default `false`).

### 1b. Watch-page signal (needed in case B, and useful in case A too)
- Add the `howThisWasMade…` view-model fields to `WatchNextItems.kt`.
- In `WatchNextHelper.kt`, add `WatchNextResult.isAiLabeled()` that reads
  `getDescriptionPanel()` (and the player overlay label if Phase 0 finds one).
- Add `boolean isAiLabeled()` to `MediaItemMetadata` and implement it.

### 1c. Known-AI cache (case B)
- New `AiLabelCache` that stores labeled video IDs. It is persisted through
  `MediaServiceCache` / `GlobalPreferences` the same way other cached data is,
  and is bounded as an LRU of about 5,000 IDs.
- It gets filled from:
  1. every `/next` response the app already fetches when the user opens a
     video (this costs no extra requests);
  2. optional **background probing** (off by default): for the first N items of a
     newly loaded feed row whose IDs are not cached, run throttled `/next`
     lookups (for example, at most 2 at a time with a delay between them), and
     remove items from the row as their results come back. Results are
     cached, including "not labeled" results with a TTL.
- Risks of probing: more requests, a higher chance of rate limiting or
  bot-checks, and more battery use on weak TV boxes. That is why it needs its
  own toggle and should stay off by default.

## Phase 2 — Filtering

1. **Flags** in `MediaServiceData.java`:
   ```java
   public static final int CONTENT_AI_HOME          = 1 << 15;
   public static final int CONTENT_AI_SUBSCRIPTIONS = 1 << 16;
   public static final int CONTENT_AI_SEARCH        = 1 << 17;
   public static final int CONTENT_AI_TRENDING      = 1 << 18;
   public static final int CONTENT_AI_CHANNEL       = 1 << 19;
   public static final int CONTENT_AI_ALL = CONTENT_AI_HOME | CONTENT_AI_SUBSCRIPTIONS
           | CONTENT_AI_SEARCH | CONTENT_AI_TRENDING | CONTENT_AI_CHANNEL;
   ```
   They default to **off**: leave them out of the default value in
   `parseInt(split, 18, …)`. They still fit in the existing `int`, so the
   persisted format does not change.
2. **`MediaGroupOptions.kt`**: add `removeAiLabeled`, worked out per
   `groupType` the same way `removeShorts` is.
3. **`BaseMediaGroup.kt`**: add
   `(options.removeAiLabeled && (it.isAiLabeled() || AiLabelCache.contains(it.getVideoId())))`
   to `filter`.
4. **`YouTubeHelper.filterIfNeeded()`**: add the same check for the legacy
   path.
5. **Async removal (only for background probing):** when a probe confirms a
   video is labeled after its row has already been shown, remove that card from
   the adapter in the app (`common`), via the presenter that owns the row
   (`BrowsePresenter` / `VideoGroup` update path). Do not reload the whole
   row.
6. **Empty rows:** a row can end up empty after filtering. Reuse the existing
   "group could be empty after filtering" handling, which loads the next page.
   Check that this also works for Subscriptions and Search.

## Phase 3 — Settings UI (SmartTube `common` module)

In `GeneralSettingsPresenter.appendHideContent()`:

- **Hide AI-labeled videos everywhere** → `CONTENT_AI_ALL`, placed next to
  *Hide shorts everywhere*.
- Per-section toggles for Home, Subscriptions, Search, Trending and Channel.
- If case B applies: a separate toggle, **Check feed videos for AI labels in
  the background (more network use)**, which enables probing.

Add the new English strings to `common/src/main/res/values/strings.xml` only.
The other locales are synced through Crowdin (`crowdin.yml`).

## Phase 4 — Tests and verification

- **Unit tests** in `MediaServiceCore/youtubeapi/src/test`:
  - `isAiLabeled()` returns `true` for the labeled fixtures and `false` for
    normal and Shorts fixtures;
  - `MediaGroupOptions.create()` sets `removeAiLabeled` for the right group
    types;
  - `BaseMediaGroup` drops labeled items and keeps the others;
  - `AiLabelCache` LRU eviction, persistence round-trip, and TTL.
- **App checks:** run `./gradlew :common:testStstableDebugUnitTest` (or the
  matching flavor) and lint, then build `smarttubetv`.
- **Manual test** on an Android TV emulator or device: turn on the toggle, open
  a known AI video (this fills the cache), go back to Home and Subscriptions,
  and confirm the video is gone. Also try with probing on and off.

## Open questions / decisions for the maintainer

1. **Submodule ownership:** almost all of the detection and filtering code is
   in `MediaServiceCore`, a submodule that points to
   `github.com/yuliskov/MediaServiceCore`. This repo cannot push there. Options:
   - **(Recommended)** Fork it to `Jespertnh/MediaServiceCore`, change the URL
     in `.gitmodules` on this branch, and bump the submodule pointer there.
   - Send the change upstream to `yuliskov/MediaServiceCore` and wait for it to
     be merged.
   - Do the filtering only in the app (`common`), using the watch-page cache
     only. This needs no submodule changes, but it is weaker because there is
     no access to feed-level JSON.
2. Should probing ship at all, or should v1 rely only on feed-level labels plus
   videos the user has opened?
3. Should the Shorts shelf and the Shorts player be filtered too? That would
   need the Shorts-feed label that Phase 0 is meant to find.
4. Should there also be an option to show an **"AI" badge** on cards instead
   of hiding them? It would use the same detection and is cheap to add later.

## Rough sizing

| Phase | Effort |
|---|---|
| 0 — signal discovery | ~1 day |
| 1 — detection (+ cache) | 1–3 days, depending on case A or B |
| 2 — filtering | ~1 day |
| 3 — settings UI | ~0.5 day |
| 4 — tests and manual QA | ~1 day |
