"""The word-level language model the keyboard runs: a small pre-norm
transformer with tied input and output embeddings.

Every shape here is read by `wm-engine/src/lm.rs`, which runs the same
arithmetic in int8: learned positions over `ctx` tokens, `layers` blocks of
causal self-attention and a GELU (tanh form) feed-forward, a final LayerNorm,
and logits = h · E^T + b. Keep the two in step.
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F


class Block(nn.Module):
    def __init__(self, d, heads, ff):
        super().__init__()
        self.h = heads
        self.ln1 = nn.LayerNorm(d)
        self.qkv = nn.Linear(d, 3 * d)
        self.o = nn.Linear(d, d)
        self.ln2 = nn.LayerNorm(d)
        self.w1 = nn.Linear(d, ff)
        self.w2 = nn.Linear(ff, d)

    def forward(self, x):
        B, T, D = x.shape
        h = self.ln1(x)
        q, k, v = self.qkv(h).view(B, T, 3, self.h, D // self.h).permute(2, 0, 3, 1, 4)
        a = F.scaled_dot_product_attention(q, k, v, is_causal=True)
        x = x + self.o(a.transpose(1, 2).reshape(B, T, D))
        return x + self.w2(F.gelu(self.w1(self.ln2(x)), approximate="tanh"))


class WordLm(nn.Module):
    def __init__(self, vocab, d=192, layers=4, heads=4, ff=512, ctx=32):
        super().__init__()
        self.vocab, self.d, self.layers_n, self.heads, self.ff, self.ctx = vocab, d, layers, heads, ff, ctx
        self.emb = nn.Embedding(vocab, d)
        self.pos = nn.Parameter(torch.zeros(ctx, d))
        self.blocks = nn.ModuleList(Block(d, heads, ff) for _ in range(layers))
        self.ln = nn.LayerNorm(d)
        self.bias = nn.Parameter(torch.zeros(vocab))
        nn.init.normal_(self.emb.weight, std=0.02)
        nn.init.normal_(self.pos, std=0.02)

    def forward(self, ids):
        T = ids.shape[1]
        x = self.emb(ids) + self.pos[:T]
        for b in self.blocks:
            x = b(x)
        return F.linear(self.ln(x), self.emb.weight, self.bias)

    def config(self):
        return dict(d=self.d, layers=self.layers_n, heads=self.heads, ff=self.ff, ctx=self.ctx, vocab=self.vocab)
