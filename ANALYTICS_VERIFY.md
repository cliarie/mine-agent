# Analytics Pipeline Verification

## 1. Unit Tests

**File:** `mod/src/test/kotlin/dev/replaycraft/mcap/analytics/RunTrackerTest.kt`

Run with:
```bash
cd mod
./gradlew compileTestKotlin && \
java -cp "build/classes/kotlin/test:build/classes/kotlin/client:build/classes/kotlin/main:build/classes/java/main:$(find ~/.gradle/caches -name 'kotlin-stdlib-1.9.23.jar' | head -1)" \
  dev.replaycraft.mcap.analytics.RunTrackerTestKt
```

Seven tests covering:
| # | Test | Validates |
|---|------|-----------|
| 1 | Complete run → WIN summary | Tick counts, milestone ticks, resource counts, efficiency score, sessionId/playerId/modVersion, UUID runId |
| 2 | Quit mid-nether → QUIT | Partial nether ticks, zero end ticks, no kill/dragon ticks, portalBuildTick set |
| 3 | Death outcome | deaths counter increments, outcome="death", completed=false |
| 4 | Efficiency score floor | Overconsumption clamps efficiencyScore to 0 (not negative) |
| 5 | Beds only count in END | Bed placed in overworld is ignored; bed placed in End is counted |
| 6 | Unique run IDs | Two separate RunTracker instances produce different UUIDs |
| 7 | ResourceCounter deltas | Direct delta feeding: blazeRodsCollected, blazeRodsUsed, pearlsUsed, deaths, goldTraded, bedsPlaced |

## 2. Inventory Delta Hookup

`GameStateEventWriter.checkInventory()` writes raw binary slot snapshots. It does **not** emit discrete callback events.

`RecordingEventHandler.trackAnalyticsInventory()` independently diffs consecutive `ItemStack` snapshots per slot and calls `RunTracker.onInventoryDelta(itemId, delta)`. This is the correct approach — no modification to `GameStateEventWriter` is needed.

## 3. F8 Debug Keybind

**File:** `mod/src/client/kotlin/dev/replaycraft/mcap/McapReplayClient.kt`

Press **F8** in-game to print live `RunTracker` state to the game log:
```
[MCAP Analytics Debug] session=<uuid>
  phase=NETHER  overworld=3600  nether=1200  end=0
  blazeRods=5  pearls=3  beds=0  deaths=0  gold=0
  portalBuild=3600  fortress=-1  stronghold=-1  dragonEnter=-1  kill=-1
```

## 4. Analytics Emit Logging

**File:** `mod/src/client/kotlin/dev/replaycraft/mcap/analytics/AnalyticsEmitter.kt`

Before each HTTP POST, `doPost()` prints:
```
[MCAP Analytics] Analytics emit: run=<uuid> outcome=win ticks=7720 attempt=1 endpoint=<url>
```

## 5. SessionId Threading

`MlSessionManager.tryStart()` creates `sessionId` (line 55), passes it to `RunTracker(sessionId, ...)` (line 70), and sets the tracker on `RecordingEventHandler` (line 72). The same `sessionId` is used for the ML session directory, manifest, and analytics — single source of truth.

## 6. Replay Pipeline Isolation

No replay pipeline files were modified:
- `GameStateWriter.kt` — unchanged
- `GameStateEventWriter.kt` — unchanged
- `CaptureWriter.kt` — unchanged
- `TickRingBuffer.kt` — unchanged
- `RawPacketCapture.kt` — unchanged
- `replay/` directory — unchanged

## Files Changed

| File | Change |
|------|--------|
| `mod/build.gradle.kts` | Added test→client source set dependency |
| `mod/src/client/kotlin/.../McapReplayClient.kt` | F8 debug keybind registration + handler |
| `mod/src/client/kotlin/.../analytics/AnalyticsEmitter.kt` | Pre-emit log line |
| `mod/src/test/kotlin/.../analytics/RunTrackerTest.kt` | New — 7 unit tests |
