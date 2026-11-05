# Stream-Deck-style icon sheet for the DerpCraft Remote MIDlet. Drawn at 4x in a 22-unit grid, downsampled.
import math
from PIL import Image, ImageDraw, ImageFont
S=4; N=22
Y=(255,210,60); O=(255,150,50); R=(240,75,65); B=(95,170,255); C=(80,220,230); G=(115,215,95); W=(242,244,248)
P=(190,135,255); BR=(165,112,70); GR=(165,172,184); PK=(255,125,175); DK=(40,44,52); LB=(170,210,255)
icons={}
def icon(name):
    def deco(f): icons[name]=f; return f
    return deco
class D:
    def __init__(s,d): s.d=d
    def p(s,pts): return [(x*S,y*S) for x,y in pts]
    def line(s,pts,c,w=2): s.d.line(s.p(pts),fill=c,width=int(w*S),joint='curve'); [s.d.ellipse([x*S-w*S/2,y*S-w*S/2,x*S+w*S/2,y*S+w*S/2],fill=c) for x,y in (pts[0],pts[-1])]
    def poly(s,pts,c,o=None,w=0): s.d.polygon(s.p(pts),fill=c,outline=o,width=int(w*S))
    def circ(s,x,y,r,c=None,o=None,w=2): s.d.ellipse([(x-r)*S,(y-r)*S,(x+r)*S,(y+r)*S],fill=c,outline=o,width=int(w*S))
    def rect(s,x0,y0,x1,y1,c=None,o=None,w=2,r=0):
        if r: s.d.rounded_rectangle([x0*S,y0*S,x1*S,y1*S],radius=r*S,fill=c,outline=o,width=int(w*S))
        else: s.d.rectangle([x0*S,y0*S,x1*S,y1*S],fill=c,outline=o,width=int(w*S))
    def arc(s,x,y,r,a0,a1,c,w=2): s.d.arc([(x-r)*S,(y-r)*S,(x+r)*S,(y+r)*S],a0,a1,fill=c,width=int(w*S))
    def text(s,x,y,t,c,size=9,bold=True):
        f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf' if bold else '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', int(size*S))
        s.d.text((x*S,y*S),t,font=f,fill=c,anchor='mm')
def arrow(d,x0,y0,x1,y1,c,w=2.4,h=5):
    a=math.atan2(y1-y0,x1-x0); bx,by=x1-math.cos(a)*h*0.8,y1-math.sin(a)*h*0.8
    d.line([(x0,y0),(bx,by)],c,w)
    d.poly([(x1,y1),(bx+math.cos(a+1.57)*h*0.6,by+math.sin(a+1.57)*h*0.6),(bx-math.cos(a+1.57)*h*0.6,by-math.sin(a+1.57)*h*0.6)],c)
def sun(d,x,y,r,c=Y,rays=True,rl=3):
    d.circ(x,y,r,c)
    if rays:
        for i in range(8):
            a=i*math.pi/4; d.line([(x+math.cos(a)*(r+1.5),y+math.sin(a)*(r+1.5)),(x+math.cos(a)*(r+rl),y+math.sin(a)*(r+rl))],c,1.6)
def moon(d,x,y,r,c=LB,bg=(0,0,0,0)):
    d.circ(x,y,r,c); d.circ(x+r*0.55,y-r*0.35,r*0.85,bg)
def person(d,x,y,c=W,s=1):
    d.circ(x,y-5*s,2.6*s,c); d.d.pieslice([(x-5*s)*S,(y-1*s)*S,(x+5*s)*S,(y+9*s)*S],180,360,fill=c)
def camera(d,x,y,c=W):
    d.rect(x-7,y-4,x+5,y+5,c,r=1.5); d.poly([(x+5,y),(x+9,y-3),(x+9,y+4),(x+5,y+1)],c)
def mag(d,x,y,c=W):
    d.circ(x,y,5,None,c,2.2); d.line([(x+3.8,y+3.8),(x+8,y+8)],c,2.8)
def clock(d,x,y,r,c=W):
    d.circ(x,y,r,None,c,2); d.line([(x,y-r+3),(x,y),(x+r-3.5,y+1.5)],c,1.8)
def cube(d,x,y,s,top,left,right):
    d.poly([(x,y-s),(x+s*0.95,y-s/2),(x,y),(x-s*0.95,y-s/2)],top)
    d.poly([(x-s*0.95,y-s/2),(x,y),(x,y+s),(x-s*0.95,y+s/2)],left)
    d.poly([(x+s*0.95,y-s/2),(x,y),(x,y+s),(x+s*0.95,y+s/2)],right)
def diamond(d,x,y,r,c): d.poly([(x,y-r),(x+r,y),(x,y+r),(x-r,y)],c)
def iris(d,x,y,r,c=W):
    d.circ(x,y,r,None,c,1.8)
    for i in range(6):
        a=i*math.pi/3; d.line([(x+math.cos(a)*r,y+math.sin(a)*r),(x+math.cos(a+2.1)*r*0.45,y+math.sin(a+2.1)*r*0.45)],c,1.4)
def hand(d,x,y,c=W):
    d.rect(x-5,y-1,x+5,y+7,c,r=3)
    for i,h in enumerate([5,7,7,6]): d.rect(x-5+i*2.7,y-h,x-3.4+i*2.7,y+1,c,r=0.8)
    d.poly([(x-5,y+2),(x-8,y-1),(x-8.5,y+1),(x-5,y+5)],c)
def face(d,x,y,c=Y):
    d.circ(x,y,7,c); d.circ(x-2.5,y-1.5,1,DK); d.circ(x+2.5,y-1.5,1,DK); d.arc(x,y+0.5,3.6,20,160,DK,1.4)
def bubble(d,x,y,c=W):
    d.rect(x-8,y-6,x+8,y+4,c,r=3); d.poly([(x-4,y+3),(x-6,y+8),(x+1,y+3)],c)
def circarrow(d,x,y,r,c,a0=40,a1=330,w=2.2,rev=False):
    d.arc(x,y,r,a0,a1,c,w)
    a=math.radians(a1 if not rev else a0); px,py=x+math.cos(a)*r,y+math.sin(a)*r
    t=a+(math.pi/2 if not rev else -math.pi/2)
    d.poly([(px+math.cos(t)*3.2,py+math.sin(t)*3.2),(px+math.cos(t+2.3)*3,py+math.sin(t+2.3)*3),(px+math.cos(t-2.3)*3,py+math.sin(t-2.3)*3)],c)
def keyb(d,x,y,c=W):
    d.rect(x-9,y-5,x+9,y+5,None,c,1.6,r=1.5)
    for i in range(5): d.rect(x-7+i*3,y-3,x-5.6+i*3,y-1.6,c)
    for i in range(5): d.rect(x-6+i*3,y,x-4.6+i*3,y+1.4,c)
    d.rect(x-5,y+2.6,x+5,y+3.6,c)

T=lambda t,c,sz=8: (lambda d: d.text(11,11.5,t,c,sz))
# ---------------------------------------------------------------- world
@icon('sunrise')
def _(d): sun(d,11,13,4.5,O); d.rect(1,13,21,21,(0,0,0,0)); d.line([(2,14),(20,14)],W,1.6); arrow(d,11,20.5,11,15.5,W,1.6,3.5)
@icon('morning')
def _(d): sun(d,11,12,4.5,Y)
@icon('noon')
def _(d): sun(d,11,11,5.5,(255,235,90),rl=3.5)
@icon('sunset')
def _(d): sun(d,11,14,5,O); d.rect(1,14.5,21,21,(0,0,0,0)); d.line([(2,15),(20,15)],R,1.6); d.line([(5,18),(17,18)],O,1.3)
@icon('evening')
def _(d): moon(d,10,11,6.5,(150,180,255)); d.circ(17,5,1,W)
@icon('midnight')
def _(d): moon(d,9,12,7,LB); [d.circ(x,y,r,W) for x,y,r in [(17,4,1.1),(19,10,0.8),(15,16,0.7)]]
@icon('timestop')
def _(d): clock(d,11,11,8,W); d.rect(13,13,21,21,DK); d.rect(14,14,16,20,R); d.rect(18,14,20,20,R)
@icon('timeplay')
def _(d): clock(d,11,11,8,W); d.rect(13,13,21,21,DK); d.poly([(14,13.5),(21,17),(14,20.5)],G)
@icon('clockminus')
def _(d): clock(d,10,11,7.5,W); d.circ(17,17,4.5,R); d.line([(15,17),(19,17)],W,1.6)
@icon('clockplus')
def _(d): clock(d,10,11,7.5,W); d.circ(17,17,4.5,G); d.line([(15,17),(19,17)],W,1.6); d.line([(17,15),(17,19)],W,1.6)
@icon('creative')
def _(d): cube(d,11,11,8,(110,200,80),(150,100,60),(120,80,48))
@icon('survival')
def _(d): d.line([(5,17),(15,7)],GR,2.5); d.poly([(13,5),(18,4),(17,9)],GR); d.line([(4,14),(8,18)],BR,2.2); d.line([(3,19),(6,16)],BR,2)
@icon('spectator')
def _(d): d.d.ellipse([2*S,6*S,20*S,16*S],outline=W,width=int(2*S)); d.circ(11,11,3.2,C); d.circ(11,11,1.4,DK)
@icon('heal')
def _(d): d.circ(7.5,8.5,4.5,R); d.circ(14.5,8.5,4.5,R); d.poly([(3.2,10.5),(18.8,10.5),(11,19)],R)
@icon('fly')
def _(d):
    d.poly([(3,17),(6,8),(12,3),(20,2),(17,7),(19,8),(15,12),(16,13),(11,16)],W)
    for i in range(3): d.line([(6+i*3.5,14-i*1.5),(13+i*2.5,6.5-i*1.5)],LB,1)
    d.line([(3,19),(9,13)],GR,1.6)
@icon('hud')
def _(d): d.rect(2,4,20,18,None,W,1.8,r=2); d.rect(4,14,12,16,G); d.rect(13,14,18,16,R); d.line([(3,19),(19,3)],O,2)
@icon('screenshot')
def _(d): d.rect(2,6,20,18,W,r=2); d.rect(7,4,12,7,W); d.circ(11,12,4,DK); d.circ(11,12,2.5,B); d.circ(17,9,0.9,DK)
@icon('perspective')
def _(d): person(d,8,12,W,1); d.circ(16,6,3,None,C,1.6); d.circ(16,6,1,C); arrow(d,15,10,11,14,C,1.4,3)
@icon('smooth')
def _(d):
    pts=[(2+i*0.5,11+math.sin(i*0.5/3.0)*5) for i in range(37)]; d.line(pts,C,2.2)
@icon('debug')
def _(d): d.rect(2,3,20,19,None,GR,1.6,r=2); d.line([(4,15),(8,10),(11,13),(15,6),(18,9)],G,1.8); d.line([(4,17),(18,17)],GR,1)
@icon('shaders')
def _(d):
    d.poly([(11,1),(13,9),(21,11),(13,13),(11,21),(9,13),(1,11),(9,9)],Y); d.circ(11,11,2.2,W)
@icon('grading')
def _(d): d.circ(8,9,5,(255,80,80,200)); d.circ(14,9,5,(80,200,90,200)); d.circ(11,14,5,(80,140,255,200))
@icon('fullscreen')
def _(d):
    for (x,y,dx,dy) in [(3,3,1,1),(19,3,-1,1),(3,19,1,-1),(19,19,-1,-1)]: d.line([(x,y+dy*5),(x,y),(x+dx*5,y)],W,2)
    d.rect(7,7,15,15,None,B,1.4)
@icon('inventory')
def _(d): d.rect(3,8,19,19,BR,r=1.5); d.rect(3,5,19,10,(195,140,90),r=1.5); d.rect(9.5,9,12.5,13,Y); d.line([(3,10),(19,10)],(110,70,40),1)
@icon('hub3d')
def _(d):
    for pts in [[(11,2),(20,6.5),(11,11),(2,6.5),(11,2)],[(2,6.5),(2,15.5),(11,20),(20,15.5),(20,6.5)],[(11,11),(11,20)]]: d.line(pts,P,1.6)
@icon('esc')
def _(d): d.rect(2,4,20,18,None,W,1.8,r=3); d.text(11,11.5,'ESC',W,6)
@icon('enter')
def _(d): d.line([(17,4),(17,13),(6,13)],W,2.4); d.poly([(3,13),(8,8.5),(8,17.5)],W)
@icon('terminal')
def _(d): d.rect(1,3,21,19,(30,34,40),GR,1.4,r=2); d.line([(5,8),(9,11),(5,14)],G,1.8); d.line([(11,15),(16,15)],G,1.8)
@icon('repeat')
def _(d): circarrow(d,11,11,7,G,30,300); d.text(11,11.5,'/',W,8)
@icon('worlds')
def _(d):
    d.circ(11,11,8.5,(60,130,220)); d.poly([(5,6),(10,5),(11,9),(8,12),(5,11)],G); d.poly([(13,12),(17,10),(18,14),(14,18)],G)
    d.circ(11,11,8.5,None,W,1.2)
@icon('leave')
def _(d): d.rect(3,2,13,20,None,W,1.8); d.circ(10.5,11,0.9,W); arrow(d,10,11,20,11,O,2,4)
@icon('release')
def _(d): hand(d,11,12,W)
@icon('wifi')
def _(d):
    for r in (8.5,5.5): d.arc(11,17,r,225,315,C,2)
    d.circ(11,17,1.8,C); d.text(16.5,5,'IP',W,5)
@icon('power')
def _(d): d.arc(11,12,7.5,300,240,R,2.2); d.line([(11,2.5),(11,11)],R,2.2)
# ---------------------------------------------------------------- move
for nm,(x0,y0,x1,y1) in {'up':(11,19,11,3),'down':(11,3,11,19),'left':(19,11,3,11),'right':(3,11,19,11)}.items():
    icons['go_'+nm]=(lambda a: (lambda d: arrow(d,*a,W,3,7)))((x0,y0,x1,y1))
@icon('jump')
def _(d): d.line([(3,20),(19,20)],GR,1.6); arrow(d,11,18,11,3,G,2.6,6.5)
@icon('sneak')
def _(d): d.line([(3,20),(19,20)],GR,1.6); arrow(d,11,3,11,17,O,2.6,6.5)
@icon('sprint')
def _(d):
    for x in (4,10): d.line([(x,4),(x+6,11),(x,18)],Y,2.6)
    d.line([(16,4),(20,11),(16,18)],O,2)
@icon('attack')
def _(d):
    d.line([(4,18),(14,8)],BR,2.4); d.d.arc([3*S,1*S,21*S,17*S],200,320,fill=(120,220,230),width=int(3*S))
@icon('place')
def _(d): cube(d,9,12,6.5,(110,200,80),(150,100,60),(120,80,48)); d.circ(17,5,4.2,G); d.line([(15,5),(19,5)],W,1.6); d.line([(17,3),(17,7)],W,1.6)
@icon('pick')
def _(d): d.line([(4,18),(12,10)],W,2.2); d.poly([(11,7),(15,11),(19,7),(15,3)],C); d.circ(4,18,1.5,C)
@icon('drop')
def _(d): hand(d,8,7,(225,225,230)); d.rect(12,13,19,20,(110,200,80),r=1); arrow(d,9,12,13,16,O,1.6,3.5)
@icon('wheelup')
def _(d): d.rect(5,2,17,20,None,W,1.8,r=6); d.line([(11,4),(11,9)],W,1.6); arrow(d,11,17,11,11,C,1.8,4)
@icon('wheeldown')
def _(d): d.rect(5,2,17,20,None,W,1.8,r=6); d.line([(11,4),(11,9)],W,1.6); arrow(d,11,11,11,17,C,1.8,4)
def lookicon(dx,dy,fast):
    def f(d):
        d.d.ellipse([3*S,7*S,19*S,15*S],outline=C,width=int(1.6*S)); d.circ(11+dx*2.5,11+dy*1.2,2.2,C)
        n=2 if fast else 1
        for k in range(n):
            o=k*4
            if dx: x=11+dx*(7+o); d.line([(x-dx*2,7),(x+dx*1.5,11),(x-dx*2,15)],W,1.8)
            if dy: y=11+dy*(6.5+o*0.7); d.line([(7,y-dy*2),(11,y+dy*1.5),(15,y-dy*2)],W,1.8)
    return f
for nm,(dx,dy) in {'left':(-1,0),'right':(1,0),'up':(0,-1),'down':(0,1)}.items():
    icons['look_'+nm]=lookicon(dx,dy,False); icons['lookf_'+nm]=lookicon(dx,dy,True)
@icon('crosshair')
def _(d): d.circ(11,11,7,None,W,1.6); d.line([(11,1.5),(11,6)],W,1.8); d.line([(11,16),(11,20.5)],W,1.8); d.line([(1.5,11),(6,11)],W,1.8); d.line([(16,11),(20.5,11)],W,1.8); d.circ(11,11,1.4,R)
@icon('chat')
def _(d): bubble(d,11,10,W); [d.circ(x,9,1.2,DK) for x in (7,11,15)]
@icon('zoom')
def _(d): mag(d,9,9,W)
# ---------------------------------------------------------------- camera
@icon('freecam')
def _(d): camera(d,10,12,W); [arrow(d,a,b,c,e,C,1.3,3) for a,b,c,e in [(10,7,10,1.5),(4,12,0.5,12)]]
@icon('orbit')
def _(d): cube(d,11,11,3.5,(110,200,80),(150,100,60),(120,80,48)); d.d.arc([1*S,5*S,21*S,17*S],200,520-200+160,fill=C,width=int(1.8*S)); arrow(d,4,15,2,12.5,C,1.6,3.5)
@icon('orbitme')
def _(d): person(d,11,11,W,0.75); d.d.arc([1*S,6*S,21*S,18*S],200,500,fill=C,width=int(1.8*S)); arrow(d,4,16,2,13.5,C,1.6,3.5)
@icon('tripod')
def _(d): camera(d,10,6,W); d.line([(10,11),(4,20)],GR,1.6); d.line([(10,11),(16,20)],GR,1.6); d.line([(10,11),(10,20)],GR,1.6)
@icon('playercam')
def _(d): person(d,11,12,W,1.05); d.circ(11,6.8,0.8,DK)
@icon('selfie')
def _(d): d.rect(12,2,20,19,None,W,1.6,r=2); face(d,7,11,Y); d.circ(16,5,1,C)
@icon('selfiefree')
def _(d): face(d,9,12,Y); circarrow(d,9,12,9.5,C,200,330,1.6)
@icon('panel')
def _(d):
    for i,(y,k) in enumerate([(5,14),(11,7),(17,11)]): d.line([(3,y),(19,y)],GR,1.6); d.rect(k-2,y-2.5,k+2,y+2.5,[C,O,G][i],r=1)
@icon('keyadd')
def _(d): diamond(d,9,12,7,Y); d.circ(17,5,4.2,G); d.line([(15,5),(19,5)],W,1.6); d.line([(17,3),(17,7)],W,1.6)
@icon('keydel')
def _(d): diamond(d,9,12,7,Y); d.circ(17,5,4.2,R); d.line([(15,5),(19,5)],W,1.6)
@icon('trash')
def _(d): d.rect(5,6,17,20,None,W,1.8,r=1.5); d.line([(3,5),(19,5)],W,2); d.rect(8.5,2,13.5,4.5,None,W,1.4); [d.line([(x,9),(x,17)],W,1.3) for x in (8.5,11,13.5)]
@icon('pathplay')
def _(d): d.line([(2,17),(6,12),(10,15),(14,8)],Y,1.6); [diamond(d,x,y,1.8,Y) for x,y in [(2,17),(10,15)]]; d.poly([(12,6),(21,11.5),(12,17)],G)
@icon('stopwatch')
def _(d): d.circ(11,13,7.5,None,W,2); d.rect(9,2,13,4,W); d.line([(11,13),(11,8)],O,2); d.line([(17,6),(18.5,4.5)],W,1.6)
@icon('loop')
def _(d): circarrow(d,11,11,7.5,C,200,500-20); d.poly([(9,8),(15,11),(9,14)],G)
@icon('zoomin')
def _(d): mag(d,9,9,W); d.line([(6.5,9),(11.5,9)],G,1.8); d.line([(9,6.5),(9,11.5)],G,1.8)
@icon('zoomout')
def _(d): mag(d,9,9,W); d.line([(6.5,9),(11.5,9)],R,1.8)
@icon('zoomreset')
def _(d): mag(d,9,9,W); d.text(9,9.3,'1',Y,6)
@icon('rollleft')
def _(d): d.line([(3,15),(19,7)],GR,1.4); circarrow(d,11,11,7.5,O,60,250,2,rev=True)
@icon('rollright')
def _(d): d.line([(3,7),(19,15)],GR,1.4); circarrow(d,11,11,7.5,O,290,480)
@icon('level')
def _(d): d.rect(2,8,20,14,None,W,1.6,r=3); d.circ(11,11,2,G); d.line([(8,8),(8,14)],W,1); d.line([(14,8),(14,14)],W,1)
@icon('grid')
def _(d):
    d.rect(2,4,20,18,None,W,1.4)
    for k in (8,14): d.line([(k,4),(k,18)],W,1)
    for k in (8.7,13.3): d.line([(2,k),(20,k)],W,1)
@icon('letterbox')
def _(d): d.rect(2,3,20,19,(110,160,220)); d.rect(2,3,20,7,(10,10,12)); d.rect(2,15,20,19,(10,10,12)); d.rect(2,3,20,19,None,W,1.2)
@icon('dof')
def _(d): iris(d,11,11,8,W)
@icon('focus')
def _(d):
    for (x,y,dx,dy) in [(3,4,1,1),(19,4,-1,1),(3,18,1,-1),(19,18,-1,-1)]: d.line([(x,y+dy*4),(x,y),(x+dx*4,y)],G,1.8)
    d.circ(11,11,2,G)
@icon('autofocus')
def _(d):
    for (x,y,dx,dy) in [(3,4,1,1),(19,4,-1,1),(3,18,1,-1),(19,18,-1,-1)]: d.line([(x,y+dy*4),(x,y),(x+dx*4,y)],C,1.8)
    d.text(11,11.5,'AF',W,6.5)
@icon('aperture')
def _(d): iris(d,11,11,8,Y)
@icon('shake')
def _(d): camera(d,11,8,W); d.line([(2,17),(5,14),(8,19),(11,14),(14,19),(17,14),(20,17)],O,1.6)
@icon('noshake')
def _(d): camera(d,11,8,W); d.line([(2,17),(20,17)],G,1.8)
@icon('lens')
def _(d): d.circ(11,11,8.5,(50,55,65),W,1.6); d.circ(11,11,5.5,(30,60,110)); d.circ(9,9,1.6,(200,230,255))
@icon('lensreset')
def _(d): d.circ(11,11,8.5,(50,55,65),W,1.6); circarrow(d,11,11,4.5,Y,30,300,1.6)
@icon('easing')
def _(d): d.line([(3,18)]+[(3+i*0.5,18-14*(lambda t: t*t*(3-2*t))(i/32)) for i in range(1,33)],C,2); d.circ(3,18,1.6,W); d.circ(19,4,1.6,W)
@icon('info')
def _(d): d.circ(11,11,8.5,B); d.text(11,11.8,'i',W,11)
# ---------------------------------------------------------------- npc
@icon('record')
def _(d): d.circ(11,11,8.5,None,W,1.4); d.circ(11,11,6,R)
@icon('reccancel')
def _(d): d.circ(11,11,8,None,R,2); d.line([(7,7),(15,15)],W,2.2); d.line([(15,7),(7,15)],W,2.2)
@icon('scene')
def _(d): d.rect(2,9,20,20,(40,40,48),W,1.4); d.poly([(2,4),(19,1),(20,5),(3,8)],W); [d.line([(x,3.5),(x+2,7)],DK,1.4) for x in (6,10,14)]; d.poly([(9,11.5),(15,14.5),(9,17.5)],G)
@icon('rewind')
def _(d): d.poly([(11,5),(3,11),(11,17)],W); d.poly([(19,5),(11,11),(19,17)],W); d.line([(2,5),(2,17)],W,1.8)
@icon('hub')
def _(d): person(d,7,12,GR,0.75); person(d,15,12,GR,0.75); person(d,11,13,W,0.95)
@icon('npcnew')
def _(d): person(d,9,12,W,1); d.circ(17,5,4.2,G); d.line([(15,5),(19,5)],W,1.6); d.line([(17,3),(17,7)],W,1.6)
@icon('list')
def _(d):
    for y in (5,11,17): d.circ(4,y,1.5,C); d.line([(8,y),(19,y)],W,1.8)
@icon('groups')
def _(d): d.rect(2,7,20,19,(230,180,70),r=1.5); d.poly([(2,7),(2,4),(9,4),(11,7)],(230,180,70)); d.rect(2,9,20,19,(250,205,90),r=1.5)
@icon('gesture')
def _(d): hand(d,11,12,(255,220,180))
@icon('face')
def _(d): face(d,11,11,Y)
@icon('mobs')
def _(d): d.d.chord([4*S,4*S,18*S,22*S],180,360,fill=P); d.rect(4,12.5,18,15,P); [d.circ(x,10,1.6,W) for x in (8.5,13.5)]; [d.circ(x,10.3,0.8,DK) for x in (8.5,13.5)]; d.circ(11,11,9.5,None,R,1.8); d.line([(4.3,4.3),(17.7,17.7)],R,1.8)
# ---------------------------------------------------------------- lists / chat / view
@icon('refresh')
def _(d): circarrow(d,11,11,7.5,C,330,630-40)
@icon('listup')
def _(d): arrow(d,11,18,11,4,W,2.6,6.5)
@icon('listdown')
def _(d): arrow(d,11,4,11,18,W,2.6,6.5)
@icon('hold')
def _(d): d.rect(5,10,17,20,O,r=2); d.arc(11,10,4.5,180,360,W,2); d.line([(6.5,10),(6.5,8)],W,2); d.line([(15.5,10),(15.5,8)],W,2); d.circ(11,15,1.5,DK)
@icon('tap')
def _(d): d.circ(11,6,4,None,C,1.4); d.rect(9.5,5,12.5,15,W,r=1.5); d.rect(8,11,16,20,W,r=3)
@icon('minus')
def _(d): d.circ(11,11,8.5,R); d.line([(6.5,11),(15.5,11)],W,2.4)
@icon('plus')
def _(d): d.circ(11,11,8.5,G); d.line([(6.5,11),(15.5,11)],W,2.4); d.line([(11,6.5),(11,15.5)],W,2.4)
@icon('say')
def _(d): bubble(d,11,10,B); d.text(11,9.2,'Aa',W,6)
@icon('keyboard')
def _(d): keyb(d,11,11,W)
@icon('help')
def _(d): d.circ(11,11,8.5,P); d.text(11,11.8,'?',W,11)
@icon('frame')
def _(d): d.rect(2,4,20,18,(110,160,220)); d.poly([(2,18),(8,10),(13,15),(16,12),(20,18)],G); d.circ(16,8,2,Y); d.rect(2,4,20,18,None,W,1.4)
@icon('auto')
def _(d): circarrow(d,11,11,8,G,300,600-40); d.text(11,11.5,'A',W,8)
@icon('mouse')
def _(d): d.rect(5,2,17,20,None,W,1.8,r=6); d.line([(11,2.5),(11,10)],W,1.4); d.line([(5.5,10),(16.5,10)],W,1.4)
@icon('mouse_l')
def _(d): d.rect(5,2,17,20,None,W,1.8,r=6); d.d.pieslice([5*S,2*S,17*S,18*S],180,270,fill=C); d.line([(11,2.5),(11,10)],W,1.4); d.line([(5.5,10),(16.5,10)],W,1.4)
@icon('mouse_r')
def _(d): d.rect(5,2,17,20,None,W,1.8,r=6); d.d.pieslice([5*S,2*S,17*S,18*S],270,360,fill=O); d.line([(11,2.5),(11,10)],W,1.4); d.line([(5.5,10),(16.5,10)],W,1.4)

names=list(icons)
COLS=12
for size,fn in [(22,'icons22.png'),(16,'icons16.png')]:
    rows=(len(names)+COLS-1)//COLS
    sheet=Image.new('RGBA',(COLS*size,rows*size),(0,0,0,0))
    for i,n in enumerate(names):
        im=Image.new('RGBA',(N*S,N*S),(0,0,0,0)); icons[n](D(ImageDraw.Draw(im)))
        sheet.paste(im.resize((size,size),Image.LANCZOS),((i%COLS)*size,(i//COLS)*size))
    # 256-colour palette with alpha: a third of the size, old phones have small JAR limits
    sheet.quantize(256, method=Image.Quantize.FASTOCTREE).save('../res/'+fn,optimize=True)
print('Pad.ICONS = "' + ','.join(names) + '"')
# preview
pv=Image.new('RGB',(COLS*60,((len(names)+COLS-1)//COLS)*60),(38,42,50)); dd=ImageDraw.Draw(pv)
for i,n in enumerate(names):
    im=Image.new('RGBA',(N*S,N*S),(0,0,0,0)); icons[n](D(ImageDraw.Draw(im)))
    x,y=(i%COLS)*60,(i//COLS)*60; pv.paste(im.resize((44,44),Image.LANCZOS),(x+8,y+2),im.resize((44,44),Image.LANCZOS)); dd.text((x+2,y+47),n[:10],fill=(200,200,200))
pv.save('icons-preview.png')
