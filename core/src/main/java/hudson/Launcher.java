/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly
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
package hudson;

import hudson.Proc.LocalProc;
import hudson.Proc.RemoteProc;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.model.Node;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Pipe;
import hudson.remoting.RemoteInputStream;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.util.StreamCopyThread;
import hudson.util.ArgumentListBuilder;
import hudson.util.ProcessTree;
import org.apache.commons.io.input.NullInputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.io.output.NullOutputStream.NULL_OUTPUT_STREAM;

/**
 * Starts a process.
 *
 * <p>
 * This hides the difference between running programs locally vs remotely.
 *
 *
 * <h2>'env' parameter</h2>
 * <p>
 * To allow important environment variables to be copied over to the remote machine,
 * the 'env' parameter shouldn't contain default inherited environment variables
 * (which often contains machine-specific information, like PATH, TIMEZONE, etc.)
 *
 * <p>
 * {@link Launcher} is responsible for inheriting environment variables.
 *
 *
 * @author Kohsuke Kawaguchi
 * @see FilePath#createLauncher(TaskListener) 
 */
public abstract class Launcher {

    protected final TaskListener listener;

    protected final VirtualChannel channel;

    public Launcher(TaskListener listener, VirtualChannel channel) {
        this.listener = listener;
        this.channel = channel;
    }

    /**
     * Constructor for a decorator.
     */
    protected Launcher(Launcher launcher) {
        this(launcher.listener, launcher.channel);
    }

    /**
     * Gets the channel that can be used to run a program remotely.
     *
     * @return
     *      null if the target node is not configured to support this.
     *      this is a transitional measure.
     *      Note that a launcher for the master is always non-null.
     */
    public VirtualChannel getChannel() {
        return channel;
    }

    /**
     * Gets the {@link TaskListener} that this launcher uses to
     * report the commands that it's executing.
     */
    public TaskListener getListener() {
        return listener;
    }

    /**
     * If this {@link Launcher} is encapsulating an execution on a specific {@link Computer},
     * return it.
     *
     * <p>
     * Because of the way internal Hudson abstractions are set up (that is, {@link Launcher} only
     * needs a {@link VirtualChannel} to do its job and isn't really required that the channel
     * comes from an existing {@link Computer}), this method may not always the right {@link Computer} instance.
     *
     * @return
     *      null if this launcher is not created from a {@link Computer} object.
     * @deprecated since 2008-11-16.
     *      See the javadoc for why this is inherently unreliable. If you are trying to
     *      figure out the current {@link Computer} from within a build, use
     *      {@link Computer#currentComputer()}  
     */
    public Computer getComputer() {
        for( Computer c : Hudson.getInstance().getComputers() )
            if(c.getChannel()==channel)
                return c;
        return null;
    }

    /**
     * Builder pattern for configuring a process to launch.
     * @since 1.311
     */
    public final class ProcStarter {
        protected List<String> commands;
        protected boolean[] masks;
        protected FilePath pwd;
        protected OutputStream stdout = NULL_OUTPUT_STREAM, stderr;
        protected InputStream stdin = new NullInputStream(0);
        protected String[] envs;

        public ProcStarter cmds(String... args) {
            return cmds(Arrays.asList(args));
        }

        public ProcStarter cmds(File program, String... args) {
            commands = new ArrayList<String>(args.length+1);
            commands.add(program.getPath());
            commands.addAll(Arrays.asList(args));
            return this;
        }

        public ProcStarter cmds(List<String> args) {
            commands = new ArrayList<String>(args);
            return this;
        }

        public ProcStarter cmds(ArgumentListBuilder args) {
            commands = args.toList();
            masks = args.toMaskArray();
            return this;
        }

        public List<String> cmds() {
            return commands;
        }

        public ProcStarter masks(boolean... masks) {
            this.masks = masks;
            return this;
        }

        public boolean[] masks() {
            return masks;
        }

        public ProcStarter pwd(FilePath workDir) {
            this.pwd = workDir;
            return this;
        }

        public ProcStarter pwd(File workDir) {
            return pwd(new FilePath(workDir));
        }

        public ProcStarter pwd(String workDir) {
            return pwd(new File(workDir));
        }

        public FilePath pwd() {
            return pwd;
        }

        public ProcStarter stdout(OutputStream out) {
            this.stdout = out;
            return this;
        }

        /**
         * Sends the stdout to the given {@link TaskListener}.
         */
        public ProcStarter stdout(TaskListener out) {
            return stdout(out.getLogger());
        }

        public OutputStream stdout() {
            return stdout;
        }

        /**
         * Controls where the stderr of the process goes.
         * By default, it's bundled into stdout.
         */
        public ProcStarter stderr(OutputStream err) {
            this.stderr =  err;
            return this;
        }

        public OutputStream stderr() {
            return stderr;
        }

        /**
         * Controls where the stdin of the process comes from.
         * By default, <tt>/dev/null</tt>.
         */
        public ProcStarter stdin(InputStream in) {
            this.stdin = in;
            return this;
        }

        public InputStream stdin() {
            return stdin;
        }

        /**
         * Sets the environment variable overrides.
         *
         * <p>
         * In adition to what the current process
         * is inherited (if this is going to be launched from a slave agent, that
         * becomes the "current" process), these variables will be also set.
         */
        public ProcStarter envs(Map<String, String> overrides) {
            return envs(Util.mapToEnv(overrides));
        }

        /**
         * @param overrides
         *      List of "VAR=VALUE". See {@link #envs(Map)} for the semantics.
         */
        public ProcStarter envs(String... overrides) {
            this.envs = overrides;
            return this;
        }

        public String[] envs() {
            return envs;
        }

        /**
         * Starts the new process as configured.
         */
        public Proc start() throws IOException {
            return launch(this);
        }

        /**
         * Starts the process and waits for its completion.
         */
        public int join() throws IOException, InterruptedException {
            return start().join();
        }

        /**
         * Copies a {@link ProcStarter}.
         */
        public ProcStarter copy() {
            return new ProcStarter().cmds(commands).pwd(pwd).masks(masks).stdin(stdin).stdout(stdout).stderr(stderr).envs(envs);
        }
    }

    /**
     * Launches a process by using a {@linkplain ProcStarter builder-pattern} to configure
     * the parameters.
     */
    public final ProcStarter launch() {
        return new ProcStarter();
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String cmd, Map<String,String> env, OutputStream out, FilePath workDir) throws IOException {
        return launch(cmd,Util.mapToEnv(env),out,workDir);
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, Map<String, String> env, OutputStream out, FilePath workDir) throws IOException {
        return launch(cmd, Util.mapToEnv(env), out, workDir);
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, Map<String, String> env, InputStream in, OutputStream out) throws IOException {
        return launch(cmd, Util.mapToEnv(env), in, out);
    }

    /**
     * Launch a command with optional censoring of arguments from the listener (Note: <strong>The censored portions will
     * remain visible through /proc, pargs, process explorer, etc. i.e. people logged in on the same machine</strong>
     * This version of the launch command just ensures that it is not visible from a build log which is exposed via the
     * web)
     *
     * @param cmd     The command and all it's arguments.
     * @param mask    Which of the command and arguments should be masked from the listener
     * @param env     Environment variable overrides.
     * @param out     stdout and stderr of the process will be sent to this stream. the stream won't be closed.
     * @param workDir null if the working directory could be anything.
     * @return The process of the command.
     * @throws IOException When there are IO problems.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, boolean[] mask, Map<String, String> env, OutputStream out, FilePath workDir) throws IOException {
        return launch(cmd, mask, Util.mapToEnv(env), out, workDir);
    }

    /**
     * Launch a command with optional censoring of arguments from the listener (Note: <strong>The censored portions will
     * remain visible through /proc, pargs, process explorer, etc. i.e. people logged in on the same machine</strong>
     * This version of the launch command just ensures that it is not visible from a build log which is exposed via the
     * web)
     *
     * @param cmd     The command and all it's arguments.
     * @param mask    Which of the command and arguments should be masked from the listener
     * @param env     Environment variable overrides.
     * @param in      null if there's no input.
     * @param out     stdout and stderr of the process will be sent to this stream. the stream won't be closed.
     * @return The process of the command.
     * @throws IOException When there are IO problems.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, boolean[] mask, Map<String, String> env, InputStream in, OutputStream out) throws IOException {
        return launch(cmd, mask, Util.mapToEnv(env), in, out);
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String cmd,String[] env,OutputStream out, FilePath workDir) throws IOException {
        return launch(Util.tokenize(cmd),env,out,workDir);
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, String[] env, OutputStream out, FilePath workDir) throws IOException {
        return launch(cmd, env, null, out, workDir);
    }

    /**
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, String[] env, InputStream in, OutputStream out) throws IOException {
        return launch(cmd, env, in, out, null);
    }

    /**
     * Launch a command with optional censoring of arguments from the listener (Note: <strong>The censored portions will
     * remain visible through /proc, pargs, process explorer, etc. i.e. people logged in on the same machine</strong>
     * This version of the launch command just ensures that it is not visible from a build log which is exposed via the
     * web)
     *
     * @param cmd     The command and all it's arguments.
     * @param mask    Which of the command and arguments should be masked from the listener
     * @param env     Environment variable overrides.
     * @param out     stdout and stderr of the process will be sent to this stream. the stream won't be closed.
     * @param workDir null if the working directory could be anything.
     * @return The process of the command.
     * @throws IOException When there are IO problems.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, boolean[] mask, String[] env, OutputStream out, FilePath workDir) throws IOException {
        return launch(cmd, mask, env, null, out, workDir);
    }

    /**
     * Launch a command with optional censoring of arguments from the listener (Note: <strong>The censored portions will
     * remain visible through /proc, pargs, process explorer, etc. i.e. people logged in on the same machine</strong>
     * This version of the launch command just ensures that it is not visible from a build log which is exposed via the
     * web)
     *
     * @param cmd     The command and all it's arguments.
     * @param mask    Which of the command and arguments should be masked from the listener
     * @param env     Environment variable overrides.
     * @param in      null if there's no input.
     * @param out     stdout and stderr of the process will be sent to this stream. the stream won't be closed.
     * @return The process of the command.
     * @throws IOException When there are IO problems.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public final Proc launch(String[] cmd, boolean[] mask, String[] env, InputStream in, OutputStream out) throws IOException {
        return launch(cmd, mask, env, in, out, null);
    }

    /**
     * @param env
     *      Environment variable overrides.
     * @param in
     *      null if there's no input.
     * @param workDir
     *      null if the working directory could be anything.
     * @param out
     *      stdout and stderr of the process will be sent to this stream.
     *      the stream won't be closed.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public Proc launch(String[] cmd, String[] env, InputStream in, OutputStream out, FilePath workDir) throws IOException {
        return launch(launch().cmds(cmd).envs(env).stdin(in).stdout(out).pwd(workDir));
    }

    /**
     * Launch a command with optional censoring of arguments from the listener (Note: <strong>The censored portions will
     * remain visible through /proc, pargs, process explorer, etc. i.e. people logged in on the same machine</strong>
     * This version of the launch command just ensures that it is not visible from a build log which is exposed via the
     * web)
     *
     * @param cmd     The command and all it's arguments.
     * @param mask    Which of the command and arguments should be masked from the listener
     * @param env     Environment variable overrides.
     * @param in      null if there's no input.
     * @param out     stdout and stderr of the process will be sent to this stream. the stream won't be closed.
     * @param workDir null if the working directory could be anything.
     * @return The process of the command.
     * @throws IOException When there are IO problems.
     *
     * @deprecated as of 1.311
     *      Use {@link #launch()} and its associated builder pattern
     */
    public Proc launch(String[] cmd, boolean[] mask, String[] env, InputStream in, OutputStream out, FilePath workDir) throws IOException {
        return launch(launch().cmds(cmd).masks(mask).envs(env).stdin(in).stdout(out).pwd(workDir));
    }

    /**
     * Primarily invoked from {@link ProcStarter#start()} to start a process with a specific launcher.
     */
    public abstract Proc launch(ProcStarter starter) throws IOException;

    /**
     * Launches a specified process and connects its input/output to a {@link Channel}, then
     * return it.
     *
     * <p>
     * When the returned channel is terminated, the process will be killed.
     *
     * @param out
     *      Where the stderr from the launched process will be sent.
     * @param workDir
     *      The working directory of the new process, or null to inherit
     *      from the current process
     * @param envVars
     *      Environment variable overrides. In addition to what the current process
     *      is inherited (if this is going to be launched from a slave agent, that
     *      becomes the "current" process), these variables will be also set.
     */
    public abstract Channel launchChannel(String[] cmd, OutputStream out, FilePath workDir, Map<String,String> envVars) throws IOException, InterruptedException;

    /**
     * Returns true if this {@link Launcher} is going to launch on Unix.
     */
    public boolean isUnix() {
        return File.pathSeparatorChar==':';
    }

    /**
     * Calls {@link ProcessTree#killAll(Map)} to kill processes.
     */
    public abstract void kill(Map<String,String> modelEnvVars) throws IOException, InterruptedException;

    /**
     * Prints out the command line to the listener so that users know what we are doing.
     */
    protected final void printCommandLine(String[] cmd, FilePath workDir) {
        StringBuilder buf = new StringBuilder();
        if (workDir != null) {
            buf.append('[');
            if(showFullPath)
                buf.append(workDir.getRemote());
            else
                buf.append(workDir.getRemote().replaceFirst("^.+[/\\\\]", ""));
            buf.append("] ");
        }
        buf.append('$');
        for (String c : cmd) {
            buf.append(' ');
            if(c.indexOf(' ')>=0) {
                if(c.indexOf('"')>=0)
                    buf.append('\'').append(c).append('\'');
                else
                    buf.append('"').append(c).append('"');
            } else
                buf.append(c);
        }
        listener.getLogger().println(buf.toString());
    }

    /**
     * Prints out the command line to the listener with some portions masked to prevent sensitive information from being
     * recorded on the listener.
     *
     * @param cmd     The commands
     * @param mask    An array of booleans which control whether a cmd element should be masked (<code>true</code>) or
     *                remain unmasked (<code>false</code>).
     * @param workDir The work dir.
     */
    protected final void maskedPrintCommandLine(List<String> cmd, boolean[] mask, FilePath workDir) {
        if(mask==null) {
            printCommandLine(cmd.toArray(new String[cmd.size()]),workDir);
            return;
        }
        
        assert mask.length == cmd.size();
        final String[] masked = new String[cmd.size()];
        for (int i = 0; i < cmd.size(); i++) {
            if (mask[i]) {
                masked[i] = "********";
            } else {
                masked[i] = cmd.get(i);
            }
        }
        printCommandLine(masked, workDir);
    }
    protected final void maskedPrintCommandLine(String[] cmd, boolean[] mask, FilePath workDir) {
        maskedPrintCommandLine(Arrays.asList(cmd),mask,workDir);
    }

    /**
     * Returns a decorated {@link Launcher} for the given node.
     */
    public final Launcher decorateFor(Node node) {
        Launcher l = this;
        for (LauncherDecorator d : LauncherDecorator.all())
            l = d.decorate(l,node);
        return l;
    }

    /**
     * Returns a decorated {@link Launcher} that puts the given set of arguments as a prefix to any commands
     * that it invokes.
     *
     * @since 1.299
     */
    public final Launcher decorateByPrefix(final String... prefix) {
        final Launcher outer = this;
        return new Launcher(outer) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                starter.commands.addAll(0,Arrays.asList(prefix));
                starter.masks = prefix(starter.masks);
                return outer.launch(starter);
            }

            @Override
            public Channel launchChannel(String[] cmd, OutputStream out, FilePath workDir, Map<String, String> envVars) throws IOException, InterruptedException {
                return outer.launchChannel(prefix(cmd),out,workDir,envVars);
            }

            @Override
            public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
                outer.kill(modelEnvVars);
            }

            private String[] prefix(String[] args) {
                String[] newArgs = new String[args.length+prefix.length];
                System.arraycopy(prefix,0,newArgs,0,prefix.length);
                System.arraycopy(args,0,newArgs,prefix.length,args.length);
                return newArgs;
            }

            private boolean[] prefix(boolean[] args) {
                boolean[] newArgs = new boolean[args.length+prefix.length];
                System.arraycopy(args,0,newArgs,prefix.length,args.length);
                return newArgs;
            }
        };
    }

    /**
     * {@link Launcher} that launches process locally.
     */
    public static class LocalLauncher extends Launcher {
        public LocalLauncher(TaskListener listener) {
            this(listener,Hudson.MasterComputer.localChannel);
        }

        public LocalLauncher(TaskListener listener, VirtualChannel channel) {
            super(listener, channel);
        }

        @Override
        public Proc launch(ProcStarter ps) throws IOException {
            maskedPrintCommandLine(ps.commands, ps.masks, ps.pwd);

            EnvVars jobEnv = inherit(ps.envs);

            // replace variables in command line
            String[] jobCmd = new String[ps.commands.size()];
            for ( int idx = 0 ; idx < jobCmd.length; idx++ )
            	jobCmd[idx] = jobEnv.expand(ps.commands.get(idx));

            return new LocalProc(jobCmd, Util.mapToEnv(jobEnv), ps.stdin, ps.stdout, ps.stderr, toFile(ps.pwd));
        }

        private File toFile(FilePath f) {
            return f==null ? null : new File(f.getRemote());
        }

        public Channel launchChannel(String[] cmd, OutputStream out, FilePath workDir, Map<String,String> envVars) throws IOException {
            printCommandLine(cmd, workDir);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(toFile(workDir));
            if (envVars!=null) pb.environment().putAll(envVars);

            return launchChannel(out, pb);
        }

        @Override
        public void kill(Map<String, String> modelEnvVars) throws InterruptedException {
            ProcessTree.get().killAll(modelEnvVars);
        }

        /**
         * @param out
         *      Where the stderr from the launched process will be sent.
         */
        public Channel launchChannel(OutputStream out, ProcessBuilder pb) throws IOException {
            final EnvVars cookie = EnvVars.createCookie();
            pb.environment().putAll(cookie);

            final Process proc = pb.start();

            final Thread t2 = new StreamCopyThread(pb.command()+": stderr copier", proc.getErrorStream(), out);
            t2.start();

            return new Channel("locally launched channel on "+ pb.command(),
                Computer.threadPoolForRemoting, proc.getInputStream(), proc.getOutputStream(), out) {

                /**
                 * Kill the process when the channel is severed.
                 */
                @Override
                protected synchronized void terminate(IOException e) {
                    super.terminate(e);
                    ProcessTree pt = ProcessTree.get();
                    try {
                        pt.killAll(proc,cookie);
                    } catch (InterruptedException x) {
                        LOGGER.log(Level.INFO, "Interrupted", x);
                    }
                }

                @Override
                public synchronized void close() throws IOException {
                    super.close();
                    // wait for all the output from the process to be picked up
                    try {
                        t2.join();
                    } catch (InterruptedException e) {
                        // process the interrupt later
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    /**
     * Launches processes remotely by using the given channel.
     */
    public static class RemoteLauncher extends Launcher {
        private final boolean isUnix;

        public RemoteLauncher(TaskListener listener, VirtualChannel channel, boolean isUnix) {
            super(listener, channel);
            this.isUnix = isUnix;
        }

        public Proc launch(ProcStarter ps) throws IOException {
            final OutputStream out = ps.stdout == null ? null : new RemoteOutputStream(new CloseProofOutputStream(ps.stdout));
            final OutputStream err = ps.stderr==null ? null : new RemoteOutputStream(new CloseProofOutputStream(ps.stderr));
            final InputStream  in  = ps.stdin==null ? null : new RemoteInputStream(ps.stdin);
            final String workDir = ps.pwd==null ? null : ps.pwd.getRemote();

            return new RemoteProc(getChannel().callAsync(new RemoteLaunchCallable(ps.commands, ps.masks, ps.envs, in, out, err, workDir, listener)));
        }

        public Channel launchChannel(String[] cmd, OutputStream err, FilePath _workDir, Map<String,String> envOverrides) throws IOException, InterruptedException {
            printCommandLine(cmd, _workDir);

            Pipe out = Pipe.createRemoteToLocal();
            final String workDir = _workDir==null ? null : _workDir.getRemote();

            OutputStream os = getChannel().call(new RemoteChannelLaunchCallable(cmd, out, err, workDir, envOverrides));

            return new Channel("remotely launched channel on "+channel,
                Computer.threadPoolForRemoting, out.getIn(), new BufferedOutputStream(os));
        }

        @Override
        public boolean isUnix() {
            return isUnix;
        }

        @Override
        public void kill(final Map<String,String> modelEnvVars) throws IOException, InterruptedException {
            getChannel().call(new KillTask(modelEnvVars));
        }

        private static final class KillTask implements Callable<Void,RuntimeException> {
            private final Map<String, String> modelEnvVars;

            public KillTask(Map<String, String> modelEnvVars) {
                this.modelEnvVars = modelEnvVars;
            }

            public Void call() throws RuntimeException {
                try {
                    ProcessTree.get().killAll(modelEnvVars);
                } catch (InterruptedException e) {
                    // we are asked to terminate early by the caller, so no need to do anything
                }
                return null;
            }

            private static final long serialVersionUID = 1L;
        }
    }

    private static class RemoteLaunchCallable implements Callable<Integer,IOException> {
        private final List<String> cmd;
        private final boolean[] masks;
        private final String[] env;
        private final InputStream in;
        private final OutputStream out;
        private final OutputStream err;
        private final String workDir;
        private final TaskListener listener;

        RemoteLaunchCallable(List<String> cmd, boolean[] masks, String[] env, InputStream in, OutputStream out, OutputStream err, String workDir, TaskListener listener) {
            this.cmd = new ArrayList<String>(cmd);
            this.masks = masks;
            this.env = env;
            this.in = in;
            this.out = out;
            this.err = err;
            this.workDir = workDir;
            this.listener = listener;
        }

        public Integer call() throws IOException {
            Launcher.ProcStarter ps = new LocalLauncher(listener).launch();
            ps.cmds(cmd).masks(masks).envs(env).stdin(in).stdout(out).stderr(err);
            if(workDir!=null)   ps.pwd(workDir);

            Proc p = ps.start();
            try {
                return p.join();
            } catch (InterruptedException e) {
                return -1;
            } finally {
                // make sure I/O is delivered to the remote before we return
                try {
              	    Channel.current().syncIO();
              } catch (Throwable _) {
                   // this includes a failure to sync, slave.jar too old, etc
              }
            }
        }

        private static final long serialVersionUID = 1L;
    }

    private static class RemoteChannelLaunchCallable implements Callable<OutputStream,IOException> {
        private final String[] cmd;
        private final Pipe out;
        private final String workDir;
        private final OutputStream err;
        private final Map<String,String> envOverrides;

        public RemoteChannelLaunchCallable(String[] cmd, Pipe out, OutputStream err, String workDir, Map<String,String> envOverrides) {
            this.cmd = cmd;
            this.out = out;
            this.err = new RemoteOutputStream(err);
            this.workDir = workDir;
            this.envOverrides = envOverrides;
        }

        public OutputStream call() throws IOException {
            Process p = Runtime.getRuntime().exec(cmd,
                Util.mapToEnv(inherit(envOverrides)),
                workDir == null ? null : new File(workDir));

            List<String> cmdLines = Arrays.asList(cmd);
            new StreamCopyThread("stdin copier for remote agent on "+cmdLines,
                p.getInputStream(), out.getOut()).start();
            new StreamCopyThread("stderr copier for remote agent on "+cmdLines,
                p.getErrorStream(), err).start();

            // TODO: don't we need to join?

            return new RemoteOutputStream(p.getOutputStream());
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Expands the list of environment variables by inheriting current env variables.
     */
    private static EnvVars inherit(String[] env) {
        // convert String[] to Map first
        EnvVars m = new EnvVars();
        if(env!=null) {
            for (String e : env) {
                int index = e.indexOf('=');
                m.put(e.substring(0,index), e.substring(index+1));
            }
        }
        // then do the inheritance
        return inherit(m);
    }

    /**
     * Expands the list of environment variables by inheriting current env variables.
     */
    private static EnvVars inherit(Map<String,String> overrides) {
        EnvVars m = new EnvVars(EnvVars.masterEnvVars);
        for (Map.Entry<String,String> o : overrides.entrySet()) 
            m.override(o.getKey(),m.expand(o.getValue()));
        return m;
    }

    /**
     * Debug option to display full current path instead of just the last token.
     */
    public static boolean showFullPath = false;

    private static final Logger LOGGER = Logger.getLogger(Launcher.class.getName());
}
