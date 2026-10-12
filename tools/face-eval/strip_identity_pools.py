"""Removes the 1x1 / stride-1 MaxPools from an IR-ResNet (AdaFace) graph: they are the identity shortcut of every
same-size IR block (45 of IR-101's 46) and only copy the tensor, so dropping them gives bit-identical embeddings.
x86 ORT 1.22, one thread: ~5% less time per face (on x86 each one also costs two NCHWc layout reorders).

    python strip_identity_pools.py in.onnx out.onnx      (quant.py runs it on its output)
"""
import sys, onnx
from onnx import helper


def strip(model):
    g = model.graph
    alias, keep = {}, []
    for n in g.node:
        a = {x.name: helper.get_attribute_value(x) for x in n.attribute}
        if n.op_type == "MaxPool" and len(n.output) == 1 and a.get("kernel_shape") == [1, 1] \
                and a.get("strides", [1, 1]) == [1, 1] and not any(a.get("pads", [0])) and a.get("ceil_mode", 0) == 0:
            alias[n.output[0]] = n.input[0]
        else:
            keep.append(n)

    def resolve(x):
        while x in alias: x = alias[x]
        return x

    for n in keep:
        for i, x in enumerate(n.input): n.input[i] = resolve(x)
    assert not any(o.name in alias for o in g.output), "an identity MaxPool feeds a graph output"
    del g.node[:]; g.node.extend(keep)
    return len(alias)


if __name__ == "__main__":
    m = onnx.load(sys.argv[1])
    print("removed", strip(m), "identity MaxPools")
    onnx.checker.check_model(m)
    onnx.save(m, sys.argv[2])
