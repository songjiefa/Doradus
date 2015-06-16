package com.dell.doradus.logservice.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.FieldDefinition;
import com.dell.doradus.common.FieldType;
import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.logservice.ChunkInfo;
import com.dell.doradus.logservice.ChunkReader;
import com.dell.doradus.logservice.LogAggregate;
import com.dell.doradus.logservice.LogEntry;
import com.dell.doradus.logservice.LogQuery;
import com.dell.doradus.logservice.LogService;
import com.dell.doradus.logservice.search.filter.FilterBuilder;
import com.dell.doradus.logservice.search.filter.IFilter;
import com.dell.doradus.olap.aggregate.AggregationResult;
import com.dell.doradus.olap.aggregate.MetricValueCount;
import com.dell.doradus.olap.aggregate.MetricValueSet;
import com.dell.doradus.olap.collections.strings.BstrSet;
import com.dell.doradus.olap.io.BSTR;
import com.dell.doradus.olap.store.IntList;
import com.dell.doradus.search.SearchResultList;
import com.dell.doradus.search.parser.DoradusQueryBuilder;
import com.dell.doradus.search.query.Query;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.Tenant;

public class Searcher {
    
    public static SearchResultList search(LogService ls, Tenant tenant, String application, String table, LogQuery logQuery) {
        SearchRequest request = new SearchRequest(tenant, application, table, logQuery);
        SearchCollector collector = new SearchCollector(request.getCount());
        LogEntry current = null;
        List<String> partitions = ls.getPartitions(tenant, application, table, request.getMinTimestamp(), request.getMaxTimestamp());
        //optimization: inverse partitions
        if(request.getSkipCount() && request.getSortDescending()) {
            Collections.reverse(partitions);
        }
        IFilter filter = FilterBuilder.build(request.getQuery());
        ChunkReader chunkReader = new ChunkReader();
        int documentsCount = 0;
        for(String partition: partitions) {
            Iterable<ChunkInfo> chunks = ls.getChunks(tenant, application, table, partition);
            chunks = new SortedChunkIterable(chunks, request.getSortDescending());
            for(ChunkInfo chunkInfo: chunks) {
                if(chunkInfo.getMaxTimestamp() < request.getMinTimestamp()) continue;
                if(chunkInfo.getMinTimestamp() >= request.getMaxTimestamp()) continue;
                if(request.getSkipCount() && collector.size() >= request.getCount()) {
                    if(request.getSortDescending()) {
                        if(chunkInfo.getMaxTimestamp() <= collector.getMinTimestamp()) continue;
                    } else {
                        if(chunkInfo.getMinTimestamp() >= collector.getMaxTimestamp()) continue;
                    }
                }
                ls.readChunk(tenant, application, table, chunkInfo, chunkReader);
                if(request.getSortDescending()) {
                    for(int i = chunkReader.size() - 1; i >= 0; i--) {
                        long timestamp = chunkReader.getTimestamp(i);
                        if(timestamp < request.getMinTimestamp()) continue;
                        if(timestamp >= request.getMaxTimestamp()) continue;
                        if(!filter.check(chunkReader, i)) continue;
                        //optimization: avoid instantiating LogEntry if it won't go to the results
                        if(collector.size() < request.getCount() || timestamp > collector.getMinTimestamp()) {
                            if(current == null) current = new LogEntry(request.getFields(), request.getSortDescending());
                            current.set(chunkReader, i);
                            current = collector.add(current);
                        }
                        documentsCount++;
                    }
                } else {
                    for(int i = 0; i < chunkReader.size(); i++) {
                        long timestamp = chunkReader.getTimestamp(i);
                        if(timestamp < request.getMinTimestamp()) continue;
                        if(timestamp >= request.getMaxTimestamp()) continue;
                        if(!filter.check(chunkReader, i)) continue; 
                        //optimization: avoid instantiating LogEntry if it won't go to the results
                        if(collector.size() < request.getCount() || timestamp < collector.getMaxTimestamp()) {
                            if(current == null) current = new LogEntry(request.getFields(), request.getSortDescending());
                            current.set(chunkReader, i);
                            current = collector.add(current);
                        }
                        documentsCount++;
                    }
                }
            }
        }
        
        SearchResultList list = collector.getSearchResult(request.getFieldSet(), request.getSortOrders());
        if(!request.getSkipCount()) list.documentsCount = documentsCount;
        if(list.results.size() == request.getCount()) list.continuation_token = list.results.get(list.results.size() - 1).id();
        if(request.getSkip() > 0) {
            int size = list.results.size();
            if(request.getSkip() >= size) list.results.clear();
            else list.results = new ArrayList<>(list.results.subList(request.getSkip(), size));
        }
        return list;
    }
    
    public static AggregationResult aggregate(LogService ls, Tenant tenant, String application, String table, LogAggregate logAggregate) {
        TableDefinition tableDef = Searcher.getTableDef(tenant, application, table);
        Query query = DoradusQueryBuilder.Build(logAggregate.getQuery(), tableDef);
        String field = Aggregate.getAggregateField(tableDef, logAggregate.getFields());
        IFilter filter = FilterBuilder.build(query);
        
        if(field == null) {
            int count = 0;
            List<String> partitions = ls.getPartitions(tenant, application, table);
            ChunkReader chunkReader = new ChunkReader();
            for(String partition: partitions) {
                for(ChunkInfo chunkInfo: ls.getChunks(tenant, application, table, partition)) {
                    ls.readChunk(tenant, application, table, chunkInfo, chunkReader);
                    for(int i = 0; i < chunkReader.size(); i++) {
                        if(!filter.check(chunkReader, i)) continue; 
                        count++;
                    }
                }
            }
            
            AggregationResult result = new AggregationResult();
            result.documentsCount = count;
            result.summary = new AggregationResult.AggregationGroup();
            result.summary.id = null;
            result.summary.name = "*";
            result.summary.metricSet = new MetricValueSet(1);
            MetricValueCount c = new MetricValueCount();
            c.metric = count;
            result.summary.metricSet.values[0] = c; 
            return result;
        }
        else {
            IntList list = new IntList();
            BstrSet fields = new BstrSet();
            BSTR temp = new BSTR();
            int count = 0;
            List<String> partitions = ls.getPartitions(tenant, application, table);
            ChunkReader chunkReader = new ChunkReader();
            for(String partition: partitions) {
                for(ChunkInfo chunkInfo: ls.getChunks(tenant, application, table, partition)) {
                    ls.readChunk(tenant, application, table, chunkInfo, chunkReader);
                    int index = chunkReader.getFieldIndex(new BSTR(field));
                    if(index < 0) continue;
                    for(int i = 0; i < chunkReader.size(); i++) {
                        if(!filter.check(chunkReader, i)) continue;
                        chunkReader.getFieldValue(i, index, temp);
                        int pos = fields.add(temp);
                        if(pos == list.size()) list.add(1);
                        else list.set(pos, list.get(pos) + 1);
                        count++;
                    }
                }
            }
            
            AggregationResult result = new AggregationResult();
            result.documentsCount = count;
            result.summary = new AggregationResult.AggregationGroup();
            result.summary.id = null;
            result.summary.name = "*";
            result.summary.metricSet = new MetricValueSet(1);
            MetricValueCount c = new MetricValueCount();
            c.metric = count;
            result.summary.metricSet.values[0] = c;
            for(int i = 0; i < fields.size(); i++) {
                AggregationResult.AggregationGroup g = new AggregationResult.AggregationGroup();
                g.id = fields.get(i).toString();
                g.name = g.id.toString();
                g.metricSet = new MetricValueSet(1);
                MetricValueCount cc = new MetricValueCount();
                cc.metric = list.get(i);
                g.metricSet.values[0] = cc;
                result.groups.add(g);
            }
            result.groupsCount = result.groups.size();
            Collections.sort(result.groups);
            return result;
            
        }
    }
    
    
    
    public static TableDefinition getTableDef(Tenant tenant, String application, String table) {
        String store = application + "_" + table;
        ApplicationDefinition appDef = new ApplicationDefinition();
        appDef.setAppName(application);
        TableDefinition tableDef = new TableDefinition(appDef, table);
        appDef.addTable(tableDef);
        FieldDefinition fieldDef = new FieldDefinition(tableDef);
        fieldDef.setType(FieldType.TIMESTAMP);
        fieldDef.setName("Timestamp");
        tableDef.addFieldDefinition(fieldDef);
        Iterator<DColumn> it = DBService.instance().getAllColumns(tenant, store, "fields");
        if(it != null) {
            while(it.hasNext()) {
                String field = it.next().getName();
                fieldDef = new FieldDefinition(tableDef);
                fieldDef.setType(FieldType.TEXT);
                fieldDef.setName(field);
                tableDef.addFieldDefinition(fieldDef);
            }
        }
        return tableDef;
    }
    
}