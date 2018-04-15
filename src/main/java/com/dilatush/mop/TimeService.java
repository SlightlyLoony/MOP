package com.dilatush.mop;

/**
 * Implements a message-oriented general time service that is both configured through messages and provides its outputs through messages.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TimeService extends Service {
//
//    public static final String CONFIGURE_CATEGORY    = "TimeServiceConfigure";   // category of all configuration messages...
//    public static final String TIME_MSG_CATEGORY     = "TimeService";            // category of all output messages...
//    public static final String ENABLE_PERIODIC_TYPE  = "enablePeriodic";
//    public static final String DISABLE_PERIODIC_TYPE = "disablePeriodic";
//    public static final String FQN_ENABLE_PERIODIC   = CONFIGURE_CATEGORY + "." + ENABLE_PERIODIC_TYPE;
//    public static final String FQN_DISABLE_PERIODIC  = CONFIGURE_CATEGORY + "." + DISABLE_PERIODIC_TYPE;
//
//    private static final int EXECUTOR_THREAD_POOL_SIZE = 5;
//
//    public  final Handle configurationCategoryHandle;
//    public  final Handle timeMessageCategoryHandle;
//    public  final Handle enablePeriodicTypeHandle;
//    public  final Handle disablePeriodicTypeHandle;
//    private final ScheduledThreadPoolExecutor executor;
//
//    private Map<String,ScheduledFuture<?>> futures = new HashMap<>();
//
//
//    public TimeService( final MOPContext _context ) {
//        super( _context, "Time Service" );
//
//        // register our message categories and types...
//        configurationCategoryHandle = context.registerMessageCategory( CONFIGURE_CATEGORY );
//        timeMessageCategoryHandle   = context.registerMessageCategory( TIME_MSG_CATEGORY  );
//        enablePeriodicTypeHandle    = context.registerMessageType( configurationCategoryHandle, ENABLE_PERIODIC_TYPE,  IntervalSpec.class );
//        disablePeriodicTypeHandle   = context.registerMessageType( configurationCategoryHandle, DISABLE_PERIODIC_TYPE, String.class       );
//
//        // construct our dispatch map...
//        dispatcher.put( enablePeriodicTypeHandle,  this::handleEnablePeriodic  );
//        dispatcher.put( disablePeriodicTypeHandle, this::handleDisablePeriodic );
//
//        // subscribe to configuration messages...
//        po.subscribeToMessageCategory( mailboxHandle, configurationCategoryHandle );
//
//        // set up our executor (through which all timed services execute)...
//        executor = new ScheduledThreadPoolExecutor( EXECUTOR_THREAD_POOL_SIZE );
//    }
//
//
//    @Override
//    public void onMessage( final Message _message ) {
//
//        MessageHandler messageHandler = dispatcher.get( _message.messageTypeHandle );
//        if( isNull( messageHandler ) ) throw new IllegalStateException( "Unknown message type: " + context.getMessageTypeName( _message.messageTypeHandle ) );
//        messageHandler.handle( _message );
//    }
//
//
//    private void handleEnablePeriodic( final Message _message ) {
//
//        IntervalSpec spec = (IntervalSpec) _message.contents;
//
//        // if we don't already have the requested message type registered, do so...
//        Handle ph = context.getMessageTypeHandle( timeMessageCategoryHandle, spec.name );
//        if( isNull( ph ) )
//            ph = context.registerMessageType( timeMessageCategoryHandle, spec.name, null );
//
//        ScheduledFuture<?> handle = executor.scheduleAtFixedRate( new Executor( ph ), spec.interval, spec.interval, spec.unit );
//        futures.put( spec.name, handle );
//    }
//
//
//    private void handleDisablePeriodic( final Message _message ) {
//        if( !(_message.contents instanceof String) ) throw new IllegalArgumentException( "Disable periodic message has invalid contents" );
//        String spec = (String) _message.contents;
//        ScheduledFuture<?> handle = futures.get( spec );
//        if( handle != null ) {
//            handle.cancel( false );
//            futures.remove( _message.contents );
//        }
//    }
//
//
//    private class Executor implements Runnable {
//
//        private final Handle handle;
//
//
//        private Executor( final Handle _handle ) {
//            handle = _handle;
//        }
//
//        @Override
//        public void run() {
//            po.send( handle, null );
//        }
//    }
//
//
//    public static class IntervalSpec {
//        public String name;
//        public long interval;
//        public TimeUnit unit;
//
//
//        public IntervalSpec( final String _name, final long _interval, final TimeUnit _unit ) {
//            name = _name;
//            interval = _interval;
//            unit = _unit;
//        }
//    }
}
