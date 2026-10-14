# Usage: python3 tools/rpa-fixtures/make_rpa.py app/src/test/resources/rpa
# Writes small Ren'Py archives the way Ren'Py's archiver lays them out, for RpaArchiveTest.
import pickle, zlib, sys, os
out = sys.argv[1]
files = {
    "images/bg/room.png": b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 3,
    "images/char/eileen happy.jpg": b"\xff\xd8\xff\xe0JFIF" + b"x" * 500,
    "video/intro.webm": b"\x1a\x45\xdf\xa3" + b"v" * 1000,
    "script.rpyc": b"RENPY RPC2" + b"s" * 50,
    ".hidden/secret.png": b"\x89PNGsecret",
}
def write(name, version, protocol, key, prefix_len):
    body = bytearray()
    header_len = 34 if version == "3.0" else (25 if version == "2.0" else 52)
    index = {}
    offset = header_len
    data = bytearray()
    for path, content in files.items():
        prefix, rest = content[:prefix_len], content[prefix_len:]
        index[path] = [(offset + len(data), len(content), prefix)] if prefix_len else [(offset + len(data), len(content))]
        data += rest
    index_off = header_len + len(data)
    if version != "2.0":
        index = {k: [tuple([v[0][0] ^ key, v[0][1] ^ key] + list(v[0][2:]))] for k, v in index.items()}
    if version == "3.0":
        header = "RPA-3.0 %016x %08x\n" % (index_off, key)
    elif version == "2.0":
        header = "RPA-2.0 %016x\n" % index_off
    else:  # 3.2: offset, an ignored field, then key parts that are XOR'd together
        header = "RPA-3.2 %016x 00000000 %08x %08x\n" % (index_off, key ^ 0x1234, 0x1234)
    header = header.encode().ljust(header_len, b" ")
    header = header[:-1] + b"\n" if not header.endswith(b"\n") else header
    assert len(header) == header_len, (len(header), header_len)
    blob = header + bytes(data) + zlib.compress(pickle.dumps(index, protocol))
    open(os.path.join(out, name), "wb").write(blob)
write("v3_p2.rpa", "3.0", 2, 0xDEADBEEF, 0)       # bytes via _codecs.encode in protocol 2? (no prefix here)
write("v3_p2_prefix.rpa", "3.0", 2, 0xDEADBEEF, 6) # prefix bytes -> _codecs.encode(..., 'latin1') + REDUCE
write("v3_p5_prefix.rpa", "3.0", 5, 0x42424242, 4) # FRAME, MEMOIZE, SHORT_BINBYTES
write("v2_p0.rpa", "2.0", 0, 0, 3)                  # protocol 0: text opcodes
write("v32_p4.rpa", "3.2", 4, 0x0BADF00D, 2)
open(os.path.join(out, "manifest.txt"), "w").write("\n".join("%s\t%d" % (k, len(v)) for k, v in files.items()) + "\n")
