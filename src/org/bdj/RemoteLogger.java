package org.bdj;

import java.io.*;
import java.net.*;
import java.util.Vector;

public class RemoteLogger {
    private int port;
    private int maxCached;
    private DatagramSocket socket;
    private boolean running = false;
    private Vector logCache = new Vector();
    private Vector clients = new Vector();
    
    private static final int HEARTBEAT_INTERVAL = 1000;
    private static final int HEARTBEAT_TIMEOUT = 3000;
    private Thread heartbeatThread;

    public RemoteLogger(int port, int maxCached) {
        this.port = port;
        this.maxCached = maxCached;
    }

    public synchronized void start() {
        if (running) return;
        
        try {
            socket = new DatagramSocket(port);
            running = true;
            
            Thread listener = new Thread(new Runnable() {
                public void run() {
                    listenForClients();
                }
            });
            listener.setDaemon(true);
            listener.start();
            
            heartbeatThread = new Thread(new Runnable() {
                public void run() {
                    runHeartbeat();
                }
            });
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            
        } catch (Exception e) {

        }
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        clients.clear();
    }

    private void listenForClients() {
        byte[] buffer = new byte[1024];
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                
                if ("REGISTER".equals(message)) {
                    ClientEndpoint client = new ClientEndpoint(packet.getAddress(), packet.getPort());

                    synchronized (clients) {
                        if (!clients.contains(client)) {
                            clients.add(client);
                            sendToClient(client, "CONNECTED from " + client.address.getHostAddress() + "\n");
                            sendCachedMessages(client);
                        } else {
                            int index = clients.indexOf(client);
                            if (index >= 0) {
                                ClientEndpoint existingClient = (ClientEndpoint) clients.get(index);
                                existingClient.updateLastSeen();
                            }
                            sendToClient(client, "ALREADY_CONNECTED from " + client.address.getHostAddress() + "\n");
                        }
                    }
                } else if ("HEARTBEAT_ACK".equals(message)) {
                    ClientEndpoint client = new ClientEndpoint(packet.getAddress(), packet.getPort());
                    synchronized (clients) {
                        int index = clients.indexOf(client);
                        if (index >= 0) {
                            ClientEndpoint existingClient = (ClientEndpoint) clients.get(index);
                            existingClient.updateLastSeen();
                        }
                    }
                }
                
            } catch (Exception e) {
                if (running) {
                    
                }
            }
        }
    }

    private void runHeartbeat() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                
                if (!running) break;
                
                synchronized (clients) {
                    long currentTime = System.currentTimeMillis();
                    
                    for (int i = clients.size() - 1; i >= 0; i--) {
                        ClientEndpoint client = (ClientEndpoint) clients.get(i);
                        
                        // Check if client is still alive
                        if (currentTime - client.lastSeen > HEARTBEAT_TIMEOUT) {
                            // Client is dead, remove it
                            clients.remove(i);
                            continue;
                        }
                        
                        sendToClient(client, "HEARTBEAT");
                    }
                }
                
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    
                }
            }
        }
    }

    private void sendCachedMessages(ClientEndpoint client) {
        synchronized (logCache) {
            for (int i = 0; i < logCache.size(); i++) {
                String cachedMsg = (String) logCache.get(i);
                sendToClient(client, cachedMsg);
            }
        }
    }

    private void sendToClient(ClientEndpoint client, String message) {
        try {
            byte[] data = message.getBytes("UTF-8");
            
            // Send in small chunks to avoid "packet too large" exceptions
            // This is a UDP protocol constraint
            int i = 0;
            while (i < data.length) {
                int chunkSize = Math.min(data.length - i, 1024);
                DatagramPacket packet = new DatagramPacket(data, i, chunkSize, client.address, client.port);
                socket.send(packet);
                i += chunkSize;
            }
            
            // Send end-of-message marker to indicate complete message
            if (data.length > 1024) {
                String eom = "<<EOM>>";
                byte[] eomData = eom.getBytes("UTF-8");
                DatagramPacket eomPacket = new DatagramPacket(eomData, eomData.length, client.address, client.port);
                socket.send(eomPacket);
            }
        } catch (Exception e) {
            synchronized (clients) {
                clients.remove(client);
            }
        }
    }

    private void addToCache(String message) {
        synchronized (logCache) {
            logCache.add(message);
            while (logCache.size() > maxCached) {
                logCache.remove(0);
            }
        }
    }

    private void broadcast(String message) {
        addToCache(message);
        synchronized (clients) {
            for (int i = clients.size() - 1; i >= 0; i--) {
                ClientEndpoint client = (ClientEndpoint) clients.get(i);
                sendToClient(client, message);
            }
        }
    }


    public void println(String msg) {
        broadcast(msg + "\n");
    }

    public void printStackTrace(String msg, Throwable e) {
        broadcast(msg + "\n");
        
        if (e != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.close();
            broadcast(sw.toString() + "\n");
        }
    }


    private static class ClientEndpoint {
        InetAddress address;
        int port;
        long lastSeen;

        ClientEndpoint(InetAddress address, int port) {
            this.address = address;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
        
        void updateLastSeen() {
            this.lastSeen = System.currentTimeMillis();
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof ClientEndpoint)) return false;
            ClientEndpoint other = (ClientEndpoint) obj;
            return port == other.port && address.equals(other.address);
        }

        public int hashCode() {
            return address.hashCode() + port;
        }
    }
}