# NS APIv3 `settings` collection — design notes

Brainstorming for what AAPS could store in the Nightscout APIv3 `settings` collection.
Transport layer is in place (functional SDK + no-op plugin stubs). Payload, identifier
convention, and triggering rules are still open.

## Implementation status

| Component                                                                              | State             |
|----------------------------------------------------------------------------------------|-------------------|
| APIv3 SDK methods (get / search / history / create / patch / delete)                   | ✅ done            |
| Plugin no-op stubs (`LoadSettingsWorker`, `dbOperationSettings`, WS branch)            | ✅ done            |
| `ConfigExportImport` interface refactor (preferences, syncedKeys, reloadInternalState) | ✅ done (step 1)   |
| Plugin adoption of declarative `syncedKeys`                                            | ✅ done (step 1)   |
| `QuickWizard` as `ConfigExportImport`                                                  | ✅ done (step 1.5) |
| Unified `put`/`store`/`observeChange` over `NonPreferenceKey`                          | ✅ done (step 2)   |
| `RunningConfigurationImpl` schema-driven build/apply                                   | ⏳ next (step 3)   |
| `RunningConfigurationPublisher` (master writer)                                        | ⏳ phase 1         |
| Replace `LoadSettingsWorker` no-op with real loader                                    | ⏳ phase 1         |
| Replace WS `settings` no-op with apply path                                            | ⏳ phase 1         |

## What the collection actually is

- Generic NS APIv3 collection, identified per-app: each "app" (xdrip, AAPS, etc.) keeps
  its own document keyed by an `identifier`. Schema is **completely free-form** JSON
  (just `DocumentBase` envelope + arbitrary fields).
- Authorization: SEARCH and HISTORY require `api:settings:admin`. READ-by-id, CREATE,
  PATCH, UPDATE, DELETE follow the normal `:read`/`:create`/`:update`/`:delete` scopes.
  Users will be instructed to issue an admin token, so this is not a constraint for v1.
- Sync semantics: only `srvModified` is honored — no `created_at` / `date` fallback.
- Dedup: only on `identifier`. Two POSTs with the same identifier → second one becomes
  a replace. NS docs recommend PATCH for partial updates.
- Auto-prune: if the server enables `API3_AUTOPRUNE_SETTINGS`, docs expire by
  `srvCreated`. PATCH does NOT refresh `srvCreated`. Long-lived docs can disappear
  unless we periodically re-POST. Quarterly self-refresh required for any long-lived
  doc.

## Scope decided (2026-05-04 discussion)

Confirmed direction, in shipping order:

1. **Phase 1 — RunningConfiguration migration.** Move what's currently smuggled
   inside devicestatus (master→client config exchange) onto a dedicated settings
   doc. One writer (master), N readers (clients). No new user-facing surface;
   replaces an objectively broken channel.
2. **Phase 2 — Active scene state + end-from-client.** Master writes scene state
   to the same doc; client can PATCH a single `scene.endRequested` flag; master
   observes and runs deactivate. First multi-writer surface, but on a single
   boolean — small enough to validate the model.
3. **Phase 3 — Shared editable configs** (scenes, automations, TT presets).
   Real multi-writer with optimistic concurrency + audit trail. **Insulin
   configurations are explicitly excluded from this phase.**
4. **Phase 4 — Backup / restore.** Independent of phases 1-3. Per-install
   identifier, distinct doc.
5. **Phase 5 (optional, gated)** — Insulin configuration editing from client,
   only if a trust allowlist is added (see §"Trust model").

Each phase is independently shippable and the channel design accommodates all
of them without rework.

## Why phase 1 is the right starting point (revised analysis)

Initial proposal was to start with backup. Reconsidered after talking through
the actual pain points:

| Concern                     | devicestatus today                              | settings doc (proposed)            |
|-----------------------------|-------------------------------------------------|------------------------------------|
| When updates fire           | every loop iteration                            | only on actual change              |
| Latency                     | bounded by loop interval + upload backoff       | bounded by WS push                 |
| Stale-read recovery         | client must keep last devicestatus, diff fields | one doc, one srvModified           |
| Behavior when master pauses | RunningConfiguration freezes with last status   | unaffected — config still readable |
| Conceptual fit              | live telemetry stream                           | durable per-app config             |

RunningConfiguration is durable configuration, not telemetry. It only ended up
on devicestatus because that was the only channel available between master and
clients. Migrating it is plumbing cleanup, not a feature.

## Identifier strategy

Two identifier classes, kept distinct:

- `aaps-cfg-<masterInstallId>` — shared config doc. Master writes everything;
  trusted clients PATCH only specific sections (`scene.endRequested`, `shared.*`).
  One per logical AAPS install (the looping device defines the install id).
- `aaps-backup-<installId>` — per-install backup. Only the install that owns
  the id reads/writes this. No contention.

Different docs, different writers, no overlap.

`<masterInstallId>` is a UUID generated on first launch of the master AAPS and
persisted in prefs. Clients are configured (manually or via QR / pairing) to
follow a specific master id. This is the same model the wear pairing already
uses.

## Doc structure (proposed)

```
{
  schemaVersion: 1,
  runningConfig: {                  // master writes only
    pumpType, maxBolus, maxBasal, units, plugins: {...}, ...
  },
  scene: {                           // master writes; client PATCHes endRequested only
    activeScene: "Exercise" | null,
    endsAt: <epoch> | null,
    endRequested: false,
    endRequestedBy: <installId> | null
  },
  shared: {                          // multi-writer; phase 3
    scenes: [...],
    automations: [...],
    ttPresets: [...]
  },
  trustedClients: [<installId>, ...] // master writes only; gates risky edits
}
```

Stratifying by writer trust matters because the conflict story is fundamentally
different per section:

- `runningConfig`: single writer, no conflicts possible.
- `scene`: master writes the state; client toggles one flag. Conflicts impossible
  unless two clients both end at the same instant — accepted as last-write-wins.
- `shared.*`: real multi-writer. Optimistic concurrency required.

Always include `schemaVersion` so we can evolve the shape later.

## Concurrency for multi-writer sections (phase 3)

NS APIv3 has no server-side If-Match or version-checked PATCH. App-level
optimistic concurrency:

1. Client reads doc, captures `srvModified = X`.
2. User edits a scene/automation/preset.
3. Client constructs PATCH body that includes `__expectedSrvModified: X` plus
   the actual changes.
4. Before PATCHing, client re-reads the latest `srvModified`. If it has advanced,
   show "this was changed on another device — reload?" rather than silently
   overwriting.
5. After successful PATCH, capture the new `srvModified` from the response.

This isn't airtight (TOCTOU window between re-read and PATCH) but is good
enough given the low edit frequency.

Audit trail: every cross-device edit also writes a `Note` treatment with
`{enteredBy: "AAPSClient/<installId>", notes: "Edited scene 'Exercise'"}`
so the patient sees what was changed and by whom.

## Trust model

Most clients should be allowed to read everything in `runningConfig` and `scene`.
Only some should be allowed to edit `shared.*`. Insulin configurations, if ever
opened up, need an even stricter gate.

Lightweight model: `trustedClients` list inside the doc itself, master-managed.

- Master device generates a per-install id on first launch.
- Clients pair with master (existing wear pairing flow has this) and the master
  adds the client's install id to `trustedClients`.
- Server-side this isn't enforced (any admin token can PATCH the doc); the gate
  is enforced *in-app*. Untrusted clients refuse to expose edit UI for `shared.*`.
  This is "trusting the client app", not strong access control — but the threat
  model is "wrong family member opens AAPSClient", not adversarial.

For phase 5 (insulin config editing), the gate would be a separate `criticalConfigClients`
list, opted into per-client by an explicit confirmation on the master phone.

## Phase-by-phase scope

### Phase 1 — RunningConfiguration migration

- Fields currently smuggled into devicestatus's configuration block (see Round 5
  enumeration): `insulin`, `insulinConfiguration`, `aps`, `apsConfiguration`,
  `sensitivity`, `sensitivityConfiguration`, `smoothing`, `safetyConfiguration`,
  `pump`, `version`, plus a nested `overviewConfiguration` (units, all warning
  thresholds, `QuickWizard`, `TempTargetPresets`, `AutosensUsedOnMainPhone`).
- Migrate them into `runningConfig` section of the new doc.
- Master writes via PATCH on every preference change that touches a synced key.
- Clients subscribe to settings WS, observe PATCH, update in-memory cache.
- Backwards-compatibility: keep writing to devicestatus during a transition
  window so older clients still work.
- Replace the `LoadSettingsWorker` no-op with a real loader that pulls the doc
  on connect and seeds the cache.
- Drop the WS `settings` no-op at `NSClientV3Service.kt:251` — wire it to the
  cache update.

Quarterly re-POST job to defeat server-side autoprune.

### Phase 2 — Active scene + end-from-client

- Master writes `scene` section on activate / deactivate / duration change.
- Client UI shows active scene from the same doc the running config came from.
- "End scene" button on client: PATCH `{scene: {endRequested: true,
  endRequestedBy: <installId>}}`.
- Master's settings observer reacts to that flag, runs the existing scene
  deactivation path, clears the flag and the scene state.
- Client sees the cleared state via WS push.

Race tolerance: if the master ends the scene at the same moment a client
requests an end, both code paths converge on "scene is now null" — fine.

### Phase 3 — Shared scenes / automations / TT presets

- Add `shared` section with the three editable lists.
- Build the optimistic-concurrency PATCH wrapper.
- Add audit trail (`Note` treatments).
- Trust-gated edit UI on clients (read everywhere, edit only if `installId in
  trustedClients`).
- **Explicitly excluded:** insulin configurations. Document the carve-out.

### Phase 4 — Backup / restore

- New identifier class `aaps-backup-<installId>`.
- Reuse existing `ImportExportPrefs` serializer; filter out secrets list
  (master password, paired pump MACs, NS tokens).
- Daily upload + manual "Backup now" button.
- Restore is always user-triggered. List existing `aaps-backup-*` docs via
  SEARCH, user picks, diff against current prefs, confirm.
- Per-section opt-out during restore.

### Phase 5 (optional) — Insulin config editing

- Separate `criticalConfigClients` allowlist; explicit per-client opt-in on
  the master.
- Same PATCH path as phase 3, but additional confirmation UI on commit.

## Open issues / decisions still pending

- **Pairing flow.** Does the existing wear pairing extend to AAPSClient pairing
  for the `trustedClients` list, or do we need a fresh QR/code flow?
- **What `installId` actually is.** A UUID in prefs is fine, but it must be
  stable across app updates and not leak useful identifying info if the doc
  is exposed.
- **How to surface "currently being edited" / "last edited by X".** UI design
  TBD.
- **Conflict reload UX in phase 3.** Probably a snackbar with "Reload" and
  "Overwrite anyway" — but worth a real design pass.
- **Migration strategy for phase 1.** How long does the dual-write window need
  to be before old clients are assumed gone?

## Things deliberately not happening

- **Insulin config edits without trust allowlist.** Phase 5 gates this; without
  the gate it's not on the table.
- **Server-side enforcement of write permissions.** NS doesn't support per-section
  ACLs; trust is in-app and assumes the client app respects its own rules. Threat
  model is "wrong family member", not adversarial.
- **Live loop telemetry on settings.** IOB / COB / current temp basal / loop
  status stay on devicestatus. Settings is for things that change on user action
  or preference change, not on every loop tick.
- **Profile (basal/ISF/ICR) sync.** Stays on the `profile` collection. Settings
  doesn't duplicate.

## Discussion log

### Round 1 — initial framing (Claude proposed)

- Recommended backup-first; per-install identifier; "live state goes to
  devicestatus" as an absolute rule.
- Concrete proposals: (1) settings backup, (2) follower display prefs, (3)
  active scene broadcast (with caveat that it should probably go on devicestatus).
- Discouraged anything near insulin configs, secrets, profile data.

### Round 2 — user pushback

User outlined four desired uses, in priority order:

- (a) Backup as a cloud option, lowest priority.
- (b) Replace RunningConfiguration's piggybacking on devicestatus — "ugly,
  delayed, inconsistent." Single up-to-date settings doc, observed on client,
  triggered on master pref change.
- (c) Active scene shared so the client can end it.
- (d) Allow editing automations, scenes, insulin configurations, and TT
  presets from the client.

### Round 3 — Claude reconsidered

Conceded that (b) is the strongest case and was misclassified as "live
state" earlier. Settings IS the right channel for stable-but-mutable
configuration that's currently smuggled into devicestatus. Reordered scope so
(b) ships first.

Stayed pushing back on:

- Editing **insulin** configurations from client (safety category mismatch
  with other phase-(d) items). Carved out into phase 5 behind an explicit
  trust allowlist.
- "Last-write-wins and shrug" as the entire conflict story for phase 3.
  Required app-level optimistic concurrency + audit trail.

Added ideas:

- Stratify the doc by writer trust (`runningConfig` / `scene` /
  `shared` / `trustedClients`) rather than one flat blob.
- Two identifier classes (shared config vs per-install backup) — different
  docs prevent unrelated workloads from contending.
- Quarterly re-POST to defeat server-side autoprune.
- WS no-op stub at `NSClientV3Service.kt:251` is removed as part of phase 1.

### Round 4 — agreed scope

Phases 1→4 as listed in §"Scope decided". Phase 5 stays optional and gated.
Implementation order TBD; phase 1 is the next concrete piece of work.

### Round 5 — phase 1 design sketch

Audit of the existing piggyback channel:

- Master writes `RunningConfigurationImpl.configuration()` into the
  `configuration` field of every devicestatus
  (`plugins/aps/.../LoopPlugin.kt:993`). Throttled to *every 12th call* via
  `RunningConfigurationImpl.kt:53,61`. With a 5-minute loop that is one update
  per ~60 minutes — that's the "delayed".
- Client iterates devicestatus newest-first, applies the first non-null
  `configuration` (`plugins/sync/.../NSDeviceStatusHandler.kt:107-113`). If
  devicestatus uploads stall, config flow stops.
- Synced shape (`RunningConfigurationImpl.kt:56-87`): `insulin`,
  `insulinConfiguration`, `aps`, `apsConfiguration`, `sensitivity`,
  `sensitivityConfiguration`, `smoothing`, `safetyConfiguration`, `pump`,
  `version`, plus a nested `overviewConfiguration` containing units, all
  warning thresholds, `QuickWizard`, `TempTargetPresets`, and the
  `AutosensUsedOnMainPhone` flag. `TempTargetPresets` already round-trips
  through this — it is the canary for the new channel.

Decisions for phase 1:

- **Identifier:** fixed `"aaps"` settings doc. Per-master-id deferred to
  phase 2/3.
- **Doc shape:** `{ schemaVersion: 1, runningConfig: <same JSON as
  RunningConfigurationImpl.configuration() produces> }`. Reusing the existing
  shape means the client-side `runningConfiguration.apply()` works unchanged.
- **Debounce:** 5s after last preference change before PATCH fires.
- **Dual-write window:** master writes to BOTH the devicestatus configuration
  field AND the settings doc. Client prefers the settings doc and falls back
  to devicestatus if no settings doc seen yet. Cut over in a later release
  once mixed-version installs are gone.
- **Auto-prune defense:** weekly worker re-POSTs to refresh `srvCreated`.

Components:

- **Master writer (new):** `RunningConfigurationPublisher` (or similar) — own
  class, do not modify `RunningConfigurationImpl`. Observes the synced
  preference keys, debounces 5s, calls `RunningConfigurationImpl.configuration()`
  for the JSON, PATCHes via `nsAndroidClient.patchSettings("aaps", ...)`. Initial
  PATCH on app start so the doc is fresh.
- **Client reader (replace stubs):** `LoadSettingsWorker.doWorkAndLog()` does
  the GET + apply on connect. `NSClientV3Service.kt:251` settings WS branch
  parses `runningConfig`, calls the same apply. Track the doc's `srvModified`
  to avoid re-applying.
- **Plugin-switch trigger:** preference observation alone misses plugin
  switches (insulin / APS / sensitivity / smoothing change via
  `configBuilder.performPluginSwitch`, which emits an event rather than a pref
  change). Need to hook that event in addition to preference changes.

Pre-step before phase 1 — **single-source-of-truth refactor of
`RunningConfigurationImpl`** (round 5b, below).

### Round 5b — refactor before publisher work (initial sketch, superseded)

**Note:** the initial proposal here was a centralized `SyncedConfigSchema` object with
typed lists grouped by key type (`intKeys`, `unitDoubleKeys`, `stringKeys`). This was
superseded by Rounds 6-8 below. The actual landed design **decentralizes** the schema —
each `ConfigExportImport` plugin owns its own `syncedKeys`. No central object.

The original problem identification still holds: introducing observation would create
a third parallel list of synced preference keys (build / apply / observe), and all
three must stay in sync. The fix shipped — it just looks different from this sketch.

### Round 6 — `ConfigExportImport` as the single source of truth

Realized the schema fits better on the existing `ConfigExportImport` interface than
on a separate object. Each plugin already implements this interface (Insulin, APS,
Sensitivity, Safety) and owns its own preference keys. Adding `syncedKeys` to the
interface lets each plugin declare what it owns — no central registry needed.

Bonus: the same insight applies to `QuickWizard` (`@Singleton`, owns
`StringNonKey.QuickWizard`). Made it a `ConfigExportImport` too. `TempTargetPresets`
has no domain class (every consumer reads the preference fresh — no cache to
invalidate), so its key stays free-floating in `RunningConfigurationImpl`'s overview
block.

### Round 7 — central orchestration vs per-plugin override

Considered two shapes:

- **A**: keep `applyConfiguration(JsonObject)` on the interface, force every plugin
  to override it (most write a 1-line delegation to a helper). Compile-error if a
  cache-having plugin forgets to wire the rebuild.
- **B**: drop `applyConfiguration` from the interface; central orchestration in
  `RunningConfigurationImpl` writes prefs directly via `.store()` then calls
  `reloadInternalState()` per plugin (default no-op for cacheless plugins, override
  in cache-having plugins like InsulinImpl).

Picked **B**. Reasoning: most plugins have no cache, so option A's forced override
is busywork. The risk in B (cache-having plugin forgets to override
`reloadInternalState`) is small — cache-having plugins are rare and obvious to their
authors. Plugins shouldn't see JSON at all; that's the orchestrator's concern.

### Round 8 — `reloadInternalState` abstract, not defaulted

Going further: even though most plugins want a no-op `reloadInternalState`, making
it abstract (no default body) forces every plugin to write `override fun
reloadInternalState() {}` — a one-line acknowledgement that's free for cacheless
plugins and forces awareness for cache-having ones. Future-proofs against silent
stale-cache bugs.

### Round 9 — incremental implementation

Sliced the refactor into reviewable steps that each compile cleanly with no behavior
change:

- **Step 1.** Add `preferences`, `syncedKeys`, abstract `reloadInternalState()` to
  `ConfigExportImport`. Keep `configuration()` / `applyConfiguration()` on the
  interface (still required by current callers; will be removed once
  `RunningConfigurationImpl` is rewritten). Adapt every implementer: declare
  `syncedKeys` matching its existing `configuration()` chain. Camp 1 plugins
  (Safety, Sensitivity ×3, APS ×3) use empty `reloadInternalState`. InsulinImpl uses
  `reloadInternalState() = loadSettings()`.
- **Step 1.5.** Make `QuickWizard` a `ConfigExportImport` — declarative readiness, no
  wire-shape change yet.
- **Step 2 (R1).** Replace 6 typed `JsonObject.put` / `.store` overloads with one
  each over `NonPreferenceKey`, using `when` dispatch. Add
  `Preferences.observeChange(NonPreferenceKey): Flow<Unit>` extension. Existing
  callers compile unchanged via subtype resolution.
- **Step 3 (next).** Migrate `RunningConfigurationImpl.buildOverviewConfiguration`
  and `applyOverviewConfiguration` to iterate `SyncedConfigSchema.overviewKeys` (the
  free-floating overview keys that have no plugin owner). Migrate per-plugin
  sub-objects in `configuration()` and `apply(NSDeviceStatus.Configuration)` to
  iterate `plugin.syncedKeys` instead of calling `plugin.configuration()` /
  `plugin.applyConfiguration()`. Side effect: SensitivityAAPSPlugin's
  duplicate-`AutosensMin` / missing-`AutosensMax` bug disappears (the chain is gone).
- **Step 4.** Once nothing else calls them, delete `configuration()` and
  `applyConfiguration()` from `ConfigExportImport` and remove the dead overrides.
- **Step 5+.** Phase 1 proper: `RunningConfigurationPublisher`, real
  `LoadSettingsWorker`, WS apply path, dual-write to devicestatus during transition.

### Round 9 side-discussion — TT presets and decentralized ownership

Considered making `TempTargetManagementViewModel` a `ConfigExportImport`. Wrong
shape:

- ViewModels are lifecycle-scoped, not application-scoped — can't be enumerated as
  `ConfigExportImport` providers when no UI is active.
- Multiple call sites read/write `StringNonKey.TempTargetPresets` (the VM, scenes,
  automation, QuickLaunch) — none is the natural owner.

Verified by grep: every consumer reads `preferences.get(StringNonKey.TempTargetPresets)`
fresh on each call. **No cache anywhere.** Therefore no `reloadInternalState()` is
needed — and no domain class is needed either. The key stays in
`RunningConfigurationImpl`'s overview block as a free-floating preference. That's
correct ownership when there's no domain object wrapping the preference.

InsulinImpl and QuickWizard are different: both have in-memory caches that need
rebuilding when the preference changes externally. Both became `ConfigExportImport`
with non-trivial `reloadInternalState`.
