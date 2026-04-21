# BuildCore — Foundation Framework Design

**Date:** 2026-04-21
**Status:** Spec — ready for implementation planning
**Scope:** Foundation layer only (see "Scope Boundaries" below)
**Location:** `C:\Code\VitalPlugins\BuildCore\` subproject, mirrored to standalone private repo via `git subtree`
**Authors:** Chich + Claude (brainstormed through superpowers:brainstorming skill)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Research Summary](#2-research-summary)
3. [Scope Decisions Matrix](#3-scope-decisions-matrix)
4. [High-Level Architecture](#4-high-level-architecture)
5. [BuildCore-Server](#5-buildcore-server)
6. [Core Runtime (Task Engine)](#6-core-runtime-task-engine)
7. [Task SPI](#7-task-spi)
8. [Restriction Engine](#8-restriction-engine)
9. [Antiban Layer](#9-antiban-layer)
10. [Action Library (L5 Services)](#10-action-library-l5-services)
11. [Confidence, Watchdog, Recovery](#11-confidence-watchdog-recovery)
12. [Configuration & Profile System](#12-configuration--profile-system)
13. [Logging & Telemetry](#13-logging--telemetry)
14. [Licensing, Identity, Updates](#14-licensing-identity-updates)
15. [GUI Shell](#15-gui-shell)
16. [Architecture Tests Catalog](#16-architecture-tests-catalog)
17. [Open Questions & Provisional Items](#17-open-questions--provisional-items)

---

## 1. Overview

### What BuildCore is

BuildCore is an all-inclusive OSRS account builder for the VitalClient platform. It runs as a Kotlin RuneLite-style plugin inside VitalShell's JVM, hosted by the VitalClient C++ client. It is designed for:

- Private fleet use first (user + beta testers)
- Commercial sale later (all commercial-grade infrastructure built from day 1)
- Long lifetime with many activity modules added on top of a stable foundation

### Goals of this spec

Define the **foundation layer only** — the framework every future activity module (quests, skill trainers, money makers, minigames) sits on. The foundation is:

- Core runtime / task engine with state machine, retry, DAG, safe-stop
- Task SPI — the contract activity modules implement
- Restriction engine (account archetype enforcement)
- Antiban layer (per-account seeded behavioral profiles)
- Action library (thin wrappers over VitalAPI adding cross-cutting concerns)
- Confidence + watchdog + recovery pipeline
- Configuration / profile / plan system (portable JSON)
- Structured logging + telemetry
- Licensing + identity + updates
- Local GUI shell (Swing + FlatLaf)
- BuildCore-Server backend (Bun + TypeScript)

### Non-goals of this spec

- Any activity module implementation (quests, skills, money makers) — each is a separate future subproject with its own spec
- Account progression orchestrator (goal-graph "get this account to X" autoplanner)
- Account warmup system
- Fleet coordination (VitalExchange-Server already handles that for flipping)
- Web planner (explicitly out of scope, never building)
- Hard anti-piracy / obfuscation (deferred pending VitalClient's own security model)
- Cross-platform support (Windows-only v1)
- Licensing-for-sale details (in scope architecturally; customer-facing subject to revision when VitalClient's auth model is defined)

### Guiding principles (derived from research)

1. **Plan is portable JSON.** GUI edits files; runner consumes files. Clean separation means a future web planner, CLI launcher, or fleet admin tool all emit the same artifact.
2. **Every task has a mandatory ironman path.** GE/mule/trade paths are optimizations. The type system enforces this.
3. **Restrictions are cross-cutting and enumerated.** No free-form restriction strings.
4. **Antiban is transparent at L7.** Tasks cannot issue a raw click — they call `interact(npc)` and L7 handles mouse curve + timing.
5. **No force-close ever** (except unhandled crash). Safe-Stop Contract applies to server-triggered stops, license revocation, updates, failures.
6. **External inputs cannot weaponize safety triggers.** Trade spam, impersonation via chat text, etc. are rate-limited and debounced.
7. **Structured JSON events from day 1.** No unstructured log lines. Telemetry, replay, GUI, third-party monitors all consume the same event stream.
8. **Hot-editable rules data lives on BuildCore-Server.** Quest steps, item mappings, failure patterns, shop inventories. Fix without JAR redeploys.
9. **Safety triggers only consume authoritative game-state flags.** Never chat text or visual heuristics that an external player can fabricate.
10. **Tick-precision bypasses are bounded and audited.** Escape hatches exist; they are logged, budgeted, annotated, and never allow raw input bypass.

---

## 2. Research Summary

Source: live-web research agent crawling public SDN pages, forum threads, wikis, reddit, and community writeups on six commercial builders.

### Builders surveyed

1. **Dentist AIO Account Builder (TRiBot)** — premium, broad task library, Tutorial Island reputation. [tribot.org/store/products/6-AIO-Account-Builder](https://tribot.org/store/products/6-AIO-Account-Builder)
2. **gZero / GAIO (DreamBot)** — GScripts team, dynamic task rotation, integrated muling, 1–1000 priority manual mode. [wiki.gscripts.co/GAIO](https://wiki.gscripts.co/GAIO)
3. **Guester (DreamBot)** — quest specialist addon, ~190 quests + 170 diaries with ironman variants, in-progress resume. [wiki.gscripts.co/index.php?title=Guester](https://wiki.gscripts.co/index.php?title=Guester)
4. **Allure (Storm)** — ~134 quests + 6 diaries. **FLAGGED AS MALWARE** in community sources (credential theft allegations). Feature-scope reference only; distribution model must not be emulated.
5. **7up Builder (Storm)** — standalone web-based planner at [sevenupplanner.com](https://sevenupplanner.com/) compiles plans server-side; runner consumes.
6. **Aeglen's P2P Master AI (DreamBot)** — "Argen" in user brief was misspelling. 200+ training methods, 80+ resumable quests, marketed as "ML-trained behavioral profiles." [dreambot.org/forums/index.php?/store/product/597-p2p-master-ai/](https://dreambot.org/forums/index.php?/store/product/597-p2p-master-ai/)

### Universal features (table-stakes for BuildCore)

- All-skills 1–99 coverage with per-skill method selection
- Quest engine with resume-from-any-state
- Grand Exchange integration for supply acquisition
- Muling with configurable thresholds + Discord webhook
- JSON profile save/load with community sharing
- 4-tier break system (micro / normal / bedtime / banking)
- Per-script Discord support channel

### Differentiators users pay for

- **Quest breadth as moat** — Guester's value prop is literally incrementing its thread title with quest counts (130→190+). Quest count is the most-advertised number.
- **"1-click start" with dynamic defaults** — users pay for not having to configure. GAIO, Aeglen both lead with this.
- **External hot-editable data** — GScripts and Aeglen both use Google Sheets for quest data and failure triage. Fixes ship without JAR updates.
- **Addon/plugin loader inside the builder** — GAIO's ~25 addons pattern.
- **Per-account seeded behavioral profile** — Aeglen's most-cited feature.

### Recurring failure modes

- **Silent stalls** — "sometimes stalls somewhere but rerunning resolves." Top complaint across every builder. Watchdog + confidence layer required.
- **"Resumable" quests that aren't** — #1 refund reason. Resume must be first-class quest-author responsibility with test harness.
- **Fresh-account ban velocity** — first 24h is critical. Graduated throttle required.
- **Ironman edge cases** — Dentist explicitly doesn't support ironman and takes complaints; Guester treats ironman as first-class and charges for it.
- **Client-level fingerprint detection** eclipses script-level antiban. VitalClient stealth is foundational; BuildCore antiban sits on top.

### Antiban 2025 state-of-the-art (community consensus)

- WindMouse-style mouse curves with overshoot
- Log-normal reaction-time distributions (not uniform)
- Seeded per-account behavioral profiles
- Over/under bank withdrawals
- Tab/examine/fidget chances
- 4-tier break taxonomy (micro/normal/bedtime/banking)
- Contextual reactions (check stats occasionally)
- Deliberate sub-optimality (not always the fastest action)
- Fatigue curves over long sessions

### Architectural patterns stolen

1. **Plan → Runner split** (7up)
2. **External hot-editable rules data** (GScripts, Aeglen)
3. **Addon/plugin loader** (GAIO)
4. **Dynamic task scheduler + 1–1000 manual priority** (GAIO)
5. **Structured log event stream** parseable by third-party tools (Aeglen)
6. **Per-account seeded behavioral profile** (Aeglen)

---

## 3. Scope Decisions Matrix

Every dimension was walked through deliberately. Table format for reference.

| Dimension | Decision |
|---|---|
| Primary user | Commercial-grade from day 1 with beta testers first |
| Project scope | Foundation only; activity modules, orchestrator, warmup, fleet, web planner deferred |
| Licensing | FOUNDATION (subject to revision per VitalClient auth model) |
| Auto-update | FOUNDATION |
| Telemetry | FOUNDATION |
| User identity | FOUNDATION (Discord OAuth) |
| Tiered feature gating | FOUNDATION (Admin, Tester, Trial, Subscriber, Expired) |
| Signed builds | FOUNDATION |
| In-app docs | FOUNDATION (tooltips v1, embedded docs later) |
| Support backchannel | FOUNDATION |
| Hard anti-piracy / obfuscation | DEFERRED (pending VitalClient) |
| Backend shape | Dedicated BuildCore-Server (Bun+TS), own VPS co-tenant |
| Account archetypes supported | Main, Pure (all variants), Zerker, Skiller, Ironman — foundation first-class |
| HCIM | PLANNED, deferred; schema present, flagged disabled |
| UIM / GIM / DMM | UNSUPPORTED forever; no special handling |
| Module distribution | Monolithic JAR + hot-editable rules data (pattern C); SPI designed module-friendly for future split |
| Antiban seed source | Username → seed (stable per account forever) |
| Antiban determinism | Two-layer RNG: stable personality vector + fresh per-session RNG |
| Antiban replay mode | Full replay-capable for debugging |
| Antiban interception | Transparent at L7 (no raw-input escape hatch except audited Precision Mode) |
| License identity | Discord OAuth |
| License offline grace | 48h (subject to revision) |
| License tiers | All 5 (Admin, Tester, Trial, Subscriber, Expired) |
| Concurrent cap model | Tier-based |
| Revocation | Immediate kill (online only; offline clients drain at next check-in) |
| Telemetry collection | Always-on Admin/Tester; opt-in Trial/Paid |
| Telemetry baseline | Crashes, task failures, heartbeats, session summary, perf counters |
| Telemetry diagnostic-mode | Full event log, screenshots, replay traces |
| Retention | Crashes 180d / Heartbeats 7d raw + aggregate / Events 30d / Screenshots 30d |
| Scale target | 500–5000 concurrent |
| Update channels | Nightly (Admin) / Beta (Tester) / Stable (Paid) |
| Update trigger | Check at launch only; never mid-session force-restart |
| Rollback mechanism | Server-side signal; manual + automatic triggers |
| Release staging | All-at-once within a tier (tier streams already stage) |
| Platform | Windows only |
| Language | Kotlin (with Java interop for VitalAPI/VitalShell) |
| GUI framework | Swing + FlatLaf (IntelliJ Darcula + accent colors) |
| Build layout | VitalPlugins subproject + mirrored standalone repo (git subtree) |
| JDK target | Java 11 |
| Task crash isolation | Per-task retry (configurable); critical-path vs optional; safe-stop if critical fails |
| Watchdog | Per-task stall detection + confidence monitoring, state snapshots |
| Unknown-state handling | Foundation Confidence Layer + standard recovery pipeline |
| Safety triggers | Task-specific HP/wilderness/combat logic; trade off at account level; authoritative flags only |

---

## 4. High-Level Architecture

### Layer stack (top → bottom)

```
┌────────────────────────────────────────────────────────────┐
│  L1  GUI SHELL  (Swing + FlatLaf standalone window)        │
│      • Profile editor  • Task queue view  • Live status    │
│      • Logs pane       • Replay viewer   • Module registry │
├────────────────────────────────────────────────────────────┤
│  L2  PROFILE / PLAN LAYER                                  │
│      • Plan = portable JSON (version-tagged)               │
│      • Restrictions set   • Personality vector (stable)    │
│      • Task queue (ordered, with predicates & priorities)  │
├────────────────────────────────────────────────────────────┤
│  L3  TASK RUNNER / DAG ENGINE                              │
│      • Lifecycle (start/pause/stop)  • Retry policy        │
│      • Critical-path vs optional  • Safe-Stop Contract     │
│      • Dynamic mode (auto-priority) + Manual (1-1000)      │
├────────────────────────────────────────────────────────────┤
│  L4  TASK SPI (plug point — modules register here)         │
│      • Declares: effects, paths, reqs, progress signal     │
│      • Every task has mandatory ironmanPath()              │
│      • Optional gePath() / mulePath() gated by restrictions│
├────────────────────────────────────────────────────────────┤
│  L5  SERVICES LAYER (primitives used by tasks)             │
│      Bank · GE · Mule · Walker · Dialogue · Inventory      │
│      Login · Combat · Health · Wilderness · Interface      │
├────────────────────────────────────────────────────────────┤
│  L6  CONFIDENCE + WATCHDOG + RECOVERY                      │
│      • "Am I sure what screen I'm on?"                     │
│      • Stall detection (no progress in N min)              │
│      • Recovery pipeline (dismiss, close, verify, resume)  │
├────────────────────────────────────────────────────────────┤
│  L7  INPUT PRIMITIVES (transparent antiban interception)   │
│      Mouse · Keyboard · Camera                             │
├────────────────────────────────────────────────────────────┤
│  L8  VITALAPI / VITALSHELL BRIDGE (given)                  │
└────────────────────────────────────────────────────────────┘
```

### Cross-cutting concerns

- **Restriction Engine** — wraps L3/L4; consulted at every path selection
- **Antiban Layer** — two-layer RNG, transparent at L7

### Horizontal infrastructure strip

- **Event Bus** — pub/sub for every lifecycle, progress, failure event
- **Logging** — structured JSON writers
- **Profile Persistence** — disk serialization
- **License / Telemetry Client** — talks to BuildCore-Server
- **Config** — local + remote (hot-rules data)

### External interactions

```
              ┌────────────────────┐
              │  BuildCore-Server  │
              │  (Bun + TS)        │
              └────────────────────┘
                       ▲
                       │  HTTPS + WSS
                       │
              ┌────────────────────┐
              │  BuildCore (L1-L8) │
              │  inside VitalShell │
              │  JVM in VitalClient│
              └────────────────────┘
                       │
                       │  JNI via VitalAPI
                       ▼
              ┌────────────────────┐
              │  OSRS C++ Client   │
              └────────────────────┘
```

---

## 5. BuildCore-Server

Dedicated Bun + TypeScript backend co-located on user's existing VPS at `157.180.5.245`, port 3001. Purpose: authenticate, authorize, ingest telemetry, serve updates and hot-data.

### Data stores

| Data | Store | Rationale |
|---|---|---|
| Users / licenses / tiers / entitlements | **PostgreSQL** | Transactional, relational, FKs |
| Live session heartbeats / concurrent-cap enforcement | **Redis** | In-memory, TTL-based. `SET session:<uuid> <meta> EX 120` |
| Ingest queue (telemetry events, crashes, screenshots) | **Redis streams** | Decouples HTTP latency from processing |
| Telemetry hot (recent 30d) | **PostgreSQL** with time-range partitioning | Queryable directly |
| Telemetry cold (historical, compressed) | **Parquet files via DuckDB** | Same pattern as VitalExchange-Server |
| Screenshots / blobs | **Filesystem** with hash-dedupe | `data/blobs/<sha256>/...` |
| Hot rules data | **Git-versioned JSON/YAML** served via signed HTTPS | Editable by admin, versioned by git |

### Endpoint surface

All non-auth endpoints require `Authorization: Bearer <license-token>`.

**Auth / identity**
- `GET /auth/discord/start`
- `GET /auth/discord/callback`
- `POST /auth/validate`
- `POST /auth/refresh`

**Sessions (concurrent-cap enforcement)**
- `POST /sessions/start` (returns UUID or 409 on tier cap exceeded)
- `POST /sessions/heartbeat`
- `POST /sessions/end`

**Telemetry ingest**
- `POST /telemetry/crash`
- `POST /telemetry/task-failure`
- `POST /telemetry/session-summary`
- `POST /telemetry/event-batch`
- `POST /telemetry/blob`

**Updates**
- `GET /updates/manifest?channel=<nightly|beta|stable>`
- `GET /updates/download/<version>.jar`

**Hot rules data** (all signed; all support `?since=<etag>` for 304)
- `GET /rules/quests/<quest-id>`
- `GET /rules/item-map`
- `GET /rules/failure-patterns`
- `GET /rules/shop-inventory/<npc-id>`

**Admin (Admin tier only)**
- `POST /admin/revoke/<user-id>`
- `POST /admin/rollback`
- `POST /admin/release`
- `GET /admin/fleet`

**Support**
- `POST /support/bug-report`

### WebSocket push channel

`WSS /ws/client` — authenticated with session UUID.

**Server → client events**
- `license.revoked` — trigger Safe Stop
- `config.reload` — pull new rules data, hot-apply
- `update.available` — informational; client checks at next natural restart
- `admin.message` — targeted message for debugging

### Retention / lifecycle jobs

- Hourly: drain Redis streams → Postgres hot tables
- Daily: aggregate yesterday's heartbeats into daily summaries; delete raw heartbeats > 7d
- Weekly: export 30d-old rows from Postgres hot tables to Parquet
- Monthly: compact Parquet, compute long-term aggregates
- On demand: GDPR-compliant user wipe across Postgres, Redis, Parquet, blobs

### Server deliverables (bootstrap checklist)

1. Bun+TS project scaffolded, matching VitalExchange-Server conventions (file logging, EST timestamps, 10MB rotation)
2. Postgres schema migrated (users, licenses, tiers, sessions, crashes, task_failures, session_summaries, blobs)
3. Redis running with session + stream config
4. Discord OAuth flow end-to-end
5. All endpoints implemented with request validation (Zod or similar)
6. WebSocket channel with reconnect handling
7. JAR signing pipeline
8. Rules-data repo with sample quest + item-map, served + signed
9. Systemd service + Caddy TLS
10. Admin CLI (curl-level OK for v1)

---

## 6. Core Runtime (Task Engine)

### Task lifecycle state machine

Every task instance moves through:

```
PENDING → VALIDATE → STARTING → RUNNING → STOPPING → COMPLETED
                                    │                      ↑
                                    ↓                      │
                               RECOVERING ─────────────────┘
                                    │
                                    ↓
              FAILED ← (any non-terminal state)
              PAUSED ← (any non-terminal state)
```

Every transition emits a structured event on the event bus.

### Plan / queue structure

A **Plan** is portable JSON (see Section 12 for full schema):

```
Plan
├── metadata (name, schemaVersion, createdAt, accountArchetype)
├── restrictions (Restriction set)
├── personalityVector (seeded from username, persisted)
├── muleConfig (none | bondsOnly | full, plus mule group ID)
├── mode (DYNAMIC | MANUAL)
├── tasks [ordered]
│   ├── taskId
│   ├── config (per-task config blob)
│   ├── predicate (completion override)
│   ├── priority (1-1000, manual mode only)
│   ├── criticality (CRITICAL_PATH | OPTIONAL)
│   ├── retry (count, backoff, onExhausted)
│   └── dependsOn [list of taskIds]
└── breakSchedule
```

**Dynamic mode:** priorities computed at runtime from account state.
**Manual mode:** user-assigned 1-1000 weights, runner picks highest-weighted ready task each cycle.

### Retry & failure policy

Per-task config:

```
retry {
    maxAttempts: Int         default 3
    backoff: Backoff         default Exponential(baseSeconds=30, maxSeconds=600)
    onExhausted: Action      default SKIP_IF_OPTIONAL else SAFE_STOP
    resetOnSuccess: Boolean  default true
}
```

Decision tree when a task fails:

```
Task failed (exception, validation fail, recovery fail, stall)
  ↓
  Transient? (network, session timeout, dialog timeout)
    Yes → retry with backoff (if attempts remain)
    No  → continue
  ↓
  Retries exhausted?
    No  → retry with backoff
    Yes → continue
  ↓
  CRITICAL_PATH?
    No  → mark FAILED, skip, runner picks next task
    Yes → ship crash report, run safe-stop, exit session
```

### Safe-Stop Contract

Every task declares `canStopNow(): Boolean` and `safeStop(ctx: TaskContext)`. When a stop is requested:

```
Stop requested → runner enters STOPPING mode
  ↓
  Wait up to N seconds for canStopNow() to return true
  ↓
  safeStop() is called: bank items, walk to safe location, clean logout
  ↓
  Session ends cleanly, telemetry session-summary fires
```

Hard exceptions where safe-stop is skipped: unhandled exception / JVM fatal error. Never force-close otherwise.

### Watchdog

Dedicated thread, 1s polling. Monitors:

1. **Progress fingerprint** — unchanged for 5 min (default) → STALL
2. **Confidence** — below 0.4 for 30s → UNCERTAIN
3. **Runner heartbeat** — missed > 15s → DEADLOCK
4. **Precision mode budget** — exceeded declared max → LEAK

### Graduated throttle (fresh accounts)

Activates when:
- Account total XP < 100k, OR
- Account created-at within last 24h, OR
- Explicit `freshAccount: true` flag on profile

When active:
- Reaction time multiplier: 1.5×
- Task switches per hour cap: reduced 40%
- Shorter session length bias
- XP/hour soft cap reduced to 60%

### Event bus

All lifecycle, progress, failure events flow through one `MutableSharedFlow<BusEvent>`. Subscribers: GUI, local logger, telemetry client, replay recorder, fatigue model, watchdog. See Section 13 for event taxonomy.

### Foundation deliverables for v1

1. State machine implementation with all transitions emitting events
2. `Task` interface + abstract base class with common helpers
3. `Plan` JSON schema + loader/saver + migration framework
4. Runner loop (dynamic + manual mode)
5. Retry policy engine with backoff
6. Safe-Stop Contract orchestrator
7. Watchdog with stall + confidence monitoring
8. Graduated throttle module
9. Event bus with subscriber registration
10. Reference "null task" that ticks through states without doing game actions

---

## 7. Task SPI

### Three-level variability hierarchy

```
Task                (unit of intent: "Mining")
  ├── Method A      ("power-mine iron @ Varrock West")
  │     ├── Path: ironman   (always required)
  │     └── Path: GE         (optional, gated)
  ├── Method B      ("drop-mine coal @ Mining Guild")
  │     └── Path: ironman
  └── Method C      ("iron rotation pool across 3 locations")
        └── Path: ironman
```

- **Task** = activity unit. "Mining" is one task module.
- **Method** = concrete execution strategy. Each has own location, rate, requirements, config, risk profile. A single task ships many methods.
- **Path** = economic variant within a method (ironman self-gather vs GE-accelerated vs mule-assisted).

### Task interface

```kotlin
interface Task {
    val id: TaskId
    val displayName: String
    val version: SemVer
    val moduleId: ModuleId

    fun methods(): List<Method>
    fun config(): ConfigSchema
    fun priority(): PriorityHints
    fun estimatedDuration(profile: Profile): Duration

    fun validate(ctx: TaskContext): ValidationResult
    fun onStart(ctx: TaskContext)
    fun step(ctx: TaskContext): StepResult
    fun isComplete(ctx: TaskContext): Boolean
    fun safeStop(ctx: TaskContext)

    fun progressSignal(ctx: TaskContext): ProgressFingerprint
    fun canStopNow(ctx: TaskContext): Boolean
    fun onUnknownState(ctx: TaskContext, snapshot: StateSnapshot): RecoveryDecision
}
```

### Method interface

```kotlin
interface Method {
    val id: MethodId
    val displayName: String
    val description: String

    fun paths(): List<ExecutionPath>
    fun requirements(): Requirements
    fun effects(): Set<Effect>
    fun config(): ConfigSchema
    fun estimatedRate(profile: Profile): Rate
    fun locationFootprint(): Set<Area>
    fun risk(): RiskProfile   // NONE / LOW / MEDIUM / HIGH
}
```

### ExecutionPath

```kotlin
data class ExecutionPath(
    val id: PathId,
    val kind: PathKind,                // IRONMAN, GE, MULE, HYBRID
    val effects: Set<Effect>,
    val requirements: Requirements,
    val estimatedRate: XpPerHr,
    val gatingRestrictions: Set<Restriction>,
    val run: suspend (TaskContext) -> StepResult
)
```

**Invariants** (enforced at module load time):
1. Every method MUST have exactly one path with `kind = IRONMAN`.
2. The ironman path has no `gatingRestrictions`.
3. Other paths declare which restrictions gate them.

### Effect taxonomy (sealed, enumerated)

```kotlin
sealed class Effect {
    data class GrantsXp(val skill: Skill, val rate: XpRange) : Effect()
    data class AcquiresItem(val itemId: Int, val qty: Int, val via: AcquisitionMethod) : Effect()
    data class SpendsGp(val estimated: GpRange) : Effect()
    data class EntersArea(val area: AreaTag) : Effect()
    data class CompletesQuest(val questId: QuestId) : Effect()
    data class RaisesCombatLevel(val delta: Int) : Effect()
    object RequiresMembership : Effect()
    data class TradesWithPlayer(val purpose: TradePurpose) : Effect()
    data class UsesGrandExchange(val action: GeAction) : Effect()
    data class CustomEffect(val tag: String) : Effect()
}
```

### Requirement taxonomy

```kotlin
sealed class Requirement {
    data class StatLevel(val skill: Skill, val min: Int) : Requirement()
    data class QuestComplete(val questId: QuestId) : Requirement()
    data class QuestPartial(val questId: QuestId, val step: Int) : Requirement()
    data class ItemInInventory(val itemId: Int, val qty: Int) : Requirement()
    data class ItemInBank(val itemId: Int, val qty: Int) : Requirement()
    data class ItemEquipped(val itemId: Int, val slot: EquipmentSlot) : Requirement()
    data class GpOnHand(val amount: GpAmount) : Requirement()
    data class MembershipStatus(val status: Member | F2P) : Requirement()
    data class InArea(val area: AreaTag) : Requirement()
    data class AccountFlag(val flag: String) : Requirement()
    data class AllOf(val reqs: List<Requirement>) : Requirement()
    data class AnyOf(val reqs: List<Requirement>) : Requirement()
    data class Not(val req: Requirement) : Requirement()
}
```

### Path Selector algorithm

```
pathsSortedByRate = method.paths().sortedByDescending { it.estimatedRate }

for path in pathsSortedByRate:
    if any of path.gatingRestrictions in profile.restrictions: skip
    if any of path.requirements unmet: skip
    return path

// Only ironman path remained and it's also unmet → task FAILED with UNAVAILABLE
```

### Method Selector algorithm

Runs before Path Selector:

```
compatible = task.methods().filter { method ->
    RestrictionEngine.isMethodCompatible(method, profile)
    && method.requirements().isSatisfied(profile.currentState)
}
if compatible.empty → task HARD_FAIL

if user selected one method → return that
if multiple → weighted rotation with antiban bias (don't repeat last, personality-seeded weight perturbation)
```

### ConfigSchema (GUI-auto-rendered)

```kotlin
data class ConfigSchema(val fields: List<ConfigField>)

sealed class ConfigField {
    data class IntRange(val key: String, val label: String, val min: Int, val max: Int, val default: Int)
    data class Toggle(val key: String, val label: String, val default: Boolean)
    data class ItemPicker(val key: String, val label: String, val filter: ItemFilter)
    data class LocationPicker(val key: String, val label: String, val preset: List<Location>)
    data class Enum(val key: String, val label: String, val options: List<String>, val default: String)
    data class CompletionPredicate(val key: String, val defaults: List<PredicateTemplate>)
    data class VisibleWhen(val field: ConfigField, val condition: String)
}
```

One `ConfigSchemaRenderer` in the GUI walks any schema and produces Swing form controls. Task authors never write Swing code.

### Progress fingerprint

```kotlin
data class ProgressFingerprint(
    val xpTotals: Map<Skill, Long>,
    val inventoryHash: Int,
    val playerTile: Location?,
    val openInterface: InterfaceId?,
    val custom: Map<String, Any>
)
```

### Module registration

```kotlin
@BuildCoreModule(
    id = "buildcore.skill.smithing",
    version = "1.0.0",
    requiresCoreVersion = "^1.0"
)
class SmithingModule : Module {
    override fun tasks(): List<Task> = listOf(...)
}
```

Discovery at runtime via build-time registry (kapt/KSP). No reflection on hot path.

### Hot-data binding

```kotlin
class WaterfallQuestTask : Task {
    private val rules by hotRules("quests/waterfall")   // auto-refresh on server push

    override fun step(ctx: TaskContext): StepResult {
        val currentStep = rules.steps[ctx.questProgress]
        // ...
    }
}
```

`hotRules(path)` property delegate fetches + verifies signature, caches locally, watches for `config.reload` pushes, swaps transactionally between ticks.

### Task authorship guarantees (build-time enforced)

1. Ironman path mandatory (annotation check)
2. No static mutable state in tasks
3. No direct input calls (no `Mouse.click()` etc.)
4. No direct file I/O
5. Every task ships with validation test + resume-from-state test

---

## 8. Restriction Engine

### Restriction taxonomy (sealed, enumerated)

```kotlin
sealed class Restriction {
    // XP
    data class XpCap(val skill: Skill, val maxLevel: Int) : Restriction()
    data class XpForbidden(val skill: Skill) : Restriction()
    data class QpCap(val maxQp: Int) : Restriction()

    // Economy
    object NoGrandExchange : Restriction()
    object NoPlayerTrade : Restriction()
    object NoDroppedLoot : Restriction()

    // Mule (exactly one of these three)
    object NoMuleInteraction : Restriction()
    object MuleBondsOnly : Restriction()
    object MuleFull : Restriction()

    // Area
    object NoWilderness : Restriction()
    object NoPvP : Restriction()
    data class NoArea(val area: AreaTag) : Restriction()

    // Safety (HCIM layer, deferred)
    data class HpFleeFloor(val percent: Int) : Restriction()
    object NoHighRiskCombat : Restriction()
    object HcimSafetyBundle : Restriction()

    // Feature-flag
    object NoQuestsBeyondRequirements : Restriction()
    object NoTasksThatRaiseCombatLevel : Restriction()

    // Escape hatch
    data class CustomFlag(val tag: String) : Restriction()
}
```

### Archetype presets

```kotlin
object Archetypes {
    val MAIN       = ArchetypeDefinition(id="main", baseRestrictions=setOf(MuleFull))
    val PURE_1DEF  = ArchetypeDefinition(id="pure.1def", baseRestrictions=setOf(
        XpCap(DEFENCE, 1), XpCap(PRAYER, 1), MuleFull))
    val PURE_60ATT = ArchetypeDefinition(id="pure.60att", baseRestrictions=setOf(
        XpCap(ATTACK, 60), XpCap(DEFENCE, 1), XpCap(PRAYER, 1), MuleFull))
    val ZERKER     = ArchetypeDefinition(id="zerker", baseRestrictions=setOf(
        XpCap(DEFENCE, 45), XpCap(PRAYER, 52), XpCap(HITPOINTS, 80), MuleFull))
    val SKILLER    = ArchetypeDefinition(id="skiller", baseRestrictions=setOf(
        XpForbidden(ATTACK), XpForbidden(STRENGTH), XpForbidden(DEFENCE),
        XpForbidden(HITPOINTS), XpForbidden(RANGED), XpForbidden(MAGIC),
        XpForbidden(PRAYER), MuleFull))
    val IRONMAN    = ArchetypeDefinition(id="ironman", baseRestrictions=setOf(
        NoGrandExchange, NoPlayerTrade, NoDroppedLoot, MuleBondsOnly))
    val HCIM       = ArchetypeDefinition(id="hcim", enabled=false, baseRestrictions=
        IRONMAN.baseRestrictions + setOf(HcimSafetyBundle, NoWilderness,
        NoHighRiskCombat, HpFleeFloor(50)))
}
```

Archetypes are **data bundles, not code branches**.

### Per-profile overrides

```
effective = archetype.baseRestrictions
          + profile.additionalRestrictions
          .replaceMuleTier(profile.muleOverride)
```

Profiles can only ADD restrictions, never REMOVE archetype-imposed ones.

### Validation lifecycle (three moments)

1. **Edit-time** — incompatible methods hidden in GUI, incompatible tasks hidden from add-to-plan
2. **Plan-start-time** — full plan re-validation, hard-stop with clear error if any unresolvable
3. **Runtime** — cheap re-check before each task's STARTING state

### Restriction violation handling

```kotlin
sealed class RestrictionViolationHandler {
    object HARD_FAIL : RestrictionViolationHandler()
    object PATH_FALLBACK : RestrictionViolationHandler()
    object METHOD_FALLBACK : RestrictionViolationHandler()
    object LOG_AND_CONTINUE : RestrictionViolationHandler()
}
```

Defaults:
- XP restrictions → PATH_FALLBACK → METHOD_FALLBACK → HARD_FAIL
- Economy restrictions → PATH_FALLBACK (ironman path is fallback)
- Area restrictions → HARD_FAIL
- Safety restrictions (HCIM) → HARD_FAIL

### XP boundary enforcement

For `XpCap(skill, maxLevel)`:

```
Before every action emitting GrantsXp(skill, _):
  currentLevel = state.skillLevel(skill)
  if currentLevel >= maxLevel: MethodSelector re-pick
  if currentLevel == maxLevel-1 AND nextActionXp would overshoot: abort action, re-pick
```

Conservative projection — if we can't prove an action won't overshoot, we don't take it.

### Custom archetypes

Users can save custom restriction bundles. Named presets stored alongside built-ins. If a preset exactly matches a built-in, uses built-in name; otherwise "Custom."

---

## 9. Antiban Layer

### Two-layer RNG

```
LAYER 1: PersonalityRng (stable, per-account)
  Seed: SHA256(username).toLongBits()
  Output: PersonalityVector (once, persisted at data/personalities/<uuid>.json)

LAYER 2: SessionRng (fresh, per-session)
  Seed: SecureRandom.nextLong() captured at session start
  Output: individual action samples
  Every draw logged for replay
```

### PersonalityVector (15 dimensions)

- `mouseSpeedCenter` (0.6–1.8)
- `mouseCurveGravity` (8.0–12.0)
- `mouseCurveWind` (3.0–7.0)
- `overshootTendency` (0.02–0.12)
- `reactionLogMean` (5.5–6.5)
- `reactionLogStddev` (0.3–0.5)
- `hotkeyPreference` (0.4–0.9)
- `foodEatDelayCenter` (400–900ms)
- `cameraFidgetRatePerMin` (0.8–3.5)
- `bankWithdrawalPrecision` (0.85–0.99)
- `breakBias` (nightOwl / dayRegular / burst)
- `misclickRate` (0.003–0.015)
- `menuTopSelectionRate` (0.92–0.995)
- `idleExamineRate` (0.5–2.5/min)
- `tabSwapRate` (0.3–1.8/min)

### Transparent interception at L7

```kotlin
object Mouse {
    fun moveTo(screenTarget: Point)
    fun click(button: MouseButton = LEFT)
    fun moveAndClick(screenTarget: Point)
}
object Keyboard { fun press(key: Key); fun type(text: String) }
object Camera  { fun rotateToward(angle: Angle); fun pitch(degrees: Int) }
```

No raw-input escape hatches. Every call wraps with: pre-action reaction delay, mouse curve (WindMouse with personality params), post-action micro-delay, event emission.

### Precision Mode (controlled bypass)

```kotlin
enum class InputMode { NORMAL, PRECISION, SURVIVAL }

Antiban.withMode(InputMode.PRECISION, maxDurationTicks = 3) {
    Inventory.drop(fish)
    Inventory.use(bait, fishingSpot)
    Interact.click(fishingSpot)
}
```

**PRECISION changes:**
- Reaction delay: tight normal(~30, ~15) ≈ 15–60ms (vs 250–700ms normal)
- Mouse curve: short but still not teleport
- Overshoot disabled
- Fatigue multiplier not applied
- Break scheduler deferred until window ends
- Camera fidget suppressed

**SURVIVAL:** identical to PRECISION with 5–15ms minimum delay.

**What does NOT change in precision modes:**
- Mouse curves still run (teleporting cursor = instant detection flag)
- Events still emit with `mode=PRECISION` flag
- Personality still seeds tiny jitter

### Precision guardrails

- Max window duration (declared `maxDurationTicks`)
- Per-session precision tick budget (default ~300 ticks/hr)
- `@UsesPrecisionInput` annotation required on methods using precision
- Telemetry event on every precision entry
- Forbidden by default in bulk-skilling methods (lint warning)

### 4-tier break system

| Tier | Duration | Trigger |
|---|---|---|
| Micro | 2–30s | Poisson every few minutes |
| Normal | 2–15min | 30–90min intervals biased by `breakBias` |
| Bedtime | 6–10h | Sleep phase from `breakBias` schedule |
| Banking | 30–60s | After sustained inventory operations |

### Break ↔ Safe-Stop Contract integration

Break scheduler calls active task's `canStopNow()` before pausing. Per-tier defer caps:

| Tier | maxDeferSeconds | On exhaustion |
|---|---|---|
| Micro | 30s | Skip |
| Banking | 60s | Skip |
| Normal | 300s | Downgrade to micro, re-queue |
| Bedtime | unbounded (up to 20min) then invoke `safeStopSteps()` | Bedtime MUST happen |

Precision mode blocks break scheduler's pause until precision window ends (bounded, short).

### Fatigue curve

Session-scoped multiplier on sample distributions:
- Reaction time mean creeps +5–15% over 4h
- Misclick rate creeps +20–50% over 4h
- Mouse overshoot variance increases
- Camera fidget frequency increases

### Graduated throttle integration

```
effectiveReactionDelay = personality.sample(SessionRng)
                       × fatigue.multiplier(sessionAge)
                       × throttle.multiplier(accountAge, totalXp)
```

### Replay mode

Records per-session:
- `SessionRng` initial seed
- Every input action (type, target, curve, timings, rngStateBefore/After)
- Every service call (name, method, args, response)
- Every external event (game state deltas observed)
- PersonalityVector snapshot

Format: append-only JSON lines in `data/replays/<sessionId>.jsonl`. Replay runtime uses `ReplayRng` (deterministic replay of recorded draws) + `ReplayServices` (intercepts service calls, validates arg match, returns recorded responses). Byte-identical re-execution given the same code version.

---

## 10. Action Library (L5 Services)

### Framing

Services are **thin interception wrappers over VitalAPI**, adding cross-cutting concerns:
1. Antiban routing (all input through L7)
2. Event emission (structured events on every call)
3. Restriction gating (first-line vetoes on GE/Mule/Trade/Wilderness)
4. Confidence tracking (state-change hints for Section 11)
5. Hot-rules binding

VitalAPI provides the underlying functionality; we do not reimplement it.

### Interception wrappers (thin delegates)

`Bank`, `Inventory`, `Equipment`, `Login`, `World`, `Walker`, `Combat`, `Magic`, `Prayer`, `Interact`, `Dialogue`, `Chat` (read/send), `GrandExchange`, `Health` (core getters)

Shape:
```kotlin
object Bank {
    suspend fun open(nearest: Boolean = true): Boolean {
        RestrictionGate.check(BankAccess)
        Events.emit(ServiceCallStart("Bank.open"))
        val result = VitalBank.open(nearest)
        Events.emit(ServiceCallEnd("Bank.open", result))
        Confidence.onBankInteraction(result)
        return result
    }
}
```

### Custom services (BuildCore-owned logic)

1. **`MuleService` interface** — talks to BuildCore-Server / VitalExchange-Server. `NullMuleService` default. Adapter pattern for real impls.
2. **`WildernessThreatAnalyzer`** — consumes hot-rules data for PK detection patterns
3. **`TradeSafetyFilter`** — rate-limited auto-decline, manifest verification for expected trades
4. **`ChatSafetyListener`** — orchestrates `onJagexModContact` (authoritative flag only), `onTradeRequest`, pattern detection
5. **`FoodPolicy` engine** — eat cheapest-that-doesn't-waste, save high-healing for emergencies
6. **`GearLoadout`** switching helper
7. **`StaminaPolicy`** energy/stamina orchestration
8. **`TeleportPlanner`** — shortest path via available teleports in inventory/spells
9. **`HotRulesClient`** — shared fetch/cache/verify for rules data

### Key service integrations

- **Walker** wraps VitalPlugins/walker plugin (already exists)
- **Login** wraps VitalPlugins/autologin plugin (already exists)
- **Login hop** uses standard in-game world hops, never Guester's disconnect-packet technique
- **Trade** default disabled at account level; auto-decline all with rate-limiting
- **Mod contact** uses only authoritative Jagex-mod flag from VitalAPI, never chat-text heuristics

### Cross-cutting invariants

1. Every service method emits `service.call.start` and `service.call.end` events
2. Every method is `suspend` (Kotlin coroutines)
3. Every method has sane defaults (retries, timeouts, idle waits)
4. No service newed up by tasks — singletons or injected via `TaskContext`
5. Confidence Layer hooks on every call
6. Restriction gates are first-line

---

## 11. Confidence, Watchdog, Recovery

### Three cooperating subsystems

1. **Confidence Layer** — computes current confidence score; gates high-stakes actions
2. **Watchdog** — monitors stall + confidence + deadlock + precision leak
3. **Recovery Pipeline** — deterministic steps to return to known state

### Confidence score

Scalar in `[0.0, 1.0]` from 8 weighted signals:

```kotlin
enum class Signal {
    INTERFACE_KNOWN,
    LAST_ACTION_RESULTED,
    EXPECTED_ENTITIES_VISIBLE,
    POSITION_REASONABLE,
    HP_NORMAL,
    INVENTORY_DELTA_EXPECTED,
    NO_UNEXPECTED_DIALOG,
    RECENT_CHAT_NORMAL
}
```

Recomputed after every service call. Cached ~600ms. Published on event bus.

### Action-stakes gating

```kotlin
enum class ActionStakes {
    READ_ONLY,  // 0.0
    LOW,        // 0.4 — movement, camera
    MEDIUM,     // 0.6 — inventory ops, typical clicks
    HIGH,       // 0.8 — combat engage, GE submit, teleport
    CRITICAL    // 0.9 — trade accept, quest-final-click, irreversible
}
```

If confidence below threshold at action time → task enters RECOVERING.

### Watchdog checks

1. Progress fingerprint unchanged for 5min default (per-task override) → STALL
2. Confidence < 0.4 sustained 30s → UNCERTAIN
3. Runner thread heartbeat missed > 15s → DEADLOCK
4. Precision mode exceeded declared duration → LEAK

Runs on dedicated thread (deadlocked runner can't disable its own watchdog).

### State snapshot format

```kotlin
data class StateSnapshot(
    val timestamp: Instant,
    val sessionId: UUID,
    val taskInstanceId: UUID,
    val trigger: SnapshotTrigger,
    val player: PlayerSnapshot,
    val inventory: List<ItemSnapshot>,
    val equipment: Map<EquipmentSlot, ItemSnapshot>,
    val skills: Map<Skill, SkillSnapshot>,
    val openInterface: InterfaceSnapshot?,
    val recentChat: List<ChatMessage>,
    val nearbyEntities: EntitiesSnapshot,
    val activeQuestProgress: Map<QuestId, Int>?,
    val confidence: Confidence,
    val recentEvents: List<BusEvent>,
    val replaySeed: Long,
    val screenshot: Sha256Hash?   // only in diagnostic mode
)
```

### Recovery Pipeline (7 steps, stops at first success)

1. **Dismiss unexpected dialogs** — try known-safe options, ESC
2. **Close all interfaces** — return to bare game world
3. **Verify login state** — re-login if logged out
4. **Verify reasonable position** — teleport home if misplaced
5. **Hot-rules consult** — server-published recovery recipes matched against state hash
6. **Task custom recovery** — `task.onUnknownState(ctx, snapshot)` hook
7. **Give up** → task FAILED, retry policy applies

Budget: 90s total. If exceeded, escalate to FAILED.

### Hot-rules recovery recipe format

```json
{
  "version": 42,
  "patterns": [
    {
      "id": "random-event-strange-plant",
      "match": { "npcInDialog": "Strange Plant" },
      "recipe": [
        {"action": "dialogue.continueUntilOption"},
        {"action": "dialogue.chooseOption", "args": {"matcher": "Yes"}},
        {"action": "dialogue.close"}
      ]
    }
  ]
}
```

Fleet-wide hot-fixes without JAR releases.

### Weaponization resistance

- Incoming trade request → auto-decline + rate-limit. Never triggers recovery.
- Chat claiming to be a mod → ignored (authoritative flag only)
- Wilderness aggressive player → threat-level; only IMMINENT triggers flee
- Recovery entry always internal signals, never external input

---

## 12. Configuration & Profile System

### Profile vs Plan

- **Profile** = account-specific (username → personality RNG, archetype, restrictions, credentials, mule config, license link). NOT shareable.
- **Plan** = reusable recipe (task queue, per-task config, predicates, priorities, break schedule). Sharable freely — zero account-specific data.

### On-disk layout

```
data/buildcore/
├── profiles/<username>/
│   ├── profile.json
│   ├── credentials.enc        (DPAPI-encrypted)
│   ├── personality.json
│   ├── state.json
│   └── current-plan-ref.json
├── plans/
│   ├── <slug>.plan.json
│   └── imported/
│       └── <sha-hash>.plan.json
├── personalities/<account-uuid>.json
├── replays/<session-uuid>.jsonl
├── rules-cache/
│   ├── quests/
│   ├── item-map.json
│   └── failure-patterns.json
├── logs/buildcore.log
├── blobs/<sha256>/...
└── server/
    ├── license.enc
    └── manifest.json
```

### Plan JSON schema (v1)

```json
{
  "schemaVersion": 1,
  "plan": {
    "id": "pure-to-99-range",
    "displayName": "1def Pure to 99 Ranged",
    "requiredArchetype": "pure.1def",
    "mode": "DYNAMIC",
    "tasks": [
      {
        "taskId": "quest.waterfall",
        "criticality": "CRITICAL_PATH",
        "dependsOn": [],
        "taskConfig": {},
        "selectedMethods": [
          {"methodId": "quest.waterfall.standard", "weight": 1.0, "methodConfig": {}}
        ],
        "completionPredicate": {"type": "QUEST_COMPLETE", "args": {"questId": "waterfall_quest"}},
        "retry": {"maxAttempts": 3, "backoff": "EXPONENTIAL", "onExhausted": "SAFE_STOP"}
      }
    ],
    "breakSchedule": {
      "microEnabled": true,
      "normalIntervalMinutes": [30, 90],
      "bedtimeHoursLocal": [23, 7],
      "bankingEnabled": true
    }
  },
  "signature": {"method": "none", "value": null}
}
```

### Schema migration

All three artifacts (Profile, Plan, Personality) carry `schemaVersion`. On load, chain of migrations advances old versions. Migrations additive-compatible where possible.

### Credentials storage

Windows DPAPI via `CredentialStore` facade. Per-user-per-machine encryption. Moving a profile between machines requires re-entering credentials — this is intended safety.

### Plan sharing (clipboard)

- **Export:** JSON → base64 → prefixed with `BC1:` → clipboard
- **Import:** detect prefix → decode → validate against schema → validate against profile's archetype → save to `plans/imported/<hash>.plan.json`

Safety: imported plans reference tasks/methods by string ID only. Unregistered IDs → clear import error. No code execution on import.

### CLI launch modes

```
# With GUI (default)
java -jar buildcore.jar

# Preselect profile
java -jar buildcore.jar --profile="MyAccount"

# Headless
java -jar buildcore.jar --profile="MyAccount" --plan="pure-to-99-range" --headless
```

Precedence: CLI args → env vars (`BUILDCORE_*`) → bootstrap config last-used → GUI interactive.

### Validation (Profile + Plan)

- Schema version + migrate
- Archetype resolves?
- Additional restrictions compose legally?
- Personality valid?
- License cache valid or grace unexpired?
- Every taskId + methodId resolves to a registered module?
- Every method compatible with profile restrictions?
- No circular dependsOn?
- Completion predicates valid?

Failures surface as actionable GUI errors or non-zero exit codes with stderr messages in headless.

### Resume

- Clean shutdown writes `state.json` with last active task + sub-state hint
- Next launch prompts "Resume last session?" if profile + plan + reasonable time gap
- Resume is advisory; task's own resume logic is authoritative

---

## 13. Logging & Telemetry

### Principles

1. Structured JSON events from day 1
2. One event bus, many subscribers
3. Correlation IDs on everything
4. Privacy-first default, diagnostic-mode for depth
5. Rate-limited high-volume categories

### Event taxonomy (closed, enumerated — ~50 types)

**Session lifecycle:** SessionStart, SessionEnd, SessionSummary
**Task lifecycle:** TaskQueued, TaskValidated, TaskStarted, TaskProgress, TaskCompleted, TaskFailed, TaskRetrying, TaskSkipped, TaskPaused, TaskResumed
**Method / Path:** MethodPicked, PathPicked
**Service calls:** ServiceCallStart, ServiceCallEnd
**Input:** InputAction (never shipped baseline, diagnostic only)
**Antiban:** PrecisionModeEntered, PrecisionModeExited, BreakAttempted, BreakDeferred, BreakStarted, BreakEnded, FatigueUpdated
**Confidence / Watchdog:** ConfidenceUpdated, StallDetected, UncertaintyDetected, RecoveryStarted, RecoveryStepRan, RecoverySucceeded, RecoveryFailed, StateSnapshotCaptured
**Safety:** JagexModContact, TradeRequestDeclined, WildernessThreatDetected, PkerFleeTriggered
**Safe stop:** SafeStopRequested, SafeStopCompleted
**Licensing / infra:** LicenseValidated, LicenseRevoked, UpdateAvailable, ConfigReloaded, ServerConnectionUp, ServerConnectionDown
**Errors:** UnhandledException, ValidationFailed, RestrictionViolated
**User actions:** UserStartedPlan, UserStoppedPlan, UserEditedPlan
**Performance:** PerformanceSample

Every event is a Kotlin data class with named fields. No free-form blobs. Closed taxonomy.

### Subscribers

| Subscriber | Events |
|---|---|
| LocalJsonlWriter | ALL |
| LocalSummaryWriter | Task lifecycle + Safety + Session |
| TelemetryClient | Filtered per consent + rate limits |
| ReplayRecorder | ALL + RNG state |
| GuiLiveStatus | Task lifecycle + Progress + Confidence + Breaks |
| WatchdogSubscriber | Progress + ConfidenceUpdated + heartbeats |
| PerformanceAggregator | PerformanceSample |
| FatigueModel | SessionStart + TaskCompleted + BreakEnded |

### Telemetry shipping matrix

| Event | Baseline? | Diagnostic? | Endpoint |
|---|---|---|---|
| Session* | yes | yes | `/telemetry/session-summary` |
| TaskFailed, TaskSkipped | yes | yes | `/telemetry/task-failure` |
| StallDetected, RecoveryFailed | yes | yes | `/telemetry/task-failure` |
| JagexModContact | yes (priority, immediate) | yes | WS |
| UnhandledException | yes | yes | WS |
| PerformanceSample | yes (5min avg) | yes (per-sample) | `/telemetry/event-batch` |
| TaskStarted, TaskCompleted, MethodPicked | yes (aggregated) | yes (individual) | `/telemetry/event-batch` |
| TaskProgress, ConfidenceUpdated | GUI only | yes | `/telemetry/event-batch` |
| InputAction | **never** | sampled 10% | `/telemetry/event-batch` |
| ServiceCallStart/End | no | sampled 10% | `/telemetry/event-batch` |

### Rate limiting

- ConfidenceUpdated: change-debounce (>0.1 delta or 5s)
- InputAction in diagnostic: 10% sampled
- ServiceCall*: 10% sampled diagnostic
- PerformanceSample: always 5-min buckets

Local disk gets everything; telemetry samples the high-volume.

### Privacy scrubbing (pre-transmit, unit-tested)

| Field | Baseline | Diagnostic |
|---|---|---|
| username | HMAC-SHA256 hashed | hashed |
| password | never | never |
| IP | never | never (server sees via TCP) |
| chat content | never | never (classification flags only) |
| player names | hashed | full in PKer-flee only |
| RNG seeds | yes | yes |
| screenshots | no | yes if captured |

### Log levels (human-readable)

DEBUG / INFO / WARN / ERROR / FATAL. CLI `--log-level=DEBUG`. GUI toggle. Separate from baseline/diagnostic telemetry flag.

### Correlation IDs

Every event carries `eventId`, `sessionId`, `taskInstanceId` (nullable), `moduleId`.

### Performance contract

- Bus is `MutableSharedFlow` with replay buffer 0
- Fast-path subscribers synchronous on one dedicated logger thread
- Slow subscribers (telemetry, replay) bounded queues with overflow tombstones

---

## 14. Licensing, Identity, Updates

> **Section 14 is provisional** — subject to revision when VitalClient's auth model is defined. The primitives (signed `LicenseBlob`, DPAPI storage, WebSocket client, entitlements single-source-of-truth, signature verification) are architecturally stable. What may flex: identity anchor (Discord vs VitalClient-native), shared vs federated auth infrastructure, shared credential store.

### First-launch flow

```
1. User runs BuildCore first time (no license.enc)
2. GUI: "Link your Discord" → [Open Discord Login]
3. Browser redirect to /auth/discord/start
4. User authorizes; redirect to token display page
5. User pastes token into GUI
6. Client POST /auth/validate → signed LicenseBlob
7. Verify sig, write DPAPI-encrypted license.enc
8. Connect WebSocket /ws/client
9. Unlock GUI
```

### Subsequent launches

```
1. Load + decrypt license.enc
2. Grace unexpired? → use cached, background-refresh
3. Grace expired → /auth/refresh
     success → extend, save, continue
     network failure → soft lockout with retry banner
     server says revoked → hard lockout
4. POST /sessions/start → UUID or 409 (tier cap)
5. Start 60s heartbeat loop
6. Connect WebSocket
```

### LicenseBlob shape

```kotlin
data class LicenseBlob(
    val discordId: String,
    val userId: UUID,
    val tier: LicenseTier,
    val entitlements: Set<Entitlement>,
    val issuedAt: Instant,
    val graceExpiresAt: Instant,
    val serverSignature: String
)
enum class LicenseTier { ADMIN, TESTER, TRIAL, SUBSCRIBER, EXPIRED }
sealed class Entitlement {
    object AllFeatures : Entitlement()
    data class Module(val id: ModuleId) : Entitlement()
    data class ConcurrentCap(val max: Int) : Entitlement()
    object DiagnosticMode : Entitlement()
    object PrecisionModeUnlimited : Entitlement()
}
```

### Tier concurrent-cap defaults

- ADMIN: unlimited
- TESTER: 10
- TRIAL: 1
- SUBSCRIBER: sub-tier-defined (Basic=3, Pro=10)
- EXPIRED: 0

Enforcement server-side in `POST /sessions/start`; client shows clear message on 409.

### Entitlement enforcement pattern

Single source of truth — `Entitlements` object. Two patterns:
- **Static check** — GUI hides unavailable modules at edit time
- **Runtime check** — `runTask()` throws `EntitlementViolation` if not entitled

Architecture-test-enforced: no sprinkled `if tier == ...` elsewhere.

### WebSocket client

- Coroutine-scoped
- Auth with LicenseBlob first message
- Subscribes to `license.revoked`, `config.reload`, `update.available`, `admin.message`
- Reconnect exponential (1s, 2s, 4s, max 60s)
- On disconnect N attempts, escalate heartbeat to 15s

### Update client

Runs at launch only (no mid-session).

```
1. license.tier → channel:
     ADMIN → Nightly
     TESTER → Beta
     TRIAL/SUB → Stable
2. GET /updates/manifest?channel=X → manifest
3. If rolledBackTo: targetVersion = that (not latest)
4. If targetVersion != current:
     Show "Update available"
     On confirm: download jar → verify sha256 → verify signature → atomic replace → restart
5. If currentVersion flagged bad: force update before proceeding
```

### Version mismatch safety

Each `Task` module declares `minCoreVersion` / `maxCoreVersion`. Validator at task-start checks; mismatched task marked INCOMPATIBLE, skipped (critical-path → safe-stop with clear reason). Never mid-session kill.

### Revocation — client flow

```
WS: {type: "license.revoked", reason: "..."}
  ↓
Emit LicenseRevoked event
Invoke SafeStop.requested("license revoked: <reason>")
Runner STOPPING; tasks' safeStopSteps() execute
After safe stop: session ends, license.enc deleted, GUI locked with message
Client stays alive; "link new license" available
```

Never force-close. Offline clients drain at next check-in.

### Signed artifacts

Three things verified with embedded public key:
1. License blob
2. Rules data (every `/rules/*` response)
3. JAR updates

Single public key embedded at compile time. Key rotation is a breaking change handled via update machinery.

### Offline behavior

| Scenario | What works | What doesn't |
|---|---|---|
| Grace unexpired, WS up | everything | — |
| Grace unexpired, WS down | almost all | revocation delayed |
| Grace expiring soon | aggressive refresh | — |
| Grace expired, server unreachable | running session 1h post-expiry | no new sessions |
| Grace expired, revoked | — | locked out |
| Fresh install, no network | — | cannot activate |

---

## 15. GUI Shell

### Window shape

- Min 1280×800, default 1440×900
- FlatLaf IntelliJ Darcula + BuildCore accent
- Custom title bar with extended window style
- Per-profile windows (one BuildCore instance per account)

### Top-level layout

Left sidebar → tab switcher (9 tabs). Main content pane. Status bar always visible at bottom.

### Tabs

1. **Overview** — live dashboard: current task + ETA, queue preview, live metrics (confidence, next break, XP/hr), event tail
2. **Plan Editor** — task queue (drag-reorder), per-task config via `ConfigSchemaRenderer`, task picker with hide-incompatible
3. **Profile** — archetype, restrictions (inherited + additional), mule config, credentials, fresh-account throttle toggle
4. **Antiban** — personality vector viewer (read-only), break schedule config
5. **Modules** — installed modules with versions, tasks, entitlement status
6. **Logs** — filterable structured event viewer, detail panel, export + ship-as-bug-report
7. **Replay** — recent sessions list, open/ship/delete, timeline scrubber + state-at-cursor
8. **Settings** — update channel (tier-capped), telemetry consent, log level, theme + accent
9. **Diagnostics** — version info, license status, server + WS status, "report bug" button

### ConfigSchemaRenderer

One renderer walks any `ConfigSchema` → Swing form. Task modules never write Swing.

- `IntRange` → `JSpinner`
- `Toggle` → `JCheckBox`
- `ItemPicker` → filtered searchable `JComboBox`
- `LocationPicker` → combo + "pick on map" dialog
- `Enum` → `JComboBox`
- `CompletionPredicate` → templated form
- `VisibleWhen` → dependent visibility

### Cross-cutting contracts

- **Errors** — actionable GUI banners/dialogs with copy-to-clipboard
- **Empty states** — clear CTAs, never silent
- **Hot-reload** — profile/plan changes surface warnings for incompatibilities
- **License revoked** — GUI locks with banner (session continues to safe-stop first)
- **Update available** — non-blocking toast, install at next launch
- **Accessibility** — keyboard-navigable, font scale respects system, color + text labels (no color-alone signaling)

### Clipboard plan sharing

Plan Editor has Export → `BC1:<base64>` to clipboard. Paste → validate → save to `plans/imported/<hash>`.

### Headless mode

CLI `--profile=X --plan=Y --headless` skips GUI entirely.

---

## 16. Architecture Tests Catalog

Consolidated list of compile-time / build-time enforcement rules. Implemented with Konsist or ArchUnit.

### Layering
- No class outside `core.services.*` may import `net.vital.api.*` directly
- No class outside `core.input.*` may import `net.vital.api.Mouse`/`Keyboard`/`Camera`
- No task module may call `Mouse.click()` etc. directly (must go through services)

### Task SPI
- Every `Method` must have exactly one path with `kind = IRONMAN`
- Ironman path has no `gatingRestrictions`
- Method IDs globally unique across all modules
- Every `Task` subclass either overrides `canStopNow()` or is tagged `@UsesDefaultSafeStop`
- Every `Task` ships a validation test + resume-from-state test
- No static mutable state in tasks
- No direct file I/O from task code

### Restriction engine
- Every `Effect` expressible in the taxonomy; unmatchable effect is build-time error
- Archetypes composed from `Restriction` taxonomy only (no free-form)

### Antiban
- Every input call emits a bus event
- `PersonalityVector` fields all covered by `PersonalityRng.generate()`
- `SessionRng` cannot be constructed outside `core.rng.*`
- Only `@UsesPrecisionInput`-annotated method bodies may call `Antiban.withMode(PRECISION, ...)`

### Services
- Every service method is `suspend`
- Every service method emits start + end events
- Services may not hold mutable state beyond derivable game state + session config

### Confidence / watchdog
- Every service call must compute confidence afterward
- Recovery pipeline must not call restricted-economy services (no GE inside recovery)
- Watchdog must run on dedicated thread

### Logging / telemetry
- No subscriber blocks the emitting thread
- No event contains unboxed mutable state (must be immutable data class)
- Privacy scrubber covers every `BusEvent` subtype (compile-time check)
- Bus is `MutableSharedFlow` with replay buffer 0

### Licensing
- All license/entitlement branches route through `Entitlements`
- No path ships license/credentials over unencrypted transport
- No path bypasses signature verification for rules or updates

### Config / plan
- Plan loading must not depend on any Profile-specific field
- Plan JSON roundtrip equality (serialize → deserialize → serialize = identical)
- All schema versions have migration paths from v1 forward

---

## 17. Open Questions & Provisional Items

### Provisional pending VitalClient auth model decisions

- Identity anchor (Discord OAuth vs VitalClient-native auth)
- Whether BuildCore-Server federates with a VitalClient license server or stands alone
- Whether credential storage is DPAPI-direct or delegated to VitalClient's credential store
- 48h grace period may change

### Deliberate deferrals (with schema/hook in place)

- HCIM archetype (schema present, flag disabled)
- Mule-integration adapter to VitalExchange-Server (interface + NullMuleService ship; real adapter later)
- Signed plans (signature field reserved, no signing implementation in v1)
- Hard anti-piracy / obfuscation (pending VitalClient's security posture)
- Cross-platform support (Windows only v1)
- Machine-learned confidence (explicit signals only in foundation)
- Predictive stall detection (reactive only)
- Cloud profile sync
- Community plan browser in-app
- In-app embedded docs (tooltips only v1)

### Non-goals (explicitly out of scope)

- Activity modules (each is its own subproject)
- Account progression orchestrator
- Account warmup system
- Fleet coordination
- Web planner
- Licensing-for-sale commercial specifics (subject to VitalClient auth)

---

## Document End

**Next step:** writing-plans skill invoked to turn this spec into a phased implementation plan. Every section above is input to that plan.
