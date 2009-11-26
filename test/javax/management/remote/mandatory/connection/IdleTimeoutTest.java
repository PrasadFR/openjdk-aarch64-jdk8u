/*
 * Copyright 2003-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * @test
 * @bug 4886838 4886830
 * @summary Tests that idle timeouts happen at appropriate times
 * @author Eamonn McManus
 * @run clean IdleTimeoutTest
 * @run build IdleTimeoutTest
 * @run main IdleTimeoutTest
 */

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;
import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import com.sun.jmx.remote.util.EnvHelp;

public class IdleTimeoutTest {
    public static void main(String[] args) throws Exception {
        boolean ok = true;
        List protos;
        if (args.length > 0)
            protos = Arrays.asList(args);
        else {
            protos =
                new ArrayList(Arrays.asList(new String[] {"rmi", "iiop"}));
            try {
                Class.forName("javax.management.remote.jmxmp." +
                              "JMXMPConnectorServer");
                protos.add("jmxmp");
            } catch (ClassNotFoundException e) {
                // OK: Optional JMXMP support is not present
            }
        }
        for (Iterator it = protos.iterator(); it.hasNext(); ) {
            String proto = (String) it.next();
            int liCount;
            if (proto.equals("jmxmp")) liCount=1;
            else liCount=2;
            if (test(proto,4,liCount))
                System.out.println("Test for protocol " + proto + " passed");
            else {
                System.out.println("Test for protocol " + proto + " FAILED");
                ok = false;
            }
        }
        if (!ok) {
            System.out.println("SOME TESTS FAILED");
            System.exit(1);
        }
    }

    private static long getIdleTimeout(MBeanServer mbs, JMXServiceURL url)
        throws Exception {
        JMXConnectorServer server =
            JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs);
        server.start();
        try {
            url = server.getAddress();

            // Force initialization (class loading, JIT, etc...)
            //
            JMXConnector client = JMXConnectorFactory.connect(url);
            try {
                String connId = client.getConnectionId();
                MBeanServerConnection conn = client.getMBeanServerConnection();
            } finally {
                client.close();
            }

            // Do the time measurement
            //
            final long firstTime = System.currentTimeMillis();
            final long endtime;
            client = JMXConnectorFactory.connect(url);
            try {
                String connId = client.getConnectionId();
                MBeanServerConnection conn = client.getMBeanServerConnection();
                endtime = System.currentTimeMillis();
            } finally {
                client.close();
            }

            // multipled by 10 for a slow machine, plus 1500 for a fast one.
            return 10*(endtime - firstTime) + 1500;
        } finally {
            server.stop();
        }
    }

    private static class NotificationCounter
        implements NotificationListener {
        private final int[]  listenerCount;
        private final String listenerName;
        NotificationCounter(int[] counter, String name) {
            listenerCount=counter;
            listenerName=name;
        }

        public void handleNotification(Notification n,
                                       Object h) {
            MBeanServerNotification mbsn =
                (MBeanServerNotification) n;
            System.out.println(listenerName + " got notification: "
                               + mbsn.getMBeanName());
            synchronized (listenerCount) {
                listenerCount[0]++;
                listenerCount.notify();
            }
        }
        public String toString() {
            return listenerName;
        }
    }

    private static boolean test(String proto,int opCount,int liCount)
        throws Exception {
        System.out.println("Idle timeout test for protocol " + proto);
        ObjectName delegateName =
            ObjectName.getInstance("JMImplementation:" +
                                   "type=MBeanServerDelegate");
        MBeanServer mbs = MBeanServerFactory.createMBeanServer();
        JMXServiceURL url = new JMXServiceURL("service:jmx:" + proto + "://");

        final long timeout = getIdleTimeout(mbs,url);
        System.out.println("Timeout for " + proto + " is: " +
                           timeout + " ms");

        Map idleMap = new HashMap();
        idleMap.put(EnvHelp.SERVER_CONNECTION_TIMEOUT, new Long(timeout));
        JMXConnectorServer server =
            JMXConnectorServerFactory.newJMXConnectorServer(url,idleMap,mbs);

        final int[] listenerCount = new int[1];
        final NotificationListener countListeners[] =
            new NotificationListener[liCount];
        int i;
        for (i=0; i<countListeners.length; i++) {
            countListeners[i] =
                new NotificationCounter(listenerCount,"Listener"+i);
        }

        server.start();
        try {
            url = server.getAddress();
            final long firstTime = System.currentTimeMillis();
            JMXConnector client = JMXConnectorFactory.connect(url);
            long elapsed, startIdle=0;
            try {
                String connId = client.getConnectionId();
                MBeanServerConnection conn =
                    client.getMBeanServerConnection();
                elapsed   = System.currentTimeMillis() - firstTime;
                System.out.println("Idle Time: " + elapsed + "ms");

                for (i=0; i<countListeners.length; i++) {
                    System.out.println("add " + countListeners[i] +
                                       ": starting at " + elapsed + "ms");
                    conn.addNotificationListener(delegateName,
                                                 countListeners[i],
                                                 null,null);
                }

                System.out.println("connId=" + connId);
                for (i = 0; i < opCount; i++) {
                    elapsed   = System.currentTimeMillis() - firstTime;
                    System.out.println("Operation[" + (i+1)
                                       +"]: starting at " +
                                       elapsed + "ms");
                    final String name = "d:type=mlet,instance=" + i;
                    mbs.createMBean("javax.management.loading.MLet",
                                    new ObjectName(name));
                    if (i == (opCount-1))
                        startIdle = System.currentTimeMillis();
                    Thread.sleep(2);
                }

                // Wait for notifs to arrive before doing removeNListener
                long startTime = System.currentTimeMillis();
                long deadline = startTime + 10000;

                System.out.println("Waiting for notifs: starting at " +
                                   (startTime - firstTime) + "ms");

                final int expectedCount = opCount*countListeners.length;
                while (System.currentTimeMillis() < deadline) {
                    synchronized (listenerCount) {
                        if (listenerCount[0] >= expectedCount)
                            break;
                        listenerCount.wait();
                    }
                }

                long elapsedWait = System.currentTimeMillis() - startTime;
                System.out.println("Waited " + elapsedWait +
                                   "ms for notifs to arrive");

                if (listenerCount[0] != expectedCount) {
                    System.out.println("Did not get expected " +
                                       expectedCount + " notifications: "
                                       + listenerCount[0]);
                    return false;
                }

                elapsed   = System.currentTimeMillis() - firstTime;

                System.out.println("idle time since last operation: " +
                                   (elapsed + firstTime - startIdle) + "ms");
                System.out.println("Requesting conn id at: " +
                                   elapsed + "ms");
                final String cid = client.getConnectionId();

                elapsed   = System.currentTimeMillis() - firstTime;
                System.out.println("Got conn id <" + cid + "> at: " +
                                   elapsed + "ms");

                if (!connId.equals(cid)) {
                    System.out.println("Client id changed: <" + connId +
                                       "> -> <" + cid +
                                       ">");
                    return false;
                }

                List ids = Arrays.asList(server.getConnectionIds());
                if (!ids.contains(connId)) {
                    System.out.println("Server ids don't contain our id: " +
                                       ids + " - " + connId);
                    return false;
                }

                for (i=0;i<countListeners.length;i++) {
                    System.out.println("Removing notification listener: " +
                                       countListeners[i]);
                    conn.removeNotificationListener(delegateName,
                                                    countListeners[i]);
                }

                System.out.println("Waiting for id list to drop ours");
                deadline = System.currentTimeMillis() + timeout*2 + 10000;
                while (true) {
                    ids = Arrays.asList(server.getConnectionIds());
                    if (!ids.contains(connId)
                        || System.currentTimeMillis() >= deadline)
                        break;
                    Thread.sleep(500);
                }
                if (ids.contains(connId)) {
                    System.out.println("Client id still in list after " +
                                       "deadline: " + ids);
                    return false;
                }

                conn.getDefaultDomain();
                if (connId.equals(client.getConnectionId())) {
                    System.out.println("Client id did not change: <" + connId +
                                       ">: idle timeout did not happen?");
                    return false;
                } else {
                    System.out.println("Client id changed as expected: <" +
                                       connId + "> -> <" +
                                       client.getConnectionId() + ">");
                }
            } finally {
                client.close();
                System.out.println("Connection id list on server after " +
                                   "client close: " +
                                   Arrays.asList(server.getConnectionIds()));
            }
        } finally {
            server.stop();
        }
        System.out.println("*** ------------------------------------------");
        System.out.println("*** Test passed for " + proto);
        System.out.println("*** ------------------------------------------");
        return true;
    }
}
