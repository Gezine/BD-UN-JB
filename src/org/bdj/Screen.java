package org.bdj;

import java.awt.Color;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.StringTokenizer;
import java.util.Vector;

public class Screen extends Container {
    private static final long serialVersionUID = 0x4141414141414141L;
    private final Font FONT = new Font(null, Font.PLAIN, 17);
    private final Vector messages = new Vector();
    private static final Screen instance = new Screen();
    private volatile boolean isPainting = false;
    private volatile boolean isDirty = false;
    private volatile boolean isVisible = true;

    private Screen() {
        super();
        setBackground(Color.BLACK);
        setForeground(Color.WHITE);
        
        // Add component listener to track visibility
        addComponentListener(new ComponentAdapter() {
            public void componentShown(ComponentEvent e) {
                isVisible = true;
                safeRepaint();
            }
            
            public void componentHidden(ComponentEvent e) {
                isVisible = false;
            }
        });
    }


    public static Screen getInstance() {
        return instance;
    }


    public static void println(String msg) {
        println(msg, true, false);
    }


    public static void println(String msg, boolean repaint, boolean replaceLast) {
        getInstance().print(msg, repaint, replaceLast);
    }


    public void print(String msg, boolean repaint, boolean replaceLast) {
        if (msg == null) {
            msg = "null";
        }
        
        synchronized (messages) {
            if (replaceLast && messages.size() > 0) {
                messages.removeElementAt(messages.size() - 1);
            }
            messages.addElement(msg);
            
            while (messages.size() > 46) {
                messages.removeElementAt(0);
            }
            
            isDirty = true;
        }

        if (repaint) {
            safeRepaint();
        }
    }
    

    private void safeRepaint() {
        if (EventQueue.isDispatchThread()) {
            // Already on EDT, repaint directly
            if (isDisplayable()) {
                repaint();
            }
        } else {
            // Not on EDT, queue the repaint
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    if (isDisplayable()) {
                        repaint();
                    }
                }
            });
        }
    }
    

    public void update(Graphics g) {
        paint(g);
    }

    public static void printStackTrace(String msg, Throwable e) {
        println(msg, true, false);
        getInstance().printStackTrace(e);
    }

    public void printStackTrace(Throwable e) {
        if (e == null) {
            print("null exception", true, false);
            return;
        }
        
        StringTokenizer st;
        StringBuffer sb;

        try {
            StringWriter sw = new StringWriter();
            try {
                PrintWriter pw = new PrintWriter(sw);
                try {
                    e.printStackTrace(pw);
                    pw.flush();
                } finally {
                    pw.close();
                }

                String stackTrace = sw.toString();
                st = new StringTokenizer(stackTrace, "\n", false);
                sb = new StringBuffer(stackTrace.length());
            } finally {
                sw.close();
            }

            Vector stackLines = new Vector();
            while (st.hasMoreTokens()) {
                String line = st.nextToken();
                sb.setLength(0);
                for (int i = 0; i < line.length(); ++i) {
                    char c = line.charAt(i);
                    if (c == '\t') {
                        sb.append("   ");
                    } else if (c == '\r') {
                        continue;
                    } else {
                        sb.append(c);
                    }
                }
                stackLines.addElement(sb.toString());
            }
            
            synchronized (messages) {
                for (int i = 0; i < stackLines.size(); i++) {
                    String line = (String) stackLines.elementAt(i);
                    print(line, false, false);
                }
            }

            safeRepaint();
            
        } catch (IOException ioEx) {
            printThrowable(e);
            throw new RuntimeException("Another exception occurred while printing stacktrace. " + ioEx.getClass().getName() + ": " + ioEx.getMessage());
        }
    }


    public void printThrowable(Throwable e) {
        if (e == null) {
            print("null throwable", true, false);
            return;
        }
        String message = e.getMessage();
        if (message == null) {
            message = "(no message)";
        }
        print(e.getClass().getName() + ": " + message, true, false);
    }

    public void paint(Graphics g) {
        if (g == null) {
            return;
        }
        
        Vector messagesCopy;
        boolean needsAnotherRepaint = false;
        
        synchronized (messages) {
            // If already painting, just return
            if (isPainting) {
                return;
            }
            
            // If nothing to paint, return
            if (!isDirty && messages.isEmpty()) {
                return;
            }
            
            isPainting = true;
            isDirty = false;

            messagesCopy = new Vector(messages);
        }

        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            
            g.setFont(FONT);
            g.setColor(getForeground());

            int fontHeight = g.getFontMetrics().getHeight();
            
            int x = 8;
            int y = 23;
            
            for (int i = 0; i < messagesCopy.size(); i++) {
                String msg = (String) messagesCopy.elementAt(i);
                if (msg != null && y < getHeight()) {
                    g.drawString(msg, x, y);
                    y += fontHeight;
                }
            }
            
        } finally {
            synchronized (messages) {
                isPainting = false;
                
                // Check if more messages were added while painting
                if (isDirty) {
                    needsAnotherRepaint = true;
                }
            }
            
            // Schedule another repaint if needed, outside the synchronized block
            if (needsAnotherRepaint) {
                safeRepaint();
            }
        }
    }
    

    public static void clear() {
        getInstance().clearMessages();
    }
    
    public void clearMessages() {
        synchronized (messages) {
            messages.removeAllElements();
            isDirty = true;
        }
        safeRepaint();
    }
    
}