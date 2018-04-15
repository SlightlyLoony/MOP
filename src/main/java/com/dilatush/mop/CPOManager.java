package com.dilatush.mop;

import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Scanner;

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

    private static PostOffice po;
    private static ManagerActor ma;
    private static boolean quit;
    private static Scanner scanner;
    private static volatile boolean wait;

    public static void main( final String[] _args ) throws InterruptedException {

        // get the path to our configuration file...
        String config = "CPOManager.json";   // the default...
        if( isNotNull( (Object) _args ) && (_args.length > 0) )
            config = _args[0];

        // if the file doesn't exist, bail out with an error...
        if( !new File( config ).exists() ) {
            print( "Configuration file " + config + " does not exist!" );
            return;
        }

        // get our post office...
        po = new PostOffice( config );

        // get our actor...
        ma = new ManagerActor( po );

        // now loop, printing the menu and getting a command, until the user says "quit"...
        scanner = new Scanner( System.in );
        quit = false;
        while( !quit ) {

            showMenu();
            String command = scanner.nextLine();
            String[] parts = command.split( "\\s+" );
            switch( parts[0] ) {

                case "quit": handleQuit( parts ); break;
                case "status": handleStatus( parts ); break;

                default: handleError( parts ); break;
            }

            while( wait ) sleep( 100 );
        }
    }


    private static void handleError( final String[] _parts ) throws InterruptedException {
        print( "Invalid command: " + _parts[0] );
        sleep( 2000 );
    }


    private static void handleQuit( final String[] _parts ) {
        print( "Goodbye, manager!" );
        quit = true;
    }


    private static void handleStatus( final String[] _parts ) {
        print( "Sending status request..." );
        ma.requestStatus();
        wait = true;
    }


    private static void showMenu() {
        print( "quit     quit using the CPO manager" );
        print( "status   get the status of the CPO"  );
        print( ""                                    );
        System.out.print( "I await your command: "   );
    }


    private static void waitForAck() {
        System.out.print( "Press <enter> to continue..." );
        scanner.nextLine();
        wait = false;
    }


    private static void print( final String _msg ) {
        System.out.println( _msg );
    }


    private static class ManagerActor extends Actor {

        protected ManagerActor( final PostOffice _po ) {
            super( _po, "manager" );
            registerFQDirectMessageHandler( this::statusHandler, "central.po", "manage", "status" );
        }


        private void requestStatus() {
            Message msg = mailbox.createDirectMessage( "central.po", "manage.status", false );
            mailbox.send( msg );
        }



        protected void statusHandler( final Message _message ) {

            // print the status on the screen...
            print( "Status for Central Post Office " + _message.getStringDotted( "name" ) );
            print( "" );
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
                print( "    Client: " + _message.getStringDotted( prefix + "name" ) );
                print( "        Connected: " + _message.getBooleanDotted( prefix + "isConnected"   ) );
                print( "      Connections: " + _message.getLongDotted(     prefix + "connections"   ) );
                print( "   Last connected: " + _message.optStringDotted(   prefix + "lastConnected", "<never>" ) );
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
