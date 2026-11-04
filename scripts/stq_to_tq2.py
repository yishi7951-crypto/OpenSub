"""Rewrites the Hy-MT2 1.25-bit model's STQ1_0 weights as TQ2_0, losslessly, for the tablet.

Both formats hold the same thing: weights of -1, 0 or +1 with one scale for every 256. STQ1_0 packs four weights into five
bits through a codebook, which costs the tablet's cores a table lookup for every four weights; TQ2_0 spends two bits on each
and needs none. The scale is copied bit for bit and every weight keeps its value, so the model computes the same numbers.

usage: python scripts/stq_to_tq2.py <in.gguf> <out.gguf>
"""
import sys, types
from pathlib import Path

import numpy as np

sys.modules.setdefault("yaml", types.ModuleType("yaml"))  # gguf-py imports it for model cards, which this does not need
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "llama-stq" / "gguf-py"))
from gguf import GGUFReader, GGUFWriter, GGMLQuantizationType, GGUFValueType  # noqa: E402

CODEBOOK = np.array([0xA9, 0x89, 0x29, 0x09, 0xA6, 0x86, 0x26, 0x06, 0x9A, 0x92, 0x1A, 0x12, 0x6A, 0x62, 0x4A, 0x42,
                     0x01, 0x21, 0x81, 0xA1, 0x04, 0x24, 0x84, 0xA4, 0x10, 0x18, 0x90, 0x98, 0x40, 0x48, 0x60, 0x68], np.uint8)


def stq_to_tq2(blocks: np.ndarray) -> np.ndarray:
    """[n, 42] STQ1_0 blocks (qs[32], sign[8], d) to [n, 66] TQ2_0 blocks (qs[64], d)."""
    n = blocks.shape[0]
    qs, sign, d = blocks[:, :32], blocks[:, 32:40], blocks[:, 40:42]
    g = np.arange(64)
    code = (qs[:, g // 2] >> (4 * (g & 1))) & 0x0F
    flip = (sign[:, g // 8] >> (g % 8)) & 1
    qpack = CODEBOOK[(flip << 4) | code]                       # [n, 64]: four weights, each 0..2 for -1..+1
    weights = np.zeros((n, 256), np.uint8)
    for p in range(4):
        weights[:, (g // 16) * 64 + (g % 16) + p * 16] = (qpack >> (2 * p)) & 3
    # TQ2_0: byte m of each 32-byte half holds weights m, m+32, m+64 and m+96 of that half's 128.
    halves = weights.reshape(n, 2, 4, 32)
    packed = sum(halves[:, :, k, :].astype(np.uint8) << (2 * k) for k in range(4)).reshape(n, 64).astype(np.uint8)
    return np.concatenate([packed, d], axis=1)


def main(source: str, target: str) -> None:
    reader = GGUFReader(source)
    arch = bytes(reader.fields["general.architecture"].parts[-1]).decode()
    writer = GGUFWriter(target, arch)
    for name, field in reader.fields.items():
        if name.startswith("GGUF.") or name == "general.architecture":
            continue
        kind = field.types[0]
        if kind == GGUFValueType.ARRAY:
            sub = field.types[1]
            values = [bytes(field.parts[i]).decode() if sub == GGUFValueType.STRING else field.parts[i][0] for i in field.data]
            writer.add_key_value(name, values, kind, sub_type=sub)
        else:
            value = bytes(field.parts[-1]).decode() if kind == GGUFValueType.STRING else field.parts[-1][0]
            writer.add_key_value(name, value, kind)
    for tensor in reader.tensors:
        # A quantized tensor's data comes as bytes, one row of them per row of weights.
        data = np.asarray(tensor.data)
        if tensor.tensor_type == GGMLQuantizationType.STQ1_0:
            rows = data.shape[0]
            data = stq_to_tq2(data.reshape(-1, 42)).reshape(rows, -1)
            writer.add_tensor(tensor.name, data, raw_dtype=GGMLQuantizationType.TQ2_0)
        else:
            writer.add_tensor(tensor.name, data, raw_dtype=tensor.tensor_type)
    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
