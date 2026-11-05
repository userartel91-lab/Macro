package javax.microedition.lcdui;
public class Graphics { public static final int HCENTER=1,VCENTER=2,LEFT=4,RIGHT=8,TOP=16,BOTTOM=32,BASELINE=64;
 public void setColor(int c){} public void fillRect(int x,int y,int w,int h){} public void drawRect(int x,int y,int w,int h){} public void fillRoundRect(int x,int y,int w,int h,int a,int b){}
 public void drawRoundRect(int x,int y,int w,int h,int a,int b){} public void fillArc(int x,int y,int w,int h,int s,int a){} public void setFont(Font f){} public void drawString(String s,int x,int y,int a){}
 public void drawImage(Image i,int x,int y,int a){}  public int getClipX(){return 0;} public int getClipY(){return 0;} public int getClipWidth(){return 0;} public int getClipHeight(){return 0;} public void drawLine(int a,int b,int c,int d){} public void drawRegion(Image src,int sx,int sy,int w,int h,int t,int x,int y,int a){} }
