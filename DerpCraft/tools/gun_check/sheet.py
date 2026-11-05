import sys
from PIL import Image
d=sys.argv[1]; names=sys.argv[2:]
ims=[Image.open(f'{d}/{n}.png') for n in names]
W,H=ims[0].size
s=Image.new('RGB',(W*2,H*((len(ims)+1)//2)))
for i,im in enumerate(ims): s.paste(im,((i%2)*W,(i//2)*H))
s.save(f'{d}/big.png')
