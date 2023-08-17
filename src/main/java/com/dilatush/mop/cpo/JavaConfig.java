package com.dilatush.mop.cpo;


import com.dilatush.util.config.AConfig;

import java.util.List;

/**
 * Configuration POJO for the CPO application.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class JavaConfig extends AConfig {

    // our Central Post Office name...
    public String name;

    // the local interface to bind to...
    public String localAddress = "0.0.0.0";

    // the port to listen on...
    public int port = 4000;

    // the ping interval in milliseconds...
    public long pingIntervalMS = 5000;

    // the maximum message size...
    public int maxMessageSize = 5000;


    /**
     * Verify the fields of this configuration.
     */
    @Override
    public void verify( final List<String> _messages ) {
    }
}
// "echoIntervalMS":5000, "localAddress" : "0.0.0.0", "port" : 4000,"pingIntervalMS" : 5000,"maxMsgSize" : 5000,"name" : "Central Post Office"