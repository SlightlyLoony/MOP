package com.dilatush.mop.cpo;

import com.dilatush.mop.Message;
import com.dilatush.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.channels.SelectionKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;

/**
 * Implements a MOP Central Post Office that routes messages between Post Offices.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CentralPostOffice {

    public final static String CPO       = "central";
    public final static String CPO_PO    = "central.po";
    public final static String RECONNECT = "manage.reconnect";
    public final static String CONNECT   = "manage.connect";
    public final static String PONG      = "manage.pong";

    private static final Logger LOG = LogManager.getLogger();

    private static final int PONG_CHECK_INTERVAL_MS = 100;

    private static final int RXBYTES_QUEUE_SIZE     = 100;       // number of blocks of bytes that can be in queue at once...

    private       final AtomicLong                  nextID;
    private       final Timer                       timer;
    private       final Map<String,Set<String>>     subscriptions;  // note 1...
    /* package */ final String                      configFilePath;
    /* package */ final Map<String,POConnection>    connections;
    /* package */ final ArrayBlockingQueue<RxBytes> rxbytes;
    /* package */ final ChannelEventHandler         handler;
    /* package */ final Config                      config;
    /* package */ final RxBytesHandler              rxhandler;
    /* package */ final Map<String,POClient>        clients;

    /* package */ volatile boolean shutdown;

    // Notes:
    //
    // 1.  This data structure is used for routing publish messages from the source post office to the (possibly multiple) destination post offices.
    //     The structure is built by snooping on subscribe messages from post offices with boxes subscribing to foreign sources.  The map's key is a
    //     fully-qualified subscription: (po).(mailbox).(major) or (po).(mailbox).(major).(minor), where the (po).(mailbox) is the source of the
    //     publish messages.  The value at that key is a set of subscribing mailbox names: (po).(mailbox)
    //

    public CentralPostOffice( final String _configFilePath ) {
        configFilePath = _configFilePath;
        timer          = new Timer( "Central Post Office Timer", true );
        connections    = new ConcurrentHashMap<>();
        subscriptions  = new ConcurrentHashMap<>();
        config         = Config.initializeConfig( _configFilePath );
        rxbytes        = new ArrayBlockingQueue<>( RXBYTES_QUEUE_SIZE );
        rxhandler      = new RxBytesHandler();
        clients        = config.clients;    // just making a local convenience variable...
        nextID         = new AtomicLong( 0 );
        handler        = new ChannelEventHandler( this );
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
    public void shutdown() {

        // let all our threads know we're shutting down...
        shutdown = true;

        // first kill our timer...
        timer.cancel();

        // then kill our received bytes handler...
        rxhandler.interrupt();

        // and finally kill the channel events handler (which may be dead already because the event loop checks shutdown)...
        handler.close();
        handler.interrupt();

        LOG.info( "Central post office has shutdown" );
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
                LOG.error( "CPO received unknown publish message source and type: " + key );
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

                    case CONNECT:   handleConnect( _message, false ); break;
                    case RECONNECT: handleConnect( _message, true  ); break;
                    case PONG:      handlePong(    _message                    ); break;

                    default:
                        LOG.error( "Unknown message type received: " + _message.type );
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
                Message copy = new Message( _message.toJSON() );            // make a copy so we keep the source encryption...
                copy.reEncrypt( _source.secretBytes, client.secretBytes );  // decrypt/encrypt for source/destination...
                client.deliver( copy );
            }
            else
                client.deliver( _message );
        }
        else LOG.error( "Can't route direct message to unknown client post office: " + _po );
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
    }


    private void handlePong( final Message _message ) {

        // get the connection info (inserted by POConnection)...
        String connectionName = _message.optString( POConnection.CONNECTION_NAME, null );
        POConnection connection = connections.get( connectionName );

        // if we didn't get a connection, just log it and leave...
        if( isNull( connection ) ) {
            LOG.info( "Connection not found: " + connectionName );
            return;
        }

        // reset the time since the last pong was received...
        connection.timeSinceLastPongMS.set( 0 );
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
            LOG.info( "Connection not found: " + connectionName );
            return;
        }

        // if we don't know about this client post office, close the connection and bail...
        if( isNull( client ) ) {
            closeConnection( connection );
            LOG.info( "Connection attempted from unknown post office: " + poName );
            return;
        }

        // construct an authenticator with our shared secret...
        Authenticator authenticator = new Authenticator( client.secretBytes, poName, _message.id );
        byte[] rxAuthenticator = Base64.decodeBytes( _message.optString( "authenticator", "" ) );

        // if it doesn't match, so we have an evil impostor (or something went horribly wrong) - shut down the connection and log it...
        if( !authenticator.verify( rxAuthenticator ) ) {
            closeConnection( connection );
            LOG.error( "Connection attempted with invalid authenticator, from post office: " + poName );
        }

        // if there's already a different connection associated with this client, close it, and log it, because it really shouldn't be happening...
        if( isNotNull( client.connection ) && (client.connection != connection) ) {
            closeConnection( client.connection );
            LOG.info( "Client already had a different connection associated with it, which is now closed" );
        }

        // if this connection is already associated, log the anomaly but do nothing else...
        if( client.connection == connection ) {
            LOG.info( "Got connect message for post office that's already connected: " + poName );
            return;
        }

        // if we make here, then all looks good -- time to associate our new connection with this client...
        associate( connection, client );
        LOG.info( "Associated post office " + client.name + " with connection " + connection.name );

        // now send the appropriate response back to the client post office...
        String fromClient = _message.from.substring( 0, _message.from.indexOf( '.' ) );
        String type = "manage." + ((client.connections.get() == 0) ? "connect" : "reconnect");
        String toPO = fromClient + ".po";
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

        // scan all subscriptions, looking for those that are sourced on the specified client...
        String prefix = _client.name + ".";   // the key prefix indicating subscriptions we care about...
        Set<Map.Entry<String,Set<String>>> entries = subscriptions.entrySet();
        for( final Map.Entry<String, Set<String>> entry : entries ) {

            // if the key doesn't match, then move along...
            String key = entry.getKey();
            if( !key.startsWith( prefix ) ) continue;

            // if we get here, the prefix matches and we need to send messages to the connecting client...
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

        // if some numbskull calls us without a connection, just leave...
        if( isNull( _connection ) ) return;

        // get the key for this connection, a bit indirectly...
        SelectionKey key = _connection.socket.keyFor( handler.selector );

        // if we found a key, kill the attachment so we don't hang onto the POConnection object...
        if( isNotNull( key ) ) key.attach( null );

        // now close the connection...
        _connection.close();

        // and remove it from our collection of connections...
        connections.remove( _connection.name );
    }


    public void killConnection( final String _name ) {
        POClient client = clients.get( _name );
        if( isNull( client ) ) return;
        POConnection connection = client.connection;
        if( isNull( connection ) ) return;
        closeConnection( connection );
    }


    public void killServerSocket() {
        handler.close();
    }


    public void testReadException() {
        handler.testReadException = true;
    }


    public void testWriteException() {
        handler.testWriteException = true;
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
                    LOG.error( "Exception below escaped from rx bytes handler; ignoring", _e );
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

            LOG.info( "Sending pings" );
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
                        LOG.error( "Failed to receive pong in time on " + connection.name );
                        closeConnection( connection );
                    }
                }
            }
        }
    }
}
