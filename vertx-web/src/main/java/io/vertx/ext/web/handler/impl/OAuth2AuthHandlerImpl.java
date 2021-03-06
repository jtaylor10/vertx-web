/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.AuthHandler;
import io.vertx.ext.web.handler.OAuth2AuthHandler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import static io.vertx.ext.auth.oauth2.OAuth2FlowType.AUTH_CODE;

/**
 * @author <a href="http://pmlopes@gmail.com">Paulo Lopes</a>
 */
public class OAuth2AuthHandlerImpl extends AuthorizationAuthHandler implements OAuth2AuthHandler {

  /**
   * This is a verification step, it can abort the instantiation by
   * throwing a RuntimeException
   *
   * @param provider a auth provider
   * @return the provider if valid
   */
  private static AuthProvider verifyProvider(AuthProvider provider) {
    if (provider instanceof OAuth2Auth) {
      if (((OAuth2Auth) provider).getFlowType() != AUTH_CODE) {
        throw new IllegalArgumentException("OAuth2Auth + Bearer Auth requires OAuth2 AUTH_CODE flow");
      }
    }

    return provider;
  }

  private final String host;
  private final String callbackPath;
  private final boolean supportJWT;
  private final Set<String> scopes = new HashSet<>();

  private Route callback;
  private JsonObject extraParams;

  public OAuth2AuthHandlerImpl(OAuth2Auth authProvider, String callbackURL) {
    super(verifyProvider(authProvider), Type.BEARER);

    this.supportJWT = authProvider.hasJWTToken();

    try {
      if (callbackURL != null) {
        final URL url = new URL(callbackURL);
        this.host = url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
        this.callbackPath = url.getPath();
      } else {
        this.host = null;
        this.callbackPath = null;
      }
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public AuthHandler addAuthority(String authority) {
    scopes.add(authority);
    return this;
  }

  @Override
  public AuthHandler addAuthorities(Set<String> authorities) {
    this.scopes.addAll(authorities);
    return this;
  }

  @Override
  public void parseCredentials(RoutingContext context, Handler<AsyncResult<JsonObject>> handler) {
    if (supportJWT) {
      parseAuthorization(context, true, parseAuthorization -> {
        if (parseAuthorization.failed()) {
          handler.handle(Future.failedFuture(parseAuthorization.cause()));
          return;
        }
        // if the provider supports JWT we can try to validate the Authorization header
        final String token = parseAuthorization.result();

        if (token != null) {
          ((OAuth2Auth) authProvider).decodeToken(token, decodeToken -> {
            if (decodeToken.failed()) {
              handler.handle(Future.failedFuture(new HttpStatusException(401, decodeToken.cause().getMessage())));
              return;
            }

            context.setUser(decodeToken.result());
            // continue
            handler.handle(Future.succeededFuture());
          });
        }
      });
    }
    // redirect request to the oauth2 server
    if (callback == null) {
      handler.handle(Future.failedFuture("callback route is not configured."));
      return;
    }

    handler.handle(Future.failedFuture(new HttpStatusException(302, authURI(context.request().uri()))));
  }

  private String authURI(String redirectURL) {
    final JsonObject config = new JsonObject()
      .put("state", redirectURL);

    if (host != null) {
      config.put("redirect_uri", host + callback.getPath());
    }

    if (extraParams != null) {
      config.mergeIn(extraParams);
    }

    if (scopes.size() > 0) {
      JsonArray _scopes = new JsonArray();
      // scopes are passed as an array because the auth provider has the knowledge on how to encode them
      for (String authority : scopes) {
        _scopes.add(authority);
      }

      config.put("scopes", _scopes);
    }

    return ((OAuth2Auth) authProvider).authorizeURL(config);
  }

  @Override
  public OAuth2AuthHandler extraParams(JsonObject extraParams) {
    this.extraParams = extraParams;
    return this;
  }

  @Override
  public OAuth2AuthHandler setupCallback(Route route) {

    callback = route;

    if (callbackPath != null && !"".equals(callbackPath)) {
      // no matter what path was provided we will make sure it is the correct one
      callback.path(callbackPath);
    }
    callback.method(HttpMethod.GET);

    route.handler(ctx -> {
      // Handle the callback of the flow
      final String code = ctx.request().getParam("code");

      // code is a require value
      if (code == null) {
        ctx.fail(400);
        return;
      }

      final String state = ctx.request().getParam("state");

      final JsonObject config = new JsonObject()
        .put("code", code);

      if (host != null) {
        config.put("redirect_uri", host + callback.getPath());
      }

      if (extraParams != null) {
        config.mergeIn(extraParams);
      }

      authProvider.authenticate(config, res -> {
        if (res.failed()) {
          ctx.fail(res.cause());
        } else {
          ctx.setUser(res.result());
          Session session = ctx.session();
          if (session != null) {
            // the user has upgraded from unauthenticated to authenticated
            // session should be upgraded as recommended by owasp
            session.regenerateId();
            // we should redirect the UA so this link becomes invalid
            ctx.response()
              // disable all caching
              .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
              .putHeader("Pragma", "no-cache")
              .putHeader(HttpHeaders.EXPIRES, "0")
              // redirect (when there is no state, redirect to home
              .putHeader(HttpHeaders.LOCATION, state != null ? state : "/")
              .setStatusCode(302)
              .end("Redirecting to " + (state != null ? state : "/") + ".");
          } else {
            // there is no session object so we cannot keep state
            ctx.reroute(state != null ? state : "/");
          }
        }
      });
    });

    return this;
  }
}
