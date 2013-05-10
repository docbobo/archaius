package com.netflix.config.sources;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodb.AmazonDynamoDB;
import com.amazonaws.services.dynamodb.model.AttributeValue;
import com.amazonaws.services.dynamodb.model.Key;
import com.amazonaws.services.dynamodb.model.ScanRequest;
import com.amazonaws.services.dynamodb.model.ScanResult;
import com.netflix.config.AbstractPollingScheduler;
import com.netflix.config.DeploymentContext;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import com.netflix.config.PropertyWithDeploymentContext;
import com.netflix.config.sources.AbstractDynamoDbConfigurationSource;

/**
 * User: gorzell
 * Date: 1/17/13
 * Time: 10:18 AM
 * This leverages some of the semantics of the PollingSource in order to have one place where the full table scan from
 * Dynamo is cached.  It is mean to be consumed but a number of DeploymentContext aware sources to keep them from all
 * having to load the table separately.
 */
public class DynamoDbDeploymentContextTableCache extends AbstractDynamoDbConfigurationSource<PropertyWithDeploymentContext> {
    private static Logger log = LoggerFactory.getLogger(AbstractPollingScheduler.class);

    //Property names
    static final String contextKeyAttributePropertyName = "com.netflix.config.dynamo.contextAttributeName";

    //Property defaults
    static final String defaultContextKeyAttribute = "context";

    //Dynamic Properties
    private final DynamicStringProperty contextAttributeName = DynamicPropertyFactory.getInstance()
            .getStringProperty(contextKeyAttributePropertyName, defaultContextKeyAttribute);

    // Delay defaults
    static final int defaultInitialDelayMillis = 30000;
    static final int defaultDelayMillis = 60000;

    private final int initialDelayMillis;
    private final int delayMillis;

    private ScheduledExecutorService executor;
    private volatile Map<String, PropertyWithDeploymentContext> cachedTable = new HashMap<String, PropertyWithDeploymentContext>();


    public DynamoDbDeploymentContextTableCache() {
        this(defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(int initialDelayMillis, int delayMillis) {
        super();
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param clientConfiguration
     */
    public DynamoDbDeploymentContextTableCache(ClientConfiguration clientConfiguration) {
        this(clientConfiguration, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param clientConfiguration
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(ClientConfiguration clientConfiguration, int initialDelayMillis, int delayMillis) {
        super(clientConfiguration);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param credentials
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentials credentials) {
        this(credentials, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param credentials
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentials credentials, int initialDelayMillis, int delayMillis) {
        super(credentials);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param credentials
     * @param clientConfiguration
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentials credentials, ClientConfiguration clientConfiguration) {
        this(credentials, clientConfiguration, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param credentials
     * @param clientConfiguration
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentials credentials, ClientConfiguration clientConfiguration, int initialDelayMillis, int delayMillis) {
        super(credentials, clientConfiguration);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param credentialsProvider
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentialsProvider credentialsProvider) {
        this(credentialsProvider, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param credentialsProvider
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentialsProvider credentialsProvider, int initialDelayMillis, int delayMillis) {
        super(credentialsProvider);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param credentialsProvider
     * @param clientConfiguration
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentialsProvider credentialsProvider, ClientConfiguration clientConfiguration) {
        this(credentialsProvider, clientConfiguration, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param credentialsProvider
     * @param clientConfiguration
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(AWSCredentialsProvider credentialsProvider, ClientConfiguration clientConfiguration, int initialDelayMillis, int delayMillis) {
        super(credentialsProvider, clientConfiguration);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    /**
     * @param dbClient
     */
    public DynamoDbDeploymentContextTableCache(AmazonDynamoDB dbClient) {
        this(dbClient, defaultInitialDelayMillis, defaultDelayMillis);
    }

    /**
     * @param dbClient
     * @param initialDelayMillis
     * @param delayMillis
     */
    public DynamoDbDeploymentContextTableCache(AmazonDynamoDB dbClient, int initialDelayMillis, int delayMillis) {
        super(dbClient);
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        start();
    }

    private synchronized void schedule(Runnable runnable) {
        executor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pollingConfigurationSource");
                t.setDaemon(true);
                return t;
            }
        });
        executor.scheduleWithFixedDelay(runnable, initialDelayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop polling the source table
     */
    public void stop() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private void start() {
        cachedTable = loadPropertiesFromTable(tableName.get());
        schedule(getPollingRunnable());
    }

    private Runnable getPollingRunnable() {
        return new Runnable() {
            public void run() {
                log.debug("Polling started");
                try {
                    Map<String, PropertyWithDeploymentContext> newMap = loadPropertiesFromTable(tableName.get());
                    cachedTable = newMap;
                } catch (Throwable e) {
                    log.error("Error getting result from polling source", e);
                    return;
                }
            }
        };
    }

    /**
     * Scan the table in dynamo and create a map with the results.  In this case the map has a complex type as the value,
     * so that Deployment Context is taken into account.
     *
     * @param table
     * @return
     */
    @Override
    protected Map<String, PropertyWithDeploymentContext> loadPropertiesFromTable(String table) {
        Map<String, PropertyWithDeploymentContext> propertyMap = new HashMap<String, PropertyWithDeploymentContext>();
        Key lastKeyEvaluated = null;
        do {
            ScanRequest scanRequest = new ScanRequest()
                    .withTableName(table)
                    .withExclusiveStartKey(lastKeyEvaluated);
            ScanResult result = dbClient.scan(scanRequest);
            for (Map<String, AttributeValue> item : result.getItems()) {
                String keyVal = item.get(keyAttributeName.get()).getS();
                
                DeploymentContext.ContextKey contextKey;
                String contextVal;

                String compoundContextKeyValue = item.get(contextAttributeName.get()).getS();
                if (compoundContextKeyValue.equals("global"))
                {
                	contextKey = null;
                	contextVal = null;
                }
                else
                {
                	String[] splitUpKey = compoundContextKeyValue.split("=");
                	if (splitUpKey.length != 2)
                	{
                		log.warn("Invalid context format: " + compoundContextKeyValue);
                		continue;
                	}
                	contextKey = DeploymentContext.ContextKey.valueOf(splitUpKey[0]);
                	contextVal = splitUpKey[1];
                }
                
                String key = keyVal + ";" + contextKey + ";" + contextVal;
                propertyMap.put(key,
                        new PropertyWithDeploymentContext(
                                contextKey,
                                contextVal,
                                keyVal,
                                item.get(valueAttributeName.get()).getS()
                        ));
            }
            lastKeyEvaluated = result.getLastEvaluatedKey();
        } while (lastKeyEvaluated != null);
        return propertyMap;
    }

    /**
     * Get the current values in the cache.
     *
     * @return
     */
    public Collection<PropertyWithDeploymentContext> getProperties() {
        return cachedTable.values();
    }
}
