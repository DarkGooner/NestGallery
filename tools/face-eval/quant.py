"""Static int8 quantisation of a recogniser -> app/src/main/assets/ (QDQ, per-channel int8 weights, int8 activations,
only Conv/Gemm/MatMul quantised - residual Adds, PReLUs and the pre-conv BatchNorms stay float).

Shipped (AdaFace IR-101, 2026-10-08): percentile calibration (99.99) on 128 CGI + 128 real faces.
    python export_adaface.py
    python quant.py ~/face-eval-data/models/adaface_ir101_webface12m.onnx ../../app/src/main/assets/adaface_ir101_int8.onnx \
        --calib digi,lfw --n 128 --percentile 99.99
MinMax calibration is enough for ArcFace ResNet-50 (cos(fp32, int8) 0.987) but not for IR-101, whose blocks start with a
BatchNorm that cannot be folded into a conv: MinMax 0.960 vs percentile 0.975, and percentile keeps TAR within ~1 point
of fp32 (README). Percentile needs a few GB of RAM for the histograms (fine on 16 GB; it OOMed in the 7 GB WSL VM).

The source model must be opset >= 13 (w600k_r50 ships as opset 11):
    python -c "import onnx;from onnx import version_converter as v;onnx.save(v.convert_version(onnx.load('w600k_r50.onnx'),13),'r50_op13.onnx')"
Run embed.py on the calibration sets first (it caches the aligned faces).
"""
import argparse, os, shutil, tempfile, numpy as np, onnx
from onnxruntime.quantization import quantize_static, CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType
from onnxruntime.quantization.shape_inference import quant_pre_process

E = os.path.dirname(os.path.abspath(__file__)) + "/emb"
ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("dst")
ap.add_argument("--calib", default="digi,lfw", help="face sets to draw calibration faces from")
ap.add_argument("--n", type=int, default=64, help="detected faces per calibration set")
ap.add_argument("--percentile", type=float, default=None, help="percentile calibration (e.g. 99.99); default MinMax")
args = ap.parse_args()


class Reader(CalibrationDataReader):
    def __init__(self, name):
        faces = []
        for s in args.calib.split(","):
            z = np.load(f"{E}/{s}_faces.npz"); f = z["faces"][z["ok"]]          # detected faces only
            faces.append(f[np.random.default_rng(7).permutation(len(f))[:args.n]])
        x = (np.concatenate(faces).astype(np.float32) - 127.5) / 127.5
        self.batches = iter([{name: x[i:i + 1].transpose(0, 3, 1, 2).copy()} for i in range(len(x))])

    def get_next(self): return next(self.batches, None)


src, dst = os.path.expanduser(args.src), os.path.abspath(os.path.expanduser(args.dst))
# Intermediate files live in a temp dir: on Windows, deleting them next to the destination (app/src/main/assets, which
# the IDE / Gradle watch) failed with "file in use".
with tempfile.TemporaryDirectory() as tmp:
    pre, out = os.path.join(tmp, "pre.onnx"), os.path.join(tmp, "int8.onnx")
    quant_pre_process(src, pre)
    name = onnx.load(pre).graph.input[0].name
    calib = dict(calibrate_method=CalibrationMethod.MinMax) if args.percentile is None else \
        dict(calibrate_method=CalibrationMethod.Percentile, extra_options={"CalibPercentile": args.percentile})
    quantize_static(pre, out, Reader(name), quant_format=QuantFormat.QDQ, per_channel=True,
                    activation_type=QuantType.QInt8, weight_type=QuantType.QInt8,
                    op_types_to_quantize=["Conv", "Gemm", "MatMul"], **calib)
    shutil.copyfile(out, dst)
print("wrote", dst, os.path.getsize(dst) // 1024, "KB")
