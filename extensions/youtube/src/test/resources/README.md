# Catalog fixtures

These are reduced public, signed-out playlist responses captured during development.
They preserve the initial browse wrappers, playlist row order (20 then 32), text
runs, durations and continuation response shape. Unused thumbnails, menus,
tracking parameters and service metadata were removed; visitor and continuation
values are inert placeholders. CatalogTest also constructs unavailable, duplicate,
recommendation, empty and malformed responses independently.

The discovery fixtures retain a public player metadata identity/title and the
captured Android compact-playlist search wrappers, playlist IDs and creator
bylines. Description prose and tracking/service metadata are omitted. Synthetic
tests cover description links and rejected/ambiguous candidates separately.

Taskmaster fixtures were captured on 2026-09-13 for ASzE5CuYNks and the
"Taskmaster Season 22" search. The four browse pages retain the complete official
Full Episodes catalog, including the target video near its end. They reproduce
season ranking, the misleading Full Episode suffix and continuation membership
verification without network access. Continuation tokens are inert placeholders.

The additional Taskmaster channel fixtures preserve the WEB channel identity,
server-provided playlist-tab endpoint and lockupViewModel playlist rows captured
on 2026-09-13. The season catalog uses YouTube's Show unavailable videos option:
two public episodes followed by one private entry. Only the informational
playback banner may be skipped; responses that still hide entries are rejected.
Channel continuation tokens are inert placeholders.

The China browse fixtures capture the 27-entry Real Life in China playlist
(PLAfLmKFlXztwP4VNiotH2npwIwj7R9vQO) on 2026-09-13. They retain source order,
25 playable entries, two private slots and the original continuation wrapper.
The target ccXVgWAW19w is first in playlist order; I_hTOyE41Ic becomes the first
playable entry when reversed. Service metadata is omitted and the continuation
is an inert placeholder.

The Taskmaster season fixture also retains the public videoInfo view counts and
relative ages captured in that response. Private entries have no statistics.

The China fixtures also retain their captured public videoInfo ages and view
counts to validate automatic direction inference without new network requests.
