import numpy as np, csv, sys
from PIL import Image
from pipeline import *
from ai_edge_litert.interpreter import Interpreter
D="/home/claude/dfset/"
it = Interpreter(model_path="/home/claude/p/NestGallery-face/app/src/main/assets/facenet_512.tflite", num_threads=1); it.allocate_tensors()
inp=it.get_input_details()[0]; out=it.get_output_details()[0]
def old_embed(img, box):
    x1,y1,x2,y2=box; side=max(x2-x1,y2-y1); tot=side*1.4; cx,cy=(x1+x2)/2,(y1+y2)/2
    l,t=int(cx-tot/2),int(cy-tot/2); T=max(int(tot),2)
    pil=Image.fromarray(img); canvas=Image.new("RGB",(T,T),(128,128,128)); canvas.paste(pil.crop((max(l,0),max(t,0),min(l+T,img.shape[1]),min(t+T,img.shape[0]))),(max(0,-l),max(0,-t)))
    a=np.asarray(canvas.resize((160,160),Image.BILINEAR),np.float32); m=a.mean(); sd=max(a.std(),1/np.sqrt(a.size)); a=(a-m)/sd
    it.set_tensor(inp['index'],a[None].astype(np.float32)); it.invoke()
    e=it.get_tensor(out['index'])[0].astype(np.float32); return e/np.linalg.norm(e)
names=open(D+"names.txt").read().split()
new={};old={};miss=[]
for n in names:
    img=load(D+n,640); ds=detect(img)
    if not ds: miss.append(n); continue
    b,s,l=max(ds,key=lambda d:(d[0][2]-d[0][0])*(d[0][3]-d[0][1]))
    new[n]=embed(align(img,l)); old[n]=old_embed(img,b)
print("no face detected:",miss, "| faces ok:",len(new))
pairs=[(r[0],r[1],r[2]=="Yes") for r in list(csv.reader(open(D+"master.csv")))[1:] if r[0] in new and r[1] in new]
print("pairs:",len(pairs),"same:",sum(p[2] for p in pairs))
def auc(pos,neg): 
    pos=np.array(pos);neg=np.array(neg); return float(((pos[:,None]>neg[None,:]).sum()+0.5*(pos[:,None]==neg[None,:]).sum())/(len(pos)*len(neg)))
res={}
for label,E in (("old FaceNet512+crop",old),("new SCRFD+ArcFace-MBF",new)):
    s=np.array([float(E[a]@E[b]) for a,b,_ in pairs]); y=np.array([p[2] for p in pairs])
    ths=np.linspace(-0.2,0.9,221); accs=[((s>=t)==y).mean() for t in ths]; bi=int(np.argmax(accs))
    # FAR/FRR at fixed thresholds
    print(f"\n{label}: AUC={auc(s[y],s[~y]):.4f}  best-acc={accs[bi]:.3f}@thr={ths[bi]:.2f}")
    print(f"  same-person  sims: min {s[y].min():.2f} p10 {np.percentile(s[y],10):.2f} median {np.median(s[y]):.2f}")
    print(f"  diff-person  sims: max {s[~y].max():.2f} p99 {np.percentile(s[~y],99):.2f} median {np.median(s[~y]):.2f}")
    res[label]=(s,y)
    for t in (0.30,0.35,0.40,0.45,0.50,0.55,0.62):
        print(f"  thr {t:.2f}: recall(same) {(s[y]>=t).mean():.2f}  false-accept(diff) {(s[~y]>=t).mean():.3f}")
np.save("emb_new.npy",new,allow_pickle=True)
