/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.*;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MailingListBridgeBotTests {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge.test");

    private Optional<String> archiveContents(Path archive, String prId) {
        try {
            var mbox = Files.find(archive, 50, (path, attrs) -> path.toString().endsWith(".mbox"))
                            .filter(path -> path.getFileName().toString().contains(prId))
                            .findAny();
            if (mbox.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(mbox.get(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }

    }

    private boolean archiveContains(Path archive, String text) {
        return archiveContains(archive, text, "");
    }

    private boolean archiveContains(Path archive, String text, String prId) {
        return archiveContainsCount(archive, text, prId) > 0;
    }

    private int archiveContainsCount(Path archive, String text) {
        return archiveContainsCount(archive, text, "");
    }

    private int archiveContainsCount(Path archive, String text, String prId) {
        var lines = archiveContents(archive, prId);
        if (lines.isEmpty()) {
            return 0;
        }
        var pattern = Pattern.compile(text);
        int count = 0;
        for (var line : lines.get().split("\\R")) {
            var matcher = pattern.matcher(line);
            if (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    private boolean webrevContains(Path webrev, String text) {
        try {
            var index = Files.find(webrev, 5, (path, attrs) -> path.toString().endsWith("index.html")).findAny();
            if (index.isEmpty()) {
                return false;
            }
            var lines = Files.readString(index.get(), StandardCharsets.UTF_8);
            return lines.contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private long countSubstrings(String string, String substring) {
        return Pattern.compile(substring).matcher(string).results().count();
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .ignoredComments(Set.of())
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .readyLabels(Set.of("rfr"))
                                            .readyComments(Map.of(ignored.forge().currentUser().username(), Pattern.compile("ready")))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .headers(Map.of("Extra1", "val1", "Extra2", "val2"))
                                            .sendInterval(Duration.ZERO)
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should not be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.setBody("This should now be ready");
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // But it should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a general comment - not a ready marker
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.addComment("hello there");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // It should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a ready comment
            ignoredPr.addComment("ready");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This should now be ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch:"));
            assertTrue(archiveContains(archiveFolder.path(), "Changes:"));
            assertTrue(archiveContains(archiveFolder.path(), "Webrev:"));
            assertTrue(archiveContains(archiveFolder.path(), webrevServer.uri().toString()));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/00"));
            assertTrue(archiveContains(archiveFolder.path(), "Issue:"));
            assertTrue(archiveContains(archiveFolder.path(), "http://issues.test/browse/TSTPRJ-1234"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch:"));
            assertTrue(archiveContains(archiveFolder.path(), "^ - Change msg"));
            assertFalse(archiveContains(archiveFolder.path(), "With several lines"));

            // The mailing list as well
            listServer.processIncoming();
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: 1234: This is a pull request", mail.subject());
            assertEquals(pr.author().fullName(), mail.author().fullName().orElseThrow());
            assertEquals(from.address(), mail.author().address());
            assertEquals(listAddress, mail.sender());
            assertEquals("val1", mail.headerValue("Extra1"));
            assertEquals("val2", mail.headerValue("Extra2"));

            // And there should be a webrev
            Repository.materialize(webrevFolder.path(), archive.url(), "webrev");
            assertTrue(webrevContains(webrevFolder.path(), "1 lines changed"));
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Add a comment
            pr.addComment("This is a comment :smile:");

            // Add a comment from an ignored user as well
            ignoredPr.addComment("Don't mind me");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain the comment, but not the ignored one
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This should now be ready"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(2, conversations.get(0).allMessages().size());

            // Remove the rfr flag and post another comment
            pr.addLabel("rfr");
            pr.addComment("@" + pr.author().username() +" This is another comment");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain the additional comment
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is another comment"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This should now be ready"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }
            assertTrue(conversations.get(0).allMessages().get(2).body().contains("This is a comment 😄"));
        }
    }

    @Test
    void archiveIntegrated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is now ready");
            pr.addLabel("rfr");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // There should be an RFR thread
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: 1234: This is a pull request"));

            // Add a comment quickly before integration - it should not be combined with the integration message
            pr.addComment("I will now integrate this PR");

            // Mark it as integrated but skip adding the integration comment for now
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.setBody("This has been integrated");
            ignoredPr.addLabel("integrated");
            ignoredPr.setState(Issue.State.CLOSED);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // Verify that no integration message was added to the archive
            assertFalse(archiveContains(archiveFolder.path(), "Subject: Integrated:"));

            // Add the integration comment
            ignoredPr.addComment("Pushed as commit " + editHash + ".");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain another entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: RFR: 1234: This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Integrated: 1234: This is a pull request"));
            assertFalse(archiveContains(archiveFolder.path(), "\\[Closed\\]"));
        }
    }

    @Test
    void archiveLegacyIntegrated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR with a date in the past
            var editFile = tempFolder.path().resolve("change.txt");
            Files.writeString(editFile, "A simple change");
            localRepo.add(editFile);
            var commitDate = ZonedDateTime.of(2020, 3, 12, 0, 0, 0, 0, ZoneId.of("UTC"));
            var editHash = localRepo.commit("An old change", "duke", "duke@openjdk.org", commitDate,
                             "duke", "duke@openjdk.org", commitDate);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is now ready");
            pr.addLabel("rfr");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // There should be an RFR thread
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: 1234: This is a pull request"));

            // Now it has been integrated
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.setBody("This has been integrated");
            ignoredPr.addLabel("integrated");
            ignoredPr.addComment("Pushed as commit " + editHash + ".");
            ignoredPr.setState(Issue.State.CLOSED);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain another entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "\\[Integrated\\]"));
            assertFalse(archiveContains(archiveFolder.path(), "\\[Closed\\]"));
        }
    }

    @Test
    void archiveDirectToIntegrated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should not be ready");
            pr.setState(Issue.State.CLOSED);

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now it has been integrated
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.setBody("This has already been integrated");
            ignoredPr.addLabel("integrated");
            ignoredPr.addComment("Pushed as commit " + editHash + ".");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Integrated: 1234: This is a pull request"));

            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Another change");
            localRepo.push(updatedHash, author.url(), "edit");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain another entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: Integrated: 1234: This is a pull request \\[v2\\]"));
            assertFalse(archiveContains(archiveFolder.path(), "Withdrawn"));
        }
    }

    @Test
    void archiveIntegratedRetainPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .readyLabels(Set.of("rfr"))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: 1234: This is a pull request"));

            // Close it and then push another change
            pr.setState(Issue.State.CLOSED);
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Another change");
            localRepo.push(updatedHash, author.url(), "edit");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain another entry - should retain the RFR thread prefix
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: RFR: 1234: This is a pull request \\[v2\\]"));
        }
    }

    @Test
    void archiveClosed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .readyLabels(Set.of("rfr"))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: 1234: This is a pull request"));

            // Close it (as a separate user)
            var closerPr = ignored.pullRequest(pr.id());
            closerPr.setState(Issue.State.CLOSED);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain another entry - should say that it is closed
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Subject: Withdrawn: 1234: This is a pull request"));

            pr.addComment("Fair enough");

            // Run another archive pass - only a single close notice should have been posted
            TestBotRunner.runPeriodicItems(mlBot);
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Subject: Withdrawn: 1234: This is a pull request"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Subject: Re: RFR: 1234: This is a pull request"));

            // The closer should be the bot account - not the PR creator nor the closer
            assertEquals(2, archiveContainsCount(archiveFolder.path(), Pattern.quote("From: test at test.mail (User Number 2)")));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote("From: test at test.mail (test)")));
            assertEquals(0, archiveContainsCount(archiveFolder.path(), Pattern.quote("From: test at test.mail (User Number 3)")));
        }
    }

    @Test
    void archiveFailedAutoMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "Cannot automatically merge");
            pr.setBody("This is an automated merge PR");
            pr.addLabel("failed-auto-merge");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: Cannot automatically merge"));
        }
    }

    @Test
    void reviewComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // And make a file specific comment
            var currentMaster = localRepo.resolve("master").orElseThrow();
            var comment = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            // Add one from an ignored user as well
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Don't mind me");

            // Process comments
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Review comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), reviewFile + " line 2:"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());

            // Comment on the comment
            pr.addReviewCommentReply(comment, "This is a review reply");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain the additional comment (but no quoted footers)
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a review reply"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "^> PR:"));

            // As well as the mailing list
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }

            // Add a file comment (on line 0)
            var fileComment = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 0, "File review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain the additional comment
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "File review comment"));
            assertTrue(archiveContains(archiveFolder.path(), reviewFile + ":"));
        }
    }

    @Test
    void combineComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            pr.addComment("Avoid combining");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();

            // Make several file specific comments
            var first = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            var second = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Further review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Final review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a combined entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // As well as the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(3, conversations.get(0).allMessages().size());

            var commentReply = conversations.get(0).replies(mail).get(0);
            assertEquals(2, commentReply.body().split("^On.*wrote:").length);
            assertTrue(commentReply.body().contains("Avoid combining\n\n"), commentReply.body());

            var reviewReply = conversations.get(0).replies(mail).get(1);
            assertEquals(2, reviewReply.body().split("^On.*wrote:").length);
            assertEquals(2, reviewReply.body().split("> This is now ready").length, reviewReply.body());
            assertEquals("RFR: This is a pull request", reviewReply.subject());
            assertTrue(reviewReply.body().contains("Review comment\n\n"), reviewReply.body());
            assertTrue(reviewReply.body().contains("Another review comment"), reviewReply.body());

            // Now reply to the first and second (collapsed) comment
            pr.addReviewCommentReply(first, "I agree");
            pr.addReviewCommentReply(second, "Not with this one though");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a new entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(3, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // The combined review comments should only appear unquoted once
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "^Another review comment"));
        }
    }

    @Test
    void commentThreading(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            var reviewPr = reviewer.pullRequest(pr.id());
            var comment1 = reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            pr.addReviewCommentReply(comment1, "I agree");
            reviewPr.addReviewCommentReply(comment1, "Great");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // And a second one by ourselves
            var comment2 = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            reviewPr.addReviewCommentReply(comment2, "Sounds good");
            pr.addReviewCommentReply(comment2, "Thanks");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // Finally some approvals and another comment
            pr.addReview(Review.Verdict.APPROVED, "Nice");
            reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "The final review comment");
            reviewPr.addReview(Review.Verdict.APPROVED, "Looks fine");
            reviewPr.addReviewCommentReply(comment2, "You are welcome");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // Sanity check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(9, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // File specific comments should appear after the approval
            var archiveText = archiveContents(archiveFolder.path(), "").orElseThrow();
            assertTrue(archiveText.indexOf("Looks fine") < archiveText.indexOf("The final review comment"));

            // Check the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(10, conversations.get(0).allMessages().size());

            // There should be four separate threads
            var thread1 = conversations.get(0).replies(mail).get(0);
            assertEquals(2, thread1.body().split("^On.*wrote:").length);
            assertEquals(2, thread1.body().split("> This is now ready").length, thread1.body());
            assertEquals("RFR: This is a pull request", thread1.subject());
            assertTrue(thread1.body().contains("Review comment\n\n"), thread1.body());
            assertFalse(thread1.body().contains("Another review comment"), thread1.body());
            var thread1reply1 = conversations.get(0).replies(thread1).get(0);
            assertTrue(thread1reply1.body().contains("I agree"));
            assertEquals(from.address(), thread1reply1.author().address());
            assertEquals(archive.forge().currentUser().fullName(), thread1reply1.author().fullName().orElseThrow());
            var thread1reply2 = conversations.get(0).replies(thread1reply1).get(0);
            assertTrue(thread1reply2.body().contains("Great"));
            assertEquals("integrationreviewer1@openjdk.org", thread1reply2.author().address());
            assertEquals("Generated Reviewer 1", thread1reply2.author().fullName().orElseThrow());

            var thread2 = conversations.get(0).replies(mail).get(1);
            assertEquals(2, thread2.body().split("^On.*wrote:").length);
            assertEquals(2, thread2.body().split("> This is now ready").length, thread2.body());
            assertEquals("RFR: This is a pull request", thread2.subject());
            assertFalse(thread2.body().contains("Review comment\n\n"), thread2.body());
            assertTrue(thread2.body().contains("Another review comment"), thread2.body());
            var thread2reply1 = conversations.get(0).replies(thread2).get(0);
            assertTrue(thread2reply1.body().contains("Sounds good"));
            var thread2reply2 = conversations.get(0).replies(thread2reply1).get(0);
            assertTrue(thread2reply2.body().contains("Thanks"));

            var replies = conversations.get(0).replies(mail);
            var thread3 = replies.get(2);
            assertEquals("RFR: This is a pull request", thread3.subject());
            var thread4 = replies.get(3);
            assertEquals("RFR: This is a pull request", thread4.subject());
            assertTrue(thread4.body().contains("Looks fine"));
            assertTrue(thread4.body().contains("The final review comment"));
            assertTrue(thread4.body().contains("Marked as reviewed by integrationreviewer1 (Reviewer)"));
        }
    }

    @Test
    void commentThreadingSeparated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile1 = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile1);
            var reviewFile2 = Path.of("aardvark_reviewfile.txt");
            Files.writeString(localRepo.root().resolve(reviewFile2), "1\n2\n3\n4\n5\n6\n", StandardCharsets.UTF_8);
            localRepo.add(reviewFile2);
            var masterHash = localRepo.commit("Another one", "duke", "duke@openjdk.org");
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a few file specific comments
            var reviewPr = reviewer.pullRequest(pr.id());
            var comment1 = reviewPr.addReviewComment(masterHash, editHash, reviewFile1.toString(), 2, "Review comment");
            var comment2 = reviewPr.addReviewComment(masterHash, editHash, reviewFile1.toString(), 3, "Another review comment");
            var comment3 = reviewPr.addReviewComment(masterHash, editHash, reviewFile2.toString(), 4, "Yet another review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addReviewCommentReply(comment3, "I don't care");
            pr.addReviewCommentReply(comment2, "I don't agree");
            pr.addReviewCommentReply(comment1, "I agree");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Sanity check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            var archiveText = archiveContents(archiveFolder.path(), "").orElseThrow();
            assertTrue(archiveText.indexOf("I agree") < archiveText.indexOf("I don't agree"), archiveText);
            assertTrue(archiveText.indexOf("I don't care") < archiveText.indexOf("I don't agree"), archiveText);
        }
    }

    @Test
    void commentWithMention(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make two comments from different authors
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("First comment");
            pr.addComment("Second comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("@" + reviewer.forge().currentUser().username() + " reply to first");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The first comment should be quoted more often than the second
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "First comment"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Second comment"));
        }
    }

    @Test
    void reviewCommentWithMention(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make two review comments from different authors
            var reviewPr = reviewer.pullRequest(pr.id());
            var comment = reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            reviewPr.addReviewCommentReply(comment, "First review comment");
            pr.addReviewCommentReply(comment, "Second review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addReviewCommentReply(comment, "@" + reviewer.forge().currentUser().username() + " reply to first");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The first comment should be quoted more often than the second
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(3, archiveContainsCount(archiveFolder.path(), "First review comment"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Second review comment"));
        }
    }

    @Test
    void commentWithQuote(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make two comments from different authors
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("First comment\nsecond line");
            var authorPr = author.pullRequest(pr.id());
            authorPr.addComment("Second comment\nfourth line");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment(">First comm\n\nreply to first");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The first comment should be replied to once, and the original post once
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote(reviewPr.author().fullName()) + ".* wrote"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote(pr.author().fullName()) + ".* wrote"));
        }
    }

    @Test
    void reviewContext(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should only contain context up to and including Line 2
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "^> 2: Line 1$"));
            assertFalse(archiveContains(archiveFolder.path(), "^> 3: Line 2$"));
        }
    }

    @Test
    void multipleReviewContexts(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);
            var initialHash = CheckableRepository.appendAndCommit(localRepo,
                                                                  "Line 0.1\nLine 0.2\nLine 0.3\nLine 0.4\n" +
                                                                          "Line 1\nLine 2\nLine 3\nLine 4\n" +
                                                                          "Line 5\nLine 6\nLine 7\nLine 8\n" +
                                                                          "Line 8.1\nLine 8.2\nLine 8.3\nLine 8.4\n" +
                                                                          "Line 9\nLine 10\nLine 11\nLine 12\n" +
                                                                          "Line 13\nLine 14\nLine 15\nLine 16\n");
            localRepo.push(initialHash, author.url(), "master");

            // Make a change with a corresponding PR
            var current = Files.readString(localRepo.root().resolve(reviewFile), StandardCharsets.UTF_8);
            var updated = current.replaceAll("Line 2", "Line 2 edit\nLine 2.5");
            updated = updated.replaceAll("Line 13", "Line 12.5\nLine 13 edit");
            Files.writeString(localRepo.root().resolve(reviewFile), updated, StandardCharsets.UTF_8);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make file specific comments
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 7, "Review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 24, "Another review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should only contain context around line 2 and 20
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "reviewfile.txt line 7"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 6: Line 1$"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 7: Line 2 edit$"));
            assertFalse(archiveContains(archiveFolder.path(), "Line 3"));

            assertTrue(archiveContains(archiveFolder.path(), "reviewfile.txt line 24"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 23: Line 12.5$"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 24: Line 13 edit$"));
            assertFalse(archiveContains(archiveFolder.path(), "^Line 15"));
        }
    }

    @Test
    void filterComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready\n<!-- this is a comment -->\nAnd this is not\n" +
                               "<!-- Anything below this marker will be hidden -->\nStatus stuff");

            // Make a bunch of comments
            pr.addComment("Plain comment\n<!-- this is a comment -->");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment <!-- this is a comment -->\n");
            pr.addComment("  /cc others");
            pr.addComment("/integrate stuff");
            pr.addComment("the command is /hello there");
            pr.addComment("but this will be parsed\n/newline command");
            pr.addComment("/multiline\nwill be dropped");
            TestBotRunner.runPeriodicItems(mlBot);

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain the comment
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "this is a comment"));
            assertFalse(archiveContains(archiveFolder.path(), "Status stuff"));
            assertTrue(archiveContains(archiveFolder.path(), "And this is not"));
            assertFalse(archiveContains(archiveFolder.path(), "<!--"));
            assertFalse(archiveContains(archiveFolder.path(), "-->"));
            assertTrue(archiveContains(archiveFolder.path(), "Plain comment"));
            assertTrue(archiveContains(archiveFolder.path(), "Review comment"));
            assertFalse(archiveContains(archiveFolder.path(), "/integrate"));
            assertFalse(archiveContains(archiveFolder.path(), "/cc"));
            assertTrue(archiveContains(archiveFolder.path(), "/hello there"));
            assertTrue(archiveContains(archiveFolder.path(), "but this will be parsed"));
            assertFalse(archiveContains(archiveFolder.path(), "/newline"));
            assertFalse(archiveContains(archiveFolder.path(), "/multiline"));
            assertFalse(archiveContains(archiveFolder.path(), "will be dropped"));

            // There should not be consecutive empty lines due to a filtered multiline message
            var lines = archiveContents(archiveFolder.path(), "").orElseThrow();
            assertFalse(lines.contains("\n\n\n"), lines);

            // And a stand-alone multiline comment should not cause another mail to be sent
            pr.addComment("/another\nmultiline\nwill not cause another mail");
            TestBotRunner.runPeriodicItems(mlBot);
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            lines = archiveContents(archiveFolder.path(), "").orElseThrow();
            var mails = Mbox.splitMbox(lines, EmailAddress.from("duke@openjdk.org"));
            assertEquals(2, mails.size());
        }
    }

    @Test
    void incrementalChanges(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var nextHash = CheckableRepository.appendAndCommit(localRepo, "Yet one more line", "Fixing");
            localRepo.push(nextHash, author.url(), "edit");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the updated push
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "has updated the pull request incrementally"));
            assertTrue(archiveContains(archiveFolder.path(), "full.*/" + pr.id() + "/01"));
            assertTrue(archiveContains(archiveFolder.path(), "inc.*/" + pr.id() + "/00-01"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fixing"));

            // The webrev comment should be updated
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains("Full"))
                                         .filter(comment -> comment.body().contains("Incremental"))
                                         .filter(comment -> comment.body().contains(nextHash.hex()))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }

            // Add a comment
            var commenterPr = commenter.pullRequest(pr.id());
            commenterPr.addReviewComment(masterHash, nextHash, reviewFile.toString(), 2, "Review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Ensure that additional updates are only reported once
            for (int i = 0; i < 3; ++i) {
                var anotherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fixing");
                localRepo.push(anotherHash, author.url(), "edit");

                TestBotRunner.runPeriodicItems(mlBot);
                TestBotRunner.runPeriodicItems(mlBot);
                listServer.processIncoming();
            }
            var updatedConversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, updatedConversations.size());
            var conversation = updatedConversations.get(0);
            assertEquals(6, conversation.allMessages().size());
            assertEquals("RFR: This is a pull request [v2]", conversation.allMessages().get(1).subject());
            assertEquals("RFR: This is a pull request [v2]", conversation.allMessages().get(2).subject(), conversation.allMessages().get(2).toString());
            assertEquals("RFR: This is a pull request [v5]", conversation.allMessages().get(5).subject());
        }
    }

    @Test
    void forcePushed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository("author");
            var main = credentials.getHostedRepository("main");
            var archive = credentials.getHostedRepository("archive");
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var sender = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(sender)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, main.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line", "Original msg");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, main, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var newLocalRepo = Repository.materialize(tempFolder.path().resolve("second"), author.url(), "master");
            var newEditHash = CheckableRepository.appendAndCommit(newLocalRepo, "Another line", "Replaced msg");
            newLocalRepo.push(newEditHash, author.url(), "edit", true);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "has refreshed the contents of this pull request, and previous commits have been removed."));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/01"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "Replaced msg"));

            // The webrev comment should be updated
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(newEditHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(sender.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
                assertFalse(newMail.hasHeader("PR-Head-Hash"));
            }
            assertEquals("RFR: This is a pull request [v2]", conversations.get(0).allMessages().get(1).subject());
        }
    }

    @Test
    void rebased(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository("author");
            var main = credentials.getHostedRepository("main");
            var archive = credentials.getHostedRepository("archive");
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var sender = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                    .from(sender)
                    .repo(author)
                    .archive(archive)
                    .censusRepo(censusBuilder.build())
                    .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                    .listArchive(listServer.getArchive())
                    .smtpServer(listServer.getSMTP())
                    .webrevStorageHTMLRepository(archive)
                    .webrevStorageRef("webrev")
                    .webrevStorageBase(Path.of("test"))
                    .webrevStorageBaseUri(webrevServer.uri())
                    .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                    .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, main.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Edit line", "Original msg");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, main, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Add another change in the master
            localRepo.checkout(masterHash);
            var newMasterHash = CheckableRepository.appendAndCommit(localRepo, "New master line", "New master commit message");
            localRepo.push(newMasterHash, main.url(), "master");
            // Add a new "rebased" version of the edit change on top of the new master and force
            // push it to the PR. This should emulate a rebase.
            localRepo.push(newMasterHash, author.url(), "master");
            var newEditHash = CheckableRepository.appendAndCommit(localRepo, "Edit line", "New edit commit message");
            localRepo.push(newEditHash, author.url(), "edit", true);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "has updated the pull request with a new target base"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/01"));
            assertFalse(archiveContains(archiveFolder.path(), "Incremental"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "New edit commit message"));

            // The webrev comment should be updated
            var comments = pr.comments();
            var webrevComments = comments.stream()
                    .filter(comment -> comment.author().equals(author.forge().currentUser()))
                    .filter(comment -> comment.body().contains("webrev"))
                    .filter(comment -> comment.body().contains(newEditHash.hex()))
                    .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(sender.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
                assertFalse(newMail.hasHeader("PR-Head-Hash"));
            }
            assertEquals("RFR: This is a pull request [v2]", conversations.get(0).allMessages().get(1).subject());
        }
    }

    @Test
    void incrementalAfterRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var sender = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(sender)
                                            .repo(author)
                                            .archive(archive)
                                            .archiveRef("archive")
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);
            localRepo.push(masterHash, archive.url(), "archive", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line", "Original msg");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Push more stuff to master
            localRepo.checkout(masterHash, true);
            var unrelatedFile = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelatedFile, "Other things happens in master");
            localRepo.add(unrelatedFile);
            var newMasterHash = localRepo.commit("Unrelated change", "duke", "duke@openjdk.org");
            localRepo.push(newMasterHash, author.url(), "master");

            // And more stuff to the pr branch
            localRepo.checkout(editHash, true);
            CheckableRepository.appendAndCommit(localRepo, "Another line", "More updates");

            // Merge master
            localRepo.merge(newMasterHash);
            var newEditHash = localRepo.commit("Latest changes from master", "duke", "duke@openjdk.org");
            localRepo.push(newEditHash, author.url(), "edit");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.url(), "archive");
            assertTrue(archiveContains(archiveFolder.path(), "has updated the pull request with a new target base"));
            assertTrue(archiveContains(archiveFolder.path(), "excludes"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/01"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/00-01"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "More updates"));
        }
    }

    @Test
    void mergeWebrev(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .archiveRef("archive")
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "archive", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Create a diverging branch
            var editOnlyFile = Path.of("editonly.txt");
            Files.writeString(localRepo.root().resolve(editOnlyFile), "Only added in the edit");
            localRepo.add(editOnlyFile);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Edited");
            localRepo.push(editHash, author.url(), "edit");

            // Make conflicting changes in the target
            localRepo.checkout(masterHash, true);
            var masterOnlyFile = Path.of("masteronly.txt");
            Files.writeString(localRepo.root().resolve(masterOnlyFile), "Only added in master");
            localRepo.add(masterOnlyFile);
            var updatedMasterHash = CheckableRepository.appendAndCommit(localRepo, "Master change");
            localRepo.push(updatedMasterHash, author.url(), "master");

            // Perform the merge - resolve conflicts in our favor
            localRepo.merge(editHash, "ours");
            localRepo.commit("Merged edit", "duke", "duke@openjdk.org");
            var mergeOnlyFile = Path.of("mergeonly.txt");
            Files.writeString(localRepo.root().resolve(mergeOnlyFile), "Only added in the merge");
            localRepo.add(mergeOnlyFile);
            Files.writeString(localRepo.root().resolve(reviewFile), "Overwriting the conflict resolution");
            localRepo.add(reviewFile);
            var appendedCommit = localRepo.amend("Updated merge commit", "duke", "duke@openjdk.org");
            localRepo.push(appendedCommit, author.url(), "merge_of_edit", true);

            // Make a merge PR
            var pr = credentials.createPullRequest(archive, "master", "merge_of_edit", "Merge edit");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a merge style webrev
            Repository.materialize(archiveFolder.path(), archive.url(), "archive");
            assertTrue(archiveContains(archiveFolder.path(), "The webrevs contain the adjustments done while merging with regards to each parent branch:"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/00.0"));
            assertTrue(archiveContains(archiveFolder.path(), "3 lines in 2 files changed: 1 ins; 1 del; 1 mod"));

            // The PR should contain a webrev comment
            assertEquals(1, pr.comments().size());
            var webrevComment = pr.comments().get(0);
            assertTrue(webrevComment.body().contains("Merge target"));
            assertTrue(webrevComment.body().contains("Merge source"));
        }
    }

    @Test
    void mergeWebrevConflict(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .archiveRef("archive")
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "archive", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Create a merge
            var editOnlyFile = Path.of("editonly.txt");
            Files.writeString(localRepo.root().resolve(editOnlyFile), "Only added in the edit");
            localRepo.add(editOnlyFile);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Edited");
            localRepo.checkout(masterHash, true);
            var masterOnlyFile = Path.of("masteronly.txt");
            Files.writeString(localRepo.root().resolve(masterOnlyFile), "Only added in master");
            localRepo.add(masterOnlyFile);
            var updatedMasterHash = CheckableRepository.appendAndCommit(localRepo, "Master change");
            localRepo.push(updatedMasterHash, author.url(), "master");
            localRepo.push(editHash, author.url(), "edit", true);

            // Make a merge PR
            var pr = credentials.createPullRequest(archive, "master", "edit", "Merge edit");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a merge style webrev
            Repository.materialize(archiveFolder.path(), archive.url(), "archive");
            assertTrue(archiveContains(archiveFolder.path(), "The webrev contains the conflicts with master:"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/00.conflicts"));
            assertTrue(archiveContains(archiveFolder.path(), "2 lines in 2 files changed: 2 ins; 0 del; 0 mod"));

            // The PR should contain a webrev comment
            assertEquals(1, pr.comments().size());
            var webrevComment = pr.comments().get(0);
            assertTrue(webrevComment.body().contains("Merge conflicts"));
        }
    }

    @Test
    void mergeWebrevNoConflict(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .archiveRef("archive")
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "archive", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Create a merge
            var editOnlyFile = Path.of("editonly.txt");
            Files.writeString(localRepo.root().resolve(editOnlyFile), "Only added in the edit");
            localRepo.add(editOnlyFile);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Edited", "Commit in edit branch");
            localRepo.checkout(masterHash, true);
            var masterOnlyFile = Path.of("masteronly.txt");
            Files.writeString(localRepo.root().resolve(masterOnlyFile), "Only added in master");
            localRepo.add(masterOnlyFile);
            var updatedMasterHash = localRepo.commit("Only added in master", "duke", "duke@openjdk.org");
            localRepo.push(updatedMasterHash, author.url(), "master");
            localRepo.merge(editHash);
            var mergeCommit = localRepo.commit("Merged edit", "duke", "duke@openjdk.org");
            localRepo.push(mergeCommit, author.url(), "edit", true);

            // Make a merge PR
            var pr = credentials.createPullRequest(archive, "master", "edit", "Merge edit");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a merge style webrev
            Repository.materialize(archiveFolder.path(), archive.url(), "archive");
            assertTrue(archiveContains(archiveFolder.path(), "so no merge-specific webrevs have been generated"));

            // The PR should not contain a webrev comment
            assertEquals(0, pr.comments().size());
        }
    }

    @Test
    void skipAddingExistingWebrev(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");

            // Flag it as ready for review
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            var archiveRepo = Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), editHash.abbreviate()));

            // And there should be a webrev comment
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            assertEquals(1, countSubstrings(webrevComments.get(0).body(), pr.id() + "/00"));

            // Pretend the archive didn't work out
            archiveRepo.push(masterHash, archive.url(), "master", true);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The webrev comment should not contain duplicate entries
            comments = pr.comments();
            webrevComments = comments.stream()
                                     .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                     .filter(comment -> comment.body().contains("webrev"))
                                     .filter(comment -> comment.body().contains(editHash.hex()))
                                     .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            assertEquals(1, countSubstrings(webrevComments.get(0).body(), pr.id() + "/00"));
        }
    }

    @Test
    void notifyReviewVerdicts(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var from = EmailAddress.from("test", "test@test.mail");
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // First unapprove it
            var reviewedPr = reviewer.pullRequest(pr.id());
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 1");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain a note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Changes requested by "));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), " by integrationreviewer1"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 1"));

            // Then approve it
            reviewedPr.addReview(Review.Verdict.APPROVED, "Reason 2");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Marked as reviewed by "));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 2"));
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "Re: RFR:"));

            // Yet another change
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 3");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "Changes requested by "));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 3"));
        }
    }

    @Test
    void ignoreComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .ignoredComments(Set.of(Pattern.compile("ignore this comment", Pattern.MULTILINE | Pattern.DOTALL)))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Make a bunch of comments
            pr.addComment("Plain comment");
            pr.addComment("ignore this comment");
            pr.addComment("I think it is time to\nignore this comment!");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review ignore this comment");

            var ignoredPR = ignored.pullRequest(pr.id());
            ignoredPR.addComment("Don't mind me");

            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain the ignored comments
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "ignore this comment"));
            assertFalse(archiveContains(archiveFolder.path(), "it is time to"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));
            assertFalse(archiveContains(archiveFolder.path(), "Review ignore"));
        }
    }

    @Test
    void replyToEmptyReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make an empty approval
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("@" + reviewer.forge().currentUser().username() + " Thanks for the review!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The approval text should be included in the quote
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "^> Marked as reviewed"));
        }
    }

    @Test
    void cooldown(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().username()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageHTMLRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.build();
            var mlBotWithCooldown = mlBotBuilder.cooldown(Duration.ofDays(1)).build();

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a comment
            pr.addComment("Looks good");

            // Bot with cooldown configured should not bridge the comment
            TestBotRunner.runPeriodicItems(mlBotWithCooldown);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But without, it should
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Looks good"));
        }
    }

    @Test
    void cooldownNewRevision(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().username()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageHTMLRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.build();
            var mlBotWithCooldown = mlBotBuilder.cooldown(Duration.ofDays(1)).build();

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Commit another revision
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "More stuff");
            localRepo.push(updatedHash, author.url(), "edit");

            // Bot with cooldown configured should not create a new webrev
            TestBotRunner.runPeriodicItems(mlBotWithCooldown);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But without, it should
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/01"));
        }
    }

    @Test
    void retryAfterCooldown(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var cooldown = Duration.ofMillis(500);
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().username()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageHTMLRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.cooldown(cooldown).build();
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a comment and run the check within the cooldown period
            int counter;
            boolean noMailReceived = false;
            for (counter = 1; counter < 10; ++counter) {
                var start = Instant.now();
                pr.addComment("Looks good - " + counter + " -");

                // The bot should not bridge the comment due to cooldown
                TestBotRunner.runPeriodicItems(mlBot);
                try {
                    noMailReceived = false;
                    listServer.processIncoming(Duration.ofMillis(1));
                } catch (RuntimeException e) {
                    noMailReceived = true;
                }
                var elapsed = Duration.between(start, Instant.now());
                if (elapsed.compareTo(cooldown) < 0) {
                    break;
                } else {
                    log.info("Didn't do the test in time - retrying (elapsed: " + elapsed + " required: " + cooldown + ")");
                    // Ensure that the cooldown expires
                    Thread.sleep(cooldown.toMillis());
                    // If no mail was received, we have to flush it out
                    if (noMailReceived) {
                        TestBotRunner.runPeriodicItems(mlBot);
                        listServer.processIncoming();
                    }
                    cooldown = cooldown.multipliedBy(2);
                    mlBot = mlBotBuilder.cooldown(cooldown).build();
                }
            }
            assertTrue(noMailReceived);

            // But after the cooldown period has passed, it should
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Looks good - " + counter + " -"));
        }
    }

    @Test
    void branchPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .branchInSubject(Pattern.compile(".*"))
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is a PR");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("Looks good!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: \\[master\\] RFR: "));
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: \\[master\\] RFR: "));
        }
    }

    @Test
    void repoPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .repoInSubject(true)
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is a PR");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("Looks good!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: \\[" + pr.repository().name() + "\\] RFR: "));
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: \\[" + pr.repository().name() + "\\] RFR: "));
        }
    }

    @Test
    void repoAndBranchPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .repoInSubject(true)
                                            .branchInSubject(Pattern.compile(".*"))
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is a PR");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("Looks good!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: \\[" + pr.repository().name() + ":master\\] RFR: "));
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: \\[" + pr.repository().name() + ":master\\] RFR: "));
        }
    }

    @Test
    void retryNewRevisionAfterCooldown(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var cooldown = Duration.ofMillis(500);
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().username()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageHTMLRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.cooldown(cooldown).build();
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a new revision and run the check within the cooldown period
            int counter;
            boolean noMailReceived = false;
            for (counter = 1; counter < 10; ++counter) {
                var start = Instant.now();
                var revHash = CheckableRepository.appendAndCommit(localRepo, "more stuff", "Update number - " + counter + " -");
                localRepo.push(revHash, author.url(), "edit");

                // The bot should not bridge the new revision due to cooldown
                TestBotRunner.runPeriodicItems(mlBot);
                try {
                    noMailReceived = false;
                    listServer.processIncoming(Duration.ofMillis(1));
                } catch (RuntimeException e) {
                    noMailReceived = true;
                }
                var elapsed = Duration.between(start, Instant.now());
                if (elapsed.compareTo(cooldown) < 0) {
                    break;
                } else {
                    log.info("Didn't do the test in time - retrying (elapsed: " + elapsed + " required: " + cooldown + ")");
                    // Ensure that the cooldown expires
                    Thread.sleep(cooldown.toMillis());
                    // If no mail was received, we have to flush it out
                    if (noMailReceived) {
                        TestBotRunner.runPeriodicItems(mlBot);
                        listServer.processIncoming();
                    }
                    cooldown = cooldown.multipliedBy(2);
                    mlBot = mlBotBuilder.cooldown(cooldown).build();
                }
            }
            assertTrue(noMailReceived);

            // But after the cooldown period has passed, it should
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Update number - " + counter + " -"));
        }
    }

    @Test
    void multipleRecipients(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress1 = EmailAddress.parse(listServer.createList("test1"));
            var listAddress2 = EmailAddress.parse(listServer.createList("test2"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress1, Set.of("list1")),
                                                           new MailingListConfiguration(listAddress2, Set.of("list2"))))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This is a PR");
            pr.addLabel("list1");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The mail should have been sent to list1
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress1.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: 1234: This is a pull request", mail.subject());
            assertEquals(pr.author().fullName(), mail.author().fullName().orElseThrow());
            assertEquals(from.address(), mail.author().address());
            assertEquals(listAddress1, mail.sender());
            assertEquals(List.of(listAddress1), mail.recipients());

            // Add another label and comment
            pr.addLabel("list2");
            pr.addComment("Looks good!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // This one should have been sent to list1 and list2
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var reply = conversations.get(0).replies(conversations.get(0).first()).get(0);
            assertEquals("RFR: 1234: This is a pull request", reply.subject());
            assertEquals(pr.author().fullName(), reply.author().fullName().orElseThrow());
            assertEquals(from.address(), reply.author().address());
            assertEquals(listAddress1, reply.sender());
            assertEquals(List.of(listAddress1, listAddress2), reply.recipients());
        }
    }

    @Test
    void jsonArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory(false);
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .ignoredComments(Set.of())
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageJSONRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .webrevGenerateHTML(false)
                                            .webrevGenerateJSON(true)
                                            .readyLabels(Set.of("rfr"))
                                            .readyComments(Map.of(ignored.forge().currentUser().username(), Pattern.compile("ready")))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .headers(Map.of("Extra1", "val1", "Extra2", "val2"))
                                            .sendInterval(Duration.ZERO)
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should not be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.setBody("This should now be ready");
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // But it should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a general comment - not a ready marker
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.addComment("hello there");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // It should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a ready comment
            ignoredPr.addComment("ready");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This should now be ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch:"));
            assertTrue(archiveContains(archiveFolder.path(), "Changes:"));
            assertTrue(archiveContains(archiveFolder.path(), "Webrev:"));
            assertTrue(archiveContains(archiveFolder.path(), webrevServer.uri().toString()));
            assertTrue(archiveContains(archiveFolder.path(), "&pr=" + pr.id() + "&range=00"));
            assertTrue(archiveContains(archiveFolder.path(), "Issue:"));
            assertTrue(archiveContains(archiveFolder.path(), "http://issues.test/browse/TSTPRJ-1234"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch:"));
            assertTrue(archiveContains(archiveFolder.path(), "^ - Change msg"));
            assertFalse(archiveContains(archiveFolder.path(), "With several lines"));

            // The mailing list as well
            listServer.processIncoming();
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: 1234: This is a pull request", mail.subject());
            assertEquals(pr.author().fullName(), mail.author().fullName().orElseThrow());
            assertEquals(from.address(), mail.author().address());
            assertEquals(listAddress, mail.sender());
            assertEquals("val1", mail.headerValue("Extra1"));
            assertEquals("val2", mail.headerValue("Extra2"));

            // And there should be a JSON version of a webrev
            Repository.materialize(webrevFolder.path(), archive.url(), "webrev");
            var jsonDir = webrevFolder.path()
                                      .resolve(author.name())
                                      .resolve(pr.id())
                                      .resolve("00");
            assertTrue(Files.exists(jsonDir));
            assertTrue(Files.isDirectory(jsonDir));

            var commitsFile = jsonDir.resolve("commits.json");
            assertTrue(Files.exists(commitsFile));
            var commits = JSON.parse(Files.readString(commitsFile));
            assertEquals(1, commits.asArray().size());
            var commit = commits.get(0);
            assertEquals(editHash.hex(), commit.get("sha").asString());
            assertEquals("Change msg\n\nWith several lines", commit.get("commit").get("message").asString());
            assertEquals(1, commit.get("files").asArray().size());

            var metadataFile = jsonDir.resolve("metadata.json");
            assertTrue(Files.exists(metadataFile));
            var metadata = JSON.parse(Files.readString(metadataFile));
            assertEquals(masterHash.hex(), metadata.get("base").get("sha").asString());
            assertEquals(editHash.hex(), metadata.get("head").get("sha").asString());

            var comparisonFile = jsonDir.resolve("comparison.json");
            assertTrue(Files.exists(comparisonFile));
            var comparsion = JSON.parse(Files.readString(comparisonFile));
            assertEquals(1, comparsion.get("files").asArray().size());
            assertEquals("modified", comparsion.get("files").get(0).get("status").asString());
            assertTrue(comparsion.get("files").get(0).get("patch").asString().contains("A simple change"));

            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            var comment = webrevComments.get(0);
            assertTrue(comment.body().contains("&pr=" + pr.id()));
            assertTrue(comment.body().contains("&range=00"));

            // Add a comment
            pr.addComment("This is a comment :smile:");

            // Add a comment from an ignored user as well
            ignoredPr.addComment("Don't mind me");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain the comment, but not the ignored one
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This should now be ready"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(2, conversations.get(0).allMessages().size());

            // Remove the rfr flag and post another comment
            pr.addLabel("rfr");
            pr.addComment("@" + pr.author().username() + " This is another comment");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain the additional comment
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is another comment"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This should now be ready"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }
            assertTrue(conversations.get(0).allMessages().get(2).body().contains("This is a comment 😄"));
        }
    }

    @Test
    void rebaseOnRetry(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .ignoredComments(Set.of())
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .readyLabels(Set.of("rfr"))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .headers(Map.of("Extra1", "val1", "Extra2", "val2"))
                                            .sendInterval(Duration.ZERO)
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");

            // Flag it as ready for review
            pr.setBody("This should now be ready");
            pr.addLabel("rfr");

            // The archive should not yet contain an entry
            var archiveRepo = Repository.materialize(archiveFolder.path(), archive.url(), "master");

            // Interfere while creating
            webrevServer.setHandleCallback(uri -> {
                try {
                    var unrelatedFile = archiveRepo.root().resolve("unrelated.txt");
                    if (!Files.exists(unrelatedFile)) {
                        Files.writeString(unrelatedFile, "Unrelated");
                        archiveRepo.add(unrelatedFile);
                        var unrelatedHash = archiveRepo.commit("Unrelated", "duke", "duke@openjdk.org");
                        archiveRepo.push(unrelatedHash, archive.url(), "master");
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This should now be ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch:"));
            assertTrue(archiveContains(archiveFolder.path(), "Changes:"));
            assertTrue(archiveContains(archiveFolder.path(), "Webrev:"));
            assertTrue(archiveContains(archiveFolder.path(), webrevServer.uri().toString()));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/00"));
            assertTrue(archiveContains(archiveFolder.path(), "Issue:"));
            assertTrue(archiveContains(archiveFolder.path(), "http://issues.test/browse/TSTPRJ-1234"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch:"));
            assertTrue(archiveContains(archiveFolder.path(), "^ - Change msg"));
            assertFalse(archiveContains(archiveFolder.path(), "With several lines"));
        }
    }

    @Test
    void dependent(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Create a separate change
            var depHash = CheckableRepository.appendAndCommit(localRepo, "A simple change");
            localRepo.push(depHash, author.url(), "dep", true);
            var depPr = credentials.createPullRequest(archive, "master", "dep", "The first pr");

            // Simulate the pr dependency notifier creating the corresponding branch
            localRepo.push(depHash, author.url(), "pr/" + depPr.id(), true);

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a change with a corresponding PR
            localRepo.checkout(masterHash, true);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "pr/" + depPr.id(), "edit", "1234: This is a pull request");
            pr.setBody("This is a PR");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("Looks good!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Subject: RFR: "), pr.id());
            assertTrue(archiveContains(archiveFolder.path(), "Subject: Re: RFR: ", pr.id()));

            assertTrue(archiveContains(archiveFolder.path(), "Depends on:", pr.id()));
            assertFalse(archiveContains(archiveFolder.path(), "Depends on:", depPr.id()));
        }
    }

    @Test
    void commentWithQuoteFromBridged(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var bridge = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Simulate a bridged comment
            var authorPr = author.pullRequest(pr.id());
            var bridgedMail = Email.create("Re: " + pr.title(), "Mailing list comment\nFirst comment\nsecond line")
                                   .id(EmailAddress.from("bridgedemailid@bridge.bridge"))
                                   .author(EmailAddress.from("List User", "listuser@openjdk.org"))
                                   .build();
            BridgedComment.post(authorPr, bridgedMail);

            // And a regular comment
            pr.addComment("Second comment\nfourth line");

            // Reply to the bridged one
            pr.addComment(">First comm\n\nreply to first");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();

            // Ensure that the PR is considered again - no duplicates should be sent
            pr.addLabel("ping");
            TestBotRunner.runPeriodicItems(mlBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // The first comment should be replied to once, and the original post once
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote("List User <listuser@openjdk.org>") + ".* wrote"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote(pr.author().fullName()) + ".* wrote"));

            // There should have been a reply directed towards the bridged mail id
            assertEquals(1, archiveContainsCount(archiveFolder.path(), Pattern.quote("In-Reply-To: <bridgedemailid@bridge.bridge>")));
        }
    }
}
