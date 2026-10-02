// AES-128-CBC decryption without padding, synchronous (BCU's game code reads files synchronously,
// so WebCrypto's promise-based API can't be used on that path). Used by worker.js.
"use strict";

const AES = (() => {
  const SBOX = new Uint8Array(256), INV = new Uint8Array(256);
  // build the S-box from GF(2^8) inverses
  (() => {
    let p = 1, q = 1;
    do {
      p = p ^ ((p << 1) & 0xff) ^ (p & 0x80 ? 0x1b : 0);
      q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
      if (q & 0x80) q ^= 0x09;
      const x = q ^ ((q << 1 | q >> 7) & 0xff) ^ ((q << 2 | q >> 6) & 0xff) ^ ((q << 3 | q >> 5) & 0xff) ^ ((q << 4 | q >> 4) & 0xff) ^ 0x63;
      SBOX[p] = x; INV[x] = p;
    } while (p !== 1);
    SBOX[0] = 0x63; INV[0x63] = 0;
  })();
  const mul = (a, b) => {
    let r = 0;
    while (b) { if (b & 1) r ^= a; a = (a << 1) ^ (a & 0x80 ? 0x11b : 0); b >>= 1; }
    return r;
  };
  // decryption tables: InvMixColumns combined with InvSubBytes
  const T0 = new Uint32Array(256), T1 = new Uint32Array(256), T2 = new Uint32Array(256), T3 = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    const s = INV[i];
    const w = (mul(s, 14) << 24) | (mul(s, 9) << 16) | (mul(s, 13) << 8) | mul(s, 11);
    T0[i] = w >>> 0; T1[i] = (w >>> 8 | w << 24) >>> 0; T2[i] = (w >>> 16 | w << 16) >>> 0; T3[i] = (w >>> 24 | w << 8) >>> 0;
  }
  // InvMixColumns on a round-key word (for the equivalent inverse cipher)
  const imc = (w) => T0[SBOX[w >>> 24]] ^ T1[SBOX[(w >>> 16) & 0xff]] ^ T2[SBOX[(w >>> 8) & 0xff]] ^ T3[SBOX[w & 0xff]];

  function expandDecKey(key) {
    const w = new Uint32Array(44);
    for (let i = 0; i < 4; i++) w[i] = (key[4 * i] << 24 | key[4 * i + 1] << 16 | key[4 * i + 2] << 8 | key[4 * i + 3]) >>> 0;
    let rcon = 1;
    for (let i = 4; i < 44; i++) {
      let t = w[i - 1];
      if (i % 4 === 0) {
        t = (SBOX[(t >>> 16) & 0xff] << 24 | SBOX[(t >>> 8) & 0xff] << 16 | SBOX[t & 0xff] << 8 | SBOX[t >>> 24]) ^ (rcon << 24);
        rcon = mul(rcon, 2);
      }
      w[i] = (w[i - 4] ^ t) >>> 0;
    }
    // reverse the rounds and apply InvMixColumns to the middle ones
    const d = new Uint32Array(44);
    for (let r = 0; r <= 10; r++) {
      for (let c = 0; c < 4; c++) {
        const v = w[(10 - r) * 4 + c];
        d[r * 4 + c] = r === 0 || r === 10 ? v : imc(v) >>> 0;
      }
    }
    return d;
  }

  const keyCache = new Map();

  /** Decrypts `data` (length a multiple of 16) with AES-128-CBC; returns a new Uint8Array. */
  function decryptCBC(keyHex, iv, data) {
    let k = keyCache.get(keyHex);
    if (!k) {
      const key = new Uint8Array(16);
      for (let i = 0; i < 16; i++) key[i] = parseInt(keyHex.substr(2 * i, 2), 16);
      k = expandDecKey(key);
      keyCache.set(keyHex, k);
    }
    const out = new Uint8Array(data.length);
    let p0 = (iv[0] << 24 | iv[1] << 16 | iv[2] << 8 | iv[3]) >>> 0, p1 = (iv[4] << 24 | iv[5] << 16 | iv[6] << 8 | iv[7]) >>> 0,
        p2 = (iv[8] << 24 | iv[9] << 16 | iv[10] << 8 | iv[11]) >>> 0, p3 = (iv[12] << 24 | iv[13] << 16 | iv[14] << 8 | iv[15]) >>> 0;
    for (let o = 0; o < data.length; o += 16) {
      const c0 = (data[o] << 24 | data[o + 1] << 16 | data[o + 2] << 8 | data[o + 3]) >>> 0;
      const c1 = (data[o + 4] << 24 | data[o + 5] << 16 | data[o + 6] << 8 | data[o + 7]) >>> 0;
      const c2 = (data[o + 8] << 24 | data[o + 9] << 16 | data[o + 10] << 8 | data[o + 11]) >>> 0;
      const c3 = (data[o + 12] << 24 | data[o + 13] << 16 | data[o + 14] << 8 | data[o + 15]) >>> 0;
      let s0 = c0 ^ k[0], s1 = c1 ^ k[1], s2 = c2 ^ k[2], s3 = c3 ^ k[3];
      let ki = 4;
      for (let r = 1; r < 10; r++) {
        const t0 = T0[s0 >>> 24] ^ T1[(s3 >>> 16) & 0xff] ^ T2[(s2 >>> 8) & 0xff] ^ T3[s1 & 0xff] ^ k[ki];
        const t1 = T0[s1 >>> 24] ^ T1[(s0 >>> 16) & 0xff] ^ T2[(s3 >>> 8) & 0xff] ^ T3[s2 & 0xff] ^ k[ki + 1];
        const t2 = T0[s2 >>> 24] ^ T1[(s1 >>> 16) & 0xff] ^ T2[(s0 >>> 8) & 0xff] ^ T3[s3 & 0xff] ^ k[ki + 2];
        const t3 = T0[s3 >>> 24] ^ T1[(s2 >>> 16) & 0xff] ^ T2[(s1 >>> 8) & 0xff] ^ T3[s0 & 0xff] ^ k[ki + 3];
        s0 = t0; s1 = t1; s2 = t2; s3 = t3; ki += 4;
      }
      const r0 = (INV[s0 >>> 24] << 24 | INV[(s3 >>> 16) & 0xff] << 16 | INV[(s2 >>> 8) & 0xff] << 8 | INV[s1 & 0xff]) ^ k[40] ^ p0;
      const r1 = (INV[s1 >>> 24] << 24 | INV[(s0 >>> 16) & 0xff] << 16 | INV[(s3 >>> 8) & 0xff] << 8 | INV[s2 & 0xff]) ^ k[41] ^ p1;
      const r2 = (INV[s2 >>> 24] << 24 | INV[(s1 >>> 16) & 0xff] << 16 | INV[(s0 >>> 8) & 0xff] << 8 | INV[s3 & 0xff]) ^ k[42] ^ p2;
      const r3 = (INV[s3 >>> 24] << 24 | INV[(s2 >>> 16) & 0xff] << 16 | INV[(s1 >>> 8) & 0xff] << 8 | INV[s0 & 0xff]) ^ k[43] ^ p3;
      out[o] = r0 >>> 24; out[o + 1] = r0 >>> 16; out[o + 2] = r0 >>> 8; out[o + 3] = r0;
      out[o + 4] = r1 >>> 24; out[o + 5] = r1 >>> 16; out[o + 6] = r1 >>> 8; out[o + 7] = r1;
      out[o + 8] = r2 >>> 24; out[o + 9] = r2 >>> 16; out[o + 10] = r2 >>> 8; out[o + 11] = r2;
      out[o + 12] = r3 >>> 24; out[o + 13] = r3 >>> 16; out[o + 14] = r3 >>> 8; out[o + 15] = r3;
      p0 = c0; p1 = c1; p2 = c2; p3 = c3;
    }
    return out;
  }

  return { decryptCBC };
})();

if (typeof module !== "undefined") module.exports = AES;
