"""Builds app/src/main/assets/face_knn.onnx: the k-nearest-neighbour kernel the clusterer runs through ONNX Runtime.

    scores  = Q . X^T            (Gemm, transB=1; unit vectors => cosine similarity)
    values, indices = TopK(scores, k, axis=1)

ONNX Runtime's Gemm uses its optimised NEON/AVX kernels, which is ~4x faster than a hand-written Kotlin loop on
Android (ART does not vectorise), and TopK keeps only k columns per row so nothing big crosses the JNI boundary.

usage: pip install onnx && python make_knn_model.py
"""
import os
import onnx
from onnx import TensorProto, helper

OUT = os.path.join(os.path.dirname(__file__), "../../app/src/main/assets/face_knn.onnx")


def build():
    q = helper.make_tensor_value_info("queries", TensorProto.FLOAT, ["m", "d"])
    x = helper.make_tensor_value_info("base", TensorProto.FLOAT, ["n", "d"])
    k = helper.make_tensor_value_info("k", TensorProto.INT64, [1])
    vals = helper.make_tensor_value_info("values", TensorProto.FLOAT, ["m", "kk"])
    idx = helper.make_tensor_value_info("indices", TensorProto.INT64, ["m", "kk"])
    nodes = [
        helper.make_node("Gemm", ["queries", "base"], ["scores"], transB=1),
        helper.make_node("TopK", ["scores", "k"], ["values", "indices"], axis=1, largest=1, sorted=1),
    ]
    graph = helper.make_graph(nodes, "face_knn", [q, x, k], [vals, idx])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="nestgallery")
    model.ir_version = 8          # loadable by every ONNX Runtime >= 1.10
    onnx.checker.check_model(model)
    return model


if __name__ == "__main__":
    onnx.save(build(), OUT)
    print("wrote", os.path.abspath(OUT))
