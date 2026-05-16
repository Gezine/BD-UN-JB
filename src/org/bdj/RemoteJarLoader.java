package org.bdj;

import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

public class RemoteJarLoader implements Runnable {
    private static volatile boolean shouldStop = false;
    private ServerSocket server; 
    public void run() {
        try {
            server = new ServerSocket(9025);
            server.setSoTimeout(100); // wake the server up intermittenly to check if the Loader should be stopped
            Status.println("Remote JAR Loader listening on port 9025...");
            
            while (!shouldStop) {
                try {
                    Socket client = server.accept();
                    if (shouldStop) {
                        client.close();
                        break;
                    }
                    
                    try {
                        loadAndRunJar(client);
                    } catch (Exception e) {
                        Status.printStackTrace("Error processing JAR", e);
                    }
                    
                    client.close();
                    Status.println("Waiting for next JAR on port 9025...");
                } catch (SocketTimeoutException e) {
                    // No client connected during the timeout window.
                }
                
            }
        } catch (IOException e) {
            Status.printStackTrace("Server error", e);
        }
    }

    public void stop()
    {
        this.shouldStop = true;
    }
    
    private static void loadAndRunJar(Socket client) throws Exception {
        String jarPath = "/download0/received.jar";
        
        InputStream inputStream = client.getInputStream();
        
        OutputStream outputStream = new FileOutputStream(jarPath);
        
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        
        Status.println("Receiving JAR...");
        while ((read = inputStream.read(buf)) > 0) {
            outputStream.write(buf, 0, read);
            total += read;
        }
        
        outputStream.close();
        inputStream.close();
        Status.println("JAR received: " + total + " bytes total");
        
        runJar(new File(jarPath));
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
        
        ClassLoader parentLoader = RemoteJarLoader.class.getClassLoader();
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
}