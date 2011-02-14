/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.lifecycle;

import com.sun.jna.Native;
import hudson.Launcher.LocalLauncher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.remoting.Engine;
import hudson.remoting.jnlp.MainDialog;
import hudson.remoting.jnlp.MainMenu;
import hudson.util.StreamTaskListener;
import hudson.util.jna.DotNet;
import hudson.util.jna.Kernel32Utils;
import hudson.util.jna.SHELLEXECUTEINFO;
import hudson.util.jna.Shell32;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;

import static hudson.util.jna.SHELLEXECUTEINFO.*;
import static javax.swing.JOptionPane.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class WindowsSlaveInstaller implements Callable<Void,RuntimeException>, ActionListener {
    /**
     * Root directory of this slave.
     * String, not File because the platform can be different.
     */
    private final String rootDir;

    private transient Engine engine;
    private transient MainDialog dialog;

    public WindowsSlaveInstaller(String rootDir) {
        this.rootDir = rootDir;
    }

    public Void call() {
        if(File.separatorChar=='/') return null;    // not Windows
        if(System.getProperty("hudson.showWindowsServiceInstallLink")==null)
            return null;    // only show this when it makes sense, which is when we run from JNLP

        dialog = MainDialog.get();
        if(dialog==null)     return null;    // can't find the main window. Maybe not running with GUI

        // capture the engine
        engine = Engine.current();

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                MainMenu mainMenu = dialog.getMainMenu();
                JMenu m = mainMenu.getFileMenu();
                JMenuItem menu = new JMenuItem(Messages.WindowsInstallerLink_DisplayName(), KeyEvent.VK_W);
                menu.addActionListener(WindowsSlaveInstaller.this);
                m.add(menu);
                mainMenu.commit();
            }
        });

        return null;
    }

    /**
     * Invokes slave.exe with a SCM management command.
     *
     * <p>
     * If it fails in a way that indicates the presence of UAC, retry in an UAC compatible manner.
     */
    static int runElevated(File slaveExe, String command, TaskListener out, File pwd) throws IOException, InterruptedException {
        try {
            return new LocalLauncher(out).launch().cmds(slaveExe, command).stdout(out).pwd(pwd).join();
        } catch (IOException e) {
            if (e.getMessage().contains("CreateProcess") && e.getMessage().contains("=740")) {
                // fall through
            } else {
                throw e;
            }
        }

        // error code 740 is ERROR_ELEVATION_REQUIRED, indicating that
        // we run in UAC-enabled Windows and we need to run this in an elevated privilege
        SHELLEXECUTEINFO sei = new SHELLEXECUTEINFO();
        sei.fMask = SEE_MASK_NOCLOSEPROCESS;
        sei.lpVerb = "runas";
        sei.lpFile = slaveExe.getAbsolutePath();
        sei.lpParameters = "/redirect redirect.log "+command;
        sei.lpDirectory = pwd.getAbsolutePath();
        sei.nShow = SW_HIDE;
        if (!Shell32.INSTANCE.ShellExecuteEx(sei))
            throw new IOException("Failed to shellExecute: "+ Native.getLastError());

        try {
            return Kernel32Utils.waitForExitProcess(sei.hProcess);
        } finally {
            FileInputStream fin = new FileInputStream(new File(pwd,"redirect.log"));
            IOUtils.copy(fin,out.getLogger());
            fin.close();
        }
    }

    /**
     * Called when the install menu is selected
     */
    public void actionPerformed(ActionEvent e) {
        try {
            int r = JOptionPane.showConfirmDialog(dialog,
                    Messages.WindowsSlaveInstaller_ConfirmInstallation(),
                    Messages.WindowsInstallerLink_DisplayName(), OK_CANCEL_OPTION);
            if(r!=JOptionPane.OK_OPTION)    return;

            if(!DotNet.isInstalled(2,0)) {
                JOptionPane.showMessageDialog(dialog,Messages.WindowsSlaveInstaller_DotNetRequired(),
                        Messages.WindowsInstallerLink_DisplayName(), ERROR_MESSAGE);
                return;
            }

            final File dir = new File(rootDir);
            if (!dir.exists()) {
                JOptionPane.showMessageDialog(dialog,Messages.WindowsSlaveInstaller_RootFsDoesntExist(rootDir),
                        Messages.WindowsInstallerLink_DisplayName(), ERROR_MESSAGE);
                return;
            }

            final File slaveExe = new File(dir, "jenkins-slave.exe");
            FileUtils.copyURLToFile(getClass().getResource("/windows-service/jenkins.exe"), slaveExe);

            // write out the descriptor
            URL jnlp = new URL(engine.getHudsonUrl(),"computer/"+Util.rawEncode(engine.slaveName)+"/slave-agent.jnlp");
            String xml = generateSlaveXml(
                    generateServiceId(rootDir),
                    System.getProperty("java.home")+"\\bin\\java.exe", "-jnlpUrl "+jnlp.toExternalForm());
            FileUtils.writeStringToFile(new File(dir, "jenkins-slave.xml"),xml,"UTF-8");

            // copy slave.jar
            URL slaveJar = new URL(engine.getHudsonUrl(),"jnlpJars/remoting.jar");
            File dstSlaveJar = new File(dir,"slave.jar").getCanonicalFile();
            if(!dstSlaveJar.exists()) // perhaps slave.jar is already there?
                FileUtils.copyURLToFile(slaveJar,dstSlaveJar);

            // install as a service
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            StreamTaskListener task = new StreamTaskListener(baos);
            r = runElevated(slaveExe,"install",task,dir);
            if(r!=0) {
                JOptionPane.showMessageDialog(
                    dialog,baos.toString(),"Error", ERROR_MESSAGE);
                return;
            }

            r = JOptionPane.showConfirmDialog(dialog,
                    Messages.WindowsSlaveInstaller_InstallationSuccessful(),
                    Messages.WindowsInstallerLink_DisplayName(), OK_CANCEL_OPTION);
            if(r!=JOptionPane.OK_OPTION)    return;

            // let the service start after we close our connection, to avoid conflicts
            Runtime.getRuntime().addShutdownHook(new Thread("service starter") {
                public void run() {
                    try {
                        StreamTaskListener task = StreamTaskListener.fromStdout();
                        int r = runElevated(slaveExe,"start",task,dir);
                        task.getLogger().println(r==0?"Successfully started":"start service failed. Exit code="+r);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
            System.exit(0);
        } catch (Exception t) {// this runs as a JNLP app, so if we let an exeption go, we'll never find out why it failed 
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            JOptionPane.showMessageDialog(dialog,sw.toString(),"Error", ERROR_MESSAGE);
        }
    }

    public static String generateServiceId(String slaveRoot) throws IOException {
        return "jenkinsslave-"+slaveRoot.replace(':','_').replace('\\','_').replace('/','_');
    }

    public static String generateSlaveXml(String id, String java, String args) throws IOException {
        String xml = IOUtils.toString(WindowsSlaveInstaller.class.getResourceAsStream("/windows-service/jenkins-slave.xml"), "UTF-8");
        xml = xml.replace("@ID@", id);
        xml = xml.replace("@JAVA@", java);
        xml = xml.replace("@ARGS@", args);
        return xml;
    }

    private static final long serialVersionUID = 1L;
}
