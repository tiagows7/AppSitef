using System;
using System.IO;
using System.Text;

public static class PatchPin {
    static void W(byte[] b, int o, int v) {
        b[o] = (byte)((v >> 8) & 255);
        b[o + 1] = (byte)(v & 255);
    }

    public static string Run(string path) {
        byte[] d = File.ReadAllBytes(path);
        var sb = new StringBuilder();
        byte[] h = new byte[] { 0xb2, 0x00, 0x89, 0x99, 0x00, 0x0f, 0x03, 0xb3, 0x00, 0x89 };
        int hide = -1, hn = 0;
        for (int i = 0; i <= d.Length - h.Length; i++) {
            bool ok = true;
            for (int j = 0; j < h.Length; j++) if (d[i + j] != h[j]) { ok = false; break; }
            if (ok) { hide = i; hn++; }
        }
        sb.Append("hideHits=").Append(hn).AppendLine();
        if (hn != 1) return sb.ToString();
        d[hide + 6] = 0x00; d[hide + 7] = 0x00; d[hide + 8] = 0x00; d[hide + 9] = 0x00;

        byte[] old = new byte[] {
            0x2a,0xc2,0x03,0xb3,0x00,0x89,0x2a,0x03,0xb5,0x00,0x75,0x2a,0x03,0xb5,0x00,0x73,
            0x2a,0x10,0x0d,0xb5,0x00,0x77,0x2a,0xc3,0xb1,0x4c,0x2a,0xc3,0x2b,0xbf
        };
        int at = -1, n = 0;
        for (int i = 0; i <= d.Length - old.Length; i++) {
            bool ok = true;
            for (int j = 0; j < old.Length; j++) if (d[i + j] != old[j]) { ok = false; break; }
            if (ok) { at = i; n++; }
        }
        sb.Append("onDismiss=").Append(n).Append(" @").Append(at).AppendLine();
        if (n != 1) return sb.ToString();
        int lenAt = at - 4;
        int codeLen = (d[lenAt] << 24) | (d[lenAt + 1] << 16) | (d[lenAt + 2] << 8) | d[lenAt + 3];
        if (codeLen != 30) { sb.Append("codeLen=").Append(codeLen); return sb.ToString(); }
        byte[] neu = new byte[] {
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
        int delta = neu.Length - old.Length;
        int attrLenAt = lenAt - 8;
        int attrLen = (d[attrLenAt] << 24) | (d[attrLenAt + 1] << 16) | (d[attrLenAt + 2] << 8) | d[attrLenAt + 3];
        byte[] outb = new byte[d.Length + delta];
        Buffer.BlockCopy(d, 0, outb, 0, at);
        Buffer.BlockCopy(neu, 0, outb, at, neu.Length);
        Buffer.BlockCopy(d, at + old.Length, outb, at + neu.Length, d.Length - (at + old.Length));
        outb[lenAt] = 0; outb[lenAt + 1] = 0; outb[lenAt + 2] = 0; outb[lenAt + 3] = 39;
        int newAttr = attrLen + delta;
        outb[attrLenAt] = (byte)((newAttr >> 24) & 255);
        outb[attrLenAt + 1] = (byte)((newAttr >> 16) & 255);
        outb[attrLenAt + 2] = (byte)((newAttr >> 8) & 255);
        outb[attrLenAt + 3] = (byte)(newAttr & 255);
        int ex = at + neu.Length;
        int exCount = (outb[ex] << 8) | outb[ex + 1];
        if (exCount != 2) { sb.Append("exCount=").Append(exCount); return sb.ToString(); }
        int e0 = ex + 2;
        W(outb, e0, 2); W(outb, e0 + 2, 0x22); W(outb, e0 + 4, 0x22); W(outb, e0 + 6, 0);
        int e1 = e0 + 8;
        W(outb, e1, 0x23); W(outb, e1 + 2, 0x25); W(outb, e1 + 4, 0x22); W(outb, e1 + 6, 0);
        File.WriteAllBytes(path, outb);
        sb.Append("patched");
        return sb.ToString();
    }
}
