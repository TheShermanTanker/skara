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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Hash;

import java.util.*;

class InMemoryHost implements Forge {
    HostUser currentUserDetails = HostUser.create(0, "openjdk", "openjdk [bot]");
    Map<String, Set<HostUser>> groups;

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public String name() {
        return "InMemory";
    }

    @Override
    public String hostname() {
        return "in.memory";
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<HostUser> user(String username) {
        return Optional.empty();
    }

    @Override
    public HostUser currentUser() {
        return currentUserDetails;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        return groups.get(groupId).contains(user);
    }

    @Override
    public Optional<HostedCommit> search(Hash hash, boolean includeDiffs) {
        return Optional.empty();
    }
}
