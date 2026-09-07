import numpy as np, json, cv2
from PIL import Image, ImageDraw

CUTS=[0,20,36,52,69,85,103,118,134,152,168,183,195,200,218,236,244,248,267,
      281,298,314,326,331,347,362,379,395,413,429,444,461,477,493,510,526,529]
SEGS=[(CUTS[i],CUTS[i+1]-1) for i in range(len(CUTS)-1)]
# segments with no head to replace (hand / portal / legs-only shots)
NOHEAD={ (248,266),(267,280),(281,297),(379,394),(395,412) }
json.dump({'segs':SEGS,'nohead':[list(x) for x in NOHEAD]},open('segs.json','w'))
for i,(a,b) in enumerate(SEGS):
    tag='SKIP' if (a,b) in NOHEAD else ''
    print(f"{i:2d} {a:4d}-{b:4d} len={b-a+1:3d} {tag}")
