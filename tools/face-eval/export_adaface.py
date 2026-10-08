"""Exports AdaFace IR-101 (WebFace12M, CVLFace release) to ONNX for embed.py ("ada101").
Download https://huggingface.co/minchul/cvlface_adaface_ir101_webface12m (models/, pretrained_model/) into
$FACE_EVAL_DATA/adaface_src first; needs torch (CPU is fine) + omegaconf.  usage: python export_adaface.py"""
import os, sys, types, torch
from omegaconf import OmegaConf

DATA = os.environ.get("FACE_EVAL_DATA", os.path.expanduser("~/face-eval-data"))
SRC = os.path.join(DATA, "adaface_src")
sys.path.insert(0, SRC)
# model.py imports fvcore only for FLOP counting; stub it instead of installing it
fv = types.ModuleType("fvcore"); fv.nn = types.ModuleType("fvcore.nn"); fv.nn.flop_count = lambda *a, **k: ({}, {})
sys.modules.update({"fvcore": fv, "fvcore.nn": fv.nn})
from models import get_model                                       # noqa: E402  (CVLFace code)

cfg = OmegaConf.create({"input_size": [3, 112, 112], "color_space": "RGB", "name": "ir101", "output_dim": 512,
                        "start_from": "", "freeze": False, "yaml_path": "models/iresnet/configs/v1_ir101.yaml"})
model = get_model(cfg)
sd = torch.load(os.path.join(SRC, "pretrained_model", "model.pt"), map_location="cpu", weights_only=True)
sd = sd.get("state_dict", sd)
own = model.state_dict()
sd = {k if k in own else "net." + k: v for k, v in sd.items()}
missing = [k for k in own if k not in sd and not k.endswith("num_batches_tracked")]
assert not missing, f"weights missing for {len(missing)} keys, e.g. {missing[:5]}"
model.load_state_dict(sd, strict=False); model.eval()

class Wrap(torch.nn.Module):          # some CVLFace backbones return (embedding, norm); keep the embedding only
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x):
        y = self.m(x)
        return y[0] if isinstance(y, (tuple, list)) else y

out = os.path.join(DATA, "models", "adaface_ir101_webface12m.onnx")
torch.onnx.export(Wrap(model), torch.randn(2, 3, 112, 112), out, input_names=["input"], output_names=["embedding"],
                  dynamic_axes={"input": {0: "n"}, "embedding": {0: "n"}}, opset_version=17, dynamo=False)
print("wrote", out)
