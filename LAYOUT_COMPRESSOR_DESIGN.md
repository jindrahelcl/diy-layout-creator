# Layout Compressor — Design Document

**Status:** draft, 2026-07-13
**Branch:** `layout-compressor`
**Goal:** a DIYLC plugin that takes an existing, electrically-correct but sprawling layout and
produces a compact perfboard layout: components packed on a 0.1″ grid, connections realized as
bare wire runs on the underside of the board, insulated jumper wires on top only where
unavoidable, and a `PerfBoard` sized to fit the result.

---

## 1. Problem statement

The input is a normal DIYLC project: real components (resistors, ICs, pots, …) connected by
arbitrary connectivity components (`HookupWire`, `Line`, `CopperTrace`, `Jumper`, …), typically
spread over a large canvas with no regard for physical board size. DIYLC derives electrical
connectivity purely from geometry (control points within `NetlistBuilder.eps` = 4 px count as
connected), so the input layout *is* a complete netlist specification.

The output is an electrically identical project in the perfboard idiom:

- real components snapped to a 0.1″ hole grid, packed densely;
- every net realized as chains of point-to-point wire runs between holes:
  - **underside runs**: bare wire, must never cross another underside run (they would short);
  - **top jumpers**: insulated wire, may cross anything, cost to be minimized;
- one `PerfBoard` component shrink-wrapped around the result;
- netlist before == netlist after (machine-verified).

## 2. Approach

Two-phase: a single global **normalization** step that crosses the representation gap
(free-form layout → routed grid layout), followed by **compression** via atomic
equivalence-preserving edits over always-valid states (local search / simulated annealing).
Routing is cheap at this scale (boards ≲ 40×30 holes, ≲ 100 nets; A* per net is microseconds),
so the real router runs inside the optimization loop as the evaluation function. A placement is
never "unroutable" — the top jumper is a universal fallback — so routing failure degrades into
cost, not infeasibility.

```
input project
   │  1. extract netlist          (plugInPort.extractNetlists(false))
   │  2. classify components      (real parts vs connectivity-only vs boards)
   │  3. seed placement           (shrink existing geometry onto grid — preserves
   │                               the author's grouping intuition)
   │  4. legalize + route all     (A* per net, rip-up & reroute, jumper fallback)
   ▼
valid routed layout  ──────────────────────────────┐
   │  5. compression loop: atomic edits            │ every state is a fully
   │     slide / swap / rotate / stretch /         │ routed, valid layout;
   │     reroute-net / jumper↔run                  │ anytime-stoppable
   │     cost = w₁·area + w₂·jumpers + w₃·length   │
   ▼ ──────────────────────────────────────────────┘
   │  6. shrink-wrap PerfBoard, emit components
   │  7. verify netlist equality (CompareService); abort+report on mismatch
   ▼
output project (single undoable edit)
```

## 3. Architecture

New code only, plus one registration line in `MainFrame`. Follows existing patterns
(`AnalyzeMenuPlugin` for the menu plugin, `FlexibleLeadsEditor` for editor logic living in
`diylc-library`).

### 3.1 Modules

**`diylc-library` — `org.diylc.editor.compressor`** (engine; depends on component classes):

| Class | Responsibility |
|---|---|
| `LayoutCompressor implements IProjectEditor` | Orchestrates the pipeline inside `edit(Project, selection)`; returns new selection. Invoked via `plugInPort.applyEditor(...)` → undo, repaint, dirty-flag handled by `Presenter` (`Presenter.applyEditor`, Presenter.java:2438). |
| `ComponentClassifier` | Splits `project.getComponents()` into: real parts, connectivity-only (`IContinuity` non-switch: wires/traces/jumpers), boards (`IBoard`/`AbstractBoard`), decorations (text, images — dropped or parked). |
| `GridModel` | The world model: 0.1″ lattice, occupancy per hole (component body / pin / underside run segment), net ids, coordinate conversion (grid ↔ inches; all DIYLC points are `Point2D` in inches). |
| `Footprint` | Per-component grid shape: pin offsets + body extent, derived from control points and body bounds; knows legal orientations (0/90/180/270) and, for 2-lead stretchable parts (`AbstractLeadedComponent`), min/max lead span. |
| `Router` | A* over hole-lattice edges for one net (multi-terminal via sequential Steiner-ish extension: route each terminal to the growing tree). Obstacles: pins of other nets, underside segments of other nets, component bodies only block via their pins (wire runs may pass under bodies between holes — configurable). Fallback: top jumper terminal-to-tree. Rip-up & reroute: on fallback, try ripping the blocking nets (bounded depth) before accepting a jumper. |
| `PlacementSeeder` | Initial placement: proportional shrink of original coordinates onto the grid + greedy legalization (nearest free slot, keep relative order). |
| `Compressor` | The optimization loop from §2 step 5: move catalogue, incremental re-route of affected nets, annealing schedule, time budget, best-state tracking. |
| `LayoutEmitter` | Converts final `GridModel` state back to DIYLC components: moves real parts (`setControlPoint`), creates `Jumper` components for runs (underside: dashed + dark color; top: solid red — constants, later configurable), creates `PerfBoard` (TwoPoints mode, 0.1″ spacing), orders the list board-first for z-order. |
| `CompressorResult` | Stats for the report dialog: board size, jumper count, wire length, verification verdict. |

**`diylc-swing` — `org.diylc.swing.plugins.compressor`**:

| Class | Responsibility |
|---|---|
| `CompressorPlugin implements IPlugIn` | Registers menu action(s) via `swingUI.injectMenuAction(...)`; registered in `MainFrame` next to the other `installPlugin` calls (MainFrame.java:129-142). |
| `CompressLayoutAction` | Runs the engine via `swingUI.executeBackgroundTask(ITask, true)` (off the EDT, progress UI), then applies via `applyEditor` on completion and shows the result summary. Cancel support: compressor checks an abort flag; best-so-far state is still applied on cancel (anytime property). |

### 3.2 Key framework facts this design relies on

- `IPlugInPort.extractNetlists(boolean includeSwitches)` — netlists straight from the presenter;
  each `Netlist` is a set of `Group`s of `Node`s (`Node` = component + control-point index).
- `CompareService.compare(Netlist, Netlist)` (org.diylc.plugins.compare) — existing
  netlist-diff machinery; reused verbatim for the final verification step.
- `IProjectEditor` + `Presenter.applyEditor` — single-shot undoable project mutation.
- `Jumper` — 2-point `IContinuity` leaded component with editable `Color` and
  `LineStyle` (SOLID/DASHED/DOTTED); our wire primitive for both sides.
- `PerfBoard` — `AbstractBoard` in TwoPoints mode with `spacing` property.
- Sticky-point rule: two control points are connected iff within 4 px → emitted wire endpoints
  must land *exactly* on the pin coordinates they connect to.

## 4. Algorithms

### 4.1 Net model

From `extractNetlists(false)` (switches in their default position; `includeSwitches=false`
matches what the Compare feature uses): each `Group` whose nodes span ≥ 2 real-part pins becomes
a routing net over those pins. Connectivity-only components inside the group are discarded —
they were only the carrier of the connection. Nets with pins on exactly one component
(e.g. internally-linked pins) need no routing.

**Caveat (found 2026-07-13 via manual testing):** some components deliberately return `null`
from `getControlPointNodeName` — e.g. `PCBTerminalBlock`, whose code comment says it "just
makes connections". Such pins produce no netlist nodes, and nets ending only at them are
dropped by `NetlistBuilder` as trivial single-node groups. The compressor must therefore build
its net model from its *own* node set — every sticky pin of every real part, regardless of node
name — via the lower-level `NetlistBuilder.buildNetlist(components, nodes, continuityAreas,
connections)` instead of consuming `extractNetlists` output. The verification step must use the
same node rule; otherwise a lost terminal-block connection would still pass netlist equality
(both sides drop it).

### 4.2 Placement seeding & legalization

Scale original component centroids by the ratio of (estimated required area / current bounding
box), snap to grid, resolve overlaps by spiral search for the nearest legal slot in original
relative order (largest components first). "Estimated required area" = Σ footprint areas × slack
factor (start 2.0). If legalization fails, grow the canvas and retry — never fail hard.

### 4.3 Router

- Graph: hole lattice; edges between orthogonally adjacent holes (diagonals off in v1).
- Edge cost: 1 per step + turn penalty (straighter runs are easier to solder) + light
  congestion penalty near foreign pins.
- Multi-terminal nets: sort terminals, route first pair with A*, then each remaining terminal
  to the nearest node of the existing tree (standard cheap Steiner approximation).
- Underside run segments claim lattice edges *and* holes they pass through (a bare wire
  touching a foreign pin hole would short) — except holes it merely passes are claimable by
  the same net only.
- Jumper fallback cost ≫ any plausible detour, so A* exhausts underside options first.
- Rip-up & reroute: when a terminal falls back to a jumper, identify nets whose segments block
  the shortest hypothetical path (run A* ignoring net obstacles, collect owners), rip up to K=3
  of them, route this net, re-route the ripped ones; keep whichever outcome has lower total cost.

### 4.4 Compression loop (atomic equivalence-preserving edits)

State = placement + complete routing (always valid). Moves:

| Move | Effect | Re-route scope |
|---|---|---|
| `SLIDE` | move one component ±1 hole (x or y) | its nets |
| `ROTATE` | rotate component 90° | its nets |
| `STRETCH` | change lead span of a 2-lead part ±1 hole | its nets |
| `SWAP` | exchange two similarly-sized components | both components' nets |
| `REROUTE` | rip one net and re-route from scratch | that net |
| `UNJUMP` | attempt to convert one top jumper to underside (with rip-up) | that net + ripped |

Acceptance: simulated annealing (accept worse states with probability `exp(-Δ/T)`),
geometric cooling, fixed wall-clock budget (default ~5 s, configurable), track global best.
Cost: `w_area·boardArea + w_jump·jumperCount + w_len·totalWireLength` with
`w_jump ≫ w_len` (defaults: area 10 per hole-column/row of bounding box, jumper 50, length 1;
tune in M4). Board area recomputed as the bounding box of everything + 1-hole margin.

### 4.5 Special cases

- **Off-grid parts** (pots, jacks, anything whose pin pitch isn't a 0.1″ multiple): phase 1
  keeps them stationary outside the board; their nets terminate at the nearest board hole and a
  top-side flying wire connects onward. Detected via footprint extraction (pin offsets not
  representable on the lattice).
- **Locked components / locked layers**: never moved; treated as fixed obstacles if on-grid.
- **Groups (`project.getGroups()`)**: v1 dissolves groups of connectivity components; groups of
  real parts move rigidly as one footprint.
- **Multiple disconnected subcircuits**: packed independently, then shelf-packed side by side
  on the shared board.
- **Text/labels**: component labels travel with components automatically. Free-floating `Label`
  components are parked below the board, untouched.

## 5. Verification & testing

1. **Built-in check (always on):** after emit, run `NetlistBuilder.extractNetlists` on the
   candidate result and `CompareService.compare` against the input netlist *before* touching the
   real project. On mismatch: abort, keep project untouched, show the diff. This is the
   invariant that makes the tool trustworthy.
2. **Unit tests** (`diylc-library` test tree): `GridModel` occupancy, `Footprint` extraction on
   representative components (resistor, DIL IC, TO-92), `Router` on synthetic fixtures
   (crossing nets must yield exactly 1 jumper; parallel nets 0).
3. **Regression harness:** headless runner (pattern: `RegressionTestRunner` in diylc-swing
   tests) that loads `.diy` files from `diylc-regression-data/input/user-files/diy/`, runs the
   compressor, asserts netlist equality, and records metrics (board area, jumper count) so
   algorithm changes are comparable across runs.
4. **Manual smoke test** per milestone: run the app (`mvn package`, `java -jar
   diylc-swing/target/diylc.jar`), compress a real project, eyeball + undo.

## 6. Milestones

Each milestone builds, passes existing tests, and is demoable. Sizes are rough.

- **M0 — Skeleton (small).** `CompressorPlugin` + menu action + `MainFrame` registration.
  Action extracts the netlist, classifies components, shows a summary dialog (N real parts,
  M connectivity components, K nets, P pins). *Accept:* correct numbers on 3 regression files;
  no behavior change elsewhere.
- **M1 — World model (medium).** Three committable steps:
  - **M1.1 — `GridModel`:** the 0.1″ lattice (1 cell = 20 px at DIYLC's 200 px/inch);
    pixel ↔ cell conversion, snapping with tolerance, cell occupancy (pin owner / body owners),
    occupied bounds. Unit tests.
  - **M1.2 — `Footprint`:** per-component grid shape extracted from sticky control points:
    pin offsets in cells relative to pin 0, on-grid check (all offsets within tolerance of the
    lattice), 90° rotation variants, stretchable-lead detection for 2-pin
    `AbstractLeadedComponent`s, off-grid flagging. Unit tests on representative families
    (leaded passive, DIL, TO-92, terminal block, pot).
  - **M1.3 — Project assembly + survey integration:** body extents from drawn component areas
    (`DrawingManager.getComponentArea`), full project → model build, and footprint/off-grid
    stats in the preview dialog. Verified against regression files and the manual test project.

  *Accept:* footprints correct for the common component families (leaded passives, DIL, TO-92,
  board-mounted); off-grid parts detected.
- **M2 — Router (large).** Five committable steps:
  - **M2.1 — Wire occupancy:** `GridModel` gains net-aware wire state — underside runs claim
    lattice edges and every hole they pass through (bare wire shorts against foreign pins and
    runs); per-net release enables rip-up; pin→net assignment.
  - **M2.2 — Single-net A*:** cheapest underside path from a cell to a set of target cells;
    integer costs (step + turn penalty), direction-aware search states, board bounds,
    deterministic tie-breaking.
  - **M2.3 — Multi-terminal nets:** closest pin pair first, then each remaining pin to the
    nearest cell of the growing route tree (cheap Steiner approximation); `RoutedNet` result.
  - **M2.4 — Whole-board routing:** net ordering, route all nets; a pin that can't reach its
    tree on the underside gets a top-side insulated jumper (always succeeds).
  - **M2.5 — Rip-up & reroute:** before accepting a jumper, rip up to K=3 blocking nets and
    retry, keep the cheaper outcome; routing metrics (wire length, jumper count).

  *Accept:* fixture suite passes; two nets forced to cross on a bounded board yield exactly
  one jumper; parallel nets yield zero.
- **M3 — Normalization end-to-end (large).** Seeder + legalizer + route-all + `LayoutEmitter` +
  verification + `applyEditor` wiring. First real "Compress Layout (rough)" button.
  *Accept:* ≥ 5 perfboard-suitable regression projects compress with netlist equality; undo
  restores the original exactly.
- **M4 — Compression loop (large).** Move catalogue + incremental reroute + annealer + time
  budget + cancel. *Accept:* on the M3 project set, mean board area and jumper count strictly
  improve vs. M3 output; equality still holds; 5 s default budget respected.
- **M5 — Product polish (medium).** Result summary dialog with stats, progress + cancel UX,
  options (time budget, wire colors/styles, margin), special-case handling from §4.5 hardened.
  *Accept:* full manual smoke pass; regression harness green across the whole `.diy` corpus
  (compress succeeds or degrades gracefully with a clear message — never corrupts).
- **M6 — Upstreaming (small).** Regression metrics report, screenshots, user-facing docs,
  push to fork, PR to `bancika/diy-layout-creator` per its contribution conventions.

## 7. Risks / open questions

- **Footprint fidelity** is the biggest correctness risk after routing: if a body extent is
  underestimated, parts overlap visually even though the netlist checks out. Mitigation:
  derive body extent from the component's actual drawn bounds where possible, err large.
- **Wire-under-body rule** (may an underside run pass beneath a DIL IC?): physically yes on the
  underside. v1 allows it; revisit if outputs look confusing.
- **Netlist granularity:** `extractNetlists(false)` fixes switches in one position; a layout
  compressed against one switch state is still electrically identical in all states because we
  reproduce point-to-point connectivity of *all* nets, but the verification should ideally
  compare all switch combinations (`includeSwitches=true`) — decide in M3 based on runtime.
- **Very dense inputs** may not fit any single-jumper-layer solution the annealer finds within
  budget; acceptable — output is still valid, just not pretty. Report stats honestly.
- **Upstream fit:** plugin list is hardcoded in `MainFrame`; upstreaming needs the maintainer's
  buy-in on a new menu entry. Keep the engine UI-independent so it survives any repackaging.

## 8. Out of scope (v1)

Stripboard/Vero mode (strip-assignment routing model), single-sided PCB mode (`CopperTrace` +
`SolderPad` emission — the engine's `LayoutEmitter` is the only PCB-specific part, so this is a
natural v2), double-sided boards, schematic inputs, component substitution.
