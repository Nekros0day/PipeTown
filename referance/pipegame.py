"""
Infinite Utilities v7.0 - compact mobile shared-source multi-house pipe puzzle.

Run:
    python infinite_pipes_game.py

Rules:
    Several houses now need utilities from the same shared Water / Heat /
    Sewage sources. Draw freehand pipe networks from each shared source to
    every matching house port.

    Same-utility pipes may merge into a shared network. Different utilities may
    not cross. The challenge comes from several houses competing for the same
    compact utility sources.

Design notes:
    * The map remains a compact fixed mobile grid: 10 x 16 cells.
    * Difficulty never increases the map size.
    * Difficulty increases houses, demanded utilities, source sharing pressure,
      port congestion, shared trunk pressure, and strategic no-dig pressure.
    * The generator proves a hidden solution before the puzzle is shown.
"""

from __future__ import annotations

import math
import random
import time
import tkinter as tk
from dataclasses import dataclass, field
from tkinter import messagebox, ttk
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

Point = Tuple[int, int]
Pixel = Tuple[float, float]

APP_VERSION = "v7.0 shared-source multi-house network generator"

# Small fixed mobile map. Difficulty changes density, count, bends and blockers,
# never the amount of available space.
FIXED_GRID_W = 10
FIXED_GRID_H = 16
DEFAULT_CELL_SIZE = 30
MIN_CELL_SIZE = 18
MAX_CELL_SIZE = 46
CANVAS_PAD = 22

UTILITY_INFO = {
    "Water": {"letter": "W", "color": "#1e88e5", "emoji": "💧"},
    "Heat": {"letter": "H", "color": "#e65100", "emoji": "🔥"},
    "Sewage": {"letter": "S", "color": "#6d4c41", "emoji": "⬇"},
}

DIRECTIONS: Tuple[Point, ...] = ((1, 0), (-1, 0), (0, 1), (0, -1))


@dataclass
class Connection:
    pid: int
    kind: str
    label: str
    start: Point
    goal: Point
    house_id: int = 1
    solution: List[Point] = field(default_factory=list)

    @property
    def color(self) -> str:
        return UTILITY_INFO[self.kind]["color"]


@dataclass
class Puzzle:
    width: int
    height: int
    house_rect: Tuple[int, int, int, int]
    house_cells: Set[Point]
    terrain_cells: Set[Point]
    connections: List[Connection]
    seed: int
    difficulty: int
    source_edge_clearance: int = 0
    hidden_bends: int = 0
    zone_count: int = 0
    forced_chokes: int = 0
    house_rects: List[Tuple[int, int, int, int]] = field(default_factory=list)
    utility_sources: Dict[str, Point] = field(default_factory=dict)
    clearance_rule: bool = False
    house_count: int = 1
    network_count: int = 3

    @property
    def blocked_cells(self) -> Set[Point]:
        return self.house_cells | self.terrain_cells

    @property
    def endpoints(self) -> Set[Point]:
        cells: Set[Point] = set()
        for conn in self.connections:
            cells.add(conn.start)
            cells.add(conn.goal)
        return cells


# ----------------------------- geometry helpers ----------------------------


def manhattan(a: Point, b: Point) -> int:
    return abs(a[0] - b[0]) + abs(a[1] - b[1])


def in_bounds(p: Point, width: int, height: int) -> bool:
    x, y = p
    return 0 <= x < width and 0 <= y < height


def neighbors(p: Point) -> Iterable[Point]:
    x, y = p
    for dx, dy in DIRECTIONS:
        yield x + dx, y + dy


def path_bends(path: Sequence[Point]) -> int:
    if len(path) < 3:
        return 0
    bends = 0
    prev = (path[1][0] - path[0][0], path[1][1] - path[0][1])
    for a, b in zip(path[1:], path[2:]):
        cur = (b[0] - a[0], b[1] - a[1])
        if cur != prev:
            bends += 1
        prev = cur
    return bends


def edge_clearance(p: Point, width: int, height: int) -> int:
    x, y = p
    return min(x, y, width - 1 - x, height - 1 - y)


def distance(a: Pixel, b: Pixel) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def orient(a: Pixel, b: Pixel, c: Pixel) -> float:
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def segments_intersect(a: Pixel, b: Pixel, c: Pixel, d: Pixel) -> bool:
    """Robust enough for UI strokes; treats touching as an intersection."""
    eps = 1e-6

    def on_seg(p: Pixel, q: Pixel, r: Pixel) -> bool:
        return (
            min(p[0], r[0]) - eps <= q[0] <= max(p[0], r[0]) + eps
            and min(p[1], r[1]) - eps <= q[1] <= max(p[1], r[1]) + eps
            and abs(orient(p, q, r)) <= eps
        )

    o1 = orient(a, b, c)
    o2 = orient(a, b, d)
    o3 = orient(c, d, a)
    o4 = orient(c, d, b)
    if (o1 > eps and o2 < -eps or o1 < -eps and o2 > eps) and (o3 > eps and o4 < -eps or o3 < -eps and o4 > eps):
        return True
    if abs(o1) <= eps and on_seg(a, c, b):
        return True
    if abs(o2) <= eps and on_seg(a, d, b):
        return True
    if abs(o3) <= eps and on_seg(c, a, d):
        return True
    if abs(o4) <= eps and on_seg(c, b, d):
        return True
    return False


# -------------------------- compact puzzle generator ------------------------


class PuzzleGenerator:
    """Constructive generator for a tiny shared-source city board.

    v7 changes the puzzle from independent pairs into source networks:
    Water, Heat, and Sewage each have one shared source, and several houses
    demand those shared utilities. The hidden solver routes all demanded house
    ports first. Same-utility paths may reuse cells to form trunk lines, but
    different utilities may not overlap or cross.
    """

    def __init__(self, seed: Optional[int] = None):
        if seed is None:
            seed = random.randrange(1_000_000_000)
        self.seed = seed
        self.rng = random.Random(seed)

    def generate(self, difficulty: int = 3) -> Puzzle:
        difficulty = max(1, min(7, int(difficulty)))
        last_error = "unknown"
        for _ in range(520 + difficulty * 110):
            try:
                puzzle = self._make_candidate(difficulty)
                if self.validate_solution(puzzle) and self._is_challenging_enough(puzzle):
                    return puzzle
                last_error = "candidate was valid but too simple"
            except ValueError as exc:
                last_error = str(exc)
        raise RuntimeError(f"Could not generate a valid puzzle: {last_error}")

    def _house_count(self, difficulty: int) -> int:
        return {1: 2, 2: 2, 3: 3, 4: 3, 5: 4, 6: 4, 7: 5}[difficulty]

    def _target_connection_count(self, difficulty: int, house_count: int) -> int:
        # More connections on the same fixed board, never a larger map.
        return min(house_count * 3, {1: 3, 2: 4, 3: 5, 4: 6, 5: 8, 6: 9, 7: 11}[difficulty])

    def _make_candidate(self, difficulty: int) -> Puzzle:
        width, height = FIXED_GRID_W, FIXED_GRID_H
        clearance_rule = False
        house_count = self._house_count(difficulty)

        utility_sources = self._choose_shared_sources(width, height, difficulty)
        house_rects, house_cells = self._place_houses(width, height, house_count, utility_sources, difficulty)
        connections = self._assign_house_demands(width, height, house_rects, house_cells, utility_sources, difficulty)
        if len(connections) < self._target_connection_count(difficulty, house_count):
            raise ValueError("not enough house utility ports")

        # Main puzzle object. house_rect is kept for backward compatibility with
        # older UI code; house_rects is the true v7 house list.
        puzzle = Puzzle(
            width=width,
            height=height,
            house_rect=house_rects[0],
            house_cells=house_cells,
            terrain_cells=set(),
            connections=connections,
            seed=self.seed,
            difficulty=difficulty,
            house_rects=house_rects,
            utility_sources=utility_sources,
            clearance_rule=clearance_rule,
            house_count=len(house_rects),
            network_count=len(utility_sources),
        )

        # Route all hidden solution paths. Same utility can share trunk cells;
        # different utilities cannot. Utility order is randomized so each map has
        # a different global dependency pattern.
        cell_owner: Dict[Point, str] = {}
        endpoint_cells = puzzle.endpoints
        route_order = connections[:]
        utility_order = list(UTILITY_INFO.keys())
        self.rng.shuffle(utility_order)
        route_order.sort(key=lambda c: (utility_order.index(c.kind), -manhattan(c.start, c.goal), self.rng.random()))

        for conn in route_order:
            path = self._route_path(puzzle, conn, cell_owner, endpoint_cells, difficulty)
            if not path:
                raise ValueError("could not route shared-source network")
            conn.solution = path
            for cell in path:
                if cell not in endpoint_cells or cell in (conn.start, conn.goal):
                    # A shared source is allowed; house ports are terminals and
                    # should not become pass-through trunk cells.
                    if cell not in puzzle.house_cells:
                        cell_owner[cell] = conn.kind

        occupied = set(puzzle.house_cells) | set(endpoint_cells)
        for conn in puzzle.connections:
            occupied.update(conn.solution)

        puzzle.forced_chokes = self._add_terrain_around_solution(puzzle, occupied)
        puzzle.source_edge_clearance = min(edge_clearance(p, width, height) for p in utility_sources.values())
        puzzle.hidden_bends = sum(path_bends(c.solution) for c in puzzle.connections)
        puzzle.zone_count = self._used_source_zones(puzzle)
        return puzzle

    def _choose_shared_sources(self, width: int, height: int, difficulty: int) -> Dict[str, Point]:
        zones = [
            (1, 2, 3, 5),
            (width - 4, 2, width - 2, 5),
            (1, height - 6, 3, height - 3),
            (width - 4, height - 6, width - 2, height - 3),
            (3, 1, width - 4, 3),
            (3, height - 4, width - 4, height - 2),
        ]
        self.rng.shuffle(zones)
        utilities = list(UTILITY_INFO.keys())
        self.rng.shuffle(utilities)
        sources: Dict[str, Point] = {}
        used: Set[Point] = set()
        min_gap = 4 if difficulty <= 4 else 3
        for kind in utilities:
            placed = False
            zone_order = zones[:]
            self.rng.shuffle(zone_order)
            for x0, y0, x1, y1 in zone_order:
                candidates = [(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)]
                self.rng.shuffle(candidates)
                candidates.sort(key=lambda p: (sum(manhattan(p, q) for q in used), self.rng.random()), reverse=True)
                for p in candidates:
                    if not in_bounds(p, width, height):
                        continue
                    if edge_clearance(p, width, height) < 1:
                        continue
                    if any(manhattan(p, q) < min_gap for q in used):
                        continue
                    sources[kind] = p
                    used.add(p)
                    placed = True
                    break
                if placed:
                    break
            if not placed:
                raise ValueError("could not place shared utility sources")
        return sources

    def _place_houses(
        self,
        width: int,
        height: int,
        count: int,
        sources: Dict[str, Point],
        difficulty: int,
    ) -> Tuple[List[Tuple[int, int, int, int]], Set[Point]]:
        rects: List[Tuple[int, int, int, int]] = []
        cells: Set[Point] = set()
        source_cells = set(sources.values())
        attempts = 500
        min_house_gap = 3 if count <= 3 else 2
        for _ in range(attempts):
            if len(rects) >= count:
                break
            # Single-cell houses make multiple houses fit on the compact mobile map.
            x = self.rng.randrange(2, width - 2)
            y = self.rng.randrange(2, height - 2)
            cell = (x, y)
            if cell in source_cells or cell in cells:
                continue
            if any(manhattan(cell, s) < 3 for s in source_cells):
                continue
            if any(manhattan(cell, h) < min_house_gap for h in cells):
                continue
            # Keep at least three valid neighboring ports open.
            open_ports = [n for n in neighbors(cell) if in_bounds(n, width, height) and n not in source_cells]
            if len(open_ports) < 3:
                continue
            rects.append((x, y, 1, 1))
            cells.add(cell)
        if len(rects) < count:
            raise ValueError("could not place enough compact houses")
        return rects, cells

    def _assign_house_demands(
        self,
        width: int,
        height: int,
        house_rects: List[Tuple[int, int, int, int]],
        house_cells: Set[Point],
        sources: Dict[str, Point],
        difficulty: int,
    ) -> List[Connection]:
        target = self._target_connection_count(difficulty, len(house_rects))
        ports_used: Set[Point] = set()
        connections: List[Connection] = []
        utility_counts = {kind: 0 for kind in UTILITY_INFO}

        # First pass: every house gets at least one demand, then harder levels
        # add second/third utilities on the same tiny board.
        demand_plan: List[Tuple[int, str]] = []
        for hid, _rect in enumerate(house_rects, start=1):
            required = 1
            if difficulty >= 2:
                required = 2
            if difficulty >= 5 and hid <= max(2, len(house_rects) - 1):
                required = 3
            if difficulty >= 7:
                required = 3
            kinds = list(UTILITY_INFO.keys())
            self.rng.shuffle(kinds)
            demand_plan.extend((hid, kind) for kind in kinds[:required])

        # Trim or expand to target while keeping utility mix balanced.
        self.rng.shuffle(demand_plan)
        demand_plan = demand_plan[:target]
        while len(demand_plan) < target:
            hid = self.rng.randrange(1, len(house_rects) + 1)
            existing = {k for h, k in demand_plan if h == hid}
            options = [k for k in UTILITY_INFO if k not in existing]
            if not options:
                break
            demand_plan.append((hid, self.rng.choice(options)))

        # Bias demand order so each utility appears multiple times; this creates
        # the shared-source pressure the user requested.
        demand_plan.sort(key=lambda hk: (hk[1], self.rng.random()))
        self.rng.shuffle(demand_plan)

        for hid, kind in demand_plan:
            x, y, _w, _h = house_rects[hid - 1]
            port_options = list(neighbors((x, y)))
            self.rng.shuffle(port_options)
            # Prefer the side away from that utility source to create wrapping.
            sx, sy = sources[kind]
            port_options.sort(key=lambda p: (manhattan(p, (sx, sy)), self.rng.random()), reverse=True)
            port: Optional[Point] = None
            for p in port_options:
                if not in_bounds(p, width, height):
                    continue
                if p in house_cells or p in ports_used or p in sources.values():
                    continue
                port = p
                break
            if port is None:
                raise ValueError("could not allocate house ports")
            ports_used.add(port)
            utility_counts[kind] += 1
            label = f"{UTILITY_INFO[kind]['letter']}{hid}"
            connections.append(
                Connection(
                    pid=len(connections) + 1,
                    kind=kind,
                    label=label,
                    start=sources[kind],
                    goal=port,
                    house_id=hid,
                )
            )

        # Require at least two demands for most utilities at medium+ difficulty.
        if difficulty >= 4:
            shared_counts = {k: sum(1 for c in connections if c.kind == k) for k in UTILITY_INFO}
            if min(shared_counts.values()) < 2:
                raise ValueError("not enough shared-source reuse")

        for pid, conn in enumerate(connections, start=1):
            conn.pid = pid
        return connections

    def _route_path(
        self,
        puzzle: Puzzle,
        conn: Connection,
        cell_owner: Dict[Point, str],
        endpoint_cells: Set[Point],
        difficulty: int,
    ) -> Optional[List[Point]]:
        import heapq

        start, goal, kind = conn.start, conn.goal, conn.kind
        blocked_static = puzzle.house_cells | puzzle.terrain_cells

        def passable(cell: Point) -> bool:
            if not in_bounds(cell, puzzle.width, puzzle.height):
                return False
            if cell in blocked_static:
                return False
            if cell in endpoint_cells and cell not in (start, goal):
                return False
            owner = cell_owner.get(cell)
            if owner is not None and owner != kind:
                return False
            if puzzle.clearance_rule and cell not in (start, goal):
                for n in neighbors(cell):
                    owner2 = cell_owner.get(n)
                    if owner2 is not None and owner2 != kind:
                        return False
            return True

        counter = 0
        start_state = (start, (0, 0))
        heap: List[Tuple[float, int, Point, Point]] = [(0.0, counter, start, (0, 0))]
        came_from: Dict[Tuple[Point, Point], Tuple[Point, Point]] = {}
        cost_so_far: Dict[Tuple[Point, Point], float] = {start_state: 0.0}
        best_goal_state: Optional[Tuple[Point, Point]] = None

        # Per-cell noise makes the generated route less predictable but stable
        # inside one solve attempt.
        noise = {(x, y): self.rng.random() * (0.18 + difficulty * 0.035) for y in range(puzzle.height) for x in range(puzzle.width)}

        while heap:
            _priority, _idx, current, prev_dir = heapq.heappop(heap)
            state = (current, prev_dir)
            if current == goal:
                best_goal_state = state
                break
            for dx, dy in DIRECTIONS:
                nxt = (current[0] + dx, current[1] + dy)
                ndir = (dx, dy)
                if not passable(nxt):
                    continue
                reuse = cell_owner.get(nxt) == kind
                step_cost = 0.42 if reuse else 1.0
                turn_cost = 0.0 if prev_dir == (0, 0) or prev_dir == ndir else 0.28 + difficulty * 0.035
                # At higher levels, do not over-reward straight obvious lines.
                directness_tax = 0.0
                if difficulty >= 5 and current[0] == goal[0] or difficulty >= 5 and current[1] == goal[1]:
                    directness_tax = 0.08
                new_cost = cost_so_far[state] + step_cost + turn_cost + noise[nxt] + directness_tax
                next_state = (nxt, ndir)
                if new_cost < cost_so_far.get(next_state, float("inf")):
                    cost_so_far[next_state] = new_cost
                    heuristic = manhattan(nxt, goal) * 0.93
                    # Slightly prefer using same-utility trunks, because that
                    # creates actual network puzzles instead of independent pairs.
                    trunk_bonus = -0.55 if reuse else 0.0
                    counter += 1
                    heapq.heappush(heap, (new_cost + heuristic + trunk_bonus, counter, nxt, ndir))
                    came_from[next_state] = state

        if best_goal_state is None:
            return None

        rev: List[Point] = []
        state = best_goal_state
        while True:
            cell, _dir = state
            rev.append(cell)
            if cell == start:
                break
            state = came_from[state]
        path = list(reversed(rev))
        if len(path) < 2:
            return None
        return path

    def _add_terrain_around_solution(self, puzzle: Puzzle, occupied: Set[Point]) -> int:
        protected = set(occupied) | puzzle.endpoints | puzzle.house_cells
        for ep in puzzle.endpoints:
            protected.update(neighbors(ep))
        terrain: Set[Point] = set()
        chokes = 0
        # Obstacles are not the main difficulty anymore. They are lower-density
        # no-dig cells used to stop trivial straight corridors and frame lanes.
        target = int(puzzle.width * puzzle.height * (0.035 + puzzle.difficulty * 0.012))
        target += max(0, puzzle.difficulty - 5)

        def add_if_ok(cell: Point, cluster_limit: int = 2) -> bool:
            if not in_bounds(cell, puzzle.width, puzzle.height):
                return False
            if cell in protected or cell in terrain:
                return False
            if any(manhattan(cell, ep) <= 1 for ep in puzzle.endpoints):
                return False
            if sum(n in terrain for n in neighbors(cell)) > cluster_limit:
                return False
            terrain.add(cell)
            return True

        # Block obvious Manhattan shortcuts between shared sources and ports.
        corridor: List[Point] = []
        for conn in puzzle.connections:
            sx, sy = conn.start
            gx, gy = conn.goal
            route_a = [(x, sy) for x in range(min(sx, gx), max(sx, gx) + 1)] + [(gx, y) for y in range(min(sy, gy), max(sy, gy) + 1)]
            route_b = [(sx, y) for y in range(min(sy, gy), max(sy, gy) + 1)] + [(x, gy) for x in range(min(sx, gx), max(sx, gx) + 1)]
            corridor.extend(c for c in route_a + route_b if c not in occupied)
        self.rng.shuffle(corridor)
        for cell in corridor[: int(target * 0.42)]:
            if add_if_ok(cell, cluster_limit=1):
                chokes += 1

        candidates = [
            (x, y)
            for y in range(1, puzzle.height - 1)
            for x in range(1, puzzle.width - 1)
            if (x, y) not in protected and (x, y) not in terrain
        ]
        self.rng.shuffle(candidates)
        for cell in candidates:
            if len(terrain) >= target:
                break
            if self.rng.random() < 0.34 + puzzle.difficulty * 0.035:
                add_if_ok(cell, cluster_limit=2)

        puzzle.terrain_cells = terrain
        return chokes

    def _zone_of(self, p: Point, width: int, height: int) -> Tuple[int, int]:
        x, y = p
        return min(2, int(x * 3 / width)), min(2, int(y * 3 / height))

    def _used_source_zones(self, puzzle: Puzzle) -> int:
        if puzzle.utility_sources:
            return len({self._zone_of(p, puzzle.width, puzzle.height) for p in puzzle.utility_sources.values()})
        return len({self._zone_of(c.start, puzzle.width, puzzle.height) for c in puzzle.connections})

    def _is_challenging_enough(self, puzzle: Puzzle) -> bool:
        if puzzle.source_edge_clearance < 1:
            return False
        if puzzle.house_count < self._house_count(puzzle.difficulty):
            return False
        per_kind = {k: sum(1 for c in puzzle.connections if c.kind == k) for k in UTILITY_INFO}
        if puzzle.difficulty >= 4 and min(per_kind.values()) < 2:
            return False
        if puzzle.difficulty >= 6 and len(puzzle.connections) < 8:
            return False
        if puzzle.zone_count < 2:
            return False
        # Hidden routes should not be just straight spokes.
        min_bends = max(2, puzzle.difficulty + len(puzzle.connections) // 3)
        if puzzle.hidden_bends < min_bends:
            return False
        return True

    @staticmethod
    def validate_solution(puzzle: Puzzle) -> bool:
        cell_owner: Dict[Point, str] = {}
        endpoints = puzzle.endpoints
        for conn in puzzle.connections:
            path = conn.solution
            if len(path) < 2 or path[0] != conn.start or path[-1] != conn.goal:
                return False
            # A route may touch/reuse previous same-utility trunk cells, but it
            # may not loop back on itself.
            if len(path) != len(set(path)):
                return False
            for a, b in zip(path, path[1:]):
                if manhattan(a, b) != 1:
                    return False
            for cell in path:
                if not in_bounds(cell, puzzle.width, puzzle.height):
                    return False
                if cell in puzzle.blocked_cells:
                    return False
                if cell in endpoints and cell not in (conn.start, conn.goal):
                    return False
                owner = cell_owner.get(cell)
                if owner is not None and owner != conn.kind:
                    return False
                if puzzle.clearance_rule and cell not in (conn.start, conn.goal):
                    for n in neighbors(cell):
                        owner2 = cell_owner.get(n)
                        if owner2 is not None and owner2 != conn.kind:
                            return False
                cell_owner[cell] = conn.kind
        return True


# -------------------------------- game UI -----------------------------------


class InfinitePipesApp:
    PIPE_WIDTH_RATIO = 0.30

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(f"Infinite Utilities - {APP_VERSION}")
        self.root.minsize(760, 760)

        self.puzzle: Optional[Puzzle] = None
        self.difficulty = tk.IntVar(value=3)
        self.cell_size = tk.IntVar(value=DEFAULT_CELL_SIZE)
        self.show_solution = tk.BooleanVar(value=False)
        self.show_grid = tk.BooleanVar(value=False)
        self.status = tk.StringVar(value="Generate a puzzle to begin.")
        self.stats = tk.StringVar(value="")

        self.user_strokes: Dict[int, List[Pixel]] = {}
        self.connected: Set[int] = set()
        self.selected_pid: Optional[int] = None
        self.active_kind: Optional[str] = None
        self.dragging = False
        self.last_mouse: Optional[Pixel] = None

        self.board_x = CANVAS_PAD
        self.board_y = CANVAS_PAD
        self.canvas_w = FIXED_GRID_W * DEFAULT_CELL_SIZE + CANVAS_PAD * 2
        self.canvas_h = FIXED_GRID_H * DEFAULT_CELL_SIZE + CANVAS_PAD * 2

        self._build_layout()
        self.new_puzzle()

    # ------------------------------- layout --------------------------------

    def _build_layout(self) -> None:
        self.root.columnconfigure(1, weight=1)
        self.root.rowconfigure(0, weight=1)

        side = ttk.Frame(self.root, padding=12)
        side.grid(row=0, column=0, sticky="ns")

        ttk.Label(side, text="Infinite Utilities", font=("TkDefaultFont", 16, "bold")).pack(anchor="w", pady=(0, 4))
        ttk.Label(side, text=APP_VERSION, foreground="#555").pack(anchor="w", pady=(0, 10))

        instructions = (
            "Several houses now share the same Water / Heat / Sewage sources.\n"
            "Draw freehand utility networks from a shared source to every matching house port.\n\n"
            "Same-utility pipes may merge or branch. Different utilities cannot cross, "
            "so every shared source becomes a network-planning problem."
        )
        ttk.Label(side, text=instructions, wraplength=250, justify="left").pack(anchor="w", pady=(0, 12))

        gen_frame = ttk.LabelFrame(side, text="Generator")
        gen_frame.pack(fill="x", pady=(0, 12))

        diff_row = ttk.Frame(gen_frame, padding=(8, 8, 8, 2))
        diff_row.pack(fill="x")
        ttk.Label(diff_row, text="Difficulty").pack(side="left")
        self.diff_value_label = ttk.Label(diff_row, text=str(self.difficulty.get()), width=3, anchor="e")
        self.diff_value_label.pack(side="right")
        diff_scale = ttk.Scale(gen_frame, from_=1, to=7, orient="horizontal", command=self._on_difficulty_slide)
        diff_scale.set(self.difficulty.get())
        diff_scale.pack(fill="x", padx=8, pady=(0, 8))

        cell_row = ttk.Frame(gen_frame, padding=(8, 0, 8, 2))
        cell_row.pack(fill="x")
        ttk.Label(cell_row, text="Cell size").pack(side="left")
        self.cell_value_label = ttk.Label(cell_row, text=f"{self.cell_size.get()}px", width=6, anchor="e")
        self.cell_value_label.pack(side="right")
        cell_scale = ttk.Scale(gen_frame, from_=MIN_CELL_SIZE, to=MAX_CELL_SIZE, orient="horizontal", command=self._on_cell_size_slide)
        cell_scale.set(self.cell_size.get())
        cell_scale.pack(fill="x", padx=8, pady=(0, 8))

        ttk.Button(side, text="New random map", command=self.new_puzzle).pack(fill="x", pady=3)
        ttk.Button(side, text="Reset current map", command=self.reset_paths).pack(fill="x", pady=3)
        ttk.Button(side, text="Check solution", command=self.check_solution).pack(fill="x", pady=3)
        ttk.Button(side, text="Hint next unfinished", command=self.hint_next).pack(fill="x", pady=3)
        ttk.Checkbutton(side, text="Show hidden solution", variable=self.show_solution, command=self.draw).pack(anchor="w", pady=(10, 2))
        ttk.Checkbutton(side, text="Show invisible grid", variable=self.show_grid, command=self.draw).pack(anchor="w", pady=(0, 4))

        legend = ttk.LabelFrame(side, text="Utilities")
        legend.pack(fill="x", pady=(12, 8))
        for kind, info in UTILITY_INFO.items():
            item = ttk.Frame(legend, padding=(8, 3))
            item.pack(fill="x")
            swatch = tk.Canvas(item, width=20, height=20, highlightthickness=0)
            swatch.create_oval(3, 3, 17, 17, fill=info["color"], outline="")
            swatch.pack(side="left")
            ttk.Label(item, text=f" {info['letter']} = {kind}").pack(side="left")

        ttk.Separator(side).pack(fill="x", pady=12)
        ttk.Label(side, textvariable=self.stats, wraplength=250, justify="left").pack(anchor="w")
        ttk.Label(side, textvariable=self.status, wraplength=250, justify="left").pack(anchor="w", pady=(12, 0))

        canvas_frame = ttk.Frame(self.root, padding=(0, 12, 12, 12))
        canvas_frame.grid(row=0, column=1, sticky="nsew")
        canvas_frame.columnconfigure(0, weight=1)
        canvas_frame.rowconfigure(0, weight=1)

        self.canvas = tk.Canvas(
            canvas_frame,
            width=self.canvas_w,
            height=self.canvas_h,
            bg="#f4ead8",
            highlightthickness=1,
            highlightbackground="#b8ae9c",
        )
        self.canvas.grid(row=0, column=0, sticky="n")
        self.canvas.bind("<Button-1>", self.on_mouse_down)
        self.canvas.bind("<B1-Motion>", self.on_mouse_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_mouse_up)
        self.canvas.bind("<Button-3>", self.on_right_click)
        self.canvas.bind("<Control-Button-1>", self.on_right_click)

    def _on_difficulty_slide(self, value: str) -> None:
        d = int(round(float(value)))
        self.difficulty.set(d)
        self.diff_value_label.configure(text=str(d))

    def _on_cell_size_slide(self, value: str) -> None:
        old_size = max(1, self.cell_size.get())
        size = int(round(float(value)))
        if size == old_size:
            return
        # Scale any already drawn freehand strokes so changing the visual cell
        # factor does not destroy the player's work. Points are stored in board
        # coordinates, so convert to grid-space and back.
        if self.puzzle:
            scaled: Dict[int, List[Pixel]] = {}
            for pid, stroke in self.user_strokes.items():
                new_stroke: List[Pixel] = []
                for px, py in stroke:
                    gx = (px - self.board_x) / old_size
                    gy = (py - self.board_y) / old_size
                    new_stroke.append((CANVAS_PAD + gx * size, CANVAS_PAD + gy * size))
                scaled[pid] = new_stroke
            self.user_strokes = scaled
        self.cell_size.set(size)
        self.cell_value_label.configure(text=f"{size}px")
        self.status.set(f"Cell size changed to {size}px. Map cells stayed fixed at {FIXED_GRID_W} × {FIXED_GRID_H}.")
        self.draw()

    # ----------------------------- puzzle flow ------------------------------

    def new_puzzle(self) -> None:
        d = self.difficulty.get()
        started = time.perf_counter()
        last_error: Optional[Exception] = None
        for _ in range(18):
            generator = PuzzleGenerator()
            try:
                puzzle = generator.generate(difficulty=d)
                self.puzzle = puzzle
                self.connected.clear()
                self.selected_pid = None
                self.active_kind = None
                self.dragging = False
                self.show_solution.set(False)
                self.user_strokes = {conn.pid: [self._cell_center(conn.start)] for conn in puzzle.connections}
                elapsed_ms = int((time.perf_counter() - started) * 1000)
                direct_total = sum(manhattan(c.start, c.goal) for c in puzzle.connections)
                solution_total = sum(len(c.solution) - 1 for c in puzzle.connections)
                per_kind = {k: sum(1 for c in puzzle.connections if c.kind == k) for k in UTILITY_INFO}
                self.status.set("New multi-house shared-source map. Same size; difficulty is network pressure.")
                self.stats.set(
                    f"Version: {APP_VERSION}\n"
                    f"Seed: {puzzle.seed}\n"
                    f"Fixed grid: {puzzle.width} × {puzzle.height} cells\n"
                    f"Cell size: {self.cell_size.get()}px, user-controlled\n"
                    f"Houses: {puzzle.house_count}\n"
                    f"Shared sources: {puzzle.network_count}\n"
                    f"House ports to satisfy: {len(puzzle.connections)}\n"
                    f"Demand mix: W{per_kind['Water']} H{per_kind['Heat']} S{per_kind['Sewage']}\n"
                    f"Shared-network rule: same utility can branch\n"
                    f"Hidden bends: {puzzle.hidden_bends}\n"
                    f"Extra route length: {solution_total - direct_total}\n"
                    f"No-dig cells: {len(puzzle.terrain_cells)}\n"
                    f"Generated in: {elapsed_ms} ms"
                )
                self.draw()
                return
            except RuntimeError as exc:
                last_error = exc
        messagebox.showerror("Generation failed", f"Could not generate this compact layout. Try difficulty one lower.\n\n{last_error}")

    def reset_paths(self) -> None:
        if not self.puzzle:
            return
        self.connected.clear()
        self.selected_pid = None
        self.active_kind = None
        self.dragging = False
        self.user_strokes = {conn.pid: [self._cell_center(conn.start)] for conn in self.puzzle.connections}
        self.status.set("Map reset.")
        self.draw()

    def check_solution(self) -> None:
        ok, message = self._is_player_solution_valid(require_all=True)
        self.status.set(message)
        if ok:
            messagebox.showinfo("Solved", "All utilities are connected without intersections!")

    def hint_next(self) -> None:
        if not self.puzzle:
            return
        for conn in self.puzzle.connections:
            if conn.pid not in self.connected:
                self.selected_pid = conn.pid
                self.status.set(f"Hint: {conn.label} belongs to the shared {conn.kind} network. Toggle hidden solution for full route.")
                self._flash_solution_for(conn)
                return
        self.status.set("Everything is already connected.")

    def _flash_solution_for(self, conn: Connection) -> None:
        coords = self._path_to_coords(conn.solution)
        if coords:
            self.canvas.create_line(
                coords,
                fill=conn.color,
                width=max(3, self.cell_size.get() // 6),
                dash=(6, 5),
                capstyle=tk.ROUND,
                joinstyle=tk.ROUND,
                tags="hint",
            )
            self.root.after(1300, self.draw)

    # ----------------------------- free drawing -----------------------------

    def on_mouse_down(self, event: tk.Event) -> None:
        if not self.puzzle:
            return
        pos = (float(event.x), float(event.y))
        hit = self._hit_network_start(pos)
        if hit is None:
            self.selected_pid = None
            self.active_kind = None
            self.dragging = False
            self.status.set("Start on a shared source, an unfinished loose end, or an existing same-utility pipe to branch.")
            self.draw()
            return

        kind, pid_hint, start_pos = hit
        pid = pid_hint if pid_hint is not None else self._first_unfinished_pid(kind)
        if pid is None:
            self.status.set(f"All {kind} ports are already connected. Right-click one to redraw it.")
            return
        conn = self._conn_by_id(pid)
        if not conn:
            return

        self.selected_pid = pid
        self.active_kind = kind
        self.dragging = True
        self.connected.discard(pid)

        # If continuing the loose end of this exact pipe, keep its partial path;
        # otherwise start a fresh branch from the source or existing network.
        existing = self.user_strokes.get(pid, [self._cell_center(conn.start)])
        if pid_hint == pid and existing and distance(pos, existing[-1]) <= self._terminal_radius() * 1.35:
            stroke = existing
        else:
            stroke = [start_pos]
        self.user_strokes[pid] = stroke
        self.last_mouse = stroke[-1]
        self.status.set(f"Drawing {kind} network. Release on any matching {kind} house port.")
        self.draw()

    def on_mouse_drag(self, event: tk.Event) -> None:
        if not self.dragging or self.selected_pid is None or not self.puzzle:
            return
        pos = self._clamp_to_board((float(event.x), float(event.y)))
        stroke = self.user_strokes[self.selected_pid]
        if distance(stroke[-1], pos) >= max(2.0, self.cell_size.get() / 7):
            stroke.append(pos)
            self.last_mouse = pos
            self.draw()

    def on_mouse_up(self, event: tk.Event) -> None:
        if not self.dragging or self.selected_pid is None or not self.puzzle:
            self.dragging = False
            return
        active_kind = self.active_kind
        original_pid = self.selected_pid
        conn = self._conn_by_id(original_pid)
        if not conn or active_kind is None:
            self.dragging = False
            return

        pos = self._clamp_to_board((float(event.x), float(event.y)))
        stroke = self.user_strokes[original_pid]
        if distance(stroke[-1], pos) >= 1:
            stroke.append(pos)

        goal_conn = self._hit_goal_for_kind(pos, active_kind)
        if goal_conn is not None:
            # The player can start at a shared source and decide which house port
            # they are solving only when they release.
            final_pid = goal_conn.pid
            if final_pid != original_pid:
                old_conn = self._conn_by_id(original_pid)
                if old_conn and original_pid not in self.connected:
                    self.user_strokes[original_pid] = [self._cell_center(old_conn.start)]
                self.selected_pid = final_pid
                conn = goal_conn
            stroke.append(self._cell_center(conn.goal))
            self.user_strokes[conn.pid] = self._simplify_stroke(stroke)
            legal, msg = self._stroke_is_legal(conn.pid)
            if legal:
                self.connected.add(conn.pid)
                self.status.set(f"{conn.label} connected to House {conn.house_id}.")
                ok, _ = self._is_player_solution_valid(require_all=True)
                if ok:
                    self.status.set("Solved! Every house is connected to all demanded shared sources.")
            else:
                self.connected.discard(conn.pid)
                self.status.set(f"{conn.label} is not valid: {msg}")
        else:
            self.user_strokes[original_pid] = self._simplify_stroke(stroke)
            self.connected.discard(original_pid)
            self.status.set(f"Unfinished {active_kind} branch. Release on a matching colored house port.")

        self.dragging = False
        self.active_kind = None
        self.draw()

    def on_right_click(self, event: tk.Event) -> None:
        if not self.puzzle:
            return
        pos = (float(event.x), float(event.y))
        pid = self._hit_any_stroke_or_terminal(pos)
        if pid is None:
            return
        conn = self._conn_by_id(pid)
        if not conn:
            return
        self.user_strokes[pid] = [self._cell_center(conn.start)]
        self.connected.discard(pid)
        self.selected_pid = pid
        self.active_kind = conn.kind
        self.dragging = False
        self.status.set(f"Cleared {conn.label} for House {conn.house_id}.")
        self.draw()

    def _first_unfinished_pid(self, kind: str) -> Optional[int]:
        if not self.puzzle:
            return None
        for conn in self.puzzle.connections:
            if conn.kind == kind and conn.pid not in self.connected:
                return conn.pid
        # If all are connected, return the first of that kind so the user can redraw.
        for conn in self.puzzle.connections:
            if conn.kind == kind:
                return conn.pid
        return None

    def _hit_network_start(self, pos: Pixel) -> Optional[Tuple[str, Optional[int], Pixel]]:
        if not self.puzzle:
            return None
        radius = self._terminal_radius() * 1.35
        # Shared source circles.
        for kind, cell in self.puzzle.utility_sources.items():
            center = self._cell_center(cell)
            if distance(pos, center) <= radius:
                return kind, self._first_unfinished_pid(kind), center
        # Continue from loose ends.
        for conn in self.puzzle.connections:
            stroke = self.user_strokes.get(conn.pid, [])
            if stroke and conn.pid not in self.connected and distance(pos, stroke[-1]) <= radius:
                return conn.kind, conn.pid, stroke[-1]
        # Branch from an already drawn same-utility network.
        best: Optional[Tuple[str, Optional[int], Pixel]] = None
        best_dist = float("inf")
        for conn in self.puzzle.connections:
            if conn.pid not in self.connected:
                continue
            stroke = self.user_strokes.get(conn.pid, [])
            if len(stroke) < 2:
                continue
            for a, b in zip(stroke, stroke[1:]):
                d = self._point_to_segment_distance(pos, a, b)
                if d < best_dist:
                    best_dist = d
                    best = (conn.kind, self._first_unfinished_pid(conn.kind), pos)
        if best is not None and best_dist <= max(8, self.cell_size.get() * 0.30):
            return best
        return None

    def _hit_goal_for_kind(self, pos: Pixel, kind: str) -> Optional[Connection]:
        if not self.puzzle:
            return None
        radius = self._terminal_radius() * 1.75
        matches = [conn for conn in self.puzzle.connections if conn.kind == kind]
        matches.sort(key=lambda c: distance(pos, self._cell_center(c.goal)))
        if matches and distance(pos, self._cell_center(matches[0].goal)) <= radius:
            return matches[0]
        return None

    def _hit_any_stroke_or_terminal(self, pos: Pixel) -> Optional[int]:
        if not self.puzzle:
            return None
        radius = self._terminal_radius() * 1.4
        for conn in self.puzzle.connections:
            if distance(pos, self._cell_center(conn.goal)) <= radius:
                return conn.pid
        for kind, source in self.puzzle.utility_sources.items():
            if distance(pos, self._cell_center(source)) <= radius:
                return self._first_unfinished_pid(kind)
        best_pid: Optional[int] = None
        best_dist = float("inf")
        for pid, stroke in self.user_strokes.items():
            for a, b in zip(stroke, stroke[1:]):
                d = self._point_to_segment_distance(pos, a, b)
                if d < best_dist:
                    best_dist = d
                    best_pid = pid
        return best_pid if best_dist <= max(8, self.cell_size.get() * 0.28) else None

    # ---------------------------- stroke validity ---------------------------

    def _is_player_solution_valid(self, require_all: bool = True) -> Tuple[bool, str]:
        if not self.puzzle:
            return False, "No puzzle loaded."
        for conn in self.puzzle.connections:
            if conn.pid not in self.connected:
                if require_all:
                    return False, f"House {conn.house_id} still needs {conn.kind} ({conn.label})."
                continue
            legal, msg = self._stroke_is_legal(conn.pid)
            if not legal:
                return False, f"{conn.label}: {msg}"
        if require_all and len(self.connected) != len(self.puzzle.connections):
            return False, "Not every demanded house port is connected."
        return True, "All houses are connected to their shared utility sources."

    def _stroke_is_legal(self, pid: int) -> Tuple[bool, str]:
        if not self.puzzle:
            return False, "no puzzle loaded"
        conn = self._conn_by_id(pid)
        if not conn:
            return False, "missing connection"
        stroke = self.user_strokes.get(pid, [])
        if len(stroke) < 2:
            return False, "pipe is too short"

        source_center = self._cell_center(conn.start)
        starts_at_source = distance(stroke[0], source_center) <= self._terminal_radius() * 1.7
        starts_on_network = self._point_touches_same_kind_network(stroke[0], conn.kind, exclude_pid=pid)
        if not (starts_at_source or starts_on_network):
            return False, "pipe must start at the shared source or branch from the same utility network"
        if distance(stroke[-1], self._cell_center(conn.goal)) > self._terminal_radius() * 1.8:
            return False, "pipe does not end at its matching house port"

        touched_cells = self._cells_touched_by_stroke(stroke)
        for cell in touched_cells:
            if not in_bounds(cell, self.puzzle.width, self.puzzle.height):
                return False, "pipe leaves the board"
            if cell in self.puzzle.terrain_cells:
                return False, "pipe crosses a no-dig cell"
            if cell in self.puzzle.house_cells:
                return False, "pipe goes through a house"
            if cell in self.puzzle.endpoints and cell not in (conn.start, conn.goal):
                return False, "pipe crosses another source or house port"

        if self._stroke_self_intersects(stroke):
            return False, "pipe crosses itself"

        for other in self.puzzle.connections:
            if other.pid == pid:
                continue
            other_stroke = self.user_strokes.get(other.pid, [])
            if len(other_stroke) < 2:
                continue
            if other.kind == conn.kind:
                # Same utility is one shared network: overlap and branch are allowed.
                continue
            if self._strokes_intersect(stroke, other_stroke):
                return False, "different utility networks intersect"

        if self.puzzle.clearance_rule:
            for other in self.puzzle.connections:
                if other.pid == pid or other.kind == conn.kind or other.pid not in self.connected:
                    continue
                other_cells = self._cells_touched_by_stroke(self.user_strokes.get(other.pid, []))
                for cell in touched_cells:
                    if any(manhattan(cell, oc) <= 1 for oc in other_cells):
                        return False, "hard mode clearance: different utilities are too close"
        return True, "ok"

    def _point_touches_same_kind_network(self, pos: Pixel, kind: str, exclude_pid: Optional[int] = None) -> bool:
        if not self.puzzle:
            return False
        threshold = max(8.0, self.cell_size.get() * 0.30)
        for other in self.puzzle.connections:
            if other.kind != kind or other.pid == exclude_pid or other.pid not in self.connected:
                continue
            stroke = self.user_strokes.get(other.pid, [])
            for a, b in zip(stroke, stroke[1:]):
                if self._point_to_segment_distance(pos, a, b) <= threshold:
                    return True
        return False

    def _cells_touched_by_stroke(self, stroke: Sequence[Pixel]) -> Set[Point]:
        cells: Set[Point] = set()
        step = max(2.0, self.cell_size.get() / 6)
        for a, b in zip(stroke, stroke[1:]):
            length = max(step, distance(a, b))
            samples = max(2, int(length / step) + 1)
            for i in range(samples + 1):
                t = i / samples
                px = a[0] + (b[0] - a[0]) * t
                py = a[1] + (b[1] - a[1]) * t
                cell = self._pixel_to_cell((px, py))
                if cell is not None:
                    cells.add(cell)
                else:
                    # use sentinel outside board
                    cells.add((-999, -999))
        return cells

    def _stroke_self_intersects(self, stroke: Sequence[Pixel]) -> bool:
        if len(stroke) < 5:
            return False
        segments = list(zip(stroke, stroke[1:]))
        for i, (a, b) in enumerate(segments):
            for j, (c, d) in enumerate(segments):
                if abs(i - j) <= 1:
                    continue
                if i == 0 and j == len(segments) - 1:
                    continue
                if segments_intersect(a, b, c, d):
                    return True
        return False

    def _strokes_intersect(self, a_stroke: Sequence[Pixel], b_stroke: Sequence[Pixel]) -> bool:
        # Ignore endpoint touches only when they are the same source/goal; all
        # different connections have different terminals, so any touch is illegal.
        for a, b in zip(a_stroke, a_stroke[1:]):
            for c, d in zip(b_stroke, b_stroke[1:]):
                if segments_intersect(a, b, c, d):
                    return True
        return False

    # ------------------------------ drawing ---------------------------------

    def draw(self) -> None:
        if not self.puzzle:
            return
        self._recalculate_board_transform()
        self.canvas.configure(width=self.canvas_w, height=self.canvas_h)
        self.canvas.delete("all")
        self._draw_background()
        self._draw_blockers()
        self._draw_house()
        if self.show_solution.get():
            self._draw_hidden_solution()
        self._draw_player_pipes()
        self._draw_endpoints()
        self._draw_selection()

    def _draw_background(self) -> None:
        assert self.puzzle is not None
        bw = self.puzzle.width * self.cell_size.get()
        bh = self.puzzle.height * self.cell_size.get()
        x0, y0 = self.board_x, self.board_y
        x1, y1 = x0 + bw, y0 + bh
        self.canvas.create_rectangle(0, 0, self.canvas_w, self.canvas_h, fill="#efe3cf", outline="")
        self.canvas.create_rectangle(x0, y0, x1, y1, fill="#fff8ea", outline="#8e8373", width=3)
        # Rounded mobile-screen feel using inner sandy play area.
        self.canvas.create_rectangle(x0 + 4, y0 + 4, x1 - 4, y1 - 4, outline="#e0d0ba", width=1)
        if self.show_grid.get():
            for x in range(self.puzzle.width + 1):
                px = self.board_x + x * self.cell_size.get()
                self.canvas.create_line(px, self.board_y, px, self.board_y + bh, fill="#e7dccb")
            for y in range(self.puzzle.height + 1):
                py = self.board_y + y * self.cell_size.get()
                self.canvas.create_line(self.board_x, py, self.board_x + bw, py, fill="#e7dccb")

    def _draw_blockers(self) -> None:
        assert self.puzzle is not None
        for cell in self.puzzle.terrain_cells:
            cx, cy = self._cell_center(cell)
            r = self.cell_size.get() * 0.32
            self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, fill="#6f7b5f", outline="#4d5a43", width=2)
            self.canvas.create_line(cx - r * 0.45, cy - r * 0.45, cx + r * 0.45, cy + r * 0.45, fill="#dfe6d4", width=2)
            self.canvas.create_line(cx + r * 0.45, cy - r * 0.45, cx - r * 0.45, cy + r * 0.45, fill="#dfe6d4", width=2)

    def _draw_house(self) -> None:
        assert self.puzzle is not None
        rects = self.puzzle.house_rects or [self.puzzle.house_rect]
        for idx, (left, top, w, h) in enumerate(rects, start=1):
            x0, y0 = self._cell_top_left((left, top))
            x1 = x0 + w * self.cell_size.get()
            y1 = y0 + h * self.cell_size.get()
            self.canvas.create_rectangle(x0, y0, x1, y1, fill="#d9b98f", outline="#8d6e63", width=2)
            roof = [x0 - 4, y0 + 4, (x0 + x1) / 2, y0 - max(8, self.cell_size.get() * 0.32), x1 + 4, y0 + 4]
            self.canvas.create_polygon(roof, fill="#a5533d", outline="#6e3428", width=1)
            self.canvas.create_text(
                (x0 + x1) / 2,
                (y0 + y1) / 2,
                text=f"H{idx}",
                font=("TkDefaultFont", max(7, self.cell_size.get() // 3), "bold"),
                fill="#5d4037",
            )

    def _draw_hidden_solution(self) -> None:
        assert self.puzzle is not None
        for conn in self.puzzle.connections:
            coords = self._path_to_coords(conn.solution)
            if len(coords) >= 4:
                self.canvas.create_line(coords, fill=conn.color, width=max(3, self.cell_size.get() // 6), dash=(6, 5), capstyle=tk.ROUND, joinstyle=tk.ROUND)

    def _draw_player_pipes(self) -> None:
        assert self.puzzle is not None
        pipe_width = max(7, int(self.cell_size.get() * self.PIPE_WIDTH_RATIO))
        for conn in self.puzzle.connections:
            stroke = self.user_strokes.get(conn.pid, [])
            if len(stroke) < 2:
                continue
            coords: List[float] = []
            for px, py in stroke:
                coords.extend([px, py])
            legal, _ = self._stroke_is_legal(conn.pid) if conn.pid in self.connected else (True, "")
            color = conn.color if legal else "#b71c1c"
            self.canvas.create_line(coords, fill=color, width=pipe_width, capstyle=tk.ROUND, joinstyle=tk.ROUND, smooth=False)
            self.canvas.create_line(coords, fill="#ffffff", width=max(2, pipe_width // 5), capstyle=tk.ROUND, joinstyle=tk.ROUND, smooth=False)

    def _draw_endpoints(self) -> None:
        assert self.puzzle is not None
        # Draw one shared source per utility, not one duplicated source per house port.
        for kind, cell in self.puzzle.utility_sources.items():
            dummy = Connection(pid=-1, kind=kind, label=UTILITY_INFO[kind]["letter"], start=cell, goal=cell)
            self._draw_endpoint(cell, dummy, source=True, label=UTILITY_INFO[kind]["letter"])
        for conn in self.puzzle.connections:
            self._draw_endpoint(conn.goal, conn, source=False, label=conn.label)

    def _draw_endpoint(self, cell: Point, conn: Connection, source: bool, label: Optional[str] = None) -> None:
        cx, cy = self._cell_center(cell)
        r = self._terminal_radius()
        if source:
            self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, fill=conn.color, outline="#263238", width=2)
        else:
            self.canvas.create_rectangle(cx - r, cy - r, cx + r, cy + r, fill=conn.color, outline="#263238", width=2)
        self.canvas.create_text(cx, cy, text=label or conn.label, fill="white", font=("TkDefaultFont", max(7, self.cell_size.get() // 3), "bold"))

    def _draw_selection(self) -> None:
        if self.selected_pid is None or not self.puzzle:
            return
        stroke = self.user_strokes.get(self.selected_pid, [])
        if not stroke:
            return
        x, y = stroke[-1]
        r = self._terminal_radius() * 0.65
        self.canvas.create_oval(x - r, y - r, x + r, y + r, outline="#111111", width=3)

    # ---------------------------- coordinates -------------------------------

    def _recalculate_board_transform(self) -> None:
        assert self.puzzle is not None
        cell = self.cell_size.get()
        self.canvas_w = self.puzzle.width * cell + CANVAS_PAD * 2
        self.canvas_h = self.puzzle.height * cell + CANVAS_PAD * 2
        self.board_x = CANVAS_PAD
        self.board_y = CANVAS_PAD
        # Re-anchor untouched source-only strokes when cell size changes.
        for conn in self.puzzle.connections:
            stroke = self.user_strokes.get(conn.pid, [])
            if self.dragging and conn.pid == self.selected_pid:
                continue
            if len(stroke) <= 1 and conn.pid not in self.connected:
                self.user_strokes[conn.pid] = [self._cell_center(conn.start)]

    def _cell_top_left(self, cell: Point) -> Pixel:
        x, y = cell
        return self.board_x + x * self.cell_size.get(), self.board_y + y * self.cell_size.get()

    def _cell_center(self, cell: Point) -> Pixel:
        x0, y0 = self._cell_top_left(cell)
        half = self.cell_size.get() / 2
        return x0 + half, y0 + half

    def _pixel_to_cell(self, p: Pixel) -> Optional[Point]:
        if not self.puzzle:
            return None
        x = int((p[0] - self.board_x) // self.cell_size.get())
        y = int((p[1] - self.board_y) // self.cell_size.get())
        cell = (x, y)
        if in_bounds(cell, self.puzzle.width, self.puzzle.height):
            return cell
        return None

    def _path_to_coords(self, path: Sequence[Point]) -> List[float]:
        coords: List[float] = []
        for cell in path:
            cx, cy = self._cell_center(cell)
            coords.extend([cx, cy])
        return coords

    def _clamp_to_board(self, pos: Pixel) -> Pixel:
        if not self.puzzle:
            return pos
        x0, y0 = self.board_x, self.board_y
        x1 = x0 + self.puzzle.width * self.cell_size.get()
        y1 = y0 + self.puzzle.height * self.cell_size.get()
        return max(x0, min(x1, pos[0])), max(y0, min(y1, pos[1]))

    def _terminal_radius(self) -> float:
        return max(7.0, self.cell_size.get() * 0.38)

    def _conn_by_id(self, pid: int) -> Optional[Connection]:
        if not self.puzzle:
            return None
        for conn in self.puzzle.connections:
            if conn.pid == pid:
                return conn
        return None

    def _simplify_stroke(self, stroke: List[Pixel]) -> List[Pixel]:
        if len(stroke) <= 2:
            return stroke
        simplified = [stroke[0]]
        min_dist = max(2.0, self.cell_size.get() / 9)
        for p in stroke[1:-1]:
            if distance(p, simplified[-1]) >= min_dist:
                simplified.append(p)
        simplified.append(stroke[-1])
        return simplified

    def _point_to_segment_distance(self, p: Pixel, a: Pixel, b: Pixel) -> float:
        ax, ay = a
        bx, by = b
        px, py = p
        dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0:
            return distance(p, a)
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        proj = (ax + t * dx, ay + t * dy)
        return distance(p, proj)


def main() -> None:
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.15)
    except tk.TclError:
        pass
    InfinitePipesApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
