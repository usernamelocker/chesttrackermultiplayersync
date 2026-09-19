# Client overlay (retired)

Early in the project the sync client was developed as a patch copied into a local
QMSync checkout. That flow is obsolete: **`mod/` is the full buildable source and
the source of truth** (see [`mod/CMSync.md`](../mod/CMSYNC.md)).

The patch copies and per-version porting notes that used to live here
(`src/`, `docs/APPLY.md`, `docs/PORTING-26x.md`, `scripts/apply-overlay.ps1`)
were removed — they were stale and misleading. Git history still has them if
you ever need archaeology.
