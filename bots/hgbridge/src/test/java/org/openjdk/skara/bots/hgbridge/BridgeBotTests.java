/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.hgbridge;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.*;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.process.Process;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.*;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BridgeBotTests {
    private List<String> runHgCommand(Repository repository, String... params) throws IOException {
        List<String> finalParams = new ArrayList<>();
        finalParams.add("hg");
        finalParams.addAll(List.of("--config", "extensions.strip="));

        finalParams.addAll(List.of(params));
        try (var p = Process.capture(finalParams.toArray(new String[0]))
                            .workdir(repository.root().toString())
                            .environ("HGRCPATH", "")
                            .environ("HGPLAIN", "")
                            .execute()) {
            return p.check().stdout();
        }
    }

    static class TestExporterConfig extends ExporterConfig {
        private boolean badAuthors = false;

        TestExporterConfig(URI source, HostedRepository destination, Path marksRepoPath) throws IOException {
            this.source(source);
            this.destinations(List.of(destination));

            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));
            var marksLocalRepo = TestableRepository.init(marksRepoPath.resolve("marks.git"), VCS.GIT);

            var initialFile = marksLocalRepo.root().resolve("init.txt");
            if (!Files.exists(initialFile)) {
                Files.writeString(initialFile, "Hello", StandardCharsets.UTF_8);
                marksLocalRepo.add(initialFile);
                var hash = marksLocalRepo.commit("First", "duke", "duke@duke.duke");
                marksLocalRepo.checkout(hash, true); // Have to move away from the master branch to allow pushes
            }

            var marksHostedRepo = new TestHostedRepository(host, "test", marksLocalRepo);
            this.marksRepo(marksHostedRepo);
            this.marksRef("master");
            this.marksAuthorName("J. Duke");
            this.marksAuthorEmail("j@duke.duke");
        }

        void setBadAuthors() {
            this.badAuthors = true;
        }

        @Override
        public Converter resolve(Path scratchPath) {
            var replacements = new HashMap<Hash, List<String>>();
            var corrections = new HashMap<Hash, Map<String, String>>();
            var lowercase = new HashSet<Hash>();
            var punctuated = new HashSet<Hash>();

            var authors = Map.of("jjg", "JJG <jjg@openjdk.org>",
                                 "duke", "Duke <duke@openjdk.org>");
            var contributors = new HashMap<String, String>();
            var sponsors = new HashMap<String, List<String>>();

            return new HgToGitConverter(replacements, corrections, lowercase, punctuated, badAuthors ? Map.of() : authors, contributors, sponsors);
        }
    }

    private Set<String> getTagNames(Repository repo) throws IOException {
        var tags = repo.tags().stream()
                       .map(Tag::name)
                       .collect(Collectors.toSet());
        if (repo.defaultTag().isPresent()) {
            tags.remove(repo.defaultTag().get().name());
        }
        return tags;
    }

    private Set<String> getCommitHashes(Repository repo) throws IOException {
        try (var commits = repo.commits()) {
            return commits.stream()
                    .map(c -> c.hash().hex())
                    .collect(Collectors.toSet());
        }
    }

    private TemporaryDirectory sourceFolder;
    private URI source;

    @BeforeAll
    void setup() throws IOException {
        // Export the beginning of the jtreg repository
        sourceFolder = new TemporaryDirectory();
        try {
            var localRepo = Repository.materialize(sourceFolder.path(), URIBuilder.base("http://hg.openjdk.org/code-tools/jtreg").build(), "default");
            runHgCommand(localRepo, "strip", "-r", "b2511c725d81");

            // Create a lockfile in the mercurial repo, as it will overwrite the existing lock in the remote git repo
            runHgCommand(localRepo, "update", "null");
            runHgCommand(localRepo, "branch", "testlock");
            var lockFile = localRepo.root().resolve("lock.txt");
            Files.writeString(lockFile, ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), StandardCharsets.UTF_8);
            localRepo.add(lockFile);
            localRepo.commit("Lock", "duke", "Duke <duke@openjdk.org>");
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Failed to connect to hg.openjdk.org - skipping tests");
        }
        this.source = sourceFolder.path().toUri();
    }

    @AfterAll
    void teardown() {
        sourceFolder.close();
    }

    @Test
    void bridgeTest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var hgFolder = new TemporaryDirectory();
             var gitFolder = new TemporaryDirectory();
             var storageFolder = new TemporaryDirectory();
             var storageFolder2 = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            // Export a partial version of a hg repository
            var localHgRepo = Repository.materialize(hgFolder.path(), source, "default");
            localHgRepo.fetch(source, "testlock");
            var destinationRepo = credentials.getHostedRepository();
            var config = new TestExporterConfig(localHgRepo.root().toUri(), destinationRepo, marksFolder.path());
            var bridge = new JBridgeBot(config, storageFolder.path());

            runHgCommand(localHgRepo, "strip", "-r", "bd7a3ed1210f");
            TestBotRunner.runPeriodicItems(bridge);

            var localGitRepo = Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");

            // Only a subset of known tags should be present
            var localGitTags = getTagNames(localGitRepo);
            assertEquals(getTagNames(localHgRepo), localGitTags);
            assertTrue(localGitTags.contains("jtreg4.1-b02"));
            assertFalse(localGitTags.contains("jtreg4.1-b05"));

            // Import more revisions into the local hg repository and export again
            localHgRepo.fetch(source, "default");
            TestBotRunner.runPeriodicItems(bridge);

            // There should now be more tags present
            Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            localGitTags = getTagNames(localGitRepo);
            assertEquals(getTagNames(localHgRepo), localGitTags);
            assertTrue(localGitTags.contains("jtreg4.1-b02"));
            assertTrue(localGitTags.contains("jtreg4.1-b05"));

            // Export it again with different storage to force an export from scratch
            bridge = new JBridgeBot(config, storageFolder2.path());
            TestBotRunner.runPeriodicItems(bridge);
            Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            var newLocalGitTags = getTagNames(localGitRepo);
            assertEquals(localGitTags, newLocalGitTags);

            // Export it once more when nothing has changed
            TestBotRunner.runPeriodicItems(bridge);
            Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            newLocalGitTags = getTagNames(localGitRepo);
            assertEquals(localGitTags, newLocalGitTags);
        }
    }

    @Test
    void bridgeCorruptedStorageHg(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var storageFolder = new TemporaryDirectory();
             var gitFolder = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            var destinationRepo = credentials.getHostedRepository();

            // Export an hg repository as is
            var config = new TestExporterConfig(source, destinationRepo, marksFolder.path());
            var bridge = new JBridgeBot(config, storageFolder.path());
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it and ensure that it contains a known commit
            var localGitRepo = Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            var localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Now corrupt the .hg folder in the permanent storage
            Files.walk(storageFolder.path())
                 .filter(p -> p.toString().contains("/.hg/"))
                 .filter(p -> p.toFile().isFile())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         throw new UncheckedIOException(e);
                     }
                 });

            // Now export it again - should still be intact
            TestBotRunner.runPeriodicItems(bridge);
            Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));
        }
    }

    @Test
    void bridgeExportScriptFailure(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var storageFolder = new TemporaryDirectory();
             var storageFolder2 = new TemporaryDirectory();
             var gitFolder = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            var destinationRepo = credentials.getHostedRepository();

            // Export an hg repository but with an empty authors list
            var config = new TestExporterConfig(source, destinationRepo, marksFolder.path());
            config.setBadAuthors();
            var badBridge = new JBridgeBot(config, storageFolder.path());
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(badBridge));

            // Now once again with a correct configuration
            config = new TestExporterConfig(source, destinationRepo, marksFolder.path());
            var goodBridge = new JBridgeBot(config, storageFolder2.path());
            TestBotRunner.runPeriodicItems(goodBridge);

            // Verify that it now contains a known commit
            var localGitRepo = Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            var localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));
        }
    }

    @Test
    void bridgeReuseMarks(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var storageFolder = new TemporaryDirectory();
             var gitFolder = new TemporaryDirectory();
             var gitFolder2 = new TemporaryDirectory();
             var gitFolder3 = new TemporaryDirectory();
             var gitFolder4 = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            var destinationRepo = credentials.getHostedRepository();
            var config = new TestExporterConfig(source, destinationRepo, marksFolder.path());

            // Export an hg repository as is
            var bridge = new JBridgeBot(config, storageFolder.path());
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it and ensure that it contains a known commit
            var localGitRepo = Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            var localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Push something else to overwrite it (but retain the lock)
            var localRepo = CheckableRepository.init(gitFolder2.path(), destinationRepo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(destinationRepo.url());

            // Materialize it again and ensure that the known commit is now gone
            localGitRepo = Repository.materialize(gitFolder3.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertFalse(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Now run the exporter again - nothing should happen
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it yet again and ensure that the known commit is still gone
            localGitRepo = Repository.materialize(gitFolder4.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertFalse(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));
        }
    }

    @Test
    void retryFailedPush(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var storageFolder = new TemporaryDirectory();
             var gitFolder = new TemporaryDirectory();
             var gitFolder2 = new TemporaryDirectory();
             var gitFolder3 = new TemporaryDirectory();
             var gitFolder4 = new TemporaryDirectory();
             var gitFolder5 = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            var destinationRepo = credentials.getHostedRepository();
            var config = new TestExporterConfig(source, destinationRepo, marksFolder.path());

            // Export an hg repository as is
            var bridge = new JBridgeBot(config, storageFolder.path());
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it and ensure that it contains a known commit
            var localGitRepo = Repository.materialize(gitFolder.path(), destinationRepo.url(), "master");
            var localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Push something else to overwrite it
            var localRepo = CheckableRepository.init(gitFolder2.path(), destinationRepo.repositoryType());
            localRepo.pushAll(destinationRepo.url());

            // Materialize it again and ensure that the known commit is now gone
            localGitRepo = Repository.materialize(gitFolder3.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertFalse(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Now run the exporter again - nothing should happen
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it yet again and ensure that the known commit is still gone
            localGitRepo = Repository.materialize(gitFolder4.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertFalse(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));

            // Remove the successful push markers
            Files.walk(storageFolder.path())
                 .filter(p -> p.toString().contains(".success.txt"))
                 .filter(p -> p.toFile().isFile())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         throw new UncheckedIOException(e);
                     }
                 });

            // Now run the exporter again - it should do the push again
            TestBotRunner.runPeriodicItems(bridge);

            // Materialize it and ensure that the known commit is back
            localGitRepo = Repository.materialize(gitFolder5.path(), destinationRepo.url(), "master");
            localGitCommits = getCommitHashes(localGitRepo);
            assertTrue(localGitCommits.contains("9cb6a5b843c0e9f6d45273a1a6f5c98979ab0766"));
        }
    }

    @Test
    void filterUnreachable(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var hgFolder = new TemporaryDirectory();
             var storageFolder = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            // Export a hg repository with unreachable commits
            var localHgRepo = Repository.materialize(hgFolder.path(), source, "default");
            localHgRepo.fetch(source, "testlock");
            var destinationRepo = credentials.getHostedRepository();
            var config = new TestExporterConfig(localHgRepo.root().toUri(), destinationRepo, marksFolder.path());
            var bridge = new JBridgeBot(config, storageFolder.path());

            runHgCommand(localHgRepo, "update", "-r", "5");
            var other = localHgRepo.root().resolve("other.txt");
            Files.writeString(other, "Hello");
            localHgRepo.add(other);
            localHgRepo.commit("Another head", "duke", "");
            runHgCommand(localHgRepo, "commit", "--close-branch", "--user=duke", "-m", "closing head");

            // Do an initial conversion, it will drop the closed head
            TestBotRunner.runPeriodicItems(bridge);

            // The second conversion should not encounter unreachable commits in the marks file
            TestBotRunner.runPeriodicItems(bridge);
        }
    }

    @Test
    void changedMarks(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var hgFolder = new TemporaryDirectory();
             var storageFolder = new TemporaryDirectory();
             var storageFolder2 = new TemporaryDirectory();
             var marksFolder = new TemporaryDirectory()) {
            // Export a hg repository
            var localHgRepo = Repository.materialize(hgFolder.path(), source, "default");
            localHgRepo.fetch(source, "testlock");
            var destinationRepo = credentials.getHostedRepository();
            var config = new TestExporterConfig(localHgRepo.root().toUri(), destinationRepo, marksFolder.path());
            var bridge = new JBridgeBot(config, storageFolder.path());

            runHgCommand(localHgRepo, "update", "-r", "5");
            var other = localHgRepo.root().resolve("other.txt");
            Files.writeString(other, "Hello");
            localHgRepo.add(other);
            localHgRepo.commit("First", "duke", "");

            // Do an initial conversion
            TestBotRunner.runPeriodicItems(bridge);

            // Now roll back and commit something else
            runHgCommand(localHgRepo, "update", "-r", "5");
            Files.writeString(other, "There");
            localHgRepo.add(other);
            localHgRepo.commit("Second", "duke", "");

            // The second conversion (with fresh storage) should detect that marks have changed
            var newBridge = new JBridgeBot(config, storageFolder2.path());
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(newBridge));
        }
    }
}
