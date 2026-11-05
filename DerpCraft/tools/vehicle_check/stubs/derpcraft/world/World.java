package derpcraft.world;
import derpcraft.block.*;
import java.util.*;
public final class World {
  public final Map<Long,Integer> blocks = new HashMap<>();
  public int groundY = 63;
  static long k(int x,int y,int z){ return ((long)x & 0xFFFFF) | (((long)z & 0xFFFFF) << 20) | ((long)y << 40); }
  public int getState(int x,int y,int z){ Integer s = blocks.get(k(x,y,z)); if (s != null) return s; return y <= groundY ? (1<<4) : 0; }
  public Block getBlock(int x,int y,int z){ return Blocks.byId(getState(x,y,z)>>4); }
  public boolean isLoaded(int x,int z){ return true; }
  public void set(int x,int y,int z,int id){ blocks.put(k(x,y,z), id<<4); }
}
