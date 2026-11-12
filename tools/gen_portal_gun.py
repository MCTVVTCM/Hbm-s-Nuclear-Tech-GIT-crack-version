#!/usr/bin/env python3
"""Generates the portal gun .obj model + all textures for HBM's NTM (1.7.10).

The body is a surface of revolution (that rounded ray-gun silhouette), the muzzle is a
flared dish with a glowing funnel, the grip is a swept arc. Units are blocks, the gun
points towards +Z, origin at the top of the grip.
"""
import math
import os
import random

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "src", "main", "resources", "assets", "hbm")
MODELS = os.path.join(ROOT, "models")
TEX = os.path.join(ROOT, "textures")

ATLAS = 128
CELL = 32
CELLS = {
    "metal": (1, 0),
    "dark": (0, 0),
    "glow": (2, 0),
    "grip": (3, 0),
    "accent": (0, 1),
    "red": (1, 1),
}


def uv(cell):
    cx, cy = CELLS[cell]
    inset = 2.0
    return ((cx * CELL + inset) / ATLAS,
            (cy * CELL + inset) / ATLAS,
            ((cx + 1) * CELL - inset) / ATLAS,
            ((cy + 1) * CELL - inset) / ATLAS)


def norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1.0
    return (v[0] / l, v[1] / l, v[2] / l)


class Obj:
    def __init__(self):
        self.v = []
        self.vt = []
        self.vn = []
        self.faces = []
        self.group = "body"

    def _add(self, p, n, tu, tv):
        self.v.append(p)
        self.vt.append((tu, tv))
        self.vn.append(n)
        return (len(self.v), len(self.vt), len(self.vn))

    def quadv(self, pts, normals, cell):
        u0, v0, u1, v1 = uv(cell)
        coords = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
        ids = [self._add(p, n, tu, tv) for p, n, (tu, tv) in zip(pts, normals, coords)]
        self.faces.append((self.group, ids))

    def quad(self, p0, p1, p2, p3, normal, cell):
        self.quadv([p0, p1, p2, p3], [normal] * 4, cell)

    def box(self, center, size, cell, rot_x=0.0, rot_z=0.0):
        cx, cy, cz = center
        sx, sy, sz = (v * 0.5 for v in size)
        cos_x, sin_x = math.cos(rot_x), math.sin(rot_x)
        cos_z, sin_z = math.cos(rot_z), math.sin(rot_z)

        pts = {}
        for ix in (-1, 1):
            for iy in (-1, 1):
                for iz in (-1, 1):
                    x, y, z = ix * sx, iy * sy, iz * sz
                    y, z = y * cos_x - z * sin_x, y * sin_x + z * cos_x
                    x, y = x * cos_z - y * sin_z, x * sin_z + y * cos_z
                    pts[(ix, iy, iz)] = (cx + x, cy + y, cz + z)

        def c(ix, iy, iz):
            return pts[(ix, iy, iz)]

        self.quad(c(-1, -1, 1), c(1, -1, 1), c(1, 1, 1), c(-1, 1, 1), (0, 0, 1), cell)
        self.quad(c(1, -1, -1), c(-1, -1, -1), c(-1, 1, -1), c(1, 1, -1), (0, 0, -1), cell)
        self.quad(c(1, -1, 1), c(1, -1, -1), c(1, 1, -1), c(1, 1, 1), (1, 0, 0), cell)
        self.quad(c(-1, -1, -1), c(-1, -1, 1), c(-1, 1, 1), c(-1, 1, -1), (-1, 0, 0), cell)
        self.quad(c(-1, 1, 1), c(1, 1, 1), c(1, 1, -1), c(-1, 1, -1), (0, 1, 0), cell)
        self.quad(c(-1, -1, -1), c(1, -1, -1), c(1, -1, 1), c(-1, -1, 1), (0, -1, 0), cell)

    def revolve(self, profile, segments, cell, cap_back=None, cap_front=None):
        """profile: [(z, radius), ...] from back to front."""
        step = 2 * math.pi / segments
        rings = []
        for z, r in profile:
            rings.append([(r * math.cos(i * step), r * math.sin(i * step), z) for i in range(segments)])

        for k in range(len(profile) - 1):
            z0, r0 = profile[k]
            z1, r1 = profile[k + 1]
            dz, dr = z1 - z0, r1 - r0
            if dz == 0 and dr == 0:
                continue
            for i in range(segments):
                a0, a1 = i * step, (i + 1) * step
                n0 = norm((dz * math.cos(a0), dz * math.sin(a0), -dr))
                n1 = norm((dz * math.cos(a1), dz * math.sin(a1), -dr))
                self.quadv([rings[k][i], rings[k][(i + 1) % segments],
                            rings[k + 1][(i + 1) % segments], rings[k + 1][i]],
                           [n0, n1, n1, n0], cell)

        for idx, cap_cell in ((0, cap_back), (len(profile) - 1, cap_front)):
            if cap_cell is None:
                continue
            z, r = profile[idx]
            nz = (1, 0, 0) if idx == 0 else (-1, 0, 0)
            centre = self._add((0, 0, z), (0, 0, -1 if idx == 0 else 1), *uv(cap_cell)[:2])
            ring = [self._add(p, (0, 0, -1 if idx == 0 else 1), *uv(cap_cell)[:2]) for p in rings[idx]]
            self._fan(centre, ring, flip=(idx != 0))
            del nz

    def revolve_y(self, profile, segments, cell, cx=0.0, cz=0.0, cap_bottom=None, cap_top=None):
        """Surface of revolution around the Y axis; profile is [(y, radius), ...] bottom to top."""
        step = 2 * math.pi / segments
        rings = []
        for y, r in profile:
            rings.append([(cx + r * math.cos(i * step), y, cz + r * math.sin(i * step)) for i in range(segments)])

        for k in range(len(profile) - 1):
            y0, r0 = profile[k]
            y1, r1 = profile[k + 1]
            dy, dr = y1 - y0, r1 - r0
            if dy == 0 and dr == 0:
                continue
            for i in range(segments):
                a0, a1 = i * step, (i + 1) * step
                n0 = norm((dy * math.cos(a0), -dr, dy * math.sin(a0)))
                n1 = norm((dy * math.cos(a1), -dr, dy * math.sin(a1)))
                j = (i + 1) % segments
                self.quadv([rings[k][i], rings[k][j], rings[k + 1][j], rings[k + 1][i]],
                           [n0, n0, n1, n1], cell)

        for idx, cap_cell in ((0, cap_bottom), (len(profile) - 1, cap_top)):
            if cap_cell is None or profile[idx][1] <= 0:
                continue
            y = profile[idx][0]
            nz = -1 if idx == 0 else 1
            u0 = uv(cap_cell)[0]
            centre = self._add((cx, y, cz), (0, nz, 0), u0, u0)
            ring = [self._add(p, (0, nz, 0), u0, u0) for p in rings[idx]]
            self._fan(centre, ring, flip=(nz < 0))

    def cylinder(self, cx, cy, z0, z1, r, segments, cell, caps=True):
        """Cylinder along the Z axis at (cx, cy)."""
        step = 2 * math.pi / segments
        ring0 = [(cx + r * math.cos(i * step), cy + r * math.sin(i * step), z0) for i in range(segments)]
        ring1 = [(cx + r * math.cos(i * step), cy + r * math.sin(i * step), z1) for i in range(segments)]

        for i in range(segments):
            a0, a1 = i * step, (i + 1) * step
            n0 = (math.cos(a0), math.sin(a0), 0)
            n1 = (math.cos(a1), math.sin(a1), 0)
            j = (i + 1) % segments
            self.quadv([ring0[i], ring1[i], ring1[j], ring0[j]], [n0, n0, n1, n1], cell)

        if not caps:
            return

        for ring, z, nz in ((ring0, z0, -1), (ring1, z1, 1)):
            u0 = uv(cell)[0]
            centre = self._add((cx, cy, z), (0, 0, nz), u0, u0)
            pts = [self._add(p, (0, 0, nz), u0, u0) for p in ring]
            self._fan(centre, pts, flip=(nz < 0))

    def _fan(self, centre, ring, flip=False):
        """Emit a triangle fan as quads - Forge's 1.7.10 obj parser only accepts faces with 4 points."""
        n = len(ring)
        if n % 2 != 0:
            raise ValueError("ring size must be even for quad fans, got %d" % n)
        for i in range(0, n, 2):
            a, b, c = ring[i], ring[(i + 1) % n], ring[(i + 2) % n]
            self.faces.append((self.group, [centre, c, b, a] if flip else [centre, a, b, c]))

    def prism(self, profile, half_width, cell, side_cell=None):
        """profile: [(z, y), ...] convex polygon in the ZY plane, extruded along +-X."""
        side_cell = side_cell or cell
        # centroid for the side fan
        cz = sum(p[0] for p in profile) / len(profile)
        cy = sum(p[1] for p in profile) / len(profile)

        def verts(x):
            return [(x, y, z) for (z, y) in profile]

        left, right = verts(-half_width), verts(half_width)

        # side walls (fan from centroid)
        for sign, pts, nx in ((-1, left, -1), (1, right, 1)):
            centre = self._add((sign * half_width, cy, cz), (nx, 0, 0), *uv(side_cell)[:2])
            ring = [self._add(p, (nx, 0, 0), *uv(side_cell)[:2]) for p in pts]
            self._fan(centre, ring, flip=(nx < 0))

        # rim quads
        n = len(profile)
        for i in range(n):
            j = (i + 1) % n
            p0, p1 = profile[i], profile[j]
            dz, dy = p1[0] - p0[0], p1[1] - p0[1]
            normal = norm((0, dz, -dy))
            self.quadv([left[i], left[j], right[j], right[i]], [normal] * 4, cell)

    def write(self, path):
        lines = ["# HBM portal gun", "# generated by tools/gen_portal_gun.py", ""]
        for x, y, z in self.v:
            lines.append("v %.5f %.5f %.5f" % (x, y, z))
        for u, v in self.vt:
            lines.append("vt %.5f %.5f" % (u, v))
        for x, y, z in self.vn:
            lines.append("vn %.5f %.5f %.5f" % (x, y, z))

        # one "g" block per group: repeated group headers break renderPart() on some loaders
        order = ["body", "barrel", "detail", "grip", "glow"]
        buckets = {}
        for group, ids in self.faces:
            buckets.setdefault(group, []).append(ids)
        for group in order + [g for g in buckets if g not in order]:
            if group not in buckets:
                continue
            lines.append("")
            lines.append("g " + group)
            for ids in buckets[group]:
                lines.append("f " + " ".join("%d/%d/%d" % i for i in ids))
        open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
        print("wrote", path, len(self.v), "verts", len(self.faces), "faces")


def build_model():
    o = Obj()

    # ---- flat slab body ----
    o.group = "body"
    o.prism([
        (-0.300, 0.070),
        (0.400, 0.078),
        (0.430, -0.088),
        (0.130, -0.100),
        (-0.060, -0.096),
        (-0.300, -0.040),
    ], 0.130, "metal")

    # thin seam along both sides + top spine
    o.box((0.132, -0.010, 0.120), (0.006, 0.012, 0.520), "dark")
    o.box((-0.132, -0.010, 0.120), (0.006, 0.012, 0.520), "dark")
    o.box((0, 0.082, 0.100), (0.090, 0.012, 0.440), "dark")

    # ---- muzzle: dark plate + three emitters ----
    o.group = "detail"
    o.box((0, -0.004, 0.452), (0.268, 0.156, 0.030), "dark")

    for x in (-0.078, 0.0, 0.078):
        o.group = "detail"
        o.cylinder(x, -0.004, 0.466, 0.477, 0.038, 14, "dark", caps=True)
        o.group = "glow"
        o.cylinder(x, -0.004, 0.470, 0.482, 0.026, 14, "glow", caps=True)

    # ---- plasma bulb on top ----
    o.group = "detail"
    o.revolve_y([(0.060, 0.052), (0.072, 0.058)], 16, "dark", cz=-0.085)   # socket
    o.group = "glow"
    o.revolve_y([
        (0.068, 0.046),
        (0.090, 0.058),
        (0.130, 0.062),
        (0.175, 0.055),
        (0.205, 0.038),
        (0.220, 0.000),
    ], 18, "glow", cz=-0.085)

    # ---- red panel near the grip ----
    o.group = "detail"
    o.box((0.134, -0.030, -0.150), (0.008, 0.052, 0.130), "red")
    o.box((-0.134, -0.030, -0.150), (0.008, 0.052, 0.130), "red")

    # ---- grip ----
    o.group = "grip"
    o.box((0, -0.155, -0.215), (0.095, 0.190, 0.115), "grip", rot_x=math.radians(-8))
    o.box((0, -0.250, -0.240), (0.105, 0.040, 0.130), "dark", rot_x=math.radians(-8))
    o.box((0, -0.105, -0.120), (0.070, 0.045, 0.060), "dark")   # trigger block

    # ---- small greebles ----
    o.group = "detail"
    o.box((0.0, 0.080, -0.240), (0.060, 0.020, 0.060), "accent")  # rear top button
    o.box((0.0, -0.070, 0.300), (0.180, 0.020, 0.060), "accent")  # underside stripe

    return o


# ---------------------------------------------------------------- textures

def tex_portal_gun():
    from PIL import Image, ImageDraw
    rnd = random.Random(11)
    img = Image.new("RGBA", (ATLAS, ATLAS), (40, 43, 48, 255))
    d = ImageDraw.Draw(img)

    def cell_box(name):
        cx, cy = CELLS[name]
        return cx * CELL, cy * CELL, (cx + 1) * CELL, (cy + 1) * CELL

    # dark metal
    x0, y0, x1, y1 = cell_box("dark")
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(26, 38, 32, 255))
    for i in range(0, CELL, 8):
        d.line([x0, y0 + i, x1 - 1, y0 + i], fill=(35, 38, 43, 255))
    for _ in range(50):
        d.point((rnd.randint(x0 + 1, x1 - 2), rnd.randint(y0 + 1, y1 - 2)), fill=(60, 64, 71, 255))

    # light metal
    x0, y0, x1, y1 = cell_box("metal")
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(226, 229, 233, 255))
    for i in range(0, CELL, 4):
        shade = 218 + (i % 8) * 2
        d.line([x0, y0 + i, x1 - 1, y0 + i], fill=(shade, shade + 4, shade + 8, 255))
    for _ in range(80):
        d.point((rnd.randint(x0 + 1, x1 - 2), rnd.randint(y0 + 1, y1 - 2)), fill=(240, 243, 246, 255))
    d.rectangle([x0 + 2, y0 + 2, x1 - 3, y1 - 3], outline=(196, 200, 206, 255))
    for cx2, cy2 in ((5, 5), (CELL - 7, 5), (5, CELL - 7), (CELL - 7, CELL - 7)):
        d.ellipse([x0 + cx2, y0 + cy2, x0 + cx2 + 3, y0 + cy2 + 3], fill=(150, 156, 166, 255))

    # glow: bright green with a hot core line
    x0, y0, x1, y1 = cell_box("glow")
    for y in range(y0, y1):
        for x in range(x0, x1):
            t = (y - y0) / float(CELL)
            wave = 0.5 + 0.5 * math.sin(t * 10.0)
            g = int(200 + 55 * wave)
            img.putpixel((x, y), (int(50 + 90 * wave), g, int(110 + 70 * wave), 255))
    d.rectangle([x0, y0 + CELL // 2 - 1, x1 - 1, y0 + CELL // 2 + 1], fill=(235, 255, 240, 255))

    # grip
    x0, y0, x1, y1 = cell_box("grip")
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(28, 29, 33, 255))
    for i in range(0, CELL, 4):
        d.line([x0, y0 + i, x1 - 1, y0 + i], fill=(36, 38, 42, 255))
        d.line([x0 + i, y0, x0 + i, y1 - 1], fill=(33, 35, 39, 255))

    # accent
    x0, y0, x1, y1 = cell_box("accent")
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(238, 238, 242, 255))
    d.rectangle([x0, y0 + CELL // 2 - 3, x1 - 1, y0 + CELL // 2 + 3], fill=(255, 138, 40, 255))

    # red panel
    x0, y0, x1, y1 = cell_box("red")
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(170, 26, 34, 255))
    d.rectangle([x0 + 1, y0 + 1, x1 - 2, y1 - 2], fill=(198, 34, 44, 255))
    for _ in range(40):
        d.point((rnd.randint(x0 + 2, x1 - 3), rnd.randint(y0 + 2, y1 - 3)), fill=(150, 22, 30, 255))

    path = os.path.join(TEX, "models", "portal_gun.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", path)


def tex_portal():
    from PIL import Image
    size = 256
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            dx = (x - c) / c
            dy = (y - c) / c
            d = math.hypot(dx, dy)
            if d > 1.02:
                continue
            ang = math.atan2(dy, dx)
            swirl = math.sin(ang * 3.0 + 7.5 * d - 2.0) * 0.5 + 0.5
            core = math.exp(-(d * 3.1) ** 2)
            ring = math.exp(-((d - 0.62) * 7.0) ** 2)
            k = min(1.6, 0.10 + 0.55 * swirl * (1.0 - d) + 1.35 * core + 0.75 * ring)
            px[x, y] = (int(min(255, 40 + 165 * k)), int(min(255, 40 + 200 * k)),
                        int(min(255, 90 + 130 * k)),
                        int(255 * max(0.0, min(1.0, (1.02 - d) * 2.4))))
    path = os.path.join(TEX, "entity", "portal.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", path)


def tex_icon():
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # flat slab body, muzzle towards the lower right
    d.polygon([(1, 5), (12, 4), (15, 7), (14, 9), (2, 9)], fill=(226, 229, 233, 255))
    d.polygon([(1, 5), (12, 4), (12, 5), (2, 7)], fill=(248, 249, 251, 255))
    # muzzle plate + three emitters
    d.polygon([(12, 5), (15, 7), (15, 10), (12, 8)], fill=(28, 40, 33, 255))
    d.point((13, 7), fill=(120, 255, 160, 255))
    d.point((14, 8), fill=(120, 255, 160, 255))
    d.point((13, 8), fill=(90, 240, 130, 255))
    # green bulb on top
    d.ellipse([5, 1, 10, 4], fill=(110, 255, 150, 255))
    d.ellipse([6, 1, 8, 2], fill=(220, 255, 230, 255))
    # red panel + grip
    d.rectangle([5, 8, 8, 9], fill=(190, 32, 42, 255))
    d.polygon([(5, 9), (8, 9), (7, 15), (4, 15)], fill=(40, 40, 44, 255))
    path = os.path.join(TEX, "items", "portal_gun.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", path)


if __name__ == "__main__":
    os.makedirs(MODELS, exist_ok=True)
    build_model().write(os.path.join(MODELS, "portal_gun.obj"))
    tex_portal_gun()
    tex_portal()
    tex_icon()
