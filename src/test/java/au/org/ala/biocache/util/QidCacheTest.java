package au.org.ala.biocache.util;

import au.org.ala.biocache.dao.QidCacheDAO;
import au.org.ala.biocache.dao.QidCacheDAOImpl;
import au.org.ala.biocache.dao.QidDAO;
import au.org.ala.biocache.model.Qid;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * This test isnt written in a fashion that can be executed as part of a build.
 */
@RunWith(MockitoJUnitRunner.class)
@ContextConfiguration(locations = {"classpath:springTest.xml"})
public class QidCacheTest {

    @Mock
    QidDAO qidDao;

    @InjectMocks
    QidCacheDAO qidCacheDao = new QidCacheDAOImpl();

    //for qidDao mock doAnswer
    AtomicInteger counter = new AtomicInteger(0);
    Map<String, Qid> map = new ConcurrentHashMap<>();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        //qidDao mock
        doAnswer(new Answer<Qid>() {
            @Override
            public Qid answer(InvocationOnMock invocation) {
                return map.get(invocation.getArguments()[0]);
            }
        }).when(qidDao).get(anyString());
        doAnswer(new Answer<Qid>() {
            @Override
            public Qid answer(InvocationOnMock invocation) {
                Qid q = (Qid) invocation.getArguments()[0];
                q.setRowKey(String.valueOf(counter.incrementAndGet()));
                return map.put(q.getRowKey(), q);
            }
        }).when(qidDao).put((Qid) anyObject());

        //setup
        qidCacheDao.setMaxCacheSize(10000);
        qidCacheDao.setMinCacheSize(100);
        qidCacheDao.setLargestCacheableSize(524280);

        Qid qid = new Qid(null, "q", "displayString", "wkt", null,-1,null, -1, null);

        qidDao.put(qid);
        Qid a= qidDao.get(qid.rowKey());
    }

    /**
     * test put, get, delete old cache files
     */
    @Test
    public void testPutGet() throws QidMissingException, QidSizeException {

        //test a cache put returns a valid ordered key
        String key1 = qidCacheDao.put("q1", "displayQ", "wkt", new double[]{1, 2, 3, 4},null, -1, null);
        String key2 = qidCacheDao.put("q2", "displayQ", "wkt", new double[]{1, 2, 3, 4},null, -1, null);
        assertTrue(Long.parseLong(key1) >= 0);
        assertTrue((Long.parseLong(key1) - Long.parseLong(key2)) < 0);

        //test get returns the the correct object
        Qid qid = qidCacheDao.get(String.valueOf(key1));
        assertNotNull(qid);
        if (qid != null) {
            assertEquals(qid.getQ(), "q1");
            assertEquals(qid.getDisplayString(), "displayQ");
            assertEquals(qid.getWkt(), "wkt");
            assertTrue(qid.size() > 0);

            double[] bbox = qid.getBbox();
            assertNotNull(bbox);
            if (bbox != null) {
                assertTrue(bbox.length == 4);
                if (bbox.length == 4) {
                    assertTrue(bbox[0] == 1);
                    assertTrue(bbox[1] == 2);
                    assertTrue(bbox[2] == 3);
                    assertTrue(bbox[3] == 4);
                }
            }
        }

        //get from cache an object that does not exist throws the correct error
        boolean exception = false;
        try {
            qid = qidCacheDao.get("-1");
            assertTrue(false);
        } catch (QidMissingException e) {
            exception = true;
        }
        assertTrue(exception);

        //put very large object into cache throws an error
        exception = false;
        try {
            qidCacheDao.setLargestCacheableSize(10000);
            StringBuilder largeString = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                largeString.append("123");
            }
            qidCacheDao.put("large q", "displayString", largeString.toString(), null, null, -1, null);
            assertTrue(false);
        } catch (QidSizeException e) {
            exception = true;
        }
        assertTrue(exception);

        //test cached file on disk exists
        Qid qidFromDb = qidDao.get(String.valueOf(key1));
        assertTrue(qidFromDb != null);
    }

    /**
     * test cache size management
     * 1. put more than maxcachesize causes a drop to mincachesize
     * 2. after drop all puts are still retrievable, from disk
     */
    @Test
    public void testSizeManagement() throws QidMissingException, QidSizeException {

        ArrayList<Qid> pcos = new ArrayList<Qid>();
        ArrayList<String> keys = new ArrayList<String>();
        double[] defaultbbox = {1, 2, 3, 4};
        long putSize = 0;
        int cacheSizeDropCount = 0;
        for (int i = 0; i < 1000; i++) {
            long beforeSize = qidCacheDao.getSize();
            keys.add(qidCacheDao.put("q" + i, "displayString", "wkt", defaultbbox, null, -1, null));
            long afterSize = qidCacheDao.getSize();

            if (beforeSize > afterSize) {
                //test cache size is significantly reduced after a cacheclean
                //cachecleaner is on a thread
                assertTrue(afterSize <= 500);
                cacheSizeDropCount++;
            }

            pcos.add(qidCacheDao.get(String.valueOf(keys.get(keys.size() - 1))));

            putSize += pcos.get(pcos.size() - 1).size();
        }

        //test size calcuations are operating
        assertTrue(putSize > 10000);

        //test cache cleaner was run more than once
        assertTrue(cacheSizeDropCount > 1);

        //test gets
        for (int i = 0; i < pcos.size(); i++) {
            Qid getqid = qidCacheDao.get(String.valueOf(keys.get(i)));
            Qid putqid = pcos.get(i);

            //compare getpco and putpco
            assertQidsEqual(getqid, putqid);
        }
    }

    /**
     * test that cache does operate with concurrent requests
     * 1. perform many puts and gets on multiple threads
     */
    @Test
    public void testConcurrency() throws QidMissingException, InterruptedException {

        double[] defaultbbox = {1, 2, 3, 4};

        final ArrayList<Qid> qids = new ArrayList<Qid>();
        final ArrayList<Qid> getqids = new ArrayList<Qid>();
        final ArrayList<String> keys = new ArrayList<String>();
        final LinkedBlockingQueue<Integer> idxs = new LinkedBlockingQueue<Integer>();

        Collection<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();

        for (int i = 0; i < 3000; i++) {
            qids.add(new Qid(null, "q" + i, "displayString", "wkt", defaultbbox,-1,null, -1, null));
            getqids.add(null);
            keys.add("-1");
            idxs.put(i);

            //put and get task
            tasks.add(new Callable<Integer>() {

                public Integer call() throws Exception {
                    //put
                    int i = idxs.take();
                    Qid qid = qids.get(i);
                    keys.set(i, qidCacheDao.put(qid.getQ(), qid.getDisplayString(), qid.getWkt(), qid.getBbox(),null, -1, null));

                    //get
                    getqids.set(i, (qidCacheDao.get(String.valueOf(keys.get(i)))));
                    return i;
                }
            });
        }
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        executorService.invokeAll(tasks);

        //test cache cleaner operated correctly
        assertTrue(qidCacheDao.getSize() <= qidCacheDao.getMaxCacheSize());

        //test get objects match put objects
        for (int i = 0; i < qids.size(); i++) {
            Qid getqid = getqids.get(i);
            Qid putqid = qids.get(i);
            assertQidsEqual(getqid, putqid);
        }
    }

    void assertQidsEqual(Qid q1, Qid q2) {
        //compare getqid and putqid
        assertNotNull(q1);
        if (q1 != null) {
            assertEquals(q1.getQ(), q2.getQ());
            assertEquals(q1.getDisplayString(), q2.getDisplayString());
            assertEquals(q1.getWkt(), q2.getWkt());
            assertTrue(q1.size() > 0);

            double[] getbbox = q1.getBbox();
            double[] putbbox = q2.getBbox();
            assertNotNull(getbbox);
            if (getbbox != null) {
                assertTrue(getbbox.length == 4);
                if (getbbox.length == 4) {
                    assertTrue(getbbox[0] == putbbox[0]);
                    assertTrue(getbbox[1] == putbbox[1]);
                    assertTrue(getbbox[2] == putbbox[2]);
                    assertTrue(getbbox[3] == putbbox[3]);
                }
            }
        }
    }
}
