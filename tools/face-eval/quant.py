"""Static int8 quantisation of the ArcFace ResNet-50 recogniser -> app/src/main/assets/arcface_r50_int8.onnx.

Recipe (measured with tools/face-eval, see README): QDQ, per-channel int8 weights, int8 activations, min/max
calibration on 128 detected faces (64 CGI + 64 real), and **only Conv/Gemm quantised** - the residual Adds and PReLUs
stay float. Quantising everything drops agreement with fp32 to cos 0.980; conv-only keeps 0.987 at the same speed.
Percentile/entropy calibration needs far more RAM in onnxruntime 1.16 and was not usable here.

The source model must be opset >= 13 (w600k_r50 ships as opset 11):
    python -c "import onnx;from onnx import version_converter as v;onnx.save(v.convert_version(onnx.load('w600k_r50.onnx'),13),'r50_op13.onnx')"
    python quant.py r50_op13.onnx ../../app/src/main/assets/arcface_r50_int8.onnx      (run embed.py first)
"""
import os, sys, numpy as np, onnx
from onnxruntime.quantization import quantize_static, CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType
from onnxruntime.quantization.shape_inference import quant_pre_process

E = os.path.dirname(os.path.abspath(__file__)) + "/emb"
src, dst = sys.argv[1], sys.argv[2]


class Reader(CalibrationDataReader):
    def __init__(self, name):
        rng = np.random.default_rng(1)
        faces = []
        for s in ("digi", "lfw"):
            z = np.load(f"{E}/{s}_faces.npz"); f = z["faces"][z["ok"]]          # detected faces only
            faces.append(f[rng.permutation(len(f))[:64]])
        x = (np.concatenate(faces).astype(np.float32) - 127.5) / 127.5
        self.batches = iter([{name: x[i:i + 1].transpose(0, 3, 1, 2).copy()} for i in range(len(x))])

    def get_next(self): return next(self.batches, None)


pre = dst + ".pre.onnx"
quant_pre_process(src, pre)
name = onnx.load(pre).graph.input[0].name
quantize_static(pre, dst, Reader(name), quant_format=QuantFormat.QDQ, per_channel=True,
                activation_type=QuantType.QInt8, weight_type=QuantType.QInt8,
                calibrate_method=CalibrationMethod.MinMax, op_types_to_quantize=["Conv", "Gemm"])
os.remove(pre)
print("wrote", dst, os.path.getsize(dst) // 1024, "KB")
