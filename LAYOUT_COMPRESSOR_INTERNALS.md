# DIYLC Layout Compressor — code internals

Developer reference for the `layout-compressor` branch, written so a new contributor (or agent
session) can work on the code without re-deriving it from source. Companion documents:
`LAYOUT_COMPRESSOR_DESIGN.md` (the plan and milestones — source of truth for *what* gets built)
and the session handoff artifact (current status and open bugs). This file describes *how the
code works* as of M4a plus the body-overlap fix.

## The one-paragraph mental model

The compressor turns a sprawling DIYLC project into a compact perfboard layout in a single
undoable edit. It extracts nets from geometry, throws away all wiring and boards, re-places the
real parts on a 0.1″ hole lattice, routes every net as bare underside runs (insulated red
top-side jumpers only when unavoidable), improves the placement with a randomized local-search
loop, emits DIYLC components back, and only commits to the real project after proving the
netlist is byte-for-byte equivalent. Everything runs on a scratch clone; a failure of any kind
leaves the user's project untouched.

## Where things live

Engine (all logic, no UI) — `diylc/diylc-library/src/main/java/org/diylc/editor/compressor/`:

| Class | One-liner |
|---|---|
| `LayoutCompressor` | The pipeline; `IProjectEditor` invoked via `applyEditor`. Owns the verification gate. |
| `ComponentClassifier` | Real parts vs connectivity-only vs boards vs decorations. |
| `NetExtractor` | Builds nets from every sticky pin of the real parts (never raw `extractNetlists`). |
| `GridModel` | 0.1″ lattice world model: pin/body/wire occupancy, claims, snapshots. |
| `Footprint` | Per-component grid shape: pin offsets, stretchable/rotatable flags, body extents. |
| `PlacementSeeder` | Scales original positions onto the lattice; defines the `Placement` record. |
| `Legalizer` | Resolves seeded overlaps: largest-first spiral search; `occupy` claims cells. |
| `Router` | 8-direction A* per net, multi-terminal trees, jumper fallback, rip-up ≤ 3 nets. |
| `RoutedNet`, `RoutingResult` | Result containers: per-net runs, jumpers, failed pins; totals. |
| `CompressionState` | The loop's world: placements + nets + routing, try/undo moves, cost. |
| `CompressionLoop` | Seeded simulated-annealing loop (`run`) plus a deterministic greedy squeeze (`compact`). |
| `LayoutEmitter` | Writes placements back to components; emits traces/jumpers/PerfBoard. |
| `ContinuityScanner` | Headless draw pass to recompute continuity areas for verification. |
| `CompressionSurvey` | M0/M1 stats for the preview dialog; not part of the compress pipeline. |

UI — `diylc/diylc-swing/src/main/java/org/diylc/swing/plugins/compressor/`: menu plugin +
`CompressAction` ("Edit → Compress Layout"), which runs `LayoutCompressor.prepare(...)` in a
background task behind `CompressProgressDialog` (phase label + progress bar; **Finish Now**
stops the loop keeping the best state, **Cancel**/close aborts via the abort monitor —
`CancelledException`, project untouched), then commits via `plugInPort.applyEditor(...)` on the
EDT and shows a stats summary. Registered with 2 lines in `MainFrame.java`. The only other upstream touches:
~11 lines in `NetlistBuilder.java` and the public `getBodyShapeBounds()` accessor on
`AbstractLeadedComponent` (body geometry for footprints).

Tests: `diylc-library` test tree mirrors the engine classes one-to-one;
`CompressorSmokeTest` lives in the diylc-swing test tree (needs a display).

## Units and coordinates

- In-memory points are pixels at **200 px/inch** (`Constants.PIXELS_PER_INCH`); the `.diy` XML
  stores inches. One lattice cell = 0.1″ = **20 px** (`GridModel.CELL_SIZE_PX`).
- `GridModel.SNAP_TOLERANCE_PX = 4` — the same epsilon `NetlistBuilder` uses to consider two
  control points connected. On-grid = within 4 px of a lattice point per axis.
- `Cell(col, row)` are integers, unbounded, but **placement never goes negative** (fits checks
  reject `col < 0 || row < 0`); the board is later shrink-wrapped around whatever is occupied.
- Quarter-turn rotation math: one clockwise turn maps `Cell(col, row) → Cell(-row, col)`.
  Direction `1` in `IComponentTransformer.rotate` is clockwise in screen coordinates and matches
  this convention.

## GridModel — the occupancy model

Three independent claim layers, all keyed by `Cell`:

1. **Pins** — `Map<Cell, List<Pin>>` (`Pin` = component + control point index). Several pins may
   share a cell (that's how parts connect). `pinNets` maps a pin cell → net id once routing tags
   it; a cell's tag is dropped when its last pin is vacated.
2. **Bodies** — `Map<Cell, Set<IDIYComponent<?>>>`. A `Set`, so double-claiming the same cell by
   the same component is idempotent and `vacate(component)` removes everything cleanly.
3. **Wires** — `wireEdges: Map<Edge, Integer>` (lattice edges, normalized ordering via
   `Edge.between`) and `wireHoles: Map<Cell, Integer>` (every hole a run passes), both by net id.
   Two diagonals of one grid square physically cross, so `canUseEdge` also checks the crossing
   diagonal (`wireCrossingNetAt`).

Key operations: `occupyPin` / `occupyBody` / `vacate(component)` (pins + bodies of one
component; wires untouched), `claimRun(netId, path)` / `releaseNet(netId)`,
`snapshotWires()` / `restoreWires` (full-copy undo for rip-up), `occupiedBounds()` (bounding box
over all three layers — this is what the board wraps and what the cost function measures).

`isFree(cell)` = no pin, no body, no wire hole. The Legalizer uses it (wires don't exist yet at
legalization time); the loop's `CompressionState.fits` deliberately checks only pins + bodies —
wires never block placement, they get re-routed.

## Footprint — what a component looks like on the lattice

Built by `Footprint.of(component, bodyBoundsPx)`:

- **Pins** = sticky control points; offsets in cells relative to pin 0, `Math.round`-snapped.
  If any offset misses the lattice by > 4 px, the part is **off-grid** — except stretchable
  parts, which are always on-grid (the placer picks their span anyway).
- **Stretchable** = `instanceof AbstractLeadedComponent` with exactly 2 sticky pins (resistors,
  diodes, caps, inductors…). Their span is a placement variable, not a footprint constant.
- **Rotatable** = stretchable, or the component type's `IComponentTransformer.canRotate` says so.
- **Body extents** — two representations:
  - *Stretchable parts*: `bodyLengthCells` × `bodyWidthCells` (fractional cells) taken from
    `AbstractLeadedComponent.getBodyShapeBounds()` — the exact body rectangle the renderer
    draws, **centered at the pin midpoint**, length along the lead axis. The drawn outline is
    *not* used: it includes the leads and would wildly overestimate. The body keeps this size
    whatever span the placer picks (matches DIYLC rendering: leads stretch, bodies don't).
  - *Fixed parts*: a single `bodyCells` rectangle relative to pin 0, derived from the drawn
    outline bounds the caller passes in (`LayoutCompressor` maps them from the original
    project's `ComponentArea.getOutlineArea()` by component list index).
- **Hole-covering rule** (`Footprint.coveredCells`): a body claims every hole whose 0.1″ square
  cell region it intersects — *not* just holes under the body — so two bodies that physically
  overlap are guaranteed to share a claimed hole. Bodies are first shrunk by
  `BODY_TOLERANCE_CELLS = 0.15` (3 px = 0.015″) per side so nominal sizes, border strokes and
  `getClosestOdd` rounding don't count as intrusion; a standard 0.125″-wide resistor therefore
  still occupies a single row and adjacent-row packing stays legal. This rule is the fix for
  the radial body-overlap bug — don't weaken it back to "holes inside the bounds".

## Placement — one part's spot

`PlacementSeeder.Placement(footprint, reference, quarterTurns, span)` (record):

- `reference` = absolute cell of pin 0. `span` only meaningful for stretchable parts (cells
  between the two pins along the axis); fixed parts carry `span = 0`.
- `pinCells()` — stretchable: `(0,0)` and `(span,0)` rotated; fixed: `rotatedOffsets`.
- `bodyCells()` — returns a **list of rectangles** (absolute cells):
  - stretchable: the lead strip `(1, 0, span-2, 0)` when `span ≥ 2`, **plus** the physical body
    rectangle centered at `(span/2, 0)` via `coveredCells` when the footprint knows its body.
  - fixed: the footprint's single body rectangle, rotated and translated.
  Rectangles may overlap each other and the pin cells; consumers handle that (occupy skips pin
  cells; the grid's body sets dedupe).

## Pipeline (`LayoutCompressor.prepare` + `edit`) step by step

The pipeline is split for the UI: `prepare(project)` does steps 1–10 (everything heavy, safe
off the EDT — it never mutates the project) and `edit(...)` only performs step 11's copy-back,
running `prepare` itself when the caller didn't (headless tools, tests). Hooks:
`setProgressListener((phase, fraction) -> ...)` reports pipeline progress (the loop dominates,
mapped to 0.15–0.90); `setAbortMonitor` aborts the whole run with `CancelledException` (polled
at phase boundaries and inside the loop); `setCancelMonitor` only stops the loop early, keeping
the best state.

1. **Classify + extract nets** on the *original* project, using the continuity areas the caller
   provides (the GUI passes the DrawingManager's; headless tools must draw first — see below).
2. **Clone** the project. A `toScratch` identity map (by list index) carries originals → clones,
   so net `Node`s can be re-pointed at clones. All mutation happens on the clone.
   *Why*: `Presenter.applyEditor`'s `finally` block commits partial mutations even when `edit`
   throws — throwing mid-edit is NOT a safe abort. Clone → verify → copy back is.
3. **Strip** connectivity components and boards from the clone.
4. **Footprints** for real parts; locked parts split off as fixed. Locked on-grid parts are
   pre-occupied in the grid as immovable obstacles; locked off-grid + off-grid parts become
   "remote" (stay in place, reached by flying wires).
5. **Seed** (`PlacementSeeder`): scale original pin-0 positions so the layout area ≈ total
   footprint area × `AREA_SLACK = 2.0`; original spans kept (span normalization is task #17).
6. **Legalize** (`Legalizer`): largest `cellArea` first; per part, spiral rings around the
   seeded spot (`MAX_RADIUS = 1000` safety net); at each candidate cell try original
   orientation/span first, then other rotations (if rotatable), then spans shrinking to 1 and
   growing to seeded+`MAX_SPAN_GROWTH = 2` (if stretchable). `Legalizer.occupy` is public
   static — `CompressionState` reuses it.
7. **Route** (`CompressionState.rerouteAll` → `Router.routeAll`) inside
   `occupiedBounds + ROUTING_MARGIN_CELLS = 2`.
8. **Improvement loop** (`CompressionLoop`): `LOOP_ITERATIONS = 2000`,
   `LOOP_TIME_BUDGET_MS = 15_000`, `LOOP_SEED = 42`; `setLoopIterations(0)` disables (pure M3).
9. **Emit** (`LayoutEmitter`), then **flying wires**: each remote pin to the nearest board cell
   of its net — always a net *pin* cell (wires only connect at endpoints), or pin-to-pin when
   the whole net is remote.
10. **Verification gate**: re-classify the emitted clone, re-scan continuity
    (`ContinuityScanner` — a real draw pass), re-extract nets, compare canonical forms
    (`Set<Set<"partIndex:pinIndex">>`, indices into the real-parts list). On mismatch: throw
    with a lost/new-nets diff; the real project was never touched. (`CompareService` was
    rejected for this: null node names make pins indistinguishable.)
11. **Copy back** components, locked set, groups into the real project; fill `Stats` (board
    cells, net count, wire length, jumper count, moved/remote/flying counts).

## Router internals

- **Search state is `(cell, incoming-direction)`** so turn penalties accumulate correctly.
  Costs: orthogonal 10, diagonal 14, turn penalty by angle `{0, 3, 5, 8, 10}` for
  {straight, 45°, 90°, 135°, 180°}. Heuristic: octile distance (admissible). All tie-breaks
  are explicit comparators → **fully deterministic**.
- A run may not: leave `bounds`, use an edge owned by a foreign net, cross a foreign diagonal,
  or pass a hole with a foreign pin or foreign run (bare wire shorts). Passing under bodies is
  allowed (runs are on the underside).
- **Multi-terminal nets** (`routeNet`): seed with one pin of the closest pair, then repeatedly
  connect the nearest remaining pin to the growing tree (cheap Steiner approximation). Pins
  that can't reach the tree go to `failedPins`.
- **`routeAll`**: tags every pin cell with its net id first, then routes nets ordered by
  half-perimeter (smallest first — least freedom to detour).
- **Rip-up** (`tryRipUpAndReroute`): for each failed pin, probe A* ignoring foreign *wires*
  (pins still block) to find the blocking nets; if ≤ `MAX_RIP_UP = 3`, snapshot wires, release
  the blockers, route this pin, re-route the blockers, and keep the outcome only if the
  blockers' jumper count didn't increase — otherwise restore the snapshot. Anything still
  failed becomes a top jumper (`resolveFailedPins`) — **routing never fails**, it degrades
  into jumper cost.
- **Jumper endpoints** (`resolveFailedPins` + `escapeHole`): a jumper end never sits in a pin
  hole (the component's lead occupies it) or under a body, and a hole hosts at most one jumper
  end. Pin-side ends *escape* to the nearest eligible free hole within
  `MAX_ESCAPE_RADIUS = 2` (biased toward the jumper's other end), tied to the pin by a claimed
  underside stub run — the emitter renders the stub as a normal CopperTrace and connectivity
  follows from the endpoint-breaking rule. Falls back to the pin cell itself only when no stub
  routes. The jumper *target* is chosen by `bestJumperTarget`: manhattan distance plus
  `CROSSING_BIAS = 8` per existing jumper the new wire would cross.
- **`rerouteNets(result, netPins, netIds)`**: incremental variant — releases and re-tags only
  the subset, but its rip-up may touch nets *outside* the subset; callers that need undo take a
  full wire snapshot (that's exactly what `CompressionState` does). The result's nets list is
  indexed by net id — keep it that way.

## CompressionState + CompressionLoop

`CompressionState` holds: `placements` (IdentityHashMap **plus `componentOrder` list — iterate
the list, never the map**, or determinism dies), nets as `List<List<PinRef>>`
(`PinRef(component, pinIndex)` — survives moves; cells resolved on demand via `cellOf`),
`fixedPins` for locked parts, and the current `RoutingResult`. Invariant: **the state is always
fully routed** between moves.

- `tryPlacement(candidate)`: vacate → `fits` (pins+bodies only; wires ignored) → occupy →
  incremental reroute of the component's own nets *plus any net whose run-hole a new pin
  displaced* → returns new cost. Returns `null` when the spot is taken (state restored,
  including pin-net re-tags via `retagPins`).
- `tryRerouteNet(netId)`: rip one net and route from scratch (rip-up included) — the mechanism
  behind REROUTE and UNJUMP moves.
- `undoMove()`: **single-level** undo of the last successful try — restores the moved placement
  (if any), the full wire snapshot, and a shallow copy of the nets list. One undo slot only;
  every `try*` overwrites it.
- `cost()` = `10·(bounds.width+1 + bounds.height+1) + 50·jumpers + 15·jumperCrossings +
  1·wireLength` (`AREA_WEIGHT`, `JUMPER_WEIGHT`, `CROSSING_WEIGHT`, `LENGTH_WEIGHT`).
  Crossings = pairs of jumper wires that touch or cross anywhere except a shared endpoint
  (`RoutingResult.getJumperCrossings` / `segmentsCross`).

`CompressionLoop.run(iterations, timeBudgetMs)`: **simulated annealing**, seeded `Random`; per
iteration one move — SLIDE ±1 cell (4⁄8 odds, the workhorse), ROTATE, STRETCH span ±1, REROUTE
random net, UNJUMP random jumpered net. Better states always accepted; worse with
`exp(-Δ/T)`, T cooling geometrically from 5% of the initial cost to 0.5 by **iteration**
progress (deterministic per seed; wall clock only stops early). The global best is snapshotted
(`CompressionState.snapshot()/restore()` — placements + wires + nets + pin retags) and
restored at the end, so timeout/cancel (`setCancelMonitor`) never lose progress. Defaults in
`LayoutCompressor`: 2000 iterations, 5 s budget, seed 42. SWAP move still deferred.

`CompressionLoop.compact()` — **deterministic greedy squeeze**, run once after `run()` in
`LayoutCompressor` (cost never increases, so it's a pure bonus pass). Fixes what annealing
alone tends to leave behind: a part sitting on the board's edge whose lone SLIDE move doesn't
pay for itself because a *neighbor* on the same edge is still holding the bounding box open —
the single-move cost is a tie or a loss, so the annealer never takes it, and the board reads
as if only some components moved in. Two move kinds per sweep, over all four edges of
`occupiedBounds()`:
  - **Pull** (`pullInward`): one part whose pins/body touch an edge hops up to
    `MAX_PULL_CELLS = 8` cells toward the center (skipping occupied spots in between); kept
    when the resulting cost doesn't worsen.
  - **Peel** (`peelEdge`): *every* part touching one edge moves inward **as one atomic step**
    — each mover just needs to fit (no per-mover cost check), nets on the vacated line are
    re-routed, and the whole edge's move is kept only if the **total** cost strictly drops,
    else the pre-peel snapshot is restored. This is what actually shrinks an edge shared by
    several parts — individually each one's move might be cost-neutral or worse, but clearing
    the whole line at once pays off.
  Sweeps (parts, then all 4 edges) repeat until a sweep makes no strict improvement 3 times
  running (`STALL_LIMIT`) or `MAX_COMPACT_SWEEPS = 30` is hit; the cancel monitor is checked
  between moves so "Finish Now" still returns the best state found. Corpus check (vs. loop-only
  baseline): aaa 21×25→20×25, LM386 23×25→20×25, Rix Pro Jr 60×34→60×33, Synth Oscillator
  jumpers 79→72 (board unchanged) — all still pass the verification gate.

## LayoutEmitter

- **Moving parts**: stretchable → set both lead points directly. Fixed → rotate via the
  component type's transformer around pin 0 (`direction=1` per turn), then rigid-translate all
  control points. **Never hand-rotate a fixed part by setting control points** — the
  transformer keeps the component's `orientation` property in sync with its geometry.
- **Wire emission**: underside runs become `CopperTrace`s, jumpers become solid red `Jumper`s.
  Runs are cut into straight segments that additionally **break at every connection cell** —
  every pin cell on the board plus every run/jumper endpoint of the same net — because DIYLC
  connects wires **only at endpoints** (4 px eps at control points). A trace passing over a pin
  mid-segment does *not* connect to it; this rule is why. Never emit geometry that relies on
  mid-wire contact.
- **Board**: `PerfBoard` wrapped `BOARD_MARGIN_CELLS = 1` beyond the occupied bounds, inserted
  at index 0 for z-order. Names are uniquified (`Trace1…`, `W1…`, `Board1`).

## Nets: extraction and classification

- `NetExtractor.extractNets` builds its own `Node` set from **every sticky pin of every real
  part regardless of node name**, then calls the low-level
  `NetlistBuilder.buildNetlist(components, nodes, continuityAreas, connections)`. Rationale:
  components like `PCBTerminalBlock` return null node names by design and the standard
  `extractNetlists` drops their nets as trivial. Verification uses the same rule — using
  different rules on the two sides would let a lost terminal-block connection pass the gate.
- `ComponentClassifier`: boards = `IBoard`; connectivity-only = `IContinuity` non-switch plus an
  explicit drawn-copper list (CopperTrace, CurvedTrace, GroundFill, SolderPad, Dot, Line,
  TraceCut, CutLine, MultimeterProbe); no-sticky-points = decorations; the rest are real parts.
  Terminal blocks, turrets, eyelets, solder lugs are deliberately **real parts** — they're
  physical hardware to place, not regenerable wiring.

## Sharp edges (read before touching anything)

1. **Continuity areas only exist after a draw pass.** Netlist extraction consumes them; any
   headless code must draw the project onto a throwaway image first (see `ContinuityScanner`).
   Also headless: configure `ProjectFileManager.configure(configurationManager.getSerializer())`
   **before** `ConfigurationManager.initialize("diylc")`, or component draws NPE
   nondeterministically.
2. **`--add-opens` flags are load-bearing** when running the app or tools on Java 21 — XStream
   can't deserialize projects with curved shapes without them (see `run-dev.cmd` / the handoff's
   `run-dev.sh` for the list).
3. **`applyEditor` commits partial mutations on exception** — mutate a clone, verify, then copy
   back. Never "abort" by throwing after touching the real project.
4. **Determinism**: seeded loop + deterministic router tie-breaks + `componentOrder` iteration.
   Any new iteration over an `IdentityHashMap` (or `HashSet` of components) that feeds the loop
   breaks reproducibility.
5. **Wires connect only at endpoints** (4 px). Junctions and pins must receive a wire
   *endpoint*; flying wires must target net *pin* cells, never mid-run tree cells.
6. **Body model**: outline bounds ≠ body for leaded parts (leads included). Use
   `getBodyShapeBounds()`. Bodies claim intruded holes minus the 0.15-cell tolerance.
7. **Undo is single-level** in `CompressionState`; don't chain two `try*` calls and expect to
   roll both back.
8. **`Router.rerouteNets` may change nets outside the requested subset** (rip-up); undo relies
   on the full wire snapshot, not on re-routing back.
9. **`mvn -pl diylc-library` needs `-am`** — the parent/core poms aren't in the local repo.

## Build, test, run

```
mvn -f diylc/pom.xml package -DskipTests          # → diylc/diylc-swing/target/diylc.jar
mvn -f diylc/pom.xml -pl diylc-library -am test   # engine tests (~150, all fast)
mvn -f diylc/pom.xml -pl diylc-swing -am test -Dtest=CompressorSmokeTest   # needs a display
```

Never launch the GUI from an agent session — build, then give the user in-app steps
("Edit → Compress Layout", check X, undo restores). Windows: `run-dev.cmd` at repo root;
permission rules match only commands *starting* with the binary — use `mvn -f` / `git -C`, no
`cd;`-prefixes, no pipes.

Headless verification tools (recreate in scratchpad as needed; compile against the jar, run
with `-Djava.awt.headless=true` + the add-opens flags, classpath `diylc.jar;<classes dir>`):
**CompressTool** (full compress on `.diy` files, prints board/wire/jumper stats; source in the
handoff artifact; `-Dloop.iterations=0` = pure M3 pipeline) and **OverlapCheck** (compresses,
redraws, intersects all real-part outline areas pairwise; overlap > 5 px in both axes = bug).

Test data: `aaa.diy` (repo parent dir; user edits it — check contents before suspecting code);
frozen copy at `diylc-swing/src/test/resources/compressor/traced-layout.diy`; regression corpus
at `diylc-regression-data/input/user-files/diy/` — references: *LM386 Voltage Converter*
(vero), *Rix Pro Jr* (point-to-point; **#18 net-merging repro**, fails the gate), *Synth
Oscllator_v1* (92 nets, the stress test).

## Current state and where work continues

Done through **M4b** (2026-07-15): M0–M3, M4a, body-overlap fix, jumper endpoint escaping
(free holes + stub traces, widened search + any-hole fallback), jumper-crossing penalty
(target bias 8 + cost 15/pair), **#17** natural-span normalization (radials → designed lead
spacing, axials → body length; `SPAN_WEIGHT = 3` deviation cost), **#18** fixed via
**pad-clearance halos** (`GridModel.claimPadHalo`: pins whose drawn pad radius +
`TRACE_CLEARANCE_PX = 6` exceeds a cell block neighboring holes for foreign runs — turret
pads at 16 px radius were touching adjacent-hole traces and merging nets in the continuity
rescan; radii come from `setCopperProvider`, the drawn continuity-positive areas), body-covered
jumper ends re-escape on moves, and the annealer. Corpus (vs loop-off baseline): aaa 28×13/1
jumper → 20×11/0; LM386 8 jumpers → 1; Rix Pro Jr **passes the gate** now, 12 jumpers → 3;
Synth flat at 5 s (needs placement work). M5 progress/cancel UX is done (prepare/edit split,
background task + `CompressProgressDialog`, wire z-order fix). Also done: `CompressionLoop.compact()`,
a deterministic post-annealing squeeze pass (pull + atomic edge-peel) that closes the gap
annealing alone leaves on boards where several parts share an edge — corpus: aaa 21×25→20×25,
LM386 23×25→20×25, Rix Pro Jr 60×34→60×33, Synth jumpers 79→72. Next: rest of **M5** (options —
time budget, wire colors, margin; §4.5 hardening; corpus-wide graceful degradation), **#19**
initial placement quality (edge terminals, bypass caps near power pins), optional
standing-mount mode, SWAP move if corpus says stuck. Git: branch `layout-compressor`; the fork is `origin` on the home Windows
machine and `fork` on the office Linux machine — **never push to bancika's repo** (named
`upstream` at home). Commit per substep, brief messages (subject + Co-Authored-By only).
