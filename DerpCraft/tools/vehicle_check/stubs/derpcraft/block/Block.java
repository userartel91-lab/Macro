package derpcraft.block;
import java.util.*;
public class Block {
  public enum Shape { CUBE, LIQUID, AIR }
  public String name; public boolean solid; public Shape shape; public int id;
  public Block(int id, String n, boolean solid, Shape s){ this.id=id; name=n; this.solid=solid; shape=s; }
  public List<float[]> collisionBoxes(int meta, int conn){ return solid ? List.of(new float[]{0,0,0,1,1,1}) : List.of(); }
  public final List<float[]> collisionShape(int meta, int conn){ return collisionBoxes(meta, conn); }
  public final List<float[]> selectionShape(int meta, int conn){ return collisionBoxes(meta, conn); }
  public int neighborInfo(derpcraft.world.World w,int x,int y,int z,int m){ return 0; }
  public boolean isAir(){ return shape == Shape.AIR; }
}
