/*******************************************************************************
 * Copyright (c) 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.jaxrs20.client.security;

import java.lang.reflect.Method;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

import org.apache.cxf.common.util.PropertyUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxrs.client.spec.TLSConfiguration;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.http.HTTPConduit;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.jaxrs20.client.JAXRSClientConstants;

/**
 *
 */
public class LibertyJaxRsClientSSLOutInterceptor extends AbstractPhaseInterceptor<Message> {

    private static final TraceComponent tc = Tr.register(LibertyJaxRsClientSSLOutInterceptor.class);

    private static final String HTTPS_SCHEMA = "https";

    private TLSConfiguration secConfig = null;

    /**
     * @param phase
     */
    public LibertyJaxRsClientSSLOutInterceptor(String phase) {
        super(phase);
    }

    @Override
    public void handleMessage(Message message) throws Fault {

        //SSL check
        //see if HTTPS is used
        boolean isSecured = false;
        String address = (String) message.get(Message.ENDPOINT_ADDRESS);
        isSecured = address == null ? false : address.startsWith(HTTPS_SCHEMA);

        //see if SSL Ref id is used
        Object sslRefObj = message.get(JAXRSClientConstants.SSL_REFKEY);
        String sslRef = null;

        if (sslRefObj != null) {
            sslRef = (String) sslRefObj;
        }

        //Allow a null sslRef to be used,  Liberty will resolve it to the default
        // getSocketFactory will return null if either the ssl feature is not enabled
        // or if it is enabled but there is no SSL configuration defined.  A null here
        // means to use the JDK's SSL implementation.
        if (isSecured && getSocketFactory(sslRef) != null) {
            Object disableCNCheckObj = message.get(JAXRSClientConstants.DISABLE_CN_CHECK);
            Conduit cd = message.getExchange().getConduit(message);
            configClientSSL(cd, sslRef, PropertyUtils.isTrue(disableCNCheckObj));
        }
    }

    private void configClientSSL(Conduit conduit, String sslRef, boolean disableCNCheck) {

        //for HTTPS protocol
        if (conduit instanceof HTTPConduit) {
            HTTPConduit httpConduit = (HTTPConduit) conduit;

            TLSClientParameters tlsClientParams = retriveHTTPTLSClientParametersUsingSSLRef(httpConduit, sslRef, disableCNCheck);
            if (null != tlsClientParams) {
                httpConduit.setTlsClientParameters(tlsClientParams);
            }
        }
    }

    private TLSClientParameters retriveHTTPTLSClientParametersUsingSSLRef(HTTPConduit httpConduit, String sslRef, boolean disableCNCheck) {
        TLSClientParameters tlsClientParams = null;
        if (this.secConfig == null) {
            tlsClientParams = httpConduit.getTlsClientParameters();
        } else {
            tlsClientParams = this.secConfig.getTlsClientParams();
        }

        SSLSocketFactory sslSocketFactory = null;

        if (!StringUtils.isEmpty(sslRef)) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "Use the sslRef = " + sslRef + " to create the SSLSocketFactory.");
            }
        } else {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "Get Liberty default SSLSocketFactory.");
            }
        }
        sslSocketFactory = getSocketFactory(sslRef);

        if (null != sslSocketFactory) {
            if (null == tlsClientParams) {
                tlsClientParams = new TLSClientParameters();
            }
            //let's use liberty SSL configuration
            tlsClientParams.setSSLSocketFactory(sslSocketFactory);
            tlsClientParams.setDisableCNCheck(disableCNCheck);
        } else if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(tc, "May not enable feature ssl-1.0 or appSecurity-2.0.");
        }

        return tlsClientParams;
    }

    public void setTLSConfiguration(TLSConfiguration secConfig) {
        this.secConfig = secConfig;
    }

    private SSLSocketFactory getSocketFactory(String sslRef) {
        try {
            Class<?> jaxrsSslMgrClass = Class.forName("com.ibm.ws.jaxrs20.appsecurity.security.JaxRsSSLManager");
            if (jaxrsSslMgrClass == null) {
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(tc, "getSocketFactory could not find JaxRsSSLManager class");
                }
                return null;
            }
            Object classObject = jaxrsSslMgrClass.newInstance();
            Method m = jaxrsSslMgrClass.getDeclaredMethod("getSSLSocketFactoryBySSLRef", String.class, Map.class, boolean.class);
            Object[] parameters = { sslRef, null, false }; //getSSLSocketFactoryBySSLRef ignores the third (boolean) parameter
            SSLSocketFactory ssLSocketFactory = (SSLSocketFactory) m.invoke(classObject, parameters);
            return ssLSocketFactory;
        } catch (Exception e) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "getSocketFactory reflection failed with exception " + e.toString());
            }
            return null;
        }
    }
}