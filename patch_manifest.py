#!/usr/bin/env python3
"""
Inject android:foregroundServiceType="mediaProjection" into ScreenMirrorService
in a compiled binary AndroidManifest.xml inside an APK.

Usage: python3 patch_manifest.py <apk_path>
The APK is modified in-place using 'zip -u' (only the manifest is replaced).
"""
import struct, zipfile, io, sys, os, tempfile, shutil, subprocess

ANDROID_NS   = 'http://schemas.android.com/apk/res/android'
TARGET_SVC   = 'ScreenMirrorService'
ATTR_NAME    = 'foregroundServiceType'
ATTR_RES_ID  = 0x0101054b   # android.R.attr.foregroundServiceType
MEDIA_PROJ   = 0x20         # foregroundServiceType flag: mediaProjection = 32 (0x40 = camera!)

def r32(b, o): return struct.unpack_from('<I', b, o)[0]
def r16(b, o): return struct.unpack_from('<H', b, o)[0]
def w32(b, o, v): struct.pack_into('<I', b, o, v)
def w16(b, o, v): struct.pack_into('<H', b, o, v)

def read_utf8(b, pos):
    p = pos
    c = b[p]; p += 1
    if c & 0x80: c = ((c & 0x7f) << 8) | b[p]; p += 1
    c2 = b[p]; p += 1
    if c2 & 0x80: c2 = ((c2 & 0x7f) << 8) | b[p]; p += 1
    return b[p:p+c2].decode('utf-8', 'replace'), p + c2 + 1 - pos

def read_utf16(b, pos):
    n = r16(b, pos)
    return b[pos+2:pos+2+n*2].decode('utf-16-le', 'replace'), 2 + n*2 + 2

def encode_utf8(s):
    enc = s.encode('utf-8')
    n16, n8 = len(s), len(enc)
    def pfx(v): return bytes([v]) if v < 0x80 else bytes([0x80|(v>>8), v&0xff])
    return pfx(n16) + pfx(n8) + enc + b'\x00'

def encode_utf16(s):
    return struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\x00\x00'

def patch_axml(raw: bytes) -> bytes:
    d = bytearray(raw)
    assert r16(d, 0) == 0x0003, 'Not an AXML file'

    SP = 8
    assert r16(d, SP) == 0x0001, 'Expected string pool chunk'
    sp_hdr  = r16(d, SP+2)
    sp_size = r32(d, SP+4)
    sc      = r32(d, SP+8)
    flags   = r32(d, SP+16)
    soff    = r32(d, SP+20)
    utf8    = bool(flags & 0x100)
    read_s   = read_utf8  if utf8 else read_utf16
    encode_s = encode_utf8 if utf8 else encode_utf16

    def get_str(idx):
        sc2   = r32(d, SP+8)
        if idx < 0 or idx >= sc2: return ''
        soff2 = r32(d, SP+20)
        hdr2  = r16(d, SP+2)
        db    = SP + soff2
        ob    = SP + hdr2
        rel   = r32(d, ob + idx*4)
        s, _  = read_s(d, db + rel)
        return s

    strs    = [get_str(i) for i in range(sc)]
    ns_idx  = next((i for i,s in enumerate(strs) if s == ANDROID_NS), -1)
    fst_idx = next((i for i,s in enumerate(strs) if s == ATTR_NAME), -1)

    if ns_idx == -1:
        print('  ERROR: android namespace not found'); return raw

    # Step 1: add "foregroundServiceType" to string pool if absent
    if fst_idx == -1:
        new_bytes = encode_s(ATTR_NAME)
        # Pad string data so that (4-byte offset + string bytes) is 4-byte aligned.
        # Android's ResStringPool requires the chunk size to be word-aligned.
        pad = (4 - ((4 + len(new_bytes)) % 4)) % 4
        new_bytes = new_bytes + b'\x00' * pad

        last_rel = r32(d, SP + sp_hdr + (sc-1)*4)
        _, last_len = read_s(d, SP + soff + last_rel)
        new_rel = last_rel + last_len

        ins_off = SP + soff
        d = d[:ins_off] + struct.pack('<I', new_rel) + d[ins_off:]
        w32(d, SP+20, soff + 4)
        w32(d, SP+8,  sc + 1)

        ins_str = SP + sp_size + 4
        d = d[:ins_str] + bytearray(new_bytes) + d[ins_str:]

        delta = 4 + len(new_bytes)
        w32(d, SP+4, sp_size + delta)
        w32(d, 4, r32(d,4) + delta)
        fst_idx = sc
        print(f'  Added "{ATTR_NAME}" to string pool at index {fst_idx}')
    else:
        print(f'  "{ATTR_NAME}" already in string pool at index {fst_idx}')

    # Step 2: add/update resource ID in resource map
    sp_size = r32(d, SP+4)
    RM = SP + sp_size
    assert r16(d, RM) == 0x0180, f'Expected resource map at {RM}'
    rm_size = r32(d, RM+4)
    rm_cnt  = (rm_size - 8) // 4

    if fst_idx < rm_cnt:
        existing = r32(d, RM+8 + fst_idx*4)
        if existing != ATTR_RES_ID:
            w32(d, RM+8 + fst_idx*4, ATTR_RES_ID)
            print(f'  Updated resource map[{fst_idx}] to 0x{ATTR_RES_ID:08x}')
        else:
            print(f'  Resource ID already correct at index {fst_idx}')
    else:
        while (r32(d, RM+4) - 8) // 4 < fst_idx:
            ins = RM + r32(d, RM+4)
            d = d[:ins] + b'\x00\x00\x00\x00' + d[ins:]
            w32(d, RM+4, r32(d,RM+4)+4)
            w32(d, 4, r32(d,4)+4)
        ins = RM + r32(d, RM+4)
        d = d[:ins] + struct.pack('<I', ATTR_RES_ID) + d[ins:]
        w32(d, RM+4, r32(d,RM+4)+4)
        w32(d, 4, r32(d,4)+4)
        print(f'  Added resource ID 0x{ATTR_RES_ID:08x} to resource map')

    # Step 3: find <service android:name="...ScreenMirrorService..."> and inject attribute
    sp_size = r32(d, SP+4)
    rm_size = r32(d, RM+4)
    pos = RM + rm_size

    while pos < len(d) - 8:
        ctype = r16(d, pos)
        csz   = r32(d, pos+4)
        if csz == 0: break

        if ctype == 0x0102:  # StartElement
            tag_name = get_str(r32(d, pos+20))
            attr_cnt = r16(d, pos+28)

            if tag_name == 'service':
                is_target = False
                for i in range(attr_cnt):
                    ab = pos + 36 + i*20
                    a_name = r32(d, ab+4)
                    a_type = d[ab+15]
                    a_data = r32(d, ab+16)
                    a_raw  = r32(d, ab+8)
                    if get_str(a_name) == 'name' and a_type == 0x03:
                        val = get_str(a_data)
                        if not val and a_raw != 0xFFFFFFFF:
                            val = get_str(a_raw)
                        if TARGET_SVC in val:
                            is_target = True
                            break

                if is_target:
                    already = any(r32(d, pos+36+i*20+4) == fst_idx for i in range(attr_cnt))
                    if already:
                        print('  Attribute already present, nothing to do')
                        return bytes(d)

                    new_attr = struct.pack('<IIIHBBI',
                        ns_idx, fst_idx, 0xFFFFFFFF, 8, 0, 0x11, MEDIA_PROJ)

                    ins = pos + csz
                    d = d[:ins] + bytearray(new_attr) + d[ins:]
                    w16(d, pos+28, attr_cnt+1)
                    w32(d, pos+4,  csz+20)
                    w32(d, 4,      r32(d,4)+20)

                    print(f'  Injected {ATTR_NAME}=0x{MEDIA_PROJ:02x} on {TARGET_SVC}')
                    return bytes(d)

        pos += csz

    print(f'  WARNING: {TARGET_SVC} service element not found!')
    return bytes(d)


def update_manifest_in_apk(apk_path: str, new_manifest: bytes):
    """
    Replace AndroidManifest.xml in the APK using 'zip -u'.
    This avoids full repack and preserves all other entries intact.
    """
    tmpdir = tempfile.mkdtemp()
    try:
        mf_path = os.path.join(tmpdir, 'AndroidManifest.xml')
        with open(mf_path, 'wb') as f:
            f.write(new_manifest)

        result = subprocess.run(
            ['zip', '-u', os.path.abspath(apk_path), 'AndroidManifest.xml'],
            cwd=tmpdir,
            capture_output=True, text=True
        )
        if result.returncode not in (0, 12):  # 12 = nothing to update (already same)
            print(f'  zip -u stderr: {result.stderr}')
            raise RuntimeError(f'zip -u failed with code {result.returncode}')
    finally:
        shutil.rmtree(tmpdir)


def main():
    if len(sys.argv) < 2:
        print('Usage: patch_manifest.py <apk_file>'); sys.exit(1)
    apk = sys.argv[1]

    with zipfile.ZipFile(apk) as z:
        mf_orig = z.read('AndroidManifest.xml')

    print(f'Manifest: {len(mf_orig)} bytes')
    mf_patched = patch_axml(mf_orig)
    print(f'Patched:  {len(mf_patched)} bytes (+{len(mf_patched)-len(mf_orig)})')

    update_manifest_in_apk(apk, mf_patched)
    print(f'Done: {apk}')

main()
