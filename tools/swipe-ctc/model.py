"""A CTC swipe encoder conditioned on the keyboard's geometry.

forward(path, dur, keys, mask) -> log-probs [B, T, K + 1]
  path  [B, 64, 2]  the stroke's time-uniform frames, any unit (px)
  dur   [B]         stroke duration, ms
  keys  [B, K, 2]   key centres in the path's unit, in any order
  mask  [B, K]      true for the keys that exist
Class k < K is key slot k, class K is the CTC blank. Nothing in the network
knows a slot's index or letter: keys are embedded from their position alone
and every head scores a slot by its embedding and its distance from the
frame, so one set of weights reads any layout, and the alphabet is the
caller's.
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F

NEG = -1e4


def fourier(p, freqs):
    a = p.unsqueeze(-1) * freqs  # [..., 2, F]
    return torch.cat([p, torch.sin(a).flatten(-2), torch.cos(a).flatten(-2)], -1)


class Attention(nn.Module):
    def __init__(self, d, heads, distance=False):
        super().__init__()
        self.h = heads
        self.q = nn.Linear(d, d)
        self.kv = nn.Linear(d, 2 * d)
        self.o = nn.Linear(d, d)
        # Per head, how fast attention falls off with distance (in key pitches):
        # a few heads look only at the nearest keys, a few wider.
        self.beta = nn.Parameter(torch.linspace(1.5, -3.0, heads)) if distance else None

    def forward(self, x, y, mask=None, px=None, py=None):
        B, T, D = x.shape
        S = y.shape[1]
        q = self.q(x).view(B, T, self.h, -1).transpose(1, 2)
        k, v = self.kv(y).view(B, S, 2, self.h, -1).permute(2, 0, 3, 1, 4)
        bias = torch.zeros(B, 1, T, S, device=x.device, dtype=x.dtype)
        if mask is not None:
            bias = bias + torch.where(mask, 0.0, NEG)[:, None, None, :]
        if self.beta is not None:
            d2 = ((px[:, :, None, :] - py[:, None, :, :]) ** 2).sum(-1)  # [B, T, S]
            bias = bias - F.softplus(self.beta)[None, :, None, None] * d2[:, None]
        out = F.scaled_dot_product_attention(q, k, v, attn_mask=bias)
        return self.o(out.transpose(1, 2).reshape(B, T, D))


class Block(nn.Module):
    def __init__(self, d, heads, ff, cross):
        super().__init__()
        self.n1 = nn.LayerNorm(d)
        self.self_attn = Attention(d, heads)
        self.cross = Attention(d, heads, distance=True) if cross else None
        self.nc = nn.LayerNorm(d) if cross else None
        self.n2 = nn.LayerNorm(d)
        self.ff = nn.Sequential(nn.Linear(d, ff), nn.GELU(), nn.Linear(ff, d))

    def forward(self, x, mask=None, keys=None, kmask=None, px=None, pk=None):
        h = self.n1(x)
        x = x + self.self_attn(h, h, mask)
        if self.cross is not None:
            x = x + self.cross(self.nc(x), keys, kmask, px, pk)
        return x + self.ff(self.n2(x))


class GeoCtc(nn.Module):
    def __init__(self, d=128, heads=4, ff=256, key_layers=2, low_layers=2, high_layers=3, frames=64, stride=2):
        super().__init__()
        self.stride = stride
        self.register_buffer("freqs", torch.tensor([0.25, 0.5, 1.0, 2.0]) * math.pi)
        fdim = 2 + 2 * 2 * 4
        self.key_in = nn.Sequential(nn.Linear(fdim, d), nn.GELU(), nn.Linear(d, d))
        self.key_blocks = nn.ModuleList(Block(d, heads, ff, False) for _ in range(key_layers))
        self.key_norm = nn.LayerNorm(d)
        self.frame_in = nn.Sequential(nn.Linear(fdim + 4, d), nn.GELU(), nn.Linear(d, d))
        self.pos_low = nn.Parameter(torch.randn(frames, d) * 0.02)
        self.low = nn.ModuleList(Block(d, heads, ff, True) for _ in range(low_layers))
        self.down = nn.Linear(stride * d, d)
        self.pos_high = nn.Parameter(torch.randn(frames // stride, d) * 0.02)
        self.high = nn.ModuleList(Block(d, heads, ff, True) for _ in range(high_layers))
        self.out_norm = nn.LayerNorm(d)
        self.head_q = nn.Linear(d, d)
        self.head_k = nn.Linear(d, d)
        self.offset = nn.Linear(d, 2)
        self.gamma = nn.Parameter(torch.tensor(0.0))
        self.blank = nn.Linear(d, 1)
        nn.init.zeros_(self.offset.weight)
        nn.init.zeros_(self.offset.bias)

    @staticmethod
    def normalise(path, keys, mask):
        """Centre on the keys, scale to their mean nearest-neighbour distance (a key pitch)."""
        m = mask.to(keys.dtype)
        centre = (keys * m[..., None]).sum(1) / m.sum(1, keepdim=True)
        d = torch.cdist(keys, keys)
        bad = ~(mask[:, :, None] & mask[:, None, :]) | torch.eye(keys.shape[1], dtype=torch.bool, device=keys.device)
        nn_d = torch.where(bad, torch.full_like(d, 1e9), d).min(-1).values
        pitch = (torch.where(mask, nn_d, torch.zeros_like(nn_d)).sum(1) / m.sum(1)).clamp_min(1e-6)
        c, p = centre[:, None, :], pitch[:, None, None]
        return (path - c) / p, (keys - c) / p

    def forward(self, path, dur, keys, mask):
        path, keys = self.normalise(path, keys, mask)
        B, N, _ = path.shape
        e = self.key_in(fourier(keys, self.freqs))
        for blk in self.key_blocks:
            e = blk(e, mask)
        e = self.key_norm(e)

        vel = torch.cat([path[:, 1:2] - path[:, :1], (path[:, 2:] - path[:, :-2]) / 2, path[:, -1:] - path[:, -2:-1]], 1)
        speed = vel.norm(dim=-1, keepdim=True)
        ld = torch.log1p(dur / 1000.0)[:, None, None].expand(B, N, 1)
        x = self.frame_in(torch.cat([fourier(path, self.freqs), vel, speed, ld], -1)) + self.pos_low
        for blk in self.low:
            x = blk(x, None, e, mask, path, keys)
        T = N // self.stride
        x = self.down(x.reshape(B, T, -1)) + self.pos_high
        p = path.reshape(B, T, self.stride, 2).mean(2)
        for blk in self.high:
            x = blk(x, None, e, mask, p, keys)
        x = self.out_norm(x)

        aim = p + self.offset(x)
        d2 = ((aim[:, :, None, :] - keys[:, None, :, :]) ** 2).sum(-1)
        score = torch.einsum("btd,bkd->btk", self.head_q(x), self.head_k(e)) / math.sqrt(x.shape[-1])
        score = score - F.softplus(self.gamma) * d2
        score = torch.where(mask[:, None, :], score, torch.full_like(score, NEG))
        logits = torch.cat([score, self.blank(x)], -1)
        return F.log_softmax(logits, -1)
