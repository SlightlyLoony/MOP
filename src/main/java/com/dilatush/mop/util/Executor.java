package com.dilatush.mop.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.isNull;

/**
 * Instances of this class execute a command in the local command shell and return the results.  The command to be executed must be a command that
 * terminates the process itself.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Executor {

    private final String command;
    private final String[] elements;


    public Executor( final String _command ) {
        command = _command;
        elements = command.split( "\\s+" );
    }


    /**
     * Runs this executor, returning the result (the output of the command being run) as a string.  If an error occurs, returns <code>null</code>.
     *
     * @return the result of running this executor, or <code>null</code> if there was an error.
     */
    public String run() {

        try {
            ProcessBuilder pb = new ProcessBuilder( elements );
            pb.redirectErrorStream( true );
            Process p = pb.start();
            BufferedReader br = new BufferedReader( new InputStreamReader( p.getInputStream(), StandardCharsets.US_ASCII ) );
            StringBuilder sb = new StringBuilder();
            while( true ) {
                String line = br.readLine();
                if( isNull( line ) ) break;
                sb.append( line );
                sb.append( System.lineSeparator() );
            }
            return sb.toString();
        }
        catch( IOException _e ) {
            return null;
        }
    }
}
