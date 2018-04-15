package com.dilatush.mop;

import java.util.Objects;

/**
 * Base class for all handles.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public abstract class Handle {

    protected long value;


    public Handle( final long _value ) {
        value = _value;
    }


    @Override
    public boolean equals( final Object _o ) {
        if( this == _o ) return true;
        if( _o == null || getClass() != _o.getClass() ) return false;
        Handle handle = (Handle) _o;
        return value == handle.value;
    }


    @Override
    public int hashCode() {

        return Objects.hash( value );
    }
}
