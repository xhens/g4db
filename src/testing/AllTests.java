package testing;

import app_kvServer.CacheReplacementStrategy;
import app_kvServer.KVServer;
import junit.framework.Test;
import junit.framework.TestSuite;


public class AllTests {

    static {
        try {
            new KVServer(50000, 10, CacheReplacementStrategy.FIFO);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Test suite() {
        TestSuite clientSuite = new TestSuite("Basic Storage ServerTest-Suite");
        clientSuite.addTestSuite(ConnectionTest.class);
        clientSuite.addTestSuite(InteractionTest.class);
        clientSuite.addTestSuite(AdditionalTest.class);
        clientSuite.addTestSuite(RecordReaderTest.class);
        return clientSuite;
    }

}
