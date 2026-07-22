#!/usr/bin/env python3
import os, sys, struct, zipfile

PAGE = 16384
PT_LOAD = 1

def align_up(v, a):
    return (v + (a - 1)) & ~(a - 1)

def fix_elf(data: bytes) -> bytes:
    """Rewrite PT_LOAD segments so p_align=16384 and p_offset/p_vaddr are
    16KB-aligned with zero bias.  Returns the fixed ELF file bytes."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return data
    ei_class = data[4]
    ei_data = data[5]
    if ei_class not in (1, 2):
        return data
    endian = "<" if ei_data == 1 else ">"
    is64 = ei_class == 2

    if is64:
        e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
        ph_fmt = endian + "IIQQQQQQ"
        ph_size = 56
        # 64-bit PHDR fields: p_type(0), p_flags(1), p_offset(2), p_vaddr(3),
        #                     p_paddr(4), p_filesz(5), p_memsz(6), p_align(7)
        T, F, O, V, PA, FS, MS, AL = range(8)
    else:
        e_phoff = struct.unpack_from(endian + "I", data, 28)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 44)[0]
        ph_fmt = endian + "IIIIIIII"
        ph_size = 32
        # 32-bit PHDR fields: p_type(0), p_offset(1), p_vaddr(2), p_paddr(3),
        #                     p_filesz(4), p_memsz(5), p_flags(6), p_align(7)
        T, O, V, PA, FS, MS, F, AL = range(8)

    if e_phnum == 0 or e_phentsize < ph_size:
        return data

    # Parse program headers
    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        fields = list(struct.unpack_from(ph_fmt, data, off))
        phdrs.append(fields)

    loads = [f for f in phdrs if f[T] == PT_LOAD]
    if not loads:
        return data

    # Save original segment data and header values before we modify headers
    segs = []
    load_orig = []
    for f in loads:
        segs.append(data[f[O]:f[O] + f[FS]])
        load_orig.append((f[O], f[V]))

    # Read section header table info from ELF header before it gets lost
    if is64:
        e_shoff = struct.unpack_from(endian + "Q", data, 40)[0]
        e_shentsize = struct.unpack_from(endian + "H", data, 58)[0]
        e_shnum = struct.unpack_from(endian + "H", data, 60)[0]
        e_shstrndx = struct.unpack_from(endian + "H", data, 62)[0]
    else:
        e_shoff = struct.unpack_from(endian + "I", data, 32)[0]
        e_shentsize = struct.unpack_from(endian + "H", data, 46)[0]
        e_shnum = struct.unpack_from(endian + "H", data, 48)[0]
        e_shstrndx = struct.unpack_from(endian + "H", data, 50)[0]

    # Save section header table bytes (if present and not inside PT_LOAD data)
    shdr_data = None
    if e_shoff > 0 and e_shnum > 0 and e_shentsize > 0:
        shdr_size = e_shnum * e_shentsize
        if e_shoff + shdr_size <= len(data):
            shdr_data = data[e_shoff:e_shoff + shdr_size]

    # New file layout:
    #   [0 : header_end)           = ELF header + PHDR table (unchanged)
    #   [header_end : first_off)   = zero padding
    #   [first_off : ...)          = segment data, each placed so that
    #                                p_offset % PAGE == p_vaddr % PAGE
    #   [... : end)                = section header table (appended)
    #
    # Kernel constraint for 16 KB pages: (p_vaddr - p_offset) % PAGE == 0.
    # Equivalently, p_offset % PAGE == p_vaddr % PAGE.  Since we keep the
    # original p_vaddr unchanged, we must compute new p_offsets that satisfy
    # this constraint.  Changing p_vaddr would break DT_* entries
    # (DT_HASH, DT_GNU_HASH, DT_STRTAB, etc.) which contain absolute virtual
    # addresses that assume the original load base.
    header_end = e_phoff + e_phnum * e_phentsize
    cur_min = align_up(header_end, PAGE)

    def align_to_rem(offset, page_size, target_rem):
        """Smallest value >= offset such that value % page_size == target_rem."""
        r = offset % page_size
        if r <= target_rem:
            return offset - r + target_rem
        else:
            return offset - r + page_size + target_rem

    # Build map from old PT_LOAD (p_vaddr, p_offset) -> new p_offset
    old_to_new_load = {}
    for f, seg in zip(loads, segs):
        old_off = f[O]
        old_vaddr = f[V]
        target_rem = old_vaddr % PAGE
        new_off = align_to_rem(cur_min, PAGE, target_rem)
        f[O] = new_off
        # Keep original p_vaddr/p_paddr unchanged
        f[AL] = PAGE
        old_to_new_load[(old_vaddr, old_off)] = new_off
        cur_min = new_off + f[FS]

    # Also update non-PT_LOAD program header file offsets so they point to the
    # correct data within the moved PT_LOAD segments, but keep original virtual
    # addresses (they remain valid since PT_LOAD p_vaddr is unchanged).
    #
    # NB: iterate PT_LOADs in reverse order.  Overlapping ranges (RW segment
    # shares pages with preceding R segment) must match via virtual address
    # delta, not file offset.
    for f in phdrs:
        if f[T] == PT_LOAD:
            continue
        old_off = f[O]
        old_vaddr = f[V]
        for (load_old_off, load_old_vaddr), load_f, seg in reversed(list(zip(load_orig, loads, segs))):
            seg_end = load_old_off + load_f[FS]
            # Match by both file offset AND virtual address delta
            if load_old_off <= old_off < seg_end:
                delta = old_vaddr - old_off   # non-PT_LOAD's original delta
                load_delta = load_old_vaddr - load_old_off  # PT_LOAD's delta
                if delta != load_delta:
                    continue  # wrong PT_LOAD (shares file pages but has different mapping)
                load_new_off = old_to_new_load[(load_old_vaddr, load_old_off)]
                new_off = load_new_off + (old_off - load_old_off)
                f[O] = new_off
                # f[V] and f[PA] keep original values — virtual addresses unchanged
                break

    # Write updated headers back into a copy of the original header area
    out = bytearray(data[:header_end])
    for i, f in enumerate(phdrs):
        struct.pack_into(ph_fmt, out, e_phoff + i * e_phentsize, *f)

    # Place segment data at their exact p_offset positions (slice assignment).
    # Segments may overlap in file offset (sharing pages between PT_LOADs);
    # the last-written segment takes priority for overlapping bytes, matching
    # kernel behavior (later PT_LOAD wins on shared pages).
    need = max(f[O] + f[FS] for f in loads)
    if len(out) < need:
        out += b"\x00" * (need - len(out))
    for f, seg in zip(loads, segs):
        out[f[O]:f[O] + f[FS]] = seg

    # Append section header table if present, update sh_offset for sections
    # that were inside moved PT_LOAD segments, and update e_shoff in ELF header.
    if shdr_data is not None:
        if is64:
            sh_fmt = endian + "IIQQQQIIQQ"
            sh_off_off = 24  # offset of sh_offset field in 64-bit section header
            sh_size = 64
        else:
            sh_fmt = endian + "IIIIIIIIII"
            sh_off_off = 16  # offset of sh_offset field in 32-bit section header
            sh_size = 40

        shdr_fixed = bytearray(shdr_data)
        for i in range(e_shnum):
            base = i * sh_size
            raw_off = struct.unpack_from(endian + ("Q" if is64 else "I"), shdr_fixed, base + sh_off_off)[0]
            # Check if this section falls within any original PT_LOAD segment
            for (load_old_off, load_old_vaddr), load_f in zip(load_orig, loads):
                seg_end = load_old_off + load_f[FS]
                if load_old_off <= raw_off < seg_end:
                    new_raw_off = old_to_new_load[(load_old_vaddr, load_old_off)] + (raw_off - load_old_off)
                    if is64:
                        struct.pack_into(endian + "Q", shdr_fixed, base + sh_off_off, new_raw_off)
                    else:
                        struct.pack_into(endian + "I", shdr_fixed, base + sh_off_off, new_raw_off)
                    break

        new_shoff = len(out)
        out += bytes(shdr_fixed)
        if is64:
            struct.pack_into(endian + "Q", out, 40, new_shoff)
        else:
            struct.pack_into(endian + "I", out, 32, new_shoff)
    else:
        # No section headers - zero out the fields so the linker skips validation
        if is64:
            struct.pack_into(endian + "Q", out, 40, 0)
            struct.pack_into(endian + "H", out, 60, 0)
            struct.pack_into(endian + "H", out, 62, 0)
        else:
            struct.pack_into(endian + "I", out, 32, 0)
            struct.pack_into(endian + "H", out, 48, 0)
            struct.pack_into(endian + "H", out, 50, 0)

    return bytes(out)

def fix_apk(path: str):
    tmp = path + ".tmp"
    with zipfile.ZipFile(path, "r") as zin:
        names = zin.namelist()
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zout:
            for n in names:
                data = zin.read(n)
                if n.endswith(".so") and n.startswith("lib/"):
                    fixed = fix_elf(data)
                    if fixed != data:
                        data = fixed
                zout.writestr(n, data)
    os.replace(tmp, path)

def check_elf(data: bytes) -> bool:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return True
    ei_class = data[4]
    ei_data = data[5]
    if ei_class not in (1, 2):
        return True
    endian = "<" if ei_data == 1 else ">"
    is64 = ei_class == 2
    if is64:
        e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
        ph_fmt = endian + "IIQQQQQQ"
        ph_size = 56
        T, F, O, V, PA, FS, MS, AL = range(8)
    else:
        e_phoff = struct.unpack_from(endian + "I", data, 28)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 44)[0]
        ph_fmt = endian + "IIIIIIII"
        ph_size = 32
        T, O, V, PA, FS, MS, F, AL = range(8)
    if e_phnum == 0 or e_phentsize < ph_size:
        return True
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        f = struct.unpack_from(ph_fmt, data, off)
        if f[T] == PT_LOAD:
            if f[O] % PAGE != f[V] % PAGE or f[AL] < PAGE:
                return False
    return True

def verify_apk(path: str) -> bool:
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
        print("usage: align16kb.py [--check] <file.so>...", file=sys.stderr)
        sys.exit(2)
    check_only = "--check" in sys.argv[1:]
    targets = [a for a in sys.argv[1:] if not a.startswith("--")]
    exit_code = 0
    for arg in targets:
        if arg.endswith(".apk") or arg.endswith(".zip"):
            if check_only:
                if not verify_apk(arg):
                    exit_code = 1
            else:
                fix_apk(arg)
                if not verify_apk(arg):
                    print(f"WARNING: verification failed after fix for {arg}", file=sys.stderr)
                    exit_code = 1
        elif arg.endswith(".so"):
            with open(arg, "rb") as f:
                data = f.read()
            if check_only:
                if check_elf(data):
                    print(f"aligned: {arg}")
                else:
                    print(f"NOT aligned: {arg}")
                    exit_code = 1
            else:
                fixed = fix_elf(data)
                if fixed != data:
                    with open(arg, "wb") as f:
                        f.write(fixed)
                    print(f"fixed: {arg}")
                else:
                    print(f"ok: {arg}")
        else:
            print(f"skip (unknown): {arg}")
    sys.exit(exit_code)

if __name__ == "__main__":
    main()
