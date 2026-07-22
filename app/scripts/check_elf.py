import struct, sys

def dump_elf(path):
    data = open(path, 'rb').read()
    is64 = data[4] == 2
    le = data[5] == 1
    e = '<' if le else '>'

    if is64:
        e_phoff = struct.unpack_from(e + 'Q', data, 32)[0]
        e_phentsize = struct.unpack_from(e + 'H', data, 54)[0]
        e_phnum = struct.unpack_from(e + 'H', data, 56)[0]
        ph_fmt = e + 'IIQQQQQQ'
    else:
        e_phoff = struct.unpack_from(e + 'I', data, 28)[0]
        e_phentsize = struct.unpack_from(e + 'H', data, 42)[0]
        e_phnum = struct.unpack_from(e + 'H', data, 44)[0]
        ph_fmt = e + 'IIIIIIII'

    print(f'File: {path}  size={len(data)}  is64={is64}')
    print(f'e_phoff={e_phoff} phentsize={e_phentsize} phnum={e_phnum}')
    print()

    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        f = list(struct.unpack_from(ph_fmt, data, off))
        p_type = f[0]
        if p_type != 1:
            continue
        if is64:
            p_offset, p_vaddr = f[2], f[3]
            p_filesz, p_memsz = f[5], f[6]
            p_align = f[7]
        else:
            p_offset, p_vaddr = f[1], f[2]
            p_filesz, p_memsz = f[4], f[5]
            p_align = f[7]

        seg = data[p_offset:p_offset + min(p_filesz, 64)]
        # Count non-zero bytes
        full_seg = data[p_offset:p_offset + p_filesz]
        nonz = sum(1 for b in full_seg if b != 0)
        zeros = nonz == 0

        print(f'LOAD #{i}: off={p_offset:#x} vaddr={p_vaddr:#x} filesz={p_filesz:#x} memsz={f[6 if is64 else 5]:#x} align={p_align:#x}')
        print(f'  data[0:32] = {seg[:32].hex(" ")}')
        print(f'  non-zero bytes in segment: {nonz} / {p_filesz}')
        # Check if p_offset is within file
        if p_offset + p_filesz > len(data):
            print(f'  ** ERROR: segment extends past file end **')

if __name__ == '__main__':
    for arg in sys.argv[1:]:
        dump_elf(arg)
