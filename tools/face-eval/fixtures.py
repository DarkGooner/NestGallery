import numpy as np, csv, json
from pipeline import *
import pipeline as P
D="/home/claude/dfset/"; O="/home/claude/jvm/"
# ---- identities from labeled Yes pairs (connected components) ----
names=open(D+"names.txt").read().split()
parent={n:n for n in names}
def find(x):
    while parent[x]!=x: parent[x]=parent[parent[x]]; x=parent[x]
    return x
for r in list(csv.reader(open(D+"master.csv")))[1:]:
    if r[2]=="Yes": parent[find(r[0])]=find(r[1])
ids={n:find(n) for n in names}
print("identities:", len(set(ids.values())), "images:", len(names), "multi-image identities:", sum(1 for k in set(ids.values()) if list(ids.values()).count(k)>1))
with open(O+"emb.txt","w") as f:
    for n in names:
        img=load(D+n,640); ds=detect(img)
        b,s,l=max(ds,key=lambda d:(d[0][2]-d[0][0])*(d[0][3]-d[0][1]))
        e=embed(align(img,l))
        f.write(f"{ids[n]}|{n}|"+" ".join(f"{x:.6f}" for x in e)+"\n")
# ---- raw SCRFD outputs + expected decode for one image ----
img=load("/home/claude/testimg/two_people.jpg",640)
size=640; h,w=img.shape[:2]; s=size/max(h,w); nw,nh=int(round(w*s)),int(round(h*s))
canvas=np.zeros((size,size,3),np.float32)
rs=np.asarray(Image.fromarray(img).resize((nw,nh),Image.BILINEAR),np.float32); canvas[:nh,:nw]=(rs-127.5)/128.0
out=P.det.run(None,{"input.1":canvas.transpose(2,0,1)[None]})
with open(O+"scrfd_raw.txt","w") as f:
    for k in range(3): f.write(" ".join(f"{x:.6f}" for x in out[k][:,0])+"\n")
    for k in range(3): f.write(" ".join(f"{x:.6f}" for x in out[k+3].reshape(-1))+"\n")
    for k in range(3): f.write(" ".join(f"{x:.6f}" for x in out[k+6].reshape(-1))+"\n")
# expected detections in detector space (not divided by letterbox scale)
exp=[]
for k_st,st in enumerate(STRIDES): pass
dets=P.detect.__wrapped__(img) if hasattr(P.detect,"__wrapped__") else None
res=P.detect(img)   # in original-image coords (divided by s)
with open(O+"scrfd_expected.txt","w") as f:
    f.write(f"{s}\n")
    for b,sc,l in res: f.write(" ".join(f"{x:.4f}" for x in list(b)+[sc]+list(l.reshape(-1)))+"\n")
print("expected detections:",len(res))
# ---- alignment transform fixtures ----
with open(O+"sim_expected.txt","w") as f:
    for p in ["obama.jpg","biden.jpg","lin-manuel-miranda.png","two_people.jpg"]:
        im=load("/home/claude/testimg/"+p,640)
        for b,sc,l in P.detect(im):
            M=umeyama(l.astype(np.float64),ARC.astype(np.float64))
            # a=M00, b=M10, tx=M02, ty=M12
            f.write(" ".join(f"{x:.5f}" for x in list(l.reshape(-1))+[M[0,0],M[1,0],M[0,2],M[1,2]])+"\n")
print("fixtures written")
