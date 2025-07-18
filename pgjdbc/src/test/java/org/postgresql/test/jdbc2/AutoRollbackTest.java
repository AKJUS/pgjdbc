/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

@ParameterizedClass
@MethodSource("data")
public class AutoRollbackTest extends BaseTest4 {
  private static final AtomicInteger counter = new AtomicInteger();

  private enum CleanSavePoint {
    TRUE,
    FALSE
  }

  private enum FailMode {
    /**
     * Executes "select 1/0" and causes transaction failure (if autocommit=no).
     * Mitigation: "autosave=always" or "autocommit=true"
     */
    SELECT,
    /**
     * Executes "alter table rollbacktest", thus it breaks a prepared select over that table.
     * Mitigation: "autosave in (always, conservative)"
     */
    ALTER,
    /**
     * Executes DEALLOCATE ALL.
     * Mitigation:
     *  1) QueryExecutor tracks "DEALLOCATE ALL" responses ({@see org.postgresql.core.QueryExecutor#setFlushCacheOnDeallocate(boolean)}
     *  2) QueryExecutor tracks "prepared statement name is invalid" and unprepared relevant statements ({@link org.postgresql.core.v3.QueryExecutorImpl#processResults(ResultHandler, int)}
     *  3) "autosave in (always, conservative)"
     *  4) Non-transactional cases are healed by retry (when no transaction present, just retry is possible)
     */
    DEALLOCATE,
    /**
     * Executes DISCARD ALL.
     * Mitigation: the same as for {@link #DEALLOCATE}
     */
    DISCARD,
    /**
     * Executes "insert ... select 1/0" in a batch statement, thus causing the transaction to fail.
     */
    INSERT_BATCH,
  }

  private enum ReturnColumns {
    EXACT("a, str"),
    STAR("*");

    public final String cols;

    ReturnColumns(String cols) {
      this.cols = cols;
    }
  }

  private enum TestStatement {
    SELECT("select ${cols} from rollbacktest", 0),
    WITH_INSERT_SELECT(
        "with x as (insert into rollbacktest(a, str) values(43, 'abc') returning ${cols})"
            + "select * from x", 1);

    private final String sql;
    private final int rowsInserted;

    TestStatement(String sql, int rowsInserted) {
      this.sql = sql;
      this.rowsInserted = rowsInserted;
    }

    public String getSql(ReturnColumns cols) {
      return sql.replace("${cols}", cols.cols);
    }
  }

  private static final EnumSet<FailMode> DEALLOCATES =
      EnumSet.of(FailMode.DEALLOCATE, FailMode.DISCARD);

  private static final EnumSet<FailMode> TRANS_KILLERS =
      EnumSet.of(FailMode.SELECT, FailMode.INSERT_BATCH);

  private enum ContinueMode {
    COMMIT,
    IS_VALID,
    SELECT,
  }

  private final AutoSave autoSave;
  private final CleanSavePoint cleanSavePoint;
  private final AutoCommit autoCommit;
  private final FailMode failMode;
  private final ContinueMode continueMode;
  private final boolean flushCacheOnDeallocate;
  private final boolean trans;
  private final TestStatement testSql;
  private final ReturnColumns cols;

  public AutoRollbackTest(AutoSave autoSave, CleanSavePoint cleanSavePoint, AutoCommit autoCommit,
      FailMode failMode, ContinueMode continueMode, boolean flushCacheOnDeallocate,
      boolean trans, TestStatement testSql, ReturnColumns cols) {
    this.autoSave = autoSave;
    this.cleanSavePoint = cleanSavePoint;
    this.autoCommit = autoCommit;
    this.failMode = failMode;
    this.continueMode = continueMode;
    this.flushCacheOnDeallocate = flushCacheOnDeallocate;
    this.trans = trans;
    this.testSql = testSql;
    this.cols = cols;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    if (testSql == TestStatement.WITH_INSERT_SELECT) {
      assumeMinimumServerVersion(ServerVersion.v9_1);
    }

    TestUtil.createTable(con, "rollbacktest", "a int, str text");
    con.setAutoCommit(autoCommit == AutoCommit.YES);
    BaseConnection baseConnection = con.unwrap(BaseConnection.class);
    baseConnection.setFlushCacheOnDeallocate(flushCacheOnDeallocate);
    assumeTrue(failMode != FailMode.DEALLOCATE || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3), "DEALLOCATE ALL requires PostgreSQL 8.3+");
    assumeTrue(failMode != FailMode.DISCARD || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3), "DISCARD ALL requires PostgreSQL 8.3+");
    assumeTrue(failMode != FailMode.ALTER || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3), "Plan invalidation on table redefinition requires PostgreSQL 8.3+");
  }

  @Override
  public void tearDown() throws SQLException {
    try {
      con.setAutoCommit(true);
      TestUtil.dropTable(con, "rollbacktest");
    } catch (Exception e) {
      e.printStackTrace();
    }
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, autoSave.value());
    PGProperty.CLEANUP_SAVEPOINTS.set(props, cleanSavePoint.toString());
    PGProperty.PREPARE_THRESHOLD.set(props, 1);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    boolean[] booleans = new boolean[]{true, false};
    for (AutoSave autoSave : AutoSave.values()) {
      for (CleanSavePoint cleanSavePoint:CleanSavePoint.values()) {
        for (AutoCommit autoCommit : AutoCommit.values()) {
          for (FailMode failMode : FailMode.values()) {
            // ERROR: DISCARD ALL cannot run inside a transaction block
            if (failMode == FailMode.DISCARD && autoCommit == AutoCommit.NO) {
              continue;
            }
            for (ContinueMode continueMode : ContinueMode.values()) {
              if (failMode == FailMode.ALTER && continueMode != ContinueMode.SELECT) {
                continue;
              }
              for (boolean flushCacheOnDeallocate : booleans) {
                if (!(flushCacheOnDeallocate || DEALLOCATES.contains(failMode))) {
                  continue;
                }

                for (boolean trans : new boolean[]{true, false}) {
                  // continueMode would commit, and autoCommit=YES would commit,
                  // so it does not make sense to test trans=true for those cases
                  if (trans && (continueMode == ContinueMode.COMMIT
                      || autoCommit != AutoCommit.NO)) {
                    continue;
                  }
                  for (TestStatement statement : TestStatement.values()) {
                    for (ReturnColumns columns : ReturnColumns.values()) {
                      ids.add(new Object[]{autoSave, cleanSavePoint, autoCommit, failMode, continueMode,
                          flushCacheOnDeallocate, trans, statement, columns});
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    return ids;
  }

  @Test
  public void run() throws SQLException {
    if (continueMode == ContinueMode.IS_VALID) {
      // make "isValid" a server-prepared statement
      con.isValid(4);
    } else if (continueMode == ContinueMode.COMMIT) {
      doCommit();
    } else if (continueMode == ContinueMode.SELECT) {
      assertRows("rollbacktest", 0);
    }

    Statement statement = con.createStatement();
    statement.executeUpdate("insert into rollbacktest(a, str) values (0, 'test')");
    int rowsExpected = 1;

    PreparedStatement ps = con.prepareStatement(testSql.getSql(cols));
    // Server-prepare the testSql
    ps.executeQuery().close();
    rowsExpected += testSql.rowsInserted;

    if (trans) {
      statement.executeUpdate("update rollbacktest set a=a");
    }

    switch (failMode) {
      case SELECT:
        try {
          statement.execute("select 1/0");
          fail("select 1/0 should fail");
        } catch (SQLException e) {
          assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState(), "division by zero expected");
        }
        break;
      case DEALLOCATE:
        statement.executeUpdate("DEALLOCATE ALL");
        break;
      case DISCARD:
        statement.executeUpdate("DISCARD ALL");
        break;
      case ALTER:
        statement.executeUpdate("alter table rollbacktest add q int");
        break;
      case INSERT_BATCH:
        try {
          statement.addBatch("insert into rollbacktest(a, str) values (1/0, 'test')");
          statement.executeBatch();
          fail("select 1/0 should fail");
        } catch (SQLException e) {
          assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState(), "division by zero expected");
        }
        break;
      default:
        fail("Fail mode " + failMode + " is not implemented");
    }

    PgConnection pgConnection = con.unwrap(PgConnection.class);
    if (autoSave == AutoSave.ALWAYS) {
      assertNotEquals(TransactionState.FAILED, pgConnection.getTransactionState(), "In AutoSave.ALWAYS, transaction should not fail");
    }
    if (autoCommit == AutoCommit.NO) {
      assertNotEquals(TransactionState.IDLE, pgConnection.getTransactionState(), "AutoCommit == NO, thus transaction should be active (open or failed)");
    }
    statement.close();

    switch (continueMode) {
      case COMMIT:
        try {
          doCommit();
          // No assert here: commit should always succeed with exception of well known failure cases in catch
        } catch (SQLException e) {
          if (!flushCacheOnDeallocate && DEALLOCATES.contains(failMode)
              && autoSave == AutoSave.NEVER) {
            assertEquals(
                PSQLState.INVALID_SQL_STATEMENT_NAME.getState(),
                e.getSQLState(),
                () -> "flushCacheOnDeallocate is disabled, thus " + failMode + " should cause "
                    + "'prepared statement \"...\" does not exist'"
                    + " error message is " + e.getMessage());
            return;
          }
          throw e;
        }
        return;
      case IS_VALID:
        assertTrue(con.isValid(4), "Connection.isValid should return true unless the connection is closed as .isValid should use simple queries only which should not fail in face of prepared statement failures");
        return;
      default:
        break;
    }

    try {
      // Try execute server-prepared statement again
      ps.executeQuery().close();
      rowsExpected += testSql.rowsInserted;
      executeSqlSuccess();
    } catch (SQLException e) {
      if (autoSave != AutoSave.ALWAYS && TRANS_KILLERS.contains(failMode) && autoCommit == AutoCommit.NO) {
        assertEquals(PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState(), "AutoSave==" + autoSave + ", thus statements should fail with 'current transaction is aborted...', "
            + " error message is " + e.getMessage());
        return;
      }

      if (autoSave == AutoSave.NEVER && autoCommit == AutoCommit.NO) {
        if (DEALLOCATES.contains(failMode) && !flushCacheOnDeallocate) {
          assertEquals(PSQLState.INVALID_SQL_STATEMENT_NAME.getState(), e.getSQLState(), "flushCacheOnDeallocate is disabled, thus " + failMode + " should cause 'prepared statement \"...\" does not exist'"
              + " error message is " + e.getMessage());
        } else if (failMode == FailMode.ALTER) {
          assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), e.getSQLState(), "AutoSave==NEVER, autocommit=NO, thus ALTER TABLE causes SELECT * to fail with "
              + "'cached plan must not change result type', "
              + " error message is " + e.getMessage());
        } else {
          throw e;
        }
      } else {
        throw e;
      }
    }

    try {
      assertRows("rollbacktest", rowsExpected);
      executeSqlSuccess();
    } catch (SQLException e) {
      if (autoSave == AutoSave.NEVER && autoCommit == AutoCommit.NO) {
        if (DEALLOCATES.contains(failMode) && !flushCacheOnDeallocate
            || failMode == FailMode.ALTER) {
          // The above statement failed with "prepared statement does not exist", thus subsequent one should fail with
          // transaction aborted.
          assertEquals(PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState(), "AutoSave==NEVER, thus statements should fail with 'current transaction is aborted...', "
              + " error message is " + e.getMessage());
        }
      } else {
        throw e;
      }
    }
  }

  private void executeSqlSuccess() throws SQLException {
    if (autoCommit == AutoCommit.YES) {
      // in autocommit everything should just work
    } else if (TRANS_KILLERS.contains(failMode)) {
      if (autoSave != AutoSave.ALWAYS) {
        fail("autosave= " + autoSave + " != ALWAYS, thus the transaction should be killed");
      }
    } else if (DEALLOCATES.contains(failMode)) {
      if (autoSave == AutoSave.NEVER && !flushCacheOnDeallocate
          && con.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
        fail("flushCacheOnDeallocate == false, thus DEALLOCATE ALL should kill the transaction");
      }
    } else if (failMode == FailMode.ALTER) {
      if (autoSave == AutoSave.NEVER
          && con.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE
          && cols == ReturnColumns.STAR) {
        fail("autosave=NEVER, thus the transaction should be killed");
      }
    } else {
      fail("It is not specified why the test should pass, thus marking a failure");
    }
  }

  private void assertRows(String tableName, int nrows) throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select count(*) from " + tableName);
    rs.next();
    assertEquals(nrows, rs.getInt(1), "Table " + tableName);
  }

  private void doCommit() throws SQLException {
    // Such a dance is required since "commit" checks "current transaction state",
    // so we need some pending changes, so "commit" query would be sent to the database
    if (con.getAutoCommit()) {
      con.setAutoCommit(false);
      Statement st = con.createStatement();
      st.executeUpdate(
          "insert into rollbacktest(a, str) values (42, '" + System.currentTimeMillis() + "," + counter.getAndIncrement() + "')");
      st.close();
    }
    con.commit();
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }
}
