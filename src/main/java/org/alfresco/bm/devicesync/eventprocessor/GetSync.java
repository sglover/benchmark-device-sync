package org.alfresco.bm.devicesync.eventprocessor;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.alfresco.bm.devicesync.data.SyncData;
import org.alfresco.bm.devicesync.util.PublicApiFactory;
import org.alfresco.bm.event.AbstractEventProcessor;
import org.alfresco.bm.event.Event;
import org.alfresco.bm.event.EventResult;
import org.alfresco.service.synchronization.api.Change;
import org.alfresco.service.synchronization.api.ChangeType;
import org.alfresco.service.synchronization.api.GetChangesResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.springframework.social.alfresco.api.Alfresco;

import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBObject;

/**
 * Execution of desktop sync client.
 * 
 * Randomly does sync operations as local file changes, stop or start desktop sync.
 * 
 * @author sglover
 * @since 1.0
 */
public class GetSync extends AbstractEventProcessor
{
    /** Logger for the class */
    private static Log logger = LogFactory.getLog(GetSync.class);

    private final PublicApiFactory publicApiFactory;
    private final int timeBetweenSyncOps;
    private final int maxTries;
    private final int timeBetweenGetFiles;
    private final int timeBetweenGetSyncs;
    private final boolean getFilesEnabled;
    private final String eventNameEndSync;
    private final String eventNameGetFile;

    /**
     * Constructor
     * 
     * @param fileService_p
     *            (TestFileService) delivers test files to insert locally
     * @param maxFolderDepth_p
     *            (int >= 0) Maximum folder depth to handle during create/move/delete of files
     * @param maxNumberOfFileOperations_p
     *            (int > 0) Maximum number of file operations per event
     * @param waitTimeMillisBetweenEvents_p
     *            (int > 0) Wait time between events
     */
    public GetSync(PublicApiFactory publicApiFactory, int timeBetweenSyncOps, int maxTries, int timeBetweenGetFiles,
    		int timeBetweenGetSyncs, boolean getFilesEnabled, String eventNameEndSync, String eventNameGetFile)
    {
    	this.publicApiFactory = publicApiFactory;
    	this.timeBetweenSyncOps = timeBetweenSyncOps;
    	this.maxTries = maxTries;
    	this.timeBetweenGetFiles = timeBetweenGetFiles;
    	this.timeBetweenGetSyncs = timeBetweenGetSyncs;
    	this.getFilesEnabled = getFilesEnabled;
    	this.eventNameEndSync = eventNameEndSync;
    	this.eventNameGetFile = eventNameGetFile;

        if (logger.isDebugEnabled())
        {
            logger.debug("Created event processor 'get sync'.");
        }
    }

    private Alfresco getAlfresco(String username)
    {
    	Alfresco alfresco = publicApiFactory.getPublicApi(username);
    	return alfresco;
    }

    private void getSync(Alfresco alfresco, SyncData syncData, List<Event> nextEvents) 
    		throws JsonParseException, JsonMappingException, IOException
    {
    	String subscriberId = syncData.getSubscriptionId();
    	String subscriptionId = syncData.getSubscriptionId();
		String syncId = syncData.getSyncId();
		int counter = syncData.getNumRetries();

    	super.resumeTimer();
		GetChangesResponse response = alfresco.getSync("-default-", subscriberId, subscriptionId, syncId);
    	super.suspendTimer();

		logger.debug("response = " + response);

		String status = response.getStatus();
		switch(status)
		{
		case "not ready":
			if(counter < maxTries)
			{
				syncData.incrementRetries();
				try
                {
	                Thread.sleep(timeBetweenGetSyncs);
                }
				catch (InterruptedException e)
                {
					// ok
                }

				Event event = new Event(this.getName(), System.currentTimeMillis() + timeBetweenGetSyncs, syncData.toDBObject());
				nextEvents.add(event);
			}
			else
			{
				syncData.maxRetriesHit();
			}
			break;
		case "ready":
			syncData.gotResults(response.getChanges());

            String username = syncData.getUsername();
	    	int numSyncChanges = syncData.getNumSyncChanges();

	    	if(getFilesEnabled && numSyncChanges > 0)
	    	{
	    		for(Change change: syncData.getChanges())
	    		{
	    			ChangeType changeType = change.getType();
	    			if(changeType.equals(ChangeType.CREATE_REPOS) || changeType.equals(ChangeType.UPDATE_REPOS))
	    			{
	    				String path = change.getPath();
	    				DBObject getFileParameter = BasicDBObjectBuilder
		    				.start("path", path)
		    				.add("username", username)
		    				.get();
	    				long scheduledTime = System.currentTimeMillis() + timeBetweenGetFiles;
	    	            Event nextEvent = new Event(eventNameGetFile, scheduledTime, getFileParameter);
	    	        	nextEvents.add(nextEvent);
	    			}
	    		}
	    	}

			long scheduledTime = System.currentTimeMillis() + timeBetweenSyncOps;
            Event nextEvent = new Event(eventNameEndSync, scheduledTime, syncData.toDBObject());
        	nextEvents.add(nextEvent);

			break;
		default:
		}
    }

    @Override
    protected EventResult processEvent(Event event) throws Exception
    {
    	super.suspendTimer();

    	DBObject dbObject = (DBObject)event.getData();
    	SyncData syncData = SyncData.fromDBObject(dbObject);

        try
        {
            String username = syncData.getUsername();
    	    Alfresco alfresco = getAlfresco(username);

            List<Event> nextEvents = new LinkedList<Event>();

			getSync(alfresco, syncData, nextEvents);

            return new EventResult(syncData.toDBObject(), nextEvents);
        }
        catch (Exception e)
        {
            logger.error("Exception occurred during event processing", e);
            throw e;
        }
    }
}