/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.openapi;

import com.networknt.config.Config;
import com.networknt.exception.ExpiredTokenException;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.handler.config.HandlerConfig;
import com.networknt.httpstring.AttachmentConstants;
import com.networknt.httpstring.HttpStringConstants;
import com.networknt.oas.model.Operation;
import com.networknt.oas.model.Path;
import com.networknt.oas.model.SecurityParameter;
import com.networknt.oas.model.SecurityRequirement;
import com.networknt.security.IJwtVerifyHandler;
import com.networknt.security.JwtVerifier;
import com.networknt.security.SecurityConfig;
import com.networknt.utility.Constants;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This is a middleware handler that handles security verification for light-rest-4j framework. It
 * verifies token signature and token expiration. And optional scope verification if it is enabled
 * in security.yml config file.
 *
 * @author Steve Hu
 */
public class JwtVerifyHandler implements MiddlewareHandler, IJwtVerifyHandler {
    static final Logger logger = LoggerFactory.getLogger(JwtVerifyHandler.class);

    static final String HANDLER_CONFIG = "handler";
    static final String OPENAPI_SECURITY_CONFIG = "openapi-security";

    static final String STATUS_INVALID_AUTH_TOKEN = "ERR10000";
    static final String STATUS_AUTH_TOKEN_EXPIRED = "ERR10001";
    static final String STATUS_MISSING_AUTH_TOKEN = "ERR10002";
    static final String STATUS_INVALID_SCOPE_TOKEN = "ERR10003";
    static final String STATUS_SCOPE_TOKEN_EXPIRED = "ERR10004";
    static final String STATUS_AUTH_TOKEN_SCOPE_MISMATCH = "ERR10005";
    static final String STATUS_SCOPE_TOKEN_SCOPE_MISMATCH = "ERR10006";
    static final String STATUS_INVALID_REQUEST_PATH = "ERR10007";
    static final String STATUS_METHOD_NOT_ALLOWED = "ERR10008";

    static SecurityConfig config;

    // make this static variable public so that it can be accessed from the server-info module
    public static JwtVerifier jwtVerifier;

    String basePath;

    private volatile HttpHandler next;

    public JwtVerifyHandler() {
        // at this moment, we assume that the OpenApiHandler is fully loaded with a single spec or multiple specs.
        // And the basePath is the correct one from the OpenApiHandler helper or helperMap if multiple is used.
        config = SecurityConfig.load(OPENAPI_SECURITY_CONFIG);
        jwtVerifier = new JwtVerifier(config);
        // in case that the specification doesn't exist, get the basePath from the handler.yml for endpoint lookup.
        HandlerConfig handlerConfig = (HandlerConfig) Config.getInstance().getJsonObjectConfig(HANDLER_CONFIG, HandlerConfig.class);
        this.basePath = handlerConfig == null ? "/" : handlerConfig.getBasePath();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleRequest(final HttpServerExchange exchange) throws Exception {

        if (logger.isDebugEnabled())
            logger.debug("JwtVerifyHandler.handleRequest starts.");

        String reqPath = exchange.getRequestPath();

        // if request path is in the skipPathPrefixes in the config, call the next handler directly to skip the security check.
        if (config.getSkipPathPrefixes() != null && config.getSkipPathPrefixes().stream().anyMatch(reqPath::startsWith)) {
            if(logger.isTraceEnabled())
                logger.trace("Skip request path base on skipPathPrefixes for " + reqPath);
            Handler.next(exchange, next);
            if (logger.isDebugEnabled())
                logger.debug("JwtVerifyHandler.handleRequest ends.");
            return;
        }
        // only UnifiedSecurityHandler will have the jwkServiceIds as the third parameter.
        if(handleJwt(exchange, null, reqPath, null)) {
            if(logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends.");
            Handler.next(exchange, next);
        }
    }

    public boolean handleJwt(HttpServerExchange exchange, String pathPrefix, String reqPath, List<String> jwkServiceIds) throws Exception {
        Map<String, Object> auditInfo = null;
        HeaderMap headerMap = exchange.getRequestHeaders();
        String authorization = headerMap.getFirst(Headers.AUTHORIZATION);

        if (logger.isTraceEnabled() && authorization != null && authorization.length() > 10)
            logger.trace("Authorization header = " + authorization.substring(0, 10));
        // if an empty authorization header or a value length less than 6 ("Basic "), return an error
        if(authorization == null ) {
            setExchangeStatus(exchange, STATUS_MISSING_AUTH_TOKEN);
            exchange.endExchange();
            if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
            return false;
        } else if(authorization.trim().length() < 6) {
            setExchangeStatus(exchange, STATUS_INVALID_AUTH_TOKEN);
            exchange.endExchange();
            if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
            return false;
        } else {
            authorization = this.getScopeToken(authorization, headerMap);

            boolean ignoreExpiry = config.isIgnoreJwtExpiry();

            String jwt = JwtVerifier.getTokenFromAuthorization(authorization);

            if (jwt != null) {

                if (logger.isTraceEnabled())
                    logger.trace("parsed jwt from authorization = " + jwt.substring(0, 10));

                try {

                    JwtClaims claims = jwtVerifier.verifyJwt(jwt, ignoreExpiry, true, pathPrefix, reqPath, jwkServiceIds);

                    if (logger.isTraceEnabled())
                        logger.trace("claims = " + claims.toJson());

                    /* if no auditInfo has been set previously, we populate here */
                    auditInfo = exchange.getAttachment(AttachmentConstants.AUDIT_INFO);
                    if (auditInfo == null) {
                        auditInfo = new HashMap<>();
                        exchange.putAttachment(AttachmentConstants.AUDIT_INFO, auditInfo);
                    }

                    String clientId = claims.getStringClaimValue(Constants.CLIENT_ID_STRING);
                    String userId = claims.getStringClaimValue(Constants.USER_ID_STRING);
                    String issuer = claims.getStringClaimValue(Constants.ISS_STRING);
                    // try to get the cid as some OAuth tokens name it as cid like Okta.
                    if (clientId == null)
                        clientId = claims.getStringClaimValue(Constants.CID_STRING);


                    // try to get the uid as some OAuth tokens name it as uid like Okta.
                    if (userId == null)
                        userId = claims.getStringClaimValue(Constants.UID_STRING);

                    auditInfo.put(Constants.USER_ID_STRING, userId);
                    auditInfo.put(Constants.SUBJECT_CLAIMS, claims);
                    auditInfo.put(Constants.CLIENT_ID_STRING, clientId);
                    auditInfo.put(Constants.ISSUER_CLAIMS, issuer);

                    if (!config.isEnableH2c() && jwtVerifier.checkForH2CRequest(headerMap)) {
                        setExchangeStatus(exchange, STATUS_METHOD_NOT_ALLOWED);
                        if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
                        return false;
                    }

                    String callerId = headerMap.getFirst(HttpStringConstants.CALLER_ID);

                    if (callerId != null)
                        auditInfo.put(Constants.CALLER_ID_STRING, callerId);

                    if (config != null && config.isEnableVerifyScope()) {
                        if (logger.isTraceEnabled())
                            logger.trace("verify scope from the primary token when enableVerifyScope is true");

                        /* get openapi operation */
                        OpenApiOperation openApiOperation = (OpenApiOperation) auditInfo.get(Constants.OPENAPI_OPERATION_STRING);
                        Operation operation = this.getOperation(exchange, openApiOperation, auditInfo);
                        if(operation == null) {
                            if(config.isSkipVerifyScopeWithoutSpec()) {
                                if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends without verifying scope due to spec.");
                                Handler.next(exchange, next);
                            } else {
                                // this will return an error message to the client.
                            }
                            if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
                            return false;
                        }

                        /* validate scope from operation */
                        String scopeHeader = headerMap.getFirst(HttpStringConstants.SCOPE_TOKEN);
                        String scopeJwt = JwtVerifier.getTokenFromAuthorization(scopeHeader);
                        List<String> secondaryScopes = new ArrayList<>();

                        if(!this.hasValidSecondaryScopes(exchange, scopeJwt, secondaryScopes, ignoreExpiry, pathPrefix, reqPath, jwkServiceIds, auditInfo)) {
                            if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
                            return false;
                        }
                        if(!this.hasValidScope(exchange, scopeHeader, secondaryScopes, claims, operation)) {
                            if (logger.isDebugEnabled()) logger.debug("JwtVerifyHandler.handleRequest ends with an error.");
                            return false;
                        }
                    }
                    // pass through claims through request headers after verification is done.
                    if(config.getPassThroughClaims() != null && config.getPassThroughClaims().size() > 0) {
                        for(Map.Entry<String, String> entry: config.getPassThroughClaims().entrySet()) {
                            String key = entry.getKey();
                            String header = entry.getValue();
                            Object value = claims.getClaimValue(key);
                            if(logger.isTraceEnabled()) logger.trace("pass through header {} with value {}", header, value);
                            headerMap.put(new HttpString(header), value.toString());
                        }
                    }
                    if (logger.isTraceEnabled())
                        logger.trace("complete JWT verification for request path = " + exchange.getRequestURI());

                    if (logger.isDebugEnabled())
                        logger.debug("JwtVerifyHandler.handleRequest ends.");

                    return true;
                } catch (InvalidJwtException e) {

                    // only log it and unauthorized is returned.
                    logger.error("InvalidJwtException: ", e);

                    if (logger.isDebugEnabled())
                        logger.debug("JwtVerifyHandler.handleRequest ends with an error.");

                    setExchangeStatus(exchange, STATUS_INVALID_AUTH_TOKEN);
                    exchange.endExchange();
                    return false;
                } catch (ExpiredTokenException e) {

                    logger.error("ExpiredTokenException", e);

                    if (logger.isDebugEnabled())
                        logger.debug("JwtVerifyHandler.handleRequest ends with an error.");

                    setExchangeStatus(exchange, STATUS_AUTH_TOKEN_EXPIRED);
                    exchange.endExchange();
                    return false;
                }
            } else {
                if (logger.isDebugEnabled())
                    logger.debug("JwtVerifyHandler.handleRequest ends with an error.");

                setExchangeStatus(exchange, STATUS_MISSING_AUTH_TOKEN);
                exchange.endExchange();
                return false;
            }
        }
    }
    /**
     * Get authToken from X-Scope-Token header.
     * This covers situations where there is a secondary auth token.
     *
     * @param authorization - The auth token from authorization header
     * @param headerMap - complete header map
     * @return - return either x-scope-token or the initial auth token
     */
    protected String getScopeToken(String authorization, HeaderMap headerMap) {
        String returnToken = authorization;
        // in the gateway case, the authorization header might be a basic header for the native API or other authentication headers.
        // this will allow the Basic authentication be wrapped up with a JWT token between proxy client and proxy server for native.
        if (returnToken != null && !returnToken.substring(0, 6).equalsIgnoreCase("Bearer")) {

            // get the jwt token from the X-Scope-Token header in this case and allow the verification done with the secondary token.
            returnToken = headerMap.getFirst(HttpStringConstants.SCOPE_TOKEN);

            if (logger.isTraceEnabled() && returnToken != null && returnToken.length() > 10)
                logger.trace("The replaced authorization from X-Scope-Token header = " + returnToken.substring(0, 10));
        }
        return returnToken;
    }


    /**
     * Gets the operation from the spec. If not defined or defined incorrectly, return null.
     *
     * @param exchange - the current exchange
     * @param openApiOperation - the openapi operation (from spec)
     * @param auditInfo A map of audit info properties
     * @return - return Operation
     */
    protected Operation getOperation(HttpServerExchange exchange, OpenApiOperation openApiOperation, Map<String, Object> auditInfo) {
        Operation operation;
        if (openApiOperation == null) {
            final NormalisedPath requestPath = new ApiNormalisedPath(exchange.getRequestURI(), basePath);
            OpenApiHelper helper = OpenApiHandler.getHelper(exchange.getRequestPath());
            final Optional<NormalisedPath> maybeApiPath = helper == null ? Optional.empty() : helper.findMatchingApiPath(requestPath);

            if (maybeApiPath.isEmpty()) {
                if(!config.isSkipVerifyScopeWithoutSpec()) {
                    setExchangeStatus(exchange, STATUS_INVALID_REQUEST_PATH);
                }
                return null;
            }

            final NormalisedPath swaggerPathString = maybeApiPath.get();
            final Path swaggerPath = OpenApiHandler.getHelper(exchange.getRequestPath()).openApi3.getPath(swaggerPathString.original());
            final String httpMethod = exchange.getRequestMethod().toString().toLowerCase();

            operation = swaggerPath.getOperation(httpMethod);

            if (operation == null) {
                setExchangeStatus(exchange, STATUS_METHOD_NOT_ALLOWED, httpMethod, swaggerPathString.normalised());
                exchange.endExchange();
                return null;
            }

            openApiOperation = new OpenApiOperation(swaggerPathString, swaggerPath, httpMethod, operation);
            auditInfo.put(Constants.OPENAPI_OPERATION_STRING, openApiOperation);
            auditInfo.put(Constants.ENDPOINT_STRING, swaggerPathString.normalised() + "@" + httpMethod);

        } else {
            operation = openApiOperation.getOperation();
        }
        return operation;
    }

    /**
     * Check is the request has secondary scopes and they are valid.
     *
     * @param exchange - current exchange
     * @param scopeJwt - the scope found in jwt
     * @param secondaryScopes - Initially an empty list that is then filled with the secondary scopes if there are any.
     * @param ignoreExpiry - if we ignore expiry or not (mostly for testing)
     * @param pathPrefix - request path prefix
     * @param reqPath - the request path as string
     * @param jwkServiceIds - a list of serviceIds for jwk loading
     * @param auditInfo - a map of audit info properties
     * @return - return true if the secondary scopes are valid or if there are no secondary scopes.
     */
    protected boolean hasValidSecondaryScopes(HttpServerExchange exchange, String scopeJwt, List<String> secondaryScopes, boolean ignoreExpiry, String pathPrefix, String reqPath, List<String> jwkServiceIds, Map<String, Object> auditInfo) {
        if (scopeJwt != null) {
            if (logger.isTraceEnabled())
                logger.trace("start verifying scope token = " + scopeJwt.substring(0, 10));

            try {
                JwtClaims scopeClaims = jwtVerifier.verifyJwt(scopeJwt, ignoreExpiry, true, pathPrefix, reqPath, jwkServiceIds);
                Object scopeClaim = scopeClaims.getClaimValue(Constants.SCOPE_STRING);

                if (scopeClaim instanceof String) {
                    secondaryScopes.addAll(Arrays.asList(scopeClaims.getStringClaimValue(Constants.SCOPE_STRING).split(" ")));
                } else if (scopeClaim instanceof List) {
                    secondaryScopes.addAll(scopeClaims.getStringListClaimValue(Constants.SCOPE_STRING));
                }

                if (secondaryScopes.isEmpty()) {

                    // some IDPs like Okta and Microsoft call scope claim "scp" instead of "scope"
                    Object scpClaim = scopeClaims.getClaimValue(Constants.SCP_STRING);
                    if (scpClaim instanceof String) {
                        secondaryScopes.addAll(Arrays.asList(scopeClaims.getStringClaimValue(Constants.SCP_STRING).split(" ")));
                    } else if (scpClaim instanceof List) {
                        secondaryScopes.addAll(scopeClaims.getStringListClaimValue(Constants.SCP_STRING));
                    }
                }
                auditInfo.put(Constants.SCOPE_CLIENT_ID_STRING, scopeClaims.getStringClaimValue(Constants.CLIENT_ID_STRING));
                auditInfo.put(Constants.ACCESS_CLAIMS, scopeClaims);
            } catch (InvalidJwtException e) {
                logger.error("InvalidJwtException", e);
                setExchangeStatus(exchange, STATUS_INVALID_SCOPE_TOKEN);
                exchange.endExchange();
                return false;
            } catch (MalformedClaimException e) {
                logger.error("MalformedClaimException", e);
                setExchangeStatus(exchange, STATUS_INVALID_AUTH_TOKEN);
                exchange.endExchange();
                return false;
            } catch (ExpiredTokenException e) {
                logger.error("ExpiredTokenException", e);
                setExchangeStatus(exchange, STATUS_SCOPE_TOKEN_EXPIRED);
                exchange.endExchange();
                return false;
            }
        }
        return true;
    }

    /**
     * Makes sure the provided scope in the JWT is valid for the main scope or secondary scopes.
     *
     * @param exchange - the current exchange
     * @param scopeHeader - the scope header
     * @param secondaryScopes - list of secondary scopes (can be empty)
     * @param claims - claims found in jwt
     * @param operation - the openapi operation
     * @return - return true if scope is valid for endpoint
     */
    protected boolean hasValidScope(HttpServerExchange exchange, String scopeHeader, List<String> secondaryScopes, JwtClaims claims, Operation operation) {

        // validate the scope against the scopes configured in the OpenAPI spec
        if (config.isEnableVerifyScope()) {
            // get scope defined in OpenAPI spec for this endpoint.
            Collection<String> specScopes = null;
            Collection<SecurityRequirement> securityRequirements = operation.getSecurityRequirements();
            if (securityRequirements != null) {
                for (SecurityRequirement requirement : securityRequirements) {
                    SecurityParameter securityParameter = null;

                    for (String oauth2Name : OpenApiHandler.getHelper(exchange.getRequestPath()).oauth2Names) {
                        securityParameter = requirement.getRequirement(oauth2Name);
                        if (securityParameter != null) break;
                    }

                    if (securityParameter != null)
                        specScopes = securityParameter.getParameters();

                    if (specScopes != null)
                        break;
                }
            }

            // validate scope
            if (scopeHeader != null) {
                if (logger.isTraceEnabled()) logger.trace("validate the scope with scope token");
                if (secondaryScopes == null || !matchedScopes(secondaryScopes, specScopes)) {
                    setExchangeStatus(exchange, STATUS_SCOPE_TOKEN_SCOPE_MISMATCH, secondaryScopes, specScopes);
                    exchange.endExchange();
                    return false;
                }
            } else {
                // no scope token, verify scope from auth token.
                if (logger.isTraceEnabled()) logger.trace("validate the scope with primary token");
                List<String> primaryScopes = null;
                try {
                    Object scopeClaim = claims.getClaimValue(Constants.SCOPE_STRING);
                    if (scopeClaim instanceof String) {
                        primaryScopes = Arrays.asList(claims.getStringClaimValue(Constants.SCOPE_STRING).split(" "));
                    } else if (scopeClaim instanceof List) {
                        primaryScopes = claims.getStringListClaimValue(Constants.SCOPE_STRING);
                    }
                    if (primaryScopes == null || primaryScopes.isEmpty()) {
                        // some IDPs like Okta and Microsoft call scope claim "scp" instead of "scope"
                        Object scpClaim = claims.getClaimValue(Constants.SCP_STRING);
                        if (scpClaim instanceof String) {
                            primaryScopes = Arrays.asList(claims.getStringClaimValue(Constants.SCP_STRING).split(" "));
                        } else if (scpClaim instanceof List) {
                            primaryScopes = claims.getStringListClaimValue(Constants.SCP_STRING);
                        }
                    }
                } catch (MalformedClaimException e) {
                    logger.error("MalformedClaimException", e);
                    setExchangeStatus(exchange, STATUS_INVALID_AUTH_TOKEN);
                    exchange.endExchange();
                    return false;
                }
                if (!matchedScopes(primaryScopes, specScopes)) {
                    setExchangeStatus(exchange, STATUS_AUTH_TOKEN_SCOPE_MISMATCH, primaryScopes, specScopes);
                    exchange.endExchange();
                    return false;
                }
            }
        }
        return true;
    }

    protected boolean matchedScopes(List<String> jwtScopes, Collection<String> specScopes) {
        boolean matched = false;
        if (specScopes != null && specScopes.size() > 0) {
            if (jwtScopes != null && jwtScopes.size() > 0) {
                for (String scope : specScopes) {
                    if (jwtScopes.contains(scope)) {
                        matched = true;
                        break;
                    }
                }
            }
        } else {
            matched = true;
        }
        return matched;
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnableVerifyJwt();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(OPENAPI_SECURITY_CONFIG, JwtVerifyHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(OPENAPI_SECURITY_CONFIG), null);
    }

    @Override
    public void reload() {
        config.reload(OPENAPI_SECURITY_CONFIG);
        jwtVerifier = new JwtVerifier(config);
        ModuleRegistry.registerModule(OPENAPI_SECURITY_CONFIG, JwtVerifyHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(OPENAPI_SECURITY_CONFIG), null);
    }

    @Override
    public JwtVerifier getJwtVerifier() {
        return jwtVerifier;
    }
}
