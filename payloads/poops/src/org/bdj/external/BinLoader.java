package org.bdj.external;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import org.bdj.Status;
import org.bdj.api.*;

public class BinLoader {
    // Memory mapping constants
    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int PROT_EXEC = 0x4;
    private static final int MAP_PRIVATE = 0x2;
    private static final int MAP_ANONYMOUS = 0x1000;
    
    // ELF constants
    private static final int ELF_MAGIC = 0x464c457f; // 0x7F 'E' 'L' 'F' in little endian
    private static final int PT_LOAD = 1;
    private static final int PAGE_SIZE = 0x1000;
    private static final int MAX_PAYLOAD_SIZE = 4 * 1024 * 1024; // 4MB
    
    // Network constants
    private static final int NETWORK_PORT = 9020;
    private static final int READ_CHUNK_SIZE = 4096;
    
    private static API api;
    private static byte[] binData;
    private static long mmapBase;
    private static long mmapSize;
    private static long entryPoint;
    private static Thread payloadThread;

    private static long mmap;
    private static long munmap;

    static {
        try {
            api = API.getInstance();
            mmap = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "mmap");
            munmap = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "munmap");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }        

    public static void start() {
        Status.println("=== BinLoader Starting ===");
        listenForPayloadsOnPort(NETWORK_PORT);
    }
    
    private static void executeEmbeddedPayload(String path) {
        try {
            byte[] embeddedData = loadResourcePayload(path);
            
            loadFromData(embeddedData);
            run();
            waitForPayloadToExit();

        } catch (Exception e) {
            Status.printStackTrace("Error executing embedded payload: ", e);
        }
    }
    
    private static byte[] loadResourcePayload(String resourcePath) throws Exception {
        InputStream inputStream = BinLoader.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new RuntimeException("Resource not found: " + resourcePath);
        }
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_CHUNK_SIZE];
        int bytesRead;
        int totalRead = 0;
        
        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                // Safety check to prevent excessive resource loading
                if (totalRead > MAX_PAYLOAD_SIZE) {
                    throw new RuntimeException("Resource payload exceeds maximum size: " + MAX_PAYLOAD_SIZE);
                }
            }
            
            return outputStream.toByteArray();
            
        } finally {
            inputStream.close();
            outputStream.close();
        }
    }
    
    public static void loadFromData(byte[] data) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("Payload data cannot be null");
        }
        
        if (data.length == 0) {
            throw new IllegalArgumentException("Payload data cannot be empty");
        }
        
        if (data.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload too large: " + data.length + " bytes (max: " + MAX_PAYLOAD_SIZE + ")");
        }
        
        binData = data;
        
        // Round up to page boundary with overflow check
        long mmapSizeCalc;
        try {
            mmapSizeCalc = roundUp(data.length, PAGE_SIZE);
            if (mmapSizeCalc <= 0 || mmapSizeCalc > MAX_PAYLOAD_SIZE * 2) {
                throw new RuntimeException("Invalid mmap size calculation: " + mmapSizeCalc);
            }
        } catch (ArithmeticException e) {
            throw new RuntimeException("Integer overflow in mmap size calculation");
        }
        
        // Allocate executable memory
        int protFlags = PROT_READ | PROT_WRITE | PROT_EXEC;
        int mapFlags = MAP_PRIVATE | MAP_ANONYMOUS;
        
        long ret = api.call(mmap, 0L, mmapSizeCalc, (long)protFlags, (long)mapFlags, -1L, 0L);
        if (ret < 0) {
            int errno = api.errno();
            throw new RuntimeException("mmap() failed with error: " + ret + " (errno: " + errno + ")");
        }
        
        // Validate mmap returned a reasonable address
        if (ret == 0 || ret == -1) {
            throw new RuntimeException("mmap() returned invalid address: 0x" + Long.toHexString(ret));
        }
        
        mmapBase = ret;
        mmapSize = mmapSizeCalc;
        
        Status.println("mmap() allocated at: 0x" + Long.toHexString(mmapBase) + " (size: 0x" + Long.toHexString(mmapSize) + ")");
        
        try {
            // Check if ELF by reading magic bytes
            if (data.length >= 4) {
                int magic = ((data[3] & 0xFF) << 24) | ((data[2] & 0xFF) << 16) | 
                           ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
                
                if (magic == ELF_MAGIC) {
                    Status.println("Detected ELF payload, parsing headers...");
                    entryPoint = loadElfSegments(data);
                } else {
                    Status.println("Non-ELF payload, treating as raw shellcode");
                    // Copy raw data to allocated memory with bounds checking
                    if (data.length > mmapSize) {
                        throw new RuntimeException("Payload size exceeds allocated memory");
                    }
                    api.memcpy(mmapBase, data, data.length);
                    entryPoint = mmapBase;
                }
            } else {
                throw new RuntimeException("Payload too small (< 4 bytes)");
            }
            
            // Validate entry point
            if (entryPoint == 0) {
                throw new RuntimeException("Invalid entry point: 0x0");
            }
            if (entryPoint < mmapBase || entryPoint >= mmapBase + mmapSize) {
                throw new RuntimeException("Entry point outside allocated memory range: 0x" + Long.toHexString(entryPoint));
            }
            
            Status.println("Entry point: 0x" + Long.toHexString(entryPoint));
            
        } catch (Exception e) {
            // Cleanup on failure
            Status.println("Cleaning up allocated memory due to error: " + e.getMessage());
            long munmapResult = api.call(munmap, mmapBase, mmapSize);
            if (munmapResult < 0) {
                Status.println("Warning: munmap() failed during cleanup: " + munmapResult);
            }
            mmapBase = 0;
            mmapSize = 0;
            entryPoint = 0;
            throw e;
        }
    }
    
    private static long loadElfSegments(byte[] data) throws Exception {
        // Create temporary buffer for ELF parsing to avoid header corruption
        long tempBuf = api.call(mmap, 0L, (long)data.length,
                                      (long)(PROT_READ | PROT_WRITE), (long)(MAP_PRIVATE | MAP_ANONYMOUS), -1L, 0L);
        if (tempBuf < 0) {
            throw new RuntimeException("Failed to allocate temp buffer for ELF parsing");
        }
        
        try {
            // Copy data to temp buffer for parsing
            api.memcpy(tempBuf, data, data.length);
            
            // Read ELF header from temp buffer
            ElfHeader elfHeader = readElfHeader(tempBuf);
            
            // Load program segments directly to final locations
            for (int i = 0; i < elfHeader.phNum; i++) {
                long phdrAddr = tempBuf + elfHeader.phOff + (i * elfHeader.phEntSize);
                ProgramHeader phdr = readProgramHeader(phdrAddr);
                
                if (phdr.type == PT_LOAD && phdr.memSize > 0) {
                    // Calculate segment address (use relative offset)
                    long segAddr = mmapBase + (phdr.vAddr % 0x1000000);
                    
                    // Copy segment data from original data array
                    if (phdr.fileSize > 0) {
                        byte[] segmentData = new byte[(int)phdr.fileSize];
                        System.arraycopy(data, (int)phdr.offset, segmentData, 0, (int)phdr.fileSize);
                        api.memcpy(segAddr, segmentData, segmentData.length);
                    }
                    
                    // Zero out BSS section
                    if (phdr.memSize > phdr.fileSize) {
                        api.memset(segAddr + phdr.fileSize, 0, phdr.memSize - phdr.fileSize);
                    }
                }
            }
            
            return mmapBase + (elfHeader.entry % 0x1000000);
            
        } finally {
            // Clean up temp buffer
            api.call(munmap, tempBuf, (long)data.length);
        }
    }
    
    public static void run() throws Exception {
        // Create Java thread to execute the payload
        payloadThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Status.println("Executing payload at entry point: 0x" + Long.toHexString(entryPoint));
                    
                    // Call the entry point function
                    long result = api.call(entryPoint);
                    
                    Status.println("Payload execution completed with result: " + result);
                } catch (Exception e) {
                    Status.printStackTrace("Payload execution error: ", e);
                }
            }
        });
        
        payloadThread.setName("BinPayload");
        payloadThread.start();
        
        Status.println("Payload thread started successfully");
    }
    
    public static void waitForPayloadToExit() throws Exception {
        if (payloadThread != null) {
            Status.println("Waiting for payload thread to complete...");
            try {
                payloadThread.join(); // Wait for thread to finish
                Status.println("Payload thread completed");
            } catch (InterruptedException e) {
                Status.printStackTrace("Thread wait interrupted: ", e);
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        }
        
        // Cleanup allocated memory with validation
        if (mmapBase != 0 && mmapSize > 0) {
            Status.println("Cleaning up payload memory at 0x" + Long.toHexString(mmapBase) + " (size: 0x" + Long.toHexString(mmapSize) + ")");
            
            try {
                long ret = api.call(munmap, mmapBase, mmapSize);
                if (ret < 0) {
                    int errno = api.errno();
                    Status.println("Warning: munmap() failed: " + ret + " (errno: " + errno + ")");
                } else {
                    Status.println("Successfully unmapped memory");
                }
            } catch (Exception e) {
                Status.println("Exception during munmap(): " + e.getMessage());
            }
            
            // Clear variables to prevent reuse
            mmapBase = 0;
            mmapSize = 0;
            entryPoint = 0;
            binData = null;
        } else {
            Status.println("No memory to cleanup (mmapBase=0x" + Long.toHexString(mmapBase) + ", mmapSize=" + mmapSize + ")");
        }
        
        // Clear thread reference
        payloadThread = null;
        
        Status.println("Payload execution completed and cleaned up");
    }
    
    private static class ElfHeader {
        long entry;
        long phOff;
        int phEntSize;
        int phNum;
    }
    
    private static class ProgramHeader {
        int type;
        long offset;
        long vAddr;
        long fileSize;
        long memSize;
    }
    
    private static ElfHeader readElfHeader(long addr) {
        ElfHeader header = new ElfHeader();
        header.entry = api.read64(addr + 0x18);
        header.phOff = api.read64(addr + 0x20);
        header.phEntSize = api.read16(addr + 0x36) & 0xFFFF;
        header.phNum = api.read16(addr + 0x38) & 0xFFFF;
        return header;
    }
    
    private static ProgramHeader readProgramHeader(long addr) {
        ProgramHeader phdr = new ProgramHeader();
        phdr.type = api.read32(addr + 0x00);
        phdr.offset = api.read64(addr + 0x08);
        phdr.vAddr = api.read64(addr + 0x10);
        phdr.fileSize = api.read64(addr + 0x20);
        phdr.memSize = api.read64(addr + 0x28);
        return phdr;
    }
    
    private static long roundUp(long value, long boundary) {
        if (value < 0 || boundary <= 0) {
            throw new IllegalArgumentException("Invalid arguments: value=" + value + ", boundary=" + boundary);
        }
        
        // Check for potential overflow
        if (value > Long.MAX_VALUE - boundary) {
            throw new ArithmeticException("Integer overflow in roundUp calculation");
        }
        
        return ((value + boundary - 1) / boundary) * boundary;
    }
    
    public static void listenForPayloadsOnPort(int port) {
        ServerSocket serverSocket = null;
        
        try {
            serverSocket = new ServerSocket(port);
            Status.println("BinLoader listening on port " + port);
            
            // Keep listening for connections indefinitely
            while (true) {
                Socket clientSocket = null;
                
                try {
                    clientSocket = serverSocket.accept();
                    Status.println("Accepted new connection from: " + clientSocket.getInetAddress());
                    
                    // Read payload data from client
                    byte[] payloadData = readPayloadFromSocket(clientSocket);
                    
                    Status.println("Received payload with size " + payloadData.length + " bytes (0x" + 
                                 Integer.toHexString(payloadData.length) + ")");
                    
                    // Load and execute payload
                    loadFromData(payloadData);
                    run();
                    waitForPayloadToExit();
                    
                    Status.println("Payload execution completed successfully");
                    Status.println("BinLoader listening on port " + port);
                    
                } catch (Exception e) {
                    Status.printStackTrace("Error processing payload: ", e);
                } finally {
                    // Close client socket
                    if (clientSocket != null) {
                        try {
                            clientSocket.close();
                        } catch (IOException e) {
                            Status.printStackTrace("Error closing client socket: ", e);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            Status.printStackTrace("Network server error: ", e);
        } finally {
            // Close server socket
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    Status.printStackTrace("Error closing server socket: ", e);
                }
            }
        }
    }
    
    private static byte[] readPayloadFromSocket(Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_CHUNK_SIZE];
        int bytesRead;
        
        // Read data until connection closes or no more data
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
            
            // Check if we've exceeded max payload size
            if (outputStream.size() > MAX_PAYLOAD_SIZE) {
                throw new IOException("Payload too large: " + outputStream.size() + " bytes");
            }
        }
        
        byte[] payloadData = outputStream.toByteArray();
        outputStream.close();
        
        if (payloadData.length == 0) {
            throw new IOException("No payload data received");
        }
        
        return payloadData;
    }

}
