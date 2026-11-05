import derpcraft.block.*; import derpcraft.world.*; import derpcraft.render.*; import java.nio.file.*; import java.util.*;
public class MeshEquiv { public static void main(String[] a) throws Exception {
  Blocks.registerDefaults(); try { derpcraft.item.Items.registerDefaults(); } catch (Throwable t) {}
  World w = new World(new WorldInfo(Files.createTempDirectory("me"))); TerrainGenerator g = new TerrainGenerator(28); int R = 10;
  List<Block> all = new ArrayList<>(Blocks.all()); all.sort(Comparator.comparing(b -> b.name)); Random r = new Random(11);
  for (int cx=-R;cx<=R;cx++) for(int cz=-R;cz<=R;cz++){Chunk c=new Chunk(cx,cz);g.generate(c);
     if (cx > 3 && cz > 3) for (int k=0;k<400;k++) c.setRaw(r.nextInt(16), 30+r.nextInt(60), r.nextInt(16), all.get(r.nextInt(all.size())).id<<4 | r.nextInt(16));
     if (cx < -3 && cz < -3) for (int k=0;k<60;k++) c.setRaw(r.nextInt(16), 5+r.nextInt(50), r.nextInt(16), 0);   // holes in the rock
     c.state=Chunk.STATE_GENERATED;w.chunks.put(c.key(),c);} w.chunksChanged();
  for (Chunk c : w.chunks.values()) w.light.lightChunk(c); w.light.await(600000);
  RenderTables t = new RenderTables(new BlockTextures()); ChunkMesher m = new ChunkMesher();
  long h = 17; int n = 0;
  for (int cx=-R+1;cx<R;cx++) for(int cz=-R+1;cz<R;cz++) for(int sy=0;sy<16;sy++){
    ChunkMesher.Mesh me = m.build(w,t,cx,sy,cz); n += me.vertexCount();
    h = h * 31 + Arrays.hashCode(me.verts); h = h * 31 + Arrays.hashCode(me.groupStart); h = h * 31 + Arrays.hashCode(me.entityBlocks); }
  System.out.println("meshes " + Long.toHexString(h) + " verts " + n);
  for (int rep=0; rep<4; rep++) { long s0=System.nanoTime();
    for (int cx=-R+1;cx<R;cx++) for(int cz=-R+1;cz<R;cz++) for(int sy=0;sy<16;sy++) m.build(w,t,cx,sy,cz);
    System.out.printf("pass %d: %.0f ms%n", rep, (System.nanoTime()-s0)/1e6); }
  System.exit(0);}}
