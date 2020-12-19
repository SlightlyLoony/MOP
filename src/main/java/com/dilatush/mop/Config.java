package com.dilatush.mop;

/**
 * Simple POJO to hold post office configuration information.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Config {

    public String name;
    public String secret;
    public int queueSize;
    public String cpoHost;
    public int cpoPort;


    /**
     * Creates a new instance of this class, with default values of 100 for the queue size and 4000 for the CPO port number.
     */
    public Config() {
        queueSize = 100;
        cpoPort   = 4000;
    }


    /**
     * Creates a new instance of this class.
     *
     * @param _name The name of this post office, which must be at least one character long and unique amongst all post offices connected to the
     *              same central post office.
     * @param _secret The secret used to encrypt messages to and from this post office and the central post office.  It must be identical to
     *                the secret for this post office that is configured on the central post office.
     * @param _queueSize The maximum number of received messages that may be queued in a mailbox.
     * @param _cpoHost The fully qualified host name (or IP address) of the central post office cpoHost.
     * @param _cpoPort The TCP port number for the central post office.
     */
    public Config( final String _name, final String _secret, final int _queueSize, final String _cpoHost, final int _cpoPort ) {
        name = _name;
        secret = _secret;
        queueSize = _queueSize;
        cpoHost = _cpoHost;
        cpoPort = _cpoPort;
    }
}
