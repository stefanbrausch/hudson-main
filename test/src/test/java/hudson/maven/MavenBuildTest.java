package hudson.maven;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.scm.SubversionSCM;
import hudson.tasks.Maven.MavenInstallation;
import hudson.util.NullStream;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.Email;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.HudsonTestCase;
import org.tmatesoft.svn.core.SVNException;

/**
 * @author Kohsuke Kawaguchi
 */
public class MavenBuildTest extends HudsonTestCase {
    
    /**
     * NPE in {@code build.getProject().getWorkspace()} for {@link MavenBuild}.
     */
    @Bug(4192)
    public void testMavenWorkspaceExists() throws Exception {
        configureDefaultMaven();
        MavenModuleSet m = createMavenProject();
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("HUDSON-4192.zip")));
        buildAndAssertSuccess(m);
    }
    
    /**
     * {@link Result} getting set to SUCCESS even if there's a test failure, when the test failure
     * does not happen in the final task segment.
     */
    @Bug(4177)
    public void testTestFailureInEarlyTaskSegment() throws Exception {
        configureDefaultMaven();
        MavenModuleSet m = createMavenProject();
        m.setGoals("clean install findbugs:findbugs");
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-test-failure-findbugs.zip")));
        assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());
    }
    
    /**
     * Verify that a compilation error properly shows up as a failure.
     */
    public void testCompilationFailure() throws Exception {
        configureDefaultMaven();
        MavenModuleSet m = createMavenProject();
        m.setGoals("clean install");
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-compilation-failure.zip")));
        assertBuildStatus(Result.FAILURE, m.scheduleBuild2(0).get());
    }
    
    /**
     * Workspace determination problem on non-aggregator style build.
     */
    @Bug(4226)
    public void testParallelModuleBuild() throws Exception {
        configureDefaultMaven();
        MavenModuleSet m = createMavenProject();
        m.setScm(new ExtractResourceSCM(getClass().getResource("multimodule-maven.zip")));
        
        buildAndAssertSuccess(m);

        m.setAggregatorStyleBuild(false);

        // run module builds
        buildAndAssertSuccess(m.getModule("test$module1"));
        buildAndAssertSuccess(m.getModule("test$module1"));
    }
    
    @Bug(value=8395)
    public void testMaven2BuildWrongScope() throws Exception {
        
        File pom = new File(this.getClass().getResource("test-pom-8395.xml").toURI());
        MavenModuleSet m = createMavenProject();
        MavenInstallation mavenInstallation = configureDefaultMaven();
        m.setMaven( mavenInstallation.getName() );
        m.getReporters().add(new TestReporter());
        m.setRootPOM(pom.getAbsolutePath());
        m.setGoals( "clean validate" );
        MavenModuleSetBuild mmsb =  buildAndAssertSuccess(m);
        assertFalse( mmsb.getProject().getModules().isEmpty());
    }    
    
    @Bug(value=8390)
    public void testMaven2BuildWrongInheritence() throws Exception {
        
        MavenModuleSet m = createMavenProject();
        MavenInstallation mavenInstallation = configureDefaultMaven();
        m.setMaven( mavenInstallation.getName() );
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("incorrect-inheritence-testcase.zip")));
        m.setGoals( "clean validate" );
        MavenModuleSetBuild mmsb =  buildAndAssertSuccess(m);
        assertFalse( mmsb.getProject().getModules().isEmpty());
    }   

    @Bug(value=8445)
    public void testMaven2SeveralModulesInDirectory() throws Exception {
        
        MavenModuleSet m = createMavenProject();
        MavenInstallation mavenInstallation = configureDefaultMaven();
        m.setMaven( mavenInstallation.getName() );
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("several-modules-in-directory.zip")));
        m.setGoals( "clean validate" );
        MavenModuleSetBuild mmsb =  buildAndAssertSuccess(m);
        assertFalse( mmsb.getProject().getModules().isEmpty());
    }    

    @Email("https://groups.google.com/d/msg/hudson-users/Xhw00UopVN0/FA9YqDAIsSYJ")
    public void testMavenWithDependencyVersionInEnvVar() throws Exception {
        
        MavenModuleSet m = createMavenProject();
        MavenInstallation mavenInstallation = configureDefaultMaven();
        ParametersDefinitionProperty parametersDefinitionProperty = 
            new ParametersDefinitionProperty(new StringParameterDefinition( "JUNITVERSION", "3.8.2" ));
        
        m.addProperty( parametersDefinitionProperty );
        m.setMaven( mavenInstallation.getName() );
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("envars-maven-project.zip")));
        m.setGoals( "clean test-compile" );
        MavenModuleSetBuild mmsb =  buildAndAssertSuccess(m);
        assertFalse( mmsb.getProject().getModules().isEmpty());
    }     
    
    private static class TestReporter extends MavenReporter {
        @Override
        public boolean end(MavenBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            assertNotNull(build.getProject().getWorkspace());
            assertNotNull(build.getWorkspace());
            return true;
        }
    }    
    
}
