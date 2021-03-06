package com.pardot.rhombus.functional;

import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.pardot.rhombus.ConnectionManager;
import com.pardot.rhombus.Criteria;
import com.pardot.rhombus.ObjectMapper;
import com.pardot.rhombus.cobject.CDefinition;
import com.pardot.rhombus.cobject.CKeyspaceDefinition;
import com.pardot.rhombus.cobject.shardingstrategy.ShardingStrategyMonthly;
import com.pardot.rhombus.cobject.shardingstrategy.ShardingStrategyNone;
import com.pardot.rhombus.util.JsonUtil;
import org.apache.cassandra.io.util.FileUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;

import static org.junit.Assert.*;

public class SSTableWriterITCase extends RhombusFunctionalTest {

    private static Logger logger = LoggerFactory.getLogger(ObjectMapperUpdateITCase.class);


    @Test
    public void testInsertingAllNonNullValuesInSchema_simple() throws Exception {
        logger.debug("Starting testInsertingAllNonNullValuesInSchema");
        System.setProperty("cassandra.config", "cassandra-config/cassandra.yaml");
        String defaultTableName = "simple";
        String testUniqueTableName = "simpleA";

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition keyspaceDefinition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "TableWriterSimpleKeyspace.js");
        assertNotNull(keyspaceDefinition);
        String keyspaceName = keyspaceDefinition.getName();
        // Hardcode this for simplicity
        ShardingStrategyMonthly shardStrategy = new ShardingStrategyMonthly();

        // SSTableWriter craps out if we try to close a writer on a table and then create a new one on the same table, so each test should write to different tables
        Map<String, CDefinition> tableDefs = keyspaceDefinition.getDefinitions();
        CDefinition def = tableDefs.get(defaultTableName);
        def.setName(testUniqueTableName);
        tableDefs.remove(defaultTableName);
        tableDefs.put(testUniqueTableName, def);

        // Make sure the SSTableOutput directory exists and is clear
        File keyspaceDir = new File(keyspaceName);
        if (keyspaceDir.exists()) {
            FileUtils.deleteRecursive(new File(keyspaceName));
        }
        assertTrue(new File(keyspaceName).mkdir());

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(keyspaceDefinition, true);
        logger.debug("Built keyspace: {}", keyspaceDefinition.getName());
        cm.setDefaultKeyspace(keyspaceDefinition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);
        om.truncateTables();

        // This is the only static table definition this test keyspace has
        List<String> staticTableNames = Arrays.asList(testUniqueTableName);

        //Insert our test data into the SSTable
        // For this test, all this data goes into the one table we have defined
        List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "SSTableWriterSimpleTestData.js");
        // Tack on time based UUIDs because we don't really care what the UUID values are
        for (Map<String, Object> map : values) {
            map.put("id", UUIDs.startOf(Long.parseLong(map.get("created_at").toString(), 10)));
        }
        // Build the map to insert that we'll actually pass in
        Map<String, List<Map<String, Object>>> insert = new HashMap<String, List<Map<String, Object>>>();
        for (String staticTableName : staticTableNames) {
            insert.put(staticTableName, values);
        }

        // Actually insert the data into the SSTableWriters
        om.initializeSSTableWriters(false);
        om.insertIntoSSTable(insert);
        om.completeSSTableWrites();

        // Figure out all the table names (including index tables) so we can load them into Cassandra
        File[] tableDirs = keyspaceDir.listFiles();
        assertNotNull(tableDirs);
        List<String> allTableNames = Lists.newArrayList();
        for (File file : tableDirs) {
            if (file != null) {
                allTableNames.add(file.getName());
            }
        }
        for (String tableName : allTableNames) {
            String SSTablePath = keyspaceName + "/" + tableName;

            // Load the SSTables into Cassandra
            ProcessBuilder builder = new ProcessBuilder("sstableloader", "-d", "localhost", SSTablePath);
            builder.redirectErrorStream(true);
            Process p = builder.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long startTime = System.currentTimeMillis();
            // TODO: sleep is the devil
            while (!r.readLine().contains("100%") && ((System.currentTimeMillis() - startTime) < 10000)) {
                Thread.sleep(100);
            }
        }

        String staticTableName = staticTableNames.get(0);
        for (Map<String, Object> expected : values) {
            Map<String, Object> actual = om.getByKey(staticTableName, expected.get("id"));
            assertEquals(expected, actual);
        }

        Map<String, Map<String, Object>> indexedExpected = Maps.uniqueIndex(values, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }});
        // Confirm get by index_1 query
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("index_1", "index1");
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        List<Map<String, Object>> results = om.list(testUniqueTableName, criteria);
        Map<String, Map<String, Object>> indexedResults;
        indexedResults = Maps.uniqueIndex(results, new Function<Map<String, Object>, String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }
        });
        Map<String, Map<String, Object>> indexedExpected1 = Maps.newHashMap(indexedExpected);
        // Index_1 test data doesn't include value 4
        indexedExpected1.remove("864f1400-2a7e-11b2-8080-808080808080");
        assertEquals(indexedExpected1, indexedResults);

        // Confirm get by index_2 query
        indexValues = Maps.newTreeMap();
        indexValues.put("index_2", "index2");
        criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        results = om.list(testUniqueTableName, criteria);

        indexedResults = Maps.uniqueIndex(results, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }});

        Map<String, Map<String, Object>> indexedExpected2 = Maps.newHashMap(indexedExpected);
        // Index_2 test data doesn't include value 3
        indexedExpected2.remove("80593300-2a7e-11b2-8080-808080808080");
        assertEquals(indexedExpected2, indexedResults);

        // Clean up the SSTable directories after ourselves
        FileUtils.deleteRecursive(new File(keyspaceName));
    }

    @Test
    public void testInsertingSomeNullValuesInSchema_simple() throws Exception {
        logger.debug("Starting testInsertingAllNonNullValuesInSchema");
        System.setProperty("cassandra.config", "cassandra-config/cassandra.yaml");
        String defaultTableName = "simple";
        String testUniqueTableName = "simpleB";

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition keyspaceDefinition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "TableWriterSimpleKeyspace.js");
        assertNotNull(keyspaceDefinition);
        String keyspaceName = keyspaceDefinition.getName();
        // Hardcode this for simplicity
        ShardingStrategyMonthly shardStrategy = new ShardingStrategyMonthly();

        // SSTableWriter craps out if we try to close a writer on a table and then create a new one on the same table, so each test should write to different tables
        Map<String, CDefinition> tableDefs = keyspaceDefinition.getDefinitions();
        CDefinition def = tableDefs.get(defaultTableName);
        def.setName(testUniqueTableName);
        tableDefs.remove(defaultTableName);
        tableDefs.put(testUniqueTableName, def);

        // Make sure the SSTableOutput directory exists and is clear
        File keyspaceDir = new File(keyspaceName);
        if (keyspaceDir.exists()) {
            FileUtils.deleteRecursive(new File(keyspaceName));
        }
        assertTrue(new File(keyspaceName).mkdir());

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(keyspaceDefinition, true);
        logger.debug("Built keyspace: {}", keyspaceDefinition.getName());
        cm.setDefaultKeyspace(keyspaceDefinition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);
        om.truncateTables();

        // This is the only static table definition this test keyspace has
        List<String> staticTableNames = Arrays.asList(testUniqueTableName);

        //Insert our test data into the SSTable
        // For this test, all this data goes into the one table we have defined
        List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "SSTableWriterSimpleTestData.js");
        // Tack on time based UUIDs because we don't really care what the UUID values are
        for (Map<String, Object> map : values) {
            map.put("id", UUIDs.startOf(Long.parseLong(map.get("created_at").toString(), 10)));
            // For this test, remove the actual values so we see what happens if we try to insert nulls
            map.remove("value");
        }
        // Build the map to insert that we'll actually pass in
        Map<String, List<Map<String, Object>>> insert = new HashMap<String, List<Map<String, Object>>>();
        for (String staticTableName : staticTableNames) {
            insert.put(staticTableName, values);
        }

        // Actually insert the data into the SSTableWriters
        om.initializeSSTableWriters(false);
        om.insertIntoSSTable(insert);
        om.completeSSTableWrites();

        // Figure out all the table names (including index tables) so we can load them into Cassandra
        File[] tableDirs = keyspaceDir.listFiles();
        assertNotNull(tableDirs);
        List<String> allTableNames = Lists.newArrayList();
        for (File file : tableDirs) {
            if (file != null) {
                allTableNames.add(file.getName());
            }
        }
        for (String tableName : allTableNames) {
            String SSTablePath = keyspaceName + "/" + tableName;

            // Load the SSTables into Cassandra
            ProcessBuilder builder = new ProcessBuilder("sstableloader", "-d", "localhost", SSTablePath);
            builder.redirectErrorStream(true);
            Process p = builder.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long startTime = System.currentTimeMillis();
            // TODO: sleep is the devil
            while (!r.readLine().contains("100%") && ((System.currentTimeMillis() - startTime) < 10000)) {
                Thread.sleep(100);
            }
        }

        String staticTableName = staticTableNames.get(0);
        for (Map<String, Object> expected : values) {
            // Expect to get the null "value" back
            expected.put("value", null);
            Map<String, Object> actual = om.getByKey(staticTableName, expected.get("id"));
            assertEquals(expected, actual);
        }

        Map<String, Map<String, Object>> indexedExpected = Maps.uniqueIndex(values, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }});
        // Confirm get by index_1 query
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("index_1", "index1");
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        List<Map<String, Object>> results = om.list(testUniqueTableName, criteria);
        Map<String, Map<String, Object>> indexedResults;
        indexedResults = Maps.uniqueIndex(results, new Function<Map<String, Object>, String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }
        });
        Map<String, Map<String, Object>> indexedExpected1 = Maps.newHashMap(indexedExpected);
        // Index_1 test data doesn't include value 4
        indexedExpected1.remove("864f1400-2a7e-11b2-8080-808080808080");
        assertEquals(indexedExpected1, indexedResults);

        // Confirm get by index_2 query
        indexValues = Maps.newTreeMap();
        indexValues.put("index_2", "index2");
        criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        results = om.list(testUniqueTableName, criteria);

        indexedResults = Maps.uniqueIndex(results, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("id").toString();
            }});

        Map<String, Map<String, Object>> indexedExpected2 = Maps.newHashMap(indexedExpected);
        // Index_2 test data doesn't include value 3
        indexedExpected2.remove("80593300-2a7e-11b2-8080-808080808080");
        assertEquals(indexedExpected2, indexedResults);

        // Clean up the SSTable directories after ourselves
        FileUtils.deleteRecursive(new File(keyspaceName));
    }

    @Test
    public void testInsertingNullValuesForIndex() throws Exception {
        logger.debug("Starting testInsertingAllNonNullValuesInSchema");
        System.setProperty("cassandra.config", "cassandra-config/cassandra.yaml");
        String defaultTableName = "simple";
        String testUniqueTableName = "simpleC";

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition keyspaceDefinition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "TableWriterSimpleKeyspace.js");
        assertNotNull(keyspaceDefinition);
        String keyspaceName = keyspaceDefinition.getName();

        // SSTableWriter craps out if we try to close a writer on a table and then create a new one on the same table, so each test should write to different tables
        Map<String, CDefinition> tableDefs = keyspaceDefinition.getDefinitions();
        CDefinition def = tableDefs.get(defaultTableName);
        def.setName(testUniqueTableName);
        tableDefs.remove(defaultTableName);
        tableDefs.put(testUniqueTableName, def);

        // Make sure the SSTableOutput directory exists and is clear
        File keyspaceDir = new File(keyspaceName);
        if (keyspaceDir.exists()) {
            FileUtils.deleteRecursive(new File(keyspaceName));
        }
        assertTrue(new File(keyspaceName).mkdir());

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(keyspaceDefinition, true);
        logger.debug("Built keyspace: {}", keyspaceDefinition.getName());
        cm.setDefaultKeyspace(keyspaceDefinition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);
        om.truncateTables();

        // This is the only static table definition this test keyspace has
        List<String> staticTableNames = Arrays.asList(testUniqueTableName);

        //Insert our test data into the SSTable
        // For this test, all this data goes into the one table we have defined
        List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "SSTableWriterSimpleTestData.js");
        // Tack on time based UUIDs because we don't really care what the UUID values are
        for (Map<String, Object> map : values) {
            map.put("id", UUIDs.startOf(Long.parseLong(map.get("created_at").toString(), 10)));
            // For this test, remove the index values so we see what happens if we try to insert nulls for index values
            map.remove("index_1");
        }
        // Build the map to insert that we'll actually pass in
        Map<String, List<Map<String, Object>>> insert = new HashMap<String, List<Map<String, Object>>>();
        for (String staticTableName : staticTableNames) {
            insert.put(staticTableName, values);
        }

        // Actually insert the data into the SSTableWriters
        om.initializeSSTableWriters(false);
        om.insertIntoSSTable(insert);
        om.completeSSTableWrites();

        // Figure out all the table names (including index tables) so we can load them into Cassandra
        File[] tableDirs = keyspaceDir.listFiles();
        assertNotNull(tableDirs);
        List<String> allTableNames = Lists.newArrayList();
        for (File file : tableDirs) {
            if (file != null) {
                allTableNames.add(file.getName());
            }
        }
        for (String tableName : allTableNames) {
            String SSTablePath = keyspaceName + "/" + tableName;

            // Load the SSTables into Cassandra
            ProcessBuilder builder = new ProcessBuilder("sstableloader", "-d", "localhost", SSTablePath);
            builder.redirectErrorStream(true);
            Process p = builder.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long startTime = System.currentTimeMillis();
            // TODO: sleep is the devil
            while (!r.readLine().contains("100%") && ((System.currentTimeMillis() - startTime) < 10000)) {
                Thread.sleep(100);
            }
        }

        String staticTableName = staticTableNames.get(0);
        // Confirm get by id query
        for (Map<String, Object> expected : values) {
            // Expect to get the null "value" back
            expected.put("index_1", null);
            Map<String, Object> actual = om.getByKey(staticTableName, expected.get("id"));
            assertEquals(expected, actual);
        }

        // Index expected data by the value, which we will use as a faux index for testing
        Map<String, Map<String, Object>> indexedExpected = Maps.uniqueIndex(values, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("value").toString();
            }});
        // Confirm get by index_1 query
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("index_1", "index1");
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        List<Map<String, Object>> results = om.list(testUniqueTableName, criteria);
        assertEquals("Index 1 values were null for this test, query on index 1 should return 0 results", 0, results.size());

        // Confirm get by index_2 query
        indexValues = Maps.newTreeMap();
        indexValues.put("index_2", "index2");
        criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setLimit(50L);
        results = om.list(testUniqueTableName, criteria);

        // Index results by the value, which we will use as a faux index for testing
        Map<String, Map<String, Object>> indexedResults = Maps.uniqueIndex(results, new Function<Map<String, Object>,String>() {
            public String apply(Map<String, Object> input) {
                return input.get("value").toString();
            }});

        Map<String, Map<String, Object>> indexedExpected1 = Maps.newHashMap(indexedExpected);
        // Index_2 test data doesn't include value 3
        indexedExpected1.remove("value3");
        assertEquals(indexedExpected1, indexedResults);

        // Clean up the SSTable directories after ourselves
        FileUtils.deleteRecursive(new File(keyspaceName));
    }

    @Test
    public void testWithNonUuidIdForObject() throws Exception {
        logger.debug("Starting testWithNonUuidIdForObject");
        System.setProperty("cassandra.config", "cassandra-config/cassandra.yaml");
        String defaultTableName = "non_uuid_pk";
        String testUniqueTableName = "non_uuid_pkA";

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition keyspaceDefinition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "TableWriterNonUuidPkKeyspace.js");
        assertNotNull(keyspaceDefinition);
        String keyspaceName = keyspaceDefinition.getName();
        // Hardcode this for simplicity
        ShardingStrategyNone shardStrategy = new ShardingStrategyNone();

        // SSTableWriter craps out if we try to close a writer on a table and then create a new one on the same table, so each test should write to different tables
        Map<String, CDefinition> tableDefs = keyspaceDefinition.getDefinitions();
        CDefinition def = tableDefs.get(defaultTableName);
        def.setName(testUniqueTableName);
        tableDefs.remove(defaultTableName);
        tableDefs.put(testUniqueTableName, def);

        // Make sure the SSTableOutput directory exists and is clear
        File keyspaceDir = new File(keyspaceName);
        if (keyspaceDir.exists()) {
            FileUtils.deleteRecursive(new File(keyspaceName));
        }
        assertTrue(new File(keyspaceName).mkdir());

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(keyspaceDefinition, true);
        logger.debug("Built keyspace: {}", keyspaceDefinition.getName());
        cm.setDefaultKeyspace(keyspaceDefinition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);
        om.truncateTables();

        // This is the only static table definition this test keyspace has
        List<String> staticTableNames = Arrays.asList(testUniqueTableName);

        //Insert our test data into the SSTable
        // For this test, all this data goes into the one table we have defined
        List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "SSTableWriterNonUuidPkTestData.js");
        // Build the map to insert that we'll actually pass in
        Map<String, List<Map<String, Object>>> insert = new HashMap<String, List<Map<String, Object>>>();
        for (String staticTableName : staticTableNames) {
            insert.put(staticTableName, values);
        }

        // Actually insert the data into the SSTableWriters
        om.initializeSSTableWriters(false);
        om.insertIntoSSTable(insert);
        om.completeSSTableWrites();

        // Figure out all the table names (including index tables) so we can load them into Cassandra
        File[] tableDirs = keyspaceDir.listFiles();
        assertNotNull(tableDirs);
        List<String> allTableNames = Lists.newArrayList();
        for (File file : tableDirs) {
            if (file != null) {
                allTableNames.add(file.getName());
            }
        }
        for (String tableName : allTableNames) {
            String SSTablePath = keyspaceName + "/" + tableName;

            // Load the SSTables into Cassandra
            ProcessBuilder builder = new ProcessBuilder("sstableloader", "-d", "localhost", SSTablePath);
            builder.redirectErrorStream(true);
            Process p = builder.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long startTime = System.currentTimeMillis();
            // TODO: sleep is the devil
            while (!r.readLine().contains("100%") && ((System.currentTimeMillis() - startTime) < 10000)) {
                Thread.sleep(100);
            }
        }

        String staticTableName = staticTableNames.get(0);
        for (Map<String, Object> expected : values) {
            Map<String, Object> actual = om.getByKey(staticTableName, expected.get("id"));
            assertEquals(expected, actual);
        }

        // Clean up the SSTable directories after ourselves
        FileUtils.deleteRecursive(new File(keyspaceName));
    }
}
