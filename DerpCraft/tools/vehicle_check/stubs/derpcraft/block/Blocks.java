package derpcraft.block;
public final class Blocks {
  public static final Block AIR = new Block(0,"air",false,Block.Shape.AIR), STONE = new Block(1,"stone",true,Block.Shape.CUBE), WATER = new Block(2,"water",false,Block.Shape.LIQUID);
  public static final Block ICE = new Block(3,"ice",true,Block.Shape.CUBE), SAND = new Block(4,"sand",true,Block.Shape.CUBE);
  public static Block byId(int id){ return id==1?STONE:id==2?WATER:id==3?ICE:id==4?SAND:AIR; }
  public static Block byName(String n){ return null; }
}
