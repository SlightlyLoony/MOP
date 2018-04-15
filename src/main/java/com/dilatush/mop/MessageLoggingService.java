package com.dilatush.mop;

/**
 * Implements a simple service that logs selected messages.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class MessageLoggingService extends Service {

//    public static final String CONFIGURE_CATEGORY   = "MsgLoggingSvcCfg";  // category of all configuration messages...
//    public static final String ENABLE_LOGGING_TYPE  = "enableLogging";
//    public static final String DISABLE_LOGGING_TYPE = "disableLogging";
//    public static final String FQN_ENABLE_LOGGING   = CONFIGURE_CATEGORY + "." + ENABLE_LOGGING_TYPE;
//    public static final String FQN_DISABLE_LOGGING  = CONFIGURE_CATEGORY + "." + DISABLE_LOGGING_TYPE;
//
//    public final Handle configurationCategoryHandle;
//    public final Handle enableLoggingTypeHandle;
//    public final Handle disableLoggingTypeHandle;
//
//    private final Handle loggingMailboxHandle;
//
//    private final Loggivator loggivator;
//
//    public MessageLoggingService( final MOPContext _context ) {
//        super( _context, "Message Logging Service" );
//
//        // register our message categories and types...
//        configurationCategoryHandle = context.registerMessageCategory( CONFIGURE_CATEGORY );
//        enableLoggingTypeHandle    = context.registerMessageType( configurationCategoryHandle, ENABLE_LOGGING_TYPE,  Handle.class );
//        disableLoggingTypeHandle   = context.registerMessageType( configurationCategoryHandle, DISABLE_LOGGING_TYPE, Handle.class );
//
//        // construct our dispatch map...
//        dispatcher.put( enableLoggingTypeHandle,  this::handleEnableLogging  );
//        dispatcher.put( disableLoggingTypeHandle, this::handleDisableLogging );
//
//        // subscribe to configuration messages...
//        po.subscribeToMessageCategory( mailboxHandle, configurationCategoryHandle );
//
//        // create our mailbox to receive messages to be logged, and run a thread to read it...
//        loggingMailboxHandle = po.createMailbox();
//        loggivator = new Loggivator();
//    }
//
//
//    @Override
//    public void onMessage( final Message _message ) {
//        MessageHandler messageHandler = dispatcher.get( _message.messageTypeHandle );
//        if( isNull( messageHandler ) ) throw new IllegalStateException( "Unknown message type: " + context.getMessageTypeName( _message.messageTypeHandle ) );
//        messageHandler.handle( _message );
//    }
//
//
//    private void handleEnableLogging( final Message _message ) {
//
//        // subscribe to category or type based on handle type...
//        Handle handle = (Handle) _message.contents;
//        if( handle instanceof MOPContext.TypeHandle )
//            po.subscribeToMessageType( loggingMailboxHandle, handle );
//        else if( handle instanceof MOPContext.CategoryHandle )
//            po.subscribeToMessageCategory( loggingMailboxHandle, handle );
//    }
//
//
//    private void handleDisableLogging( final Message _message ) {
//
//        // unsubscribe to category or type based on handle type...
//        Handle handle = (Handle) _message.contents;
//        if( handle instanceof MOPContext.TypeHandle )
//            po.unsubscribeToMessageType( loggingMailboxHandle, handle );
//        else if( handle instanceof MOPContext.CategoryHandle )
//            po.unsubscribeToMessageCategory( loggingMailboxHandle, handle );
//    }
//
//
//    private class Loggivator extends Thread {
//
//        private Loggivator() {
//            setName( name + " Logger" );
//            setDaemon( true );
//            start();
//        }
//
//
//        @Override
//        public void run() {
//
//            boolean stop = false;
//            while( !stop ) {
//
//                try {
//                    Message msg = po.take( loggingMailboxHandle );
//                    StringBuilder logLine = new StringBuilder();
//                    logLine.append( "Message: " );
//                    logLine.append( context.getMessageTypeName( msg.messageTypeHandle ) );
//                    if( isNull( msg.contents ) ) logLine.append( " (no contents)" );
//                    else logLine.append( msg.contents );
//                    Logger.log( logLine.toString() );
//                }
//                catch( Throwable _t ) {
//                    stop = true;
//                }
//
//            }
//        }
//    }
}
