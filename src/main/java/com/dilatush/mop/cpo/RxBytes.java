package com.dilatush.mop.cpo;

import java.nio.ByteBuffer;

/**
 * A simple immutable container class for bytes received on a particular channel...
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class RxBytes {

    public final POConnection connection;
    public final ByteBuffer   buffer;


    public RxBytes( final POConnection _connection, final ByteBuffer _buffer ) {
        connection = _connection;
        buffer = _buffer;
    }
}
