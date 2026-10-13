"""Builds models/adaface_ir101_fp16.onnx: the same recogniser as adaface_ir101_gpu.onnx (the phone's GPU graph, weights
read from adaface_ir101_int8.onnx), with the weight DequantizeLinear nodes folded into plain weights and everything
in float16. Input and output stay float32, so server.py feeds it exactly like the float graph.

Why: on an RTX 4060 laptop the float32 graph embeds ~370 faces/s; float16 runs on the tensor cores. setup.ps1 runs
this once (from the SHA-256-checked model files) and server.py checks the result against the float32 graph on start.

    python make_fp16.py [models_dir]
"""
import os, sys
import numpy as np
import onnx
from onnx import numpy_helper, helper, TensorProto

HERE = os.path.dirname(os.path.abspath(__file__))
SRC, DST = "adaface_ir101_gpu.onnx", "adaface_ir101_fp16.onnx"


def main(models):
    m = onnx.load(os.path.join(models, SRC))           # also reads the external int8 weight ranges
    g = m.graph
    inits = {i.name: i for i in g.initializer}
    folded = {}
    keep = []
    for n in g.node:
        if n.op_type == "DequantizeLinear" and all(x in inits for x in n.input):
            q = numpy_helper.to_array(inits[n.input[0]]).astype(np.float32)
            scale = numpy_helper.to_array(inits[n.input[1]]).astype(np.float32)
            zp = numpy_helper.to_array(inits[n.input[2]]).astype(np.float32) if len(n.input) > 2 else np.zeros_like(scale)
            axis = next((a.i for a in n.attribute if a.name == "axis"), 1)
            if scale.ndim == 1 and scale.size > 1:      # per-channel along `axis`
                shape = [1] * q.ndim; shape[axis] = -1
                scale = scale.reshape(shape); zp = zp.reshape(shape)
            folded[n.output[0]] = (q - zp) * scale
        else:
            keep.append(n)
    used = {x for n in keep for x in n.input}
    new_inits = []
    for i in g.initializer:
        if i.name not in used: continue                 # the int8 weights / scales the folded nodes consumed
        a = numpy_helper.to_array(i)
        if a.dtype == np.float32: a = a.astype(np.float16)
        new_inits.append(numpy_helper.from_array(a, i.name))
    for name, a in folded.items():
        new_inits.append(numpy_helper.from_array(a.astype(np.float16), name))

    # float32 in -> Cast -> float16 network -> Cast -> float32 out
    inp, out = g.input[0].name, g.output[0].name
    for n in keep:
        for k, x in enumerate(n.input):
            if x == inp: n.input[k] = inp + "_fp16"
        for k, x in enumerate(n.output):
            if x == out: n.output[k] = out + "_fp16"
    nodes = ([helper.make_node("Cast", [inp], [inp + "_fp16"], to=TensorProto.FLOAT16)] + keep +
             [helper.make_node("Cast", [out + "_fp16"], [out], to=TensorProto.FLOAT)])
    graph = helper.make_graph(nodes, g.name, list(g.input), list(g.output), new_inits)
    model = helper.make_model(graph, opset_imports=[o for o in m.opset_import if o.domain in ("", "ai.onnx")],
                              ir_version=m.ir_version)
    onnx.checker.check_model(model)
    tmp = os.path.join(models, DST + ".tmp")
    onnx.save(model, tmp)
    os.replace(tmp, os.path.join(models, DST))
    print(f"wrote {DST}: {len(folded)} weights folded, {os.path.getsize(os.path.join(models, DST)) / 1e6:.0f} MB")


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "models"))
