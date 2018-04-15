package com.dilatush.mop;

/**
 * Provides the context required by this message-oriented programming framework.  Normally only one instance of this class exists in a JVM.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class MOPContext {
//
//    public  static final String WILDCARD = "*";
//
//    public  final PostOffice po;
//
//    private final Map<CategoryHandle,String> handles2categories;
//    private final Map<String,CategoryHandle> categories2handles;
//    private final Map<TypeHandle,String>     handles2types;
//    private final Map<String,TypeHandle>     types2handles;
//    private final Map<TypeHandle,Class>      types2contentsTypes;
//    private final Map<String,Service>        services;
//
//    private long handleValue = 0;
//
//
//    /**
//     * Creates a new instance of this class with a post office having the specified send queue size and mailboxes size.
//     *
//     * @param _sendQueueSize the maximum number of unsent messages allowed in the send queue.
//     * @param _mailboxSize the maximum number of unreceived messages allowed in post office mailboxes.
//     */
//    public MOPContext( final int _sendQueueSize, final int _mailboxSize ) {
//
//        handles2categories  = new HashMap<>();
//        categories2handles  = new HashMap<>();
//        handles2types       = new HashMap<>();
//        types2handles       = new HashMap<>();
//        types2contentsTypes = new HashMap<>();
//        services            = new HashMap<>();
//
//        po = new PostOffice( this, _sendQueueSize, _mailboxSize );
//    }
//
//
//    /**
//     * Adds the specified service to the internal map of services, thereby maintaining a reference to the service so that it needn't be referenced anywhere
//     * else.  Services may be retrieved by name.
//     *
//     * @param _service the service to be registered.
//     */
//    synchronized public void registerService( final Service _service ) {
//        if( isNull( _service ) ) throw new IllegalArgumentException( "Missing service" );
//        if( isEmpty( _service.getServiceName() ) ) throw new IllegalArgumentException( "Missing name" );
//        services.put( _service.getServiceName(), _service );
//    }
//
//
//    /**
//     * Retrieves a previously registered service with the specified name.  Returns <code>null</code> if there is no registerd service by that name.
//     *
//     * @param _name the name of the service to retrieve.
//     * @return the service retrieved.
//     */
//    synchronized public Service getService( final String _name ) {
//        if( isEmpty( _name ) ) throw new IllegalArgumentException( "Missing name" );
//        return services.get( _name );
//    }
//
//
//    /**
//     * Registers the specified string as a message category name, returning a handle to that name.  If the specified name is <code>null</code>, zero-length,
//     * equal to the wildcard category, or not unique, throws a {@link IllegalArgumentException}.
//     *
//     * @param _name the name of the message category name to be registered.
//     * @return the handle of the message category registered.
//     */
//    synchronized public Handle registerMessageCategory( final String _name ) {
//
//        // validate the name...
//        if( isEmpty( _name ) || WILDCARD.equals( _name ) || categories2handles.containsKey( _name ) )
//            throw new IllegalArgumentException( "Attempted to register null, zero-length, wild card, or non-unique category name: " + _name );
//
//        // all is ok, so create a new handle and update our registries...
//        CategoryHandle handle = new CategoryHandle( handleValue++ );
//        handles2categories.put( handle, _name );
//        categories2handles.put( _name, handle );
//
//        return handle;
//    }
//
//
//    /**
//     * Returns the handle associated with the specified category name, or <code>null</code> if there is none.
//     *
//     * @param _name the name of the message category to get a handle for.
//     * @return the handle associated with the specified category name, or <code>null</code> if there is none.
//     */
//    synchronized public Handle getMessageCategoryHandle( final String _name ) {
//        return categories2handles.get( _name );
//    }
//
//
//    /**
//     * Returns the message category name associated with the specified handle, or <code>null</code> if the specified handle does not exist or is not a
//     * message category handle.
//     *
//     * @param _categoryHandle the category handle to look up a category name for.
//     * @return the category name associated with the specified handle, or <code>null</code> if the specified handle does not exist or is not a category
//     * handle.
//     */
//    synchronized public String getMessageCategoryName( final Handle _categoryHandle ) {
//        if( !(_categoryHandle instanceof CategoryHandle) ) return null;
//        return handles2categories.get( _categoryHandle );
//    }
//
//
//    /**
//     * Registers the specified message category handle and message type name, returning a handle to that fully-qualified name.  Also registers the contents
//     * type (as a {@link Class}) for the registered message type.  If the specified message category handle is invalid, or if the specified name is
//     * <code>null</code>, zero-length, equal to the wildcard character, or not unique, throws a {@link IllegalArgumentException}.
//     *
//     * @param _categoryHandle the message category handle for the category of the message type being registered.
//     * @param _simpleName the name of the message type being registered.
//     * @param _contentsType the class representing the type of this message type's contents (which may be <code>null</code> if there are no contents).
//     * @return the handle of the message type registered.
//     */
//    synchronized public Handle registerMessageType( final Handle _categoryHandle, final String _simpleName, final Class _contentsType ) {
//
//        String fqName = getFQMessageTypeName( _categoryHandle, _simpleName );
//        if( types2handles.containsKey( _simpleName ) )
//            throw new IllegalArgumentException( "Attempted to register non-unique message type name: " + _simpleName );
//
//        // all is ok, so create a new handle and update our registries...
//        TypeHandle handle = new TypeHandle( (CategoryHandle) _categoryHandle, handleValue++ );
//        handles2types.put( handle, fqName );
//        types2handles.put( fqName, handle );
//        types2contentsTypes.put( handle, _contentsType );
//
//        return handle;
//    }
//
//
//    /**
//     * Returns the message type handle associated with the specified category handle and simple message type name, or <code>null</code> if there is none.
//     *
//     * @param _categoryHandle the message category handle for the category the specified simple message type name belongs to.
//     * @param _simpleName the unqualified message type name to retrieve a handle for.
//     * @return the handle associated with the specified category and message type name, or <code>null</code> if there is none.
//     */
//    synchronized public Handle getMessageTypeHandle( final Handle _categoryHandle, final String _simpleName ) {
//        return categories2handles.get( getFQMessageTypeName( _categoryHandle, _simpleName ) );
//    }
//
//
//    /**
//     * Returns the handle associated with the specified fully-qualified message type name, or <code>null</code> if there is none.  A fully-qualified message
//     * type name consists of the message category name, a dot ("."), and the message type name.  For example, the string "Weather.Temperature" is the
//     * fully-qualified message type name representing the message type "Temperature" within the message category "Weather".
//     *
//     * @param _fqName the name of the message type to get a handle for.
//     * @return the handle associated with the specified message type name, or <code>null</code> if there is none.
//     */
//    synchronized public Handle getMessageTypeHandle( final String _fqName ) {
//        return categories2handles.get( _fqName );
//    }
//
//
//    /**
//     * Returns the fully-qualified message type name associated with the specified messagte type handle, or <code>null</code> if the specified handle does
//     * not exist or is not a category handle.  A fully-qualified message type name consists of the message category name, a dot ("."), and the message type
//     * name.  For example, the string "Weather.Temperature" is the fully-qualified message type name representing the message type "Temperature" within the
//     * message category "Weather".
//     *
//     * @param _messageTypeHandle the category handle to look up a category name for.
//     * @return the message type name associated with the specified handle, or <code>null</code> if the specified handle does not exist or is not a message
//     *         type handle.
//     */
//    synchronized public String getMessageTypeName( final Handle _messageTypeHandle ) {
//        if( !(_messageTypeHandle instanceof TypeHandle) ) return null;
//        String fqName = handles2types.get( _messageTypeHandle );
//        return fqName.substring( fqName.indexOf( '.' ) + 1 );
//    }
//
//
//    /**
//     * Returns the simple message type name associated with the specified handle, or <code>null</code> if the specified handle does not exist or is not a
//     * message type handle.
//     *
//     * @param _messageTypeHandle the message type handle to look up a simple message name for.
//     * @return the simple message type name associated with the specified handle, or <code>null</code> if the specified handle does not exist or is not a
//     *         message type handle.
//     */
//    synchronized public String getMessageTypeSimpleName( final Handle _messageTypeHandle ) {
//        if( !(_messageTypeHandle instanceof CategoryHandle) ) return null;
//        return handles2categories.get( _messageTypeHandle );
//    }
//
//
//    /**
//     * Returns a {@link Class} instance representing the type of contents for the specified message type, or <code>null</code> if the specified message type
//     * has no contents.
//     *
//     * @param _messageTypeHandle the message type handle for the message type to retrieve the contents type for.
//     * @return the {@link Class} instance representing the type of contents for the specified message type, or <code>null</code> if the specified message type
//     *         has no contents.
//     */
//    synchronized public Class getMessageContentsType( final Handle _messageTypeHandle ) {
//        if( !(_messageTypeHandle instanceof TypeHandle) || !types2contentsTypes.containsKey( _messageTypeHandle ) )
//            throw new IllegalArgumentException( "Invalid message type handle" );
//
//        return types2contentsTypes.get( _messageTypeHandle );
//    }
//
//
//    private String getFQMessageTypeName( final Handle _categoryHandle, final String _simpleName ) {
//        validate( _categoryHandle, _simpleName );
//        return handles2categories.get( (CategoryHandle) _categoryHandle ) + "." + _simpleName;
//    }
//
//
//    private void validate( final Handle _categoryHandle, final String _simpleName ) {
//
//        if( !(_categoryHandle instanceof CategoryHandle) ) throw new IllegalArgumentException( "Invalid category handle" );
//        if( isEmpty( _simpleName ) ) throw new IllegalArgumentException( "Null or zero-length simple type name" );
//        if( WILDCARD.equals( _simpleName ) ) throw new IllegalArgumentException( "Simple type name is the wildcard (\"*\")" );
//    }
//
//
//    protected static class CategoryHandle extends Handle {
//        private CategoryHandle( final long _value ) {
//            super( _value );
//        }
//    }
//
//
//    protected static class TypeHandle extends Handle {
//
//        public final CategoryHandle categoryHandle;
//
//        private TypeHandle( final CategoryHandle _categoryHandle, final long _value ) {
//            super( _value );
//            categoryHandle = _categoryHandle;
//        }
//
//
//        @Override
//        public boolean equals( final Object _o ) {
//            if( this == _o ) return true;
//            if( _o == null || getClass() != _o.getClass() ) return false;
//            if( !super.equals( _o ) ) return false;
//            TypeHandle that = (TypeHandle) _o;
//            return Objects.equals( categoryHandle, that.categoryHandle );
//        }
//
//
//        @Override
//        public int hashCode() {
//
//            return Objects.hash( super.hashCode(), categoryHandle );
//        }
//    }
}
