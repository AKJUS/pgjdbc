/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

class LoginTimeoutTest {
  @Test
  void intTimeout() throws Exception {
    testWithLoginTimeout("10");
  }

  @Test
  void floatTimeout() throws Exception {
    testWithLoginTimeout("10.0");
  }

  @Test
  void zeroTimeout() throws Exception {
    testWithLoginTimeout("0");
  }

  @Test
  void negativeTimeout() throws Exception {
    testWithLoginTimeout("-1");
  }

  @Test
  void badTimeout() throws Exception {
    testWithLoginTimeout("zzzz");
  }

  private static void testWithLoginTimeout(String value) throws SQLException {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, value);

    try (Connection conn = TestUtil.openDB(props); ) {
      assertTrue(conn.isValid(1), () -> "Connection should be valid when connecting with "
          + PGProperty.LOGIN_TIMEOUT.name() + " = " + value);
    }
  }

  private static class TimeoutHelper implements Runnable {
    TimeoutHelper() throws IOException {
      InetAddress localAddr;
      try {
        localAddr = InetAddress.getLocalHost();
      } catch (UnknownHostException ex) {
        System.err.println("WARNING: Could not resolve local host name, trying 'localhost'. " + ex);
        localAddr = InetAddress.getByName("localhost");
      }
      this.listenSocket = new ServerSocket(0, 1, localAddr);
    }

    String getHost() {
      return listenSocket.getInetAddress().getHostAddress();
    }

    int getPort() {
      return listenSocket.getLocalPort();
    }

    @Override
    public void run() {
      try {
        Socket newSocket = listenSocket.accept();
        try {
          Thread.sleep(30000);
        } catch (InterruptedException e) {
          // Ignore it.
        }
        newSocket.close();
      } catch (IOException e) {
        // Ignore it.
      }
    }

    void kill() {
      try {
        listenSocket.close();
      } catch (IOException e) {
      }
    }

    private final ServerSocket listenSocket;
  }

  @Test
  void timeoutOccurs() throws Exception {
    // Spawn a helper thread to accept a connection and do nothing with it;
    // this should trigger a timeout.
    TimeoutHelper helper = new TimeoutHelper();
    new Thread(helper, "timeout listen helper").start();

    try {
      String url = "jdbc:postgresql://" + helper.getHost() + ":" + helper.getPort() + "/dummy";
      Properties props = new Properties();
      props.setProperty("user", "dummy");
      props.setProperty("loginTimeout", "5");

      // This is a pretty crude check, but should help distinguish
      // "can't connect" from "timed out".
      long startTime = System.nanoTime();
      Connection conn = null;
      try {
        conn = DriverManager.getConnection(url, props);
        fail("connection was unexpectedly successful");
      } catch (SQLException e) {
        // Ignored.
      } finally {
        if (conn != null) {
          conn.close();
        }
      }

      long endTime = System.nanoTime();
      assertTrue(endTime > startTime + (2500L * 1E6), "Connection timed before 2500ms");
    } finally {
      helper.kill();
    }
  }
}
