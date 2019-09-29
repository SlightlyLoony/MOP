package com.dilatush.mop.cpo;

import com.dilatush.util.Files;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dilatush.util.Strings.isEmpty;

/**
 * Represents the current configuration of a central post office.  Certain components of this configuration need to be persistent; these can be read from
 * a configuration file, and written to a configuration file.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Config {

    private static final String FIELD_CLIENTS          = "clients";
    private static final String FIELD_SECRET           = "secret";
    private static final String FIELD_NAME             = "name";
    private static final String FIELD_ECHO_INTERVAL_MS = "echoIntervalMS";
    private static final String FIELD_LOCAL_ADDRESS    = "localAddress";
    private static final String FIELD_PORT             = "port";
    private static final String FIELD_MAX_MSG_SIZE     = "maxMsgSize";
    private static final String FIELD_PING_INTERVAL_MS = "pingIntervalMS";
    private static final String FIELD_MANAGER          = "manager";

    /* package */ final String        localAddress;
    /* package */ final int           port;
    /* package */ final AtomicInteger maxMessageSize;
    /* package */ final String        name;
    /* package */ final long          pingIntervalMS;

    /* package */ long echoIntervalMS;
    /* package */ Map<String,POClient> clients;  // the state known of client post offices, post office name -> client...


    private Config( final String _name, final long _echoIntervalMS, final String _localAddress, final int _port, final int _maxMessageSize,
                    final long _pingIntervalMS, final Map<String,POClient> _clients  ) {

        echoIntervalMS = _echoIntervalMS;
        clients        = _clients;
        localAddress   = _localAddress;
        port           = _port;
        name           = _name;
        maxMessageSize = new AtomicInteger( _maxMessageSize );
        pingIntervalMS = _pingIntervalMS;
    }


    public static Config initializeConfig( final String _configFilePath ) {

        // read the file into a JSON object...
        if( isEmpty( _configFilePath ) ) throw new IllegalArgumentException( "Configuration file path missing or empty" );
        String json = Files.readToString( new File( _configFilePath ) );
        if( isEmpty( json ) ) throw new IllegalArgumentException( "Configuration file missing or empty: " + _configFilePath );
        JSONObject config = new JSONObject( json );

        // read in our persistent fields...
        long echoIntervalMS          = config.getLong(      FIELD_ECHO_INTERVAL_MS );
        String localAddress          = config.getString(    FIELD_LOCAL_ADDRESS    );
        int port                     = config.getInt(       FIELD_PORT             );
        long pingIntervalMS          = config.getInt(       FIELD_PING_INTERVAL_MS );
        String cpoName               = config.getString(    FIELD_NAME             );
        int maxMsgSize               = config.getInt(       FIELD_MAX_MSG_SIZE     );
        JSONArray jsonClients        = config.getJSONArray( FIELD_CLIENTS          );
        Map<String,POClient> clients = new ConcurrentHashMap<>();
        for( int i = 0; i < jsonClients.length(); i++ ) {
            JSONObject jsonClient = jsonClients.getJSONObject( i );
            String name = jsonClient.getString( FIELD_NAME );
            String secretBase64 = jsonClient.getString( FIELD_SECRET );
            POClient po = new POClient( name, secretBase64 );
            if( jsonClient.optBoolean( FIELD_MANAGER, false ) )
                po.setManager( true );
            clients.put( name, po );
        }

        return new Config( cpoName, echoIntervalMS, localAddress, port, maxMsgSize, pingIntervalMS, clients );
    }


    public void write( final String _configFilePath ) {

        // create a JSON object that contains our persistent configuration information...
        JSONObject jsonConfig = new JSONObject();
        JSONArray  jsonClients     = new JSONArray();
        jsonConfig.put( FIELD_ECHO_INTERVAL_MS, echoIntervalMS );
        jsonConfig.put( FIELD_PORT, port );
        jsonConfig.put( FIELD_NAME, name );
        jsonConfig.put( FIELD_MAX_MSG_SIZE, maxMessageSize );
        jsonConfig.put( FIELD_PING_INTERVAL_MS, pingIntervalMS );
        jsonConfig.put( FIELD_LOCAL_ADDRESS, localAddress );
        jsonConfig.put( FIELD_CLIENTS, jsonClients );
        Collection<POClient> clientSet = clients.values();
        for( POClient po : clientSet ) {
            JSONObject jsonClient = new JSONObject();
            jsonClient.put( FIELD_NAME, po.name );
            jsonClient.put( FIELD_SECRET, po.secretBase64 );
            jsonClient.put( FIELD_MANAGER, po.manager );
            jsonClients.put( jsonClient );
        }

        // then stringify it and write it to a file...
        Files.writeToFile( new File( _configFilePath ), jsonConfig.toString() );
    }
}
