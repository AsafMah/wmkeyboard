"""A CTC tap encoder conditioned on the keyboard's geometry: GeoCtc's idea
for typing instead of swiping.

forward(taps, mask, keys, kmask) -> log-probs [B, 2N, K + 1]
  taps  [B, N, 4]   per tap: x, y (px, any unit), log inter-tap gap (s), hold (s)
  mask  [B, N]      true for the taps that exist
  keys  [B, K, 2]   key centres in the taps' unit, in any order
  kmask [B, K]      true for the keys that exist
Two output steps per tap, so a letter the finger skipped can still be
emitted between two taps, and a tap that was an extra press can emit blank
twice. Class k < K is key slot k, class K the CTC blank; nothing in the
network knows a slot's letter, so one set of weights reads any layout.
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F

from model import NEG, Block, GeoCtc, fourier


class TapCtc(nn.Module):
    def __init__(self, d=96, heads=4, ff=256, key_layers=1, layers=4, max_taps=24):
        super().__init__()
        self.register_buffer("freqs", torch.tensor([0.25, 0.5, 1.0, 2.0]) * math.pi)
        fdim = 2 + 2 * 2 * 4
        self.key_in = nn.Sequential(nn.Linear(fdim, d), nn.GELU(), nn.Linear(d, d))
        self.key_blocks = nn.ModuleList(Block(d, heads, ff, False) for _ in range(key_layers))
        self.key_norm = nn.LayerNorm(d)
        self.tap_in = nn.Sequential(nn.Linear(fdim + 2, d), nn.GELU(), nn.Linear(d, d))
        self.pos = nn.Parameter(torch.randn(max_taps, d) * 0.02)
        self.blocks = nn.ModuleList(Block(d, heads, ff, True) for _ in range(layers))
        self.up = nn.Linear(d, 2 * d)
        self.out_norm = nn.LayerNorm(d)
        self.head_q = nn.Linear(d, d)
        self.head_k = nn.Linear(d, d)
        self.offset = nn.Linear(d, 2)
        self.gamma = nn.Parameter(torch.tensor(0.0))
        self.blank = nn.Linear(d, 1)
        nn.init.zeros_(self.offset.weight)
        nn.init.zeros_(self.offset.bias)

    def forward(self, taps, mask, keys, kmask):
        xy, keys_n = GeoCtc.normalise(taps[..., :2], keys, kmask)
        B, N, _ = xy.shape
        e = self.key_in(fourier(keys_n, self.freqs))
        for blk in self.key_blocks:
            e = blk(e, kmask)
        e = self.key_norm(e)
        x = self.tap_in(torch.cat([fourier(xy, self.freqs), taps[..., 2:4]], -1)) + self.pos[:N]
        for blk in self.blocks:
            x = blk(x, mask, e, kmask, xy, keys_n)
        x = self.out_norm(self.up(x).view(B, 2 * N, -1))
        p = xy.repeat_interleave(2, dim=1)
        aim = p + self.offset(x)
        d2 = ((aim[:, :, None, :] - keys_n[:, None, :, :]) ** 2).sum(-1)
        score = torch.einsum("btd,bkd->btk", self.head_q(x), self.head_k(e)) / math.sqrt(x.shape[-1])
        score = score - F.softplus(self.gamma) * d2
        score = torch.where(kmask[:, None, :], score, torch.full_like(score, NEG))
        logits = torch.cat([score, self.blank(x)], -1)
        return F.log_softmax(logits, -1)
