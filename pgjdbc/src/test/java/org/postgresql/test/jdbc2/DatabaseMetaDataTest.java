/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionBelow;
import org.postgresql.test.jdbc2.BaseTest4.BinaryMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData
 */
@ParameterizedClass
@MethodSource("data")
public class DatabaseMetaDataTest {
  private Connection con;

  private final BinaryMode binaryMode;

  public DatabaseMetaDataTest(BinaryMode binaryMode) {
    this.binaryMode = binaryMode;
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeEach
  void setUp() throws Exception {
    if (binaryMode == BinaryMode.FORCE) {
      final Properties props = new Properties();
      PGProperty.PREPARE_THRESHOLD.set(props, -1);
      con = TestUtil.openDB(props);
    } else {
      con = TestUtil.openDB();
    }
    TestUtil.createTable(con, "bestrowid", "id int4 primary key");
    TestUtil.createTable(con, "metadatatest",
        "id int4, name text, updated timestamptz, colour text, quest text");
    TestUtil.createTable(con, "precision_test", "implicit_precision numeric");
    TestUtil.dropSequence(con, "sercoltest_b_seq");
    TestUtil.dropSequence(con, "sercoltest_c_seq");
    TestUtil.createTable(con, "sercoltest", "a int, b serial, c bigserial");
    TestUtil.createTable(con, "\"a\\\"", "a int4");
    TestUtil.createTable(con, "\"a'\"", "a int4");
    TestUtil.createTable(con, "arraytable", "a numeric(5,2)[], b varchar(100)[]");
    TestUtil.createTable(con, "intarraytable", "a int4[], b int4[][]");
    TestUtil.createView(con, "viewtest", "SELECT id, quest FROM metadatatest");
    TestUtil.dropType(con, "custom");
    TestUtil.dropType(con, "_custom");
    TestUtil.createCompositeType(con, "custom", "i int", false);
    TestUtil.createCompositeType(con, "_custom", "f float", false);

    // create a table and multiple comments on it
    TestUtil.createTable(con, "duplicate", "x text");
    TestUtil.execute(con, "comment on table duplicate is 'duplicate table'");
    TestUtil.execute(con, "create or replace function bar() returns integer language sql as $$ select 1 $$");
    TestUtil.execute(con, "comment on function bar() is 'bar function'");
    try (Connection conPriv = TestUtil.openPrivilegedDB()) {
      TestUtil.execute(conPriv, "update pg_description set objoid = 'duplicate'::regclass where objoid = 'bar'::regproc");
    }

    // 8.2 does not support arrays of composite types
    TestUtil.createTable(con, "customtable", "c1 custom, c2 _custom"
        + (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3) ? ", c3 custom[], c4 _custom[]" : ""));

    Statement stmt = con.createStatement();
    // we add the following comments to ensure the joins to the comments
    // are done correctly. This ensures we correctly test that case.
    stmt.execute("comment on table metadatatest is 'this is a table comment'");
    stmt.execute("comment on column metadatatest.id is 'this is a column comment'");

    stmt.execute(
        "CREATE OR REPLACE FUNCTION f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f2(a int, b varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f4(int) RETURNS metadatatest AS 'SELECT 1, ''a''::text, now(), ''c''::text, ''q''::text' LANGUAGE SQL");
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      // RETURNS TABLE requires PostgreSQL 8.4+
      stmt.execute(
          "CREATE OR REPLACE FUNCTION f5() RETURNS TABLE (i int) LANGUAGE sql AS 'SELECT 1'");
    }

    // create a custom `&` operator, which caused failure with `&` usage in getIndexInfo()
    stmt.execute(
        "CREATE OR REPLACE FUNCTION f6(numeric, integer) returns integer as 'BEGIN return $1::integer & $2;END;' language plpgsql immutable;");
    stmt.execute("DROP OPERATOR IF EXISTS & (numeric, integer)");
    stmt.execute("CREATE OPERATOR & (LEFTARG = numeric, RIGHTARG = integer, PROCEDURE = f6)");

    TestUtil.createDomain(con, "nndom", "int not null");
    TestUtil.createDomain(con, "varbit2", "varbit(3)");
    TestUtil.createDomain(con, "float83", "numeric(8,3)");

    TestUtil.createTable(con, "domaintable", "id nndom, v varbit2, f float83");
    stmt.close();

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      String tableName = "pk_include_column";
      String indexName = tableName + "_pkey";
      TestUtil.createTable(con, tableName, "a INT, b INT, c INT, d INT");

      String createIndexStmt = "CREATE UNIQUE INDEX IF NOT EXISTS " + indexName
          + " ON " + tableName + " (b,d) INCLUDE (a)";
      TestUtil.execute(con, createIndexStmt);

      String addPrimaryKeyStmt = "ALTER TABLE " + tableName + " ADD PRIMARY KEY USING INDEX " + indexName;
      TestUtil.execute(con, addPrimaryKeyStmt);
    }

    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v12) ) {
      TestUtil.createTable(con, "employee", "id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, hours_per_week decimal(3,2), rate_per_hour decimal(3,2), gross_pay decimal GENERATED ALWAYS AS (hours_per_week * rate_per_hour) STORED");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    // Drop function first because it depends on the
    // metadatatest table's type
    Statement stmt = con.createStatement();
    stmt.execute("DROP FUNCTION f4(int)");
    TestUtil.execute(con, "drop function bar()");
    TestUtil.dropTable(con, "duplicate");
    TestUtil.dropTable(con, "bestrowid");
    TestUtil.dropView(con, "viewtest");
    TestUtil.dropTable(con, "metadatatest");
    TestUtil.dropTable(con, "sercoltest");
    TestUtil.dropSequence(con, "sercoltest_b_seq");
    TestUtil.dropSequence(con, "sercoltest_c_seq");
    TestUtil.dropTable(con, "precision_test");
    TestUtil.dropTable(con, "\"a\\\"");
    TestUtil.dropTable(con, "\"a'\"");
    TestUtil.dropTable(con, "arraytable");
    TestUtil.dropTable(con, "intarraytable");
    TestUtil.dropTable(con, "customtable");
    TestUtil.dropType(con, "custom");
    TestUtil.dropType(con, "_custom");

    stmt.execute("DROP FUNCTION f1(int, varchar)");
    stmt.execute("DROP FUNCTION f2(int, varchar)");
    stmt.execute("DROP FUNCTION f3(int, varchar)");
    stmt.execute("DROP OPERATOR IF EXISTS & (numeric, integer)");
    stmt.execute("DROP FUNCTION f6(numeric, integer)");
    TestUtil.dropTable(con, "domaintable");
    TestUtil.dropDomain(con, "nndom");
    TestUtil.dropDomain(con, "varbit2");
    TestUtil.dropDomain(con, "float83");

    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v11) ) {
      TestUtil.dropTable(con, "pk_include_column");
    }

    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v12) ) {
      TestUtil.dropTable(con, "employee");
    }

    TestUtil.closeDB(con);
  }

  @Test
  void arrayTypeInfo() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "intarraytable", "a");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME"));
    con.createArrayOf("integer", new Integer[]{});
    TestUtil.closeQuietly(rs);
    rs = dbmd.getColumns(null, null, "intarraytable", "a");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME"));
    TestUtil.closeQuietly(rs);
  }

  @Test
  void arrayInt4DoubleDim() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "intarraytable", "b");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME")); // even int4[][] is represented as _int4
    con.createArrayOf("int4", new int[][]{{1, 2}, {3, 4}});
    rs = dbmd.getColumns(null, null, "intarraytable", "b");
    assertTrue(rs.next());
    assertEquals("_int4", rs.getString("TYPE_NAME")); // even int4[][] is represented as _int4
  }

  @Test
  void customArrayTypeInfo() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet res = dbmd.getColumns(null, null, "customtable", null);
    assertTrue(res.next());
    assertEquals("custom", res.getString("TYPE_NAME"));
    assertTrue(res.next());
    assertEquals("_custom", res.getString("TYPE_NAME"));
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      assertTrue(res.next());
      assertEquals("__custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v16)) {
        assertEquals("__custom_1", res.getString("TYPE_NAME"));
      } else {
        assertEquals("___custom", res.getString("TYPE_NAME"));
      }
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      con.createArrayOf("custom", new Object[]{});
      res = dbmd.getColumns(null, null, "customtable", null);
      assertTrue(res.next());
      assertEquals("custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("_custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      assertEquals("__custom", res.getString("TYPE_NAME"));
      assertTrue(res.next());
      if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v16)) {
        assertEquals("__custom_1", res.getString("TYPE_NAME"));
      } else {
        assertEquals("___custom", res.getString("TYPE_NAME"));
      }
    }
  }

  @Test
  void tables_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getTables("", "", "metadatates%", new String[]{"TABLE"});
    assertFalse(rs.next());
    rs.close();

    rs = dbmd.getColumns("", "", "meta%", "%");
    assertFalse(rs.next());
    rs.close();
  }

  @Test
  void tables_whenWrongCatalogGetMetaData_expectNoException() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getTables("FakeCatalog", "", null, new String[]{"TABLE"});
    assertFalse(rs.next());

    ResultSetMetaData resultSetMetaData = rs.getMetaData();
    for (int col = 1; col <= resultSetMetaData.getColumnCount(); col++) {
      resultSetMetaData.getColumnLabel(col);
    }

    rs.close();
  }

  @Test
  void tables() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getTables(null, null, "metadatates%", new String[]{"TABLE"});
    assertTrue(rs.next());
    String tableName = rs.getString("TABLE_NAME");
    assertEquals("metadatatest", tableName);
    String tableType = rs.getString("TABLE_TYPE");
    assertEquals("TABLE", tableType);
    assertEquals(5, rs.findColumn("REMARKS"));
    assertEquals(6, rs.findColumn("TYPE_CAT"));
    assertEquals(7, rs.findColumn("TYPE_SCHEM"));
    assertEquals(8, rs.findColumn("TYPE_NAME"));
    assertEquals(9, rs.findColumn("SELF_REFERENCING_COL_NAME"));
    assertEquals(10, rs.findColumn("REF_GENERATION"));

    // There should only be one row returned
    assertFalse(rs.next(), "getTables() returned too many rows");

    rs.close();

    rs = dbmd.getColumns(null, null, "meta%", "%");
    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("name", rs.getString("COLUMN_NAME"));
    assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("metadatatest", rs.getString("TABLE_NAME"));
    assertEquals("updated", rs.getString("COLUMN_NAME"));
    assertEquals(Types.TIMESTAMP, rs.getInt("DATA_TYPE"));
  }

  @Test
  void crossReference_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "vv", "a int not null, b int not null, constraint vv_pkey primary key ( a, b )");

      TestUtil.createTable(con1, "ww",
          "m int not null, n int not null, constraint m_pkey primary key ( m, n ), constraint ww_m_fkey foreign key ( m, n ) references vv ( a, b )");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);

      ResultSet rs = dbmd.getCrossReference("", "", "vv", null, null, "ww");
      assertFalse(rs.next());

      TestUtil.dropTable(con1, "vv");
      TestUtil.dropTable(con1, "ww");
    }
  }

  @Test
  void crossReference() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "vv", "a int not null, b int not null, constraint vv_pkey primary key ( a, b )");

      TestUtil.createTable(con1, "ww",
          "m int not null, n int not null, constraint m_pkey primary key ( m, n ), constraint ww_m_fkey foreign key ( m, n ) references vv ( a, b )");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);

      ResultSet rs = dbmd.getCrossReference(null, null, "vv", null, null, "ww");
      String[] expectedPkColumnNames = new String[]{"a", "b"};
      String[] expectedFkColumnNames = new String[]{"m", "n"};
      int numRows = 0;

      for (int j = 1; rs.next(); j++) {

        String pkTableName = rs.getString("PKTABLE_NAME");
        assertEquals("vv", pkTableName);

        String pkColumnName = rs.getString("PKCOLUMN_NAME");
        assertEquals(expectedPkColumnNames[j - 1], pkColumnName);

        String fkTableName = rs.getString("FKTABLE_NAME");
        assertEquals("ww", fkTableName);

        String fkColumnName = rs.getString("FKCOLUMN_NAME");
        assertEquals(expectedFkColumnNames[j - 1], fkColumnName);

        String fkName = rs.getString("FK_NAME");
        assertEquals("ww_m_fkey", fkName);

        String pkName = rs.getString("PK_NAME");
        assertEquals("vv_pkey", pkName);

        int keySeq = rs.getInt("KEY_SEQ");
        assertEquals(j, keySeq);
        numRows += 1;
      }
      assertEquals(2, numRows);

      TestUtil.dropTable(con1, "vv");
      TestUtil.dropTable(con1, "ww");
    }
  }

  @Test
  void foreignKeyActions_whenSchemaArgEmpty_expectNoResults() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.createTable(conn, "pkt", "id int primary key");
      TestUtil.createTable(conn, "fkt1",
          "id int references pkt on update restrict on delete cascade");
      TestUtil.createTable(conn, "fkt2",
          "id int references pkt on update set null on delete set default");
      DatabaseMetaData dbmd = conn.getMetaData();

      ResultSet rs = dbmd.getImportedKeys(null, "", "fkt1");
      assertFalse(rs.next());
      rs.close();

      rs = dbmd.getImportedKeys(null, "", "fkt2");
      assertFalse(rs.next());
      rs.close();

      TestUtil.dropTable(conn, "fkt2");
      TestUtil.dropTable(conn, "fkt1");
      TestUtil.dropTable(conn, "pkt");
    }
  }

  @Test
  void foreignKeyActions() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.createTable(conn, "pkt", "id int primary key");
      TestUtil.createTable(conn, "fkt1",
          "id int references pkt on update restrict on delete cascade");
      TestUtil.createTable(conn, "fkt2",
          "id int references pkt on update set null on delete set default");
      DatabaseMetaData dbmd = conn.getMetaData();

      ResultSet rs = dbmd.getImportedKeys(null, null, "fkt1");
      assertTrue(rs.next());
      assertEquals(DatabaseMetaData.importedKeyRestrict, rs.getInt("UPDATE_RULE"));
      assertEquals(DatabaseMetaData.importedKeyCascade, rs.getInt("DELETE_RULE"));
      rs.close();

      rs = dbmd.getImportedKeys(null, null, "fkt2");
      assertTrue(rs.next());
      assertEquals(DatabaseMetaData.importedKeySetNull, rs.getInt("UPDATE_RULE"));
      assertEquals(DatabaseMetaData.importedKeySetDefault, rs.getInt("DELETE_RULE"));
      rs.close();

      TestUtil.dropTable(conn, "fkt2");
      TestUtil.dropTable(conn, "fkt1");
      TestUtil.dropTable(conn, "pkt");
    }
  }

  @Test
  void foreignKeysToUniqueIndexes_whenCatalogAndSchemaArgsEmpty_expect() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "pkt",
          "a int not null, b int not null, CONSTRAINT pkt_pk_a PRIMARY KEY (a), CONSTRAINT pkt_un_b UNIQUE (b)");
      TestUtil.createTable(con1, "fkt",
          "c int, d int, CONSTRAINT fkt_fk_c FOREIGN KEY (c) REFERENCES pkt(b)");

      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getImportedKeys("", "", "fkt");
      assertFalse(rs.next());

      TestUtil.dropTable(con1, "fkt");
      TestUtil.dropTable(con1, "pkt");
    }
  }

  @Test
  void foreignKeysToUniqueIndexes() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "pkt",
          "a int not null, b int not null, CONSTRAINT pkt_pk_a PRIMARY KEY (a), CONSTRAINT pkt_un_b UNIQUE (b)");
      TestUtil.createTable(con1, "fkt",
          "c int, d int, CONSTRAINT fkt_fk_c FOREIGN KEY (c) REFERENCES pkt(b)");

      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getImportedKeys(null, null, "fkt");
      int j = 0;
      for (; rs.next(); j++) {
        assertEquals("pkt", rs.getString("PKTABLE_NAME"));
        assertEquals("fkt", rs.getString("FKTABLE_NAME"));
        assertEquals("pkt_un_b", rs.getString("PK_NAME"));
        assertEquals("b", rs.getString("PKCOLUMN_NAME"));
      }
      assertEquals(1, j);

      TestUtil.dropTable(con1, "fkt");
      TestUtil.dropTable(con1, "pkt");
    }
  }

  @Test
  void multiColumnForeignKeys_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "pkt",
          "a int not null, b int not null, CONSTRAINT pkt_pk PRIMARY KEY (a,b)");
      TestUtil.createTable(con1, "fkt",
          "c int, d int, CONSTRAINT fkt_fk_pkt FOREIGN KEY (c,d) REFERENCES pkt(b,a)");

      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getImportedKeys("", "", "fkt");
      assertFalse(rs.next());

      TestUtil.dropTable(con1, "fkt");
      TestUtil.dropTable(con1, "pkt");
    }
  }

  @Test
  void multiColumnForeignKeys() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "pkt",
          "a int not null, b int not null, CONSTRAINT pkt_pk PRIMARY KEY (a,b)");
      TestUtil.createTable(con1, "fkt",
          "c int, d int, CONSTRAINT fkt_fk_pkt FOREIGN KEY (c,d) REFERENCES pkt(b,a)");

      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getImportedKeys(null, null, "fkt");
      int j = 0;
      for (; rs.next(); j++) {
        assertEquals("pkt", rs.getString("PKTABLE_NAME"));
        assertEquals("fkt", rs.getString("FKTABLE_NAME"));
        assertEquals(j + 1, rs.getInt("KEY_SEQ"));
        if (j == 0) {
          assertEquals("b", rs.getString("PKCOLUMN_NAME"));
          assertEquals("c", rs.getString("FKCOLUMN_NAME"));
        } else {
          assertEquals("a", rs.getString("PKCOLUMN_NAME"));
          assertEquals("d", rs.getString("FKCOLUMN_NAME"));
        }
      }
      assertEquals(2, j);

      TestUtil.dropTable(con1, "fkt");
      TestUtil.dropTable(con1, "pkt");
    }
  }

  @Test
  void sameTableForeignKeys_whenSchemaArgEmpty_expectNoResults() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {

      TestUtil.createTable(con1, "person",
          "FIRST_NAME character varying(100) NOT NULL," + "LAST_NAME character varying(100) NOT NULL,"
              + "FIRST_NAME_PARENT_1 character varying(100),"
              + "LAST_NAME_PARENT_1 character varying(100),"
              + "FIRST_NAME_PARENT_2 character varying(100),"
              + "LAST_NAME_PARENT_2 character varying(100),"
              + "CONSTRAINT PERSON_pkey PRIMARY KEY (FIRST_NAME , LAST_NAME ),"
              + "CONSTRAINT PARENT_1_fkey FOREIGN KEY (FIRST_NAME_PARENT_1, LAST_NAME_PARENT_1)"
              + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
              + "ON UPDATE CASCADE ON DELETE CASCADE,"
              + "CONSTRAINT PARENT_2_fkey FOREIGN KEY (FIRST_NAME_PARENT_2, LAST_NAME_PARENT_2)"
              + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
              + "ON UPDATE CASCADE ON DELETE CASCADE");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);
      ResultSet rs = dbmd.getImportedKeys(null, "", "person");
      assertFalse(rs.next());

      TestUtil.dropTable(con1, "person");
    }
  }

  @Test
  void sameTableForeignKeys() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "person",
          "FIRST_NAME character varying(100) NOT NULL," + "LAST_NAME character varying(100) NOT NULL,"
              + "FIRST_NAME_PARENT_1 character varying(100),"
              + "LAST_NAME_PARENT_1 character varying(100),"
              + "FIRST_NAME_PARENT_2 character varying(100),"
              + "LAST_NAME_PARENT_2 character varying(100),"
              + "CONSTRAINT PERSON_pkey PRIMARY KEY (FIRST_NAME , LAST_NAME ),"
              + "CONSTRAINT PARENT_1_fkey FOREIGN KEY (FIRST_NAME_PARENT_1, LAST_NAME_PARENT_1)"
              + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
              + "ON UPDATE CASCADE ON DELETE CASCADE,"
              + "CONSTRAINT PARENT_2_fkey FOREIGN KEY (FIRST_NAME_PARENT_2, LAST_NAME_PARENT_2)"
              + "REFERENCES PERSON (FIRST_NAME, LAST_NAME) MATCH SIMPLE "
              + "ON UPDATE CASCADE ON DELETE CASCADE");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);
      ResultSet rs = dbmd.getImportedKeys(null, null, "person");

      final List<String> fkNames = new ArrayList<>();

      int lastFieldCount = -1;
      while (rs.next()) {
        // destination table (all foreign keys point to the same)
        String pkTableName = rs.getString("PKTABLE_NAME");
        assertEquals("person", pkTableName);

        // destination fields
        String pkColumnName = rs.getString("PKCOLUMN_NAME");
        assertTrue("first_name".equals(pkColumnName) || "last_name".equals(pkColumnName));

        // source table (all foreign keys are in the same)
        String fkTableName = rs.getString("FKTABLE_NAME");
        assertEquals("person", fkTableName);

        // foreign key name
        String fkName = rs.getString("FK_NAME");
        // sequence number within the foreign key
        int seq = rs.getInt("KEY_SEQ");
        if (seq == 1) {
          // begin new foreign key
          assertFalse(fkNames.contains(fkName));
          fkNames.add(fkName);
          // all foreign keys have 2 fields
          assertTrue(lastFieldCount < 0 || lastFieldCount == 2);
        } else {
          // continue foreign key, i.e. fkName matches the last foreign key
          assertEquals(fkNames.get(fkNames.size() - 1), fkName);
          // see always increases by 1
          assertEquals(seq, lastFieldCount + 1);
        }
        lastFieldCount = seq;
      }
      // there's more than one foreign key from a table to another
      assertEquals(2, fkNames.size());

      TestUtil.dropTable(con1, "person");
    }
  }

  @Test
  void foreignKeys_whenSchemaArgNull_expectNoResults() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "people", "id int4 primary key, name text");
      TestUtil.createTable(con1, "policy", "id int4 primary key, name text");

      TestUtil.createTable(con1, "users",
          "id int4 primary key, people_id int4, policy_id int4,"
              + "CONSTRAINT people FOREIGN KEY (people_id) references people(id),"
              + "constraint policy FOREIGN KEY (policy_id) references policy(id)");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);

      ResultSet rs = dbmd.getImportedKeys(null, "", "users");
      assertFalse(rs.next());
      rs.close();

      rs = dbmd.getExportedKeys(null, "", "people");
      assertFalse(rs.next());
      rs.close();

      TestUtil.dropTable(con1, "users");
      TestUtil.dropTable(con1, "people");
      TestUtil.dropTable(con1, "policy");
    }
  }

  @Test
  void foreignKeys() throws Exception {
    try (Connection con1 = TestUtil.openDB()) {
      TestUtil.createTable(con1, "people", "id int4 primary key, name text");
      TestUtil.createTable(con1, "policy", "id int4 primary key, name text");

      TestUtil.createTable(con1, "users",
          "id int4 primary key, people_id int4, policy_id int4,"
              + "CONSTRAINT people FOREIGN KEY (people_id) references people(id),"
              + "constraint policy FOREIGN KEY (policy_id) references policy(id)");

      DatabaseMetaData dbmd = con.getMetaData();
      assertNotNull(dbmd);

      ResultSet rs = dbmd.getImportedKeys(null, null, "users");
      int j = 0;
      for (; rs.next(); j++) {

        String pkTableName = rs.getString("PKTABLE_NAME");
        assertTrue("people".equals(pkTableName) || "policy".equals(pkTableName));

        String pkColumnName = rs.getString("PKCOLUMN_NAME");
        assertEquals("id", pkColumnName);

        String fkTableName = rs.getString("FKTABLE_NAME");
        assertEquals("users", fkTableName);

        String fkColumnName = rs.getString("FKCOLUMN_NAME");
        assertTrue("people_id".equals(fkColumnName) || "policy_id".equals(fkColumnName));

        String fkName = rs.getString("FK_NAME");
        assertTrue(fkName.startsWith("people") || fkName.startsWith("policy"));

        String pkName = rs.getString("PK_NAME");
        assertTrue("people_pkey".equals(pkName) || "policy_pkey".equals(pkName));

      }

      assertEquals(2, j);

      rs = dbmd.getExportedKeys(null, null, "people");

      // this is hacky, but it will serve the purpose
      assertTrue(rs.next());

      assertEquals("people", rs.getString("PKTABLE_NAME"));
      assertEquals("id", rs.getString("PKCOLUMN_NAME"));

      assertEquals("users", rs.getString("FKTABLE_NAME"));
      assertEquals("people_id", rs.getString("FKCOLUMN_NAME"));

      assertTrue(rs.getString("FK_NAME").startsWith("people"));

      TestUtil.dropTable(con1, "users");
      TestUtil.dropTable(con1, "people");
      TestUtil.dropTable(con1, "policy");
    }
  }

  @Test
  void numericPrecision() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getColumns(null, "public", "precision_test", "%");
    assertTrue(rs.next(), "It should have a row for the first column");
    assertEquals(0, rs.getInt("COLUMN_SIZE"), "The column size should be zero");
    assertFalse(rs.next(), "It should have a single column");
  }

  @Test
  void columns() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    String [] metadataColumns = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                                 "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                                 "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
                                 "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                                 "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA",
                                 "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"};

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getColumns(null, null, "pg_class", null);
    if ( rs.next() ) {
      for (int i = 0; i < metadataColumns.length; i++) {
        assertEquals(i + 1, rs.findColumn(metadataColumns[i]));
      }
    }
    rs.close();
  }

  @Test
  void droppedColumns() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      return;
    }

    Statement stmt = con.createStatement();
    stmt.execute("ALTER TABLE metadatatest DROP name");
    stmt.execute("ALTER TABLE metadatatest DROP colour");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    try (ResultSet rs = dbmd.getColumns(null, null, "metadatatest", null)) {

      assertTrue(rs.next());
      assertEquals("id", rs.getString("COLUMN_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));

      assertTrue(rs.next());
      assertEquals("updated", rs.getString("COLUMN_NAME"));
      assertEquals(2, rs.getInt("ORDINAL_POSITION"));

      assertTrue(rs.next());
      assertEquals("quest", rs.getString("COLUMN_NAME"));
      assertEquals(3, rs.getInt("ORDINAL_POSITION"));
    }

    try (ResultSet rs = dbmd.getColumns(null, null, "metadatatest", "quest")) {
      assertTrue(rs.next());
      assertEquals("quest", rs.getString("COLUMN_NAME"));
      assertEquals(3, rs.getInt("ORDINAL_POSITION"));
      assertFalse(rs.next());
    }

    /* getFunctionColumns also has to be aware of dropped columns
       add this in here to make sure it can deal with them
     */
    try (ResultSet rs = dbmd.getFunctionColumns(null, null, "f4", null)) {
      assertTrue(rs.next());

      assertTrue(rs.next());
      assertEquals("id", rs.getString(4));

      assertTrue(rs.next());
      assertEquals("updated", rs.getString(4));
    }

  }

  @Test
  void testGetFunctionColumnsBadCatalog() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    try (ResultSet rs = dbmd.getColumns("nonsensecatalog", null, "sercoltest", null)) {
      assertFalse(rs.next());
    }
  }

  @Test
  void serialColumns() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    try (ResultSet rs = dbmd.getColumns(null, null, "sercoltest", null)) {
      int rownum = 0;
      while (rs.next()) {
        assertEquals("sercoltest", rs.getString("TABLE_NAME"));
        assertEquals(rownum + 1, rs.getInt("ORDINAL_POSITION"));
        if (rownum == 0) {
          assertEquals("int4", rs.getString("TYPE_NAME"));

        } else if (rownum == 1) {
          assertEquals("serial", rs.getString("TYPE_NAME"));
          assertTrue(rs.getBoolean("IS_AUTOINCREMENT"));
        } else if (rownum == 2) {
          assertEquals("bigserial", rs.getString("TYPE_NAME"));
          assertTrue(rs.getBoolean("IS_AUTOINCREMENT"));
        }

        rownum++;
      }
      assertEquals(3, rownum);
    }
  }

  @Test
  void columnPrivileges() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    try (ResultSet rs = dbmd.getColumnPrivileges(null, null, "pg_statistic", null)) {
      assertTrue(rs.next());
    }
    try (ResultSet rs = dbmd.getColumnPrivileges("nonsensecatalog", null, "pg_statistic", null)) {
      assertFalse(rs.next());
    }
  }

  /*
   * Helper function - this logic is used several times to test relation privileges
   */
  public void relationPrivilegesHelper(String relationName) throws SQLException {
    // Query PG catalog for privileges
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getTablePrivileges(null, null, relationName);

    // Parse result to check if table/view owner has select privileges
    boolean foundSelect = false;
    while (rs.next()) {
      if (rs.getString("GRANTEE").equals(TestUtil.getUser())
          && "SELECT".equals(rs.getString("PRIVILEGE"))) {
        foundSelect = true;
      }
    }
    rs.close();

    // Check test condition
    assertTrue(foundSelect,
              "Couldn't find SELECT priv on relation "
                + relationName + "  for " + TestUtil.getUser());
  }

  @Test
  void tablePrivileges() throws SQLException {
    relationPrivilegesHelper("metadatatest");
  }

  @Test
  void viewPrivileges() throws SQLException {
    relationPrivilegesHelper("viewtest");
  }

  @Test
  void materializedViewPrivileges() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_3));
    TestUtil.createMaterializedView(con, "matviewtest", "SELECT id, quest FROM metadatatest");
    try {
      relationPrivilegesHelper("matviewtest");
    } finally {
      TestUtil.dropMaterializedView(con, "matviewtest");
    }
  }

  @Test
  void noTablePrivileges() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("REVOKE ALL ON metadatatest FROM PUBLIC");
    stmt.execute("REVOKE ALL ON metadatatest FROM " + TestUtil.getUser());
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTablePrivileges(null, null, "metadatatest");
    assertFalse(rs.next());
  }

  @Test
  void primaryKeys() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getPrimaryKeys(null, null, "pg_class");
    rs.close();
  }

  @Test
  void indexInfo() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("create index idx_id on metadatatest (id)");
    stmt.execute("create index idx_func_single on metadatatest (upper(colour))");
    stmt.execute("create unique index idx_un_id on metadatatest(id)");
    stmt.execute("create index idx_func_multi on metadatatest (upper(colour), upper(quest))");
    stmt.execute("create index idx_func_mixed on metadatatest (colour, upper(quest))");

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    try (ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false)) {

      assertTrue(rs.next());
      assertEquals("idx_un_id", rs.getString("INDEX_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));
      assertEquals("id", rs.getString("COLUMN_NAME"));
      assertFalse(rs.getBoolean("NON_UNIQUE"));

      assertTrue(rs.next());
      assertEquals("idx_func_mixed", rs.getString("INDEX_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));
      assertEquals("colour", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("idx_func_mixed", rs.getString("INDEX_NAME"));
      assertEquals(2, rs.getInt("ORDINAL_POSITION"));
      assertEquals("upper(quest)", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("idx_func_multi", rs.getString("INDEX_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));
      assertEquals("upper(colour)", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("idx_func_multi", rs.getString("INDEX_NAME"));
      assertEquals(2, rs.getInt("ORDINAL_POSITION"));
      assertEquals("upper(quest)", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("idx_func_single", rs.getString("INDEX_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));
      assertEquals("upper(colour)", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("idx_id", rs.getString("INDEX_NAME"));
      assertEquals(1, rs.getInt("ORDINAL_POSITION"));
      assertEquals("id", rs.getString("COLUMN_NAME"));
      assertTrue(rs.getBoolean("NON_UNIQUE"));

      assertFalse(rs.next());

    }
  }

  /**
   * Order defined at
   * https://docs.oracle.com/javase/8/docs/api/java/sql/DatabaseMetaData.html#getIndexInfo-java.lang.String-java.lang.String-java.lang.String-boolean-boolean-
   */
  @Test
  void indexInfoColumnOrder() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    /*
    two tests here
    first is that we should not return any reults for a nonsensecatalog
    second is ordering of fields
     */
    try (ResultSet rs = dbmd.getIndexInfo("nonsensecatalog", null, "metadatatest", false, false)) {
      assertFalse(rs.next());
      assertEquals(1, rs.findColumn("TABLE_CAT"));
      assertEquals(2, rs.findColumn("TABLE_SCHEM"));
      assertEquals(3, rs.findColumn("TABLE_NAME"));
      assertEquals(4, rs.findColumn("NON_UNIQUE"));
      assertEquals(5, rs.findColumn("INDEX_QUALIFIER"));
      assertEquals(6, rs.findColumn("INDEX_NAME"));
      assertEquals(7, rs.findColumn("TYPE"));
      assertEquals(8, rs.findColumn("ORDINAL_POSITION"));
      assertEquals(9, rs.findColumn("COLUMN_NAME"));
      assertEquals(10, rs.findColumn("ASC_OR_DESC"));
      assertEquals(11, rs.findColumn("CARDINALITY"));
      assertEquals(12, rs.findColumn("PAGES"));
      assertEquals(13, rs.findColumn("FILTER_CONDITION"));
    }
    try (ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false)) {
      assertEquals(1, rs.findColumn("TABLE_CAT"));
      assertEquals(2, rs.findColumn("TABLE_SCHEM"));
      assertEquals(3, rs.findColumn("TABLE_NAME"));
      assertEquals(4, rs.findColumn("NON_UNIQUE"));
      assertEquals(5, rs.findColumn("INDEX_QUALIFIER"));
      assertEquals(6, rs.findColumn("INDEX_NAME"));
      assertEquals(7, rs.findColumn("TYPE"));
      assertEquals(8, rs.findColumn("ORDINAL_POSITION"));
      assertEquals(9, rs.findColumn("COLUMN_NAME"));
      assertEquals(10, rs.findColumn("ASC_OR_DESC"));
      assertEquals(11, rs.findColumn("CARDINALITY"));
      assertEquals(12, rs.findColumn("PAGES"));
      assertEquals(13, rs.findColumn("FILTER_CONDITION"));
    }
  }

  @Test
  void indexInfoColumnCase() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    try (ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false)) {
      ResultSetMetaData rsmd = rs.getMetaData();
      for (int i = 1; i < rsmd.getColumnCount() + 1; i++) {
        char[] chars = rsmd.getColumnName(i).toCharArray();
        for (int j = 0; j < chars.length; j++) {
          if (Character.isAlphabetic(chars[j])) {
            assertTrue(Character.isUpperCase(chars[j]), "Column: " + rsmd.getColumnName(i) + " is not UPPER CASE");
          }
        }
      }
    }
  }

  @Test
  void notNullDomainColumn_whenCatalogAndSchemaAndColumnNameArgsEmpty_expectNoResults() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns("", "", "domaintable", "");
    assertFalse(rs.next());
  }

  @Test
  void notNullDomainColumn() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "domaintable", null);
    assertTrue(rs.next());
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals("NO", rs.getString("IS_NULLABLE"));
    assertTrue(rs.next());
    assertTrue(rs.next());
    assertFalse(rs.next());
  }

  @Test
  void domainColumnSize_whenCatalogAndSchemaAndColumnNameArgsEmpty_expectNoResults() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns("", "", "domaintable", "");
    assertFalse(rs.next());
  }

  @Test
  void domainColumnSize() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "domaintable", null);
    assertTrue(rs.next());
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals(10, rs.getInt("COLUMN_SIZE"));
    assertTrue(rs.next());
    assertEquals("v", rs.getString("COLUMN_NAME"));
    assertEquals(3, rs.getInt("COLUMN_SIZE"));
    assertTrue(rs.next());
    assertEquals("f", rs.getString("COLUMN_NAME"));
    assertEquals(8, rs.getInt("COLUMN_SIZE"));
    assertEquals(3, rs.getInt("DECIMAL_DIGITS"));

  }

  @Test
  void ascDescIndexInfo() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      return;
    }

    Statement stmt = con.createStatement();
    stmt.execute("CREATE INDEX idx_a_d ON metadatatest (id ASC, quest DESC)");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_a_d", rs.getString("INDEX_NAME"));
    assertEquals("id", rs.getString("COLUMN_NAME"));
    assertEquals("A", rs.getString("ASC_OR_DESC"));

    assertTrue(rs.next());
    assertEquals("idx_a_d", rs.getString("INDEX_NAME"));
    assertEquals("quest", rs.getString("COLUMN_NAME"));
    assertEquals("D", rs.getString("ASC_OR_DESC"));
  }

  @Test
  void partialIndexInfo() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("create index idx_p_name_id on metadatatest (name) where id > 5");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_p_name_id", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("name", rs.getString("COLUMN_NAME"));
    assertEquals("(id > 5)", rs.getString("FILTER_CONDITION"));
    assertTrue(rs.getBoolean("NON_UNIQUE"));

    rs.close();
  }

  @Test
  void remarkIndexInfo() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("create index idx_name on metadatatest (name)");
    stmt.execute("comment on index idx_name is 'index_comment'");
    stmt.close();

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getIndexInfo(null, null, "metadatatest", false, false);

    assertTrue(rs.next());
    assertEquals("idx_name", rs.getString("INDEX_NAME"));
    assertEquals(1, rs.getInt("ORDINAL_POSITION"));
    assertEquals("name", rs.getString("COLUMN_NAME"));
    assertEquals("index_comment", rs.getString("REMARKS"));

    rs.close();
  }

  @Test
  void tableTypes() throws SQLException {
    final List<String> expectedTableTypes = new ArrayList<>(Arrays.asList("FOREIGN TABLE", "INDEX", "PARTITIONED INDEX",
        "MATERIALIZED VIEW", "PARTITIONED TABLE", "SEQUENCE", "SYSTEM INDEX", "SYSTEM TABLE", "SYSTEM TOAST INDEX",
        "SYSTEM TOAST TABLE", "SYSTEM VIEW", "TABLE", "TEMPORARY INDEX", "TEMPORARY SEQUENCE", "TEMPORARY TABLE",
        "TEMPORARY VIEW", "TYPE", "VIEW"));
    final List<String> foundTableTypes = new ArrayList<>();

    // Test that no exceptions are thrown
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    // Test that the table types returned are the same as those expected
    ResultSet rs = dbmd.getTableTypes();
    while (rs.next()) {
      String tableType = new String(rs.getBytes(1));
      foundTableTypes.add(tableType);
    }
    rs.close();
    Collections.sort(expectedTableTypes);
    Collections.sort(foundTableTypes);
    assertEquals(foundTableTypes, expectedTableTypes, "The table types received from DatabaseMetaData should match the 18 expected types");
  }

  @Test
  void funcWithoutNames() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f1", null);

    assertTrue(rs.next());
    assertEquals("returnValue", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));

    assertTrue(rs.next());
    assertEquals("$1", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("$2", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertFalse(rs.next());

    rs.close();
  }

  @Test
  void funcWithNames() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f2", null);

    assertTrue(rs.next());

    assertTrue(rs.next());
    assertEquals("a", rs.getString(4));

    assertTrue(rs.next());
    assertEquals("b", rs.getString(4));

    assertFalse(rs.next());

    rs.close();
  }

  @Test
  void funcWithDirection() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f3", null);

    assertTrue(rs.next());
    assertEquals("a", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("b", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnInOut, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("c", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnOut, rs.getInt(5));
    assertEquals(Types.TIMESTAMP, rs.getInt(6));

    rs.close();
  }

  @Test
  void funcReturningComposite() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f4", null);

    assertTrue(rs.next());
    assertEquals("$1", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("id", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("name", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("updated", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.TIMESTAMP, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("colour", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertTrue(rs.next());
    assertEquals("quest", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnResult, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));

    assertFalse(rs.next());
    rs.close();
  }

  @Test
  void funcReturningTable() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      return;
    }
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getProcedureColumns(null, null, "f5", null);
    assertTrue(rs.next());
    assertEquals("returnValue", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertTrue(rs.next());
    assertEquals("i", rs.getString(4));
    assertEquals(DatabaseMetaData.procedureColumnReturn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertFalse(rs.next());
    rs.close();
  }

  @Test
  void versionColumns() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getVersionColumns(null, null, "pg_class");
    rs.close();
  }

  @Test
  void bestRowIdentifier() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    try (ResultSet rs =
             dbmd.getBestRowIdentifier(null, null, "bestrowid", DatabaseMetaData.bestRowSession, false)) {
      assertTrue(rs.next());
    }
    try (ResultSet rs =
             dbmd.getBestRowIdentifier("nonsensecatalog", null, "bestrowid", DatabaseMetaData.bestRowSession, false)) {
      assertFalse(rs.next());
    }

  }

  @Test
  void procedures() throws SQLException {
    // At the moment just test that no exceptions are thrown KJ
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    ResultSet rs = dbmd.getProcedures(null, null, null);
    rs.close();
  }

  @Test
  void catalogs() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    try (ResultSet rs = dbmd.getCatalogs()) {
      List<String> catalogs = new ArrayList<>();
      while (rs.next()) {
        catalogs.add(rs.getString("TABLE_CAT"));
      }
      List<String> sortedCatalogs = new ArrayList<>(catalogs);
      Collections.sort(sortedCatalogs);

      assertThat(
          catalogs,
          allOf(
              hasItem("test"),
              hasItem("postgres"),
              equalTo(sortedCatalogs)
          )
      );
    }
  }

  @Test
  void schemas() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    ResultSet rs = dbmd.getSchemas();
    boolean foundPublic = false;
    boolean foundEmpty = false;
    boolean foundPGCatalog = false;
    int count;

    for (count = 0; rs.next(); count++) {
      String schema = rs.getString("TABLE_SCHEM");
      if ("public".equals(schema)) {
        foundPublic = true;
      } else if ("".equals(schema)) {
        foundEmpty = true;
      } else if ("pg_catalog".equals(schema)) {
        foundPGCatalog = true;
      }
    }
    rs.close();
    assertTrue(count >= 2);
    assertTrue(foundPublic);
    assertTrue(foundPGCatalog);
    assertFalse(foundEmpty);
  }

  @Test
  @DisabledIfServerVersionBelow("9.2")
  void escaping() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTables(null, null, "a'", new String[]{"TABLE"});
    assertTrue(rs.next());
    rs = dbmd.getTables(null, null, "a\\\\", new String[]{"TABLE"});
    assertTrue(rs.next());
    // PostgreSQL 9.1 fails LIKE pattern must not end with escape character even though
    // we pass the pattern as a bind variable, so it should not be a subject to escaping
    rs = dbmd.getTables(null, null, "a\\", new String[]{"TABLE"});
    assertFalse(rs.next());
  }

  @Test
  void searchStringEscape() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    String pattern = dbmd.getSearchStringEscape() + "_";
    PreparedStatement pstmt = con.prepareStatement("SELECT 'a' LIKE ?, '_' LIKE ?");
    pstmt.setString(1, pattern);
    pstmt.setString(2, pattern);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertFalse(rs.getBoolean(1));
    assertTrue(rs.getBoolean(2));
    rs.close();
    pstmt.close();
  }

  @Test
  void getUDTQualified() throws Exception {
    Statement stmt = null;
    try {
      stmt = con.createStatement();
      stmt.execute("create schema jdbc");
      stmt.execute("create type jdbc.testint8 as (i int8)");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "jdbc.testint8", null);
      assertTrue(rs.next());
      String cat;
      String schema;
      String typeName;
      String remarks;
      String className;
      int dataType;
      int baseType;

      cat = rs.getString("TYPE_CAT");
      schema = rs.getString("TYPE_SCHEM");
      typeName = rs.getString("TYPE_NAME");
      className = rs.getString("CLASS_NAME");
      dataType = rs.getInt("DATA_TYPE");
      remarks = rs.getString("REMARKS");
      baseType = rs.getInt("BASE_TYPE");
      assertEquals("testint8", typeName, "type name ");
      assertEquals("jdbc", schema, "schema name ");

      // now test to see if the fully qualified stuff works as planned
      rs = dbmd.getUDTs(TestUtil.getDatabase(), "public", "catalog.jdbc.testint8", null);
      assertTrue(rs.next());
      cat = rs.getString("type_cat");
      schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      className = rs.getString("class_name");
      dataType = rs.getInt("data_type");
      remarks = rs.getString("remarks");
      baseType = rs.getInt("base_type");
      assertEquals("testint8", typeName, "type name ");
      assertEquals("jdbc", schema, "schema name ");
    } finally {
      try {
        if (stmt != null) {
          stmt.close();
        }
        stmt = con.createStatement();
        stmt.execute("drop type jdbc.testint8");
        stmt.execute("drop schema jdbc");
      } catch (Exception ex) {
      }
    }

  }

  @Test
  void getUDT1() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", null);
      assertTrue(rs.next());

      String cat = rs.getString("type_cat");
      String schema = rs.getString("type_schem");
      String typeName = rs.getString("type_name");
      String className = rs.getString("class_name");
      int dataType = rs.getInt("data_type");
      String remarks = rs.getString("remarks");

      int baseType = rs.getInt("base_type");
      assertEquals(Types.BIGINT, baseType, "base type");
      assertEquals(Types.DISTINCT, dataType, "data type");
      assertEquals("testint8", typeName, "type name ");
      assertEquals("jdbc123", remarks, "remarks");
    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  void getUDT2() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", new int[]{Types.DISTINCT, Types.STRUCT});
      assertTrue(rs.next());
      String typeName;

      String cat = rs.getString("type_cat");
      String schema = rs.getString("type_schem");
      typeName = rs.getString("type_name");
      String className = rs.getString("class_name");
      int dataType = rs.getInt("data_type");
      String remarks = rs.getString("remarks");

      int baseType = rs.getInt("base_type");
      assertEquals(Types.BIGINT, baseType, "base type");
      assertEquals(Types.DISTINCT, dataType, "data type");
      assertEquals("testint8", typeName, "type name ");
      assertEquals("jdbc123", remarks, "remarks");
    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  void getUDT3() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create domain testint8 as int8");
      stmt.execute("comment on domain testint8 is 'jdbc123'");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", new int[]{Types.DISTINCT});
      assertTrue(rs.next());

      String cat = rs.getString("type_cat");
      String schema = rs.getString("type_schem");
      String typeName = rs.getString("type_name");
      String className = rs.getString("class_name");
      int dataType = rs.getInt("data_type");
      String remarks = rs.getString("remarks");

      int baseType = rs.getInt("base_type");
      assertEquals(Types.BIGINT, baseType, "base type");
      assertEquals(Types.DISTINCT, dataType, "data type");
      assertEquals("testint8", typeName, "type name ");
      assertEquals("jdbc123", remarks, "remarks");
    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop domain testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  void getUDT4() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create type testint8 as (i int8)");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs(null, null, "testint8", null);
      assertTrue(rs.next());

      String cat = rs.getString("type_cat");
      String schema = rs.getString("type_schem");
      String typeName = rs.getString("type_name");
      String className = rs.getString("class_name");
      int dataType = rs.getInt("data_type");
      String remarks = rs.getString("remarks");

      int baseType = rs.getInt("base_type");
      assertTrue(rs.wasNull(), "base type");
      assertEquals(Types.STRUCT, dataType, "data type");
      assertEquals("testint8", typeName, "type name ");
    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop type testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  void getUDT5() throws Exception {
    try {
      Statement stmt = con.createStatement();
      stmt.execute("create type testint8 as (i int8)");
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getUDTs("nonsensecatalog", null, "testint8", null);
      assertFalse(rs.next());

      assertEquals(1, rs.findColumn("type_cat"));
      assertEquals(2, rs.findColumn("type_schem"));
      assertEquals(3, rs.findColumn("type_name"));
      assertEquals(4, rs.findColumn("class_name"));
      assertEquals(5, rs.findColumn("data_type"));
      assertEquals(6, rs.findColumn("remarks"));
      assertEquals(7, rs.findColumn("base_type"));
    } finally {
      try {
        Statement stmt = con.createStatement();
        stmt.execute("drop type testint8");
      } catch (Exception ex) {
      }
    }
  }

  @Test
  void types() throws SQLException {
    // https://www.postgresql.org/docs/8.2/static/datatype.html
    List<String> stringTypeList = new ArrayList<>();
    stringTypeList.addAll(Arrays.asList("bit",
            "bool",
            "box",
            "bytea",
            "char",
            "cidr",
            "circle",
            "date",
            "float4",
            "float8",
            "inet",
            "int2",
            "int4",
            "int8",
            "interval",
            "line",
            "lseg",
            "macaddr",
            "money",
            "numeric",
            "path",
            "point",
            "polygon",
            "text",
            "time",
            "timestamp",
            "timestamptz",
            "timetz",
            "varbit",
            "varchar"));
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3)) {
      stringTypeList.add("tsquery");
      stringTypeList.add("tsvector");
      stringTypeList.add("txid_snapshot");
      stringTypeList.add("uuid");
      stringTypeList.add("xml");
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2)) {
      stringTypeList.add("json");
    }
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4)) {
      stringTypeList.add("jsonb");
      stringTypeList.add("pg_lsn");
    }

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    List<String> types = new ArrayList<>();

    while (rs.next()) {
      types.add(rs.getString("TYPE_NAME"));
    }
    for (String typeName : stringTypeList) {
      assertTrue(types.contains(typeName));
    }
  }

  @Test
  void typeInfoSigned() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    while (rs.next()) {
      if ("int4".equals(rs.getString("TYPE_NAME"))) {
        assertFalse(rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      } else if ("float8".equals(rs.getString("TYPE_NAME"))) {
        assertFalse(rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      } else if ("text".equals(rs.getString("TYPE_NAME"))) {
        assertTrue(rs.getBoolean("UNSIGNED_ATTRIBUTE"));
      }
    }
  }

  @Test
  void typeInfoQuoting() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    while (rs.next()) {
      if ("int4".equals(rs.getString("TYPE_NAME"))) {
        assertNull(rs.getString("LITERAL_PREFIX"));
      } else if ("text".equals(rs.getString("TYPE_NAME"))) {
        assertEquals("'", rs.getString("LITERAL_PREFIX"));
        assertEquals("'", rs.getString("LITERAL_SUFFIX"));
      }
    }
  }

  @Test
  void informationAboutArrayTypes_whenCatalogSchemaColumnNamePatternArgsEmpty_expectNoResults() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns("", "", "arraytable", "");
    assertFalse(rs.next());
  }

  @Test
  void informationAboutArrayTypes() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "arraytable", null);
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(5, rs.getInt("COLUMN_SIZE"));
    assertEquals(2, rs.getInt("DECIMAL_DIGITS"));
    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals(100, rs.getInt("COLUMN_SIZE"));
    assertFalse(rs.next());
  }

  @Test
  void primaryKeysWithIncludeColumns_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws SQLException {
    String tableName = "pk_include_column";
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getPrimaryKeys("", "", tableName);
      assertFalse(rs.next());
    }
  }

  @Test
  void primaryKeysWithIncludeColumns() throws SQLException {
    String tableName = "pk_include_column";
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getPrimaryKeys(null, null, tableName);

      // getPrimaryKeys should return only the key columns and not the included column
      assertTrue(rs.next());
      assertEquals(tableName, rs.getString("TABLE_NAME"));
      assertEquals("b", rs.getString("COLUMN_NAME"));

      assertTrue(rs.next());
      assertEquals("d", rs.getString("COLUMN_NAME"));

      assertFalse(rs.next());
    }
  }

  @Test
  void partitionedTablesIndex_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists measurement");
        stmt.execute(
            "CREATE TABLE measurement (logdate date not null primary key,peaktemp int,unitsales int ) PARTITION BY RANGE (logdate);");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getPrimaryKeys("", "", "measurement");
        assertFalse(rs.next());

        stmt.execute("drop table if exists measurement");
      }
    }
  }

  @Test
  void partitionedTablesIndex() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists measurement");
        stmt.execute(
            "CREATE TABLE measurement (logdate date not null primary key,peaktemp int,unitsales int ) PARTITION BY RANGE (logdate);");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getPrimaryKeys(null, null, "measurement");
        assertTrue(rs.next());
        assertEquals("measurement_pkey", rs.getString(6));

        stmt.execute("drop table if exists measurement");
      }
    }
  }

  @Test
  void partitionedTables_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists measurement");
        stmt.execute(
            "CREATE TABLE measurement (logdate date not null primary key,peaktemp int,unitsales int ) PARTITION BY RANGE (logdate);");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getTables("", "", "measurement", new String[]{"PARTITIONED TABLE"});
        assertFalse(rs.next());
        rs.close();
        rs = dbmd.getPrimaryKeys("", "", "measurement");
        assertFalse(rs.next());

        stmt.execute("drop table if exists measurement");
      }
    }
  }

  @Test
  void partitionedTables() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists measurement");
        stmt.execute(
            "CREATE TABLE measurement (logdate date not null primary key,peaktemp int,unitsales int ) PARTITION BY RANGE (logdate);");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getTables(null, null, "measurement", new String[]{"PARTITIONED TABLE"});
        assertTrue(rs.next());
        assertEquals("measurement", rs.getString("table_name"));
        rs.close();
        rs = dbmd.getPrimaryKeys(null, null, "measurement");
        assertTrue(rs.next());
        assertEquals("measurement_pkey", rs.getString(6));

        stmt.execute("drop table if exists measurement");
      }
    }
  }

  @Test
  void identityColumns_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws SQLException {
    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v10) ) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists test_new");
        stmt.execute("CREATE TABLE test_new ("
            + "id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
            + "payload text)");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getColumns("", "", "test_new", "id");
        assertFalse(rs.next());

        stmt.execute("drop table test_new");
      }
    }
  }

  @Test
  void identityColumns() throws SQLException {
    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v10) ) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("drop table if exists test_new");
        stmt.execute("CREATE TABLE test_new ("
            + "id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
            + "payload text)");
        DatabaseMetaData dbmd = con.getMetaData();
        ResultSet rs = dbmd.getColumns(null, null, "test_new", "id");
        assertTrue(rs.next());
        assertEquals("id", rs.getString("COLUMN_NAME"));
        assertTrue(rs.getBoolean("IS_AUTOINCREMENT"));

        stmt.execute("drop table test_new");
      }
    }
  }

  @Test
  void generatedColumns() throws SQLException {
    if ( TestUtil.haveMinimumServerVersion(con, ServerVersion.v12) ) {
      DatabaseMetaData dbmd = con.getMetaData();
      ResultSet rs = dbmd.getColumns(null, null, "employee", "gross_pay");
      assertTrue(rs.next());
      assertEquals("gross_pay", rs.getString("COLUMN_NAME"));
      assertTrue(rs.getBoolean("IS_GENERATEDCOLUMN"));
    }
  }

  @Test
  void getSQLKeywords() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    String keywords = dbmd.getSQLKeywords();

    // We don't want SQL:2003 keywords returned, so check for that.
    String sql2003 = "a,abs,absolute,action,ada,add,admin,after,all,allocate,alter,always,and,any,are,"
        + "array,as,asc,asensitive,assertion,assignment,asymmetric,at,atomic,attribute,attributes,"
        + "authorization,avg,before,begin,bernoulli,between,bigint,binary,blob,boolean,both,breadth,by,"
        + "c,call,called,cardinality,cascade,cascaded,case,cast,catalog,catalog_name,ceil,ceiling,chain,"
        + "char,char_length,character,character_length,character_set_catalog,character_set_name,"
        + "character_set_schema,characteristics,characters,check,checked,class_origin,clob,close,"
        + "coalesce,cobol,code_units,collate,collation,collation_catalog,collation_name,collation_schema,"
        + "collect,column,column_name,command_function,command_function_code,commit,committed,condition,"
        + "condition_number,connect,connection_name,constraint,constraint_catalog,constraint_name,"
        + "constraint_schema,constraints,constructors,contains,continue,convert,corr,corresponding,count,"
        + "covar_pop,covar_samp,create,cross,cube,cume_dist,current,current_collation,current_date,"
        + "current_default_transform_group,current_path,current_role,current_time,current_timestamp,"
        + "current_transform_group_for_type,current_user,cursor,cursor_name,cycle,data,date,datetime_interval_code,"
        + "datetime_interval_precision,day,deallocate,dec,decimal,declare,default,defaults,deferrable,"
        + "deferred,defined,definer,degree,delete,dense_rank,depth,deref,derived,desc,describe,"
        + "descriptor,deterministic,diagnostics,disconnect,dispatch,distinct,domain,double,drop,dynamic,"
        + "dynamic_function,dynamic_function_code,each,element,else,end,end-exec,equals,escape,every,"
        + "except,exception,exclude,excluding,exec,execute,exists,exp,external,extract,false,fetch,filter,"
        + "final,first,float,floor,following,for,foreign,fortran,found,free,from,full,function,fusion,"
        + "g,general,get,global,go,goto,grant,granted,group,grouping,having,hierarchy,hold,hour,identity,"
        + "immediate,implementation,in,including,increment,indicator,initially,inner,inout,input,"
        + "insensitive,insert,instance,instantiable,int,integer,intersect,intersection,interval,into,"
        + "invoker,is,isolation,join,k,key,key_member,key_type,language,large,last,lateral,leading,left,"
        + "length,level,like,ln,local,localtime,localtimestamp,locator,lower,m,map,match,matched,max,"
        + "maxvalue,member,merge,message_length,message_octet_length,message_text,method,min,minute,"
        + "minvalue,mod,modifies,module,month,more,multiset,mumps,name,names,national,natural,nchar,"
        + "nclob,nesting,new,next,no,none,normalize,normalized,not,null,nullable,nullif,nulls,number,"
        + "numeric,object,octet_length,octets,of,old,on,only,open,option,options,or,order,ordering,"
        + "ordinality,others,out,outer,output,over,overlaps,overlay,overriding,pad,parameter,parameter_mode,"
        + "parameter_name,parameter_ordinal_position,parameter_specific_catalog,parameter_specific_name,"
        + "parameter_specific_schema,partial,partition,pascal,path,percent_rank,percentile_cont,"
        + "percentile_disc,placing,pli,position,power,preceding,precision,prepare,preserve,primary,"
        + "prior,privileges,procedure,public,range,rank,read,reads,real,recursive,ref,references,"
        + "referencing,regr_avgx,regr_avgy,regr_count,regr_intercept,regr_r2,regr_slope,regr_sxx,"
        + "regr_sxy,regr_syy,relative,release,repeatable,restart,result,return,returned_cardinality,"
        + "returned_length,returned_octet_length,returned_sqlstate,returns,revoke,right,role,rollback,"
        + "rollup,routine,routine_catalog,routine_name,routine_schema,row,row_count,row_number,rows,"
        + "savepoint,scale,schema,schema_name,scope_catalog,scope_name,scope_schema,scroll,search,second,"
        + "section,security,select,self,sensitive,sequence,serializable,server_name,session,session_user,"
        + "set,sets,similar,simple,size,smallint,some,source,space,specific,specific_name,specifictype,sql,"
        + "sqlexception,sqlstate,sqlwarning,sqrt,start,state,statement,static,stddev_pop,stddev_samp,"
        + "structure,style,subclass_origin,submultiset,substring,sum,symmetric,system,system_user,table,"
        + "table_name,tablesample,temporary,then,ties,time,timestamp,timezone_hour,timezone_minute,to,"
        + "top_level_count,trailing,transaction,transaction_active,transactions_committed,"
        + "transactions_rolled_back,transform,transforms,translate,translation,treat,trigger,trigger_catalog,"
        + "trigger_name,trigger_schema,trim,true,type,uescape,unbounded,uncommitted,under,union,unique,"
        + "unknown,unnamed,unnest,update,upper,usage,user,user_defined_type_catalog,user_defined_type_code,"
        + "user_defined_type_name,user_defined_type_schema,using,value,values,var_pop,var_samp,varchar,"
        + "varying,view,when,whenever,where,width_bucket,window,with,within,without,work,write,year,zone";

    String[] excludeSQL2003 = sql2003.split(",");
    String[] returned = keywords.split(",");
    Set<String> returnedSet = new HashSet<>(Arrays.asList(returned));
    assertEquals(returnedSet.size(), returned.length, "Returned keywords should be unique");

    for (String s : excludeSQL2003) {
      assertFalse(returnedSet.contains(s), "Keyword from SQL:2003 \"" + s + "\" found");
    }

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      assertTrue(returnedSet.contains("reindex"), "reindex should be in keywords");
    }
  }

  @Test
  void functionColumns() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      return;
    }

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getFunctionColumns(null, null, "f1", null);

    ResultSetMetaData rsmd = rs.getMetaData();
    assertEquals(17, rsmd.getColumnCount());
    assertEquals("FUNCTION_CAT", rsmd.getColumnName(1));
    assertEquals("FUNCTION_SCHEM", rsmd.getColumnName(2));
    assertEquals("FUNCTION_NAME", rsmd.getColumnName(3));
    assertEquals("COLUMN_NAME", rsmd.getColumnName(4));
    assertEquals("COLUMN_TYPE", rsmd.getColumnName(5));
    assertEquals("DATA_TYPE", rsmd.getColumnName(6));
    assertEquals("TYPE_NAME", rsmd.getColumnName(7));
    assertEquals("PRECISION", rsmd.getColumnName(8));
    assertEquals("LENGTH", rsmd.getColumnName(9));
    assertEquals("SCALE", rsmd.getColumnName(10));
    assertEquals("RADIX", rsmd.getColumnName(11));
    assertEquals("NULLABLE", rsmd.getColumnName(12));
    assertEquals("REMARKS", rsmd.getColumnName(13));
    assertEquals("CHAR_OCTET_LENGTH", rsmd.getColumnName(14));
    assertEquals("ORDINAL_POSITION", rsmd.getColumnName(15));
    assertEquals("IS_NULLABLE", rsmd.getColumnName(16));
    assertEquals("SPECIFIC_NAME", rsmd.getColumnName(17));

    assertTrue(rs.next());
    assertNotNull(rs.getString(1));
    assertEquals("public", rs.getString(2));
    assertEquals("f1", rs.getString(3));
    assertEquals("returnValue", rs.getString(4));
    assertEquals(DatabaseMetaData.functionReturn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertEquals("int4", rs.getString(7));
    assertEquals(0, rs.getInt(15));

    assertTrue(rs.next());
    assertNotNull(rs.getString(1));
    assertEquals("public", rs.getString(2));
    assertEquals("f1", rs.getString(3));
    assertEquals("$1", rs.getString(4));
    assertEquals(DatabaseMetaData.functionColumnIn, rs.getInt(5));
    assertEquals(Types.INTEGER, rs.getInt(6));
    assertEquals("int4", rs.getString(7));
    assertEquals(1, rs.getInt(15));

    assertTrue(rs.next());
    assertNotNull(rs.getString(1));
    assertEquals("public", rs.getString(2));
    assertEquals("f1", rs.getString(3));
    assertEquals("$2", rs.getString(4));
    assertEquals(DatabaseMetaData.functionColumnIn, rs.getInt(5));
    assertEquals(Types.VARCHAR, rs.getInt(6));
    assertEquals("varchar", rs.getString(7));
    assertEquals(2, rs.getInt(15));

    assertFalse(rs.next());

    rs.close();
  }

  @Test
  void smallSerialColumns() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    TestUtil.createTable(con, "smallserial_test", "a smallserial");

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "smallserial_test", "a");
    assertTrue(rs.next());
    assertEquals("smallserial_test", rs.getString("TABLE_NAME"));
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(Types.SMALLINT, rs.getInt("DATA_TYPE"));
    assertEquals("smallserial", rs.getString("TYPE_NAME"));
    assertTrue(rs.getBoolean("IS_AUTOINCREMENT"));
    assertEquals("nextval('smallserial_test_a_seq'::regclass)", rs.getString("COLUMN_DEF"));
    assertFalse(rs.next());
    rs.close();

    TestUtil.dropTable(con, "smallserial_test");
  }

  @Test
  void smallSerialSequenceLikeColumns() throws SQLException {
    Statement stmt = con.createStatement();
    // This is the equivalent of the smallserial, not the actual smallserial
    stmt.execute("CREATE SEQUENCE smallserial_test_a_seq;\n"
        + "CREATE TABLE smallserial_test (\n"
        + "    a smallint NOT NULL DEFAULT nextval('smallserial_test_a_seq')\n"
        + ");\n"
        + "ALTER SEQUENCE smallserial_test_a_seq OWNED BY smallserial_test.a;");

    DatabaseMetaData dbmd = con.getMetaData();
    ResultSet rs = dbmd.getColumns(null, null, "smallserial_test", "a");
    assertTrue(rs.next());
    assertEquals("smallserial_test", rs.getString("TABLE_NAME"));
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(Types.SMALLINT, rs.getInt("DATA_TYPE"));
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2)) {
      // in Pg 9.2+ it behaves like smallserial
      assertEquals("smallserial", rs.getString("TYPE_NAME"));
    } else {
      assertEquals("int2", rs.getString("TYPE_NAME"));
    }
    assertTrue(rs.getBoolean("IS_AUTOINCREMENT"));
    assertEquals("nextval('smallserial_test_a_seq'::regclass)", rs.getString("COLUMN_DEF"));
    assertFalse(rs.next());
    rs.close();

    stmt.execute("DROP TABLE smallserial_test");
    stmt.close();
  }

  @Test
  void upperCaseMetaDataLabels() throws SQLException {
    ResultSet rs = con.getMetaData().getTables(null, null, null, null);
    ResultSetMetaData rsmd = rs.getMetaData();

    assertEquals("TABLE_CAT", rsmd.getColumnName(1));
    assertEquals("TABLE_SCHEM", rsmd.getColumnName(2));
    assertEquals("TABLE_NAME", rsmd.getColumnName(3));
    assertEquals("TABLE_TYPE", rsmd.getColumnName(4));
    assertEquals("REMARKS", rsmd.getColumnName(5));

  }
}
