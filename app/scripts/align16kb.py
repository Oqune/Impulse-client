#!/usr/bin/env python3
"""
Re-align ELF shared libraries to a 16 KB (16384 byte) page size so they load on
Android 15+ devices that use 16 KB memory pages.

Android's dynamic linker rejects libraries whose PT_LOAD segments are not
aligned to the device page size. For a 16 KB device it requires, for EVERY
PT_LOAD segment:

    p_offset % 16384 == 0   (file offset aligned)
    p_vaddr  % 16384 == 0   (virtual address aligned)
    p_align  == 16384       (declared alignment)

`zipalign -p 16` only aligns the *zip entry offset*, NOT the ELF-internal
p_offset/p_vaddr, so it cannot fix this by itself.

This script rewrites the ELF so that each PT_LOAD segment is placed at a 16 KB
aligned file offset equal to its (16 KB aligned) virtual address. We use a
zero bias (p_offset == p_vaddr) which is always a valid, loadable layout and
guarantees both offsets are 16 KB aligned. Non-LOAD segments (NOTE, GNU_STACK,
dynamic, interp, etc.) are preserved verbatim and re-emitted at their original
positions relative to the new layout (they are not required to be 16 KB
aligned). The section header table and section data are dropped/ignored because
they are not needed at load time and re-laying them out correctly is unnecessary.

Usage:
  align16kb.py FILE.so     # rewrite FILE.so in place
  align16kb.py ARCHIVE.apk # rewrite every lib/*.so inside the apk (zip)
"""
import os
import sys
import struct
import zipfile

PAGE = 16384
PT_LOAD = 1


def align_up(v, a):
    return (v + (a - 1)) & ~(a - 1)


def fix_elf(data: bytes) -> bytes:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return data  # not an ELF -> leave untouched
    ei_class = data[4]  # 1 = 32-bit, 2 = 64-bit
    ei_data = data[5]   # 1 = LE, 2 = BE
    if ei_class not in (1, 2):
        return data
    endian = "<" if ei_data == 1 else ">"
    is64 = ei_class == 2

    if is64:
        (e_phoff,) = struct.unpack_from(endian + "Q", data, 32)
        (e_phentsize,) = struct.unpack_from(endian + "H", data, 54)
        (e_phnum,) = struct.unpack_from(endian + "H", data, 56)
        ph_fmt = endian + "IIQQQQQQ"   # p_type,p_flags,p_offset,p_vaddr,p_paddr,p_filesz,p_memsz,p_align
        ph_size = 56
        O, V, PA, FS, MS, AL = 2, 3, 4, 5, 6, 7
    else:
        (e_phoff,) = struct.unpack_from(endian + "I", data, 28)
        (e_phentsize,) = struct.unpack_from(endian + "H", data, 42)
        (e_phnum,) = struct.unpack_from(endian + "H", data, 44)
        ph_fmt = endian + "IIIIIIII"   # p_type,p_offset,p_vaddr,p_paddr,p_filesz,p_memsz,p_flags,p_align
        ph_size = 32
        O, V, PA, FS, MS, AL = 1, 2, 3, 4, 5, 7

    if e_phnum == 0 or e_phentsize < ph_size:
        return data

    # Parse program headers.
    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        fields = list(struct.unpack_from(ph_fmt, data, off))
        phdrs.append(fields)

    loads = [f for f in phdrs if f[0] == PT_LOAD]
    if not loads:
        return data

    # Determine the maximum virtual address used so we can place the
    # non-LOAD data (e.g. .dynamic) after the last LOAD segment.
    max_vaddr = 0
    for f in loads:
        max_vaddr = max(max_vaddr, f[V] + f[MS])

    # Build the new file:
    #   [ ELF header ] [ phdr table ] [ padding ] [ LOAD segments at 16KB ]
    # Each LOAD segment i is written at file offset == its (16KB-aligned) vaddr.
    # We keep a single growing buffer; gaps are zero-filled.
    ehdr = bytearray(data[:e_phoff if e_phoff > 0 else (64 if is64 else 52)])
    phdr_table = bytearray(data[e_phoff:e_phoff + e_phnum * e_phentsize])

    out = bytearray()
    out += ehdr
    out += phdr_table

    # Map each LOAD segment to a new 16 KB-aligned (offset == vaddr) position.
    for f in phdrs:
        if f[0] != PT_LOAD:
            continue
        old_off = f[O]
        old_vaddr = f[V]
        filesz = f[FS]
        memsz = f[MS]
        seg = data[old_off: old_off + filesz]
        new_vaddr = align_up(old_vaddr, PAGE)
        new_off = new_vaddr  # zero bias: p_offset == p_vaddr
        # Ensure the buffer is large enough; pad with zeros.
        if len(out) < new_off:
            out += b"\x00" * (new_off - len(out))
        # Place segment data at new_off.
        out[new_off:new_off + filesz] = seg
        f[O] = new_off
        f[V] = new_vaddr
        f[PA] = new_vaddr
        f[AL] = PAGE

    # Write back the (possibly modified) program headers into the table region.
    for i, f in enumerate(phdrs):
        struct.pack_into(ph_fmt, phdr_table, i * e_phentsize, *f)

    # Recompute e_phoff (phdr table sits right after ehdr) and write header.
    new_phoff = len(ehdr)
    if is64:
        struct.pack_into(endian + "Q", ehdr, 32, new_phoff)
    else:
        struct.pack_into(endian + "I", ehdr, 28, new_phoff)

    # Assemble final bytes: ehdr + updated phdr table + the padded segment body.
    result = bytearray()
    result += ehdr
    result += phdr_table
    # The segment data was built in `out` starting at new_phoff; copy it.
    result += out[new_phoff:]
    return bytes(result)


def fix_apk(path: str):
    tmp = path + ".tmp"
    with zipfile.ZipFile(path, "r") as zin:
        names = zin.namelist()
        infos = {n: zin.getinfo(n) for n in names}
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in names:
                data = zin.read(n)
                if n.endswith(".so") and n.startswith("lib/"):
                    data = fix_elf(data)
                zout.writestr(infos[n], data)
    os.replace(tmp, path)


def check_elf(data: bytes) -> bool:
    """Return True if every PT_LOAD segment is 16 KB aligned (offset, vaddr, align)."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return True  # not an ELF -> nothing to check
    ei_class = data[4]
    ei_data = data[5]
    if ei_class not in (1, 2):
        return True
    endian = "<" if ei_data == 1 else ">"
    is64 = ei_class == 2
    if is64:
        (e_phoff,) = struct.unpack_from(endian + "Q", data, 32)
        (e_phentsize,) = struct.unpack_from(endian + "H", data, 54)
        (e_phnum,) = struct.unpack_from(endian + "H", data, 56)
        ph_fmt = endian + "IIQQQQQQ"
        ph_size = 56
        O, V, AL = 2, 3, 7
    else:
        (e_phoff,) = struct.unpack_from(endian + "I", data, 28)
        (e_phentsize,) = struct.unpack_from(endian + "H", data, 42)
        (e_phnum,) = struct.unpack_from(endian + "H", data, 44)
        ph_fmt = endian + "IIIIIIII"
        ph_size = 32
        O, V, AL = 1, 2, 7
    if e_phnum == 0 or e_phentsize < ph_size:
        return True
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        f = struct.unpack_from(ph_fmt, data, off)
        if f[0] == PT_LOAD:
            if f[O] % PAGE != 0 or f[V] % PAGE != 0 or f[AL] != PAGE:
                return False
    return True


def verify_apk(path: str) -> bool:
    """Verify every lib/*.so inside the APK is 16 KB aligned. Returns True if all OK."""
    bad = []
    with zipfile.ZipFile(path, "r") as zin:
        for n in zin.namelist():
            if n.endswith(".so") and n.startswith("lib/"):
                if not check_elf(zin.read(n)):
                    bad.append(n)
    if bad:
        print("ERROR: the following .so files are NOT 16 KB aligned:")
        for n in bad:
            print(f"  - {n}")
        return False
    print(f"OK: all lib/*.so in {path} are 16 KB aligned")
    return True


def main():
    if len(sys.argv) < 2:
        print("usage: align16kb.py [--check] <file.so|file.apk>", file=sys.stderr)
        sys.exit(2)
    check_only = "--check" in sys.argv[1:]
    targets = [a for a in sys.argv[1:] if not a.startswith("--")]
    for arg in targets:
        if arg.endswith(".apk") or arg.endswith(".zip"):
            if check_only:
                if not verify_apk(arg):
                    sys.exit(1)
            else:
                print(f"fixing apk: {arg}")
                fix_apk(arg)
                if not verify_apk(arg):
                    sys.exit(1)
        elif arg.endswith(".so"):
            data = open(arg, "rb").read()
            if check_only:
                if not check_elf(data):
                    print(f"NOT aligned: {arg}")
                    sys.exit(1)
                print(f"aligned: {arg}")
            else:
                fixed = fix_elf(data)
                if fixed != data:
                    open(arg, "wb").write(fixed)
                    print(f"re-aligned: {arg}")
                else:
                    print(f"unchanged: {arg}")
        else:
            print(f"skip (unknown): {arg}")


if __name__ == "__main__":
    main()
