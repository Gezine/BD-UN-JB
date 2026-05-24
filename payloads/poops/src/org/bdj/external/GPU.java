package org.bdj.external;

import org.bdj.api.*;
import org.bdj.Status;

public class GPU {

    private static final long DMEM_SIZE = 2L * 0x100000L;

    private static final int PROT_READ       = 0x01;
    private static final int PROT_WRITE      = 0x02;
    private static final int GPU_READ        = 0x10;
    private static final int GPU_WRITE       = 0x20;
    private static final int MAP_NO_COALESCE = 0x400000;
    private static final int O_RDWR          = 0x02;

    private static final int PROT_RO = PROT_READ | PROT_WRITE | GPU_READ;
    private static final int PROT_RW = PROT_RO | GPU_WRITE;

    private static final long CPU_PHYS_MASK = 0x000FFFFFFFFFF000L;

    private static final long GPU_PDE_ADDR_MASK             = 0x0000ffffffffffc0L;
    private static final int  GPU_PDE_SHIFT_VALID           = 0;
    private static final int  GPU_PDE_SHIFT_IS_PTE          = 54;
    private static final int  GPU_PDE_SHIFT_TF              = 56;
    private static final int  GPU_PDE_SHIFT_BLOCK_FRAG_SIZE = 59;
    private static final long GPU_PDE_MASK_VALID            = 0x1L;
    private static final long GPU_PDE_MASK_IS_PTE           = 0x1L;
    private static final long GPU_PDE_MASK_TF               = 0x1L;
    private static final long GPU_PDE_MASK_BLOCK_FRAG_SIZE  = 0x1fL;

    private static final long PM4_IT_DMA_DATA    = 0x50L;
    private static final long PM4_PACKET_TYPE3   = 0x3L;
    private static final long PM4_SHADER_COMPUTE = 0x1L;

    private static final long IOCTL_SUBMIT_COMMANDS = 0xC0108102L;

    private static final long PROC_VM_SPACE     = 0x200L;
    private static final long SIZEOF_GVMSPACE   = 0x100L;
    private static final long GVMSPACE_START_VA = 0x08L;
    private static final long GVMSPACE_SIZE     = 0x10L;
    private static final long GVMSPACE_PAGE_DIR = 0x38L;
    private static final long PMAP_PML4         = 0x20L;
    private static final long PMAP_CR3          = 0x28L;

    private static long KERNEL_PMAP_STORE = 0L;
    private static long GVMSPACE         = 0L;
    private static long SECURITY_FLAGS   = 0L;

    private static long curproc    = 0;
    private static long kdata_base = 0;

    private static long kernelCr3 = 0;
    private static long dmap      = 0;

    private static final API api;
    private static final KernelAPI kapi;
    
    private static long open;
    private static long close;
    private static long ioctl;
    private static long mprotect;
    private static long write;
    private static long usleep;
    private static long sceKernelAllocateMainDirectMemory;
    private static long sceKernelMapDirectMemory;

    private static int  gpuFd                  = -1;
    private static long victimVa               = 0;
    private static long transferVa             = 0;
    private static long cmdVa                  = 0;
    private static long victimPtbeVa           = 0;
    private static long clearedVictimPtbeForRo = 0;
    
    static {
        try {
            api  = API.getInstance();
            kapi = KernelAPI.getInstance();
            
            open     = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "open");
            close    = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "close");
            ioctl    = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "ioctl");
            mprotect = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "mprotect");
            write    = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "write");
            usleep    = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "usleep");
            sceKernelAllocateMainDirectMemory = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "sceKernelAllocateMainDirectMemory");
            sceKernelMapDirectMemory = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "sceKernelMapDirectMemory");
            
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private static int open(String path, int flags) {
        return (int) api.call(open, new Text(path).address(), flags);
    }

    private static int close(int fd) {
        return (int) api.call(close, fd);
    }

    private static int ioctl(int fd, long request, long structPtr) {
        return (int) api.call(ioctl, fd, request, structPtr);
    }

    private static int mprotect(long addr, long len, int prot) {
        return (int) api.call(mprotect, addr, len, prot);
    }

    private static long write(int fd, Buffer buf, long nbytes) {
        return api.call(write, fd, buf != null ? buf.address() : 0L, nbytes);
    }

    private static long allocMainDmem(long size, int prot, int flags) {
        Int64 out = new Int64();

        long ret = api.call(sceKernelAllocateMainDirectMemory, size, size, 1L, out.address());
        if (ret != 0) {
            throw new InternalError("sceKernelAllocateMainDirectMemory() failed: 0x"
                    + Long.toHexString(ret));
        }

        long physAddr = out.get();
        out.set(0L);

        long ret2 = api.call(sceKernelMapDirectMemory,
                out.address(), size, (long) prot, (long) flags,
                physAddr, size);
        if (ret2 != 0) {
            throw new InternalError("sceKernelMapDirectMemory() failed: 0x"
                    + Long.toHexString(ret2));
        }

        return out.get();
    }

    private static long physToDmap(long physAddr) {
        return dmap + physAddr;
    }

    private static long virtToPhys(long va, long cr3) {
        long pml4eIdx = (va >>> 39) & 0x1FFL;
        long pdpeIdx  = (va >>> 30) & 0x1FFL;
        long pdeIdx   = (va >>> 21) & 0x1FFL;
        long pteIdx   = (va >>> 12) & 0x1FFL;
        long offset   = va & 0xFFFL;
    
        long pml4e = kapi.kread64(physToDmap(cr3) + pml4eIdx * 8L);
        if ((pml4e & 1) == 0) return 0;
    
        long pdpe = kapi.kread64(physToDmap(pml4e & CPU_PHYS_MASK) + pdpeIdx * 8L);
        if ((pdpe & 1) == 0) return 0;
        if ((pdpe & (1L << 7)) != 0) return (pdpe & 0x000FFFFFC0000000L) + (va & 0x3FFFFFFFL);
    
        long pde = kapi.kread64(physToDmap(pdpe & CPU_PHYS_MASK) + pdeIdx * 8L);
        if ((pde & 1) == 0) return 0;
        if ((pde & (1L << 7)) != 0) return (pde & 0x000FFFFFFFE00000L) + (va & 0x1FFFFFL);
    
        long pte = kapi.kread64(physToDmap(pde & CPU_PHYS_MASK) + pteIdx * 8L);
        if ((pte & 1) == 0) return 0;
    
        return (pte & CPU_PHYS_MASK) + offset;
    }

    private static long gpuPdeField(long pde, int shift, long mask) {
        return (pde >>> shift) & mask;
    }

    private static long[] gpuWalkPt(int vmid, long virtAddr) {
        long pdb2Addr   = getPdb2Addr(vmid);
        long pml4eIndex = (virtAddr >>> 39) & 0x1FFL;
        long pdpeIndex  = (virtAddr >>> 30) & 0x1FFL;
        long pdeIndex   = (virtAddr >>> 21) & 0x1FFL;

        long pml4e = kapi.kread64(pdb2Addr + pml4eIndex * 8L);
        if (gpuPdeField(pml4e, GPU_PDE_SHIFT_VALID, GPU_PDE_MASK_VALID) != 1L) return null;

        long pdpeVa = physToDmap(pml4e & GPU_PDE_ADDR_MASK) + pdpeIndex * 8L;
        long pdpe   = kapi.kread64(pdpeVa);
        if (gpuPdeField(pdpe, GPU_PDE_SHIFT_VALID, GPU_PDE_MASK_VALID) != 1L) return null;

        long pdeVa = physToDmap(pdpe & GPU_PDE_ADDR_MASK) + pdeIndex * 8L;
        long pde   = kapi.kread64(pdeVa);
        if (gpuPdeField(pde, GPU_PDE_SHIFT_VALID, GPU_PDE_MASK_VALID) != 1L) return null;

        if (gpuPdeField(pde, GPU_PDE_SHIFT_IS_PTE, GPU_PDE_MASK_IS_PTE) == 1L) {
            return new long[]{pdeVa, 0x200000L};
        }

        long fragmentSize = gpuPdeField(pde, GPU_PDE_SHIFT_BLOCK_FRAG_SIZE,
                GPU_PDE_MASK_BLOCK_FRAG_SIZE);
        long offset   = virtAddr & 0x1FFFFFL;
        long ptBasePa = pde & GPU_PDE_ADDR_MASK;

        long pteVa;
        long pageSize;

        if (fragmentSize == 4L) {
            pteVa = physToDmap(ptBasePa) + (offset >>> 16) * 8L;
            long pte = kapi.kread64(pteVa);

            if (gpuPdeField(pte, GPU_PDE_SHIFT_VALID, GPU_PDE_MASK_VALID) == 1L
                    && gpuPdeField(pte, GPU_PDE_SHIFT_TF, GPU_PDE_MASK_TF) == 1L) {
                pteVa    = physToDmap(ptBasePa) + ((virtAddr & 0xFFFFL) >>> 13) * 8L;
                pageSize = 0x2000L;
            } else {
                pageSize = 0x10000L;
            }
        } else if (fragmentSize == 1L) {
            pteVa    = physToDmap(ptBasePa) + (offset >>> 13) * 8L;
            pageSize = 0x2000L;
        } else {
            return null;
        }

        return new long[]{pteVa, pageSize};
    }

    private static int getCurprocVmid() {
        long vmspace = kapi.kread64(curproc + PROC_VM_SPACE);
        for (int i = 1; i <= 8; i++) {
            int val = kapi.kread32(vmspace + 0x1D4L + i * 4L);
            if (val > 0 && val <= 0x10) return val;
        }
        throw new InternalError("getCurprocVmid: could not determine vmid");
    }

    private static long getCurprocCr3() {
        long vmspace = kapi.kread64(curproc + PROC_VM_SPACE);
        if (vmspace == 0 || (vmspace >>> 48) != 0xFFFFL) return 0;
        for (int i = 1; i <= 6; i++) {
            long val  = kapi.kread64(vmspace + 0x1C8L + i * 8L);
            long diff = val - vmspace;
            if (diff >= 0x2C0L && diff <= 0x2F0L) {
                return kapi.kread64(val + PMAP_CR3);
            }
        }
        return 0;
    }

    private static long getGvmspace(int vmid) {
        return (kdata_base + GVMSPACE) + (long) vmid * SIZEOF_GVMSPACE;
    }

    private static long getPdb2Addr(int vmid) {
        return kapi.kread64(getGvmspace(vmid) + GVMSPACE_PAGE_DIR);
    }

    private static Long getRelativeVa(int vmid, long va) {
        long gvmspace  = getGvmspace(vmid);
        long startAddr = kapi.kread64(gvmspace + GVMSPACE_START_VA);
        long endAddr   = startAddr + kapi.kread64(gvmspace + GVMSPACE_SIZE);
    
        boolean aboveStart = !((va + Long.MIN_VALUE) < (startAddr + Long.MIN_VALUE));
        boolean belowEnd   =   (va + Long.MIN_VALUE) < (endAddr   + Long.MIN_VALUE);
    
        if (aboveStart && belowEnd) {
            return new Long(va - startAddr);
        }
        return null;
    }
    
    private static long[] getPtbEntryOfRelativeVa(long virtAddr) {
        int vmid = getCurprocVmid();
        Long rel = getRelativeVa(vmid, virtAddr);
        if (rel == null) {
            throw new InternalError("Invalid virtual address for vmid " + vmid);
        }
        return gpuWalkPt(vmid, rel.longValue());
    }

    private static long pm4Type3Header(long opcode, long count) {
        return (PM4_SHADER_COMPUTE & 0x1L) << 1
             | (opcode & 0xFFL)            << 8
             | ((count - 1L) & 0x3FFFL)   << 16
             | (PM4_PACKET_TYPE3 & 0x3L)  << 30;
    }

    private static Buffer pm4DmaData(long destVa, long srcVa, int length) {
        final long count   = 6L;
        final int  bufSize = (int) (4L * (count + 1L));

        long dmaDataHeader = (0L & 0x1L) << 0
                           | (0L & 0x1L) << 12
                           | (2L & 0x3L) << 13
                           | (1L & 0x1L) << 15
                           | (0L & 0x3L) << 20
                           | (0L & 0x1L) << 24
                           | (2L & 0x3L) << 25
                           | (1L & 0x1L) << 27
                           | (0L & 0x3L) << 29
                           | (1L & 0x1L) << 31;
        dmaDataHeader &= 0xFFFFFFFFL;

        Buffer pm4 = new Buffer(bufSize);
        pm4.putInt(0x00, (int) pm4Type3Header(PM4_IT_DMA_DATA, count));
        pm4.putInt(0x04, (int) dmaDataHeader);
        pm4.putInt(0x08, (int) (srcVa  & 0xFFFFFFFFL));
        pm4.putInt(0x0C, (int) (srcVa  >>> 32));
        pm4.putInt(0x10, (int) (destVa & 0xFFFFFFFFL));
        pm4.putInt(0x14, (int) (destVa >>> 32));
        pm4.putInt(0x18, (int) ((long) length & 0x1FFFFFL));

        return pm4;
    }

    private static Buffer buildCommandDescriptor(long gpuAddr, int sizeInBytes) {
        Buffer desc = new Buffer(16);
        long sizeInDwords = ((long) sizeInBytes) >>> 2;
        desc.putLong(0x00, ((gpuAddr & 0xFFFFFFFFL) << 32) | 0xC0023F00L);
        desc.putLong(0x08, ((sizeInDwords & 0xFFFFFL) << 32) | ((gpuAddr >>> 32) & 0xFFFFL));
        return desc;
    }

    private static void ioctlSubmitCommands(int pipeId, int cmdCount, Buffer cmdDescriptors) {
        Buffer submitStruct = new Buffer(0x10);
        submitStruct.putInt(0x00, pipeId);
        submitStruct.putInt(0x04, cmdCount);
        submitStruct.putLong(0x08, cmdDescriptors.address());
        
        gpuFd = open("/dev/gc", O_RDWR);
        if (gpuFd < 0) {
            throw new InternalError("Failed to open /dev/gc");
        }
        
        int ret = ioctl(gpuFd, IOCTL_SUBMIT_COMMANDS, submitStruct.address());
        if (ret != 0) {
            throw new InternalError("ioctl submit failed: 0x" + Integer.toHexString(ret));
        }
        
        api.call(usleep, 300000);
        
        close(gpuFd);
        gpuFd = -1;
        
        api.call(usleep, 300000);
        
    }

    private static void submitDmaDataCommand(long destVa, long srcVa, int size) {
        Buffer pm4 = pm4DmaData(destVa, srcVa, size);
        api.memcpy(cmdVa, pm4.address(), pm4.size());

        Buffer desc = buildCommandDescriptor(cmdVa, pm4.size());
        ioctlSubmitCommands(0, 1, desc);        
    }

    private static void transferPhysicalBuffer(long physAddr, int size, boolean isWrite) {
        long truncPhysAddr = physAddr & ~(DMEM_SIZE - 1L);
        long offset        = physAddr - truncPhysAddr;

        if (offset + size > DMEM_SIZE) {
            throw new InternalError("Transfer exceeds direct-memory size");
        }

        if (mprotect(victimVa, DMEM_SIZE, PROT_RO) == -1) {
            throw new InternalError("mprotect(RO) failed");
        }

        kapi.kwrite64(victimPtbeVa, clearedVictimPtbeForRo | truncPhysAddr);

        if (mprotect(victimVa, DMEM_SIZE, PROT_RW) == -1) {
            throw new InternalError("mprotect(RW) failed");
        }

        long src = isWrite ? transferVa        : victimVa + offset;
        long dst = isWrite ? victimVa + offset  : transferVa;

        submitDmaDataCommand(dst, src, size);
    }

    public static Buffer readBuffer(long addr, int size) {
        long physAddr = virtToPhys(addr, kernelCr3);
        if (physAddr == 0) {
            throw new InternalError("readBuffer: failed to translate 0x" + Long.toHexString(addr));
        }
        transferPhysicalBuffer(physAddr, size, false);

        Buffer result = new Buffer(size);
        api.memcpy(result.address(), transferVa, size);
        return result;
    }

    public static void writeBuffer(long addr, Buffer buf) {
        long physAddr = virtToPhys(addr, kernelCr3);
        if (physAddr == 0) {
            throw new InternalError("writeBuffer: failed to translate 0x" + Long.toHexString(addr));
        }
        api.memcpy(transferVa, buf.address(), buf.size());
        transferPhysicalBuffer(physAddr, buf.size(), true);
    }

    public static byte gread8(long kaddr) {
        return readBuffer(kaddr, 1).getByte(0x00);
    }

    public static short gread16(long kaddr) {
        return readBuffer(kaddr, 2).getShort(0x00);
    }

    public static int gread32(long kaddr) {
        return readBuffer(kaddr, 4).getInt(0x00);
    }

    public static long gread64(long kaddr) {
        return readBuffer(kaddr, 8).getLong(0x00);
    }

    public static void gwrite8(long dest, byte value) {
        Buffer buf = new Buffer(1);
        buf.putByte(0x00, value);
        writeBuffer(dest, buf);
    }

    public static void gwrite16(long dest, short value) {
        Buffer buf = new Buffer(2);
        buf.putShort(0x00, value);
        writeBuffer(dest, buf);
    }

    public static void gwrite32(long dest, int value) {
        Buffer buf = new Buffer(4);
        buf.putInt(0x00, value);
        writeBuffer(dest, buf);
    }

    public static void gwrite64(long dest, long value) {
        Buffer buf = new Buffer(8);
        buf.putLong(0x00, value);
        writeBuffer(dest, buf);
    }

    public static boolean run(long kdata_base, long curproc) {
        GPU.kdata_base = kdata_base;
        GPU.curproc    = curproc;

        Status.println("[GPU] Initializing GPU RW");
        
        KERNEL_PMAP_STORE = PS5_KernelOffset.getOffset("KERNEL_PMAP_STORE");
        GVMSPACE          = PS5_KernelOffset.getOffset("GVMSPACE");
        SECURITY_FLAGS    = PS5_KernelOffset.getOffset("SECURITY_FLAGS");

        if (KERNEL_PMAP_STORE == 0 || GVMSPACE == 0 || SECURITY_FLAGS == 0) {
            Status.println("Failed to resolve kernel offsets from PS5_KernelOffset");
            return false;
        }

        long pmapStore = kdata_base + KERNEL_PMAP_STORE;
        long pmPml4    = kapi.kread64(pmapStore + PMAP_PML4);
        long pmCr3     = kapi.kread64(pmapStore + PMAP_CR3);
        if (pmPml4 == 0 || pmCr3 == 0) {
            Status.println("Failed to read KERNEL_PMAP_STORE fields");
            return false;
        }
        dmap      = pmPml4 - pmCr3;
        kernelCr3 = pmCr3;

        try {
            victimVa   = allocMainDmem(DMEM_SIZE, PROT_RW, MAP_NO_COALESCE);
            transferVa = allocMainDmem(DMEM_SIZE, PROT_RW, MAP_NO_COALESCE);
            cmdVa      = allocMainDmem(DMEM_SIZE, PROT_RW, MAP_NO_COALESCE);
        } catch (InternalError e) {
            Status.println("Failed to allocate direct memory: " + e.getMessage());
            return false;
        }

        long[] ptResult = getPtbEntryOfRelativeVa(victimVa);
        if (ptResult == null || ptResult[0] == 0 || ptResult[1] != DMEM_SIZE) {
            Status.println("Failed to locate victim PTE");
            return false;
        }
        victimPtbeVa = ptResult[0];

        long victimRealPa = virtToPhys(victimVa, getCurprocCr3());
        if (victimRealPa == 0) {
            Status.println("Failed to translate victimVa to physical address");
            return false;
        }

        if (mprotect(victimVa, DMEM_SIZE, PROT_RO) == -1) {
            Status.println("mprotect(RO) on victimVa failed");
            return false;
        }

        long initialVictimPtbeForRo = kapi.kread64(victimPtbeVa);
        clearedVictimPtbeForRo      = initialVictimPtbeForRo & ~victimRealPa;

        if (mprotect(victimVa, DMEM_SIZE, PROT_RW) == -1) {
            Status.println("mprotect(RW) restore on victimVa failed");
            return false;
        }
        
        Status.println("[GPU] Patching kdata... ");

        // Set security flags
        Status.println("[GPU] Setting security flags");
        long securityFlagsAddr = kdata_base + SECURITY_FLAGS;
        int securityFlags = kapi.kread32(securityFlagsAddr);
        Status.println("  before: " + toHex32(securityFlags));
        gwrite32(securityFlagsAddr, securityFlags | 0x14);
        Status.println("  after:  " + toHex32(kapi.kread32(securityFlagsAddr)));
        
        // Set targetid to DEX
        Status.println("[GPU] Setting targetid");
        long targetIdFlagsAddr = securityFlagsAddr + 0x09;
        Status.println("  before: " + toHex8(kapi.kread8(targetIdFlagsAddr)));
        gwrite8(targetIdFlagsAddr, (byte) 0x82);
        Status.println("  after:  " + toHex8(kapi.kread8(targetIdFlagsAddr)));
        
        // Set qa flags for debug menu enable
        Status.println("[GPU] Setting qa flags");
        long qaFlagsAddr = securityFlagsAddr + 0x24;
        int qaFlags = kapi.kread32(qaFlagsAddr);
        Status.println("  qa_flags before: " + toHex32(qaFlags));
        gwrite32(qaFlagsAddr, qaFlags | 0x10300);
        Status.println("  qa_flags after:  " + toHex32(kapi.kread32(qaFlagsAddr)));
        
        // Set utoken flags for debug menu enable
        Status.println("[GPU] Setting utoken flags");
        long utokenFlagsAddr = securityFlagsAddr + 0x8C;
        byte utokenFlags = kapi.kread8(utokenFlagsAddr);
        Status.println("  utoken_flags before: " + toHex8(utokenFlags));
        gwrite8(utokenFlagsAddr, (byte) (utokenFlags | 0x01));
        Status.println("  utoken_flags after:  " + toHex8(kapi.kread8(utokenFlagsAddr)));
        
        Status.println("[GPU] Debug menu enabled");
        
        return true;
    }
    
    private static String toHex32(int value) {
        return "0x" + Integer.toHexString(value);
    }
    
    private static String toHex8(byte value) {
        return "0x" + Integer.toHexString(value & 0xFF);
    }    
    
}