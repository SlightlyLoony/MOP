package com.dilatush.mop;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;

/**
 * Mailboxes are the central element that MOP actors interact with.  Actors send direct messages, reply to direct messages, or publish messages through their
 * mailbox(es).  They also receive messages (both direct and subscribed) through their mailbox.  The mailbox also provides actors with a simple mechanism to
 * wait for a reply to a direct message.  Finally, mailboxes provide the interface through which actors can subscribe (or unsubscribe) to published messages.
 * An actor obtains a mailbox through its post office.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Mailbox {


    public  final PostOffice po;         // the post office this mailbox is associated with...
    public  final String     name;       // the unique name of this mailbox within this post office...
    public  final String     mailboxID;  // the unique name of this mailbox in the world...

    private final BlockingQueue<Message>             queue;      // the queue of received messages...
    private final Map<String,BlockingQueue<Message>> waiters;    // the registry of waiters waiting for reply messages (message IDs -> waiters)...


    /**
     * Creates a new mailbox instance using the specified parameters.  This method should only be called by the associated {@link PostOffice} instance.
     *
     * @param _po the post office instance this mailbox belongs to.
     * @param _name the unique name of this mailbox.
     * @param _queueSize the maximum number of messages that may be queued in the receive queue.
     */
    /*package*/ Mailbox( final PostOffice _po, final String _name, final int _queueSize ) {
        po = _po;
        name = _name;
        mailboxID = po.name + "." + name;
        queue = new ArrayBlockingQueue<>( _queueSize );
        waiters = new ConcurrentHashMap<>();
    }


    /**
     * Create a direct message that does not expect a reply, addressed to the specified mailbox.
     *
     * @param _to the mailbox ID that this message is directed to.
     * @return the newly created {@link Message} instance.
     */
    public Message createDirectMessageNotExpectingReply( final String _to ) {
        return createDirectMessage( po.ensureFQMailbox( _to ), null, false );
    }


    /**
     * Create a direct message that expects a reply, addressed to the specified mailbox.
     *
     * @param _to the mailbox ID that this message is directed to.
     * @return the newly created {@link Message} instance.
     */
    public Message createDirectMessageExpectingReply( final String _to ) {
        return createDirectMessage( po.ensureFQMailbox( _to ), null, true );
    }


    /**
     * Create a direct message addressed to the specified mailbox.  The message type (an optional string field for direct message) may be specified, or may
     * be <code>null</code>.  The expectation of a reply must be specified as true (for reply expected) or false (for reply not expected).
     *
     * @param _to the mailbox ID that this message is directed to.
     * @param _type the optional string (not necessarily an actual message type for a direct message).
     * @param _expect true for reply expected, or false for reply not expected.
     * @return the newly created {@link Message} instance.
     */
    public Message createDirectMessage( final String _to, final String _type, final boolean _expect ) {
        return Message.createDirectMessage( po.ensureFQMailbox( _to ), _type, _expect, mailboxID, po.getNextID() );
    }


    /**
     * Create a direct message in reply to the specified message.  The "from" and "to" mailboxes are automatically created from the given message.  The
     * message type (an optional string field for direct message) may be specified, or may be <code>null</code>.  The reply message will not itself expect
     * a reply.
     *
     * @param _message the message being replied to.
     * @param _type the optional string (not necessarily an actual message type for a direct message).
     * @return the newly created {@link Message} instance.
     */
    public Message createReplyMessage( final Message _message, final String _type ) {
        return Message.createReplyMessage( _message, _type, po.getNextID() );
    }


    /**
     * Create a publish message of the specified type.
     *
     * @param _type the type (major and minor type) for this message.
     * @return the newly created {@link Message} instance.
     */
    public Message createPublishMessage( final String _type ) {
        return Message.createPublishMessage( _type, mailboxID, po.getNextID() );
    }


    /**
     * Sends the specified message through the associated post office.  The message may be direct or publish, and the destination may be foreign or local.
     * In other words, any message may be sent through this method.  However, when sending messages expecting a reply,
     * {@link #sendAndWaitForReply(Message,Interval)}
     * may be a better option.
     *
     * @param _message the message to send.
     */
    public void send( final Message _message ) {
        po.send( _message );
    }


    /**
     * Sends the specified message and waits up to the specified time for a reply.  Returns the reply message, or {@code null} if no reply was received within
     * the specified wait time.
     *
     * @param _message the message to send.
     * @param _waitTime the maximum time to wait for a reply.
     * @return the reply message, or {@code null} if none was received.
     */
    public Message sendAndWaitForReply( final Message _message, final Interval _waitTime ) {

        if( isNull( _waitTime ) ) throw new IllegalArgumentException( "Missing wait time interval" );

        // create and register an instance to wait on...
        SynchronousQueue<Message> waiter = new SynchronousQueue<>();
        waiters.put( _message.id, waiter );

        // send the message...
        send( _message );

        // wait for the response...
        Message reply;
        try {
            reply = waiter.poll( _waitTime.value, _waitTime.unit );
        }
        catch( InterruptedException _e ) {
            reply = null;
        }

        // unregister our waiter...
        waiters.remove( _message.id );

        return reply;
    }


    /**
     * Accepts the specified message for forwarding through this mailbox.  Throws an {@link IllegalStateException} if the queue is full, and an
     * {@link IllegalArgumentException} if the message is missing.
     *
     * @param _message the message to be received.
     */
    /* package */ void receive( final Message _message ) {

        if( isNull( _message ) ) throw new IllegalArgumentException( "Message is missing" );

        // if we got a reply message, check for waiters...
        if( isNotNull( _message.reply ) ) {
            BlockingQueue<Message> waiter = waiters.get( _message.reply );

            // if we have a waiter, handle it that way (so the reply never makes it to the queue)...
            // note that if we get multiple replies to a message, some of those replies might make it into the queue, if they're received after the waiter
            // is deregistered...
            if( isNotNull( waiter ) ) {

                waiter.offer( _message );  // note that if we already received a reply, this just does nothing...
                return;                    // our work is done...
            }
        }

        // if we didn't handle it through a waiter, then into the queue it goes...
        // TODO: add an optional mechanism for dropping oldest messages if the queue gets full...
        queue.add( _message );
    }


    /**
     * Subscribes this mailbox to published messages from the specified mailbox and with the specified type.  The mailbox ID can be either a fully-qualified
     * mailbox ID (like "irrigation.io") or just the mailbox name (like "io").  The first form will work for both local and foreign mailboxes.  The second
     * form works only for local mailboxes.  Foreign mailboxes are not checked for validity, but if a specified local mailbox does not exist, throws an
     * {@link IllegalArgumentException}.  There are two valid forms for the specified type.  The first form is "(major).(minor)", which subscribes to messages
     * of just that specific type.  For example, a type of "sensor.temperature" might subscribe only to temperature messages.  The second form is "(major)",
     * which subscribes to all messages of the specified major type.  For example, a type of "sensor" might subscribe to several specific types of sensor
     * messages, such as perhaps temperature, relative humidity, barometric pressure, etc.
     *
     * @param _sourceMailboxID the mailbox ID for the mailbox that is the source of the messages to subscribe to.
     * @param _type the type of message to subscribe to.
     */
    public void subscribe( final String _sourceMailboxID, String _type ) {
        po.subscribe( this, _sourceMailboxID, _type );
    }


    /**
     * Unsubscribes this mailbox from published messages that were previously subscribed {@link #subscribe(String, String)} to with exactly the specified
     * parameters.
     *
     * @param _sourceMailboxID the mailbox ID for the mailbox that is the source of the messages to unsubscribe from.
     * @param _type the type of message to unsubscribe from.
     */
    public void unsubscribe( final String _sourceMailboxID, String _type ) {
        po.unsubscribe( this, _sourceMailboxID, _type );
    }


    /**
     * Encrypts the specified fields in the specified message.  The field names can be dotted hierarchical names.
     *
     * @param _msg the message to encrypt.
     * @param _fields the fields to encrypt.
     */
    public void encrypt( final Message _msg, final String... _fields ) {
        _msg.encrypt( po.getSecret(), _fields );
    }


    /**
     * Retrieves and removes the oldest message in this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the oldest message in this queue
     * @throws InterruptedException if interrupted while waiting
     */
    public Message take() throws InterruptedException {
        return queue.take();
    }


    /**
     * Retrieves and removes the oldest message in this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the {@code timeout} parameter
     * @return the oldest message in this queue, or {@code null} if the specified waiting time elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     */
    public Message poll( final long timeout, final TimeUnit unit ) throws InterruptedException {
        return queue.poll( timeout, unit );
    }


    /**
     * Retrieves and removes the oldest message in this queue.  This method differs
     * from {@link #poll poll} only in that it throws an exception if this
     * queue is empty.
     *
     * @return the oldest message in this queue
     * @throws NoSuchElementException if this queue is empty
     */
    public Message remove() {
        return queue.remove();
    }


    /**
     * Retrieves and removes the oldest message in this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the oldest message in this queue, or {@code null} if this queue is empty
     */
    public Message poll() {
        return queue.poll();
    }


    /**
     * Returns the number of messages in the queue.
     *
     * @return the number of elements in the queue.
     */
    public int size() {
        return queue.size();
    }


    /**
     * Returns <tt>true</tt> if the queue contains no messages.
     *
     * @return <tt>true</tt> if the queue contains no messages.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
