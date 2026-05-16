package org.bdj;

import java.io.*;
import java.net.*;
import java.lang.*;
import java.lang.reflect.*;

import java.awt.BorderLayout;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;

import org.bdj.sandbox.Exploit;
import org.bdj.sandbox.ExploitInternal;

public class InitXlet implements Xlet {
    private HScene scene;
    private Screen screen;
    private RemoteJarLoader jarLoader;
    private Thread jarLoaderThread;
    private InternalJarLoader internalJarLoader;
    private Thread internalJarLoaderThread;
    private final String jarLoaderThreadName = "JarLoader";
    private final KeyListener[] listeners = new KeyListener[1];
    private final String poopsVersion = "1.4";
    private volatile boolean loadInternalPoop = false;
    
    public void initXlet(XletContext context) {
        
        Status.println("BD-J init");
        Status.setScreenOutputEnabled(true);
        Status.setNetworkLoggerEnabled(false);

        screen = Screen.getInstance();
        screen.setSize(1920, 1080);

        scene = HSceneFactory.getInstance().getDefaultHScene();
        scene.add(screen, BorderLayout.CENTER);
        scene.validate();
    }
    
    public void startXlet() {
        screen.setVisible(true);
        scene.setVisible(true);
        
        Status.println("Screen initialized");
        
        try {
            Status.println("Triggering sandbox escape exploit...");
            
            if (!Exploit.disableSecurityManager()) {
                ExploitInternal.disableSecurityManager();
            }

            Status.println(System.getSecurityManager() == null
                ? "Exploit success - sandbox escape achieved"
                : "Exploit failed - sandbox still active");

        } catch (Exception e) {
            Status.printStackTrace("Error when disabling sandbox: ", e);
        }
        
        // Add sanity check
        if (System.getSecurityManager() == null) {
            Status.println("Press [X] to load the internal poops " + poopsVersion);
            try {
                jarLoader = new RemoteJarLoader();
                jarLoaderThread = new Thread(jarLoader, jarLoaderThreadName);
                listeners[0] = new KeyListener() {
                    public void keyPressed(KeyEvent e) {
                        int keyCode = e.getKeyCode();
                        // Press [X] to disrupt the remote loader
                        if (keyCode == KeyEvent.VK_ENTER) {
                            jarLoader.stop();
                            jarLoaderThread.interrupt();
                            scene.removeKeyListener(listeners[0]);
                            loadInternalPoop = true;
                        }
                    }
                    public void keyReleased(KeyEvent e) {}
                    public void keyTyped(KeyEvent e) {}
                };
                scene.addKeyListener(listeners[0]);
                scene.requestFocus();
                jarLoaderThread.start();
                jarLoaderThread.join();
                Status.println("Remote JAR Loader stopped");
            } catch (Throwable e) {
                Status.printStackTrace("Loader startup failed", e);
            }

            if (loadInternalPoop) {
                Status.println("Loading internal poops");
                try {
                    internalJarLoader = new InternalJarLoader();
                    internalJarLoaderThread = new Thread(internalJarLoader, jarLoaderThreadName);
                    internalJarLoaderThread.start();
                } catch (Throwable e) {
                    Status.printStackTrace("Loader startup failed", e);
                }
            } else {
                scene.removeKeyListener(listeners[0]);
            }
            
        } else {
            Status.println("Sandbox is still activated");
        }
        
    }

    public void pauseXlet() {
        screen.setVisible(false);
    }

    public void destroyXlet(boolean unconditional) {
        scene.remove(screen);
        scene = null;
    }
    
    private void disableFileProxies() {        
        try {
            Class bdjFactoryClass = Class.forName("com.oracle.orbis.io.BDJFactory");
            
            Field instanceField = bdjFactoryClass.getDeclaredField("instance");
            instanceField.setAccessible(true);
            
            Object currentInstance = instanceField.get(null);
            Status.println("Current BDJFactory instance: " + 
                (currentInstance != null ? currentInstance.getClass().getName() : "null"));
            
            // Null out the instance - this will make needProxy() return false
            instanceField.set(null, null);
            Status.println("BDJFactory instance set to null");
            
            Object newInstance = instanceField.get(null);
            Status.println("New BDJFactory instance: " + 
                (newInstance != null ? newInstance.getClass().getName() : "null"));
            
            Status.println("Setting java.io.tmpdir to /download0/BD_BUDA/javatmp");
            System.setProperty("java.io.tmpdir", "/download0/BD_BUDA/javatmp");
            
        } catch (Exception e) {
            Status.printStackTrace("Error disabling BDJFactory", e);
        }
    }
    
}



