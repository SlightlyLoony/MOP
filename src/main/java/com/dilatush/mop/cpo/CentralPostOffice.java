package com.dilatush.mop.cpo;

import com.dilatush.mop.Message;
import com.dilatush.mop.util.JVMMonitor;
import com.dilatush.mop.util.OSMonitor;
import com.dilatush.util.Base64;

import java.nio.channels.SelectionKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.General.*;
import static com.dilatush.util.Strings.isNonEmpty;

/**
 * Implements a MOP Central Post Office that routes messages between Post Offices.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CentralPostOffice {

    public final static String CPO_PO    = "central.po";
    public final static String RECONNECT = "manage.reconnect";
    public final static String CONNECT   = "manage.connect";
    public final static String PONG      = "manage.pong";
    public final static String STATUS    = "manage.status";
    public final static String WRITE     = "manage.write";
    public final static String ADD       = "manage.add";
    public final static String DELETE    = "manage.delete";
    public final static String MONITOR   = "manage.monitor";
    public final static String CONNECTED = "manage.connected";

    @SuppressWarnings( "SpellCheckingInspection" )
    public final static DateTimeFormatter  TIME_FORMATTER
            = DateTimeFormatter
            .ofPattern( "yyyy/MM/dd HH:mm:ss.SSS zzzz" )
            .withZone( ZoneId.systemDefault() );

    private static final Logger LOGGER                 = getLogger();
    private static final int    PONG_CHECK_INTERVAL_MS = 100;
    private static final int    RXBYTES_QUEUE_SIZE     = 100;       // number of blocks of bytes that can be in queue at once...

    private       final Instant                     started;
    private       final AtomicLong                  nextID;
    private       final Timer                       timer;
    private       final Map<String,Set<String>>     subscriptions;  // note 1...
    /* package */ final String                      configFilePath;
    /* package */ final String                      secretsFilePath;
    /* package */ final Map<String,POConnection>    connections;
    /* package */ final ArrayBlockingQueue<RxBytes> rxbytes;
    /* package */ final ChannelEventHandler         handler;
    /* package */ final Config                      config;
    /* package */ final RxBytesHandler              rxhandler;
    /* package */ final Map<String,POClient>        clients;

    /* package */ volatile boolean shutdown;
    @SuppressWarnings( "unused" )
    private       volatile RunMonitors monitors;    // keeps a monitor reference until it has finished, so GC doesn't get it...

    // Notes:
    //
    // 1.  This data structure is used for routing publish messages from the source post office to the (possibly multiple) destination post offices.
    //     The structure is built by snooping on subscribe messages from post offices with boxes subscribing to foreign sources.  The map's key is a
    //     fully-qualified subscription: (po).(mailbox).(major) or (po).(mailbox).(major).(minor), where the (po).(mailbox) is the source of the
    //     publish messages.  The value at that key is a set of subscribing mailbox names: (po).(mailbox)
    //


    /**
     * Creates a new central post office, with the configuration and client secrets taken from the given paths.
     *
     * @param _configFilePath the path to the Java configuration file.
     * @param _secretsFilePath the path to the client secrets file.
     */
    public CentralPostOffice( final String _configFilePath, final String _secretsFilePath ) {
        started         = Instant.now();
        configFilePath  = _configFilePath;
        secretsFilePath = _secretsFilePath;
        timer           = new Timer( "Central Post Office Timer", true );
        connections     = new ConcurrentHashMap<>();
        subscriptions   = new ConcurrentHashMap<>();
        config          = Config.initializeConfig( configFilePath, secretsFilePath );
        rxbytes         = new ArrayBlockingQueue<>( RXBYTES_QUEUE_SIZE );
        rxhandler       = new RxBytesHandler();
        clients         = config.clients;    // just making a local convenience variable...
        nextID          = new AtomicLong( 0 );
        handler         = new ChannelEventHandler( this );
    }


    /**
     * Starts the central post office.  Throws an {@link IllegalStateException} if there is any problem while opening.
     */
    public synchronized void start() {

        timer.schedule( new Pinger(),    config.pingIntervalMS, config.pingIntervalMS   );
        timer.schedule( new PongCheck(), PONG_CHECK_INTERVAL_MS, PONG_CHECK_INTERVAL_MS );
        handler.open();
        handler.start();
    }


    /**
     * Shuts down the central post office, including all subordinate threads.
     */
    @SuppressWarnings( "unused" )
    public void shutdown() {

        LOGGER.info( "Central Post Office is shutting down" );

        // let all our threads know we're shutting down...
        shutdown = true;

        // first kill our timer...
        timer.cancel();

        // then kill our received bytes handler...
        rxhandler.interrupt();

        // and finally kill the channel events handler (which may be dead already because the event loop checks shutdown)...
        handler.close();
        handler.interrupt();

        LOGGER.info( "Central post office has shutdown" );
    }


    /**
     * Receive the specified message into the central post office for routing.
     *
     * @param _source the client this message came from.
     * @param _message the message to route.
     */
    public void receiveMessage( final POClient _source, final Message _message ) {

        // if it's a publish message, see if we can route it...
        if( _message.isPublish() ) {

            // make the key we need to look up the subscription...
            String key = _message.from + "." + _message.type;

            // get the entry at that key...
            Set<String> destinations = subscriptions.get( key );

            // if it's not null and has at least one entry, we've got a routable message type...
            if( isNotNull( destinations ) && !destinations.isEmpty() ) {

                // iterate over all our destinations, sending the message on just once per post office...
                Set<String> pos = new HashSet<>();
                for( String destination : destinations ) {

                    // if we haven't already sent the message along to this post office, do so...
                    String po = destination.substring( 0, destination.indexOf( '.' ) );
                    if( !pos.contains( po ) ) {

                        // forward the message to this post office...
                        deliver( _source, _message, po );

                        // mark this post office as having been done...
                        pos.add( po );
                    }
                }
            }

            // otherwise we've got an unknown message type...
            else {
                LOGGER.severe( "CPO received unknown publish message source and type: " + key );
            }
        }

        // otherwise, it's a direct message...
        else {

            // if it's a foreign subscribe or unsubscribe message, snoop on it to build our routing table...
            boolean isPOMailbox = "po".equals( _message.to.substring( _message.to.indexOf( '.' ) + 1 ) );
            boolean isSubscription = "manage.subscribe".equals( _message.type ) ||  "manage.unsubscribe".equals( _message.type );
            if( isPOMailbox && isSubscription && !_message.isReply() ) handleSubscriptionSnooping( _message );

            // if the destination is the CPO post office, handle it right here...
            if( CPO_PO.equals( _message.to ) ) {

                // now handle each possible message type...
                switch( _message.type ) {
                    case CONNECT   -> handleConnect( _message, false );
                    case RECONNECT -> handleConnect( _message, true );
                    case STATUS    -> handleStatus( _message );
                    case WRITE     -> handleWrite( _message );
                    case ADD       -> handleAdd( _message );
                    case DELETE    -> handleDelete( _message );
                    case MONITOR   -> handleMonitor( _message );
                    case PONG      -> handlePong( _message );
                    case CONNECTED -> handleConnected( _message );
                    default        -> LOGGER.severe( "Unknown message type received: " + _message.type );
                }
                return;
            }

            // otherwise, try to deliver it to the right client...
            String prefix = _message.to.substring( 0, _message.to.indexOf( '.' ) );
            deliver( _source, _message, prefix );
        }
    }


    private void deliver( final POClient _source, final Message _message, final String _po ) {
        POClient client = clients.get( _po );
        if( isNotNull( client ) ) {
            if( _message.isEncrypted() ) {
                Message copy = new Message( _message.toJSON() );            // make a copy, so we keep the source encryption...
                copy.reEncrypt( _source.secretBytes, client.secretBytes );  // decrypt/encrypt for source/destination...
                client.deliver( copy );
            }
            else
                client.deliver( _message );
        }
        else LOGGER.severe( "Can't route direct message to unknown client post office: " + _po );
    }


    private void handleMonitor( final Message _message ) {

        LOGGER.info( "Received monitor request from: " + _message.from );

        // run the monitors in another thread so that we don't block this one (monitoring can take up to a second or two)...
        monitors = new RunMonitors( _message );
    }


    private class RunMonitors extends Thread {

        private       Message  message;
        private final POClient client;

        private RunMonitors( final Message _message ) {
            client = clients.get( _message.fromPO );
            if( isNull( client ) ) {
                LOGGER.severe( "Received manage.monitor message from unknown post office: " + _message.fromPO );
                monitors = null;  // kill our reference...
                return;
            }
            message = new Message( "central.po", _message.from, "manage.monitor", getNextID(), null, false );
            setName( "Run Monitors" );
            setDaemon( true );
            start();
        }


        @Override
        public void run() {

            OSMonitor osm = new OSMonitor();
            JVMMonitor jvm = new JVMMonitor();
            osm.fill( message );
            jvm.fill( message );
            client.deliver( message );
            monitors = null;  // kill our reference so that GC will clean up this object...
        }
    }


    private void handleSubscriptionSnooping( final Message _message ) {

        boolean isSubscribe = _message.type.endsWith( ".subscribe" );

        // make the key we need to look up the subscription...
        String key = _message.getString( "source" ) + "." + _message.getString( "type" );

        // get the entry at that key, or make an empty one...
        Set<String> destinations = subscriptions.computeIfAbsent( key, k -> ConcurrentHashMap.newKeySet() );

        if( isSubscribe ) {

            // add our subscriber to the set...
            destinations.add( _message.getString( "requestor" ) );
        }
        else {
            destinations.remove( _message.getString( "requestor" ) );
        }
        LOGGER.info( "Snooping on " + (isSubscribe ? "add" : "remove") + " subscription to: " + key + " from " + _message.getString( "requestor" ) );
    }


    private void handlePong( final Message _message ) {

        LOGGER.finer( "Received pong from: " + _message.from );

        // get the connection info (inserted by POConnection)...
        String connectionName = _message.optString( POConnection.CONNECTION_NAME, null );
        POConnection connection = connections.get( connectionName );

        // if we didn't get a connection, just log it and leave...
        if( isNull( connection ) ) {
            LOGGER.info( "Connection not found: " + connectionName );
            return;
        }

        // reset the time since the last pong was received...
        connection.timeSinceLastPongMS.set( 0 );
    }


    private void handleConnected( final Message _message ) {

        // get a list of connected post offices...
        StringBuilder sb = new StringBuilder();
        for( POClient client : clients.values() ) {
            if( client.isConnected() ) {
                if( sb.length() > 0 )
                    sb.append( "," );
                sb.append( client.name );
            }
        }

        // now send the list back...
        POClient sender = clients.get( _message.fromPO );
        Message msg = new Message( "central.po", _message.from, "manage.connected", getNextID(), null, false );
        msg.put( "postOffices", sb.toString() );
        sender.deliver( msg );

        LOGGER.info( "Sent list of connected post offices" );
    }


    private void handleDelete( final Message _message ) {

        // first we verify that this client is a designated manager...
        POClient manager = clients.get( _message.fromPO );
        if( isNull( manager ) || !manager.isManager() ) return;

        // ok, it is a manager, so delete the specified post office...
        _message.decrypt( manager.secretBytes );
        String po = _message.optString( "name",   "" );

        // if we didn't get the fields we need, don't delete anything...
        if( isNonEmpty( po ) )
            clients.remove( po );

        // set the ack back...
        Message msg = new Message( "central.po", _message.from, "manage.delete", getNextID(), null, false );
        manager.deliver( msg );

        LOGGER.info( "Deleted post office \"" + po + "\" from configured clients" );
    }


    private void handleAdd( final Message _message ) {

        // first we verify that this client is a designated manager...
        POClient manager = clients.get( _message.fromPO );
        if( isNull( manager ) || !manager.isManager() ) return;

        // ok, it is a manager, so create and add the new post office...
        _message.decrypt( manager.secretBytes );
        String po           = _message.optString( "name",   "" );
        String secretBase64 = _message.optString( "secret", "" );

        // if we didn't get the fields we need, don't add anything...
        if( isNonEmpty( po ) && isNonEmpty( secretBase64 ) ) {
            POClient client = new POClient( po, secretBase64 );
            clients.put( po, client );
        }

        // set the ack back...
        Message msg = new Message( "central.po", _message.from, "manage.add", getNextID(), null, false );
        manager.deliver( msg );

        LOGGER.info( "Added post office \"" + po + "\" to configured clients" );
    }


    private void handleWrite( final Message _message ) {

        // first we verify that this client is a designated manager...
        POClient manager = clients.get( _message.fromPO );
        if( isNull( manager ) || !manager.isManager() ) return;

        // ok, it is a manager, so write the secrets file out...
        config.write( secretsFilePath );

        // now send the ack back...
        Message msg = new Message( "central.po", _message.from, "manage.write", getNextID(), null, false );
        manager.deliver( msg );

        LOGGER.info( "Wrote configuration file" );
    }


    private void handleStatus( final Message _message ) {

        // first we verify that this client is a designated manager...
        POClient manager = clients.get( _message.fromPO );
        if( isNull( manager ) || !manager.isManager() ) return;

        // ok, it is a manager - so construct our reply message and send it back...
        Message msg = new Message( "central.po", _message.from, "manage.status", getNextID(), null, false );

        // first the cpo info...
        double uptimeDays = Duration.between( started, Instant.now() ).toNanos() / (24d * 60 * 60 * 1000000000);
        msg.put( "started",        TIME_FORMATTER.format( started ) );
        msg.put( "upDays",         uptimeDays                       );
        msg.put( "numConnections", connections.size()               );
        msg.put( "numClients",     clients.size()                   );
        msg.put( "maxMessageSize", config.maxMessageSize            );
        msg.put( "pingIntervalMS", config.pingIntervalMS            );
        msg.put( "name",           config.name                      );
        msg.put( "port",           config.port                      );
        msg.put( "localAddress",   config.localAddress              );

        // then the client information...
        for( POClient client : clients.values() ) {

            String prefix = "clients." + client.name + ".";

            msg.putDotted( prefix + "name",              client.name                                     );
            msg.putDotted( prefix + "manager",           client.manager                                  );
            msg.putDotted( prefix + "connections",       client.connections.get()                        );
            msg.putDotted( prefix + "isConnected",       isNotNull( client.connection )                  );
            if( isNotNull( client.lastConnectTime ) ) {
                uptimeDays = Duration.between(           client.lastConnectTime, Instant.now() ).toNanos() / (24d * 60 * 60 * 1000000000);
                msg.putDotted( prefix + "lastConnected", TIME_FORMATTER.format( client.lastConnectTime ) );
                msg.putDotted( prefix + "upDays",        uptimeDays                                      );
            }
            msg.putDotted( prefix + "secret",            client.secretBase64                             );
            msg.putDotted( prefix + "rxMessages",        client.rxMessages.get()                         );
            msg.putDotted( prefix + "rxBytes",           client.rxBytes.get()                            );
            msg.putDotted( prefix + "txMessages",        client.txMessages.get()                         );
            msg.putDotted( prefix + "txBytes",           client.txBytes.get()                            );
        }

        // encrypt the client information...
        msg.encrypt( manager.secretBytes, "clients" );

        // now send the message back to the manager...
        manager.deliver( msg );

        LOGGER.info( "Sent Central Post Office status to " + _message.from );
    }


    private void handleConnect( final Message _message, final boolean _isReconnect ) {

        // authenticate this connection...
        String poName = _message.from.substring( 0, _message.from.indexOf( '.' ) );
        POClient client = clients.get( poName );

        // get the connection info (inserted by POConnection)...
        String connectionName = _message.optString( POConnection.CONNECTION_NAME, null );
        POConnection connection = connections.get( connectionName );

        // if we didn't get a connection, just log it and leave...
        if( isNull( connection ) ) {
            LOGGER.info( "Connection not found: " + connectionName );
            return;
        }

        // if we don't know about this client post office, close the connection and bail...
        if( isNull( client ) ) {
            closeConnection( connection );
            LOGGER.info( "Connection attempted from unknown post office: " + poName );
            return;
        }

        // construct an authenticator with our shared secret...
        Authenticator authenticator = new Authenticator( client.secretBytes, poName, _message.id );
        byte[] rxAuthenticator = Base64.decodeBytes( _message.optString( "authenticator", "" ) );

        // if it doesn't match, so we have an evil impostor (or something went horribly wrong) - shut down the connection and log it...
        if( !authenticator.verify( rxAuthenticator ) ) {
            closeConnection( connection );
            LOGGER.severe( "Connection attempted with invalid authenticator, from post office: " + poName );
        }

        // if there's already a different connection associated with this client, close it, and log it, because it really shouldn't be happening...
        if( isNotNull( client.connection ) && (client.connection != connection) ) {
            closeConnection( client.connection );
            LOGGER.info( "Client already had a different connection associated with it, which is now closed" );
        }

        // if this connection is already associated, log the anomaly but do nothing else...
        if( client.connection == connection ) {
            LOGGER.info( "Got connect message for post office that's already connected: " + poName );
            return;
        }

        // if we make here, then all looks good -- time to associate our new connection with this client...
        associate( connection, client );
        LOGGER.info( "Associated post office " + client.name + " with connection " + connection.name );

        // now send the appropriate response back to the client post office...
        String type = "manage." + ((client.connections.get() == 0) ? "connect" : "reconnect");
        String toPO = poName + ".po";
        Message response = new Message( toPO, _message.from, type, getNextID(), _message.id, false  );
        response.put( "maxMessageSize", config.maxMessageSize );
        response.put( "pingIntervalMS", config.pingIntervalMS );
        client.deliverNext( response );
        client.lastConnectTime = Instant.now();

        if( !_isReconnect )
            handleSubscriptionRefresh( client );

        // update our count of connects...
        client.connections.incrementAndGet();
    }


    // We get here if a client just connected.  This method re-sends all the subscription requests that the central post office has seen being sent
    // to the post office that just connected.
    private void handleSubscriptionRefresh( final POClient _client ) {

        LOGGER.info( "Refreshing subscriptions for post office \"" + _client.name + "\"" );

        // scan all subscriptions, looking for those that are sourced on the specified client...
        String prefix = _client.name + ".";   // the key prefix indicating subscriptions we care about...
        Set<Map.Entry<String,Set<String>>> entries = subscriptions.entrySet();
        for( final Map.Entry<String, Set<String>> entry : entries ) {

            // if the key doesn't match, then move along...
            String key = entry.getKey();
            if( !key.startsWith( prefix ) ) continue;

            // if we get here, the prefix matches, and we need to send messages to the connecting client...
            for( String subscriber : entry.getValue() ) {

                String type = "manage.subscribe";
                String subscriberPO = subscriber.substring( 0, subscriber.indexOf( '.' ) ) + ".po";
                String sourcePO = key.substring( 0, key.indexOf( '.' ) ) + ".po";
                String source = key.substring( 0, key.indexOf( '.', prefix.length() ) );
                String subscriptionType = key.substring( source.length() + 1 );
                Message message = new Message( subscriberPO, sourcePO, type, getNextID(), null, false );
                message.put( "source", source );
                message.put( "type", subscriptionType );
                message.put( "requestor", subscriber );
                _client.deliver( message );
            }
        }
    }


    /**
     * Generates and returns a string ID that is unique within the scope of this post office.
     *
     * @return the generated ID.
     */
    public String getNextID() {
        return Base64.encode( nextID.incrementAndGet() ) + ".cpo";
    }


    private void associate( final POConnection _connection, final POClient _state ) {
        _state.connection = _connection;
        _connection.client = _state;
    }


    /* package */ void closeConnection( final POConnection _connection ) {

        // if some numskull calls us without a connection, just leave...
        if( isNull( _connection ) ) return;

        // get the key for this connection, a bit indirectly...
        SelectionKey key = _connection.socket.keyFor( handler.selector );

        // if we found a key, kill the attachment, so we don't hang onto the POConnection object...
        if( isNotNull( key ) ) key.attach( null );

        // now close the connection...
        _connection.close();

        // and remove it from our collection of connections...
        connections.remove( _connection.name );
    }


    private class RxBytesHandler extends Thread {

        private RxBytesHandler() {
            setDaemon( true );
            setName( config.name + " Rx Bytes Handler" );
            start();
        }


        public void run() {

            while( true ) {

                try {
                    // wait for some bytes to be received...
                    RxBytes bytes = rxbytes.take();

                    // send the bytes to the associated connection...
                    bytes.connection.receiveBytes( bytes.buffer );
                }
                catch( InterruptedException _e ) {
                    break;
                }
                catch( Exception _e ) {

                    // log this, so we know what happened...
                    LOGGER.log( Level.SEVERE, "Exception below escaped from rx bytes handler; ignoring", _e );
                }
            }
        }
    }


    private class Pinger extends TimerTask {

        /**
         * Called every pingInterval to send a ping to all currently connected client post offices.
         */
        @Override
        public void run() {

            LOGGER.finer( "Sending pings" );
            Set<Map.Entry<String, POClient>> clientSet = clients.entrySet();
            for( Map.Entry<String, POClient> clientEntry : clientSet ) {
                POClient client = clientEntry.getValue();
                if( isNull( client.connection ) ) continue;
                String to = client.name + ".po";
                Message message = new Message( "central.po", to, "manage.ping", getNextID(), null, false );
                client.deliver( message );
            }
        }
    }


    private class PongCheck extends TimerTask {


        /**
         * Called periodically, checks to make sure we're receiving pongs on all connections.
         */
        @Override
        public void run() {

            // iterate over all extant open connections...
            for( POConnection connection : connections.values() ) {

                if( connection.open ) {

                    long pongTime = connection.timeSinceLastPongMS.addAndGet( PONG_CHECK_INTERVAL_MS );

                    // if it's been 150% of the ping interval since we got a pong, close the connection and let it reconnect...
                    if( pongTime >= (((config.pingIntervalMS << 1) + config.pingIntervalMS) >> 1 ) ) {
                        LOGGER.severe( "Failed to receive pong in time on " + connection.name );
                        closeConnection( connection );
                    }
                }
            }
        }
    }
}
