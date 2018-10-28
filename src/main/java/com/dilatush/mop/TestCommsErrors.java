package com.dilatush.mop;

import com.dilatush.mop.cpo.CentralPostOffice;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;
import static java.lang.Thread.sleep;

/**
 * Runs a simple test of the MOP, with two client post offices and a central post office all running in the same process.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TestCommsErrors {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());


    public static void main( String[] args ) throws InterruptedException {

        // create our central post office and start it up...
        CentralPostOffice cpo = new CentralPostOffice( "CentralPostOfficeConfig.json" );
        cpo.start();

        // create our post offices...
        PostOffice test1 = new PostOffice( "Test1.json" );
        PostOffice test2 = new PostOffice( "Test2.json" );

        // create our test actors...
        TestActor actor1 = new TestActor( test1, 1 );
        TestActor actor2 = new TestActor( test2, 2 );

        Timer timer = new Timer( "Problem Timer", true );
        timer.schedule( new ProblemGenerator( test1, 0 ), 100 );
        timer.schedule( new ProblemGenerator( test1, 0 ), 2000 );
        timer.schedule( new ProblemGenerator( test2, 1 ), 2200 );
        timer.schedule( new ProblemGenerator( test1, 1 ), 2800 );
        timer.schedule( new ProblemGenerator( test2, 2 ), 3200 );
        timer.schedule( new ProblemGenerator( test1, 1 ), 4000 );
        timer.schedule( new ProblemGenerator( test2, 2 ), 4000 );
        timer.schedule( new ProblemGenerator( cpo, "test1", 0 ), 5000 );
        timer.schedule( new ProblemGenerator( cpo, null, 1 ), 8000 );
        timer.schedule( new ProblemGenerator( test2, 3), 16000 );
        timer.schedule( new ProblemGenerator( test1, 4), 17000 );
        timer.schedule( new ProblemGenerator( cpo, null, 2 ), 22000 );
        timer.schedule( new ProblemGenerator( cpo, null, 3 ), 24000 );

        // wait until both post offices have connected...
        while( !test1.isConnected() || !test2.isConnected() )
            sleep( 10 );
        LOGGER.info( "test1 and test2 connected" );

        // start our tests...
        actor1.start();
        actor2.start();

        sleep( 32000 );

        cpo.hashCode();
    }


    private static class ProblemGenerator extends TimerTask {

        private CentralPostOffice targetCPO;
        private PostOffice targetPO;
        private int type;
        private String poName;

        // _target is post office
        // _type is:
        //    0 for close CPOConnection socket
        //    1 for kill writer thread
        //    2 for kill reader thread
        //    3 for exception in reader thread
        //    4 for exception in writer thread
        private ProblemGenerator( final PostOffice _target, final int _type ) {
            targetPO = _target;
            targetCPO = null;
            type = _type;
        }


        // _target is central post office
        // _type is:
        //    0 for close POConnection socket
        //    1 for close server channel socket
        //    2 for exception in read handler
        //    3 for exception in write handler
        private ProblemGenerator( final CentralPostOffice _target, final String _poName, final int _type ) {
            targetCPO = _target;
            targetPO = null;
            type = _type;
            poName = _poName;
        }


        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {
            String testType = (isNull( targetCPO ) )
                    ? targetPO.name + " type: " + type
                    : "CPO type: " + type + ((isNotNull( poName )) ? " on PO " + poName : "");

            LOGGER.info( "TEST: " + testType );

            if( isNotNull( targetPO ) ) {
                switch( type ) {

                    case 0:
                        targetPO.killSocket();
                        break;

                    case 1:
                        targetPO.killWriter();
                        break;

                    case 2:
                        targetPO.killReader();
                        break;

                    case 3:
                        targetPO.readTestException();
                        break;

                    case 4:
                        targetPO.writeTestException();
                        break;

                    default:
                        break;
                }
            }

            else {
                switch( type ) {

                    case 0:
                        targetCPO.killConnection( poName );
                        break;

                    case 1:
                        targetCPO.killServerSocket();
                        break;

                    case 2:
                        targetCPO.testReadException();
                        break;

                    case 3:
                        targetCPO.testWriteException();
                        break;

                    default:
                        break;
                }
            }
        }
    }


    private static class TestActor extends Actor {

        private final int number;
        private AtomicLong lastSequenceNumber;


        public TestActor( final PostOffice _po, final int _number ) {
            super( _po, "actor" + _number );
            number = _number;
            lastSequenceNumber = new AtomicLong( 0 );  // zero means "never received"...

            registerDirectMessageHandler( this::onDirectMessage, "seq" );
        }


        public void start() {

            if( number == 1) {
                Message message = mailbox.createDirectMessage( getTo(), "seq", false );
                message.put( "seq", lastSequenceNumber.incrementAndGet() );
                mailbox.send( message );
            }
        }


        protected void onDirectMessage( final Message _message ) {

            try {
                long seq = _message.optLong( "seq", 0 );

                // if it's the first sequence number or the last one + 1...
                if( (lastSequenceNumber.get() == 0) || (seq == lastSequenceNumber.get() + 1) ) {
                    sleep( getWaitMS( seq ) );
                    Message message = mailbox.createDirectMessage( getTo(), "seq", false );
                    lastSequenceNumber.set( seq );
                    message.put( "seq", lastSequenceNumber.incrementAndGet() );
                    mailbox.send( message );
                }

                else if( seq <= lastSequenceNumber.get() ) {
                    // this is a repeated message; ignore it..
                    LOGGER.info( "Repeated message received, sequence: " + seq );
                }

                else {
                    // we missed a message - error!
                    throw new IllegalStateException( "Skipped sequence - got " + seq + " but expected " + (lastSequenceNumber.get() + 1) );
                }
            }
            catch( InterruptedException _e ) {
                throw new IllegalStateException( "Got interrupted - how?" );
            }
        }


        private String getTo() {
            return (number == 1) ? "test2.actor2" : "test1.actor1";
        }


        private int getWaitMS( final long _seq ) {

            int mod = (int)(_seq % 10);
            return 1 << mod;
        }
    }
}
