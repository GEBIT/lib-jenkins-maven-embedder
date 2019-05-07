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

import java.io.File;
import java.util.Arrays;

import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;

import junit.framework.Assert;
import junit.framework.TestCase;

/**
 * @author olamy
 *
 */
public class TestMavenEmbedderCoreExtensions extends TestCase {

    public void testCoreExtensionProjectRead() throws Exception {
        MavenRequest mavenRequest = new MavenRequest();
        mavenRequest.setPom(new File("src/test/projects-tests/core-extension/pom.xml").getAbsolutePath());

        mavenRequest.setLocalRepositoryPath(System.getProperty("localRepository", "./target/repo-maven"));

        mavenRequest.setBaseDirectory(new File("src/test/projects-tests/core-extension").getAbsolutePath());
        MavenEmbedder mavenEmbedder = new MavenEmbedder(Thread.currentThread().getContextClassLoader(), mavenRequest);
        // new MavenEmbedder( new File( System.getProperty( "maven.home" ) ),
        // mavenRequest );

        MavenProject project = mavenEmbedder.readProject(new File("src/test/projects-tests/core-extension/pom.xml"));
        Assert.assertEquals("my-app", project.getArtifactId());
        LocalRepositoryManagerFactory localRepoManagerFactory = mavenEmbedder.getPlexusContainer()
                .lookup(LocalRepositoryManagerFactory.class);
        Assert.assertEquals("io.takari.aether.localrepo.TakariLocalRepositoryManagerFactory",
                localRepoManagerFactory.getClass().getName());
    }

    public void testCoreExtensionLifecycle() throws Exception {
        MavenRequest mavenRequest = new MavenRequest();
        mavenRequest
                .setUserSettingsFile(new File(System.getProperty("user.home"), ".m2/settings.xml").getAbsolutePath());
        mavenRequest.setLocalRepositoryPath(System.getProperty("localRepository", "./target/repo-maven"));
        mavenRequest.setPom(new File("src/test/projects-tests/sample-core-extension/pom.xml").getAbsolutePath());
        mavenRequest.setGoals(Arrays.asList("clean", "install"));

        // mavenRequest.setBaseDirectory( new File(
        // "src/test/projects-tests/scm-git-test-one-module" ).getAbsolutePath() );
        MavenEmbedder mavenEmbedder = new MavenEmbedder(Thread.currentThread().getContextClassLoader(), mavenRequest);
        MavenExecutionResult result = mavenEmbedder.execute(mavenRequest);
        assertTrue(result.getExceptions().isEmpty());

        mavenRequest = new MavenRequest();
        mavenRequest
                .setUserSettingsFile(new File(System.getProperty("user.home"), ".m2/settings.xml").getAbsolutePath());
        mavenRequest.setLocalRepositoryPath(System.getProperty("localRepository", "./target/repo-maven"));
        mavenRequest.setPom(new File("src/test/projects-tests/core-extension-lifecycle/pom.xml").getAbsolutePath());
        mavenRequest.setBaseDirectory(new File("src/test/projects-tests/core-extension-lifecycle").getAbsolutePath());

        mavenEmbedder = new MavenEmbedder(Thread.currentThread().getContextClassLoader(), mavenRequest);

        MavenProject project = mavenEmbedder
                .readProject(new File("src/test/projects-tests/core-extension-lifecycle/pom.xml"));
        Assert.assertEquals("my-app-lifecycle", project.getArtifactId());
        Assert.assertEquals("1.0.0.sample", project.getVersion());
    }

    public void testCoreExtensionLifecycleBuild() throws Exception {
        MavenRequest mavenRequest = new MavenRequest();
        mavenRequest
                .setUserSettingsFile(new File(System.getProperty("user.home"), ".m2/settings.xml").getAbsolutePath());
        mavenRequest.setLocalRepositoryPath(System.getProperty("localRepository", "./target/repo-maven"));
        mavenRequest.setPom(new File("src/test/projects-tests/sample-core-extension/pom.xml").getAbsolutePath());
        mavenRequest.setGoals(Arrays.asList("clean", "install"));

        // mavenRequest.setBaseDirectory( new File(
        // "src/test/projects-tests/scm-git-test-one-module" ).getAbsolutePath() );
        MavenEmbedder mavenEmbedder = new MavenEmbedder(Thread.currentThread().getContextClassLoader(), mavenRequest);
        MavenExecutionResult result = mavenEmbedder.execute(mavenRequest);
        assertTrue(result.getExceptions().isEmpty());

        mavenRequest = new MavenRequest();
        mavenRequest
                .setUserSettingsFile(new File(System.getProperty("user.home"), ".m2/settings.xml").getAbsolutePath());
        mavenRequest.setLocalRepositoryPath(System.getProperty("localRepository", "./target/repo-maven"));
        mavenRequest.setPom(new File("src/test/projects-tests/core-extension-lifecycle/pom.xml").getAbsolutePath());
        mavenRequest.setBaseDirectory(new File("src/test/projects-tests/core-extension-lifecycle").getAbsolutePath());
        mavenRequest.setGoals(Arrays.asList("clean", "package"));

        mavenEmbedder = new MavenEmbedder(Thread.currentThread().getContextClassLoader(), mavenRequest);

        result = mavenEmbedder.execute(mavenRequest);
        assertTrue(result.getExceptions().isEmpty());

        Assert.assertEquals("1.0.0.sample", result.getProject().getVersion());
    }
}
