"""Reference implementation of the exact pipeline that gets ported to Kotlin:
SCRFD-500M (letterbox 640, stride 8/16/32, 2 anchors) -> NMS -> 5-pt similarity align (112) -> w600k_mbf embedding."""
import numpy as np, onnxruntime as ort, time
from PIL import Image, ImageOps

M="/home/claude/models/"
opts = ort.SessionOptions(); opts.intra_op_num_threads = 4
det = ort.InferenceSession(M+"det_500m.onnx", opts, providers=["CPUExecutionProvider"])
rec = ort.InferenceSession(M+"w600k_mbf.onnx", opts, providers=["CPUExecutionProvider"])

ARC = np.array([[38.2946,51.6963],[73.5318,51.5014],[56.0252,71.7366],[41.5493,92.3655],[70.7299,92.2041]],np.float32)
STRIDES=[8,16,32]; NA=2

def load(path, max_side=640):
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    s = max_side/max(im.size)
    if s<1: im = im.resize((round(im.width*s),round(im.height*s)), Image.BILINEAR)
    return np.asarray(im)  # HxWx3 RGB uint8

def nms(b, s, thr=0.4):
    idx = s.argsort()[::-1]; keep=[]
    while idx.size:
        i=idx[0]; keep.append(i)
        xx1=np.maximum(b[i,0],b[idx[1:],0]); yy1=np.maximum(b[i,1],b[idx[1:],1])
        xx2=np.minimum(b[i,2],b[idx[1:],2]); yy2=np.minimum(b[i,3],b[idx[1:],3])
        inter=np.maximum(0,xx2-xx1)*np.maximum(0,yy2-yy1)
        a=(b[i,2]-b[i,0])*(b[i,3]-b[i,1]); a2=(b[idx[1:],2]-b[idx[1:],0])*(b[idx[1:],3]-b[idx[1:],1])
        idx=idx[1:][inter/(a+a2-inter)<=thr]
    return keep

def detect(img, size=640, thr=0.5):
    h,w = img.shape[:2]; s = size/max(h,w)
    nw,nh = int(round(w*s)), int(round(h*s))
    canvas = np.zeros((size,size,3),np.float32)  # letterbox top-left, zero-pad AFTER normalization => value 0
    rs = np.asarray(Image.fromarray(img).resize((nw,nh), Image.BILINEAR),np.float32)
    canvas[:nh,:nw] = (rs-127.5)/128.0
    x = canvas.transpose(2,0,1)[None]
    out = det.run(None,{"input.1":x})
    B=[];S=[];L=[]
    for k,st in enumerate(STRIDES):
        sc=out[k][:,0]; bb=out[k+3]*st; kp=out[k+6]*st
        g=size//st
        ys,xs=np.mgrid[0:g,0:g]; ctr=np.stack([xs,ys],-1).reshape(-1,2).astype(np.float32)*st
        ctr=np.repeat(ctr,NA,axis=0)
        m=sc>=thr
        if not m.any(): continue
        c=ctr[m]; b=bb[m]; p=kp[m]
        B.append(np.stack([c[:,0]-b[:,0],c[:,1]-b[:,1],c[:,0]+b[:,2],c[:,1]+b[:,3]],1))
        L.append((p.reshape(-1,5,2)+c[:,None,:])); S.append(sc[m])
    if not B: return []
    B=np.concatenate(B)/s; S=np.concatenate(S); L=np.concatenate(L)/s
    keep=nms(B,S)
    return [(B[i],S[i],L[i]) for i in keep]

def umeyama(src,dst):
    mu_s,mu_d=src.mean(0),dst.mean(0); sc=src-mu_s; dc=dst-mu_d
    var=(sc**2).sum()/len(src); cov=dc.T@sc/len(src)
    U,D,Vt=np.linalg.svd(cov); S=np.eye(2)
    if np.linalg.det(U)*np.linalg.det(Vt)<0: S[1,1]=-1
    R=U@S@Vt; scale=(D*np.diag(S)).sum()/var
    t=mu_d-scale*R@mu_s
    M=np.zeros((2,3),np.float32); M[:,:2]=scale*R; M[:,2]=t
    return M

def align(img, lm, size=112):
    M=umeyama(lm.astype(np.float64),ARC.astype(np.float64))
    Mi=np.linalg.inv(np.vstack([M,[0,0,1]]))[:2]
    ys,xs=np.mgrid[0:size,0:size]
    sx=Mi[0,0]*xs+Mi[0,1]*ys+Mi[0,2]; sy=Mi[1,0]*xs+Mi[1,1]*ys+Mi[1,2]
    h,w=img.shape[:2]
    x0=np.floor(sx).astype(int); y0=np.floor(sy).astype(int); fx=sx-x0; fy=sy-y0
    def g(yy,xx):
        ok=(xx>=0)&(xx<w)&(yy>=0)&(yy<h)
        v=np.zeros(yy.shape+(3,),np.float32); v[ok]=img[yy[ok],xx[ok]]; return v
    return (g(y0,x0)*((1-fx)*(1-fy))[...,None]+g(y0,x0+1)*(fx*(1-fy))[...,None]
           +g(y0+1,x0)*((1-fx)*fy)[...,None]+g(y0+1,x0+1)*(fx*fy)[...,None])

def embed(face):
    x=((face-127.5)/127.5).transpose(2,0,1)[None].astype(np.float32)
    e=rec.run(None,{"input.1":x})[0][0]; return e/np.linalg.norm(e)

def faces(path):
    img=load(path); res=[]
    for b,s,l in detect(img):
        res.append(dict(box=b,score=float(s),emb=embed(align(img,l)),lm=l))
    return img,res
