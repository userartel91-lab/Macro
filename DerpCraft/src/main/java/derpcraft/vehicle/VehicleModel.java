package derpcraft.vehicle;

import derpcraft.model.Model3D;

import java.util.*;

/**
 * What a vehicle model is made of, found from the names of its Blockbench groups (.bbmodel):
 * <ul>
 *   <li>{@code wheel*} — wheels: spin with the speed; the front ones turn with the steering;</li>
 *   <li>{@code door*}, {@code hood}, {@code trunk} (also bonnet, boot, hatch, lid) — open and close around their pivot;</li>
 *   <li>{@code steering*} — the steering wheel: turns around its column (only what is near the pivot turns);</li>
 *   <li>{@code seat*} — seats: where riders sit (the seat nearest the steering wheel is the driver's);</li>
 *   <li>{@code block1} .. {@code block4} — places for blocks: a block stands with the middle of its bottom on the pivot;</li>
 *   <li>everything else — the body.</li>
 * </ul>
 * The front of the model is -Z (north in Blockbench), y up, 16 pixels = 1 block. Local coordinates used by vehicles
 * have their origin between the axles, on the ground under the wheels.
 */
public final class VehicleModel {
    public static final int BODY = 0, WHEEL = 1, OPENABLE = 2, STEERING = 3;
    public static final int MAX_SLOTS = 4;
    /** Triangles per box of the ray test acceleration. */
    public static final int CHUNK = 16;

    /** An animated (or static) piece of the model. */
    public static final class Part {
        public final String name;
        public final int role;
        /** The name the role was taken from (the settings can rename a group: "bag" counts as "trunk"). */
        public String kind;
        /** Pivot in local coordinates (blocks). */
        public float px, py, pz;
        public final List<Model3D.Group> groups = new ArrayList<>();
        /** Bounds of its triangles (local). */
        public float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        /** Per group: boxes {x0,y0,z0,x1,y1,z1} around runs of CHUNK triangles (sorted along the part), for fast ray tests. */
        public float[][] chunks = new float[0][];
        /** The pivot was not in the model (OBJ): found from the shape. */
        boolean autoPivot;
        // wheels
        public boolean front;
        public float radius;
        // doors, hood, trunk: hinge axis (0 = x, 1 = y) and the opening angle in degrees (signed)
        public int axis;
        public float openAngle;
        /** The sign the geometry gives the opening angle (the settings' angle is relative to it: a negative one turns it the other way). */
        public float geoSign = 1;
        // steering wheel: the column axis (towards the driver)
        public float ax, ay = 0, az = 1;

        Part(String name, int role) { this.name = name; this.role = role; }

        public int triangles() { int n = 0; for (Model3D.Group g : groups) n += g.tris; return n; }

        Model3D.Group group(Model3D.Group of) {
            Model3D.Group src = of.shared != null ? of.shared : of;
            for (Model3D.Group g : groups) if (g.shared == src) return g;
            Model3D.Group g = new Model3D.Group();
            g.shared = src;
            g.image = src.image;
            g.color = src.color;
            groups.add(g);
            return g;
        }

        void grow(float x, float y, float z) {
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
    }

    /** Model-space point that is the local origin (between the axles, under the wheels). */
    public final float ox, oy, oz;
    public final List<Part> parts = new ArrayList<>();
    public final List<Part> wheels = new ArrayList<>(), openables = new ArrayList<>();
    public Part steering;
    /** Seat surfaces {x, y, z} (local): a rider's hips rest there. */
    public final List<float[]> seats = new ArrayList<>();
    /** Camera point of each seat (local), or null = above the seat as usual; as long as 'seats'. */
    public final List<float[]> eyes = new ArrayList<>();
    /** Turn of each seat around the vertical (degrees, 0 = facing the front); as long as 'seats'. */
    public final List<Float> seatYaw = new ArrayList<>();
    public int driverSeat;
    /** The door (index into openables) to get into each seat, or -1 (no side doors: get in freely). */
    public int[] seatDoor = new int[0];
    /** Block places {x, y, z} (local): the bottom centre of the block. */
    public final List<float[]> slots = new ArrayList<>();
    public final List<String> slotNames = new ArrayList<>();
    /** Distance between front and rear axle, half the track (blocks). */
    public float wheelBase = 2.5f, halfTrack = 0.8f;
    public float frontZ = -1.25f, rearZ = 1.25f, wheelRadius = 0.4f;
    /** Bounds of the whole model (local). */
    public float minX, maxX, minY, maxY, minZ, maxZ;
    /** Collision pieces along the length: {z0, z1, x0, x1, top} (local). */
    public final List<float[]> segments = new ArrayList<>();
    /** Triangles that the ray tests use: per part, model space (see {@link Part#groups}). */
    public final String problem;

    private VehicleModel(Model3D m, VehicleType cfg) {
        List<Model3D.Bone> bones = prepare(m, cfg);
        // ---- which bone belongs to which role
        int n = bones.size();
        int[] owner = new int[n];        // bone index of the role part it belongs to, or -1 = body
        int[] role = new int[n];
        String[] eff = new String[n];                  // the name the roles are taken from (settings can rename: "bag" -> "trunk")
        for (int i = 0; i < n; i++) {
            String nm = bones.get(i).name;
            String alias = cfg == null ? null : cfg.roles.get(nm.toLowerCase(Locale.ROOT));
            eff[i] = alias != null ? alias : nm;
            role[i] = roleOf(eff[i]);
        }
        for (int i = 0; i < n; i++) {
            owner[i] = -1;
            for (int b = i; b >= 0; b = bones.get(b).parent) {
                int r = role[b];
                if (r == WHEEL || r == OPENABLE || r == STEERING) { owner[i] = b; break; }
            }
        }
        // ---- raw parts in model space first (the origin is found from the wheels)
        Map<Integer, Part> byBone = new LinkedHashMap<>();
        Part body = new Part("body", BODY);
        List<Model3D.Bone> seatBones = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Model3D.Bone b = bones.get(i);
            String ln = b.name.toLowerCase(Locale.ROOT);
            if (role[i] == SEAT) seatBones.add(b);
            if (role[i] == SLOT) {
                // a block place: the group's pivot, or (OBJ) the middle of the bottom of its marker box, which is not drawn
                if (slots.size() < MAX_SLOTS) {
                    float[] sp = {b.px, b.py, b.pz};
                    if (!b.pivotSet && b.triangles() > 0) {
                        Part tmp = new Part("", BODY);
                        for (Model3D.Group g : b.groups) { Model3D.Group d = tmp.group(g); for (int t = 0; t < g.tris; t++) d.copyTri(g, t); }
                        boundsOf(tmp);
                        sp = new float[]{(tmp.x0 + tmp.x1) / 2, tmp.y0, (tmp.z0 + tmp.z1) / 2};
                    }
                    slots.add(sp); slotNames.add(ln);
                }
                continue;
            }
            int o = owner[i];
            Part target;
            if (o < 0) target = body;
            else target = byBone.computeIfAbsent(o, k -> {
                Model3D.Bone ob = bones.get(k);
                Part p = new Part(ob.name, role[k]);
                p.kind = eff[k].toLowerCase(Locale.ROOT);
                p.px = ob.px; p.py = ob.py; p.pz = ob.pz;
                p.autoPivot = !ob.pivotSet;
                return p;
            });
            for (Model3D.Group g : b.groups) {
                Model3D.Group dst = target.group(g);
                for (int t = 0; t < g.tris; t++) dst.copyTri(g, t);
            }
        }
        // slots sorted by name (block1, block2, ...)
        Integer[] order = new Integer[slots.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparing(i -> slotNames.get(i)));
        List<float[]> ss = new ArrayList<>(); List<String> sn = new ArrayList<>();
        for (int i : order) { ss.add(slots.get(i)); sn.add(slotNames.get(i)); }
        slots.clear(); slots.addAll(ss); slotNames.clear(); slotNames.addAll(sn);

        for (Part p : byBone.values()) if (p.triangles() > 0) parts.add(p);
        autoPivots(parts, body, cfg);
        // the steering wheel: only what is near its pivot turns (a gear lever in the same group stays put)
        for (Part p : new ArrayList<>(parts)) {
            if (p.role != STEERING) continue;
            float r = 0.5f;
            for (Model3D.Group g : new ArrayList<>(p.groups)) {
                Model3D.Group keep = new Model3D.Group();
                keep.shared = g.shared; keep.image = g.image; keep.color = g.color;
                Model3D.Group still = body.group(g);
                for (int t = 0; t < g.tris; t++) {
                    int o = t * 15;
                    float cx = (g.v[o] + g.v[o + 5] + g.v[o + 10]) / 3 - p.px, cy = (g.v[o + 1] + g.v[o + 6] + g.v[o + 11]) / 3 - p.py, cz = (g.v[o + 2] + g.v[o + 7] + g.v[o + 12]) / 3 - p.pz;
                    if (cx * cx + cy * cy + cz * cz <= r * r) keep.copyTri(g, t); else still.copyTri(g, t);
                }
                g.v = keep.v; g.shade = keep.shade; g.tris = keep.tris;
            }
        }
        if (body.triangles() > 0) parts.add(0, body);

        // ---- wheels: origin between the axles, under the wheels
        float wx = 0, wFront = Float.MAX_VALUE, wRear = -Float.MAX_VALUE, wBottom = Float.MAX_VALUE;
        int wc = 0;
        for (Part p : parts) {
            if (p.role != WHEEL) continue;
            boundsOf(p);
            wx += p.px; wc++;
            wFront = Math.min(wFront, p.pz); wRear = Math.max(wRear, p.pz);
            wBottom = Math.min(wBottom, p.y0);
        }
        float mx0 = m.minX, mx1 = m.maxX, mz0 = m.minZ, mz1 = m.maxZ;
        if (wc >= 2) {
            ox = wx / wc;
            oz = (wFront + wRear) / 2;
            oy = wBottom;
        } else {
            ox = (mx0 + mx1) / 2; oz = (mz0 + mz1) / 2; oy = m.minY;
        }
        // ---- to local coordinates
        for (Part p : parts) {
            p.px -= ox; p.py -= oy; p.pz -= oz;
            for (Model3D.Group g : p.groups)
                for (int t = 0; t < g.tris * 3; t++) { g.v[t * 5] -= ox; g.v[t * 5 + 1] -= oy; g.v[t * 5 + 2] -= oz; }
            boundsOf(p);
        }
        for (float[] s : slots) { s[0] -= ox; s[1] -= oy; s[2] -= oz; }
        if (cfg != null && cfg.blocksSet) {        // block places from the settings (car's own coordinates) replace the model's
            slots.clear(); slotNames.clear();
            for (float[] b : cfg.blocks) { if (slots.size() < MAX_SLOTS) { slots.add(b.clone()); slotNames.add("block" + slots.size()); } }
        }
        if (cfg != null) {             // pivots from the settings are in the car's own coordinates
            for (Part p : parts) {
                float[] ov = cfg.pivots.get(p.name.toLowerCase(Locale.ROOT));
                if (ov != null) { p.px = ov[0]; p.py = ov[1]; p.pz = ov[2]; }
            }
            if (!cfg.blocksSet) for (int i = 0; i < slots.size(); i++) {
                float[] ov = cfg.pivots.get(slotNames.get(i));
                if (ov != null) slots.set(i, ov.clone());
            }
        }
        minX = minY = minZ = Float.MAX_VALUE; maxX = maxY = maxZ = -Float.MAX_VALUE;
        for (Part p : parts) {
            minY = Math.min(minY, p.y0); minZ = Math.min(minZ, p.z0);
            maxY = Math.max(maxY, p.y1); maxZ = Math.max(maxZ, p.z1);
            if (p.role != OPENABLE) { minX = Math.min(minX, p.x0); maxX = Math.max(maxX, p.x1); }     // closed doors are inside the body: stray bits of them do not widen the car
        }
        if (minX > maxX) for (Part p : parts) { minX = Math.min(minX, p.x0); maxX = Math.max(maxX, p.x1); }
        if (parts.isEmpty()) { minX = minY = minZ = -0.5f; maxX = maxY = maxZ = 0.5f; }

        // ---- wheels
        float sumR = 0;
        for (Part p : parts) {
            if (p.role != WHEEL) continue;
            wheels.add(p);
            p.radius = Math.max(0.05f, (p.y1 - p.y0) / 2);
            p.py = p.y0 + p.radius;              // spin around the middle of the wheel
            sumR += p.radius;
        }
        if (!wheels.isEmpty()) {
            float zf = Float.MAX_VALUE, zr = -Float.MAX_VALUE, tr = 0;
            for (Part p : wheels) { zf = Math.min(zf, p.pz); zr = Math.max(zr, p.pz); tr = Math.max(tr, Math.abs(p.px)); }
            for (Part p : wheels) p.front = wheels.size() >= 3 ? p.pz < (zf + zr) / 2 - 0.01f : p.pz <= zf + 0.01f;
            frontZ = zf; rearZ = zr;
            wheelBase = Math.max(0.5f, zr - zf);
            halfTrack = Math.max(0.3f, tr);
            wheelRadius = sumR / wheels.size();
        } else {
            frontZ = minZ * 0.6f; rearZ = maxZ * 0.6f; wheelBase = Math.max(0.5f, rearZ - frontZ); halfTrack = Math.max(0.3f, (maxX - minX) / 2 * 0.8f);
        }

        // ---- doors, hood, trunk
        float carCx = (minX + maxX) / 2, carCz = (minZ + maxZ) / 2;
        for (Part p : parts) {
            if (p.role != OPENABLE) continue;
            openables.add(p);
            float dx = p.x1 - p.x0, dy = p.y1 - p.y0, dz = p.z1 - p.z0;
            float cx = (p.x0 + p.x1) / 2 - p.px, cz = (p.z0 + p.z1) / 2 - p.pz;
            String ln = p.kind;
            boolean hood = ln.contains("hood") || ln.contains("bonnet") || ln.contains("kapot");
            boolean trunk = ln.contains("trunk") || ln.contains("boot") || ln.contains("bagazh") || ln.contains("hatch");
            float angle = cfg == null ? (hood ? 60 : trunk ? 65 : 70) : hood ? cfg.hoodAngle : trunk ? cfg.trunkAngle : cfg.doorAngle;
            Float own = cfg == null ? null : cfg.angles.get(p.name.toLowerCase(Locale.ROOT));
            float flip = 1;
            if (own != null) { angle = Math.abs(own); if (own < 0) flip = -1; }
            if (dy <= dx && dy <= dz) {
                // lies flat (hood, trunk lid): turns around x, the free edge goes up
                p.axis = 0;
                p.openAngle = flip * (cz < 0 ? 1 : -1) * angle;
            } else {
                p.axis = 1;
                if (dx <= dz) {
                    // a side door: the free edge swings out to its side
                    float out = Math.signum(((p.x0 + p.x1) / 2) - carCx);
                    if (out == 0) out = Math.signum(p.px - carCx);
                    p.openAngle = flip * (out == 0 ? 1 : out) * (cz == 0 ? 1 : Math.signum(cz)) * angle;
                } else {
                    // a door across the front / back (a rear hatch that swings sideways)
                    float out = Math.signum(((p.z0 + p.z1) / 2) - carCz);
                    p.openAngle = -flip * (out == 0 ? 1 : out) * (cx == 0 ? 1 : Math.signum(cx)) * angle;
                }
            }
            p.geoSign = p.openAngle == 0 ? 1 : Math.signum(p.openAngle) * flip;
        }

        // ---- steering wheel: the column axis is the thinnest direction of the wheel ring
        for (Part p : parts) {
            if (p.role != STEERING) continue;
            steering = p;
            float[] a = thinAxis(p);
            if (a[2] < 0 || (Math.abs(a[2]) < 1e-3f && a[1] < 0)) { a[0] = -a[0]; a[1] = -a[1]; a[2] = -a[2]; }   // towards the driver (+z)
            p.ax = a[0]; p.ay = a[1]; p.az = a[2];
            break;
        }

        // ---- seats
        for (Model3D.Bone b : seatBones) {
            List<Model3D.Group> gs = new ArrayList<>();
            collect(bones, b, gs);
            float[] s = seatSurface(gs, b.px - ox, b.py - oy, b.pz - oz);
            seats.add(s);
        }
        boolean ownSeats = cfg != null && !cfg.seats.isEmpty();       // seats from the settings (the editor) keep their order: it is the seat numbers
        if (ownSeats) { seats.clear(); for (float[] s : cfg.seats) seats.add(s.clone()); }
        else if (seats.isEmpty()) autoSeats();
        if (!ownSeats) seats.sort(Comparator.comparingDouble((float[] s) -> s[2]).thenComparingDouble(s -> s[0]));
        for (int i = 0; i < seats.size(); i++) seatYaw.add(cfg != null && i < cfg.seatYaws.size() ? cfg.seatYaws.get(i) : 0f);
        for (int i = 0; i < seats.size(); i++) { float[] e = cfg != null && i < cfg.eyes.size() ? cfg.eyes.get(i) : null; eyes.add(e == null ? null : e.clone()); }
        driverSeat = 0;
        if (steering != null && !seats.isEmpty()) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < seats.size(); i++) {
                float[] s = seats.get(i);
                double d = (s[0] - steering.px) * (s[0] - steering.px) + (s[2] - steering.pz) * (s[2] - steering.pz);
                if (d < best) { best = d; driverSeat = i; }
            }
        }

        // each seat's door: the nearest side door (hinged around the vertical axis), on the seat's side first
        seatDoor = new int[seats.size()];
        for (int i = 0; i < seats.size(); i++) {
            float[] st = seats.get(i);
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int k = 0; k < openables.size(); k++) {
                Part p = openables.get(k);
                if (p.axis != 1) continue;
                double cx = (p.x0 + p.x1) / 2, cz = (p.z0 + p.z1) / 2;
                double d = (cx - st[0]) * (cx - st[0]) + (cz - st[2]) * (cz - st[2]);
                if (Math.signum(cx) != Math.signum(st[0]) && st[0] != 0) d += 4;      // the other side: only if this side has none
                if (d < bd) { bd = d; best = k; }
            }
            seatDoor[i] = best;
        }

        // ---- collision pieces
        float len = maxZ - minZ;
        int ns = Math.max(1, (int) Math.ceil(len / 0.5f));
        for (int i = 0; i < ns; i++) {
            float z0 = minZ + len * i / ns, z1 = minZ + len * (i + 1) / ns;
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, top = 0, ax0 = Float.MAX_VALUE, ax1 = -Float.MAX_VALUE;
            for (Part p : parts) {
                boolean lid = p.role == OPENABLE;       // closed doors lie inside the body: their stray parts (mirrors, bars) do not widen the box
                for (Model3D.Group g : p.groups) {
                    float[] v = g.v;
                    for (int t = 0; t < g.tris; t++) {
                        int o = t * 15;
                        float tz0 = Math.min(v[o + 2], Math.min(v[o + 7], v[o + 12])), tz1 = Math.max(v[o + 2], Math.max(v[o + 7], v[o + 12]));
                        if (tz1 < z0 || tz0 > z1) continue;
                        float tx0 = Math.min(v[o], Math.min(v[o + 5], v[o + 10])), tx1 = Math.max(v[o], Math.max(v[o + 5], v[o + 10]));
                        ax0 = Math.min(ax0, tx0); ax1 = Math.max(ax1, tx1);
                        if (!lid) { x0 = Math.min(x0, tx0); x1 = Math.max(x1, tx1); }
                        top = Math.max(top, Math.max(v[o + 1], Math.max(v[o + 6], v[o + 11])));
                    }
                }
            }
            if (x0 > x1) { x0 = ax0; x1 = ax1; }
            if (x0 > x1 || top <= 0.05f) continue;
            segments.add(new float[]{z0, z1, x0, x1, top});
        }
        problem = wheels.size() < 2 ? "no wheel groups (name them wheel1, wheel2...)" : null;
        for (Part p : parts) buildChunks(p);
    }

    private static final int SEAT = 10, SLOT = 11;

    /**
     * A model without seat groups: the driver sits behind the steering wheel (about 0.42 behind its middle, on its side), the
     * front passenger on the other side, two more on the back row. The seats can be set in the settings ("seats").
     */
    private void autoSeats() {
        float sy = 0.55f;
        float sx = steering != null ? steering.px : -0.45f, sz = steering != null ? steering.pz + 0.42f : (frontZ + rearZ) / 2 * 0.3f;
        if (Math.abs(sx) < 0.2f) sx = sx < 0 ? -0.3f : 0.3f;
        float half = Math.abs(sx);
        seats.add(new float[]{sx, sy, sz});
        seats.add(new float[]{-sx, sy, sz});
        seats.add(new float[]{-half, sy, sz + 0.85f});
        seats.add(new float[]{half, sy, sz + 0.85f});
    }

    /** Sorts each group's triangles along the part's longest side and puts a box around every CHUNK of them. */
    private static void buildChunks(Part p) {
        float dx = p.x1 - p.x0, dy = p.y1 - p.y0, dz = p.z1 - p.z0;
        int axis = dx >= dy && dx >= dz ? 0 : dy >= dz ? 1 : 2;
        p.chunks = new float[p.groups.size()][];
        for (int gi = 0; gi < p.groups.size(); gi++) {
            Model3D.Group g = p.groups.get(gi);
            int n = g.tris;
            Integer[] idx = new Integer[n];
            float[] key = new float[n];
            for (int t = 0; t < n; t++) { idx[t] = t; key[t] = g.v[t * 15 + axis] + g.v[t * 15 + 5 + axis] + g.v[t * 15 + 10 + axis]; }
            Arrays.sort(idx, Comparator.comparingDouble(t -> key[t]));
            float[] nv = new float[Math.max(15, n * 15)], ns = new float[Math.max(1, n)];
            for (int t = 0; t < n; t++) { System.arraycopy(g.v, idx[t] * 15, nv, t * 15, 15); ns[t] = g.shade[idx[t]]; }
            g.v = nv; g.shade = ns;
            int nc = (n + CHUNK - 1) / CHUNK;
            float[] b = new float[nc * 6];
            for (int c = 0; c < nc; c++) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
                for (int t = c * CHUNK; t < Math.min(n, (c + 1) * CHUNK); t++)
                    for (int k = 0; k < 3; k++) {
                        int o = t * 15 + k * 5;
                        x0 = Math.min(x0, nv[o]); y0 = Math.min(y0, nv[o + 1]); z0 = Math.min(z0, nv[o + 2]);
                        x1 = Math.max(x1, nv[o]); y1 = Math.max(y1, nv[o + 1]); z1 = Math.max(z1, nv[o + 2]);
                    }
                float e = 0.01f;
                b[c * 6] = x0 - e; b[c * 6 + 1] = y0 - e; b[c * 6 + 2] = z0 - e; b[c * 6 + 3] = x1 + e; b[c * 6 + 4] = y1 + e; b[c * 6 + 5] = z1 + e;
            }
            p.chunks[gi] = b;
        }
    }

    static int roleOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("steer") || n.contains("steering") || n.equals("rul") || n.startsWith("rul_")) return STEERING;
        if (n.contains("wheel") || n.startsWith("koleso")) return WHEEL;
        if (n.startsWith("door") || n.contains("_door") || n.startsWith("dver") || n.equals("hood") || n.startsWith("hood") || n.startsWith("bonnet") || n.startsWith("kapot")
                || n.startsWith("trunk") || n.equals("boot") || n.startsWith("bagazh") || n.startsWith("hatch") || n.equals("lid")) return OPENABLE;
        if (n.startsWith("seat") || n.startsWith("sid")) return SEAT;
        if (n.matches("block[0-9]*")) return SLOT;
        return BODY;
    }

    private static void collect(List<Model3D.Bone> bones, Model3D.Bone b, List<Model3D.Group> out) {
        out.addAll(b.groups);
        int idx = bones.indexOf(b);
        for (Model3D.Bone c : bones) if (c.parent == idx) collect(bones, c, out);
    }

    /** The model's bones with the settings' turn and size applied (copies; the model itself is not changed). */
    private static List<Model3D.Bone> prepare(Model3D m, VehicleType cfg) {
        float sc = cfg == null ? 1 : cfg.scale, turn = cfg == null ? 0 : cfg.turn;
        float[] sz = cfg == null ? new float[]{1, 1, 1} : new float[]{cfg.sizeX, cfg.sizeY, cfg.sizeZ};
        if (sc == 1 && turn == 0 && sz[0] == 1 && sz[1] == 1 && sz[2] == 1) return m.bones;
        double a = Math.toRadians(turn), ca = Math.cos(a), sa = Math.sin(a);
        List<Model3D.Bone> out = new ArrayList<>();
        for (Model3D.Bone b : m.bones) {
            Model3D.Bone c = new Model3D.Bone(b.name, b.parent);
            c.pivotSet = b.pivotSet;
            float[] pv = transform(b.px, b.py, b.pz, ca, sa, sc, sz);
            c.px = pv[0]; c.py = pv[1]; c.pz = pv[2];
            for (Model3D.Group g : b.groups) {
                Model3D.Group d = c.group(g.shared != null ? g.shared : g);
                for (int t = 0; t < g.tris; t++) d.copyTri(g, t);
            }
            for (Model3D.Group d : c.groups)
                for (int k = 0; k < d.tris * 3; k++) {
                    float[] q = transform(d.v[k * 5], d.v[k * 5 + 1], d.v[k * 5 + 2], ca, sa, sc, sz);
                    d.v[k * 5] = q[0]; d.v[k * 5 + 1] = q[1]; d.v[k * 5 + 2] = q[2];
                }
            out.add(c);
        }
        return out;
    }

    private static float[] transform(float x, float y, float z, double ca, double sa, float sc, float[] sz) {
        return new float[]{(float) ((x * ca + z * sa) * sc * sz[0]), y * sc * sz[1], (float) ((-x * sa + z * ca) * sc * sz[2])};
    }

    /**
     * Pivots for parts that have none (OBJ objects): wheels and the steering wheel turn around their middle, side doors
     * around their front edge on the outside, flat lids around the edge towards the middle of the car (hood: its back
     * edge, trunk: its front edge) at the top. Pivots from the settings replace these (and Blockbench ones).
     */
    private static void autoPivots(List<Part> parts, Part body, VehicleType cfg) {
        float cx0 = Float.MAX_VALUE, cx1 = -Float.MAX_VALUE, cz0 = Float.MAX_VALUE, cz1 = -Float.MAX_VALUE;
        for (Part p : parts) { boundsOf(p); cx0 = Math.min(cx0, p.x0); cx1 = Math.max(cx1, p.x1); cz0 = Math.min(cz0, p.z0); cz1 = Math.max(cz1, p.z1); }
        if (body.triangles() > 0) { boundsOf(body); cx0 = Math.min(cx0, body.x0); cx1 = Math.max(cx1, body.x1); cz0 = Math.min(cz0, body.z0); cz1 = Math.max(cz1, body.z1); }
        float carCx = (cx0 + cx1) / 2, carCz = (cz0 + cz1) / 2;
        for (Part p : parts) {
            if (!p.autoPivot) continue;
            float mx = (p.x0 + p.x1) / 2, my = (p.y0 + p.y1) / 2, mz = (p.z0 + p.z1) / 2;
            switch (p.role) {
                case WHEEL, STEERING -> { p.px = mx; p.py = my; p.pz = mz; }
                case OPENABLE -> {
                    float dx = p.x1 - p.x0, dy = p.y1 - p.y0, dz = p.z1 - p.z0;
                    if (dy <= dx && dy <= dz) { p.px = mx; p.py = p.y1; p.pz = mz < carCz ? p.z1 : p.z0; }
                    else if (dx <= dz) { p.px = mx < carCx ? p.x0 : p.x1; p.py = my; p.pz = p.z0; }
                    else { p.px = p.x0; p.py = my; p.pz = mz < carCz ? p.z0 : p.z1; }
                }
                default -> {}
            }
        }
    }

    private static void boundsOf(Part p) {
        p.x0 = p.y0 = p.z0 = Float.MAX_VALUE; p.x1 = p.y1 = p.z1 = -Float.MAX_VALUE;
        for (Model3D.Group g : p.groups) for (int t = 0; t < g.tris * 3; t++) p.grow(g.v[t * 5], g.v[t * 5 + 1], g.v[t * 5 + 2]);
        if (p.x0 > p.x1) { p.x0 = p.x1 = p.px; p.y0 = p.y1 = p.py; p.z0 = p.z1 = p.pz; }
    }

    /**
     * The seat's sitting surface: the height with the largest upward facing area (the cushion top, not the backrest or
     * the headrest), and the middle of that area. Model space in, local out ({@code ox..oz} are subtracted).
     */
    private float[] seatSurface(List<Model3D.Group> gs, float fx, float fy, float fz) {
        Map<Integer, double[]> byY = new HashMap<>();      // y bucket -> {area, sum x*area, sum z*area}
        for (Model3D.Group g : gs) {
            float[] v = g.v;
            for (int t = 0; t < g.tris; t++) {
                int o = t * 15;
                float ux = v[o + 5] - v[o], uy = v[o + 6] - v[o + 1], uz = v[o + 7] - v[o + 2];
                float wx = v[o + 10] - v[o], wy = v[o + 11] - v[o + 1], wz = v[o + 12] - v[o + 2];
                float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (l < 1e-9f || ny / l < 0.85f) continue;          // only faces looking up (the winding is outward)
                float y = (v[o + 1] + v[o + 6] + v[o + 11]) / 3;
                int key = Math.round(y * 32);
                double[] a = byY.computeIfAbsent(key, k -> new double[3]);
                double area = l / 2;
                a[0] += area;
                a[1] += area * (v[o] + v[o + 5] + v[o + 10]) / 3;
                a[2] += area * (v[o + 2] + v[o + 7] + v[o + 12]) / 3;
            }
        }
        // the top of the cushion: of the biggest flat areas, the highest one that is not far above the lowest seat part
        int bestKey = Integer.MIN_VALUE;
        double bestArea = 0;
        for (var e : byY.entrySet()) if (e.getValue()[0] > bestArea * 1.05 || e.getValue()[0] > bestArea * 0.95 && e.getKey() > bestKey) { bestArea = e.getValue()[0]; bestKey = e.getKey(); }
        if (bestKey == Integer.MIN_VALUE) return new float[]{fx, fy + 0.25f, fz};
        double[] a = byY.get(bestKey);
        return new float[]{(float) (a[1] / a[0]) - ox, bestKey / 32f - oy, (float) (a[2] / a[0]) - oz};
    }

    /** The direction in which the part's vertices spread least (the normal of a flat ring). */
    private static float[] thinAxis(Part p) {
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Model3D.Group g : p.groups) for (int t = 0; t < g.tris * 3; t++) { sx += g.v[t * 5]; sy += g.v[t * 5 + 1]; sz += g.v[t * 5 + 2]; n++; }
        if (n == 0) return new float[]{0, 0, 1};
        sx /= n; sy /= n; sz /= n;
        double[][] c = new double[3][3];
        for (Model3D.Group g : p.groups)
            for (int t = 0; t < g.tris * 3; t++) {
                double[] d = {g.v[t * 5] - sx, g.v[t * 5 + 1] - sy, g.v[t * 5 + 2] - sz};
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) c[i][j] += d[i] * d[j];
            }
        double[][] v = jacobi(c);
        // jacobi returns eigenvectors as columns, c's diagonal becomes the eigenvalues
        int k = 0;
        for (int i = 1; i < 3; i++) if (c[i][i] < c[k][k]) k = i;
        return new float[]{(float) v[0][k], (float) v[1][k], (float) v[2][k]};
    }

    /** Eigen decomposition of a symmetric 3x3 matrix (in place: a becomes diagonal); returns the eigenvectors (columns). */
    static double[][] jacobi(double[][] a) {
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-18) break;
            for (int p = 0; p < 2; p++)
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-15) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < 3; k++) {          // rotate columns p, q
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {          // rotate rows p, q
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq;
                    }
                }
        }
        return v;
    }

    // ------------------------------------------------------------------ cache
    private static final Map<Model3D, VehicleModel> CACHE = new WeakHashMap<>();

    /** Drops the cached analysis of a model (its settings changed): the next {@link #of} analyses it again. */
    static synchronized void forget(Model3D m) { CACHE.remove(m); }

    /** The vehicle analysis of a model (cached while the model stays loaded), or null for a model without groups. */
    public static synchronized VehicleModel of(Model3D m, VehicleType cfg) {
        if (m == null || m.empty() || m.bones.isEmpty()) return null;
        return CACHE.computeIfAbsent(m, k -> new VehicleModel(k, cfg));
    }

    /** Whether a model looks like a vehicle (at least two wheel groups). Used by tools/vehicle_check. */
    public static boolean isVehicle(Model3D m) {
        if (m == null || m.bones.isEmpty()) return false;
        int w = 0;
        for (Model3D.Bone b : m.bones) if (roleOf(b.name) == WHEEL) w++;
        return w >= 2;
    }
}
