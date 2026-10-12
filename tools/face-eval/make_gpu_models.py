"""Builds the face models the GPU (Qualcomm QNN, float only) can run, without shipping a second copy of the weights.

    python make_gpu_models.py        (writes into app/src/main/assets/)

* scrfd_500m_640.onnx: SCRFD-500M with its input pinned to 1x3x640x640 (QNN needs fixed shapes; the CPU copy takes
  any multiple of 32). Same weights, 2.5 MB.
* adaface_ir101_gpu.onnx: the int8 AdaFace graph with the activation QuantizeLinear -> DequantizeLinear pairs removed,
  i.e. a float network whose weights are the int8 model's dequantised weights. It holds no weights itself: every large
  initializer is an *external data* reference into adaface_ir101_int8.onnx (the byte range where that tensor's
  raw_data already sits), so the APK grows by the graph only (~1 MB). The app opens it with
  session.disable_quant_qdq=1, which makes ONNX Runtime constant-fold the weight DequantizeLinear nodes into float
  weights before QNN partitions the graph. Its embeddings are not bit-identical to the CPU model's (no activation
  rounding); check_gpu_models() prints the cosine between the two on random input and on real faces if available.
"""
import os, sys, numpy as np, onnx
from onnx import numpy_helper as nh

A = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/assets")
INT8 = "adaface_ir101_int8.onnx"


def make_scrfd():
    m = onnx.load(f"{A}/scrfd_500m.onnx")
    dims = m.graph.input[0].type.tensor_type.shape.dim
    for d, v in zip(dims, (1, 3, 640, 640)):
        d.ClearField("dim_param"); d.dim_value = v
    del m.graph.value_info[:]
    m = onnx.shape_inference.infer_shapes(m)
    onnx.save(m, f"{A}/scrfd_500m_640.onnx")


def make_adaface():
    raw = open(f"{A}/{INT8}", "rb").read()
    m = onnx.load(f"{A}/{INT8}")
    g = m.graph
    inits = {i.name for i in g.initializer}
    # activation fake-quant: Q(x) -> DQ, x not a constant. DQ outputs get replaced by x.
    nodes = list(g.node)
    q_at = {n.output[0]: i for i, n in enumerate(nodes) if n.op_type == "QuantizeLinear" and n.input[0] not in inits}
    outputs = {o.name for o in g.output}
    alias, drop = {}, set()
    for i, n in enumerate(nodes):
        if n.op_type == "DequantizeLinear" and n.input[0] in q_at:
            src = nodes[q_at[n.input[0]]].input[0]
            if n.output[0] in outputs:            # keep the output's name: the DQ becomes an Identity
                nodes[i] = onnx.helper.make_node("Identity", [src], [n.output[0]])
            else:
                alias[n.output[0]] = src; drop.add(i)
            drop.add(q_at[n.input[0]])
    keep = [n for i, n in enumerate(nodes) if i not in drop]
    for n in keep:
        for i, x in enumerate(n.input):
            while x in alias: x = alias[x]
            n.input[i] = x
    del g.node[:]; g.node.extend(keep)
    used = {x for n in g.node for x in n.input}
    kept = [t for t in g.initializer if t.name in used]
    del g.initializer[:]
    shared = 0
    for t in kept:
        if len(t.raw_data) >= 4096:
            off = raw.find(t.raw_data)
            assert off >= 0 and raw.find(t.raw_data, off + 1) < 0, t.name     # unique byte range
            n = len(t.raw_data)
            t.ClearField("raw_data")
            t.data_location = onnx.TensorProto.EXTERNAL
            for k, v in (("location", INT8), ("offset", str(off)), ("length", str(n))):
                e = t.external_data.add(); e.key = k; e.value = v
            shared += n
        g.initializer.append(t)
    with open(f"{A}/adaface_ir101_gpu.onnx", "wb") as f: f.write(m.SerializeToString())
    print(f"adaface_ir101_gpu.onnx: {len(q_at)} activation Q/DQ pairs removed, {shared >> 20} MB of weights shared")


def check():
    import onnxruntime as ort
    o = ort.SessionOptions(); o.add_session_config_entry("session.disable_quant_qdq", "1")
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    o.optimized_model_filepath = os.path.join(os.environ.get("TMP", "/tmp"), "gpu_basic.onnx")
    gpu = ort.InferenceSession(f"{A}/adaface_ir101_gpu.onnx", o, providers=["CPUExecutionProvider"])
    import collections
    ops = collections.Counter(n.op_type for n in onnx.load(o.optimized_model_filepath, load_external_data=False).graph.node)
    print("ops after basic optimisation (what QNN sees):", dict(ops))
    cpu = ort.InferenceSession(f"{A}/{INT8}", providers=["CPUExecutionProvider"])
    faces = os.environ.get("FACES")       # optional .npz from embed.py: faces [N,112,112,3] uint8
    x = (np.load(faces)["faces"][:64].astype(np.float32) - 127.5) / 127.5 if faces else None
    x = x.transpose(0, 3, 1, 2).copy() if x is not None else np.random.default_rng(0).uniform(-1, 1, (16, 3, 112, 112)).astype(np.float32)
    a = cpu.run(None, {"input": x})[0]; b = gpu.run(None, {"input": x})[0]
    a /= np.linalg.norm(a, axis=1, keepdims=True); b /= np.linalg.norm(b, axis=1, keepdims=True)
    c = (a * b).sum(1)
    print(f"cos(int8 CPU, GPU graph on CPU): mean {c.mean():.4f} min {c.min():.4f} ({'real faces' if faces else 'random input'})")


if __name__ == "__main__":
    make_scrfd(); make_adaface(); check()
