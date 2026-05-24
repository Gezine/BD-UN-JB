// https://github.com/ufm42/kexp

package org.bdj.external;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import org.bdj.Status;
import org.bdj.api.*;

public class AioShellcode {

    private static final int PAGE_SIZE       = 0x4000;
    private static final int READ_CHUNK_SIZE = 4096;

    private static final int PROT_READ  = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int PROT_EXEC  = 0x4;
    private static final int PROT_RWX   = PROT_READ | PROT_WRITE | PROT_EXEC;

    private static final int MAP_SHARED = 0x0001;

    private static final String BIN_PATH = "/org/bdj/external/kexp_v2.bin";
    private static final String ELFLDR_PATH = "/org/bdj/external/elfldr_1320_v4.elf";

    private static final API       api;
    private static final KernelAPI kapi;

    private static final long mmap;
    private static final long sceKernelJitCreateSharedMemory;
    private static final long scePthreadCreate;
    private static final long scePthreadJoin;
    private static final long scePthreadAttrInit;
    private static final long scePthreadAttrSetstacksize;
    private static final long scePthreadAttrSetdetachstate;
    private static final long scePthreadAttrDestroy;

    private static byte[] elfldr_data;

    private static long allproc;
    private static long elfldr_addr;
    private static long elfldr_size;

    static {
        try {
            api  = API.getInstance();
            kapi = KernelAPI.getInstance();

            mmap                           = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "mmap");
            sceKernelJitCreateSharedMemory = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "sceKernelJitCreateSharedMemory");
            scePthreadCreate               = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadCreate");
            scePthreadJoin                 = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadJoin");
            scePthreadAttrInit             = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadAttrInit");
            scePthreadAttrSetstacksize     = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadAttrSetstacksize");
            scePthreadAttrSetdetachstate   = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadAttrSetdetachstate");
            scePthreadAttrDestroy          = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "scePthreadAttrDestroy");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void start(long allproc) {
        AioShellcode.allproc = allproc;

        Status.println("=== PS5 AIO JB Shellcode by ufm42 ===");
        
        try {
            loadElfldr();
            loadEmbeddedBin();
        } catch (Exception e) {
            throw new RuntimeException("PS5 AIO JB Shellcode failed");
        }
        
    }

    private static long mapShellcode(byte[] binData) throws Exception {
        int alignedSize = (binData.length + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
        if (alignedSize == 0) alignedSize = PAGE_SIZE;

        Int32 fdBuf = new Int32();
        long  ret   = api.call(sceKernelJitCreateSharedMemory, 0L, (long) alignedSize, 7L, fdBuf.address());
        int   execFd = fdBuf.get();
        if (ret != 0 || execFd <= 0) {
            throw new RuntimeException(
                "sceKernelJitCreateSharedMemory failed: ret=" + String.valueOf(ret) +
                " fd=" + String.valueOf(execFd));
        }

        long entryAddr = api.call(mmap,
            0L, (long) alignedSize, (long) PROT_RWX,
            (long) MAP_SHARED, (long) execFd, 0L);
        if (entryAddr == -1L || entryAddr == 0L) {
            throw new RuntimeException(
                "mmap failed for shellcode (size=0x" + Integer.toHexString(alignedSize) + ")");
        }

        api.memcpy(entryAddr, binData, binData.length);

        Status.println("Shellcode mapped @ 0x" + Long.toHexString(entryAddr) +
                       " (size: 0x" + Integer.toHexString(alignedSize) + ")");
        return entryAddr;
    }
    
    private static void runShellcode(long entryAddr) throws Exception {
        Int32Array masterPipeFd = kapi.getMasterPipeFd();
        Int32Array victimPipeFd = kapi.getVictimPipeFd();

        Buffer args      = new Buffer(0x28);
        Buffer attr      = new Buffer(0x100);
        Int64  thrHandle = new Int64();
        Text   name      = new Text("kexp");

        args.putInt (0x00, masterPipeFd.get(0));
        args.putInt (0x04, masterPipeFd.get(1));
        args.putInt (0x08, victimPipeFd.get(0));
        args.putInt (0x0C, victimPipeFd.get(1));
        args.putLong(0x10, allproc);
        args.putLong(0x18, elfldr_addr);
        args.putLong(0x20, elfldr_size);

        Status.println("Spawning shellcode thread at: 0x" + Long.toHexString(entryAddr));

        api.call(scePthreadAttrInit,           attr.address());
        api.call(scePthreadAttrSetstacksize,   attr.address(), 0x80000L);
        api.call(scePthreadAttrSetdetachstate, attr.address(), 0L);

        long ret = api.call(scePthreadCreate,
            thrHandle.address(), attr.address(),
            entryAddr, args.address(), name.address());
        api.call(scePthreadAttrDestroy, attr.address());

        if (ret != 0) {
            throw new RuntimeException("scePthreadCreate failed: " + String.valueOf(ret));
        }

        long handle = thrHandle.get();
        Status.println("Shellcode thread spawned, handle: 0x" + Long.toHexString(handle));

        Int64 retVal = new Int64();
        ret = api.call(scePthreadJoin, handle, retVal.address());
        if (ret != 0) {
            throw new RuntimeException("scePthreadJoin failed: " + String.valueOf(ret));
        }

        Status.println("Shellcode returned: 0x" + Long.toHexString(retVal.get()));
    }
    
    private static void loadEmbeddedBin() {
        InputStream binStream = AioShellcode.class.getResourceAsStream(BIN_PATH);
        if (binStream == null) {
            Status.println("Embedded bin not found: " + BIN_PATH);
            throw new RuntimeException("Embedded bin not found: " + BIN_PATH);
        }
        try {
            ByteArrayOutputStream buf   = new ByteArrayOutputStream();
            byte[]                chunk = new byte[READ_CHUNK_SIZE];
            int n;
            while ((n = binStream.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            byte[] binData = buf.toByteArray();
            buf.close();

            Status.println("Embedded bin size: " + String.valueOf(binData.length) +
                           " bytes (0x" + Integer.toHexString(binData.length) + ")");

            long entryAddr = mapShellcode(binData);
            runShellcode(entryAddr);

            Status.println("=== Shellcode done ===");

        } catch (Exception e) {
            Status.printStackTrace("Failed to run embedded bin: ", e);
        } finally {
            try { binStream.close(); } catch (IOException ignored) {}
        }
    }
    
    private static void loadElfldr() throws Exception {
        InputStream stream = AioShellcode.class.getResourceAsStream(ELFLDR_PATH);
        if (stream == null) {
            Status.println("Embedded elfldr not found: " + ELFLDR_PATH);
            throw new RuntimeException("Embedded elfldr not found: " + ELFLDR_PATH);
        }
    
        try {
            ByteArrayOutputStream buf   = new ByteArrayOutputStream();
            byte[]                chunk = new byte[READ_CHUNK_SIZE];
            int n;
            while ((n = stream.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            elfldr_data = buf.toByteArray();
            buf.close();
            
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    
        elfldr_addr = api.addrof(elfldr_data) + 0x18;
        elfldr_size = (long) elfldr_data.length;
    
        Status.println("elfldr @ 0x" + Long.toHexString(elfldr_addr) +
                    " size: 0x" + Integer.toHexString(elfldr_data.length));

    }

}