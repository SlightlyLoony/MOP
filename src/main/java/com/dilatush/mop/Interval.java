package com.dilatush.mop;

import java.util.concurrent.TimeUnit;

import static com.dilatush.util.General.isNull;

/**
 * Simple immutable container for specifying an interval.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Interval {

    public final long value;
    public final TimeUnit unit;


    public Interval( final long _value, final TimeUnit _unit ) {

        if( (_value < 1) )    throw new IllegalArgumentException( "Invalid interval value: " + _value );
        if( isNull( _unit ) ) throw new IllegalArgumentException( "Missing time unit" );

        value = _value;
        unit = _unit;
    }
}
