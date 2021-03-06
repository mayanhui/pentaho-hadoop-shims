/*******************************************************************************
*
* Pentaho Big Data
*
* Copyright (C) 2002-2014 by Pentaho : http://www.pentaho.com
*
*******************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
******************************************************************************/

package org.pentaho.hadoop.shim.mapr31.authorization;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.commons.lang.ClassUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Logger;
import org.pentaho.di.core.auth.core.AuthenticationConsumptionException;

public class UserSpoofingMaprInvocationHandler<T> implements InvocationHandler {
  private static final Logger logger = Logger.getLogger( UserSpoofingMaprInvocationHandler.class );
  private final T delegate;
  private final Set<Class<?>> interfacesToDelegate;
  private final Set<Method> methodsWithoutGetUser;
  private final String user;
  private final boolean isRoot;

  public UserSpoofingMaprInvocationHandler( T delegate ) {
    this( delegate, new HashSet<Class<?>>(), null, false );
  }

  public UserSpoofingMaprInvocationHandler( T delegate, Set<Class<?>> interfacesToDelegate ) {
    this( delegate, interfacesToDelegate, null, false );
  }

  public UserSpoofingMaprInvocationHandler( T delegate, Set<Class<?>> interfacesToDelegate, String user, boolean isRoot ) {
    this.delegate = delegate;
    this.interfacesToDelegate = interfacesToDelegate;
    this.user = user;
    this.methodsWithoutGetUser = new HashSet<Method>();
    this.isRoot = isRoot;
  }

  public static <T> T forObject( T delegate, Set<Class<?>> interfacesToDelegate ) {
    return forObject( delegate, interfacesToDelegate, null, false );
  }

  @SuppressWarnings( "unchecked" )
  public static <T> T forObject( T delegate, Set<Class<?>> interfacesToDelegate, String user, boolean isRoot ) {
    return (T) Proxy.newProxyInstance( delegate.getClass().getClassLoader(), (Class<?>[]) ClassUtils.getAllInterfaces(
        delegate.getClass() ).toArray( new Class<?>[] {} ), new UserSpoofingMaprInvocationHandler<Object>( delegate,
        interfacesToDelegate, user, isRoot ) );
  }

  public static <T> T forObject( final Callable<T> delegateCallable, Set<Class<?>> interfacesToDelegate, String user,
      boolean isRoot ) throws AuthenticationConsumptionException {
    T delegate;
    try {
      delegate = runAsUser( new PrivilegedExceptionAction<T>() {

        @Override
        public T run() throws Exception {
          return delegateCallable.call();
        }
      }, user, isRoot );
    } catch ( Throwable e ) {
      if ( !( e instanceof Exception ) ) {
        e = new Exception( e );
      }
      throw new AuthenticationConsumptionException( (Exception) e );
    }
    return forObject( delegate, interfacesToDelegate, user, isRoot );
  }

  private Method getMethodForUserName( Method originalMethod ) {
    if ( !methodsWithoutGetUser.contains( originalMethod ) ) {
      try {
        return delegate.getClass().getMethod( originalMethod.getName() + "GetUser", originalMethod.getParameterTypes() );
      } catch ( Exception e ) {
        methodsWithoutGetUser.add( originalMethod );
      }
    }
    return null;
  }

  private static <T> T runAsUser( PrivilegedExceptionAction<T> action, String user, boolean isRoot ) throws Throwable {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader( UserSpoofingMaprInvocationHandler.class.getClassLoader() );
      UserGroupInformation userToImpersonate = UserGroupInformation.getLoginUser();
      if ( user != null && !user.equals( userToImpersonate.getUserName() ) ) {
        if ( !isRoot ) {
          logger
              .warn( "In MapR, only the root user (usually mapr) can impersonate other users, attempted to impersonate "
                  + user + " from " + userToImpersonate.getUserName() + " for " + action );
        }
        userToImpersonate = UserGroupInformation.createProxyUser( user, userToImpersonate );
      }
      return userToImpersonate.doAs( action );
    } catch ( Exception e ) {
      Throwable actualException = e;
      if ( actualException instanceof UndeclaredThrowableException ) {
        actualException = actualException.getCause();
      }
      if ( actualException instanceof PrivilegedActionException ) {
        actualException = actualException.getCause();
      }
      throw actualException;
    } finally {
      Thread.currentThread().setContextClassLoader( contextClassLoader );
    }
  }

  @Override
  public Object invoke( Object proxy, final Method method, final Object[] args ) throws Throwable {
    PrivilegedExceptionAction<Object> action = new PrivilegedExceptionAction<Object>() {

      @SuppressWarnings( "unchecked" )
      @Override
      public Object run() throws Exception {
        Object result = method.invoke( delegate, args );
        if ( result != null ) {
          for ( Class<?> iface : (Class<?>[]) ClassUtils.getAllInterfaces( result.getClass() ).toArray(
              new Class<?>[] {} ) ) {
            if ( interfacesToDelegate.contains( iface ) ) {
              result =
                  forObject( result, interfacesToDelegate, UserGroupInformation.getCurrentUser().getUserName(), isRoot );
              break;
            }
          }
        }
        return result;
      }

      @Override
      public String toString() {
        return delegate.getClass().getCanonicalName() + "." + method.toString();
      }
    };
    String impersonateUser = null;
    if ( user == null ) {
      Method methodForUsername = getMethodForUserName( method );
      if ( methodForUsername != null ) {
        String username = (String) methodForUsername.invoke( delegate, args );
        if ( username != null && username.length() > 0 ) {
          impersonateUser = username;
        }
      }
    } else {
      impersonateUser = user;
    }
    try {
      return runAsUser( action, impersonateUser, isRoot );
    } catch ( Exception e ) {
      if ( e instanceof InvocationTargetException ) {
        throw e.getCause();
      }
      throw e;
    }
  }
}
