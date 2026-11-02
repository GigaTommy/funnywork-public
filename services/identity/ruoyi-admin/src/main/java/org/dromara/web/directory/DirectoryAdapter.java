package org.dromara.web.directory;

import java.util.List;

/** Deployment adapters normalize provider-specific authentication and directory APIs. */
public interface DirectoryAdapter {
    record User(String externalId, String username, String displayName, String departmentId,
                long version, boolean enabled) {}
    record Page(String snapshot, String nextCursor, String checkpoint, List<User> users) {}
    /** Returns the immutable external subject, never a local user ID or an upstream token. */
    String authenticate(String username, String password);
    Page users(String checkpoint, String cursor);
}
