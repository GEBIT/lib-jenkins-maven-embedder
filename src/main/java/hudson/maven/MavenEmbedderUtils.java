package hudson.maven;

/*
 * Olivier Lamy
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.apache.maven.cli.internal.BootstrapCoreExtensionManager;
import org.apache.maven.cli.internal.extension.model.CoreExtension;
import org.apache.maven.cli.internal.extension.model.io.xpp3.CoreExtensionsXpp3Reader;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.scope.internal.MojoExecutionScopeModule;
import org.apache.maven.extension.internal.CoreExports;
import org.apache.maven.extension.internal.CoreExtensionEntry;
import org.apache.maven.model.Profile;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.session.scope.internal.SessionScopeModule;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.tools.ant.AntClassLoader;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

import org.codehaus.plexus.classworlds.realm.DuplicateRealmException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;

/**
 * @author Olivier Lamy
 *
 */
public class MavenEmbedderUtils
{
    private static final String EXT_CLASS_PATH = "maven.ext.class.path";

    private static final String EXTENSIONS_FILENAME = ".mvn/extensions.xml";

    private static final String POM_PROPERTIES_PATH = "META-INF/maven/org.apache.maven/maven-core/pom.properties";

    private MavenEmbedderUtils() {
        // no op only to prevent construction
    }

    /**
     * <p>
     * build a {@link ClassRealm} with all jars in mavenHome/lib/*.jar
     * </p>
     * <p>
     * the {@link ClassRealm} is ChildFirst with the current classLoader as parent.
     * </p>
     * @param mavenHome cannot be <code>null</code>
     * @param world can be <code>null</code>
     * @return
     */
    @SuppressFBWarnings("DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED")
    public static ClassRealm buildClassRealm(File mavenHome, ClassWorld world, ClassLoader parentClassLoader )
        throws MavenEmbedderException {

        if ( mavenHome == null ) {
            throw new IllegalArgumentException( "mavenHome cannot be null" );
        }
        if ( !mavenHome.exists() ) {
            throw new IllegalArgumentException( "mavenHome '" + mavenHome.getPath() + "' doesn't seem to exist on this node (or you don't have sufficient rights to access it)" );
        }

        // list all jar under mavenHome/lib

        File libDirectory = new File( mavenHome, "lib" );
        if ( !libDirectory.exists() ) {
            throw new IllegalArgumentException( mavenHome.getPath() + " doesn't have a 'lib' subdirectory - thus cannot be a valid maven installation!" );
        }

        File[] jarFiles = libDirectory.listFiles( ( dir, name ) ->  name.endsWith( ".jar" ));

        AntClassLoader antClassLoader = new AntClassLoader( Thread.currentThread().getContextClassLoader(), false );

        if(jarFiles!=null) {
            for ( File jarFile : jarFiles ) {
                antClassLoader.addPathComponent( jarFile );
            }
        }
        if (world == null) {
            world = new ClassWorld();
        }

        ClassRealm classRealm = new ClassRealm( world, "plexus.core", parentClassLoader == null ? antClassLoader : parentClassLoader );

        if(jarFiles!=null) {
            for ( File jarFile : jarFiles ) {
                try {
                    classRealm.addURL( jarFile.toURI().toURL() );
                } catch ( MalformedURLException e ) {
                    throw new MavenEmbedderException( e.getMessage(), e );
                }
            }
        }
        return classRealm;
    }

    public static PlexusContainer buildPlexusContainer(File mavenHome, MavenRequest mavenRequest) throws MavenEmbedderException {
        ClassWorld world = new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader());

        ClassRealm classRealm = MavenEmbedderUtils.buildClassRealm( mavenHome, world, Thread.currentThread().getContextClassLoader() );

        DefaultContainerConfiguration conf = new DefaultContainerConfiguration();

        conf.setContainerConfigurationURL( mavenRequest.getOverridingComponentsXml() )
            .setRealm( classRealm )
            .setClassWorld( world )
            .setClassPathScanning( mavenRequest.getContainerClassPathScanning() )
            .setComponentVisibility( mavenRequest.getContainerComponentVisibility() );

        return buildPlexusContainer(mavenHome, mavenRequest, conf);
    }

    /**
     * used by PomParser in Jenkins
     * @param mavenClassLoader
     * @param parent
     * @param mavenRequest
     * @return
     * @throws MavenEmbedderException
     */
    @SuppressFBWarnings("DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED")
    public static PlexusContainer buildPlexusContainer(ClassLoader mavenClassLoader, ClassLoader parent, MavenRequest mavenRequest) throws MavenEmbedderException {
        DefaultContainerConfiguration conf = new DefaultContainerConfiguration();

        conf.setAutoWiring( mavenRequest.isContainerAutoWiring() )
            .setClassPathScanning( mavenRequest.getContainerClassPathScanning() )
            .setComponentVisibility( mavenRequest.getContainerComponentVisibility() )
            .setContainerConfigurationURL( mavenRequest.getOverridingComponentsXml() );
        ClassWorld classWorld = new ClassWorld( "plexus.core",
                parent == null ? Thread.currentThread().getContextClassLoader() : parent );

        ClassRealm classRealm = new ClassRealm( classWorld, "maven", mavenClassLoader );
        classRealm.setParentRealm(classWorld.getRealms().iterator().next());
        conf.setRealm( classRealm );

        conf.setClassWorld( classWorld );

        return buildPlexusContainer(null, mavenRequest, conf);
    }

    private static PlexusContainer buildPlexusContainer(File mavenHome, MavenRequest mavenRequest,
            ContainerConfiguration containerConfiguration) throws MavenEmbedderException {
        try
        {
            ClassRealm coreRealm = containerConfiguration.getClassWorld().getClassRealm( "plexus.core" );
            if ( coreRealm == null )
            {
                coreRealm = containerConfiguration.getClassWorld().getRealms().iterator().next();
            }

            CoreExtensionEntry coreEntry = CoreExtensionEntry.discoverFrom( coreRealm );
            List<CoreExtensionEntry> extensions =
                loadCoreExtensions( mavenHome, mavenRequest, coreRealm, coreEntry.getExportedArtifacts() );

            List<File> extClassPath = parseExtClasspath( mavenRequest );
            ClassRealm containerRealm = setupContainerRealm( containerConfiguration.getClassWorld(), coreRealm, extClassPath, extensions );
            ContainerConfiguration containerConf = containerConfiguration.setRealm(containerRealm);

            Set<String> exportedArtifacts = new HashSet<String>( coreEntry.getExportedArtifacts() );
            Set<String> exportedPackages = new HashSet<String>( coreEntry.getExportedPackages() );
            for ( CoreExtensionEntry extension : extensions )
            {
                exportedArtifacts.addAll( extension.getExportedArtifacts() );
                exportedPackages.addAll( extension.getExportedPackages() );
            }

            final CoreExports exports = new CoreExports( containerRealm, exportedArtifacts, exportedPackages );

            DefaultPlexusContainer plexusContainer = new DefaultPlexusContainer( containerConf, new AbstractModule()
            {
                @Override
                protected void configure()
                {
                    bind( ILoggerFactory.class ).toInstance( LoggerFactory.getILoggerFactory() );
                    bind( CoreExports.class ).toInstance( exports );
                }
            } );

            if (mavenRequest.getMavenLoggerManager() != null) {
                plexusContainer.setLoggerManager( mavenRequest.getMavenLoggerManager() );
            }
            if (mavenRequest.getLoggingLevel() > 0) {
                plexusContainer.getLoggerManager().setThreshold( mavenRequest.getLoggingLevel() );
            }

            // NOTE: To avoid inconsistencies, we'll use the TCCL exclusively for lookups
            plexusContainer.setLookupRealm( null );
            Thread.currentThread().setContextClassLoader( plexusContainer.getContainerRealm() );
            for ( CoreExtensionEntry extension : extensions )
            {
                plexusContainer.discoverComponents( extension.getClassRealm(), new SessionScopeModule( plexusContainer ),
                                              new MojoExecutionScopeModule( plexusContainer ) );
            }

            return plexusContainer;
        } catch ( PlexusContainerException e ) {
            throw new MavenEmbedderException( e.getMessage(), e );
        } catch (ComponentLookupException e) {
            throw new MavenEmbedderException( e.getMessage(), e );
        } catch (DuplicateRealmException e) {
            throw new MavenEmbedderException( e.getMessage(), e );
        } catch (MalformedURLException e) {
            throw new MavenEmbedderException( e.getMessage(), e );
        }
    }

    /**
     * @param mavenHome Maven Home directory
     * @return the maven version
     * @throws MavenEmbedderException Operation failure
     */
    public static MavenInformation getMavenVersion(@Nonnull File mavenHome) throws MavenEmbedderException {
        return getMavenVersion(mavenHome, null);
    }

    /*package*/ static MavenInformation getMavenVersion(@Nonnull File mavenHome,
            @CheckForNull MavenEmbedderCallable preopertiesPreloadHook) throws MavenEmbedderException {

        MavenInformation information = null;
        ClassLoader original = null;
        //ClassRealm realm = null;
        try (ClassRealm realm = buildClassRealm( mavenHome, null, null )) {
            //realm = buildClassRealm( mavenHome, null, null );
            if (debug) {
                debugMavenVersion(realm);
            }
            original = Thread.currentThread().getContextClassLoader();

            Thread.currentThread().setContextClassLoader( realm );
            // TODO is this really intending to use findResource rather than getResource? Cf. https://github.com/sonatype/plexus-classworlds/pull/8
            URL resource = realm.findResource( POM_PROPERTIES_PATH );
            if (resource == null) {
                throw new MavenEmbedderException("Couldn't find maven version information in '" + mavenHome.getPath()
                        + "'. Are you sure that this is a valid maven home?");
            }
            URLConnection uc = resource.openConnection();
            uc.setUseCaches(false);
            try(InputStream istream = uc.getInputStream()) {
                if (preopertiesPreloadHook != null) {
                    preopertiesPreloadHook.call();
                }
                Properties properties = new Properties();
                properties.load( istream );
                information = new MavenInformation( properties.getProperty( "version" ) , resource.toExternalForm() );
            }
        } catch ( IOException e ) {
            throw new MavenEmbedderException( e.getMessage(), e );
        } finally {
            Thread.currentThread().setContextClassLoader( original );
        }

        return information;
    }

    public static boolean isAtLeastMavenVersion(File mavenHome, String version)  throws MavenEmbedderException {
        ComparableVersion found = new ComparableVersion( getMavenVersion( mavenHome ).getVersion() );
        ComparableVersion testedOne = new ComparableVersion( version );
        return found.compareTo( testedOne ) >= 0;
    }

    private static void debugMavenVersion(ClassRealm realm ) {
        try {
            // TODO as above, consider getResources
            @SuppressWarnings("unchecked")
            Enumeration<URL> urls = realm.findResources( POM_PROPERTIES_PATH );
            System.out.println("urls for " + POM_PROPERTIES_PATH );
            while(urls.hasMoreElements()) {
                System.out.println("url " + urls.nextElement().toExternalForm());
            }
        } catch (IOException e) {
            System.out.println("Ignore IOException during searching " + POM_PROPERTIES_PATH + ":" + e.getMessage());
        }
    }

    public static final boolean debug = Boolean.getBoolean( "hudson.maven.MavenEmbedderUtils.debug" );


    private static List<CoreExtensionEntry> loadCoreExtensions(File mavenHome, MavenRequest mavenRequest,
            ClassRealm containerRealm, Set<String> providedArtifacts) {
        File extensionsFile = new File(mavenRequest.getBaseDirectory(), EXTENSIONS_FILENAME);
        if (!extensionsFile.isFile()) {
            return Collections.emptyList();
        }

        try {
            List<CoreExtension> extensions = readCoreExtensionsDescriptor(extensionsFile);
            if (extensions.isEmpty()) {
                return Collections.emptyList();
            }
            ContainerConfiguration cc = new DefaultContainerConfiguration() //
                    .setClassWorld(containerRealm.getWorld()) //
                    .setRealm(containerRealm) //
                    .setClassPathScanning(PlexusConstants.SCANNING_INDEX) //
                    .setAutoWiring(true) //
                    .setJSR250Lifecycle(true) //
                    .setName("maven");

            DefaultPlexusContainer container = new DefaultPlexusContainer(cc, new AbstractModule() {

                @Override
                protected void configure() {
                    bind(ILoggerFactory.class).toInstance(LoggerFactory.getILoggerFactory());
                }
            });

            try {
                container.setLookupRealm(containerRealm);

                if (mavenRequest.getMavenLoggerManager() != null) {
                    container.setLoggerManager(mavenRequest.getMavenLoggerManager());
                }
                if (mavenRequest.getLoggingLevel() > 0) {
                    container.getLoggerManager().setThreshold(mavenRequest.getLoggingLevel());
                }

                MavenExecutionRequest request = buildMavenExecutionRequest(mavenHome, container, mavenRequest);

                BootstrapCoreExtensionManager resolver = container.lookup(BootstrapCoreExtensionManager.class);

                return resolver.loadCoreExtensions(request, providedArtifacts, extensions);
            } finally {
                container.dispose();
            }
        } catch (RuntimeException e) {
            // runtime exceptions are most likely bugs in maven, let them bubble up to the
            // user
            throw e;
        } catch (Exception e) {
            // slf4jLogger.warn("Failed to read extensions descriptor " + extensionsFile +
            // ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private static List<CoreExtension> readCoreExtensionsDescriptor(File extensionsFile)
            throws IOException, XmlPullParserException {
        CoreExtensionsXpp3Reader parser = new CoreExtensionsXpp3Reader();

        InputStream is = new BufferedInputStream(new FileInputStream(extensionsFile));
        try {
            return parser.read(is).getExtensions();
        } finally {
            is.close();
        }

    }

    public static MavenExecutionRequest buildMavenExecutionRequest(File mavenHome, PlexusContainer plexusContainer,
            MavenRequest mavenRequest) throws MavenEmbedderException, ComponentLookupException {
        MavenExecutionRequest mavenExecutionRequest = new DefaultMavenExecutionRequest();

        if (mavenRequest.getGlobalSettingsFile() != null) {
            mavenExecutionRequest.setGlobalSettingsFile(new File(mavenRequest.getGlobalSettingsFile()));
        }

        if (mavenExecutionRequest.getUserSettingsFile() != null) {
            mavenExecutionRequest.setUserSettingsFile(new File(mavenRequest.getUserSettingsFile()));
        }

        Settings settings = getSettings(plexusContainer, mavenRequest);
        try {
            plexusContainer.lookup(MavenExecutionRequestPopulator.class).populateFromSettings(mavenExecutionRequest,
                    settings);

            plexusContainer.lookup(MavenExecutionRequestPopulator.class).populateDefaults(mavenExecutionRequest);
        } catch (MavenExecutionRequestPopulationException e) {
            throw new MavenEmbedderException(e.getMessage(), e);
        }

        ArtifactRepository localRepository = getLocalRepository(plexusContainer, settings, mavenRequest);
        mavenExecutionRequest.setLocalRepository(localRepository);
        mavenExecutionRequest.setLocalRepositoryPath(localRepository.getBasedir());
        mavenExecutionRequest.setOffline(mavenRequest.isOffline());

        mavenExecutionRequest.setUpdateSnapshots(mavenRequest.isUpdateSnapshots());

        // TODO check null and create a console one ?
        mavenExecutionRequest.setTransferListener(mavenRequest.getTransferListener());

        mavenExecutionRequest.setCacheNotFound(mavenRequest.isCacheNotFound());
        mavenExecutionRequest.setCacheTransferError(true);

        mavenExecutionRequest.setUserProperties(mavenRequest.getUserProperties());
        mavenExecutionRequest.getSystemProperties().putAll(System.getProperties());
        if (mavenRequest.getSystemProperties() != null) {
            mavenExecutionRequest.getSystemProperties().putAll(mavenRequest.getSystemProperties());
        }
        mavenExecutionRequest.getSystemProperties().putAll(getEnvVars());

        if (mavenHome != null) {
            mavenExecutionRequest.getSystemProperties().put("maven.home", mavenHome.getAbsolutePath());
        }

        if (mavenRequest.getProfiles() != null && !mavenRequest.getProfiles().isEmpty()) {
            for (String id : mavenRequest.getProfiles()) {
                Profile p = new Profile();
                p.setId(id);
                p.setSource("cli");
                mavenExecutionRequest.addProfile(p);
                mavenExecutionRequest.addActiveProfile(id);
            }
        }

        mavenExecutionRequest.setLoggingLevel(mavenRequest.getLoggingLevel());

        plexusContainer.lookup(Logger.class).setThreshold(mavenRequest.getLoggingLevel());

        mavenExecutionRequest.setExecutionListener(mavenRequest.getExecutionListener())
                .setInteractiveMode(mavenRequest.isInteractive())
                .setGlobalChecksumPolicy(mavenRequest.getGlobalChecksumPolicy()).setGoals(mavenRequest.getGoals());

        if (mavenRequest.getPom() != null) {
            File pomFile = new File(mavenRequest.getPom());
            mavenExecutionRequest.setPom(pomFile);
        }
        mavenExecutionRequest.setBaseDirectory(new File(mavenRequest.getBaseDirectory()));

        if (mavenRequest.getWorkspaceReader() != null) {
            mavenExecutionRequest.setWorkspaceReader(mavenRequest.getWorkspaceReader());
        }

        // FIXME inactive profiles

        // this.mavenExecutionRequest.set

        return mavenExecutionRequest;

    }

    public static ArtifactRepository getLocalRepository(PlexusContainer plexusContainer, Settings settings, MavenRequest mavenRequest) throws ComponentLookupException {
        try {
            String localRepositoryPath = getLocalRepositoryPath(settings, mavenRequest);
            if ( localRepositoryPath != null ) {
                return plexusContainer.lookup( RepositorySystem.class ).createLocalRepository( new File( localRepositoryPath ) );
            }
            return plexusContainer.lookup( RepositorySystem.class ).createLocalRepository( RepositorySystem.defaultUserLocalRepository );
        } catch ( InvalidRepositoryException e ) {
            // never happened
            throw new IllegalStateException( e );
        }
    }

    public static String getLocalRepositoryPath(Settings settings, MavenRequest mavenRequest) {
        String path = null;
        if (settings != null) {
            path = settings.getLocalRepository();
        }
        if ( mavenRequest.getLocalRepositoryPath() != null ) {
            path = mavenRequest.getLocalRepositoryPath();
        }

        if ( path == null ) {
            path = RepositorySystem.defaultUserLocalRepository.getAbsolutePath();
        }
        return path;
    }


    public static Settings getSettings(PlexusContainer plexusContainer, MavenRequest mavenRequest)
        throws MavenEmbedderException, ComponentLookupException {

        SettingsBuildingRequest settingsBuildingRequest = new DefaultSettingsBuildingRequest();
        if ( mavenRequest.getGlobalSettingsFile() != null ) {
            settingsBuildingRequest.setGlobalSettingsFile( new File( mavenRequest.getGlobalSettingsFile() ) );
        } else {
            settingsBuildingRequest.setGlobalSettingsFile( SettingsXmlConfigurationProcessor.DEFAULT_GLOBAL_SETTINGS_FILE );
        }
        if ( mavenRequest.getUserSettingsFile() != null ) {
            settingsBuildingRequest.setUserSettingsFile( new File( mavenRequest.getUserSettingsFile() ) );
        } else {
            settingsBuildingRequest.setUserSettingsFile( SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE );
        }

        settingsBuildingRequest.setUserProperties( mavenRequest.getUserProperties() );
        settingsBuildingRequest.getSystemProperties().putAll( System.getProperties() );
        settingsBuildingRequest.getSystemProperties().putAll( mavenRequest.getSystemProperties() );
        settingsBuildingRequest.getSystemProperties().putAll( getEnvVars() );

        try {
            return plexusContainer.lookup( SettingsBuilder.class ).build( settingsBuildingRequest ).getEffectiveSettings();
        } catch ( SettingsBuildingException e ) {
            throw new MavenEmbedderException( e.getMessage(), e );
        }
    }


    public static Properties getEnvVars( ) {
        Properties envVars = new Properties();
        boolean caseSensitive = !Os.isFamily( Os.FAMILY_WINDOWS );
        for ( Map.Entry<String, String> entry : System.getenv().entrySet() )
        {
            String key = "env." + ( caseSensitive ? entry.getKey() : entry.getKey().toUpperCase( Locale.ENGLISH ) );
            envVars.setProperty( key, entry.getValue() );
        }
        return envVars;
    }

    private static List<File> parseExtClasspath( MavenRequest mavenRequest )
    {
        String extClassPath = mavenRequest.getUserProperties().getProperty( EXT_CLASS_PATH );
        if ( extClassPath == null )
        {
            extClassPath = mavenRequest.getSystemProperties().getProperty( EXT_CLASS_PATH );
        }

        List<File> jars = new ArrayList<File>();

        if ( StringUtils.isNotEmpty( extClassPath ) )
        {
            for ( String jar : StringUtils.split( extClassPath, File.pathSeparator ) )
            {
                File file = resolveFile( new File( jar ), mavenRequest.getBaseDirectory() );

                // slf4jLogger.debug( "  Included " + file );

                jars.add( file );
            }
        }
        return jars;
    }

    private static File resolveFile( File file, String workingDirectory )
    {
        if ( file == null )
        {
            return null;
        }
        else if ( file.isAbsolute() )
        {
            return file;
        }
        else if ( file.getPath().startsWith( File.separator ) )
        {
            // drive-relative Windows path
            return file.getAbsoluteFile();
        }
        else
        {
            return new File( workingDirectory, file.getPath() ).getAbsoluteFile();
        }
    }

        private static ClassRealm setupContainerRealm(ClassWorld classWorld, ClassRealm coreRealm, List<File> extClassPath,
                        List<CoreExtensionEntry> extensions) throws DuplicateRealmException, MalformedURLException {
                if (!extClassPath.isEmpty() || !extensions.isEmpty()) {
                        ClassRealm extRealm = classWorld.newRealm("maven.ext", null);

                        extRealm.setParentRealm(coreRealm);

                        // TODO slf4jLogger.debug("Populating class realm " + extRealm.getId());

                        for (File file : extClassPath) {
                                // TODO slf4jLogger.debug("  Included " + file);

                                extRealm.addURL(file.toURI().toURL());
                        }

                        for (CoreExtensionEntry entry : reverse(extensions)) {
                                Set<String> exportedPackages = entry.getExportedPackages();
                                ClassRealm realm = entry.getClassRealm();
                                for (String exportedPackage : exportedPackages) {
                                        extRealm.importFrom(realm, exportedPackage);
                                }
                                if (exportedPackages.isEmpty()) {
                                        // sisu uses realm imports to establish component visibility
                                        extRealm.importFrom(realm, realm.getId());
                                }
                        }

                        return extRealm;
                }

                return coreRealm;
        }

        private static <T> List<T> reverse(List<T> list) {
                List<T> copy = new ArrayList<T>(list);
                Collections.reverse(copy);
                return copy;
        }
}
