/*******************************************************************************
 * Copyright (c) 2013, 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.webcontainer.security;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Sensitive;
import com.ibm.websphere.security.audit.AuditEvent;
import com.ibm.ws.common.internal.encoder.Base64Coder;
import com.ibm.ws.security.SecurityService;
import com.ibm.ws.security.authentication.tai.TAIService;
import com.ibm.ws.webcontainer.security.internal.SSOAuthenticator;
import com.ibm.ws.webcontainer.security.internal.TAIAuthenticator;
import com.ibm.ws.webcontainer.security.metadata.SecurityMetadata;
import com.ibm.wsspi.kernel.service.utils.AtomicServiceReference;
import com.ibm.wsspi.kernel.service.utils.ConcurrentServiceReferenceMap;
import com.ibm.wsspi.security.tai.TrustAssociationInterceptor;

/**
 * The responsibility of this WebProviderAuthenticatorProxy is to authenticate request with TAI and SSO
 *
 */
public class WebProviderAuthenticatorProxy implements WebAuthenticator {

    private static final TraceComponent tc = Tr.register(WebProviderAuthenticatorProxy.class);

    AuthenticationResult JASPI_CONT = new AuthenticationResult(AuthResult.CONTINUE, "JASPI said continue...");
    protected final AtomicServiceReference<SecurityService> securityServiceRef;
    protected final AtomicServiceReference<TAIService> taiServiceRef;
    protected final ConcurrentServiceReferenceMap<String, TrustAssociationInterceptor> interceptorServiceRef;
    protected volatile WebAppSecurityConfig webAppSecurityConfig;

    protected final ConcurrentServiceReferenceMap<String, WebAuthenticator> webAuthenticatorRef;

    public WebProviderAuthenticatorProxy(AtomicServiceReference<SecurityService> securityServiceRef,
                                         AtomicServiceReference<TAIService> taiServiceRef,
                                         ConcurrentServiceReferenceMap<String, TrustAssociationInterceptor> interceptorServiceRef,
                                         WebAppSecurityConfig webAppSecurityConfig,
                                         ConcurrentServiceReferenceMap<String, WebAuthenticator> webAuthenticatorRef) {

        this.securityServiceRef = securityServiceRef;
        this.taiServiceRef = taiServiceRef;
        this.interceptorServiceRef = interceptorServiceRef;
        this.webAppSecurityConfig = webAppSecurityConfig;
        this.webAuthenticatorRef = webAuthenticatorRef;
    }

    /*
     * This method is the main method calling by the WebAuthenticatorProxy to handle TAI and SSO
     */
    @Override
    public AuthenticationResult authenticate(WebRequest webRequest) {
        AuthenticationResult authResult = handleTAI(webRequest, true);
        if (authResult.getStatus() == AuthResult.CONTINUE) {
            authResult = handleSSO(webRequest, null);
            if (authResult.getStatus() == AuthResult.CONTINUE) {
                webRequest.setCallAfterSSO(true);
                authResult = handleTAI(webRequest, false);
            }
        }

        return authResult;

    }

    /**
     * @param webRequest
     * @return
     */
    protected AuthenticationResult handleJaspi(WebRequest webRequest, HashMap<String, Object> props) {
        AuthenticationResult authResult = JASPI_CONT;
        if (webAuthenticatorRef != null) {
            WebAuthenticator jaspiAuthenticator = webAuthenticatorRef.getService("com.ibm.ws.security.jaspi");
            if (jaspiAuthenticator != null) {
                HttpServletRequest request = webRequest.getHttpServletRequest();
                if (props == null) {
                    authResult = authenticateForOtherMechanisms(webRequest, authResult, jaspiAuthenticator);
                } else {
                    authResult = authenticateForFormMechanism(webRequest, props, jaspiAuthenticator);
                }

                if (authResult.getStatus() == AuthResult.SUCCESS) {
                    processAuthenticationSuccess(webRequest, props, authResult);
                }
            }
        }
        return authResult;
    }

    private AuthenticationResult authenticateForOtherMechanisms(WebRequest webRequest, AuthenticationResult authResult, WebAuthenticator jaspiAuthenticator) {
        boolean isNewAuth = ((JaspiService) jaspiAuthenticator).isProcessingNewAuthentication(webRequest.getHttpServletRequest());
        // first see if we have an ltpa token (from form login)
        if (!isNewAuth) {
            authResult = handleSSO(webRequest, null);
        }
        if (isNewAuth || authResult.getStatus() == AuthResult.CONTINUE) { // no ltpatoken
            // JASPI session requires the subject from the previous invocation
            // to be passed in to the JASPI provider on subsequent calls
            authResult = handleSSO(webRequest, webAppSecurityConfig.getJaspicSessionCookieName());
            if (!isNewAuth && authResult.getStatus() == AuthResult.SUCCESS) {
                Map<String, Object> requestProps = new HashMap<String, Object>();
                requestProps.put("javax.servlet.http.registerSession.subject", authResult.getSubject());
                webRequest.setProperties(requestProps);
            }

            authResult = jaspiAuthenticator.authenticate(webRequest);
            if (authResult.getStatus() != AuthResult.CONTINUE) {
                if (!isNewAuth) {
                    String authHeader = webRequest.getHttpServletRequest().getHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Basic ")) {
                        String basicAuthHeader = decodeCookieString(authHeader.substring(6));
                        int index = basicAuthHeader.indexOf(':');
                        String uid = basicAuthHeader.substring(0, index);
                        authResult.setAuditCredValue(uid);
                    }
                    authResult.setAuditCredType(AuditEvent.CRED_TYPE_JASPIC);
                } else {
                    //TODO: is audit event required?? if so, how to get uid??
                }
            }
        }
        return authResult;
    }

    private AuthenticationResult authenticateForFormMechanism(WebRequest webRequest, HashMap<String, Object> props, WebAuthenticator jaspiAuthenticator) {
        AuthenticationResult authResult;
        try {
            authResult = jaspiAuthenticator.authenticate(webRequest.getHttpServletRequest(),
                                                         webRequest.getHttpServletResponse(),
                                                         props);
            if (authResult.getStatus() != AuthResult.CONTINUE) {
                String authHeader = webRequest.getHttpServletRequest().getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Basic ")) {
                    String basicAuthHeader = decodeCookieString(authHeader.substring(6));
                    int index = basicAuthHeader.indexOf(':');
                    String uid = basicAuthHeader.substring(0, index);
                    authResult.setAuditCredValue(uid);
                }
                authResult.setAuditCredType(AuditEvent.CRED_TYPE_JASPIC);
            }
        } catch (Exception e) {
            if (tc.isDebugEnabled()) {
                Tr.debug(tc, "Internal error handling JASPI request", e);
            }
            authResult = new AuthenticationResult(AuthResult.FAILURE, e.getMessage());
        }
        return authResult;
    }

    private void processAuthenticationSuccess(WebRequest webRequest, HashMap<String, Object> props, AuthenticationResult authResult) {
        registerSessionWhenRequested(webRequest, authResult, (props != null));
        attemptToRestorePostParams(webRequest);
        attemptToRemoveLtpaToken(webRequest, props);
    }

    private boolean registerSessionWhenRequested(final WebRequest webRequest, final AuthenticationResult authResult, boolean isFormLogin) {
        boolean registerSession = isFormLogin;
        if (!isFormLogin) {
            Map<String, Object> reqProps = webRequest.getProperties();
            if (reqProps != null) {
                registerSession = Boolean.valueOf((String) reqProps.get("javax.servlet.http.registerSession")).booleanValue();
            }
        }
        if (registerSession) {
            final SSOCookieHelper ssoCh = new SSOCookieHelperImpl(webAppSecurityConfig, webAppSecurityConfig.getJaspicSessionCookieName());
            if (System.getSecurityManager() == null) {
                ssoCh.addSSOCookiesToResponse(authResult.getSubject(), webRequest.getHttpServletRequest(), webRequest.getHttpServletResponse());
            } else {
                AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    @Override
                    public Object run() {
                        ssoCh.addSSOCookiesToResponse(authResult.getSubject(), webRequest.getHttpServletRequest(), webRequest.getHttpServletResponse());
                        return null;
                    }
                });
            }
            return true;
        }
        return false;
    }

    private HttpServletResponse attemptToRestorePostParams(WebRequest webRequest) {
        HttpServletResponse res = webRequest.getHttpServletResponse();
        if (!res.isCommitted()) {
            PostParameterHelper postParameterHelper = new PostParameterHelper(webAppSecurityConfig);
            postParameterHelper.restore(webRequest.getHttpServletRequest(), res);
        }
        return res;
    }

    /*
     * Remove LTPA token if this is not a FORM login and the JASPI provider has not committed the response.
     */
    private void attemptToRemoveLtpaToken(WebRequest webRequest, HashMap<String, Object> props) {
        SSOCookieHelper ssoCh = webAppSecurityConfig.createSSOCookieHelper();
        if (props == null || props.get("authType") == null || props.get("authType").equals("FORM_LOGIN") == false) {
            HttpServletResponse res = webRequest.getHttpServletResponse();
            if (!res.isCommitted()) {
                ssoCh.removeSSOCookieFromResponse(res);
            }
        }
    }

    /*
     * This method is called by the FormLoginExtensionProcessor
     */
    @Override
    public AuthenticationResult authenticate(HttpServletRequest request,
                                             HttpServletResponse response,
                                             HashMap<String, Object> props) throws Exception {
        WebRequest webRequest = new WebRequestImpl(request, response, null, null, null, null, null);
        AuthenticationResult authResult = handleJaspi(webRequest, props);
        return authResult;
    }

    /**
     * @param taiAuthenticator
     * @param webRequest
     * @param beforeSSO
     * @return
     */
    protected AuthenticationResult handleTAI(WebRequest webRequest, boolean beforeSSO) {
        TAIAuthenticator taiAuthenticator = getTaiAuthenticator();
        AuthenticationResult authResult = null;
        if (taiAuthenticator == null) {
            authResult = new AuthenticationResult(AuthResult.CONTINUE, "TAI invoke " + (beforeSSO == true ? "before" : "after") + " SSO is not available, skipping TAI...");
        } else {
            authResult = taiAuthenticator.authenticate(webRequest, beforeSSO);
            if (authResult.getStatus() != AuthResult.CONTINUE) {
                authResult.setAuditCredType(AuditEvent.CRED_TYPE_TAI);
            }
        }
        return authResult;
    }

    protected AuthenticationResult handleSSO(WebRequest webRequest, String ssoCookieName) {
        WebAuthenticator authenticator = getSSOAuthenticator(webRequest, ssoCookieName);
        AuthenticationResult authResult = authenticator.authenticate(webRequest);
        if (authResult == null || authResult.getStatus() != AuthResult.SUCCESS) {
            authResult = new AuthenticationResult(AuthResult.CONTINUE, "SSO did not succeed, so continue ...");
        }
        return authResult;
    }

    /**
     * @param req
     * @param propagationTokenAuthenticated
     * @return
     */
    protected boolean isNotNullAndTrue(HttpServletRequest req, String key) {
        Boolean result = (Boolean) req.getAttribute(key);
        if (result != null) {
            return result.booleanValue();
        }
        return false;
    }

    /**
     * @return
     */
    protected TAIAuthenticator getTaiAuthenticator() {
        TAIAuthenticator taiAuthenticator = null;
        TAIService taiService = taiServiceRef.getService();
        Iterator<TrustAssociationInterceptor> interceptorServices = interceptorServiceRef.getServices();
        if (taiService != null || (interceptorServices != null && interceptorServices.hasNext())) {
            SecurityService securityService = securityServiceRef.getService();
            taiAuthenticator = new TAIAuthenticator(taiService, interceptorServiceRef, securityService.getAuthenticationService(), webAppSecurityConfig.createSSOCookieHelper());
        }

        return taiAuthenticator;
    }

    /**
     * Create an instance of SSOAuthenticator.
     *
     * @param webRequest
     * @return The SSOAuthenticator, or {@code null} if it could not be created.
     */
    public WebAuthenticator getSSOAuthenticator(WebRequest webRequest, String ssoCookieName) {
        SecurityMetadata securityMetadata = webRequest.getSecurityMetadata();
        SecurityService securityService = securityServiceRef.getService();
        SSOCookieHelper cookieHelper;
        if (ssoCookieName != null) {
            cookieHelper = new SSOCookieHelperImpl(webAppSecurityConfig, ssoCookieName);
        } else {
            cookieHelper = webAppSecurityConfig.createSSOCookieHelper();
        }
        return new SSOAuthenticator(securityService.getAuthenticationService(), securityMetadata, webAppSecurityConfig, cookieHelper);
    }

    /**
     * @return
     */
    public ConcurrentServiceReferenceMap<String, WebAuthenticator> getWebAuthenticatorRefs() {
        return webAuthenticatorRef;
    }

    @Sensitive
    private String decodeCookieString(@Sensitive String cookieString) {
        try {
            return Base64Coder.base64Decode(cookieString);
        } catch (Exception e) {
            return null;
        }
    }
}
