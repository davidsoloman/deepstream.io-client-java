package io.deepstream;


import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.deepstream.constants.*;

import java.util.*;
import java.util.List;


class Record {

    private static final String ALL_EVENT = "ALL_EVENT";
    private static final String DESTROY_PENDING = "DESTROY_PENDING";

    public int usages;
    public int version;

    public boolean isReady;
    public boolean isDestroyed;

    private final UtilResubscribeNotifier utilResubscribeNotifier;
    private final UtilAckTimeoutRegistry ackTimeoutRegistry;
    private final IConnection connection;
    private final IDeepstreamClient client;
    private final Gson gson;
    private final UtilJSONPath path;
    private final UtilEmitter subscribers;

    private ArrayList<RecordEventsListener> recordEventsListeners;
    private ArrayList<RecordReadyListener> recordReadyListeners;
    private ArrayList<RecordReadyListener> onceRecordReadyListeners;
    private RecordMergeStrategy mergeStrategy;

    private JsonElement data;
    private String name;
    private Map options;
    private RecordRemoteUpdateListener recordRemoteUpdateListener;

    public Record(String name, Map recordOptions, IConnection connection, Map options, IDeepstreamClient client) {
        this.ackTimeoutRegistry = client.getAckTimeoutRegistry();
        this.name = name;
        this.options = options;
        this.usages = 0;
        this.version = -1;
        this.connection = connection;
        this.client = client;
        this.gson = new Gson();
        this.data = new JsonObject();
        this.path = new UtilJSONPath( this.data );
        this.subscribers = new UtilEmitter();
        this.isReady = false;
        this.isDestroyed = false;

        this.recordReadyListeners = new ArrayList<>();
        this.recordEventsListeners = new ArrayList<>();
        this.onceRecordReadyListeners = new ArrayList<>();

        this.scheduleAcks();
        this.sendRead();

        this.utilResubscribeNotifier = new UtilResubscribeNotifier(client, new UtilResubscribeCallback() {
            @Override
            public void resubscribe() {
                sendRead();
            }
        });
    }

    public Record addRecordReadyListener( RecordReadyListener recordReadyListener ) {
        this.recordReadyListeners.add( recordReadyListener );
        return this;
    }

    public Record removeRecordReadyListener(RecordReadyListener recordReadyListener) {
        this.recordEventsListeners.remove( recordReadyListener );
        return this;
    }

    public Record addRecordEventsListener(RecordEventsListener recordEventsListener) {
        this.recordEventsListeners.add( recordEventsListener );
        return this;
    }

    public Record removeRecordEventsListener(RecordEventsListener recordEventsListener) {
        this.recordEventsListeners.remove( recordEventsListener );
        return this;
    }

    public Record setMergeStrategy(MergeStrategy mergeStrategy) {
        this.mergeStrategy = RecordMergeStrategies.INSTANCE.getMergeStrategy( mergeStrategy );
        return this;
    }

    public Record setMergeStrategy(RecordMergeStrategy mergeStrategy) {
        this.mergeStrategy = mergeStrategy;
        return this;
    }

    /**
     * Gets the record as a JSON object
     *
     * @return
     */
    public <T> T get( Class<T> type ) {
        return deepCopy( this.data, type );
    }

    /**
     * Gets the record as a JSON object
     *
     * @return
     */
    public JsonElement get( String path ) {
        return deepCopy( this.path.get( path ) );
    }

    /**
     * Gets the record as a JSON object
     *
     * @return
     */
    public JsonElement get() {
        return deepCopy( this.data );
    }

    public Record set( Object value ) throws DeepstreamRecordDestroyedException {
        return this.set( null, value, false );
    }

    public Record set(String path, Object value ) throws DeepstreamRecordDestroyedException {
        return this.set( path, value, false );
    }

    private Record set(String path, Object value, boolean force ) throws DeepstreamRecordDestroyedException {
        throwExceptionIfDestroyed( "set" );

        JsonElement element = gson.toJsonTree( value );

        if( !this.isReady ) {
            System.out.println( "Not ready, should queue!" );
            return this;
        }

        JsonElement object = this.path.get( path );

        if( force == false ) {
            if( object != null && object.equals( value ) ) {
                return this;
            } else if( path == null && this.data.equals( value ) ) {
                return this;
            }
        }

        Map oldValues = beginChange();
        this.version++;
        this.path.set( path, element );
        this.data = this.path.getCoreElement();
        sendUpdate( path, value );
        completeChange( oldValues );

        return this;
    }

    public Record subscribe( String path, RecordChangedCallback recordChangedCallback ) throws DeepstreamRecordDestroyedException {
        return subscribe( path, recordChangedCallback, false );
    }

    public Record subscribe( RecordChangedCallback recordChangedCallback ) throws DeepstreamRecordDestroyedException {
        return subscribe( null, recordChangedCallback, false );
    }

    public Record subscribe( RecordChangedCallback recordChangedCallback, boolean triggerNow ) throws DeepstreamRecordDestroyedException {
        subscribe( null, recordChangedCallback, triggerNow );
        return this;
    }

    public Record subscribe( String path, RecordChangedCallback recordChangedCallback, boolean triggerNow ) throws DeepstreamRecordDestroyedException {
        throwExceptionIfDestroyed( "subscribe" );

        if( path == null ) {
            this.subscribers.on( ALL_EVENT, recordChangedCallback );
        } else {
            this.subscribers.on( path, recordChangedCallback );
        }

        if( triggerNow && path == null ) {
            recordChangedCallback.onRecordChanged( this.name, this.get() );
        } else if( triggerNow ) {
            recordChangedCallback.onRecordChanged( this.name, path, this.get( path ) );
        }

        return this;
    }

    public Record unsubscribe( RecordChangedCallback recordChangedCallback ) throws DeepstreamRecordDestroyedException {
        unsubscribe( null, recordChangedCallback );
        return this;
    }

    public Record unsubscribe( String path, RecordChangedCallback recordChangedCallback ) throws DeepstreamRecordDestroyedException {
        throwExceptionIfDestroyed( "unsubscribe" );

        if( path == null ) {
            this.subscribers.off( ALL_EVENT, recordChangedCallback );
        } else {
            this.subscribers.off( path, recordChangedCallback );
        }

        return this;
    }


    public Record discard() throws DeepstreamRecordDestroyedException {
        throwExceptionIfDestroyed( "delete" );
        this.usages--;
        if( this.usages <= 0 ) {
            // TODO: on ready async callback
            int subscriptionTimeout = Integer.parseInt( (String) this.options.get( "subscriptionTimeout" ) );
            this.ackTimeoutRegistry.add( Topic.RECORD, Actions.UNSUBSCRIBE, name, subscriptionTimeout );
            this.connection.send( MessageBuilder.getMsg( Topic.RECORD, Actions.UNSUBSCRIBE, this.name ) );
        }
        return this;
    }

    public Record delete() throws DeepstreamRecordDestroyedException {
        throwExceptionIfDestroyed( "delete" );

        // TODO: on ready async callback
        int subscriptionTimeout = Integer.parseInt( (String) this.options.get( "recordDeleteTimeout" ) );
        this.ackTimeoutRegistry.add( Topic.RECORD, Actions.DELETE, name, Event.DELETE_TIMEOUT, subscriptionTimeout );
        this.connection.send( MessageBuilder.getMsg( Topic.RECORD, Actions.DELETE, this.name ) );

        return this;
    }

    public Record whenReady( RecordReadyListener recordReadyListener ) {
        if( this.isReady ) {
            recordReadyListener.onRecordReady( this.name, this );
        } else {
            this.onceRecordReadyListeners.add( recordReadyListener );
        }
        return this;
    }

    protected void onMessage(Message message) {
        if( message.action == Actions.ACK ) {
            processAckMessage( message );
        } else if( message.action == Actions.READ && this.version == -1 ) {
            onRead( message );
        } else if( message.action == Actions.READ || message.action == Actions.UPDATE || message.action == Actions.PATCH ) {
            applyUpdate( message );
        } else if( message.data[ 0 ] == Event.VERSION_EXISTS.toString() ) {
            recoverRecord( Integer.parseInt( message.data[ 2 ] ), gson.fromJson( message.data[ 3 ], JsonElement.class ), message );
        } else if( message.data[ 0 ] == Event.MESSAGE_DENIED.toString() ) {
           clearTimeouts();
        }
    }

    void setRecordRemoteUpdateListener( RecordRemoteUpdateListener recordRemoteUpdateListener ) {
        this.recordRemoteUpdateListener = recordRemoteUpdateListener;
    }

    private void applyUpdate(Message message) {
        int version = Integer.parseInt( message.data[ 1 ] );

        JsonElement data;
        if( message.action == Actions.PATCH ) {
            data = gson.toJsonTree( MessageParser.convertTyped( message.data[ 3 ], client ) );
        } else {
            data = gson.fromJson( message.data[ 2 ], JsonElement.class );
        }


        if( this.version != -1 && this.version + 1 != version ) {
            if( message.action == Actions.PATCH ) {
                /**
                 * Request a snapshot so that a merge can be done with the read reply which contains
                 * the full state of the record
                 **/
                this.connection.send( MessageBuilder.getMsg( Topic.RECORD, Actions.SNAPSHOT, this.name ) );
            } else {
                recoverRecord( version, data, message );
            }
            return;
        }

        if( this.recordRemoteUpdateListener != null ) {
            this.recordRemoteUpdateListener.beforeRecordUpdate();
        }

        Map oldValues = beginChange();

        this.version = version;
        if( Actions.PATCH == message.action ) {
            path.set( message.data[ 2 ], data );
            System.out.println( "Changed to: " + path.get( message.data[2] ));
        } else {
            this.data = data;
            this.path.setCoreElement( data );
        }

        completeChange( oldValues );

        if( this.recordRemoteUpdateListener != null ) {
            this.recordRemoteUpdateListener.afterRecordUpdate();
        }
    }

    private void recoverRecord(int remoteVersion, JsonElement remoteData, Message message) {
        try {
            JsonElement mergedData = this.mergeStrategy.merge( this, remoteData, remoteVersion );
            this.version = remoteVersion;
            this.set( null, mergedData, true );
        } catch( RecordMergeStrategyException ex ) {
            this.client.onError( Topic.RECORD, Event.VERSION_EXISTS, "Received update for " + remoteVersion + " but version is " + this.version );
        }
    }

    private void scheduleAcks() {
        int readAckTimeout = Integer.parseInt( (String) this.options.get( "recordReadAckTimeout" ) );
        this.ackTimeoutRegistry.add( Topic.RECORD, Actions.SUBSCRIBE, this.name, Event.ACK_TIMEOUT, readAckTimeout );

        int readResponseTimeout = Integer.parseInt( (String) this.options.get( "recordReadTimeout" ) );
        this.ackTimeoutRegistry.add( Topic.RECORD, Actions.READ, this.name, Event.RESPONSE_TIMEOUT, readResponseTimeout );
    }

    private void clearTimeouts() {
        this.ackTimeoutRegistry.clear( Topic.RECORD, Actions.SUBSCRIBE, this.name );
        this.ackTimeoutRegistry.clear( Topic.RECORD, Actions.READ, this.name );
    }

    private Map beginChange() {
        Set<String> paths = this.subscribers.getEvents();

        if( paths.isEmpty() ) {
            return null;
        }

        Map<String,JsonElement> oldValues = new HashMap();

        if( paths.contains( ALL_EVENT ) ) {
            oldValues.put( ALL_EVENT, this.get() );
        }

        for( String path : paths ) {
            if( path != ALL_EVENT ) {
                oldValues.put( path, this.get( path ) );
            }
        }

        return oldValues;
    }

    private void completeChange(Map<String,JsonElement> oldValues) {
        List<Object> listeners;

        JsonElement oldValue, newValue;

        if( oldValues == null || oldValues.isEmpty() ) {
            return;
        }

        oldValue = oldValues.remove( ALL_EVENT );
        if( oldValue != null && !oldValue.equals( this.data ) ) {
            listeners = this.subscribers.listeners( ALL_EVENT );
            for( Object listener : listeners ) {
                ((RecordChangedCallback) listener).onRecordChanged( this.name, this.get() );
            }
        }

        for( String key : oldValues.keySet() ) {
            oldValue = oldValues.get( key );
            newValue = this.get( key );
            if( oldValue == null || !oldValue.equals( newValue ) ) {
                listeners = this.subscribers.listeners( key );
                for( Object listener : listeners ) {
                    ((RecordChangedCallback) listener).onRecordChanged( this.name, key, newValue );
                }
            }
        }
    }

    private void throwExceptionIfDestroyed(String action) throws DeepstreamRecordDestroyedException {
        if( this.isDestroyed ) {
            throw new DeepstreamRecordDestroyedException();
        }
    }

    private void processAckMessage(Message message) {
        Actions action = Actions.getAction( message.data[ 0 ] );
        this.ackTimeoutRegistry.clear( message );

        if( action.equals( Actions.DELETE ) ) {
            for(RecordEventsListener recordEventsListener: this.recordEventsListeners) {
                recordEventsListener.onRecordDeleted( this.name );
            }
            this.destroy();
        }
        else if( action.equals( Actions.UNSUBSCRIBE ) ) {
            for(RecordEventsListener recordEventsListener: this.recordEventsListeners) {
                recordEventsListener.onRecordDiscarded( this.name );
            }
            this.destroy();
        }
    }

    private void onRead( Message message ) {
        ackTimeoutRegistry.clear( message );

        Map oldValues = beginChange();
        this.version = Integer.parseInt( message.data[ 1 ] );
        this.data = gson.fromJson( message.data[ 2 ], JsonElement.class );
        this.path.setCoreElement(this.data);
        completeChange( oldValues );
        setReady();
    }

    private void setReady() {
        this.isReady = true;

        for(RecordReadyListener recordReadyListener: this.onceRecordReadyListeners) {
            recordReadyListener.onRecordReady( this.name, this );
        }
        this.onceRecordReadyListeners.clear();

        for(RecordReadyListener recordReadyListener: this.recordReadyListeners) {
            recordReadyListener.onRecordReady( this.name, this );
        }
    }

    private void sendRead() {
        if( this.client.getConnectionState() == ConnectionState.OPEN ) {
            this.connection.send( MessageBuilder.getMsg( Topic.RECORD, Actions.CREATEORREAD, this.name ) );
        }
    }

    private void sendUpdate( String key, Object value ) {
        if( key == null || key == "" ) {
            this.connection.sendMsg( Topic.RECORD, Actions.UPDATE, new String[] {
                    this.name,
                    String.valueOf( this.version ),
                    gson.toJson( value )
            });
        }
        else {
            this.connection.sendMsg( Topic.RECORD, Actions.PATCH, new String[] {
                    this.name,
                    String.valueOf( this.version ),
                    key,
                    MessageBuilder.typed( value )
            });
        }
    }

    private void destroy() {
        this.clearTimeouts();
        this.utilResubscribeNotifier.destroy();
        this.isReady = false;
        this.isDestroyed = true;
    }

    private JsonElement deepCopy(JsonElement element) {
        try {
            return gson.fromJson(gson.toJson(element, JsonElement.class), JsonElement.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private <T> T deepCopy(JsonElement element, Class<T> type) {
        return gson.fromJson(gson.toJson(element, JsonElement.class), type);
    }

    static interface RecordRemoteUpdateListener {
        void beforeRecordUpdate();
        void afterRecordUpdate();
    }
}
