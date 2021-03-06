package com.pardot.rhombus;


import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.pardot.rhombus.cobject.*;
import com.pardot.rhombus.cobject.async.StatementIteratorConsumer;
import com.pardot.rhombus.cobject.migrations.CKeyspaceDefinitionMigrator;
import com.pardot.rhombus.cobject.migrations.CObjectMigrationException;
import com.pardot.rhombus.cobject.shardingstrategy.ShardingStrategyNone;
import com.pardot.rhombus.cobject.statement.BoundedCQLStatementIterator;
import com.pardot.rhombus.cobject.statement.CQLStatement;
import com.pardot.rhombus.cobject.statement.CQLStatementIterator;
import com.pardot.rhombus.util.JsonUtil;
import com.yammer.metrics.core.*;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Pardot, an ExactTarget company
 * User: Michael Frank
 * Date: 4/17/13
 */
public class ObjectMapper implements CObjectShardList {

	private static Logger logger = LoggerFactory.getLogger(ObjectMapper.class);
	private static final int reasonableStatementLimit = 50;
	private boolean executeAsync = true;
	private boolean useCqlBatching = false;
	private boolean logCql = false;
	private boolean cacheBoundedQueries = true;
	private CQLExecutor cqlExecutor;
	private Session session;
	private CKeyspaceDefinition keyspaceDefinition;
	private CObjectCQLGenerator cqlGenerator;
	private Long batchTimeout;
    private String defaultSSTableOutputPath = System.getProperty("user.dir");
    private Map<String, Pair<CQLSSTableWriter, Map<CIndex, CQLSSTableWriter>>> SSTableWriters = Maps.newHashMap();

	public ObjectMapper(Session session, CKeyspaceDefinition keyspaceDefinition, Integer consistencyHorizon, Long batchTimeout) {
		this.cqlExecutor = new CQLExecutor(session, logCql, keyspaceDefinition.getConsistencyLevel());
		this.session = session;
		this.keyspaceDefinition = keyspaceDefinition;
		this.cqlGenerator = new CObjectCQLGenerator(keyspaceDefinition.getName(), keyspaceDefinition.getDefinitions(), this, consistencyHorizon);
		this.batchTimeout = batchTimeout;
	}

	/**
	 * This should only be used after a successful migration to update the cached version of the keyspace definition
	 * @param keyspaceDefinition Updated keyspace definition
	 */
	public void setKeyspaceDefinition(CKeyspaceDefinition keyspaceDefinition) {
		this.keyspaceDefinition = keyspaceDefinition;
	}

	public void truncateTables() {
		// Index table
		CQLStatement truncateCql = cqlGenerator.makeCQLforShardIndexTableTruncate();
		logger.debug("Truncating shard index table");
		try {
			cqlExecutor.executeSync(truncateCql);
		} catch(Exception e) {
			logger.debug("Failed to truncate table with query ", truncateCql.getQuery());
		}

		// Index updates
		truncateCql = cqlGenerator.makeCQLforIndexUpdateTableTruncate();
		logger.debug("Truncating index update table");
		try {
			cqlExecutor.executeSync(truncateCql);
		} catch(Exception e) {
			logger.debug("Failed to truncate table with query ", truncateCql.getQuery());
		}


		// All of the tables in the keyspace
		if(keyspaceDefinition.getDefinitions() != null) {
			for(CDefinition definition : keyspaceDefinition.getDefinitions().values()) {
				CQLStatementIterator truncateStatementIterator = cqlGenerator.makeCQLforTruncate(definition.getName());
				while(truncateStatementIterator.hasNext()) {
					truncateCql = truncateStatementIterator.next();
					try {
						cqlExecutor.executeSync(truncateCql);
					} catch(Exception e) {
						logger.debug("Failed to truncate table with query ", truncateCql.getQuery());
					}
				}
			}
		}
	}

	/**
	 * Build the tables contained in the keyspace definition.
	 * This method assumes that its keyspace exists and
	 * does not contain any tables.
	 */
	public void buildKeyspace(Boolean forceRebuild) {
		if(forceRebuild) {
			truncateTables();
		}
		//we are about to rework the the keyspaces, so lets clear the bounded query cache
		cqlExecutor.clearStatementCache();
		//First build the shard index
		CQLStatement cql = cqlGenerator.makeCQLforShardIndexTableCreate();
		try {
			cqlExecutor.executeSync(cql);
			logger.debug("Created shard index table");
		} catch(Exception e) {
			if(forceRebuild) {
				CQLStatement dropCql = cqlGenerator.makeCQLforShardIndexTableDrop();
				logger.debug("Attempting to drop table with cql {}", dropCql);
				cqlExecutor.executeSync(dropCql);
				cqlExecutor.executeSync(cql);
			} else {
				logger.debug("Not dropping shard index table");
			}
		}
		//Next build the update index
		cql = cqlGenerator.makeCQLforIndexUpdateTableCreate();
		try{
			cqlExecutor.executeSync(cql);
			logger.debug("Created index update table");
		}
		catch(Exception e) {
			logger.debug("Unable to create update index table. It may already exist");
		}

		//Now build the tables for each object if the definition contains tables
		if(keyspaceDefinition.getDefinitions() != null) {
			for(CDefinition definition : keyspaceDefinition.getDefinitions().values()) {
				CQLStatementIterator statementIterator = cqlGenerator.makeCQLforCreate(definition.getName());
				CQLStatementIterator dropStatementIterator = cqlGenerator.makeCQLforDrop(definition.getName());
				while(statementIterator.hasNext()) {
					cql = statementIterator.next();
					CQLStatement dropCql = dropStatementIterator.next();
					try {
						logger.debug("Creating table in keyspace {}\n{}", this.getKeyspaceDefinition().getName(), cql);
						cqlExecutor.executeSync(cql);
					} catch (AlreadyExistsException e) {
						if(forceRebuild) {
							logger.debug("ForceRebuild is on, dropping table");
							try {
								cqlExecutor.executeSync(dropCql);
							} catch (InvalidQueryException qe) {
								logger.error("Could not rebuild table: {}", cql.getQuery(), qe);
							}
							cqlExecutor.executeSync(cql);
						} else {
							logger.warn("Table already exists and will not be updated");
						}
					}
				}
			}
		}
	}

	public void createKeyspaceDefinitionTableIfNotExists() {
		CQLStatement cql = cqlGenerator.makeCQLforCreateKeyspaceDefinitionsTable();
		try {
			cqlExecutor.executeSync(cql);
			logger.debug("Created keyspace definition table with cql: {}", cql.getQuery());
		}
		catch(Exception e) {
			logger.debug("Unable to create keyspace definitions table. It may already exist");
		}
	}

	public void insertKeyspaceDefinition(String name, String keyspaceDefinitionAsJson) throws CQLGenerationException, RhombusException {
		CQLStatementIterator it = cqlGenerator.makeCQLforInsertKeyspaceDefinition(name, keyspaceDefinitionAsJson);
		executeStatements(it);
	}

	public UUID getTimeUUIDAtEndOfConsistencyHorizion(){
		return cqlGenerator.getTimeUUIDAtEndOfConsistencyHorizion();
	}

	public void executeStatements(CQLStatementIterator statementIterator) throws RhombusException {
		List<CQLStatementIterator> statementIterators = Lists.newArrayList();
		statementIterators.add(statementIterator);
		executeStatements(statementIterators);
	}

	public void executeStatements(List<CQLStatementIterator> statementIterators) throws RhombusException {
		boolean canExecuteAsync = true;
		boolean allPreparable = true;
		for(CQLStatementIterator statementIterator : statementIterators) {
			if(!statementIterator.isBounded()) {
				canExecuteAsync = false;
			}
			if(!(statementIterator instanceof BoundedCQLStatementIterator) || !((BoundedCQLStatementIterator) statementIterator).allStatementsPreparable()) {
				allPreparable = false;
			}
		}
		if(canExecuteAsync &&  this.executeAsync) {
			//If this is a bounded statement iterator, send it through the async path
			long start = System.nanoTime();
			logger.debug("Executing statements async");
			if(this.useCqlBatching && allPreparable) {
				cqlExecutor.executeBatch(statementIterators);
			} else {
				List<StatementIteratorConsumer> consumers = Lists.newArrayList();
				for(CQLStatementIterator statementIterator : statementIterators) {
					StatementIteratorConsumer consumer = new StatementIteratorConsumer((BoundedCQLStatementIterator) statementIterator, cqlExecutor, batchTimeout);
					consumer.start();
					consumers.add(consumer);
				}
				for(StatementIteratorConsumer consumer : consumers) {
					consumer.join();
				}
			}
			logger.debug("Async execution took {}us", (System.nanoTime() - start) / 1000);
		} else {
			logger.debug("Executing statements sync");
			long start = System.nanoTime();
			for(CQLStatementIterator statementIterator : statementIterators) {
				while(statementIterator.hasNext()) {
					CQLStatement statement = statementIterator.next();
					final com.yammer.metrics.core.Timer syncSingleExecTimer = com.yammer.metrics.Metrics.defaultRegistry().newTimer(ObjectMapper.class, "syncSingleExec");
					final TimerContext syncSingleExecTimerContext = syncSingleExecTimer.time();
					cqlExecutor.executeSync(statement);
					syncSingleExecTimerContext.stop();
				}
			}
			logger.debug("Sync execution took {} ms", (System.nanoTime() - start) / 1000000);
		}
	}

	@Override
	public List<Long> getShardIdList(CDefinition def, SortedMap<String, Object> indexValues, CObjectOrdering ordering, @Nullable UUID start, @Nullable UUID end) throws CQLGenerationException {
		CQLStatement shardIdGet = CObjectCQLGenerator.makeCQLforGetShardIndexList(this.keyspaceDefinition.getName(), def, indexValues, ordering, start, end);
		ResultSet resultSet = cqlExecutor.executeSync(shardIdGet);
		List<Long> shardIdList = Lists.newArrayList();
		for(Row row : resultSet) {
			shardIdList.add(row.getLong("shardid"));
		}
		return shardIdList;
	}

	/**
	 * Insert a batch of mixed new object with values
	 * @param objects Objects to insert
	 * @return ID of most recently inserted object
	 * @throws CQLGenerationException
	 */
	public Object insertBatchMixed(Map<String, List<Map<String, Object>>> objects) throws CQLGenerationException, RhombusException {
		logger.debug("Insert batch mixed");
		List<CQLStatementIterator> statementIterators = Lists.newArrayList();
		Object key = null;
		for(String objectType : objects.keySet()) {
			for(Map<String, Object> values : objects.get(objectType)) {
				//use the id that was passed in for the insert if it was provided. Otherwise assume the key is a timeuuid
				if(values.containsKey("id")){
					key = values.get("id");
					values.remove("id");
				}
				else{
					key = UUIDs.timeBased();
				}
				long timestamp = System.currentTimeMillis();
				CQLStatementIterator statementIterator = cqlGenerator.makeCQLforInsert(objectType, values, key, timestamp);
				statementIterators.add(statementIterator);
			}
		}
		executeStatements(statementIterators);
		return key;
	}


	/**
	 * Insert a new object with values and key
	 * @param objectType Type of object to insert
	 * @param values Values to insert
	 * @param key Time UUID to use as key
	 * @return ID if newly inserted object
	 * @throws CQLGenerationException
	 */
	public Object insert(String objectType, Map<String, Object> values, Object key) throws CQLGenerationException, RhombusException {
		logger.debug("Insert {}", objectType);
		if(key == null) {
			key = UUIDs.timeBased();
		}
		long timestamp = System.currentTimeMillis();
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforInsert(objectType, values, key, timestamp);
		executeStatements(statementIterator);
		return key;
	}

	/**
	 * Insert a new objectType with values
	 * @param objectType Type of object to insert
	 * @param values Values to insert
	 * @return UUID of inserted object
	 * @throws CQLGenerationException
	 */
	public Object insert(String objectType, Map<String, Object> values) throws CQLGenerationException, RhombusException {
		Object key = null;
		if(values.containsKey("id")) {
			key = values.get("id");
			values.remove("id");
		}
		else{
			key = UUIDs.timeBased();
		}
		return insert(objectType, values, key);
	}

	/**
	 * Used to insert an object with a UUID based on the provided timestamp
	 * Best used for testing, as time resolution collisions are not accounted for
	 * @param objectType Type of object to insert
	 * @param values Values to insert
	 * @param timestamp Timestamp to use to create the object UUID
	 * @return the UUID of the newly inserted object
	 */
	public Object insert(String objectType, Map<String, Object> values, Long timestamp) throws CQLGenerationException, RhombusException {
		UUID uuid = UUIDs.startOf(timestamp);
		return insert(objectType, values, uuid);
	}

	/**
	 * Delete Object of type with id key
	 * @param objectType Type of object to delete
	 * @param key Key of object to delete
	 */
	public void delete(String objectType, UUID key) throws RhombusException {
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		Map<String, Object> values = getByKey(objectType, key);
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforDelete(objectType, key, values, null);
		mapResults(statementIterator, def, 0L);
	}

	public void deleteObsoleteIndex(IndexUpdateRow row, CIndex index, Map<String,Object> indexValues){
		Statement cql = cqlGenerator.makeCQLforDeleteUUIDFromIndex_WorkaroundForUnpreparableTimestamp(
			this.keyspaceDefinition.getName(),
			keyspaceDefinition.getDefinitions().get(row.getObjectName()),
			index,
			row.getInstanceId(),
			index.getIndexKeyAndValues(indexValues),
			row.getTimeStampOfMostCurrentUpdate());
		cqlExecutor.executeSync(cql);
	}

	public void deleteObsoleteUpdateIndexColumn(IndexUpdateRowKey rowKey, UUID id){
		CQLStatement cql = cqlGenerator.makeCQLforDeleteObsoleteUpdateIndexColumn(rowKey, id);
		cqlExecutor.executeSync(cql);
	}

	/**
	 * Update objectType with key using values
	 * @param objectType Type of object to update
	 * @param key Key of object to update
	 * @param values Values to update
	 * @param timestamp Timestamp to execute update at
	 * @param ttl Time to live for update
	 * @return new UUID of the object
	 * @throws CQLGenerationException
	 */
	public UUID update(String objectType, UUID key, Map<String, Object> values, Long timestamp, Integer ttl) throws CQLGenerationException, RhombusException {
		//New Version
		//(1) Get the old version
		Map<String, Object> oldversion = getByKey(objectType, key);

		//(2) Pass it all into the cql generator so it can create the right statements
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforUpdate(keyspaceDefinition.getName(), def, key, oldversion, values);
		executeStatements(statementIterator);
		return key;
	}

	/**
	 * Update objectType with key using values
	 * @param objectType Type of object to update
	 * @param key Key of object to update
	 * @param values Values to update
	 * @return new UUID of the object
	 * @throws CQLGenerationException
	 */
	public UUID update(String objectType, UUID key, Map<String, Object> values) throws CQLGenerationException, RhombusException {
		return update(objectType, key, values, null, null);
	}

	/**
	 *
	 * @param objectType Type of object to get
	 * @param key Key of object to get
	 * @return Object of type with key or null if it does not exist
	 */
	public Map<String, Object> getByKey(String objectType, Object key) throws RhombusException {
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforGet(objectType, key);
		List<Map<String, Object>> results = mapResults(statementIterator, def, 1L);
		if(results.size() > 0) {
			return results.get(0);
		} else {
			return null;
		}
	}

	/**
	 *
	 * @param objectType Type of object to get
	 * @return Class of the id field for this object
	 */
	public Class getTypeOfKey(String objectType){
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		return def.getPrimaryKeyClass();
	}

	/**
	 * @param objectType Type of object to query
	 * @param criteria Criteria to query by
	 * @return List of objects that match the specified type and criteria
	 * @throws CQLGenerationException
	 */
	public List<Map<String, Object>> list(String objectType, Criteria criteria) throws CQLGenerationException, RhombusException {
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforList(objectType, criteria, false);
		return mapResults(statementIterator, def, criteria.getLimit());
	}

	/**
	 * @param objectType Type of object to count
	 * @param criteria Criteria to count by
	 * @return number of items matching the criteria
	 * @throws CQLGenerationException
	 */
	public long count(String objectType, Criteria criteria) throws CQLGenerationException, RhombusException {
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		CQLStatementIterator statementIterator = cqlGenerator.makeCQLforList(objectType, criteria, true);
		return mapCount(statementIterator, def, criteria.getLimit());
	}

	public void visitObjects(String objectType, CObjectVisitor visitor){
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectType);
		CQLStatement statement = this.cqlGenerator.makeCQLforTableScan(objectType);
		Statement s = new SimpleStatement(statement.getQuery());
		s.setFetchSize(200);
		ResultSet resultSet = session.execute(s);

		Iterator<Row> it = resultSet.iterator();
		while(it.hasNext()){
			Map<String,Object> obj = this.mapResult(it.next(),def);
			if(visitor.shouldInclude(obj)){
				visitor.visit(obj);
			}
		}
	}


	protected SortedMap<String,Object> unpackIndexValuesFromJson(CDefinition def, String json) throws IOException, JsonMappingException {
		com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
		TreeMap<String,Object> jsonMap = om.readValue(json, TreeMap.class);
		return JsonUtil.rhombusMapFromJsonMap(jsonMap,def);
	}

	public IndexUpdateRow getNextUpdateIndexRow(@Nullable IndexUpdateRowKey lastInstanceKey) throws IOException, JsonMappingException {
		CQLStatement cqlForNext = (lastInstanceKey == null) ?
			cqlGenerator.makeGetFirstEligibleIndexUpdate() : cqlGenerator.makeGetNextEligibleIndexUpdate(lastInstanceKey);
		ResultSet resultSet = cqlExecutor.executeSync(cqlForNext);
		if(resultSet.isExhausted()){
			return null;
		}
		IndexUpdateRowKey nextInstanceKey = new IndexUpdateRowKey(resultSet.one());
		CQLStatement cqlForRow = cqlGenerator.makeGetRowIndexUpdate(keyspaceDefinition.getName(), nextInstanceKey);
		resultSet = cqlExecutor.executeSync(cqlForRow);
		List<Row> results = resultSet.all();
		if(results.size() == 0 ){
			return null;
		}
		String objectName = results.get(0).getString("statictablename");
		CDefinition def = keyspaceDefinition.getDefinitions().get(objectName);

		List<SortedMap<String,Object>> indexValueList = Lists.newArrayList();
		List<UUID> ids = Lists.newArrayList();
		for(Row update : results){
			indexValueList.add(unpackIndexValuesFromJson(def,update.getString("indexvalues")));
			ids.add(update.getUUID("id"));
		}


		return new IndexUpdateRow(
			objectName,
			results.get(0).getUUID("instanceid"),
			UUIDs.unixTimestamp(results.get(0).getUUID("id"))*1000,
			indexValueList,
			ids
		);
	}


	/**
	 * Iterates through cql statements executing them in sequence and mapping the results until limit is reached
	 * @param statementIterator Statement iterator to execute
	 * @param definition definition to execute the statements against
	 * @return Ordered resultset concatenating results from statements in statement iterator.
	 */
	private List<Map<String, Object>> mapResults(CQLStatementIterator statementIterator, CDefinition definition, Long limit) throws RhombusException {
		List<Map<String, Object>> results = Lists.newArrayList();
		int statementNumber = 0;
		int resultNumber = 0;
		Map<String, Object> clientFilters = statementIterator.getClientFilters();
		while(statementIterator.hasNext(resultNumber) ) {
			CQLStatement cql = statementIterator.next();
			ResultSet resultSet = cqlExecutor.executeSync(cql);
			for(Row row : resultSet) {
				Map<String, Object> result = mapResult(row, definition);
				boolean resultMatchesFilters = true;
				if(clientFilters != null) {
					resultMatchesFilters = this.resultMatchesFilters(result, clientFilters);
				}
				if(resultMatchesFilters) {
					results.add(result);
					resultNumber++;
				}
			}
			statementNumber++;
			if((limit > 0 && resultNumber >= limit)) {
				logger.debug("Breaking from mapping results");
				break;
			}
			if(statementNumber > reasonableStatementLimit) {
				throw new RhombusException("Query attempted to execute more than " + reasonableStatementLimit + " statements.");
			}
		}
		return results;
	}

	/**
	 * Make sure values in result match values in filter
	 * @param result Result retrieved from persistence
	 * @param filters Filters not applied at persistence layer
	 * @return true if filters match, false if not
	 */
	public boolean resultMatchesFilters(Map<String, Object> result, Map<String, Object> filters) {
		for(String filterKey : filters.keySet()) {
			Object resultValue = result.get(filterKey);
			Object filterValue = filters.get(filterKey);
			if(!Objects.equal(resultValue, filterValue)) {
				return false;
			}
		}
		return true;
	}

	private Long mapCount(CQLStatementIterator statementIterator, CDefinition definition, Long limit) throws RhombusException {
		Long resultCount = 0L;
        int statementNumber = 0;
		while (statementIterator.hasNext()){
			CQLStatement cql = statementIterator.next();
            Map<String, Object> clientFilters = statementIterator.getClientFilters();
			ResultSet resultSet = cqlExecutor.executeSync(cql);
			if(!resultSet.isExhausted()){
                if (clientFilters == null) {
                    // If we don't have client filters, this was just a count query, so increment by the result value
                    resultCount += resultSet.one().getLong(0);
                } else {
                    // Otherwise we do have client filters so we need to map the results and apply the filters
                    for (Row row : resultSet) {
                        Map<String, Object> result = mapResult(row, definition);
                        if (this.resultMatchesFilters(result, clientFilters)) {
                            resultCount++;
                        }
                    }
                }
                statementNumber++;
                if((limit > 0 && resultCount >= limit)) {
                    logger.debug("Breaking from mapping count query results");
                    resultCount = limit;
                    break;
                }
                if(statementNumber > reasonableStatementLimit) {
                    throw new RhombusException("Query attempted to execute more than " + reasonableStatementLimit + " statements.");
                }
			}
		}
		return resultCount;
	}


	public List<CQLStatement> runMigration(CKeyspaceDefinition oldKeyspaceDefinition, CKeyspaceDefinition newKeyspaceDefinition, boolean executeCql) throws CObjectMigrationException {
		List<CQLStatement> ret = Lists.newArrayList();
		try{
			//we have the keyspace definitions, now run the migration
			CKeyspaceDefinitionMigrator migrator = new CKeyspaceDefinitionMigrator(oldKeyspaceDefinition, newKeyspaceDefinition);
			CQLStatementIterator cqlit = migrator.getMigrationCQL(this.cqlGenerator);
			while(cqlit.hasNext()){
				CQLStatement statement = cqlit.next();
				ret.add(statement);
				if(executeCql){
					cqlExecutor.executeSync(statement);
				}
			}
		} catch(Exception e){
			throw new CObjectMigrationException(e);
		}
		return ret;
	}

	/**
	 * @param row The row to map
	 * @param definition The definition to map the row on to
	 * @return Data contained in a row mapped to the object described in definition.
	 */
	private Map<String, Object> mapResult(Row row, CDefinition definition) {
		Map<String, Object> result = Maps.newHashMap();
		if(definition.getFields().containsKey("id")){
			result.put("id",getFieldValue(row,definition.getField("id")));
		}
		else {
			result.put("id", row.getUUID("id"));
		}
		for(CField field : definition.getFields().values()) {
			result.put(field.getName(), getFieldValue(row, field));
		}
		return result;
	}

    public void prePrepareInsertStatements() throws CQLGenerationException {
        Map<String,CDefinition> defs = this.getKeyspaceDefinition().getDefinitions();
		if(defs != null) {
			for(CDefinition def : defs.values()){
				prePrepareInsertStatements(def);
			}
		}
    }

    public void prePrepareInsertStatements(CDefinition def) throws CQLGenerationException{
        SortedMap<String, Object> values = Maps.newTreeMap();
        for( CField f : def.getFields().values() ){
            values.put(f.getName(), f.getEmptyJavaObjectOfThisType());
        }
        CQLStatementIterator sti = cqlGenerator.makeCQLforInsert(def.getName(),values,UUIDs.timeBased(),0L);
        while(sti.hasNext()){
            CQLStatement cql = sti.next();
            cqlExecutor.prepareStatement(session,cql);
        }
    }

	private Object getFieldValue(Row row, CField field) {
		if(row.isNull(field.getName())){
			return null;
		}
		Object fieldValue;
		switch(field.getType()) {
			case ASCII:
			case VARCHAR:
			case TEXT:
				fieldValue = row.getString(field.getName());
				break;
			case BIGINT:
			case COUNTER:
				fieldValue = row.getLong(field.getName());
				break;
			case BLOB:
				fieldValue = row.getBytes(field.getName());
				break;
			case BOOLEAN:
				fieldValue = row.getBool(field.getName());
				break;
			case DECIMAL:
				fieldValue = row.getDecimal(field.getName());
				break;
			case DOUBLE:
				fieldValue = row.getDouble(field.getName());
				break;
			case FLOAT:
				fieldValue = row.getFloat(field.getName());
				break;
			case INT:
				fieldValue = row.getInt(field.getName());
				break;
			case TIMESTAMP:
				fieldValue = row.getDate(field.getName());
				break;
			case UUID:
			case TIMEUUID:
				fieldValue = row.getUUID(field.getName());
				break;
			case VARINT:
				fieldValue = row.getVarint(field.getName());
				break;
			default:
				fieldValue = null;
		}
		return (fieldValue == null ? null : fieldValue);
	}

	public Map<String, Object> coerceRhombusValuesFromJsonMap(String objectType, Map<String, Object> values) {
		return JsonUtil.rhombusMapFromJsonMap(values, keyspaceDefinition.getDefinitions().get(objectType));
	}

    /**
     * Creates an SSTable keyspace output directory at defaultSSTableOutputPath and table output directories for each SSTable,
     * and initializes each SSTableWriter for each static and index table in this keyspace.
     * @param sorted Defines if the SSTableWriters created by this should be set as sorted, which improves performance if
     *               rows are inserted in SSTable sort order, but throws exceptions if they are inserted in the wrong order.
     * @throws CQLGenerationException
     * @throws IOException
     */
    public void initializeSSTableWriters(boolean sorted) throws CQLGenerationException, IOException {
        Map<String, CDefinition> definitions = this.keyspaceDefinition.getDefinitions();

        // Make sure the SSTableOutput directory exists and is clear
        String keyspacePath = this.defaultSSTableOutputPath + "/" + this.keyspaceDefinition.getName();
        File keyspaceDir = new File(keyspacePath);
        if (keyspaceDir.exists()) {
            FileUtils.deleteRecursive(new File(keyspacePath));
        }
        if (!new File(keyspacePath).mkdir()) {
            throw new IOException("Failed to create SSTable keyspace output directory at " + keyspacePath);
        }

        this.SSTableWriters.put(CObjectShardList.SHARD_INDEX_TABLE_NAME, Pair.create(this.buildSSTableWriterForShardIndexTable(sorted), (Map<CIndex, CQLSSTableWriter>) null));

        for (String defName : definitions.keySet()) {
            // Build the CQLSSTableWriter for the static table
            CQLSSTableWriter staticWriter = buildSSTableWriterForStaticTable(definitions.get(defName), sorted);

            // Build the CQLSSTableWriter for all the index tables
            List<CIndex> indexes = definitions.get(defName).getIndexesAsList();
            Map<CIndex, CQLSSTableWriter> indexWriters = Maps.newHashMap();
            for (CIndex index : indexes) {
                CQLSSTableWriter writer = buildSSTableWriterForWideTable(definitions.get(defName), index, sorted);
                indexWriters.put(index, writer);
            }
            this.SSTableWriters.put(defName, Pair.create(staticWriter, indexWriters));
        }
    }

    /**
     * Writes Rhombus objects into the appropriate static and index SSTableWriters for their object definition. Requires that initializeSSTableWriters
     * be called first and completeSSTableWrites be called when you're done inserting things. Object values with key "shardid" will be ignored and removed.
     * @param objects Map keyed by object name with a list of Rhombus objects to insert for that table
     * @throws CQLGenerationException
     * @throws IOException
     * @throws InvalidRequestException
     */
    public void insertIntoSSTable(Map<String, List<Map<String, Object>>> objects) throws CQLGenerationException, IOException, InvalidRequestException {
        Map<String, CDefinition> definitions = this.keyspaceDefinition.getDefinitions();
        for (String tableName : objects.keySet()) {
            CDefinition definition = definitions.get(tableName);
            // Pull an existing CQLSSTableWriter for this object type if we already have one, if not make a new one
            if (!this.SSTableWriters.containsKey(tableName)) {
                throw new RuntimeException("Tried to write to uninitialized SSTableWriter for static table " + tableName);
            }
            CQLSSTableWriter staticWriter = this.SSTableWriters.get(tableName).left;
            Map<CIndex, CQLSSTableWriter> indexWriters = this.SSTableWriters.get(tableName).right;

            for (Map<String, Object> insert : objects.get(tableName)) {
                staticWriter.addRow(insert);
                for (CIndex index : indexWriters.keySet()) {
                    if(definition.isAllowNullPrimaryKeyInserts()){
                        //check if we have the necessary primary fields to insert on this index. If not just continue;
                        if(!index.validateIndexKeys(index.getIndexKeyAndValues(insert))){
                            continue;
                        }
                    }
                    // Add the shard id to index writes
                    long shardId = index.getShardingStrategy().getShardKey(insert.get("id"));
                    insert.put("shardid", shardId);
                    indexWriters.get(index).addRow(insert);
                    // Pull the shardid back out to avoid the overhead of cloning the values and keep our abstraction from leaking
                    insert.remove("shardid");

                    // If this index uses shards, we need to record the write into the shard index table
                    if((!(index.getShardingStrategy() instanceof ShardingStrategyNone))){
                        String indexValuesString = CObjectCQLGenerator.makeIndexValuesString(index.getIndexValues(insert));
                        Map<String, Object> shardIndexInsert = Maps.newHashMap();
                        shardIndexInsert.put("tablename", CObjectCQLGenerator.makeTableName(definition, index));
                        shardIndexInsert.put("indexvalues", indexValuesString);
                        shardIndexInsert.put("shardid", shardId);
                        shardIndexInsert.put("targetrowkey", shardId+":"+indexValuesString);
                        this.SSTableWriters.get(CObjectShardList.SHARD_INDEX_TABLE_NAME).left.addRow(shardIndexInsert);
                    }
                }
            }
        }
    }

    /**
     * Builds an SSTableWriter for a static table
     * @param sorted Defines if the SSTableWriters created by this should be set as sorted, which improves performance if
     *               rows are inserted in SSTable sort order, but throws exceptions if they are inserted in the wrong order.
     * @return A CQLSSTableWriter for this static table
     * @throws CQLGenerationException
     * @throws IOException
     */
    private CQLSSTableWriter buildSSTableWriterForShardIndexTable(boolean sorted) throws CQLGenerationException, IOException {
        // Generate CQL create syntax
        String createCQL = this.cqlGenerator.makeCQLforShardIndexTableCreate().getQuery();

        // Generate CQL insert syntax
        String tableName = CObjectShardList.SHARD_INDEX_TABLE_NAME;
        String insertCQL = this.cqlGenerator.makeCQLforInsertNoValuesforShardIndex(tableName).getQuery();

        String SSTablePath = this.defaultSSTableOutputPath + "/" + keyspaceDefinition.getName() + "/" + tableName;
        if (!new File(SSTablePath).mkdir()) {
            throw new IOException("Failed to create new directory for SSTable writing at path: " + SSTablePath);
        }

        CQLSSTableWriter.Builder builder =
                CQLSSTableWriter.builder()
                        .inDirectory(SSTablePath)
                        .forTable(createCQL)
                        .using(insertCQL);
        if (sorted) { builder = builder.sorted(); }
        return builder.build();
    }

    /**
     * Builds an SSTableWriter for a static table
     * @param definition Definition of object to build table for
     * @param sorted Defines if the SSTableWriters created by this should be set as sorted, which improves performance if
     *               rows are inserted in SSTable sort order, but throws exceptions if they are inserted in the wrong order.
     * @return A CQLSSTableWriter for this static table
     * @throws CQLGenerationException
     * @throws IOException
     */
    private CQLSSTableWriter buildSSTableWriterForStaticTable(CDefinition definition, boolean sorted) throws CQLGenerationException, IOException {
        // Generate CQL create syntax
        String tableName = definition.getName();
        String createCQL = this.cqlGenerator.makeStaticTableCreate(definition).getQuery();

        // Generate CQL insert syntax
        String insertCQL = this.cqlGenerator.makeCQLforInsertNoValuesforStaticTable(tableName).getQuery();

        String SSTablePath = this.defaultSSTableOutputPath + "/" + keyspaceDefinition.getName() + "/" + tableName;
        if (!new File(SSTablePath).mkdir()) {
            throw new IOException("Failed to create new directory for SSTable writing at path: " + SSTablePath);
        }

        CQLSSTableWriter.Builder builder =
            CQLSSTableWriter.builder()
                .inDirectory(SSTablePath)
                .forTable(createCQL)
                .using(insertCQL);
        if (sorted) { builder = builder.sorted(); }
        return builder.build();
    }

    /**
     * Builds an SSTableWriter for a wide/index table
     * @param definition The definition this index/wide table is on
     * @param index The index/wide table to create an CQLSSTableWriter for
     * @param sorted Defines if the SSTableWriters created by this should be set as sorted, which improves performance if
     *               rows are inserted in SSTable sort order, but throws exceptions if they are inserted in the wrong order.
     * @return An CQLSSTableWriter for this wide table
     * @throws CQLGenerationException
     */
    private CQLSSTableWriter buildSSTableWriterForWideTable(CDefinition definition, CIndex index, boolean sorted) throws CQLGenerationException, IOException {
        String indexTableName = CObjectCQLGenerator.makeTableName(definition, index);
        // Generate CQL create syntax
        String createCQL = this.cqlGenerator.makeWideTableCreate(definition, index).getQuery();

        // Generate CQL insert syntax
        // Just use 1 as the value for shardId, doesn't matter since we're not actually using values here
        String insertCQL = this.cqlGenerator.makeCQLforInsertNoValuesforWideTable(definition, indexTableName, 1L).getQuery();

        String SSTablePath = this.defaultSSTableOutputPath + "/" + keyspaceDefinition.getName() + "/" + indexTableName;
        if (!new File(SSTablePath).mkdir()) {
            throw new IOException("Failed to create new directory for SSTable writing at path: " + SSTablePath);
        }

        CQLSSTableWriter.Builder builder =
                CQLSSTableWriter.builder()
                        .inDirectory(SSTablePath)
                        .forTable(createCQL)
                        .using(insertCQL);
        if (sorted) { builder = builder.sorted(); }
        return builder.build();
    }

    /**
     * Sets the path to write SSTables to, if not set it will default to the current user.dir
     * @param path Path to write SSTables to
     */
    public void setSSTableOutputPath(String path) {
        this.defaultSSTableOutputPath = path;
    }

    /**
     * Completes writes to SSTables and cleans up empty table directories. Must be called after writing to SSTables if
     * you actually want to use the SSTables for anything.
     * @throws IOException
     */
    public void completeSSTableWrites() throws IOException {
        Map<String, CDefinition> definitions = this.keyspaceDefinition.getDefinitions();
        for (String tableName : this.SSTableWriters.keySet()) {
            // Close all SSTableWriters
            this.SSTableWriters.get(tableName).left.close();
            this.clearSSTableDirectoryIfEmpty(tableName);

            Map<CIndex, CQLSSTableWriter> indexWriters = this.SSTableWriters.get(tableName).right;
            if (indexWriters != null) {
                for (CQLSSTableWriter indexWriter : indexWriters.values()) {
                    indexWriter.close();
                }
                // Clear out empty SSTable directories that haven't been written to
                CDefinition def = definitions.get(tableName);
                List<CIndex> indexes = def.getIndexesAsList();
                for (CIndex index : indexes) {
                    this.clearSSTableDirectoryIfEmpty(CObjectCQLGenerator.makeTableName(def, index));
                }
            }
        }
    }

    /**
     * Removes an SSTable directory for a given table path if the directory is empty
     * @param tablePath Path to remove if empty
     */
    private void clearSSTableDirectoryIfEmpty(String tablePath) {
        String SSTablePath = this.defaultSSTableOutputPath + "/" + keyspaceDefinition.getName() + "/" + tablePath;
        File SSTableDirectory = new File(SSTablePath);

        if (SSTableDirectory.isDirectory() && SSTableDirectory.list().length == 0) {
            FileUtils.deleteRecursive(SSTableDirectory);
        }
    }

	public void setLogCql(boolean logCql) {
		this.logCql = logCql;
		this.cqlExecutor.setLogCql(logCql);
	}

	public boolean getExecuteAsync() {
		return executeAsync;
	}

	public void setExecuteAsync(boolean executeAsync) {
		logger.debug("{} setting executeAsync to {}", this.keyspaceDefinition.getName(), executeAsync);
		this.executeAsync = executeAsync;
	}

	public void teardown() {
		session.close();
	}

	public boolean isCacheBoundedQueries() {
		return cacheBoundedQueries;
	}

	public void setCacheBoundedQueries(boolean cacheBoundedQueries) {
		this.cacheBoundedQueries = cacheBoundedQueries;
	}

    public void setCompaction(String strategy, Map<String,Object> options) throws CQLGenerationException, RhombusException {
        CQLStatementIterator cql = cqlGenerator.makeCQLforCompaction(keyspaceDefinition, strategy, options);
        executeStatements(cql);
    }

	public CQLExecutor getCqlExecutor(){
		return cqlExecutor;
	}

	public CObjectCQLGenerator getCqlGenerator_ONLY_FOR_TESTING(){
		return cqlGenerator;
	}

	public CKeyspaceDefinition getKeyspaceDefinition_ONLY_FOR_TESTING(){
		return keyspaceDefinition;
	}

	protected CKeyspaceDefinition getKeyspaceDefinition() {
		return keyspaceDefinition;
	}

	public CDefinition getDefinition(String objectType) {
		return keyspaceDefinition.getDefinitions().get(objectType);
	}

	public CKeyspaceDefinition hydrateRhombusKeyspaceDefinition(String keyspaceName) {
		try{
			CQLStatement cql = cqlGenerator.makeCQLforGetKeyspaceDefinitions(keyspaceName);
			com.datastax.driver.core.ResultSet resultSet = cqlExecutor.executeSync(cql);
			for(Row row : resultSet) {
				CKeyspaceDefinition definition =  CKeyspaceDefinition.fromJsonString(row.getString("def"));
				return definition;
			}
		} catch(Exception e){
			logger.warn("Unable to hydrate keyspace {} definition from cassandra", keyspaceName, e);
		}
		return null;
	}

	/**
	 * @return Number of statements that will be executed in a query without throwing an exception
	 */
	public int getReasonableStatementLimit() {
		return ObjectMapper.reasonableStatementLimit;
	}
}
