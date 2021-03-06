// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.cas;

import static com.google.common.io.MoreFiles.asCharSink;
import static com.google.common.io.MoreFiles.asCharSource;

import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.common.DigestUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import javax.annotation.concurrent.GuardedBy;

/**
 * Ephemeral file manifestations of the entry/directory mappings Directory entries are stored in
 * files (and expected to be immutable) Entry directories are maintained in sqlite.
 *
 * <p>Sqlite db should be removed prior to using this index
 */
class FileDirectoriesIndex implements DirectoriesIndex {
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  private final String dbUrl;
  private final Path root;

  private boolean opened = false;
  private Connection conn;

  FileDirectoriesIndex(String dbUrl, Path root) {
    this.dbUrl = dbUrl;
    this.root = root;
  }

  @GuardedBy("this")
  private void open() {
    if (!opened) {
      try {
        conn = DriverManager.getConnection(dbUrl);
        try (Statement safetyStatement = conn.createStatement()) {
          safetyStatement.execute("PRAGMA synchronous=OFF");
          safetyStatement.execute("PRAGMA journal_mode=OFF");
          safetyStatement.execute("PRAGMA cache_size=100000");
        }
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }

      String createEntriesSql =
          "CREATE TABLE entries (\n"
              + "    path TEXT NOT NULL,\n"
              + "    directory TEXT NOT NULL,\n"
              + "    PRIMARY KEY (path, directory)\n"
              + ")";
      String createIndexSql = "CREATE INDEX path_idx ON entries (path)";

      try (Statement stmt = conn.createStatement()) {
        stmt.execute(createEntriesSql);
        stmt.execute(createIndexSql);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }

      opened = true;
    }
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    opened = false;
  }

  @GuardedBy("this")
  private Set<Digest> removeEntryDirectories(String entry) {
    open();

    String selectSql = "SELECT directory FROM entries WHERE path = ?";
    String deleteSql = "DELETE FROM entries where path = ?";

    ImmutableSet.Builder<Digest> directories = ImmutableSet.builder();
    try (PreparedStatement selectStatement = conn.prepareStatement(selectSql);
        PreparedStatement deleteStatement = conn.prepareStatement(deleteSql)) {
      selectStatement.setString(1, entry);
      try (ResultSet rs = selectStatement.executeQuery()) {
        while (rs.next()) {
          directories.add(DigestUtil.parseDigest(rs.getString("directory")));
        }
      }
      deleteStatement.setString(1, entry);
      deleteStatement.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return directories.build();
  }

  Path path(Digest digest) {
    return root.resolve(digest.getHash() + "_" + digest.getSizeBytes() + "_dir_inputs");
  }

  @Override
  public synchronized Set<Digest> removeEntry(String entry) {
    Set<Digest> directories = removeEntryDirectories(entry);
    try {
      for (Digest directory : directories) {
        Files.delete(path(directory));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return directories;
  }

  @Override
  public Iterable<String> directoryEntries(Digest directory) {
    try {
      return asCharSource(path(directory), UTF_8).readLines();
    } catch (NoSuchFileException e) {
      return ImmutableList.of();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private synchronized void addEntriesDirectory(Iterable<String> entries, Digest directory) {
    open();

    String digest = DigestUtil.toString(directory);
    String insertSql = "INSERT OR IGNORE INTO entries (path, directory)\n" + "    VALUES (?,?)";
    try (PreparedStatement insertStatement = conn.prepareStatement(insertSql)) {
      conn.setAutoCommit(false);
      insertStatement.setString(2, digest);
      for (String entry : entries) {
        insertStatement.setString(1, entry);
        insertStatement.addBatch();
      }
      insertStatement.executeBatch();
      conn.commit();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void put(Digest directory, Iterable<String> entries) {
    try {
      asCharSink(path(directory), UTF_8).writeLines(entries);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    addEntriesDirectory(entries, directory);
  }

  @GuardedBy("this")
  private void removeEntriesDirectory(Iterable<String> entries, Digest directory) {
    open();

    String digest = DigestUtil.toString(directory);
    String deleteSql = "DELETE FROM entries WHERE path = ? AND directory = ?";
    try (PreparedStatement deleteStatement = conn.prepareStatement(deleteSql)) {
      conn.setAutoCommit(false);
      // safe for multi delete
      deleteStatement.setString(2, digest);
      for (String entry : entries) {
        deleteStatement.setString(1, entry);
        deleteStatement.executeUpdate();
      }
      conn.commit();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized void remove(Digest directory) {
    Iterable<String> entries = directoryEntries(directory);
    try {
      Files.delete(path(directory));
    } catch (NoSuchFileException e) {
      // ignore
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    removeEntriesDirectory(entries, directory);
  }
}
