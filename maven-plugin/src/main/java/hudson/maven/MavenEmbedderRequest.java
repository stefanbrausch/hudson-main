/**
 * 
 */
package hudson.maven;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import hudson.model.TaskListener;

import java.io.File;
import java.util.Properties;

import org.apache.maven.model.building.ModelBuildingRequest;
import org.sonatype.aether.repository.WorkspaceReader;
import org.sonatype.aether.transfer.TransferListener;

/**
 * @author Olivier Lamy
 */
public class MavenEmbedderRequest
{
    private TaskListener listener;

    private File mavenHome;

    private String profiles;

    private Properties systemProperties;

    private String privateRepository;

    private File alternateSettings;
    
    private TransferListener transferListener;
    
    /**
     * @since 1.393
     */
    private ClassLoader classLoader;
    
    /**
     * will processPlugins during project reading
     * @since 1.393
     */
    private boolean processPlugins;
    
    /**
     * will resolve dependencies during project reading
     * @since 1.393
     */    
    private boolean resolveDependencies;    

    /**
     * level of validation when reading pom (ie model building request)
     * default value : {@link ModelBuildingRequest#VALIDATION_LEVEL_MAVEN_2_0} etc...
     * @since 1.393
     */    
    private int validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0;
    
    /**
     * @since 1.393
     */
    private WorkspaceReader workspaceReader;
    
    /**
     * @param listener
     *      This is where the log messages from Maven will be recorded.
     * @param mavenHome
     *      Directory of the Maven installation. We read {@code conf/settings.xml}
     *      from here. Can be null.
     * @param profiles
     *      Profiles to activate/deactivate. Can be null.
     * @param systemProperties
     *      The system properties that the embedded Maven sees. See {@link MavenEmbedder#setSystemProperties(Properties)}.
     * @param privateRepository
     *      Optional private repository to use as the local repository.
     * @param alternateSettings
     *      Optional alternate settings.xml file.
     */
    public MavenEmbedderRequest( TaskListener listener, File mavenHome, String profiles, Properties systemProperties,
                                 String privateRepository, File alternateSettings ) {
        this.listener = listener;
        this.mavenHome = mavenHome;
        this.profiles = profiles;
        this.systemProperties = systemProperties;
        this.privateRepository = privateRepository;
        this.alternateSettings = alternateSettings;
    }

    public TaskListener getListener() {
        return listener;
    }

    public MavenEmbedderRequest setListener( TaskListener listener ) {
        this.listener = listener;
        return this;
    }

    public File getMavenHome() {
        return mavenHome;
    }

    public MavenEmbedderRequest setMavenHome( File mavenHome ) {
        this.mavenHome = mavenHome;
        return this;
    }

    public String getProfiles() {
        return profiles;
    }

    public MavenEmbedderRequest setProfiles( String profiles ) {
        this.profiles = profiles;
        return this;
    }

    public Properties getSystemProperties() {
        return systemProperties;
    }

    public MavenEmbedderRequest setSystemProperties( Properties systemProperties ) {
        this.systemProperties = systemProperties;
        return this;
    }

    public String getPrivateRepository() {
        return privateRepository;
    }

    public MavenEmbedderRequest setPrivateRepository( String privateRepository ) {
        this.privateRepository = privateRepository;
        return this;
    }

    public File getAlternateSettings() {
        return alternateSettings;
    }

    public MavenEmbedderRequest setAlternateSettings( File alternateSettings ) {
        this.alternateSettings = alternateSettings;
        return this;
    }

    public TransferListener getTransferListener() {
        return transferListener;
    }

    public MavenEmbedderRequest setTransferListener( TransferListener transferListener ) {
        this.transferListener = transferListener;
        return this;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public MavenEmbedderRequest setClassLoader( ClassLoader classLoader ) {
        this.classLoader = classLoader;
        return this;
    }

    public boolean isProcessPlugins() {
        return processPlugins;
    }

    public MavenEmbedderRequest setProcessPlugins( boolean processPlugins ) {
        this.processPlugins = processPlugins;
        return this;
    }

    public boolean isResolveDependencies() {
        return resolveDependencies;
    }

    public MavenEmbedderRequest setResolveDependencies( boolean resolveDependencies ) {
        this.resolveDependencies = resolveDependencies;
        return this;
    }

    public int getValidationLevel() {
        return validationLevel;
    }

    public MavenEmbedderRequest setValidationLevel( int validationLevel ) {
        this.validationLevel = validationLevel;
        return this;
    }

    public WorkspaceReader getWorkspaceReader() {
        return workspaceReader;
    }

    public void setWorkspaceReader( WorkspaceReader workspaceReader ) {
        this.workspaceReader = workspaceReader;
    }
}
