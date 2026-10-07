"""Writes cached embeddings as a fixture for PeopleClustererTest.realEmbeddingsFixture.
format (little endian): int32 n, int32 d, n x int32 identity label, n x d float32 embedding

usage: python export_fixture.py labels.npy embeddings.npy out.bin
       FACE_EVAL_FIXTURE=out.bin ./gradlew :app:testDebugUnitTest --tests '*PeopleClustererTest*'
"""
import sys, numpy as np

labels, emb, out = np.load(sys.argv[1]).astype("<i4"), np.load(sys.argv[2]).astype("<f4"), sys.argv[3]
with open(out, "wb") as f:
    f.write(np.array([len(labels), emb.shape[1]], "<i4").tobytes())
    f.write(labels.tobytes())
    f.write(emb.tobytes())
print("wrote", out, len(labels), "faces")
