/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, id:cactusman, Seiji Sogabe
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
package hudson.maven;

import hudson.Launcher;
import hudson.Extension;
import hudson.Util;
import hudson.maven.reporters.MavenAbstractArtifactRecord;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.apache.maven.artifact.deployer.ArtifactDeploymentException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.embedder.MavenEmbedderException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import org.kohsuke.stapler.QueryParameter;

/**
 * {@link Publisher} for {@link MavenModuleSetBuild} to deploy artifacts
 * after a build is fully succeeded. 
 *
 * @author Kohsuke Kawaguchi
 * @since 1.191
 */
public class RedeployPublisher extends Recorder {
    /**
     * Repository ID. This is matched up with <tt>~/.m2/settings.xml</tt> for authentication related information.
     */
    public final String id;
    /**
     * Repository URL to deploy artifacts to.
     */
    public final String url;
    public final boolean uniqueVersion;
    public final boolean evenIfUnstable;

    /**
     * For backward compatibility
     */
    public RedeployPublisher(String id, String url, boolean uniqueVersion) {
    	this(id, url, uniqueVersion, false);
    }
    
    /**
     * @since 1.347
     */
    @DataBoundConstructor
    public RedeployPublisher(String id, String url, boolean uniqueVersion, boolean evenIfUnstable) {
        this.id = id;
        this.url = Util.fixEmptyAndTrim(url);
        this.uniqueVersion = uniqueVersion;
        this.evenIfUnstable = evenIfUnstable;
    }

    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if(build.getResult().isWorseThan(getTreshold()))
            return true;    // build failed. Don't publish

        if (url==null) {
            listener.getLogger().println("No Repository URL is specified.");
            build.setResult(Result.FAILURE);
            return true;
        }

        MavenAbstractArtifactRecord mar = getAction(build);
        if(mar==null) {
            listener.getLogger().println("No artifacts are recorded. Is this a Maven project?");
            build.setResult(Result.FAILURE);
            return true;
        }

        listener.getLogger().println("Deploying artifacts to "+url);
        try {
            
            MavenEmbedder embedder = MavenUtil.createEmbedder(listener,build);
            ArtifactRepositoryLayout layout =
                (ArtifactRepositoryLayout) embedder.getContainer().lookup( ArtifactRepositoryLayout.ROLE,"default");
            ArtifactRepositoryFactory factory =
                (ArtifactRepositoryFactory) embedder.lookup(ArtifactRepositoryFactory.ROLE);

            ArtifactRepository repository = factory.createDeploymentArtifactRepository(
                    id, url, layout, uniqueVersion);

            mar.deploy(embedder,repository,listener);

            embedder.stop();
            return true;
        } catch (MavenEmbedderException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (ComponentLookupException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (ArtifactDeploymentException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        }
        // failed
        build.setResult(Result.FAILURE);
        return true;
    }

    /**
     * Obtains the {@link MavenAbstractArtifactRecord} that we'll work on.
     * <p>
     * This allows promoted-builds plugin to reuse the code for delayed deployment. 
     */
    protected MavenAbstractArtifactRecord getAction(AbstractBuild<?, ?> build) {
        return build.getAction(MavenAbstractArtifactRecord.class);
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    protected Result getTreshold() {
        if (evenIfUnstable) {
            return Result.UNSTABLE;
        } else {
            return Result.SUCCESS;
        }
    }
    
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public DescriptorImpl() {
        }

        /**
         * @deprecated as of 1.290
         *      Use the default constructor.
         */
        protected DescriptorImpl(Class<? extends Publisher> clazz) {
            super(clazz);
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return jobType==MavenModuleSet.class;
        }

        public RedeployPublisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(RedeployPublisher.class,formData);
        }

        public String getDisplayName() {
            return Messages.RedeployPublisher_getDisplayName();
        }

        public boolean showEvenIfUnstableOption() {
            // little hack to avoid showing this option on the redeploy action's screen
            return true;
        }

        public FormValidation doCheckUrl(@QueryParameter String url) {
            String fixedUrl = hudson.Util.fixEmptyAndTrim(url);
            if (fixedUrl==null)
                return FormValidation.error(Messages.RedeployPublisher_RepositoryURL_Mandatory());

            return FormValidation.ok();
        }
    }
}
