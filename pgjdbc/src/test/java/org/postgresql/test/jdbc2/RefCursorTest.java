/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;

/**
 * RefCursor ResultSet tests. This test case is basically the same as the ResultSet test case.
 *
 * <p>For backwards compatibility reasons we verify that ref cursors can be
 * registered with both {@link Types#OTHER} and {@link Types#REF_CURSOR}.</p>
 *
 * @author Nic Ferrier (nferrier@tapsellferrier.co.uk)
 */
@ParameterizedClass
@MethodSource("data")
public class RefCursorTest extends BaseTest4 {

  private final int cursorType;

  public RefCursorTest(String typeName, int cursorType) {
    this.cursorType = cursorType;
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"OTHER", Types.OTHER},
        {"REF_CURSOR", Types.REF_CURSOR},
    });
  }

  @BeforeAll
  public static void beforeClass() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      assumeCallableStatementsSupported(con);
    }
  }

  @Override
  public void setUp() throws Exception {
    // this is the same as the ResultSet setup.
    super.setUp();
    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testrs", "id integer primary key");

    stmt.executeUpdate("INSERT INTO testrs VALUES (1)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (2)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (3)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (4)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (6)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (9)");

    // Create the functions.
    stmt.execute("CREATE OR REPLACE FUNCTION testspg__getRefcursor () RETURNS refcursor AS '"
        + "declare v_resset refcursor; begin open v_resset for select id from testrs order by id; "
        + "return v_resset; end;' LANGUAGE plpgsql;");
    stmt.execute("CREATE OR REPLACE FUNCTION testspg__getEmptyRefcursor () RETURNS refcursor AS '"
        + "declare v_resset refcursor; begin open v_resset for select id from testrs where id < 1 order by id; "
        + "return v_resset; end;' LANGUAGE plpgsql;");
    stmt.close();
    con.setAutoCommit(false);
  }

  @Override
  public void tearDown() throws SQLException {
    con.setAutoCommit(true);
    Statement stmt = con.createStatement();
    stmt.execute("drop FUNCTION testspg__getRefcursor ();");
    stmt.execute("drop FUNCTION testspg__getEmptyRefcursor ();");
    TestUtil.dropTable(con, "testrs");
    super.tearDown();
  }

  @Test
  public void testResult() throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }");
    call.registerOutParameter(1, cursorType);
    call.execute();
    ResultSet rs = (ResultSet) call.getObject(1);

    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(4, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(6, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(9, rs.getInt(1));

    assertFalse(rs.next());
    rs.close();

    call.close();
  }

  @Test
  public void testEmptyResult() throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getEmptyRefcursor () }");
    call.registerOutParameter(1, cursorType);
    call.execute();

    ResultSet rs = (ResultSet) call.getObject(1);
    assertFalse(rs.next());
    rs.close();

    call.close();
  }

  @Test
  public void testMetaData() throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }");
    call.registerOutParameter(1, cursorType);
    call.execute();

    ResultSet rs = (ResultSet) call.getObject(1);
    ResultSetMetaData rsmd = rs.getMetaData();
    assertNotNull(rsmd);
    assertEquals(1, rsmd.getColumnCount());
    assertEquals(Types.INTEGER, rsmd.getColumnType(1));
    assertEquals("int4", rsmd.getColumnTypeName(1));
    rs.close();

    call.close();
  }

  @Test
  public void testResultType() throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    call.registerOutParameter(1, cursorType);
    call.execute();
    ResultSet rs = (ResultSet) call.getObject(1);

    assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
    assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());

    assertTrue(rs.last());
    assertEquals(6, rs.getRow());
    rs.close();
    call.close();
  }

}
