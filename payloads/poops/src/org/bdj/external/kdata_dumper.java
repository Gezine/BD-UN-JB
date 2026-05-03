// https://github.com/shahrilnet/remote_lua_loader/blob/main/payloads/kdata_dumper.lua

package org.bdj.external;

import org.bdj.api.*;
import org.bdj.Status;

public class kdata_dumper {

    private static final API api;
    private static final KernelAPI kapi = KernelAPI.getInstance();

    private static String IP;
    private static int PORT = 5656;

    private static final int AF_INET     = 2;
    private static final int SOCK_STREAM = 1;

    private static long socket;
    private static long connect;
    private static long write;
    private static long close;

    static {
        try {
            api = API.getInstance();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean setup() {
        socket  = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "socket");
        connect = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "connect");
        write   = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "write");
        close   = api.dlsym(API.LIBKERNEL_MODULE_HANDLE, "close");

        if (socket == 0 || connect == 0 || write == 0 || close == 0) {
            Status.println("Failed to resolve symbols");
            return false;
        }

        return true;
    }

    private static int socket(int domain, int type, int protocol) {
        return (int) api.call(socket, domain, type, protocol);
    }

    private static int connect(int fd, Buffer sockaddr, int socklen) {
        return (int) api.call(connect, fd, sockaddr.address(), socklen);
    }

    private static long write(int fd, Buffer buf, long nbytes) {
        return api.call(write, fd, buf != null ? buf.address() : 0, nbytes);
    }

    private static int close(int fd) {
        return (int) api.call(close, fd);
    }

    private static short htons(int port) {
        return (short) (((port & 0x00FF) << 8) | ((port & 0xFF00) >>> 8));
    }

    private static int aton(String ip) {
        int i0 = ip.indexOf('.');
        int i1 = ip.indexOf('.', i0 + 1);
        int i2 = ip.indexOf('.', i1 + 1);
        int a = Integer.parseInt(ip.substring(0, i0));
        int b = Integer.parseInt(ip.substring(i0 + 1, i1));
        int c = Integer.parseInt(ip.substring(i1 + 1, i2));
        int d = Integer.parseInt(ip.substring(i2 + 1));
        return (d << 24) | (c << 16) | (b << 8) | a;
    }

    private static long findKdataBase(long addrInsideKdata) {
        Status.println("start searching for kdata base...");

        long addr = addrInsideKdata & ~((long) KernelAPI.PAGE_SIZE - 1);

        long offset = 0;
        while (true) {
            long candidate = addr - offset;

            int n1 = kapi.kread32(candidate);
            int n2 = kapi.kread32(candidate + 4);
            int n3 = kapi.kread32(candidate + 8);
            int n4 = kapi.kread32(candidate + 12);
            
            if (n1 == 1 && n2 == 1 && n3 == 0 && n4 == 0) {
                return candidate;
            }

            offset += KernelAPI.PAGE_SIZE;
        }
    }
    
    private static void dumpKdataOverNetwork(long kdataBase) {
        int sockFd = socket(AF_INET, SOCK_STREAM, 0);
        if (sockFd == -1) {
            Status.println("socket() failed");
            return;
        }

        Buffer sockaddrIn = new Buffer(16);
        sockaddrIn.putByte(0x01, (byte) AF_INET);
        sockaddrIn.putShort(0x02, htons(PORT));
        sockaddrIn.putInt(0x04, aton(IP));

        Status.println("trying to connect to " + IP + ":" + String.valueOf(PORT));

        if (connect(sockFd, sockaddrIn, 16) == -1) {
            Status.println("connect() failed");
            close(sockFd);
            return;
        }

        Status.println("connected successfully");
        Status.println("Sending kdata dump...");

        final int readSize = KernelAPI.PAGE_SIZE;
        final int MB       = 0x100000;

        Buffer mem = new Buffer(readSize);

        long offset = 0;
        while (true) {
            kapi.kread(mem, kdataBase + offset, readSize);

            if (write(sockFd, mem, readSize) == -1) {
                Status.println("server closed connection");
                break;
            }

            if (offset % (5L * MB) == 0) {
                Status.println("dumping kernel data: " + String.valueOf(offset / MB) + " mb");
            }

            offset += readSize;
        }

        close(sockFd);
    }

    public static void kdata_dumper(long kdata_addr, String ip, int port) {
        if (ip == null) {
            Status.println("IP cannot be null");
            return;
        }

        IP = ip;
        PORT = port;

        if (!setup()) {
            Status.println("setup failed");
            return;
        }

        long kdataBase = findKdataBase(kdata_addr);
        Status.println("kdata base: 0x" + Long.toHexString(kdataBase));

        dumpKdataOverNetwork(kdataBase);
    }
}