package com.dilatush.mop;

import com.dilatush.util.Base64;
import com.dilatush.util.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static com.dilatush.util.Base64.encode;
import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;
import static com.dilatush.util.Strings.isEmpty;

/**
 * A post office manages mailboxes and routes messages to and from the central post office.  Post offices are generally instantiated just once (effectively a
 * singleton) per process, though this is neither required or enforced.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class PostOffice extends Thread {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());

    public  final static String CPO_MAILBOX_NAME = "[({CPO})]";

    public        final String name;       // the name of this post office, unique within the scope of the associated central post office...
    public        final String prefix;     // the prefix...

    private       final static int CPO_MAILBOX_SIZE_MULTIPLIER      = 10;
    private       final static int SPECIAL_WAITER_CHECK_INTERVAL_MS = 100;
    private       final static int SPECIAL_WAITER_EXPIRATION_MS     = 1000;

    /* package */ final Timer                           timer;

    private       final Map<String,Mailbox>             mailboxes;           // note 2...
    private       final Map<String,Map<String,Mailbox>> subscriptions;       // note 1...
    private       final AtomicLong                      nextID;
    private       final Mailbox                         cpoMailbox;          // for messages TO the central post office...
    private       final Mailbox                         poMailbox;
    private       final int                             mailboxQueueSize;
    private       final int                             cpoMailboxQueueSize;
    private       final byte[]                          secret;
    private       final String                          cpoHost;
    private       final int                             cpoPort;
    private       final CPOConnection                   connection;
    private       final MailReceiver                    mailReceiver;
    private       final Set<String>                     specialMessageTypes;  // message types with ".po" destination that need replies handled...
    private       final Map<String,SpecialReplyWaiter>  specialReplyWaiters;  // note 3...

    /* package */ volatile boolean                      shutdown;

    // Notes:
    //
    // 1.  Subscriptions are indexed by the <postoffice.mailbox.major.minor> message type for subscriptions to individual message types, and by
    //     <postoffice.mailbox.major> for subscriptions to all messages of the specified major type.  For each type of subscriptions, a map of mailbox
    //     name to mailbox is maintained.  For all of these, the mailbox name is the fully-qualified <post office.mailbox>.  For local subscriptions, the
    //     mailbox is the actual local mailbox.  For foreign subscriptions, the mailbox is the central post office mailbox, which is acting as a proxy for
    //     the foreign mailbox.
    //
    // 2.  Mailboxes are indexed by their name, NOT qualified by the post office name.  In other words, "mailbox", not "post office.mailbox".
    //
    // 3.  This map contains an entry (indexed by message id) for every message of one of the special types that was sent and is expecting a reply.
    //     If replies are not received within a certain time, then the messages are resent.  This is used for (at least) foreign subscribe and
    //     unsubscribe messages, where we do not want to block the thread making the subscription.

    /**
     * Creates a new instance of this class from the specified parameters.
     *
     * @param _configFilePath the path to the file containing this post office's configuration.
     */
    public PostOffice( final String _configFilePath ) {
        this( getConfig( _configFilePath ) );
    }


    private static Config getConfig( final String _configFilePath ) {

        if( isEmpty( _configFilePath ) ) throw new IllegalArgumentException( "Missing configuration file path" );

        // initialize our config file...
        return Config.fromJSONFile( _configFilePath );
    }


    /**
     * Create a new {@link PostOffice} instance with the given configuration.
     *
     * @param _config the configuration.
     */
    public PostOffice( final com.dilatush.mop.Config _config ) {
        this( _config.name, _config.secret, _config.queueSize, _config.cpoHost, _config.cpoPort );
    }


    /**
     * Create a new {@link PostOffice} instance with the given configuration.
     *
     * @param _config the configuration.
     */
    public PostOffice( final Config _config ) {
        this( _config.optString( "name" ), _config.optString( "secret" ),_config.optInt( "queueSize" ), _config.optString( "cpoHost" ), _config.optInt( "cpoPort" ) );
    }


    /**
     * Create a new {@link PostOffice} instance with the given parameters.
     *
     * @param _name The name of this post office, which must be at least one character long and unique amongst all post offices connected to the
     *              same central post office.
     * @param _secretBase64 The secret used to encrypt messages to and from this post office and the central post office.  It must be identical to
     *                      the secret for this post office that is configured on the central post office.
     * @param _mailboxQueueSize The maximum number of received messages that may be queued in a mailbox.
     * @param _cpoHost The fully qualified cpoHost name (or IP address) of the central post office cpoHost.
     * @param _cpoPort The TCP cpoPort number for the central post office.
     */
    public PostOffice( final String _name, final String _secretBase64, final int _mailboxQueueSize, final String _cpoHost, final int _cpoPort ) {

        name                = _name;
        mailboxQueueSize    = _mailboxQueueSize;
        cpoHost             = _cpoHost;
        cpoPort             = _cpoPort;

        if( isEmpty( name ) || name.contains( "." ) ) throw new IllegalArgumentException( "Name is empty or contains a period (\".\")" );
        if( mailboxQueueSize < 1) throw new IllegalArgumentException( "Invalid maximum mailbox received message queue size: " + mailboxQueueSize );
        if( isEmpty( _secretBase64 ) ) throw new IllegalArgumentException( "Missing shared secret" );

        prefix              = name + ".";
        timer               = new Timer( name + " Timer", true );
        secret              = Base64.decodeBytes( _secretBase64 );
        specialMessageTypes = new HashSet<>();
        specialMessageTypes.add( "manage.subscribe"   );
        specialMessageTypes.add( "manage.unsubscribe" );
        mailboxes           = new ConcurrentHashMap<>();
        subscriptions       = new ConcurrentHashMap<>();
        specialReplyWaiters = new ConcurrentHashMap<>();
        nextID              = new AtomicLong( 0 );
        cpoMailboxQueueSize = CPO_MAILBOX_SIZE_MULTIPLIER * mailboxQueueSize;
        cpoMailbox          = new Mailbox( this, CPO_MAILBOX_NAME, cpoMailboxQueueSize );
        connection          = new CPOConnection( this, cpoHost, cpoPort, secret );
        poMailbox           = createMailbox( "po" );
        mailReceiver        = new MailReceiver();

        timer.schedule( new SpecialReplyWaiterCheck(), SPECIAL_WAITER_CHECK_INTERVAL_MS, SPECIAL_WAITER_CHECK_INTERVAL_MS  );
    }


    /**
     * Shuts down this post office, including all subordinate threads.
     */
    public void shutdown() {

        // let all our threads know we're shutting down...
        shutdown = true;

        // kill our timer...
        timer.cancel();

        // shut down the central post office connection...
        connection.shutdown();

        // kill our mail receiver...
        mailReceiver.interrupt();

        LOGGER.info( "Post office " + name + " has shut down" );
    }


    /**
     * Sends the specified message to its destination.  Local messages (direct and subscribed) are received immediately by the destination mailbox's receive
     * queue.  Foreign messages (direct and subscribed) are forwarded to the central post office (CPO) via its mailbox.  The forwarding requires the message
     * to be serialized and sent over the network to the CPO, where they are deserialized, routed, and then reserialized, transmitted over the network to the
     * destination post office, deserialized, and finally delivered to the destination mailbox.  As should be evident from the preceding description, the
     * overhead and latency of foreign messages is nontrivial.  In addition, the size of serialized messages limited.  For all these reasons, care should be
     * taken in the design of communications with foreign processes.
     *
     * @param _message the message to be sent.
     */
    /* package */ void send( final Message _message ) {

        if( isNull( _message ) ) throw new IllegalArgumentException( "Missing message to send" );

        // if this a direct message...
        if( _message.isDirect() ) {

            // if the destination is local...
            if( _message.to.startsWith( prefix ) ) {

                // deliver it straight to the local mailbox...
                Mailbox dest = mailboxes.get( _message.to.substring( prefix.length() ) );

                // Discovered (the hard way) that it's possible for a message to be received before the destination mailbox is set up in a
                // mailbox client.  In this case we'll log a warning and ignore the message, instead of erroring out (original code commented out)...
                // if( isNull( dest ) ) throw new IllegalArgumentException( "Destination mailbox does not exist: " + _message.to );
                if( isNull( dest ) )
                    LOGGER.warning( "Destination mailbox does not exist: " + _message.to );
                else
                    dest.receive( _message );
            }

            // else the destination is foreign...
            else {

                // if it's one of certain message types, record the fact that we're expecting a reply...
                boolean isToPO = "po".equals( _message.to.substring( _message.to.indexOf( '.' ) + 1 ) );
                boolean isType = specialMessageTypes.contains( _message.type );
                if( isToPO && isType && !_message.isReply() )
                    specialReplyWaiters.put( _message.id, new SpecialReplyWaiter( _message, System.currentTimeMillis() ) );

                // forward it to the CPO through the CPO mailbox...
                cpoMailbox.receive( _message );
            }
        }

        // else it's a publish message, so we deliver to any subscribed mailboxes...
        else {

            String type = _message.from + "." + _message.type;
            String major = type.substring( 0, type.lastIndexOf( '.' ) );
            deliverToSubscribed( type,  _message );  // deliver to subscribers of the specific message type...
            deliverToSubscribed( major, _message );  // deliver to subscribers of all messages of a given major type...
        }
    }


    private void deliverToSubscribed( final String _index, final Message _message ) {

        // get the bucket, if there is one...
        Map<String,Mailbox> bucket = subscriptions.get( _index );
        if( isNull( bucket ) ) return;

        // send the mail to each mailbox...
        for( final Mailbox mailbox : bucket.values() ) {
            mailbox.receive( _message );
        }
    }


    /**
     * Subscribes the specified mailbox to published messages from the mailbox with the specified source mailbox ID and with the specified type.  The mailbox
     * ID can be either a fully-qualified mailbox ID (like "irrigation.io") or just the mailbox name (like "io").  The first form will work for both local and
     * foreign mailboxes.  The second form works only for local mailboxes.  Foreign mailboxes are not checked for validity, but if a specified local mailbox
     * does not exist, throws an {@link IllegalArgumentException}.  There are two valid forms for the specified type.  The first form is "(major).(minor)",
     * which subscribes to messages of just that specific type.  For example, a type of "sensor.temperature" might subscribe only to temperature messages.
     * The second form is "(major)", which subscribes to all messages of the specified major type.  For example, a type of "sensor" might subscribe to several
     * specific types of sensor messages, such as perhaps temperature, relative humidity, barometric pressure, etc.  This method should only be called from a
     * mailbox instance.  The destination mailbox ID is only needed when the subscribed mailbox is acting as a proxy for the actual destination, so the actual
     * destination must be separately specified.  This feature is generally only useful for proxying foreign messages through the central post office.
     *
     * @param _mailbox the mailbox to be subscribed to the specified messages.
     * @param _sourceMailboxID the mailbox ID for the mailbox that is the source of the messages to subscribe to.
     * @param _type the type of message to subscribe to.
     */
    /* package */ void subscribe( final Mailbox _mailbox, final String _sourceMailboxID, final String _type ) {
        manSub( true, _mailbox, _sourceMailboxID, _type );
    }


    /**
     * Unsubscribes this mailbox from published messages that were previously subscribed {@link #subscribe(Mailbox, String, String)} to with exactly
     * the specified parameters.
     *
     * @param _mailbox the mailbox to be unsubscribed from the specified messages.
     * @param _sourceMailboxID the mailbox ID for the mailbox that is the source of the messages to unsubscribe from.
     * @param _type the type of message to unsubscribe from.
     */
    /* package */ void unsubscribe( final Mailbox _mailbox, final String _sourceMailboxID, final String _type ) {
        manSub( false, _mailbox, _sourceMailboxID, _type );
    }


    // Does the work of managing subscriptions, handling both subscribe and unsubscribe (according to _subscribe parameter).
    private void manSub( final boolean _subscribe, final Mailbox _mailbox, final String _sourceMailboxID, final String _type ) {

        // get the right bucket from our subscriptions...
        String key = ensureFQMailbox( _sourceMailboxID ) + "." + _type;
        Map<String,Mailbox> bucket = subscriptions.computeIfAbsent( key, k -> new ConcurrentHashMap<>() );

        // add our new subscription...
        String destKey = _mailbox.mailboxID;
        if( _subscribe )
            bucket.put( destKey, _mailbox );
        else
            bucket.remove( destKey );

        // if the subscription source is foreign, notify the foreign post office...
        if( isForeign( key ) ) {

            String type = _subscribe ? "manage.subscribe" : "manage.unsubscribe";
            Message message = new Message( name + ".po", getPostOfficeName( key ) + ".po", type, getNextID(), null, true );
            message.put( "source",    _sourceMailboxID   );
            message.put( "type",      _type              );
            message.put( "requestor", _mailbox.mailboxID );
            send( message );
        }
    }

    // 1.  Subscriptions are indexed by the <postoffice.mailbox.major.minor> message type for subscriptions to individual message types, and by
    //     <postoffice.mailbox.major> for subscriptions to all messages of the specified major type.  For each type of subscriptions, a map of mailbox
    //     name to mailbox is maintained.  For all of these, the mailbox name is the fully-qualified <post office.mailbox>.  For local subscriptions, the
    //     mailbox is the actual local mailbox.  For foreign subscriptions, the mailbox is the central post office mailbox, which is acting as a proxy for
    //     the foreign mailbox.

    // Called after a central post office connects for the first time.  At that time, we re-subscribe to every foreign subscription we've made since
    // startup, exactly as if a mailbox made the subscription...
    /* package */ void handleSubscriptionRefresh() {

        // scan to find all our foreign subscriptions...
        String ourPO = name + ".";
        Set<Map.Entry<String,Map<String,Mailbox>>> entries = subscriptions.entrySet();
        Iterator<Map.Entry<String,Map<String,Mailbox>>> it = entries.iterator();
        while( it.hasNext() ) {

            // if this is a local subscription, just move along...
            Map.Entry<String,Map<String,Mailbox>> entry = it.next();
            String key = entry.getKey();
            if( key.startsWith( ourPO ) ) continue;

            // it's foreign, so resubscribe...
            Map<String,Mailbox> mailboxes = entry.getValue();
            for( String subscriber : mailboxes.keySet() ) {

                String type = "manage.subscribe";
                String sourcePO = getPostOfficeName( key );
                String source = key.substring( 0, key.indexOf( '.', sourcePO.length() + 1 ) );
                String subscriptionType = key.substring( source.length() + 1 );
                Message message = new Message( name + ".po", sourcePO + ".po", type, getNextID(), null, true );
                message.put( "source",    source           );
                message.put( "type",      subscriptionType );
                message.put( "requestor", subscriber       );
                send( message );
            }
        }
    }


    /**
     * Creates a new mailbox with the specified name.  If the specified name is <code>null</code> or zero length, or if a mailbox with the specified name
     * already exists, or if the specified name contains a period, or if the specified name conflicts with any reserved mailbox names, throws an
     * {@link IllegalArgumentException}.
     *
     * @param _name the name desired for the newly created mailbox.
     * @return the newly created mailbox.
     */
    public synchronized Mailbox createMailbox( final String _name ) {

        if( isEmpty( _name ) )                 throw new IllegalArgumentException( "Mailbox name is missing or zero-length" );
        if( CPO_MAILBOX_NAME.equals( _name ) ) throw new IllegalArgumentException( "Mailbox name conflicts with reserved name" );
        if( mailboxes.containsKey( _name ) )   throw new IllegalArgumentException( "Mailbox name already in use" );
        if( _name.contains( "." ) )            throw new IllegalArgumentException( "Mailbox name contains a period" );

        Mailbox mailbox = new Mailbox( this, _name, mailboxQueueSize );
        mailboxes.put( _name, mailbox );

        return mailbox;
    }


    /**
     * Returns the mailbox with the specified name, or <code>null</code> if no such mailbox exists.
     *
     * @param _name the name of the mailbox to retrieve.
     * @return the mailbox retrieved, or <code>null</code> if no such mailbox exists.
     */
    public synchronized Mailbox getMailbox( final String _name ) {
        return mailboxes.get( _name );
    }


    /**
     * Generates and returns a string ID that is unique within the scope of this post office.
     *
     * @return the generated ID.
     */
    public String getNextID() {
        return encode( nextID.incrementAndGet() ) + "." + name;
    }


    /**
     * Returns a fully-qualified mailbox ID (i.e., "postOffice.mailbox").  If the specified mailbox ID is already fully-qualified, it is simply returned.  If
     * the specified mailbox ID is just a mailbox name, the local post office name is prepended and returned (i.e., "mailbox" becomes
     * "localPostOffice.mailbox").
     *
     * @param _mailboxID the mailbox ID to make fully-qualified.
     * @return the fully-qualified mailbox ID.
     */
    /* package */ String ensureFQMailbox( final String _mailboxID ) {
        return _mailboxID.contains( "." ) ? _mailboxID : name + "." + _mailboxID;
    }


    public Mailbox getCpoMailbox() {
        return cpoMailbox;
    }


    /**
     * Returns true if this post office is connected to the central post office.
     *
     * @return true if this post office is connected to the central post office.
     */
    public boolean isConnected() {
        return connection.isConnected();
    }


    /**
     * Returns true if the specified address (po.mailbox) is the address to a foreign mailbox.
     *
     * @param _address the address to check.
     * @return true if the specified address (po.mailbox) is the address to a foreign mailbox.
     */
    public boolean isForeign( final String _address ) {

        // sanity check...
        if( isEmpty( _address ) || !_address.contains( "." ) ) throw new IllegalArgumentException( "Not a valid address" );

        return !name.equals( _address.substring( 0, _address.indexOf( '.' ) ) );
    }


    /**
     * Returns the post office name part of the specified address (e.g., the string before the period).
     *
     * @param _address the address to extract the post office name from.
     * @return the post office name part of the specified address.
     */
    public String getPostOfficeName( final String _address ) {

        // sanity check...
        if( isEmpty( _address ) || !_address.contains( "." ) ) throw new IllegalArgumentException( "Not a valid address" );

        return _address.substring( 0, _address.indexOf( '.' ) );
    }


    public byte[] getSecret() {
        return connection.getSecret();
    }


    /* package */ void killSocket() {
        connection.killSocket();
    }


    /* package */ void killWriter() {
        connection.killWriter();
    }


    /* package */ void killReader() {
        connection.killReader();
    }


    /* package */ void readTestException() {
        connection.readTestException();
    }


    /* package */ void writeTestException() {
        connection.writeTestException();
    }


    private class MailReceiver extends Thread {


        private MailReceiver() {
            setName( name + " Mail Receiver" );
            setDaemon( true );
            start();
        }


        public void run() {

            while( true ) {

                try {
                    // grab a message from our mailbox...
                    Message msg = poMailbox.take();
                    LOGGER.finest( "Received: " + msg.toString() );

                    // decide what to do with it...
                    switch( msg.type ) {
                        case "manage.subscribe":   handleSubscriptions( msg, true  ); break;
                        case "manage.unsubscribe": handleSubscriptions( msg, false ); break;
                        default: LOGGER.severe( "Unknown po message type received: " + msg.type );
                    }
                }
                catch( InterruptedException _e ) {
                    break;
                }
            }
        }


        private void handleSubscriptions( final Message _message, final boolean _isSubscribe ) {

            // if it's a reply, clear the waiter...
            if( isNotNull( _message.reply ) ) {
                specialReplyWaiters.remove( _message.reply );
                LOGGER.finest( "Removing special reply waiter: " + _message.reply );
            }

            // otherwise, it's actually a subscribe, so do it...
            else {
                String source = _message.optString( "source", null );
                String type = _message.optString( "type", null );
                if( !isEmpty( source ) && !isEmpty( type ) ) {
                    if( _isSubscribe )
                        subscribe( cpoMailbox, source, type );
                    else
                        unsubscribe( cpoMailbox, source, type );
                }

                // if a reply was requested, send it...
                if( _message.isReplyExpected() ) {
                    Message reply = poMailbox.createReplyMessage( _message, _message.type );
                    poMailbox.send( reply );
                }
            }
        }
    }


    private static class SpecialReplyWaiter {
        private final Message message; // the message that we're waiting for a reply to...
        private long sentMS;           // the system time that we sent the message...


        public SpecialReplyWaiter( final Message _message, final long _sentMS ) {
            message = _message;
            sentMS = _sentMS;
        }
    }


    private class SpecialReplyWaiterCheck extends TimerTask {

        /**
         * Check for expired special reply waiters...
         */
        @Override
        public void run() {

            long current = System.currentTimeMillis();
            for( SpecialReplyWaiter waiter : specialReplyWaiters.values() ) {

                if( SPECIAL_WAITER_EXPIRATION_MS < (current - waiter.sentMS) ) {
                    cpoMailbox.receive( waiter.message );
                    waiter.sentMS = current;
                }
            }
        }
    }
}
