/*
 * Copyright (C) 2021-2024 Andy Nguyen
 *
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */

package org.bdj.api;

public final class API {
  public static final int RTLD_DEFAULT = -2;

  public static final int LIBC_MODULE_HANDLE = 0x2;
  public static final int LIBKERNEL_MODULE_HANDLE = 0x2001;
  public static final int LIBJAVA_MODULE_HANDLE = 0x4A;

  private static API instance;

  private API() throws Exception {
    this.init();
  }

  public static synchronized API getInstance() throws Exception {
    if (instance == null) {
      instance = new API();
    }
    return instance;
  }

  private void init() throws Exception {

  }

  public long call(long func, long arg0, long arg1, long arg2, long arg3, long arg4, long arg5) {
      return 0L;
  }
  
  public long call(long func, long arg0, long arg1, long arg2, long arg3, long arg4) {
    return 0L;
  }

  public long call(long func, long arg0, long arg1, long arg2, long arg3) {
    return 0L;
  }

  public long call(long func, long arg0, long arg1, long arg2) {
    return 0L;
  }

  public long call(long func, long arg0, long arg1) {
    return 0L;
  }

  public long call(long func, long arg0) {
    return 0L;
  }

  public long call(long func) {
    return 0L;
  }

  public int errno() {
    return 0;
  }

  public long dlsym(long handle, String symbol) {
    return 0L;
  }

  public long addrof(Object obj) {
    return 0L;
  }

  public byte read8(long addr) {
    return 0;
  }

  public short read16(long addr) {
    return 0;
  }

  public int read32(long addr) {
    return 0;
  }

  public long read64(long addr) {
    return 0L;
  }

  public void write8(long addr, byte val) {
  }

  public void write16(long addr, short val) {
  }

  public void write32(long addr, int val) {
  }

  public void write64(long addr, long val) {
  }

  public long malloc(long size) {
    return 0L;
  }

  public long calloc(long number, long size) {
    return 0L;
  }

  public long realloc(long ptr, long size) {
    return 0L;
  }

  public void free(long ptr) {
  }

  public long memcpy(long dest, long src, long n) {
    return 0L;
  }

  public long memcpy(long dest, byte[] src, long n) {
    return 0L;
  }

  public byte[] memcpy(byte[] dest, long src, long n) {
    return dest;
  }

  public long memset(long s, int c, long n) {
    return 0L;
  }

  public byte[] memset(byte[] s, int c, long n) {
    return s;
  }

  public int memcmp(long s1, long s2, long n) {
    return 0;
  }

  public int memcmp(long s1, byte[] s2, long n) {
    return 0;
  }

  public int memcmp(byte[] s1, long s2, long n) {
    return 0;
  }

  public int strcmp(long s1, long s2) {
    return 0;
  }

  public int strcmp(long s1, String s2) {
    return 0;
  }

  public int strcmp(String s1, long s2) {
    return 0;
  }

  public long strcpy(long dest, long src) {
    return 0L;
  }

  public long strcpy(long dest, String src) {
    return 0L;
  }

  public String readString(long src, long n) {
    return "";
  }

  public String readString(long src) {
    return "";
  }

  public byte[] toCBytes(String str) {
    return new byte[0];
  }
}