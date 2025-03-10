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
package org.openjdk.skara.bots.csr;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.*;
import org.openjdk.skara.test.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CSRBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                 {
                   "projects": [
                     {
                       "repository": "repo1",
                       "issues": "test_bugs/TEST"
                     },
                     {
                       "repository": "repo2",
                       "issues": "test_bugs/TEST"
                     },
                     {
                       "repository": "repo3",
                       "issues": "test_bugs/TEST2"
                     }
                   ]
                 }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testHost = TestHost.createNew(List.of());
        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository(testHost, "repo1"))
                .addHostedRepository("repo2", new TestHostedRepository(testHost, "repo2"))
                .addHostedRepository("repo3", new TestHostedRepository(testHost, "repo3"))
                .addIssueProject("test_bugs/TEST", new TestIssueProject(testHost, "TEST"))
                .addIssueProject("test_bugs/TEST2", new TestIssueProject(testHost, "TEST2"))
                .build();

        var bots = testBotFactory.createBots(CSRBotFactory.NAME, jsonConfig);
        assertEquals(5, bots.size());

        var csrPullRequestBots = bots.stream().filter(e -> e.getClass().equals(CSRPullRequestBot.class)).toList();
        var csrIssueBots = bots.stream().filter(e -> e.getClass().equals(CSRIssueBot.class)).toList();

        // A CSRPullRequestBot for every configured repository
        assertEquals(3, csrPullRequestBots.size());
        // A CSRIssueBot for each unique IssueProject
        assertEquals(2, csrIssueBots.size());

        var CSRPullRequestBot1 = (CSRPullRequestBot) csrPullRequestBots.get(0);
        var CSRPullRequestBot2 = (CSRPullRequestBot) csrPullRequestBots.get(1);
        var CSRPullRequestBot3 = (CSRPullRequestBot) csrPullRequestBots.get(2);
        assertEquals("CSRPullRequestBot@repo1", CSRPullRequestBot1.toString());
        assertEquals("CSRPullRequestBot@repo2", CSRPullRequestBot2.toString());
        assertEquals("CSRPullRequestBot@repo3", CSRPullRequestBot3.toString());
        assertEquals("TEST", CSRPullRequestBot1.getProject().name());
        assertEquals("TEST", CSRPullRequestBot2.getProject().name());
        assertEquals("TEST2", CSRPullRequestBot3.getProject().name());

        for (var bot : csrIssueBots) {
            CSRIssueBot csrIssueBot = (CSRIssueBot) bot;
            if (csrIssueBot.toString().equals("CSRIssueBot@TEST")) {
                assertEquals(2, csrIssueBot.repositories().size());
            } else if (csrIssueBot.toString().equals("CSRIssueBot@TEST2")) {
                assertEquals(1, csrIssueBot.repositories().size());
            } else {
                throw new RuntimeException("This CSRIssueBot is not expected");
            }
        }
    }
}
