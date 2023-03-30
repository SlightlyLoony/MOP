package com.dilatush.mop;

import com.dilatush.util.Base64;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

import static com.dilatush.util.General.isNotNull;
import static java.lang.Thread.sleep;

/**
 * Implements a manager for the central post office.  Accepts the following arguments on the command line:
 * <ol>
 *     <li>post office configuration file path (default is "CPOManager.json")</li>
 * </ol>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CPOManager {

    private static ManagerActor ma;
    private static boolean quit;
    private static boolean monitor;
    private static BufferedReader reader;
    private static Semaphore waiter;

    public static void main( final String[] _args ) throws Exception {

        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );

        // get the path to our configuration file...
        String config = "CPOManager.json";   // the default...
        if( isNotNull( (Object) _args ) && (_args.length > 0) )
            config = _args[0];

        // if we have "monitor" as the second argument, record that fact...
        monitor = (_args.length >= 2) && ("monitor".equals( _args[1] ) );

        // if the file doesn't exist, bail out with an error...
        if( !new File( config ).exists() ) {
            print( "Configuration file " + config + " does not exist!" );
            return;
        }

        // get our post office...
        PostOffice po = new PostOffice( config );

        // get our actor...
        ma = new ManagerActor( po );

        // if we're monitoring, get a message, wait for the response, and exit...
        if( monitor ) {
            ma.getStatus();
            System.exit( 0 );
        }

        // now loop, printing the menu and getting a command, until the user says "quit"...
        reader = new BufferedReader( new InputStreamReader( System.in ) );
        quit = false;
        waiter = new Semaphore( 1 );  // start out with a permit, as
        while( !quit ) {

            // wait until the previous command has finished...
            waiter.acquire();

            showMenu();
            String command = reader.readLine();
            String[] parts = command.split( "\\s+" );
            switch( parts[ 0 ] ) {
                case "quit"      -> handleQuit( parts );
                case "status"    -> handleStatus( parts );
                case "write"     -> handleWrite( parts );
                case "delete"    -> handleDelete( parts );
                case "add"       -> handleAdd( parts );
                case "monitor"   -> handleMonitor( parts );
                case "connected" -> handleConnected( parts );
                case "help"      -> handleHelp( parts );
                default          -> handleError( parts );
            }
        }
    }


    private static void handleDelete( final String[] _parts ) {

        // first some sanity checking on the post office name...
        if( _parts.length < 2 ) {
            err( "You must specify a post office name (for the post office you're deleting)!" );
            return;
        }
        String po = _parts[1];
        if( po.contains( "." ) ) {
            err( "The post office name may not contain a period!" );
            return;
        }

        // send the message to the CPO and wait for the acknowledgement...
        ma.requestDelete( po );
    }


    @SuppressWarnings( "unused" )
    private static void handleMonitor( final String[] _parts ) {
        ma.requestMonitor();
    }


    private static void handleAdd( final String[] _parts ) throws IOException {

        // first some sanity checking on the post office name...
        if( _parts.length < 2 ) {
            err( "You must specify a post office name (for the post office you're adding)!" );
            return;
        }
        String po = _parts[1];
        if( po.contains( "." ) ) {
            err( "The post office name may not contain a period!" );
            return;
        }

        // if a shared secret was not specified, create one...
        String secret;
        if( _parts.length < 3 ) {

            // first get some random characters and a random-ish time...
            long startTime = System.nanoTime();
            System.out.print( "Enter some random characters, followed by <enter>: " );
            String random = reader.readLine();
            long stopTime = System.nanoTime();

            // get our random-ish bytes all collected...
            byte[] chars = random.getBytes( StandardCharsets.UTF_8 );
            ByteBuffer bb = ByteBuffer.allocate( 16 + chars.length );
            bb.put( chars );
            bb.putLong( System.currentTimeMillis() );
            bb.putLong( stopTime - startTime );

            // then hash all this to make a nice, shiny, new shared secret...
            MessageDigest md;
            try {
                md = MessageDigest.getInstance( "SHA-256" );
            }
            catch( NoSuchAlgorithmException _e ) {
                err( "SHA-256 is not supported on this machine." );
                return;
            }
            byte[] hash = md.digest( bb.array() );
            secret = Base64.encode( hash );

            // show the user our secret...
            print( "Generated secret: " + secret );
            print( "NOTE: Copy this to the new post office's configuration file..." );
            waitForAck();
        }

        // otherwise, validate the specified shared secret...
        else {
            secret = _parts[2];
            try {
                Base64.decodeBytes( secret );
            }
            catch( Exception _e ) {
                err( "The specified shared secret is not valid base64." );
                return;
            }
        }

        // send the message to the CPO and wait for the acknowledgement...
        ma.requestAdd( po, secret );
    }


    private static void err( final String _message ) {
        print( "ERROR: " + _message );
        waitForAck();
    }


    private static void handleHelp( final String[] _parts ) {

        String helpOn = (_parts.length > 1) ? _parts[1] : "";
        switch( helpOn ) {
            case "quit"      -> handleHelpQuit();
            case "help"      -> handleHelpHelp();
            case "status"    -> handleHelpStatus();
            case "write"     -> handleHelpWrite();
            case "add"       -> handleHelpAdd();
            case "delete"    -> handleHelpDelete();
            case "monitor"   -> handleHelpMonitor();
            case "connected" -> handleHelpConnected();
            default -> {
                handleHelpQuit();
                handleHelpHelp();
                handleHelpStatus();
                handleHelpWrite();
                handleHelpAdd();
                handleHelpDelete();
                handleHelpMonitor();
                handleHelpConnected();
            }
        }
        waitForAck();
    }


    private static void handleHelpMonitor() {
        print( "" );
        print( "monitor      Requests a monitor report from the Central Post Office, which has information " );
        print( "             about the operating system and Java virtual machine that the Central Post" );
        print( "             Office is running on." );
    }


    private static void handleHelpConnected() {
        print( "" );
        print( "connected    Requests a list of the post offices that are currently connected to the " );
        print( "             Central Post Office." );
    }


    private static void handleHelpHelp() {
        print( "" );
        print( "help <cmd>   Get help on all of the CPOM's commands, or just the optionally specified one." );
    }


    private static void handleHelpQuit() {
        print( "" );
        print( "quit         Quit the CPOM (back to the command line)." );
    }


    private static void handleHelpStatus() {
        print( "" );
        print( "status       Prints the detailed current status of the Central Post Office." );
    }


    private static void handleHelpAdd() {
        print( "" );
        print( "add <po>     Adds a new Post Office with the specified name to the Central Post Office.  The" );
        print( "             shared secret (in base64) may be specified after the Post Office name.  If it is" );
        print( "             not specified, then a shared secret will be generated and displayed so that it" );
        print( "             may be copied to the Post Office's configuration.  The newly added Post Office" );
        print( "             will be usable immediately, but the Central Post Office's configuration will not" );
        print( "             be permanently altered until it is written (with the \"write\" command).  Note " );
        print( "             that if the specified post office name already exists, its configuration will be" );
        print( "             overwritten and lost." );
    }


    private static void handleHelpDelete() {
        print( "" );
        print( "delete <po>  Deletes an existing Post Office with the specified name from the Central Post" );
        print( "             Office.  The deleted Post Office will unusable immediately, but the Central Post" );
        print( "             Office's configuration will not be permanently altered until it is written (with" );
        print( "             \"write\" command)." );
    }


    private static void handleHelpWrite() {
        print( "" );
        print( "write        Writes the current Central Post Office configuration to its configuration file," );
        print( "             so that it will be restored if the Central Post Office is restarted." );
    }


    @SuppressWarnings( "unused" )
    private static void handleWrite( final String[] _parts ) {
        print( "Sending write configuration file request..." );
        ma.requestWrite();
    }


    private static void handleError( final String[] _parts ) {
        print( "Invalid command: " + _parts[0] );
        waitForAck();
    }


    @SuppressWarnings( "unused" )
    private static void handleQuit( final String[] _parts ) {
        print( "Goodbye, manager!" );
        quit = true;
    }


    @SuppressWarnings( "unused" )
    private static void handleConnected( final String[] _parts ) {
        print( "Sending connected request..." );
        ma.requestConnected();
    }


    @SuppressWarnings( "unused" )
    private static void handleStatus( final String[] _parts ) {
        print( "Sending status request..." );
        ma.requestStatus();
    }


    private static void showMenu() {
        print( "" );
        print( "" );
        print( "Central Post Office Manager (CPOM) commands: "          );
        print( ""                                                       );
        print( "status       get the status of the CPO"                 );
        print( "write        write the configuration file on the CPO"   );
        print( "delete <po>  delete the given Post Office from the CPO" );
        print( "add <po>     add the given Post Office to the CPO"      );
        print( "monitor      requests a monitor report from the CPO"    );
        print( "connected    requests a list of connected Post Offices" );
        print( "help <cmd>   get some help on using the CPOM"           );
        print( "quit         quit the CPOM"                             );
        print( ""                                                       );
        System.out.print( "I await your command: "                      );
    }


    private static void waitForAck() {
        System.out.print( "Press <enter> to continue..." );
        try {
            reader.readLine();
        }
        catch( IOException _e ) {
            print( "Exception when waiting for <enter>" );
        }
        waiter.release();
    }


    private static void print( final String _msg ) {
        System.out.println( _msg );
    }


    private static class ManagerActor extends Actor {

        protected static volatile boolean gotStatus;

        protected ManagerActor( final PostOffice _po ) {
            super( _po, "manager" );
            registerFQDirectMessageHandler( this::statusHandler,    "central.po", "manage", "status"    );
            registerFQDirectMessageHandler( this::writeHandler,     "central.po", "manage", "write"     );
            registerFQDirectMessageHandler( this::addHandler,       "central.po", "manage", "add"       );
            registerFQDirectMessageHandler( this::deleteHandler,    "central.po", "manage", "delete"    );
            registerFQDirectMessageHandler( this::monitorHandler,   "central.po", "manage", "monitor"   );
            registerFQDirectMessageHandler( this::connectedHandler, "central.po", "manage", "connected" );
        }


        private void getStatus() {

            // request our CPO's status...
            gotStatus = false;
            Message msg = mailbox.createDirectMessage( "central.po", "manage.status", false );
            mailbox.send( msg );

            // now wait up to one second until we get the response...
            int count = 0;
            while( !gotStatus && (count < 100) ) {
                count++;
                try {
                    //noinspection BusyWait
                    sleep( 10 );
                }
                catch( InterruptedException _e ) {
                    // naught to do...
                }
            }
            print( gotStatus ? "OK" : "DEAD" );
        }


        private void requestStatus() {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.status", false );
            mailbox.send( msg );
        }


        private void requestConnected() {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.connected", false );
            mailbox.send( msg );
        }


        private void requestWrite() {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.write", false );
            mailbox.send( msg );
        }


        private void requestAdd( final String _po, final String _secret ) {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.add", false );
            msg.put( "name",   _po     );
            msg.put( "secret", _secret );
            mailbox.encrypt( msg, "name", "secret" );
            mailbox.send( msg );
        }


        private void requestDelete( final String _po ) {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.delete", false );
            msg.put( "name", _po );
            mailbox.encrypt( msg, "name" );
            mailbox.send( msg );
        }


        private void requestMonitor() {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.monitor", false );
            mailbox.send( msg );
        }


        protected void writeHandler( final Message _message ) {
            print( "CPO configuration file written!" );
            waitForAck();
        }


        protected void deleteHandler( final Message _message ) {
            print( "Post office deleted!" );
            print( "NOTE: Don't forget to write the configuration file when you're finished adding and deleting post offices...");
            waitForAck();
        }


        protected void monitorHandler( final Message _message ) {
            print( "" );
            print( "Monitor results for Central Post Office:" );
            print( "  Operating system: " );
            if( _message.optBooleanDotted( "monitor.os.valid", false ) ) {
                print( "             OS: " + _message.getStringDotted( "monitor.os.os" ) );
                print( "      Host name: " + _message.getStringDotted( "monitor.os.hostName" ) );
                print( "      Total RAM: " + _message.getLongDotted( "monitor.os.totalMemory" ) + " bytes" );
                print( "       Free RAM: " + _message.getLongDotted( "monitor.os.freeMemory" ) + " bytes" );
                print( "       CPU busy: " + _message.getFloatDotted( "monitor.os.cpuBusyPct" ) + "%" );
            }
            else {
                print( "    Invalid OS monitoring results: " + _message.optStringDotted( "monitor.os.errorMessage", "(no message)" ) );
            }
            print( "  Java Virtual Machine:" );
            print( "           Used RAM: " + _message.getLongDotted( "monitor.jvm.usedBytes" ) + " bytes" );
            print( "            Max RAM: " + _message.getLongDotted( "monitor.jvm.maxBytes" ) + " bytes" );
            print( "            Threads: " + _message.getIntDotted( "monitor.jvm.totalThreads" ) );
            print( "    Running Threads: " + _message.getIntDotted( "monitor.jvm.runningThreads" ) );
            waitForAck();
        }


        protected void connectedHandler( final Message _message ) {
            print( "" );
            print( "Connected post offices: " + _message.getStringDotted( "postOffices" ) );
            waitForAck();
        }


        protected void addHandler( final Message _message ) {
            print( "New post office added!" );
            print( "NOTE: Don't forget to write the configuration file when you're finished adding and deleting post offices...");
            waitForAck();
        }


        protected void statusHandler( final Message _message ) {

            if( monitor ) {
                gotStatus = true;
                return;
            }

            DecimalFormat df = new DecimalFormat( "#,##0.000" );

            // print the status on the screen...
            print( "" );
            print( "Status for Central Post Office " + _message.getStringDotted( "name" ) );
            print( "" );
            print( "                Started: " + _message.getStringDotted( "started" ) );
            print( "          Uptime (days): " + df.format( _message.getDoubleDotted( "upDays" ) ) );
            print( "      Number of clients: " + _message.getLongDotted(   "numClients" ) );
            print( "  Number of connections: " + _message.getLongDotted(   "numConnections" ) );
            print( "       Max message size: " + _message.getLongDotted(   "maxMessageSize" ) );
            print( "     Ping interval (ms): " + _message.getLongDotted(   "pingIntervalMS" ) );
            print( "               TCP port: " + _message.getLongDotted(   "port" ) );
            print( "       Local IP address: " + _message.getStringDotted( "localAddress" ) );
            print( "" );
            print( "  Clients:" );

            JSONObject clients = _message.getJSONObject( "clients" );
            Iterator<String> it = clients.keys();
            while( it.hasNext() ) {

                String key = it.next();
                String prefix = "clients." + key + ".";
                print( "" );
                print( "           Client: " + _message.getStringDotted(   prefix + "name"          ) );
                print( "          Manager: " + _message.getBooleanDotted( prefix + "manager"       ) );
                print( "        Connected: " + _message.getBooleanDotted( prefix + "isConnected"   ) );
                print( "   Last connected: " + _message.optStringDotted(   prefix + "lastConnected", "<never>" ) );
                print( "    Uptime (days): " + df.format( _message.optDoubleDotted(   prefix + "upDays", 0 ) ) );
                print( "      Connections: " + _message.getLongDotted(     prefix + "connections"   ) );
                print( "           Secret: " + _message.getStringDotted(   prefix + "secret"        ) );
                print( "         RX bytes: " + _message.getLongDotted(     prefix + "rxBytes"       ) );
                print( "         TX bytes: " + _message.getLongDotted(     prefix + "txBytes"       ) );
                print( "      RX messages: " + _message.getLongDotted(     prefix + "rxMessages"    ) );
                print( "      TX messages: " + _message.getLongDotted(     prefix + "txMessages"    ) );
            }

            waitForAck();
        }
    }
}
