package org.bdj.external;

import org.bdj.Status;
import java.util.Hashtable;

public class PS5_KernelOffset {

    public static final int FILEDESCENT_SIZE = 0x30;
    public static final int KQ_FDP_OFFSET = 0xA8;
    public static final int PIPE_SIGIO_OFFSET = 0xd8;
    public static final int IN6P_OUTPUTOPTS_OFFSET = 0x120;
    public static final int IP6PO_RHI_RTHDR_OFFSET = 0x70;
    public static final int ROOTVNODE_OFFSET = 0x8;
    public static final int FDT_OFILES_OFFSET = 0x8;
    public static final int P_PID_OFFSET = 0xbc;
    
    private static Hashtable KernelOffsets;
    public static String FW_VERSION;
    
    static {
        initializeOffsets();
    }

    private static void initializeOffsets() {
        KernelOffsets = new Hashtable();
        
        // ALLPROC, SECURITY_FLAGS, ROOTVNODE, KERNEL_PMAP_STORE, GVMSPACE
        addFirmwareOffsets("10.00", 0x2765D70, 0xD79064, 0x2FA3510, 0x2CF0EF8, 0x2D52570);
        addFirmwareOffsets("11.00", 0x2875D70, 0xD8C064, 0x30B7510, 0x2E04F18, 0x2E66570);
        addFirmwareOffsets("12.00", 0x2885E00, 0xD83064, 0x30D7510, 0x2E1CFB8, 0x2E7E570);

        // 13.00 is untested
        addFirmwareOffsets("13.00", 0x28C5E00, 0xD99064, 0x3133510, 0x2E74FF8, 0x2ED6570);

        addFirmwareOffsets("13.20", 0x28C5E00, 0xD99064, 0x3133510, 0x2E74FF8, 0x2ED6570);
    }
    
    private static void addFirmwareOffsets(String fw, int ALLPROC, int SECURITY_FLAGS, int ROOTVNODE, int KERNEL_PMAP_STORE, int GVMSPACE) {
        Hashtable offsets = new Hashtable();
        offsets.put("ALLPROC",           new Long(ALLPROC));
        offsets.put("SECURITY_FLAGS",    new Long(SECURITY_FLAGS));
        offsets.put("ROOTVNODE",         new Long(ROOTVNODE));
        offsets.put("KERNEL_PMAP_STORE", new Long(KERNEL_PMAP_STORE));
        offsets.put("GVMSPACE",          new Long(GVMSPACE));
        KernelOffsets.put(fw, offsets);
    }
    
    public static long getOffset(String key) {
        Hashtable offsets;
        
        if (KernelOffsets.containsKey(FW_VERSION)) {
            offsets = (Hashtable) KernelOffsets.get(FW_VERSION);
        } else {
            String prefix = FW_VERSION.contains(".") ? FW_VERSION.substring(0, FW_VERSION.indexOf('.') + 1) : FW_VERSION;
            offsets = (Hashtable) KernelOffsets.get(prefix);
        }
        
        if (offsets == null) {
            throw new RuntimeException("No offsets available for firmware " + FW_VERSION);
        }
        Long offset = (Long) offsets.get(key);
        if (offset == null) {
            throw new RuntimeException("Offset " + offset + " not found for firmware " + FW_VERSION);
        }
        return offset.longValue();
    }
    
}