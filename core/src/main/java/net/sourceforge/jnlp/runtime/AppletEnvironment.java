// Copyright (C) 2001-2003 Jon A. Maxwell (JAM)
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp.runtime;

import net.adoptopenjdk.icedteaweb.IcedTeaWebConstants;
import net.adoptopenjdk.icedteaweb.jnlp.element.application.AppletDesc;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.adoptopenjdk.icedteaweb.ui.swing.SwingUtils;
import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.services.ServiceUtil;
import net.sourceforge.jnlp.util.AppletUtils;
import net.sourceforge.jnlp.util.WeakList;

import javax.swing.ImageIcon;
import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The applet environment including stub, context, and frame.  The
 * default environment puts the applet in a non-resizable frame;
 * this can be changed by obtaining the frame and setting it
 * resizable.
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.12 $
 */
public class AppletEnvironment implements AppletContext, AppletStub {
    
    private final static Logger LOG = LoggerFactory.getLogger(AppletEnvironment.class);

    /** the JNLP file */
    private JNLPFile file;

    /** the applet */
    private Applet applet;

    /** the parameters */
    private Map<String, String> parameters;

    /** the applet container */
    private Container cont;

    /** weak references to the audio clips */
    private final WeakList<AppletAudioClip> weakClips = new WeakList<>();

    /** whether the applet has been started / displayed */
    private boolean appletStarted = false;

    /** whether the applet has been destroyed */
    private boolean destroyed = false;

    /**
     * Create a new applet environment for the applet specified by
     * the JNLP file.
     * @param file jnlp file base to construct environment
     * @param appletInstance applet for this environment
     * @param cont container to place this applet to
     */
    public AppletEnvironment(JNLPFile file, final AppletInstance appletInstance, Container cont) {
        this.file = file;
        this.applet = appletInstance.getApplet();

        parameters = file.getApplet().getParameters();
        this.cont = cont;
    }

    /**
     * Create a new applet environment for the applet specified by
     * the JNLP file, in a new frame.
     * @param file jnlp file base to construct environment
     * @param appletInstance applet for this environment
     */
    public AppletEnvironment(JNLPFile file, final AppletInstance appletInstance) {
        this(file, appletInstance, null);

        Frame frame = new Frame(file.getApplet().getName() + " - Applet");
        frame.setResizable(false);

        appletInstance.addWindow(frame);
        // may not need this once security manager can close windows
        // that do not have app code on the stack
        WindowListener closer = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                appletInstance.destroy();
                JNLPRuntime.exit(0);
            }
        };
        frame.addWindowListener(closer);
        this.cont = frame;
    }

    /**
     * Checks whether the applet has been destroyed, and throws an
     * IllegalStateException if the applet has been destroyed of.
     *
     * @throws IllegalStateException
     */
    private void checkDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("Illegal applet stub/context access: applet destroyed.");
        }
    }

    /**
     * Disposes the applet's resources and disables the applet
     * environment from further use; after calling this method the
     * applet stub and context methods throw IllegalStateExceptions.
     */
    public void destroy() {
        destroyed = true;

        List<AppletAudioClip> clips = weakClips.hardList();
        for (AppletAudioClip clip : clips) {
            clip.dispose();
        }
    }

    /**
     * @return the frame that contains the applet.  Disposing this
     * frame will destroy the applet.
     */
    public Container getAppletFrame() {
        // TODO: rename this method to getAppletContainer ?
        return cont;
    }

    /**
     * Initialize, start, and show the applet.
     */
    public void startApplet() {
        checkDestroyed();

        if (appletStarted) {
            return;
        }

        appletStarted = true;

        try {
            AppletDesc appletDesc = file.getApplet();

            if (cont instanceof AppletStub) {
                applet.setStub((AppletStub) cont);
            }
            else {
                applet.setStub(this);
            }

            cont.setLayout(new BorderLayout());
            cont.add("Center", applet);
            cont.validate();

            // This is only needed if the applet is in its own frame.
            if (cont instanceof Frame) {
                Frame frame = (Frame) cont;
                frame.pack(); // cause insets to be calculated

                Insets insets = frame.getInsets();
                frame.setSize(AppletUtils.getFixedWidth(appletDesc.getWidth(), appletDesc.getParameters()) + insets.left + insets.right,
                        AppletUtils.getFixedHeight(appletDesc.getHeight(), appletDesc.getParameters()) + insets.top + insets.bottom);
            }

            try {
                SwingUtils.callOnAppContext(new Runnable() {
                    @Override
                    public void run() {
                        // do first because some applets need to be displayed before
                        // starting (they use Component.getImage or something)
                        cont.setVisible(true);

                        applet.init();
                        applet.start();

                        cont.invalidate(); // this should force the applet to
                        cont.validate(); // the correct size and to repaint
                        cont.repaint();
                    }
                });
            } catch (InterruptedException | InvocationTargetException ie) {
                LOG.error(IcedTeaWebConstants.DEFAULT_ERROR_MESSAGE, ie);
            }
        } catch (Exception ex) {
            LOG.error(IcedTeaWebConstants.DEFAULT_ERROR_MESSAGE, ex);

            // should also kill the applet?
        }
    }

    // applet context methods

    /**
     * Returns the applet if the applet's name is specified,
     * otherwise return null.
     */
    @Override
    public Applet getApplet(String name) {
        checkDestroyed();

        if (name != null && name.equals(file.getApplet().getName())) {
            return applet;
        } else {
            return null;
        }
    }

    /**
     * Set the applet of this environment; can only be called once.
     * @param applet source of this environment
     */
    public void setApplet(Applet applet) {
        if (this.applet != null) {
            LOG.error("Applet can only be set once.", new IllegalStateException("Applet can only be set once."));
            return;
        }
        this.applet = applet;
    }

    /**
     * Returns an enumeration that contains only the applet
     * from the JNLP file.
     */
    @Override
    public Enumeration<Applet> getApplets() {
        checkDestroyed();

        return Collections.enumeration(Arrays.asList(new Applet[] { applet }));
    }

    /**
     * @param location source of clip
     * @return an audio clip.
     */
    @Override
    public AudioClip getAudioClip(URL location) {
        checkDestroyed();

        AppletAudioClip clip = new AppletAudioClip(location);

        weakClips.add(clip);
        weakClips.trimToSize();

        return clip;
    }

    /**
     * @return  an image loaded from the specified location.
     * @param location location of image
     */
    @Override
    public Image getImage(URL location) {
        checkDestroyed();

        //return Toolkit.getDefaultToolkit().createImage(location);
        Image image = (new ImageIcon(location)).getImage();

        return image;
    }

    /**
     * @param uRL url of document
     */
    @Override
    public void showDocument(java.net.URL uRL) {
        checkDestroyed();
        ServiceUtil.getBasicService().showDocument(uRL);
    }

    /**
     * Not implemented yet.
     * @param uRL source of document
     * @param str _self, _parent, _top, _blank or "name". Have sense only for applets. Not implemented for our javaws world
     */
    @Override
    public void showDocument(java.net.URL uRL, java.lang.String str) {
        checkDestroyed();
        ServiceUtil.getBasicService().showDocument(uRL);

    }

    /**
     * Not implemented yet.
     * @param  str  id of applet
     */
    @Override
    public void showStatus(java.lang.String str) {
        checkDestroyed();

    }

    /**
     * Required for JRE1.4, but not implemented yet.
     */
    @Override
    public void setStream(String key, InputStream stream) {
        checkDestroyed();

    }

    /**
     * Required for JRE1.4, but not implemented yet.
     */
    @Override
    public InputStream getStream(String key) {
        checkDestroyed();

        return null;
    }

    /**
     * Required for JRE1.4, but not implemented yet.
     */
    @Override
    public Iterator<String> getStreamKeys() {
        checkDestroyed();

        return null;
    }

    // stub methods

    @Override
    public void appletResize(int width, int height) {
        checkDestroyed();

        if (cont instanceof Frame) {
            Frame frame = (Frame) cont;
            Insets insets = frame.getInsets();

            frame.setSize(width + insets.left + insets.right,
                          height + insets.top + insets.bottom);
        }
    }

    @Override
    public AppletContext getAppletContext() {
        checkDestroyed();

        return this;
    }

    @Override
    public URL getCodeBase() {
        checkDestroyed();

        return file.getCodeBase();
    }

    @Override
    public URL getDocumentBase() {
        checkDestroyed();
        URL db = file.getApplet().getDocumentBase();
        if (db == null) {
            return getCodeBase();
        } else {
            return db;
        }
    }

    // FIXME: Sun's applet code forces all parameters to lower case.
    // Does Netx's JNLP code do the same, so we can remove the first lookup?
    @Override
    public String getParameter(String name) {
        checkDestroyed();

        String s = parameters.get(name);
        if (s != null) {
            return s;
        }

        return  parameters.get(name.toLowerCase());
    }

    @Override
    public boolean isActive() {
        checkDestroyed();

        // it won't be started or stopped, so if it can call it's running
        return true;
    }

}
