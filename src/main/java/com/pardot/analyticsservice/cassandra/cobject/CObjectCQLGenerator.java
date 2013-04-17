package com.pardot.analyticsservice.cassandra.cobject;

import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.pardot.analyticsservice.cassandra.Criteria;
import com.pardot.analyticsservice.cassandra.cobject.shardingstrategy.ShardStrategyException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pardot, An ExactTarget Company
 * User: robrighter
 * Date: 4/8/13
 */
public class CObjectCQLGenerator {

	protected static final String TEMPLATE_CREATE_STATIC = "CREATE TABLE %s (id timeuuid PRIMARY KEY, %s);";
	protected static final String TEMPLATE_CREATE_WIDE = "CREATE TABLE %s (id timeuuid, shardid bigint, %s, PRIMARY KEY ((shardid, %s),id) );";
	protected static final String TEMPLATE_INSERT_STATIC = "INSERT INTO %s (id, %s) VALUES (%s, %s) USING TIMESTAMP %s%s;";
	protected static final String TEMPLATE_INSERT_WIDE = "INSERT INTO %s (id, shardid, %s) VALUES (%s, %s, %s) USING TIMESTAMP %s%s;";
	protected static final String TEMPLATE_SELECT_STATIC = "SELECT * FROM %s WHERE %s;";
	protected static final String TEMPLATE_SELECT_WIDE = "SELECT * FROM %s WHERE shardid = %s AND %s ORDER BY id %s %s ALLOW FILTERING;";

	protected Map<String, CDefinition> definitions;

	/**
	 * No Param constructor, mostly for testing convenience. Use the other constructor.
	 */
	public CObjectCQLGenerator(){
		this.definitions = Maps.newHashMap();
	}


	/**
	 *
	 * @param objectDefinitions - A map where the key is the CDefinition.name and the value is the CDefinition.
	 *                          This map should include a CDefinition for every object in the system.
	 */
	public CObjectCQLGenerator(Map<String, CDefinition> objectDefinitions){
		this.definitions = objectDefinitions;
	}

	/**
	 * Set the Definitions to be used
	 * @param objectDefinitions - A map where the key is the CDefinition.name and the value is the CDefinition.
	 *                          This map should include a CDefinition for every object in the system.
	 */
	public void setDefinitions(Map<String, CDefinition> objectDefinitions){
		this.definitions = objectDefinitions;
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @return Iterator of CQL statements that need to be executed for this task.
	 */
	public CQLStatementIterator makeCQLforCreate(String objType){
		return makeCQLforCreate(this.definitions.get(objType));
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param data - A map of fieldnames to values representing the data to insert
	 * @return Iterator of CQL statements that need to be executed for this task.
	 * @throws CQLGenerationException
	 */
	@NotNull
	public CQLStatementIterator makeCQLforInsert(String objType, Map<String,String> data) throws CQLGenerationException {
		return makeCQLforInsert(this.definitions.get(objType), data);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param data - A map of fieldnames to values representing the data to insert
	 * @return Iterator of CQL statements that need to be executed for this task.
	 * @throws CQLGenerationException
	 */
	@NotNull
	public CQLStatementIterator makeCQLforInsert(String objType, Map<String,String> data, UUID key, long timestamp) throws CQLGenerationException {
		return makeCQLforInsert(this.definitions.get(objType), data, key, timestamp, 0);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param key - The TimeUUID of the object to retrieve
	 * @return Iterator of CQL statements that need to be executed for this task. (Should have a length of 1 for this particular method)
	 */
	@NotNull
	public CQLStatementIterator makeCQLforGet(String objType, UUID key){
		return makeCQLforGet(this.definitions.get(objType), key);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param criteria - The criteria object describing which rows to retrieve
	 * @return Iterator of CQL statements that need to be executed for this task.
	 */
	@NotNull
	public CQLStatementIterator makeCQLforGet(String objType, Criteria criteria) throws CQLGenerationException {
		CDefinition definition = this.definitions.get(objType);
		CObjectOrdering ordering = (criteria.getOrdering() != null ? criteria.getOrdering(): CObjectOrdering.DESCENDING);
		Long startTimestamp = criteria.getStartTimestamp();
		Long endTimestamp = criteria.getEndTimestamp();
		UUID startUUID = null;
		UUID endUUID = null;
		if(startTimestamp == null && endTimestamp == null) {
			startUUID = UUIDs.timeBased();
		}
		if(startTimestamp != null) {
			startUUID = UUIDs.startOf(startTimestamp);
		}
		if(endTimestamp != null) {
			endUUID = UUIDs.startOf(endTimestamp);
		}
		return makeCQLforGet(this.definitions.get(objType), criteria.getIndex(), criteria.getIndexKeys(),
			ordering, startUUID, endUUID, criteria.getLimit(), false);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param index - The name of the index on the object to query aka CIndex.name
	 * @param indexkeys - A map of fieldnames to values representing the where clause of the index query
	 * @param ordering - CObjectOrdering.ASCENDING or CObjectOrdering.DESCENDING
	 * @param start - UUID of the item before the first result
	 * @param end - UUID of the item after the first result (Assuming the limit doesnt override it)
	 * @param limit - The maximum number of results
	 * @return Iterator of CQL statements that need to be executed for this task.
	 * @throws CQLGenerationException
	 */
	@NotNull
	public CQLStatementIterator makeCQLforGet(String objType, String index, Map<String,String> indexkeys,CObjectOrdering ordering,@Nullable UUID start, @Nullable UUID end, long limit) throws CQLGenerationException {
		return makeCQLforGet(this.definitions.get(objType),index, indexkeys,ordering,start,end,limit, false);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param index - The name of the index on the object to query aka CIndex.name
	 * @param indexkeys - A map of fieldnames to values representing the where clause of the index query
	 * @param limit - The maximum number of results
	 * @return Iterator of CQL statements that need to be executed for this task.
	 * @throws CQLGenerationException
	 */
	public CQLStatementIterator makeCQLforGet(String objType, String index, Map<String,String> indexkeys, long limit) throws CQLGenerationException {
		return makeCQLforGet(this.definitions.get(objType),index, indexkeys,limit);
	}

	/**
	 *
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param index - The name of the index on the object to query aka CIndex.name
	 * @param indexkeys - A map of fieldnames to values representing the where clause of the index query
	 * @param ordering - CObjectOrdering.ASCENDING or CObjectOrdering.DESCENDING
	 * @param starttimestamp - Return results equal to or after this timestamp
	 * @param endtimestamp - Return results equal to or before this timestamp
	 * @param limit - The maximum number of results
	 * @return Iterator of CQL statements that need to be executed for this task.
	 * @throws CQLGenerationException
	 */
	public CQLStatementIterator makeCQLforGet(String objType, String index, Map<String,String> indexkeys, CObjectOrdering ordering, long starttimestamp, long endtimestamp, long limit) throws CQLGenerationException {
		return makeCQLforGet(this.definitions.get(objType),index, indexkeys,ordering, starttimestamp, endtimestamp, limit);
	}

	/**
	 * TODO: IMPLEMENT THIS
	 * @param objType - The name of the Object type aka CDefinition.name
	 * @param key - The TimeUUID of the object to delete
	 * @return Iterator of CQL statements that need to be executed for this task.
	 */
	@NotNull
	public CQLStatementIterator makeCQLforDelete(String objType, String key){
		return makeCQLforDelete(this.definitions.get(objType), key);
	}


	protected static CQLStatementIterator makeCQLforCreate(CDefinition def){
		List<String> ret = Lists.newArrayList();
		ret.add(makeStaticTableCreate(def));
		for(CIndex i : def.getIndexes().values()){
			ret.add(makeWideTableCreate(def, i));
		}
		return new BoundedCQLStatementIterator(ret);
	}


	protected static String makeInsertStatementStatic(String objectName, String fields, String values, UUID uuid, long timestamp, int ttl){
		return String.format(
				TEMPLATE_INSERT_STATIC,
				makeTableName(objectName,null),
				fields,
				uuid.toString(),
				values,
				timestamp+"",
				(ttl == 0) ? "" : (" AND TTL "+ ttl)
		);
	}

	protected static String makeInsertStatementWide(String objectName, String fields, String values, UUID uuid, long shardid, long timestamp, int ttl){
		return String.format(
				TEMPLATE_INSERT_WIDE,
				makeTableName(objectName,null),
				fields,
				uuid.toString(),
				shardid,
				values,
				timestamp+"",
				(ttl == 0) ? "" : (" AND TTL "+ ttl)
		);
	}

	protected static CQLStatementIterator makeCQLforInsert(@NotNull CDefinition def, @NotNull Map<String,String> data) throws CQLGenerationException{
		return makeCQLforInsert(def, data, null, 0, 0);
	}

	protected static CQLStatementIterator makeCQLforInsert(@NotNull CDefinition def, @NotNull Map<String,String> data, @Nullable UUID uuid, long timestamp, int ttl) throws CQLGenerationException{
		List<String> ret = Lists.newArrayList();
		if(uuid == null){
			uuid = UUIDs.timeBased();
		}
		if(timestamp == 0){
			timestamp = UUIDs.unixTimestamp(uuid);
		}
		if(!validateData(def, data)){
			throw new CQLGenerationException("Invalid Insert Requested. Missing Field(s)");
		}
		Map<String,ArrayList<String>> fieldsAndValues = makeFieldAndValueList(def,data);
		//Static Table
		ret.add(makeInsertStatementStatic(
				def.getName(),
				makeCommaList(fieldsAndValues.get("fields")),
				makeCommaList(fieldsAndValues.get("values")),
				uuid,
				timestamp,
				ttl
		));
		//Index Tables
		for(CIndex i : def.getIndexes().values()){
			if(i.passesAllFilters(data)){
				ret.add(makeInsertStatementWide(
						makeTableName(def.getName(),i.getName()),
						makeCommaList(fieldsAndValues.get("fields")),
						makeCommaList(fieldsAndValues.get("values")),
						uuid,
						i.getShardingStrategy().getShardKey(uuid),
						timestamp,
						ttl
				));
			}
		}
		return new BoundedCQLStatementIterator(ret);
	}

	protected static CQLStatementIterator makeCQLforGet(CDefinition def, UUID key){
		return new BoundedCQLStatementIterator(Lists.newArrayList(String.format(
			TEMPLATE_SELECT_STATIC,
			def.getName(),
			"id = "+key)));
	}

	@NotNull
	protected static CQLStatementIterator makeCQLforGet(CDefinition def, String index, Map<String,String> indexvalues, CObjectOrdering ordering,@Nullable UUID start, @Nullable UUID end, long limit, boolean inclusive) throws CQLGenerationException {
		CIndex i = def.getIndexes().get(index);
		if(i == null){
			throw new CQLGenerationException(String.format("Could not find specified index %s on CDefinition %s",index,def.getName()));
		}
		if(!i.validateIndexKeys(indexvalues)){
			throw new CQLGenerationException(String.format("Cannot query index %s on CDefinition %s with the provided list of index values",index,def.getName()));
		}
		String whereCQL = makeAndedEqualList(def,indexvalues);
		if(start != null){
			whereCQL += " AND id >"+(inclusive ? "= ":" ")+ start;
		}
		if(end != null){
			whereCQL += " AND id <"+(inclusive ? "= ":" ") + end;
		}
		String limitCQL = (limit > 0)? "LIMIT %d" : "";

		Range<Long> shardIdRange;

		long starttime = (long)((start == null) ? 0 : UUIDs.unixTimestamp(start));
		long endtime = (long) ((end == null) ? 0 : UUIDs.unixTimestamp(end) );
		try{
			shardIdRange = i.getShardingStrategy().getShardKeyRange(starttime,endtime);
		}
		catch(ShardStrategyException e){
			throw new CQLGenerationException(e.getMessage());
		}
		String CQLTemplate = String.format(
			TEMPLATE_SELECT_WIDE,
			makeTableName(def.getName(),i.getName()),
			"%d",
			whereCQL,
			ordering,
			limitCQL);
		return new UnboundableCQLStatementIterator(shardIdRange,limit,ordering,CQLTemplate);
	}

	protected static CQLStatementIterator makeCQLforGet(CDefinition def, String index, Map<String,String> indexvalues, long limit) throws CQLGenerationException {
		DateTime now = new DateTime(DateTimeZone.UTC);
		long unixtimestamp = (long)now.getMillis();
		return makeCQLforGet(def, index, indexvalues, CObjectOrdering.DESCENDING, null, UUIDs.endOf(unixtimestamp), limit, false);
	}

	protected static CQLStatementIterator makeCQLforGet(CDefinition def, String index, Map<String,String> indexvalues, CObjectOrdering ordering,long starttimestamp, long endtimestamp, long limit) throws CQLGenerationException {
		return makeCQLforGet(def,index,indexvalues,ordering,UUIDs.startOf(starttimestamp),UUIDs.endOf(endtimestamp),limit, true);
	}

	protected static CQLStatementIterator makeCQLforDelete(CDefinition def, String key){
		ArrayList<String> ret = Lists.newArrayList();

		return new BoundedCQLStatementIterator(ret);
	}

	protected static String makeStaticTableCreate(CDefinition def){
		return String.format(
				TEMPLATE_CREATE_STATIC,
				def.getName(),
				makeFieldList(def.getFields().values(), true));
	}

	protected static String makeWideTableCreate(CDefinition def, CIndex index){
		return String.format(
				TEMPLATE_CREATE_WIDE,
				def.getName()+"__"+index.getName(),
				makeFieldList(def.getFields().values(), true),
				makeCommaList(index.getCompositeKeyList()));
	}

	protected static Map<String,ArrayList<String>> makeFieldAndValueList(CDefinition def, Map<String,String> data){
		ArrayList<String> fieldList = new ArrayList<String>(def.getFields().size());
		ArrayList<String> valueList = new ArrayList<String>(def.getFields().size());
		for(CField f : def.getFields().values()){
			fieldList.add(f.getName());
			valueList.add(getCQLValueString(f,data.get(f.getName())));
		}
		Map<String,ArrayList<String>> ret = Maps.newHashMap();
		ret.put("fields", fieldList);
		ret.put("values", valueList);
		return ret;
	}

	protected static boolean validateData(CDefinition def, Map<String,String> data){
		Collection<String> fields = def.getRequiredFields();
		for( String f : fields){
			if(!data.containsKey(f)){
				return false;
			}
		}
		return true;
	}

	protected static String getCQLValueString(CField f, String value){
		String strTemplate = "'%s'";
		switch (f.getType()){
			case ASCII:
			case TEXT:
			case TIMESTAMP:
			case VARCHAR:
				return String.format(strTemplate, value);
			default:
				return value;
		}
	}

	protected static String makeAndedEqualList(CDefinition def, Map<String,String> data){
		String ret = "";
		int count = 0;
		for(String key : data.keySet()){
			CField f = def.getFields().get(key);
			ret+=f.getName() + " = " + getCQLValueString(f, data.get(key));
			if(++count < data.keySet().size()){
				ret += " AND ";
			}
		}
		return ret;
	}

	protected static String makeCommaList(List<String> strings){
		Iterator<String> it = strings.iterator();
		String ret = "";
		while(it.hasNext()){
			String s = it.next();
			ret = ret + s +(it.hasNext() ? ", " : "");
		}
		return ret;
	}

	protected static String makeFieldList(Collection<CField> fields, boolean withType){
		Iterator<CField> it = fields.iterator();
		String ret = "";
		while(it.hasNext()){
			CField f = it.next();
			ret = ret + f.getName() +
					(withType ? " " + f.getType() : "") +
					(it.hasNext() ? "," : "");
		}
		return ret;
	}

	protected static String makeTableName(String objName, @Nullable String indexName){
		if(indexName == null){
			return objName;
		}
		else{
			return objName+"__"+indexName;
		}
	}

}