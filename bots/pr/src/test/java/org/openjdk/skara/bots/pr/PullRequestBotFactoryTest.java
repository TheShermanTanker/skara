/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "external": {
                        "pr": {
                          "test": "used to run tests"
                        },
                        "commit": {
                          "command1": "test1",
                          "command2": "test2"
                        }
                      },
                      "exclude-commit-comments-from": [
                          1,
                          2
                      ],
                      "blockers": {
                        "test": "Signature needs verify"
                      },
                      "ready": {
                        "labels": [],
                        "comments": []
                      },
                      "labels": {
                        "label1": {
                          "repository": "repo1:master",
                          "filename": "file.json"
                        }
                      },
                      "repositories": {
                        "repo2": {
                          "census": "census:master",
                          "censuslink": "https://test.test.com",
                          "issues": "TEST",
                          "csr": false,
                          "two-reviewers": [
                            "rfr"
                          ],
                          "24h": [
                            "24h_test"
                          ],
                          "integrators": [
                            "integrator1",
                            "integrator2"
                          ]
                        }
                      },
                      "forks": {
                        "repo3": "fork3",
                        "repo4": "fork4",
                      }
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository(TestHost.createNew(List.of()), "repo2"))
                    .addHostedRepository("repo3", new TestHostedRepository("repo3"))
                    .addHostedRepository("repo4", new TestHostedRepository("repo4"))
                    .addHostedRepository("fork3", new TestHostedRepository("fork3"))
                    .addHostedRepository("fork4", new TestHostedRepository("fork4"))
                    .addHostedRepository("census", new TestHostedRepository("census"))
                    .addIssueProject("TEST", new TestIssueProject(null, "TEST"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(PullRequestBotFactory.NAME, jsonConfig);
            //A pullRequestBot for every configured repository
            assertEquals(1, bots.size());

            var pullRequestBot1 = (PullRequestBot) bots.get(0);
            assertEquals("PullRequestBot@repo2", pullRequestBot1.toString());
            assertEquals("used to run tests", pullRequestBot1.externalPullRequestCommands().get("test"));
            assertEquals("TEST", pullRequestBot1.issueProject().name());
            assertEquals("census", pullRequestBot1.censusRepo().name());
            assertEquals("master", pullRequestBot1.censusRef());
            assertEquals("{test=used to run tests}", pullRequestBot1.externalPullRequestCommands().toString());
            assertEquals("{test=Signature needs verify}", pullRequestBot1.blockingCheckLabels().toString());
            assertEquals("[rfr]", pullRequestBot1.twoReviewersLabels().toString());
            assertEquals("[24h_test]", pullRequestBot1.twentyFourHoursLabels().toString());
            assertFalse(pullRequestBot1.ignoreStaleReviews());
            assertEquals(".*", pullRequestBot1.allowedTargetBranches().toString());
            var integrators = pullRequestBot1.integrators();
            assertEquals(2, integrators.size());
            assertTrue(integrators.contains("integrator1"));
            assertTrue(integrators.contains("integrator2"));
        }
    }
}