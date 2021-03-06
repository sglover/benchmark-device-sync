package org.alfresco.bm.devicesync.dao.mongo;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.alfresco.bm.devicesync.dao.ExtendedResultsService;
import org.alfresco.bm.event.mongo.MongoResultService;

import com.codepoetics.protonpack.StreamUtils;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandFailureException;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;

public class MongoExtendedResultsService extends MongoResultService implements
        ExtendedResultsService
{
    private DBCollection collection;

    public MongoExtendedResultsService(DB db, String collection)
    {
        super(db, collection);
        try
        {
            this.collection = db.createCollection(collection,
                    new BasicDBObject());
        }
        catch (CommandFailureException e)
        {
            if (!db.collectionExists(collection))
            {
                // The collection is really not there
                throw e;
            }
            // Someone else created it
            this.collection = db.getCollection(collection);
        }
    }

    @Override
    @SuppressWarnings(
    { "rawtypes" })
    public Set<String> distinctSitesForEvent(String eventName, String key)
    {
        Set<String> distinctSites = new HashSet<>();

        QueryBuilder queryBuilder = QueryBuilder.start("event.name").is(
                eventName);
        DBObject query = queryBuilder.get();
        List l = collection.distinct(key, query);
        for (Object o : l)
        {
            distinctSites.add((String) o);
        }

        return distinctSites;
    }

    // public class ConsecutiveSpliterator<T> implements Spliterator<List<T>> {
    //
    // private final Spliterator<T> wrappedSpliterator;
    //
    // private final int n;
    //
    // private final Deque<T> deque;
    //
    // private final Consumer<T> dequeConsumer;
    //
    // public ConsecutiveSpliterator(Spliterator<T> wrappedSpliterator, int n) {
    // this.wrappedSpliterator = wrappedSpliterator;
    // this.n = n;
    // this.deque = new LinkedList<>();
    // this.dequeConsumer = new Consumer<T>() {
    // @Override
    // public void accept(T t) {
    // deque.addLast(t);
    // }
    // };
    // }
    //
    // @Override
    // public boolean tryAdvance(Consumer<? super List<T>> action) {
    // deque.pollFirst();
    // fillDeque();
    // if (deque.size() == n) {
    // List<T> list = new ArrayList<>(deque);
    // action.accept(list);
    // return true;
    // } else {
    // return false;
    // }
    // }
    //
    // private void fillDeque() {
    // while (deque.size() < n && wrappedSpliterator.tryAdvance(dequeConsumer))
    // ;
    // }
    //
    // @Override
    // public Spliterator<List<T>> trySplit() {
    // return null;
    // }
    //
    // @Override
    // public long estimateSize() {
    // return wrappedSpliterator.estimateSize();
    // }
    //
    // @Override
    // public int characteristics() {
    // return wrappedSpliterator.characteristics();
    // }
    // }

    private static class Time
    {
        private long time;

        public Time(long time)
        {
            super();
            this.time = time;
        }

        public long getTime()
        {
            return time;
        }
    }

    @Override
    public double avgSyncTime(int skip, int limit)
    {
        QueryBuilder queryBuilder = QueryBuilder.start("event.name").in(
                Arrays.asList("startSync", "endSync"));
        DBObject query = queryBuilder.get();
        DBObject orderBy = BasicDBObjectBuilder.start("data.syncId", 1).get();
        DBCursor cursor = collection.find(query).sort(orderBy).skip(skip)
                .limit(limit);

        long currentSyncId = -1;
        Map<String, Long> times = new HashMap<>();

        long total = 0;
        int num = 0;
        for (DBObject dbObject : cursor)
        {
            DBObject eventDBObject = (DBObject) dbObject.get("event");
            String eventName = (String) eventDBObject.get("name");
            DBObject dataDBObject = (DBObject) dbObject.get("data");
            Long syncId = (Long) dataDBObject.get("syncId");
            if (currentSyncId == -1)
            {
                currentSyncId = syncId;
            }

            if (currentSyncId != syncId)
            {
                if (times.size() == 2)
                {
                    long endSyncTimestamp = times.get("endSync");
                    long startSyncTimestamp = times.get("startSync");
                    long time = endSyncTimestamp - startSyncTimestamp;
                    total += time;
                    num++;
                }
            }
            else
            {
                currentSyncId = syncId;
                Date startTime = (Date) dbObject.get("startTime");
                long startTimeMillis = startTime.getTime();
                Long time = (Long) dbObject.get("time");
                long timestamp = startTimeMillis + time;
                times.put(eventName, timestamp);
            }
        }

        return total / num;
    }

    @Override
    public Stream<List<DBObject>> syncs(int skip, int limit)
    {
        QueryBuilder queryBuilder = QueryBuilder.start("event.name").in(
                Arrays.asList("startSync", "endSync"));
        DBObject query = queryBuilder.get();
        DBObject orderBy = BasicDBObjectBuilder.start("data.syncId", 1)
                .add("event.name", 1).get();
        DBCursor cursor = collection.find(query).sort(orderBy).skip(skip)
                .limit(limit);

        Stream<DBObject> mongoStream = StreamSupport.stream(
                cursor.spliterator(), false).onClose(() -> cursor.close()); // need
                                                                            // to
                                                                            // close
                                                                            // cursor;
        Stream<List<DBObject>> stream = StreamUtils.windowed(mongoStream, 2, 2);
        return stream;
    }
}
