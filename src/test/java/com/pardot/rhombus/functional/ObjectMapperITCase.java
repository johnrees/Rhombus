package com.pardot.rhombus.functional;


import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.pardot.rhombus.ConnectionManager;
import com.pardot.rhombus.Criteria;
import com.pardot.rhombus.ObjectMapper;
import com.pardot.rhombus.cobject.*;
import com.pardot.rhombus.helpers.TestHelpers;
import com.pardot.rhombus.util.JsonUtil;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class ObjectMapperITCase extends RhombusFunctionalTest {

	private static Logger logger = LoggerFactory.getLogger(ObjectMapperITCase.class);

	@Test
	public void testObjectMapper() throws Exception {
		logger.debug("Starting testObjectMapper");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "CKeyspaceTestData.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper(definition.getName());

		//Get a test object to insert
		Map<String, Object> testObject = JsonUtil.rhombusMapFromJsonMap(TestHelpers.getTestObject(0), definition.getDefinitions().get("testtype"));
		UUID key = (UUID)om.insert("testtype", testObject);

		//Query to get back the object from the database
		Map<String, Object> dbObject = om.getByKey("testtype", key);
		for(String dbKey : dbObject.keySet()) {
			//Verify that everything but the key is the same
			if(!dbKey.equals("id")) {
				assertEquals(testObject.get(dbKey), dbObject.get(dbKey));
			}
		}

		//Add another object with the same foreign key
		UUID key2 = (UUID)om.insert("testtype", JsonUtil.rhombusMapFromJsonMap(TestHelpers.getTestObject(1), definition.getDefinitions().get("testtype")));

		//Query by foreign key
		Criteria criteria = TestHelpers.getTestCriteria(0);
		long foreignKey = ((Integer)criteria.getIndexKeys().get("foreignid")).longValue();
		criteria.getIndexKeys().put("foreignid", foreignKey);
		List<Map<String, Object>> dbObjects = om.list("testtype", criteria);
		assertEquals(2, dbObjects.size());
		for(Map<String, Object> result : dbObjects) {
			assertEquals(foreignKey, result.get("foreignid"));
		}

		//Remove one of the objects we added
		om.delete("testtype", key);

		//Re-query by foreign key
		dbObjects = om.list("testtype", criteria);
		assertEquals(1, dbObjects.size());

		//Update the values of one of the objects
		Map<String, Object> testObject2 = JsonUtil.rhombusMapFromJsonMap(
				TestHelpers.getTestObject(2),
				definition.getDefinitions().get("testtype"));
		UUID key3 = om.update("testtype", key2, testObject2, null, null);

		//Get the updated object back and make sure it matches
		Map<String, Object> dbObject2 = om.getByKey("testtype", key3);
		testObject2.put("id", key2);
		for(String dbKey : dbObject2.keySet()) {
			//Verify that everything is the same
			assertEquals(testObject2.get(dbKey), dbObject2.get(dbKey));
		}

		//Get from the original index
		dbObjects = om.list("testtype", criteria);
		assertEquals(0, dbObjects.size());

		//Get from the new index
		Criteria criteria2 = TestHelpers.getTestCriteria(1);
		criteria2.getIndexKeys().put("foreignid",((Integer)criteria2.getIndexKeys().get("foreignid")).longValue());
		dbObjects = om.list("testtype", criteria2);
		assertEquals(1, dbObjects.size());

		//an imediate request should return null, because we didnt wait for consistency
		assertEquals(null, om.getNextUpdateIndexRow(null));

		//Do another update
		Map<String, Object> testObject3 = Maps.newHashMap();
		testObject3.put("type",Integer.valueOf(7));
		UUID key4 = om.update("testtype", key2, testObject3, null, null);


		//now wait for consistency
		Thread.sleep(3000);

		//Test that we can retrieve the proper update rows
		IndexUpdateRow row =  om.getNextUpdateIndexRow(null);
		assertEquals("testtype", row.getObjectName());
		assertEquals(2, row.getIndexValues().size());
		//most recent should be at the front of the list
		assertEquals(7, row.getIndexValues().get(0).get("type"));
		assertEquals(5, row.getIndexValues().get(1).get("type"));
		assertEquals(778L, row.getIndexValues().get(0).get("foreignid"));
		assertEquals(778L, row.getIndexValues().get(1).get("foreignid"));
		assertEquals(333333L, row.getIndexValues().get(0).get("instance"));
		assertEquals(333333L, row.getIndexValues().get(1).get("instance"));

		//verify that if we try to get the next row it returns null
		assertEquals(null, om.getNextUpdateIndexRow(row.getRowKey()));





		//Teardown connections
		cm.teardown();
	}

	@Test
	public void testObjectWithCustomKey() throws Exception {
		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "CKeyspaceTestData.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper(definition.getName());

		//Get a test object to insert
		Map<String, Object> testObject = Maps.newHashMap();
		testObject.put("data1","A-data1");
		String key1 = (String)om.insert("customkey", testObject,"A");

		Map<String, Object> testObject2 = Maps.newHashMap();
		testObject2.put("data1","B-data1");
		String key2 = (String)om.insert("customkey", testObject2, "B");

		assertEquals("A", key1);
		assertEquals("B", key2);

		Map<String,Object> result = om.getByKey("customkey", "A");
		assertEquals("A-data1",result.get("data1"));

		result = om.getByKey("customkey", "B");
		assertEquals("B-data1",result.get("data1"));

	}

	@Test
	public void testDelete() throws Exception {
		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "CKeyspaceTestData.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper(definition.getName());

		//Get a test object to insert
		Map<String, Object> testObject = JsonUtil.rhombusMapFromJsonMap(TestHelpers.getTestObject(0), definition.getDefinitions().get("testtype"));
		UUID key = (UUID)om.insert("testtype", testObject);

		//Query to get back the object from the database
		Map<String, Object> dbObject = om.getByKey("testtype", key);
		for(String dbKey : dbObject.keySet()) {
			//Verify that everything but the key is the same
			if(!dbKey.equals("id")) {
				assertEquals(testObject.get(dbKey), dbObject.get(dbKey));
			}
		}

		//Query by foreign key
		Criteria criteria = TestHelpers.getTestCriteria(0);
		long foreignKey = ((Long)criteria.getIndexKeys().get("foreignid")).longValue();
		criteria.getIndexKeys().put("foreignid", foreignKey);
		List<Map<String, Object>> dbObjects = om.list("testtype", criteria);
		assertEquals(1, dbObjects.size());
		for(Map<String, Object> result : dbObjects) {
			assertEquals(foreignKey, result.get("foreignid"));
		}

		//Remove the object we added
		om.delete("testtype", key);

		//Query to get back the object from the database
		assertEquals(null, om.getByKey("testtype", key));
		assertEquals(0,om.list("testtype", criteria).size());


		//now try inserting an object that has a null for one of the index values
		testObject.put("foreignid",null);
		UUID key3 = (UUID)om.insert("testtype", testObject);
		//Query to get back the object from the database
		dbObject = om.getByKey("testtype", key3);
		for(String dbKey : dbObject.keySet()) {
			//Verify that everything but the key is the same
			if(!dbKey.equals("id")) {
				assertEquals(testObject.get(dbKey), dbObject.get(dbKey));
			}
		}
		//Remove the object we added
		om.delete("testtype", key3);
		//Query to get back the object from the database
		assertEquals(null, om.getByKey("testtype", key3));
	}

	//This does not test blob or counter types
	@Test
	public void testObjectTypes() throws Exception {
		logger.debug("Starting testObjectTypes");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "ObjectMapperTypeTestKeyspace.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper();

		//Insert in some values of each type
		List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "ObjectMapperTypeTestData.js");
		Map<String, Object> data = JsonUtil.rhombusMapFromJsonMap(values.get(0), definition.getDefinitions().get("testobjecttype"));
		UUID uuid = (UUID)om.insert("testobjecttype", data);
		assertNotNull(uuid);

		//Get back the values
		Map<String, Object> returnedValues = om.getByKey("testobjecttype", uuid);

		//Verify that id is returned
		assertNotNull(returnedValues.get("id"));

		logger.debug("Returned values: {}", returnedValues);
		for(String returnedKey : returnedValues.keySet()) {
			if(!returnedKey.equals("id")) {
				Object insertValue = data.get(returnedKey);
				Object returnValue = returnedValues.get(returnedKey);
				assertEquals(insertValue, returnValue);
			}
		}

		cm.teardown();
	}


	@Test
	public void testDateRangeQueries() throws Exception {
		logger.debug("Starting testDateRangeQueries");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "AuditKeyspace.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		logger.debug("Built keyspace: {}", definition.getName());
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper();

		//Insert our test data
		List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "DateRangeQueryTestData.js");
		for(Map<String, Object> object : values) {
			Long createdAt = (Long)(object.get("created_at"));
			logger.debug("Inserting audit with created_at: {}", createdAt);
			om.insert("object_audit", JsonUtil.rhombusMapFromJsonMap(object,definition.getDefinitions().get("object_audit")), createdAt);
		}

		//Make sure that we have the proper number of results
		SortedMap<String, Object> indexValues = Maps.newTreeMap();
		indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
		indexValues.put("object_type", "Account");
		indexValues.put("object_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
		Criteria criteria = new Criteria();
		criteria.setIndexKeys(indexValues);
		criteria.setLimit(50L);
		List<Map<String, Object>> results = om.list("object_audit", criteria);
		assertEquals(8, results.size());

		//Now query for results since May 1 2013
		criteria.setStartTimestamp(1367366400000L);
		results = om.list("object_audit", criteria);
		assertEquals(7, results.size());

		//And for results since May 14, 2013
		criteria.setStartTimestamp(1368489600000L);
		results = om.list("object_audit", criteria);
		assertEquals(5, results.size());

		cm.teardown();
	}

	@Test
	public void testNullIndexValues() throws Exception {
		logger.debug("Starting testNullIndexValues");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "AuditKeyspace.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		logger.debug("Built keyspace: {}", definition.getName());
		ObjectMapper om = cm.getObjectMapper(definition);
		om.setLogCql(true);

		//Insert our test data
		List<Map<String, Object>> values = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "NullIndexValuesTestData.js");
		Map<String, Object> object = values.get(0);
		Long createdAt = (Long)(object.get("created_at"));
		logger.debug("Inserting audit with created_at: {}", createdAt);
		UUID id = (UUID)om.insert("object_audit", JsonUtil.rhombusMapFromJsonMap(object,definition.getDefinitions().get("object_audit")), createdAt);

		//Get back the data and make sure things match
		Map<String, Object> result = om.getByKey("object_audit", id);
		assertEquals(object.get("user_id"), result.get("user_id"));
		assertEquals(object.get("changes"), result.get("changes"));
		for(String key : result.keySet()) {
			if(!key.equals("id")) {
				if(key.equals("account_id") || key.equals("object_id")){
					assertEquals(object.get(key).toString(), result.get(key).toString());
				}
				else if(key.equals("created_at")){
					assertEquals(object.get(key), ((Date)result.get(key)).getTime());
				}
				else{
					assertEquals(object.get(key), result.get(key));
				}
			}
			logger.debug("{} Result: {}, Input: {}", key, result.get(key), object.get(key));
		}
	}

    @Test
    public void testLargeCount() throws Exception {
        logger.debug("Starting testLargeCount");

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
        assertNotNull(definition);

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(definition, true);
        logger.debug("Built keyspace: {}", definition.getName());
        cm.setDefaultKeyspace(definition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);

        //Set up test data
        int nDataItems = 200;

        List<Map<String, Object>> values2 =  Lists.newArrayList();

        // insert additional data, we are testing for counts > 50
        for (int i=0; i < nDataItems; i++) {
            Map<String, Object> value = Maps.newHashMap();
            value.put("account_id","00000003-0000-0030-0040-000000030000");
            value.put("user_id","00000003-0000-0030-0040-000000030000");
            value.put("field2", "Value"+(i+8));
            values2.add(value);
        }

        List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
        for(Map<String, Object> baseValue : values2) {
            updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
        }

        Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
        multiInsertMap.put("object2", updatedValues2);

        //Insert data
        om.insertBatchMixed(multiInsertMap);

        //Count the number of inserts we made
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);

        //now test the count function
        long count = om.count("object2", criteria);
        assertEquals(nDataItems, count);
    }

    @Test
    public void testLargeCountAllowFilteringWithNoFilters() throws Exception {
        logger.debug("Starting testLargeCountAllowFilteringWithNoFilters");

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
        assertNotNull(definition);

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(definition, true);
        logger.debug("Built keyspace: {}", definition.getName());
        cm.setDefaultKeyspace(definition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);

        //Set up test data
        int nDataItems = 200;

        List<Map<String, Object>> values2 =  Lists.newArrayList();

        // insert additional data, we are testing for counts > 50
        for (int i=0; i < nDataItems; i++) {
            Map<String, Object> value = Maps.newHashMap();
            value.put("account_id","00000003-0000-0030-0040-000000030000");
            value.put("user_id","00000003-0000-0030-0040-000000030000");
            value.put("field2", "Value"+(i+8));
            values2.add(value);
        }

        List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
        for(Map<String, Object> baseValue : values2) {
            updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
        }

        Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
        multiInsertMap.put("object2", updatedValues2);

        //Insert data
        om.insertBatchMixed(multiInsertMap);

        //Count the number of inserts we made
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setAllowFiltering(true);

        //now test the count function
        long count = om.count("object2", criteria);
        assertEquals(nDataItems, count);
    }

    @Test
    public void testLargeCountWithLimit() throws Exception {
        logger.debug("Starting testLargeCountWithLimit");

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
        assertNotNull(definition);

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(definition, true);
        logger.debug("Built keyspace: {}", definition.getName());
        cm.setDefaultKeyspace(definition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);

        //Set up test data
        int nDataItems = 200;

        List<Map<String, Object>> values2 =  Lists.newArrayList();

        // insert additional data, we are testing for counts > 50
        for (int i=0; i < nDataItems; i++) {
            Map<String, Object> value = Maps.newHashMap();
            value.put("account_id","00000003-0000-0030-0040-000000030000");
            value.put("user_id","00000003-0000-0030-0040-000000030000");
            value.put("field2", "Value"+(i+8));
            values2.add(value);
        }

        List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
        for(Map<String, Object> baseValue : values2) {
            updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
        }

        Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
        multiInsertMap.put("object2", updatedValues2);

        //Insert data
        om.insertBatchMixed(multiInsertMap);

        //Count the number of inserts we made
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setAllowFiltering(true);
        criteria.setLimit(20L);

        //now test the count function
        long count = om.count("object2", criteria);
        assertEquals(20, count);
    }

    @Test
    public void testLargeCountWithFiltering() throws Exception {
        logger.debug("Starting testLargeCountWithFilteringt");

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
        assertNotNull(definition);

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(definition, true);
        logger.debug("Built keyspace: {}", definition.getName());
        cm.setDefaultKeyspace(definition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);

        //Set up test data
        int nDataItems = 200;

        List<Map<String, Object>> values2 =  Lists.newArrayList();

        // insert additional data, we are testing for counts > 50
        for (int i=0; i < nDataItems; i++) {
            Map<String, Object> value = Maps.newHashMap();
            value.put("account_id","00000003-0000-0030-0040-000000030000");
            value.put("user_id","00000003-0000-0030-0040-000000030000");
            // Set a specific value for data we want to filter
            if (i % 3 == 0) {
                value.put("field2", "taco");
            } else {
                value.put("field2", "Value"+(i+8));
            }
            values2.add(value);
        }

        List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
        for(Map<String, Object> baseValue : values2) {
            updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
        }

        Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
        multiInsertMap.put("object2", updatedValues2);

        //Insert data
        om.insertBatchMixed(multiInsertMap);

        //Count the number of inserts we made
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("field2", "taco");
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setAllowFiltering(true);

        //now test the count function
        long count = om.count("object2", criteria);
        assertEquals((nDataItems/3) + 1, count);
    }

    @Test
    public void testLargeCountWithLimitAndFiltering() throws Exception {
        logger.debug("Starting testLargeCountWithLimitAndFiltering");

        //Build the connection manager
        ConnectionManager cm = getConnectionManager();

        //Build our keyspace definition object
        CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
        assertNotNull(definition);

        //Rebuild the keyspace and get the object mapper
        cm.buildKeyspace(definition, true);
        logger.debug("Built keyspace: {}", definition.getName());
        cm.setDefaultKeyspace(definition);
        ObjectMapper om = cm.getObjectMapper();
        om.setLogCql(true);

        //Set up test data
        int nDataItems = 200;

        List<Map<String, Object>> values2 =  Lists.newArrayList();

        // insert additional data, we are testing for counts > 50
        for (int i=0; i < nDataItems; i++) {
            Map<String, Object> value = Maps.newHashMap();
            value.put("account_id","00000003-0000-0030-0040-000000030000");
            value.put("user_id","00000003-0000-0030-0040-000000030000");
            // Set a specific value for data we want to filter
            if (i % 3 == 0) {
                value.put("field2", "taco");
            } else {
                value.put("field2", "Value"+(i+8));
            }
            values2.add(value);
        }

        List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
        for(Map<String, Object> baseValue : values2) {
            updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
        }

        Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
        multiInsertMap.put("object2", updatedValues2);

        //Insert data
        om.insertBatchMixed(multiInsertMap);

        //Count the number of inserts we made
        SortedMap<String, Object> indexValues = Maps.newTreeMap();
        indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
        indexValues.put("field2", "taco");
        Criteria criteria = new Criteria();
        criteria.setIndexKeys(indexValues);
        criteria.setAllowFiltering(true);
        criteria.setLimit((long)(nDataItems/3) - 10L);

        //now test the count function
        long count = om.count("object2", criteria);
        assertEquals((nDataItems/3) - 10, count);
    }

	@Test
	public void testMultiInsert() throws Exception {
		logger.debug("Starting testMultiInsert");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		logger.debug("Built keyspace: {}", definition.getName());
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper();
		om.setLogCql(true);

		//Set up test data
		List<Map<String, Object>> values1 = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "MultiInsertTestData1.js");
		List<Map<String, Object>> updatedValues1 = Lists.newArrayList();
		for(Map<String, Object> baseValue : values1) {
			updatedValues1.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object1")));
		}
		List<Map<String, Object>> values2 = JsonUtil.rhombusMapFromResource(this.getClass().getClassLoader(), "MultiInsertTestData2.js");
		List<Map<String, Object>> updatedValues2 = Lists.newArrayList();
		for(Map<String, Object> baseValue : values2) {
			updatedValues2.add(JsonUtil.rhombusMapFromJsonMap(baseValue, definition.getDefinitions().get("object2")));
		}
		Map<String, List<Map<String, Object>>> multiInsertMap = Maps.newHashMap();
		multiInsertMap.put("object1", updatedValues1);
		multiInsertMap.put("object2", updatedValues2);

		//Insert data
		om.insertBatchMixed(multiInsertMap);

		//Query it back out
		//Make sure that we have the proper number of results
		SortedMap<String, Object> indexValues = Maps.newTreeMap();
		indexValues.put("account_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
		indexValues.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
		Criteria criteria = new Criteria();
		criteria.setIndexKeys(indexValues);
		criteria.setLimit(50L);
		List<Map<String, Object>> results = om.list("object1", criteria);
		assertEquals(3, results.size());
		results = om.list("object2", criteria);
		assertEquals(4, results.size());
		//now test the count function too
		long count = om.count("object2", criteria);
		assertEquals(4, count);
	}

	@Test
	public void testVisitAllEntries() throws Exception {
		logger.debug("Starting testVisitAllEntries");

		//Build the connection manager
		ConnectionManager cm = getConnectionManager();

		//Build our keyspace definition object
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "MultiInsertKeyspace.js");
		assertNotNull(definition);

		//Rebuild the keyspace and get the object mapper
		cm.buildKeyspace(definition, true);
		logger.debug("Built keyspace: {}", definition.getName());
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper();
		om.setLogCql(true);

		//Set up test data
		//insert a bunch in batches of 50
		int totalCount = 0;
		for(int batch = 0; batch < 400; batch++){
			//do another 50
			List<Map<String,Object>> toinsert = Lists.newArrayList();
			for(int i = 0; i<50; i++){
				Map<String,Object> item = Maps.newHashMap();
				item.put("account_id",UUID.fromString("00000003-0000-0030-0040-000000030000"));
				item.put("user_id", UUID.fromString("00000003-0000-0030-0040-000000030000"));
				item.put("field1","value"+(totalCount++));
				toinsert.add(item);
			}
			Map<String, List<Map<String, Object>>> insertMap = Maps.newHashMap();
			insertMap.put("object1", toinsert);

			//Insert data
			om.insertBatchMixed(insertMap);
		}


		//Now visit all of the objects we just inserted
		class MyVisitor implements CObjectVisitor {
			int counter = 0;
			public int getCount(){
				return counter;
			}

			@Override
			public void visit(Map<String, Object> object) {
				//To change body of implemented methods use File | Settings | File Templates.
				//System.out.println("Counter is "+counter+" value is "+object.get("field1"));
				counter++;
			}

			@Override
			public boolean shouldInclude(Map<String, Object> object) {
				return true;
			}
		};

		MyVisitor visitor = new MyVisitor();

		long start = System.currentTimeMillis();
		om.visitObjects("object1", visitor);
		long end = System.currentTimeMillis();
		long syncTime = end - start;

		System.out.println("Visiting all objects took " + syncTime+"ms");


		assertEquals(20000, visitor.getCount());
	}


	@Test
	public void testTruncateKeyspace() throws Exception {
		logger.debug("testTruncateKeyspace");
		// Set up a connection manager and build the cluster
		ConnectionManager cm = getConnectionManager();

		// Build the keyspace
		CKeyspaceDefinition definition = JsonUtil.objectFromJsonResource(CKeyspaceDefinition.class, this.getClass().getClassLoader(), "CKeyspaceTestData.js");
		assertNotNull(definition);
		cm.setDefaultKeyspace(definition);
		ObjectMapper om = cm.getObjectMapper(definition.getName());

		// Insert something
		Map<String, Object> testObject = JsonUtil.rhombusMapFromJsonMap(TestHelpers.getTestObject(0), definition.getDefinitions().get("testtype"));
		UUID key = (UUID)om.insert("testtype", testObject);

		// Truncate tables
		om.truncateTables();

		// Make sure it is gone
		Map<String, Object> returnedObject = om.getByKey("testtype", key);
		assertNull(returnedObject);

		// Insert something new
		key = (UUID)om.insert("testtype", testObject);

		// Make sure it is there
		returnedObject = om.getByKey("testtype", key);
		assertNotNull(returnedObject);
	}
}
