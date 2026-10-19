using System;
using System.IO;
using System.Text;

public static class PatchPin2 {
    static int Find(byte[] d, byte[] n) {
        int hits = 0, at = -1;
        for (int i = 0; i <= d.Length - n.Length; i++) {
            bool ok = true;
            for (int j = 0; j < n.Length; j++) if (d[i + j] != n[j]) { ok = false; break; }
            if (ok) { at = i; hits++; }
        }
        if (hits != 1) return -hits;
        return at;
    }

    static void U4(byte[] b, int o, int v) {
        b[o] = (byte)((v >> 24) & 255);
        b[o + 1] = (byte)((v >> 16) & 255);
        b[o + 2] = (byte)((v >> 8) & 255);
        b[o + 3] = (byte)(v & 255);
    }

    static void U2(byte[] b, int o, int v) {
        b[o] = (byte)((v >> 8) & 255);
        b[o + 1] = (byte)(v & 255);
    }

    public static string Run(string path) {
        byte[] d = File.ReadAllBytes(path);
        var sb = new StringBuilder();

        byte[] oldOn = new byte[] {
            0x2a, 0xc2,
            0xb2, 0x00, 0x89,
            0x99, 0x00, 0x1a,
            0x03, 0xb3, 0x00, 0x89,
            0x2a, 0x03, 0xb5, 0x00, 0x75,
            0x2a, 0x03, 0xb5, 0x00, 0x73,
            0x2a, 0x10, 0x0d, 0xb5, 0x00, 0x77,
            0x2a, 0xc3, 0xb1,
            0x2a, 0xc3, 0xb1,
            0x4c, 0x2a, 0xc3, 0x2b, 0xbf
        };
        int on = Find(d, oldOn);
        sb.Append("on=").Append(on).AppendLine();
        if (on < 0) return sb.ToString();
        byte[] neuOn = new byte[39];
        neuOn[0] = 0x2a; neuOn[1] = 0xc2;
        neuOn[2] = 0x2a; neuOn[3] = 0xc3; neuOn[4] = 0xb1;
        neuOn[34] = 0x4c; neuOn[35] = 0x2a; neuOn[36] = 0xc3; neuOn[37] = 0x2b; neuOn[38] = 0xbf;
        Buffer.BlockCopy(neuOn, 0, d, on, 39);
        int exOn = on + 39;
        if (((d[exOn] << 8) | d[exOn + 1]) != 2) {
            sb.Append("onEx bad");
            return sb.ToString();
        }
        U2(d, exOn + 2, 2); U2(d, exOn + 4, 34); U2(d, exOn + 6, 34); U2(d, exOn + 8, 0);
        U2(d, exOn + 10, 35); U2(d, exOn + 12, 37); U2(d, exOn + 14, 34); U2(d, exOn + 16, 0);

        byte[] oldHp = new byte[] {
            0xb2, 0x00, 0x89, 0x99, 0x00, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x2a, 0xb6, 0x01, 0xb0
        };
        int hp = Find(d, oldHp);
        sb.Append("hp=").Append(hp).AppendLine();
        if (hp < 0) return sb.ToString();
        int codeAt = hp - 2;
        int lenAt = codeAt - 4;
        int codeLen = (d[lenAt] << 24) | (d[lenAt + 1] << 16) | (d[lenAt + 2] << 8) | d[lenAt + 3];
        sb.Append("hpLen=").Append(codeLen).AppendLine();
        if (codeLen != 28) return sb.ToString();
        byte[] neuHp = new byte[] {
            0x2a, 0xc2,
            0xb2, 0x00, 0x89,
            0x99, 0x00, 0x15,
            0x2a, 0x10, 0x0d, 0xb5, 0x00, 0x77,
            0x03, 0xb3, 0x00, 0x89,
            0x2a, 0xb6, 0x01, 0xb0,
            0xa7, 0x00, 0x04,
            0x57,
            0x2a, 0xc3, 0xb1,
            0x4c, 0x2a, 0xc3, 0x2b, 0xbf
        };
        int oldExBytes = 2 + 2 * 8;
        int newExBytes = 2 + 3 * 8;
        int tail = codeAt + codeLen + oldExBytes;
        int delta = (neuHp.Length - codeLen) + (newExBytes - oldExBytes);
        int attrAt = lenAt - 8;
        int attrLen = (d[attrAt] << 24) | (d[attrAt + 1] << 16) | (d[attrAt + 2] << 8) | d[attrAt + 3];
        byte[] outb = new byte[d.Length + delta];
        Buffer.BlockCopy(d, 0, outb, 0, codeAt);
        Buffer.BlockCopy(neuHp, 0, outb, codeAt, neuHp.Length);
        int hex = codeAt + neuHp.Length;
        U2(outb, hex, 3);
        U2(outb, hex + 2, 18); U2(outb, hex + 4, 22); U2(outb, hex + 6, 25); U2(outb, hex + 8, 0);
        U2(outb, hex + 10, 2); U2(outb, hex + 12, 29); U2(outb, hex + 14, 29); U2(outb, hex + 16, 0);
        U2(outb, hex + 18, 30); U2(outb, hex + 20, 32); U2(outb, hex + 22, 29); U2(outb, hex + 24, 0);
        Buffer.BlockCopy(d, tail, outb, hex + newExBytes, d.Length - tail);
        U4(outb, lenAt, neuHp.Length);
        U4(outb, attrAt, attrLen + delta);
        File.WriteAllBytes(path, outb);
        sb.Append("patched");
        return sb.ToString();
    }
}
