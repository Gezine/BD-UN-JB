package org.bdj;

import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

public class InternalJarLoader implements Runnable {
    
    public InternalJarLoader() {
        cleanupOldTempFiles();
    }
    
    public void run() {
        try {
            runJar(new File("/disc/poops_1.4.jar"));
        } catch (IOException e) {
            Status.printStackTrace("JarLoader error", e);
        } catch (Exception e) {
            Status.printStackTrace("JarLoader error", e);
        }
    }

    private static void runJar(File jarFile) throws Exception {
        // Read the manifest to find the main class
        JarFile jar = new JarFile(jarFile);
        Manifest manifest = jar.getManifest();
        jar.close();
        
        if (manifest == null) {
            throw new Exception("No manifest found in JAR");
        }
        
        String mainClassName = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        if (mainClassName == null) {
            throw new Exception("No Main-Class specified in manifest");
        }
        
        ClassLoader parentLoader = InternalJarLoader.class.getClassLoader();
        ClassLoader bypassRestrictionsLoader = new URLClassLoader(new URL[0], parentLoader) {
            protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.nio") || name.startsWith("javax.security.auth") || name.startsWith("javax.net.ssl")) {
                    return findSystemClass(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        
        URL jarUrl = jarFile.toURL();
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, bypassRestrictionsLoader);
        
        Class mainClass = classLoader.loadClass(mainClassName);
        
        Method mainMethod = mainClass.getMethod("main", new Class[]{String[].class});
        
        Status.println("Running " + mainClassName + "...");
        mainMethod.invoke(null, new Object[]{new String[0]});
        
        Status.println(mainClassName + " execution completed");
    }

    private void cleanupOldTempFiles() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempFolder = new File(tempDir);
            File[] files = tempFolder.listFiles();
            
            if (files != null) {
                int cleanedCount = 0;
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (file.getName().startsWith("received") && file.getName().endsWith(".jar")) {
                        if (file.delete()) {
                            cleanedCount++;
                        }
                    }
                }
                if (cleanedCount > 0) {
                    Status.println("Cleaned up " + cleanedCount + " old temp JAR files");
                }
            }
        } catch (Exception e) {
            Status.println("Warning: Could not clean temp files: " + e.getMessage());
        }
    }
}