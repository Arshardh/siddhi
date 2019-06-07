/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.core.table.record;

import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.Event;
import io.siddhi.core.event.state.MetaStateEvent;
import io.siddhi.core.event.state.MetaStateEventAttribute;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.state.StateEventFactory;
import io.siddhi.core.event.state.populater.StateEventPopulatorFactory;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ConstantExpressionExecutor;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.executor.condition.AndConditionExpressionExecutor;
import io.siddhi.core.executor.condition.compare.equal.EqualCompareConditionExpressionExecutor;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.processor.stream.window.QueryableProcessor;
import io.siddhi.core.query.selector.QuerySelector;
import io.siddhi.core.table.CacheTable;
import io.siddhi.core.table.CacheTableFIFO;
import io.siddhi.core.table.CacheTableLFU;
import io.siddhi.core.table.CacheTableLRU;
import io.siddhi.core.table.CompiledUpdateSet;
import io.siddhi.core.table.InMemoryTable;
import io.siddhi.core.table.Table;
import io.siddhi.core.util.SiddhiConstants;
import io.siddhi.core.util.cache.CacheExpiryHandlerRunnable;
import io.siddhi.core.util.collection.AddingStreamEventExtractor;
import io.siddhi.core.util.collection.executor.AndMultiPrimaryKeyCollectionExecutor;
import io.siddhi.core.util.collection.executor.CollectionExecutor;
import io.siddhi.core.util.collection.executor.CompareCollectionExecutor;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.CompiledSelection;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.collection.operator.OverwriteTableIndexOperator;
import io.siddhi.core.util.collection.operator.SnapshotableEventQueueOperator;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.parser.ExpressionParser;
import io.siddhi.core.util.parser.SelectorParser;
import io.siddhi.core.util.parser.helper.QueryParserHelper;
import io.siddhi.query.api.annotation.Annotation;
import io.siddhi.query.api.annotation.Element;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.TableDefinition;
import io.siddhi.query.api.execution.query.StoreQuery;
import io.siddhi.query.api.execution.query.input.store.InputStore;
import io.siddhi.query.api.execution.query.output.stream.OutputStream;
import io.siddhi.query.api.execution.query.output.stream.ReturnStream;
import io.siddhi.query.api.execution.query.output.stream.UpdateSet;
import io.siddhi.query.api.execution.query.selection.OrderByAttribute;
import io.siddhi.query.api.execution.query.selection.OutputAttribute;
import io.siddhi.query.api.execution.query.selection.Selector;
import io.siddhi.query.api.expression.Expression;
import io.siddhi.query.api.expression.Variable;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.siddhi.core.util.SiddhiConstants.ANNOTATION_CACHE;
import static io.siddhi.core.util.SiddhiConstants.ANNOTATION_CACHE_PURGE_INTERVAL;
import static io.siddhi.core.util.SiddhiConstants.ANNOTATION_CACHE_RETENTION_PERIOD;
import static io.siddhi.core.util.SiddhiConstants.ANNOTATION_CACHE_POLICY;
import static io.siddhi.core.util.SiddhiConstants.ANNOTATION_STORE;
import static io.siddhi.core.util.SiddhiConstants.CACHE_QUERY_NAME;
import static io.siddhi.core.util.SiddhiConstants.CACHE_TABLE_SIZE;
import static io.siddhi.core.util.StoreQueryRuntimeUtil.executeSelector;
import static io.siddhi.core.util.cache.CacheUtils.addRequiredFieldsToDataForCache;
import static io.siddhi.core.util.cache.CacheUtils.findEventChunkSize;
import static io.siddhi.core.util.parser.StoreQueryParser.buildExpectedOutputAttributes;
import static io.siddhi.core.util.parser.StoreQueryParser.generateMatchingMetaInfoHolderForCacheTable;
import static io.siddhi.query.api.util.AnnotationHelper.getAnnotation;

/**
 * An abstract implementation of table. Abstract implementation will handle {@link ComplexEventChunk} so that
 * developer can directly work with event data.
 */
public abstract class AbstractQueryableRecordTable extends AbstractRecordTable implements QueryableProcessor {
    private static final Logger log = Logger.getLogger(AbstractQueryableRecordTable.class);
    private int maxCacheSize;
    private long purgeInterval;
    private boolean cacheEnabled = false;
    private boolean cacheExpiryEnabled = false;
    private InMemoryTable cacheTable;
    private CompiledCondition compiledConditionForCaching;
    private CompiledSelection compiledSelectionForCaching;
    private Attribute[] outputAttributesForCaching;
    private TableDefinition cacheTableDefinition;
    protected SiddhiAppContext siddhiAppContext;
    private CacheExpiryHandlerRunnable cacheExpiryHandlerRunnable;
    private ScheduledExecutorService scheduledExecutorServiceForCacheExpiry;
    protected StateEvent findMatchingEvent;
    protected Selector selectorForTestStoreQuery;
    protected SiddhiQueryContext siddhiQueryContextForTestStoreQuery;
    protected MatchingMetaInfoHolder matchingMetaInfoHolderForTestStoreQuery;
    protected StateEvent containsMatchingEvent;
    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private long storeSizeLastCheckedTime;
    private String cachePolicy;

    @Override
    public void initCache(TableDefinition tableDefinition, SiddhiAppContext siddhiAppContext,
                     StreamEventCloner storeEventCloner, ConfigReader configReader) {
        this.siddhiAppContext = siddhiAppContext;
        String[] annotationNames = {ANNOTATION_STORE, ANNOTATION_CACHE};
        Annotation cacheTableAnnotation = getAnnotation(annotationNames, tableDefinition.getAnnotations());
        if (cacheTableAnnotation != null) {
            cacheEnabled = true;
            maxCacheSize = Integer.parseInt(cacheTableAnnotation.getElement(CACHE_TABLE_SIZE));
            cacheTableDefinition = TableDefinition.id(tableDefinition.getId());
            for (Attribute attribute: tableDefinition.getAttributeList()) {
                cacheTableDefinition.attribute(attribute.getName(), attribute.getType());
            }
            for (Annotation annotation: tableDefinition.getAnnotations()) {
                if (!annotation.getName().equalsIgnoreCase("Store")) {
                    cacheTableDefinition.annotation(annotation);
                }
            }

            cachePolicy = cacheTableAnnotation.getElement(ANNOTATION_CACHE_POLICY);

            if (cachePolicy == null || cachePolicy.equalsIgnoreCase("FIFO")) {
                cachePolicy = "FIFO";
                cacheTable = new CacheTableFIFO();
            } else if (cachePolicy.equalsIgnoreCase("LRU")) {
                cacheTable = new CacheTableLRU();
            } else if (cachePolicy.equalsIgnoreCase("LFU")) {
                cacheTable = new CacheTableLFU();
            }

            ((CacheTable) cacheTable).initCacheTable(cacheTableDefinition, configReader, siddhiAppContext,
                    recordTableHandler, cacheExpiryEnabled, maxCacheSize, cachePolicy);

            // if cache expiry enabled create expiry handler
            if (cacheTableAnnotation.getElement(ANNOTATION_CACHE_RETENTION_PERIOD) != null) {
                cacheExpiryEnabled = true;
                long retentionPeriod = Expression.Time.timeToLong(cacheTableAnnotation.
                        getElement(ANNOTATION_CACHE_RETENTION_PERIOD));
                if (cacheTableAnnotation.getElement(ANNOTATION_CACHE_PURGE_INTERVAL) == null) {
                    purgeInterval = retentionPeriod;
                } else {
                    purgeInterval = Expression.Time.timeToLong(cacheTableAnnotation.
                            getElement(ANNOTATION_CACHE_PURGE_INTERVAL));
                }
                scheduledExecutorServiceForCacheExpiry = siddhiAppContext.getScheduledExecutorService();
                cacheExpiryHandlerRunnable = new CacheExpiryHandlerRunnable(retentionPeriod, cacheTable,
                        tableMap, this, siddhiAppContext);
            }

            // creating objects needed to load cache
            SiddhiQueryContext siddhiQueryContext = new SiddhiQueryContext(siddhiAppContext,
                    CACHE_QUERY_NAME + tableDefinition.getId());
            MatchingMetaInfoHolder matchingMetaInfoHolder =
                    generateMatchingMetaInfoHolderForCacheTable(tableDefinition);
            StoreQuery storeQuery = StoreQuery.query().
                    from(
                            InputStore.store(tableDefinition.getId())).
                    select(
                            Selector.selector().
                                    limit(Expression.value((maxCacheSize + 1)))
                    );
            List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();

            compiledConditionForCaching = compileCondition(Expression.value(true), matchingMetaInfoHolder,
                    variableExpressionExecutors, tableMap, siddhiQueryContext);
            List<Attribute> expectedOutputAttributes = buildExpectedOutputAttributes(storeQuery,
                    tableMap, SiddhiConstants.UNKNOWN_STATE, matchingMetaInfoHolder, siddhiQueryContext);

            compiledSelectionForCaching = compileSelection(storeQuery.getSelector(), expectedOutputAttributes,
                    matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);

            outputAttributesForCaching = expectedOutputAttributes.toArray(new Attribute[0]);
            QueryParserHelper.reduceMetaComplexEvent(matchingMetaInfoHolder.getMetaStateEvent());
            QueryParserHelper.updateVariablePosition(matchingMetaInfoHolder.getMetaStateEvent(),
                    variableExpressionExecutors);
        }
    }

    @Override
    protected void connectAndLoadCache() throws ConnectionUnavailableException {
        connect();
        if (cacheEnabled) {
            ((CacheTable) cacheTable).deleteAll();
            StateEvent stateEventForCaching = new StateEvent(1, 0);
            StreamEvent preLoadedData = queryFromStore(stateEventForCaching, compiledConditionForCaching,
                    compiledSelectionForCaching, outputAttributesForCaching);

            if (preLoadedData != null) {
                ((CacheTable) cacheTable).addStreamEvent(preLoadedData);
            }
            if (cacheExpiryEnabled) {
                scheduledExecutorServiceForCacheExpiry.
                        scheduleAtFixedRate(cacheExpiryHandlerRunnable.checkAndExpireCache(), 0,
                        purgeInterval, TimeUnit.MILLISECONDS);
            }
        }
    }

    protected abstract void connect() throws ConnectionUnavailableException;

    @Override
    public StreamEvent query(StateEvent matchingEvent, CompiledCondition compiledCondition,
                             CompiledSelection compiledSelection) throws ConnectionUnavailableException {
        return query(matchingEvent, compiledCondition, compiledSelection, null);
    }

    @Override
    public void add(ComplexEventChunk<StreamEvent> addingEventChunk) throws ConnectionUnavailableException {
        if (cacheEnabled) {
            readWriteLock.writeLock().lock();
            try {
                ((CacheTable) cacheTable).addUptoMaxSize(addingEventChunk);
                super.add(addingEventChunk);
            } finally {
                readWriteLock.writeLock().unlock();
            }
        } else {
            super.add(addingEventChunk);
        }
    }

    @Override
    public void delete(ComplexEventChunk<StateEvent> deletingEventChunk, CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        CompiledConditionWithCache compiledConditionWithCache;
        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionWithCache.getStoreCompileCondition());

            readWriteLock.writeLock().lock();
            try {
                cacheTable.delete(deletingEventChunk,
                        compiledConditionWithCache.getCacheCompileCondition());
                super.delete(deletingEventChunk, recordStoreCompiledCondition);
            } finally {
                readWriteLock.writeLock().unlock();
            }
        } else {
            recordStoreCompiledCondition = ((RecordStoreCompiledCondition) compiledCondition);
            super.delete(deletingEventChunk, recordStoreCompiledCondition);
        }
    }

    @Override
    public void update(ComplexEventChunk<StateEvent> updatingEventChunk, CompiledCondition compiledCondition,
                       CompiledUpdateSet compiledUpdateSet) throws ConnectionUnavailableException {
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        RecordTableCompiledUpdateSet recordTableCompiledUpdateSet;
        CompiledConditionWithCache compiledConditionWithCache;
        CompiledUpdateSetWithCache compiledUpdateSetWithCache;
        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionWithCache.getStoreCompileCondition());
            compiledUpdateSetWithCache = (CompiledUpdateSetWithCache) compiledUpdateSet;
            recordTableCompiledUpdateSet = (RecordTableCompiledUpdateSet)
                    compiledUpdateSetWithCache.storeCompiledUpdateSet;
            readWriteLock.writeLock().lock();
            try {
                cacheTable.update(updatingEventChunk, compiledConditionWithCache.getCacheCompileCondition(),
                        compiledUpdateSetWithCache.getCacheCompiledUpdateSet());
                super.update(updatingEventChunk, recordStoreCompiledCondition, recordTableCompiledUpdateSet);
            } finally {
                readWriteLock.writeLock().unlock();
            }
        } else {
            recordStoreCompiledCondition = ((RecordStoreCompiledCondition) compiledCondition);
            recordTableCompiledUpdateSet = (RecordTableCompiledUpdateSet) compiledUpdateSet;
            super.update(updatingEventChunk, recordStoreCompiledCondition, recordTableCompiledUpdateSet);
        }
    }

    @Override
    public boolean contains(StateEvent matchingEvent, CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        containsMatchingEvent = matchingEvent;
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        CompiledConditionWithCache compiledConditionWithCache;
        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionWithCache.getStoreCompileCondition());

            readWriteLock.readLock().lock();
            try {
                if (cacheTable.contains(matchingEvent, compiledConditionWithCache.getCacheCompileCondition())) {
                    return true;
                }
                return super.contains(matchingEvent, recordStoreCompiledCondition);
            } finally {
                readWriteLock.readLock().unlock();
            }
        } else {
            return super.contains(matchingEvent, compiledCondition);
        }
    }

    @Override
    public void updateOrAdd(ComplexEventChunk<StateEvent> updateOrAddingEventChunk,
                            CompiledCondition compiledCondition, CompiledUpdateSet compiledUpdateSet,
                            AddingStreamEventExtractor addingStreamEventExtractor)
            throws ConnectionUnavailableException {
        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            CompiledConditionWithCache compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            RecordStoreCompiledCondition recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionWithCache.getStoreCompileCondition());
            CompiledUpdateSetWithCache compiledUpdateSetWithCache = (CompiledUpdateSetWithCache) compiledUpdateSet;
            RecordTableCompiledUpdateSet recordTableCompiledUpdateSet = (RecordTableCompiledUpdateSet)
                    compiledUpdateSetWithCache.storeCompiledUpdateSet;

            readWriteLock.writeLock().lock();
            try {
                ((CacheTable) cacheTable).updateOrAddWithMaxSize(updateOrAddingEventChunk,
                        compiledConditionWithCache.getCacheCompileCondition(),
                        compiledUpdateSetWithCache.getCacheCompiledUpdateSet(), addingStreamEventExtractor,
                        maxCacheSize);
                super.updateOrAdd(updateOrAddingEventChunk, recordStoreCompiledCondition, recordTableCompiledUpdateSet,
                        addingStreamEventExtractor);
            } finally {
                readWriteLock.writeLock().unlock();
            }
        } else {
            super.updateOrAdd(updateOrAddingEventChunk, compiledCondition, compiledUpdateSet,
                    addingStreamEventExtractor);
        }
    }

    @Override
    public StreamEvent find(CompiledCondition compiledCondition, StateEvent matchingEvent)
            throws ConnectionUnavailableException {

        // handle compile condition type conv
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        CompiledConditionWithCache compiledConditionWithCache = null;
        findMatchingEvent = matchingEvent;
        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionWithCache.getStoreCompileCondition());
        } else {
            recordStoreCompiledCondition =
                    ((RecordStoreCompiledCondition) compiledCondition);
        }

        StreamEvent cacheResults;
        // when table is smaller than max cache send results from cache
        if (cacheEnabled & storeTableSize <= maxCacheSize) {
            assert compiledConditionWithCache != null;
            readWriteLock.readLock().lock();
            try {
                cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                        matchingEvent);
                log.info(siddhiAppContext.getName() + ": sending results from cache"); //todo: make these debug logs
                return cacheResults;
            } finally {
                readWriteLock.readLock().unlock();
            }
        } else {
            if (cacheEnabled && checkCompileConditionForPKAndEquals(compiledCondition, cacheTableDefinition)) {
                readWriteLock.readLock().lock();
                try {
                    cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                            matchingEvent);
                    if (cacheResults != null) {
                        return cacheResults;
                    }
                } finally {
                    readWriteLock.readLock().unlock();
                }
                // cache miss
                ComplexEventChunk<StreamEvent> cacheMissEntry = new ComplexEventChunk<>(true);
                StreamEvent streamEvent = super.find(recordStoreCompiledCondition, matchingEvent);

                if (cacheTable.size() == maxCacheSize) {
                    ((CacheTable) cacheTable).deleteOneEntryUsingCachePolicy();
                }
                addRequiredFieldsToDataForCache(cacheMissEntry, streamEvent, siddhiAppContext, cachePolicy,
                        cacheExpiryEnabled);
                cacheTable.add(cacheMissEntry);
                readWriteLock.readLock().lock();
                try {
                    cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                            matchingEvent);
                    log.info(siddhiAppContext.getName() + ": sending results from cache");
                    return cacheResults;
                } finally {
                    readWriteLock.readLock().unlock();
                }
            }
        }

        // when cache is not enabled or cache query conditions are not satisfied
        return super.find(recordStoreCompiledCondition, matchingEvent);
    }

    @Override
    public CompiledUpdateSet compileUpdateSet(UpdateSet updateSet,
                                              MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        CompiledUpdateSet recordTableCompiledUpdateSet = super.compileUpdateSet(updateSet,
                matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);
        if (cacheEnabled) {
            CompiledUpdateSet cacheCompileUpdateSet =  cacheTable.compileUpdateSet(updateSet, matchingMetaInfoHolder,
                    variableExpressionExecutors, tableMap, siddhiQueryContext);
            return new CompiledUpdateSetWithCache(recordTableCompiledUpdateSet, cacheCompileUpdateSet);
        }
        return recordTableCompiledUpdateSet;
    }

    /**
     * Class to wrap store compile update set and cache compile update set
     */
    private class CompiledUpdateSetWithCache implements CompiledUpdateSet {
        CompiledUpdateSet storeCompiledUpdateSet;
        CompiledUpdateSet cacheCompiledUpdateSet;

        public CompiledUpdateSetWithCache(CompiledUpdateSet storeCompiledUpdateSet,
                                          CompiledUpdateSet cacheCompiledUpdateSet) {
            this.storeCompiledUpdateSet = storeCompiledUpdateSet;
            this.cacheCompiledUpdateSet = cacheCompiledUpdateSet;
        }

        public CompiledUpdateSet getStoreCompiledUpdateSet() {
            return storeCompiledUpdateSet;
        }

        public CompiledUpdateSet getCacheCompiledUpdateSet() {
            return cacheCompiledUpdateSet;
        }
    }

    @Override
    public CompiledCondition compileCondition(Expression condition, MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        ExpressionBuilder expressionBuilder = new ExpressionBuilder(condition, matchingMetaInfoHolder,
                variableExpressionExecutors, tableMap, siddhiQueryContext);
        CompiledCondition compileCondition = compileCondition(expressionBuilder);
        Map<String, ExpressionExecutor> expressionExecutorMap = expressionBuilder.getVariableExpressionExecutorMap();

        if (cacheEnabled) {
            CompiledCondition compiledConditionWithCache = new CompiledConditionWithCache(compileCondition,
                    ((CacheTable) cacheTable).generateCacheCompileCondition(condition, matchingMetaInfoHolder, siddhiQueryContext,
                            variableExpressionExecutors));
            return new RecordStoreCompiledCondition(expressionExecutorMap, compiledConditionWithCache);
        } else {
            return new RecordStoreCompiledCondition(expressionExecutorMap, compileCondition);
        }
    }

    private boolean checkCompileConditionForPKAndEquals(CompiledCondition compiledCondition,
                                                        TableDefinition tableDefinition) {
        List<String> primaryKeysArray = new ArrayList<>();
        Annotation primaryKeys = getAnnotation("PrimaryKey", tableDefinition.getAnnotations());
        if (primaryKeys == null) {
            return false;
        }
        List<Element> keys = primaryKeys.getElements();
        for (Element element: keys) {
            primaryKeysArray.add(element.getValue());
        }
        ExpressionExecutor expressionExecutor = null;
        int storePosition;
        RecordStoreCompiledCondition rscc = (RecordStoreCompiledCondition) compiledCondition;
        CompiledConditionWithCache ccwc = (CompiledConditionWithCache) rscc.compiledCondition;
        CompiledCondition cacheCC = ccwc.getCacheCompileCondition();
        try {
            SnapshotableEventQueueOperator operator = (SnapshotableEventQueueOperator) cacheCC;
            expressionExecutor = operator.getExpressionExecutor();
            storePosition = operator.getStoreEventPosition();
            recursivelyCheckExecutorsSEQO(primaryKeysArray, expressionExecutor, storePosition);
        } catch (ClassCastException ignore) {
            try {
                OverwriteTableIndexOperator operator = (OverwriteTableIndexOperator) cacheCC;
                CollectionExecutor ce = operator.getCollectionExecutor();
                checkExecutorsIO(primaryKeysArray, ce);
            } catch (ClassCastException e) {
                return false;
            }
        }
        if (primaryKeysArray.size() == 0) {
            return true;
        } else {
            return false;
        }
    }

    private void checkExecutorsIO(List<String> primaryKeysArray, CollectionExecutor collectionExecutor) {
        try {
            CompareCollectionExecutor comp = (CompareCollectionExecutor) collectionExecutor;
            primaryKeysArray.remove(comp.getAttribute());
        } catch (ClassCastException e1) {
            try {
                AndMultiPrimaryKeyCollectionExecutor mul = (AndMultiPrimaryKeyCollectionExecutor) collectionExecutor;
                String[] compositeKeys = mul.getCompositePrimaryKey().split(":-:");
                for (String key: compositeKeys) {
                    primaryKeysArray.remove(key);
                }
            } catch (ClassCastException e2) {

            }
        }
    }

    private void recursivelyCheckExecutorsSEQO(List<String> primaryKeysArray, ExpressionExecutor expressionExecutor,
                                               int storePosition) {
        try {
            VariableExpressionExecutor variableExpressionExecutor = (VariableExpressionExecutor) expressionExecutor;
            if (variableExpressionExecutor.getPosition()[0] == storePosition) {
                primaryKeysArray.remove(variableExpressionExecutor.getAttribute().getName());
            }
            return;
        } catch (ClassCastException e) {
            try {
                EqualCompareConditionExpressionExecutor eql =
                        (EqualCompareConditionExpressionExecutor) expressionExecutor;
                recursivelyCheckExecutorsSEQO(primaryKeysArray, eql.getLeftExpressionExecutor(), storePosition);
                recursivelyCheckExecutorsSEQO(primaryKeysArray, eql.getRightExpressionExecutor(), storePosition);
            } catch (ClassCastException e2) {
                try {
                    AndConditionExpressionExecutor and = (AndConditionExpressionExecutor) expressionExecutor;
                    recursivelyCheckExecutorsSEQO(primaryKeysArray, and.getLeftConditionExecutor(), storePosition);
                    recursivelyCheckExecutorsSEQO(primaryKeysArray, and.getRightConditionExecutor(), storePosition);
                } catch (ClassCastException e3) {
                    return;
                }
            }
        }
    }

    //todo: use threadlocal variable to decide query from store inside query
    public StreamEvent queryFromStore(StateEvent matchingEvent, CompiledCondition compiledCondition,
                             CompiledSelection compiledSelection, Attribute[] outputAttributes)
            throws ConnectionUnavailableException {
        findMatchingEvent = matchingEvent;

        ComplexEventChunk<StreamEvent> streamEventComplexEventChunk = new ComplexEventChunk<>(true);
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        RecordStoreCompiledSelection recordStoreCompiledSelection;
        CompiledConditionWithCache compiledConditionWithCache;
        CompiledSelectionWithCache compiledSelectionWithCache;

        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(
                    compiledConditionTemp.variableExpressionExecutorMap,
                    compiledConditionWithCache.getStoreCompileCondition());

            compiledSelectionWithCache = (CompiledSelectionWithCache) compiledSelection;
            recordStoreCompiledSelection = compiledSelectionWithCache.recordStoreCompiledSelection;
        } else {
            recordStoreCompiledSelection = ((RecordStoreCompiledSelection) compiledSelection);
            recordStoreCompiledCondition = ((RecordStoreCompiledCondition) compiledCondition);
        }

        Map<String, Object> parameterMap = new HashMap<>();
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledCondition.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledSelection.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }

        Iterator<Object[]> records;
        if (recordTableHandler != null) {
            records = recordTableHandler.query(matchingEvent.getTimestamp(), parameterMap,
                    recordStoreCompiledCondition.compiledCondition,
                    recordStoreCompiledSelection.compiledSelection, outputAttributes);
        } else {
            records = query(parameterMap, recordStoreCompiledCondition.compiledCondition,
                    recordStoreCompiledSelection.compiledSelection, outputAttributes);
        }
        addStreamEventToChunk(outputAttributes, streamEventComplexEventChunk, records);
        return streamEventComplexEventChunk.getFirst();
    }

    @Override
    public StreamEvent query(StateEvent matchingEvent, CompiledCondition compiledCondition,
                             CompiledSelection compiledSelection, Attribute[] outputAttributes)
            throws ConnectionUnavailableException {
        findMatchingEvent = matchingEvent;

        if (cacheEnabled) {
            // check if store size is old
            if (storeSizeLastCheckedTime < siddhiAppContext.getTimestampGenerator().currentTime() - 30000 ||
                    storeTableSize == -1) {
                StateEvent stateEventForCaching = new StateEvent(1, 0);
                StreamEvent preLoadedData = queryFromStore(stateEventForCaching, compiledConditionForCaching,
                        compiledSelectionForCaching, outputAttributesForCaching);
                storeTableSize = findEventChunkSize(preLoadedData);
            }
        }

        // handle condition type convs
        ComplexEventChunk<StreamEvent> streamEventComplexEventChunk = new ComplexEventChunk<>(true);
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        RecordStoreCompiledSelection recordStoreCompiledSelection;
        CompiledConditionWithCache compiledConditionWithCache = null;
        CompiledSelectionWithCache compiledSelectionWithCache = null;
        StreamEvent cacheResults = null;

        if (cacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(
                    compiledConditionTemp.variableExpressionExecutorMap,
                    compiledConditionWithCache.getStoreCompileCondition());

            compiledSelectionWithCache = (CompiledSelectionWithCache) compiledSelection;
            recordStoreCompiledSelection = compiledSelectionWithCache.recordStoreCompiledSelection;
        } else {
            recordStoreCompiledSelection = ((RecordStoreCompiledSelection) compiledSelection);
            recordStoreCompiledCondition = ((RecordStoreCompiledCondition) compiledCondition);
        }

        Map<String, Object> parameterMap = new HashMap<>();
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledCondition.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledSelection.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }

        Iterator<Object[]> records = null;

        if (cacheEnabled && storeTableSize <= maxCacheSize) { // when store is smaller than max cache size
            // return results from cache
            assert compiledConditionWithCache != null;
            readWriteLock.readLock().lock();
            try {
                cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                        matchingEvent);
                if (cacheResults != null) {
                    return getStreamEvent(outputAttributes, streamEventComplexEventChunk, compiledSelectionWithCache,
                            cacheResults);
                } else {
                    return null;
                }
            } finally {
                readWriteLock.readLock().unlock();
            }
        } else { // when store is bigger than max cache size
            if (cacheEnabled && checkCompileConditionForPKAndEquals(compiledCondition, cacheTableDefinition)) {
                // if query conrains all primary keys and has == only for them
                assert compiledConditionWithCache != null;
                readWriteLock.readLock().lock();
                try {
                    cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                            matchingEvent);
                    if (cacheResults != null) {
                        return getStreamEvent(outputAttributes, streamEventComplexEventChunk,
                                compiledSelectionWithCache, cacheResults);
                    }
                } finally {
                    readWriteLock.readLock().unlock();
                }

                // read all fields of missed entry from store
                CompiledSelection csForSlectAll = generateCSForSelectAll();
                Iterator<Object[]> recordsFromSelectAll;
                if (recordTableHandler != null) {
                    recordsFromSelectAll = recordTableHandler.query(matchingEvent.getTimestamp(), parameterMap,
                            recordStoreCompiledCondition.compiledCondition, csForSlectAll, outputAttributes);
                } else {
                    recordsFromSelectAll = query(parameterMap, recordStoreCompiledCondition.compiledCondition,
                            csForSlectAll, outputAttributes);
                }
                if (recordsFromSelectAll == null) {
                    return null;
                }
                ComplexEventChunk<StreamEvent> cacheMissEntry = new ComplexEventChunk<>(true);
                Object[] recordSelectAll = recordsFromSelectAll.next();
                StreamEvent streamEvent = storeEventPool.newInstance();
                streamEvent.setOutputData(new Object[outputAttributes.length]);
                System.arraycopy(recordSelectAll, 0, streamEvent.getOutputData(), 0, recordSelectAll.length);

                if (cacheTable.size() == maxCacheSize) {
                    ((CacheTable) cacheTable).deleteOneEntryUsingCachePolicy();
                }
                addRequiredFieldsToDataForCache(cacheMissEntry, streamEvent, siddhiAppContext, cachePolicy,
                        cacheExpiryEnabled);
                cacheTable.add(cacheMissEntry);
                readWriteLock.readLock().lock();
                try {
                    cacheResults = cacheTable.find(compiledConditionWithCache.getCacheCompileCondition(),
                            matchingEvent);
                    if (cacheResults != null) {
                        return getStreamEvent(outputAttributes, streamEventComplexEventChunk,
                                compiledSelectionWithCache, cacheResults);
                    } else {
                        //throw error?
                    }
                } finally {
                    readWriteLock.readLock().unlock();
                }
            }
            // query conditions are not satisfied check from store/ cache not enabled
            if (recordTableHandler != null) {
                records = recordTableHandler.query(matchingEvent.getTimestamp(), parameterMap,
                        recordStoreCompiledCondition.compiledCondition,
                        recordStoreCompiledSelection.compiledSelection, outputAttributes);
            } else {
                records = query(parameterMap, recordStoreCompiledCondition.compiledCondition,
                        recordStoreCompiledSelection.compiledSelection, outputAttributes);
            }
        }
        addStreamEventToChunk(outputAttributes, streamEventComplexEventChunk, records);
        log.info(siddhiAppContext.getName() + ": sending results from store table");
        return streamEventComplexEventChunk.getFirst();
    }

    private CompiledSelection generateCSForSelectAll() {
        MetaStreamEvent metaStreamEventForSelectAll = new MetaStreamEvent();
        for (Attribute attribute: tableDefinition.getAttributeList()) {
            metaStreamEventForSelectAll.addOutputData(attribute);
        }
        MetaStateEvent metaStateEventForSelectAll = new MetaStateEvent(1);
        metaStateEventForSelectAll.addEvent(metaStreamEventForSelectAll);
        MatchingMetaInfoHolder matchingMetaInfoHolderForSlectAll = new
                MatchingMetaInfoHolder(metaStateEventForSelectAll, -1, 0, tableDefinition, tableDefinition, 0);

        List<OutputAttribute> outputAttributesAll = new ArrayList<>();
//                MetaStreamEvent metaStreamEvent = matchingMetaInfoHolderForSlectAll.getMetaStateEvent()
//                .getMetaStreamEvent(
//                        matchingMetaInfoHolderForSlectAll.getStoreEventIndex());
        List<Attribute> attributeList = tableDefinition.getAttributeList();
        for (Attribute attribute : attributeList) {
            outputAttributesAll.add(new OutputAttribute(new Variable(attribute.getName())));
        }

        List<SelectAttributeBuilder> selectAttributeBuilders = new ArrayList<>(outputAttributesAll.size());
        List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
        for (OutputAttribute outputAttribute : outputAttributesAll) {
            ExpressionBuilder expressionBuilder = new ExpressionBuilder(outputAttribute.getExpression(),
                    matchingMetaInfoHolderForSlectAll, variableExpressionExecutors, tableMap, null);
            selectAttributeBuilders.add(new SelectAttributeBuilder(expressionBuilder,
                    outputAttribute.getRename()));
        }
        return compileSelection(selectAttributeBuilders, null, null, null, null, null);
    }

    private void addStreamEventToChunk(Attribute[] outputAttributes, ComplexEventChunk<StreamEvent>
            streamEventComplexEventChunk, Iterator<Object[]> records) {
        if (records != null) {
            while (records.hasNext()) {
                Object[] record = records.next();
                StreamEvent streamEvent = storeEventPool.newInstance();
                streamEvent.setOutputData(new Object[outputAttributes.length]);
                System.arraycopy(record, 0, streamEvent.getOutputData(), 0, record.length);
                streamEventComplexEventChunk.add(streamEvent);
            }
        }
    }

    private StreamEvent getStreamEvent(Attribute[] outputAttributes,
                                       ComplexEventChunk<StreamEvent> streamEventComplexEventChunk,
                                       CompiledSelectionWithCache compiledSelectionWithCache,
                                       StreamEvent cacheResults) {
        StateEventFactory stateEventFactory = new StateEventFactory(compiledSelectionWithCache.
                metaStateEvent);
        Event[] cacheResultsAfterSelection = executeSelector(cacheResults,
                compiledSelectionWithCache.querySelector,
                stateEventFactory, MetaStreamEvent.EventType.TABLE);
        assert cacheResultsAfterSelection != null;
        for (Event event : cacheResultsAfterSelection) {
            Object[] record = event.getData();
            StreamEvent streamEvent = storeEventPool.newInstance();
            streamEvent.setOutputData(new Object[outputAttributes.length]);
            System.arraycopy(record, 0, streamEvent.getOutputData(), 0, record.length);
            streamEventComplexEventChunk.add(streamEvent);
        }
        log.info(siddhiAppContext.getName() + ": sending results from cache");
        return streamEventComplexEventChunk.getFirst();
    }

    /**
     * Query records matching the compiled condition and selection
     *
     * @param parameterMap      map of matching StreamVariable Ids and their values
     *                          corresponding to the compiled condition and selection
     * @param compiledCondition the compiledCondition against which records should be matched
     * @param compiledSelection the compiledSelection that maps records based to requested format
     * @return RecordIterator of matching records
     * @throws ConnectionUnavailableException
     */
    protected abstract RecordIterator<Object[]> query(Map<String, Object> parameterMap,
                                                      CompiledCondition compiledCondition,
                                                      CompiledSelection compiledSelection,
                                                      Attribute[] outputAttributes)
            throws ConnectionUnavailableException;

    public CompiledSelection compileSelection(Selector selector,
                                              List<Attribute> expectedOutputAttributes,
                                              MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        selectorForTestStoreQuery = selector;
        siddhiQueryContextForTestStoreQuery = siddhiQueryContext;
        matchingMetaInfoHolderForTestStoreQuery = matchingMetaInfoHolder;
        List<OutputAttribute> outputAttributes = selector.getSelectionList();
        if (outputAttributes.size() == 0) {
            MetaStreamEvent metaStreamEvent = matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(
                    matchingMetaInfoHolder.getStoreEventIndex());
            List<Attribute> attributeList = metaStreamEvent.getLastInputDefinition().getAttributeList();
            for (Attribute attribute : attributeList) {
                outputAttributes.add(new OutputAttribute(new Variable(attribute.getName())));
            }
        }
        List<SelectAttributeBuilder> selectAttributeBuilders = new ArrayList<>(outputAttributes.size());
        for (OutputAttribute outputAttribute : outputAttributes) {
            ExpressionBuilder expressionBuilder = new ExpressionBuilder(outputAttribute.getExpression(),
                    matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);
            selectAttributeBuilders.add(new SelectAttributeBuilder(expressionBuilder, outputAttribute.getRename()));
        }

        MatchingMetaInfoHolder metaInfoHolderAfterSelect = new MatchingMetaInfoHolder(
                matchingMetaInfoHolder.getMetaStateEvent(), matchingMetaInfoHolder.getMatchingStreamEventIndex(),
                matchingMetaInfoHolder.getStoreEventIndex(), matchingMetaInfoHolder.getMatchingStreamDefinition(),
                matchingMetaInfoHolder.getMatchingStreamDefinition(), matchingMetaInfoHolder.getCurrentState());

        List<ExpressionBuilder> groupByExpressionBuilders = null;
        if (selector.getGroupByList().size() != 0) {
            groupByExpressionBuilders = new ArrayList<>(outputAttributes.size());
            for (Variable variable : selector.getGroupByList()) {
                groupByExpressionBuilders.add(new ExpressionBuilder(variable, metaInfoHolderAfterSelect,
                        variableExpressionExecutors, tableMap, siddhiQueryContext));
            }
        }

        ExpressionBuilder havingExpressionBuilder = null;
        if (selector.getHavingExpression() != null) {
            havingExpressionBuilder = new ExpressionBuilder(selector.getHavingExpression(), metaInfoHolderAfterSelect,
                    variableExpressionExecutors, tableMap, siddhiQueryContext);
        }

        List<OrderByAttributeBuilder> orderByAttributeBuilders = null;
        if (selector.getOrderByList().size() != 0) {
            orderByAttributeBuilders = new ArrayList<>(selector.getOrderByList().size());
            for (OrderByAttribute orderByAttribute : selector.getOrderByList()) {
                ExpressionBuilder expressionBuilder = new ExpressionBuilder(orderByAttribute.getVariable(),
                        metaInfoHolderAfterSelect, variableExpressionExecutors,
                        tableMap, siddhiQueryContext);
                orderByAttributeBuilders.add(new OrderByAttributeBuilder(expressionBuilder,
                        orderByAttribute.getOrder()));
            }
        }

        Long limit = null;
        Long offset = null;
        if (selector.getLimit() != null) {
            ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression((Expression) selector.getLimit(),
                    metaInfoHolderAfterSelect.getMetaStateEvent(), SiddhiConstants.HAVING_STATE, tableMap,
                    variableExpressionExecutors, false, 0,
                    ProcessingMode.BATCH, false, siddhiQueryContext);
            limit = ((Number) (((ConstantExpressionExecutor) expressionExecutor).getValue())).longValue();
            if (limit < 0) {
                throw new SiddhiAppCreationException("'limit' cannot have negative value, but found '" + limit + "'",
                        selector, siddhiQueryContext.getSiddhiAppContext());
            }
        }
        if (selector.getOffset() != null) {
            ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression((Expression) selector.getOffset(),
                    metaInfoHolderAfterSelect.getMetaStateEvent(), SiddhiConstants.HAVING_STATE, tableMap,
                    variableExpressionExecutors, false, 0,
                    ProcessingMode.BATCH, false, siddhiQueryContext);
            offset = ((Number) (((ConstantExpressionExecutor) expressionExecutor).getValue())).longValue();
            if (offset < 0) {
                throw new SiddhiAppCreationException("'offset' cannot have negative value, but found '" + offset + "'",
                        selector, siddhiQueryContext.getSiddhiAppContext());
            }
        }
        CompiledSelection compiledSelection = compileSelection(selectAttributeBuilders, groupByExpressionBuilders,
                havingExpressionBuilder, orderByAttributeBuilders, limit, offset);

        Map<String, ExpressionExecutor> expressionExecutorMap = new HashMap<>();
        if (selectAttributeBuilders.size() != 0) {
            for (SelectAttributeBuilder selectAttributeBuilder : selectAttributeBuilders) {
                expressionExecutorMap.putAll(
                        selectAttributeBuilder.getExpressionBuilder().getVariableExpressionExecutorMap());
            }
        }
        if (groupByExpressionBuilders != null && groupByExpressionBuilders.size() != 0) {
            for (ExpressionBuilder groupByExpressionBuilder : groupByExpressionBuilders) {
                expressionExecutorMap.putAll(groupByExpressionBuilder.getVariableExpressionExecutorMap());
            }
        }
        if (havingExpressionBuilder != null) {
            expressionExecutorMap.putAll(havingExpressionBuilder.getVariableExpressionExecutorMap());
        }
        if (orderByAttributeBuilders != null && orderByAttributeBuilders.size() != 0) {
            for (OrderByAttributeBuilder orderByAttributeBuilder : orderByAttributeBuilders) {
                expressionExecutorMap.putAll(
                        orderByAttributeBuilder.getExpressionBuilder().getVariableExpressionExecutorMap());
            }
        }

        if (cacheEnabled) {
            CompiledSelectionWithCache compiledSelectionWithCache;
            MetaStateEvent metaStateEventForCacheSelection = new MetaStateEvent(matchingMetaInfoHolder.
                    getMetaStateEvent().getStreamEventCount());
            for (MetaStreamEvent metaStreamEvent: matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvents()) {
                metaStateEventForCacheSelection.addEvent(metaStreamEvent);
            }

            ReturnStream returnStream = new ReturnStream(OutputStream.OutputEventType.CURRENT_EVENTS);
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            List<VariableExpressionExecutor> variableExpressionExecutorsForQuerySelector = new ArrayList<>();
            QuerySelector querySelector = SelectorParser.parse(selector,
                    returnStream,
                    metaStateEventForCacheSelection, tableMap, variableExpressionExecutorsForQuerySelector,
                    metaPosition, ProcessingMode.BATCH, false, siddhiQueryContext);
            if (matchingMetaInfoHolder.getMetaStateEvent().getOutputDataAttributes().size() == 0) {
                for (MetaStateEventAttribute outputDataAttribute : metaStateEventForCacheSelection.
                        getOutputDataAttributes()) {
                    matchingMetaInfoHolder.getMetaStateEvent().addOutputDataAllowingDuplicate(outputDataAttribute);
                }
            }
            QueryParserHelper.updateVariablePosition(metaStateEventForCacheSelection,
                    variableExpressionExecutorsForQuerySelector);
            querySelector.setEventPopulator(
                    StateEventPopulatorFactory.constructEventPopulator(metaStateEventForCacheSelection));

            RecordStoreCompiledSelection recordStoreCompiledSelection =
                    new RecordStoreCompiledSelection(expressionExecutorMap, compiledSelection);

            compiledSelectionWithCache = new CompiledSelectionWithCache(recordStoreCompiledSelection, querySelector,
                    metaStateEventForCacheSelection);
            return compiledSelectionWithCache;
        } else {
            return  new RecordStoreCompiledSelection(expressionExecutorMap, compiledSelection);
        }
    }

    public void setStoreSizeLastCheckedTime(long storeSizeLastCheckedTime) {
        this.storeSizeLastCheckedTime = storeSizeLastCheckedTime;
    }

    public void setStoreTableSize(int storeTableSize) {
        this.storeTableSize = storeTableSize;
    }

    private int storeTableSize = -1;

    public long getStoreSizeLastCheckedTime() {
        return storeSizeLastCheckedTime;
    }

    public int getStoreTableSize() {
        return storeTableSize;
    }

    public int getMaxCacheSize() {
        return maxCacheSize;
    }

    public CompiledCondition getCompiledConditionForCaching() {
        return compiledConditionForCaching;
    }

    public CompiledSelection getCompiledSelectionForCaching() {
        return compiledSelectionForCaching;
    }

    public Attribute[] getOutputAttributesForCaching() {
        return outputAttributesForCaching;
    }

    public String getCachePolicy() {
        return cachePolicy;
    }

    /**
     * Compile the query selection
     *
     * @param selectAttributeBuilders  helps visiting the select attributes in order
     * @param groupByExpressionBuilder helps visiting the group by attributes in order
     * @param havingExpressionBuilder  helps visiting the having condition
     * @param orderByAttributeBuilders helps visiting the order by attributes in order
     * @param limit                    defines the limit level
     * @param offset                   defines the offset level
     * @return compiled selection that can be used for retrieving events on a defined format
     */
    protected abstract CompiledSelection compileSelection(List<SelectAttributeBuilder> selectAttributeBuilders,
                                                          List<ExpressionBuilder> groupByExpressionBuilder,
                                                          ExpressionBuilder havingExpressionBuilder,
                                                          List<OrderByAttributeBuilder> orderByAttributeBuilders,
                                                          Long limit, Long offset);

    /**
     * Class to hold store compile condition and cache compile condition wrapped
     */
    private class CompiledConditionWithCache implements CompiledCondition {
        CompiledCondition cacheCompileCondition;
        CompiledCondition storeCompileCondition;

        public CompiledConditionWithCache(CompiledCondition storeCompileCondition,
                                          CompiledCondition cacheCompileCondition) {
            this.storeCompileCondition = storeCompileCondition;
            this.cacheCompileCondition = cacheCompileCondition;
        }

        public CompiledCondition getStoreCompileCondition() {
            return storeCompileCondition;
        }

        public CompiledCondition getCacheCompileCondition() {
            return cacheCompileCondition;
        }
    }

    /**
     * class to hold both store compile selection and cache compile selection wrapped
     */
    public class CompiledSelectionWithCache implements CompiledSelection {
        QuerySelector querySelector;
        MetaStateEvent metaStateEvent;
        RecordStoreCompiledSelection recordStoreCompiledSelection;

        public CompiledSelectionWithCache(RecordStoreCompiledSelection recordStoreCompiledSelection,
                                          QuerySelector querySelector,
                                          MetaStateEvent metaStateEvent) {
            this.recordStoreCompiledSelection = recordStoreCompiledSelection;
            this.querySelector = querySelector;
            this.metaStateEvent = metaStateEvent;
        }

        public QuerySelector getQuerySelector() {
            return querySelector;
        }

        public MetaStateEvent getMetaStateEvent() {
            return metaStateEvent;
        }
    }

    private class RecordStoreCompiledSelection implements CompiledSelection {


        private final Map<String, ExpressionExecutor> variableExpressionExecutorMap;
        private final CompiledSelection compiledSelection;

        RecordStoreCompiledSelection(Map<String, ExpressionExecutor> variableExpressionExecutorMap,
                                     CompiledSelection compiledSelection) {

            this.variableExpressionExecutorMap = variableExpressionExecutorMap;
            this.compiledSelection = compiledSelection;
        }
    }

    /**
     * Holder of Selection attribute with renaming field
     */
    public class SelectAttributeBuilder {
        private final ExpressionBuilder expressionBuilder;
        private final String rename;

        public SelectAttributeBuilder(ExpressionBuilder expressionBuilder, String rename) {
            this.expressionBuilder = expressionBuilder;
            this.rename = rename;
        }

        public ExpressionBuilder getExpressionBuilder() {
            return expressionBuilder;
        }

        public String getRename() {
            return rename;
        }
    }

    /**
     * Holder of order by attribute with order orientation
     */
    public class OrderByAttributeBuilder {
        private final ExpressionBuilder expressionBuilder;
        private final OrderByAttribute.Order order;

        public OrderByAttributeBuilder(ExpressionBuilder expressionBuilder, OrderByAttribute.Order order) {
            this.expressionBuilder = expressionBuilder;
            this.order = order;
        }

        public ExpressionBuilder getExpressionBuilder() {
            return expressionBuilder;
        }

        public OrderByAttribute.Order getOrder() {
            return order;
        }
    }


}
