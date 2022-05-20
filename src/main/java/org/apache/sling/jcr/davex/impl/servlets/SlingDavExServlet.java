/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.jcr.davex.impl.servlets;

import java.util.Dictionary;
import java.util.Hashtable;

import javax.jcr.LoginException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.jackrabbit.server.SessionProvider;
import org.apache.jackrabbit.server.remoting.davex.JcrRemotingServlet;
import org.apache.jackrabbit.webdav.util.CSRFUtil;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.auth.core.AuthenticationSupport;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.osgi.service.component.propertytypes.ServiceVendor;

/**
 * DavEx WebDav servlet which acquires a Repository instance via the OSGi
 * service registry.
 *
 */
@SuppressWarnings("serial")
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@ServiceVendor("The Apache Software Foundation")
@ServiceDescription("Sling JcrRemoting Servlet")
@Designate(ocd = SlingDavExServlet.Config.class)
public class SlingDavExServlet extends JcrRemotingServlet {

    @SuppressWarnings("java:S100")
    @ObjectClassDefinition(name = "%dav.name",  description = "%dav.description")
    public @interface Config {

        /**
         * Name of the property to configure the location for the DavEx servlet
         * registration. Default for the property is {@link #DEFAULT_DAV_ROOT}.
         */
        @AttributeDefinition
        String alias() default DEFAULT_DAV_ROOT;

        /**
         * Name of the property to configure whether absolute URIs ({@code true}) or
         * absolute paths ({@code false}) are generated in responses. Default for
         * the property is true.
         */
        @AttributeDefinition
        boolean dav_create$_$absolute$_$uri() default true;

        /**
         * defines the Protected handlers for the Jcr Remoting Servlet
         */
        @AttributeDefinition
        String dav_protectedhandlers() default DEFAULT_PROTECTED_HANDLERS;
    }

    /**
     * Default value for the DavEx servlet registration.
     */
    private static final String DEFAULT_DAV_ROOT = "/server";
    /**
     * Default value for the configuration
     */
    private static final String DEFAULT_PROTECTED_HANDLERS = "org.apache.jackrabbit.server.remoting.davex.AclRemoveHandler";

    /**
     * The name of the service property of the registered dummy service to cause
     * the path to the DavEx servlet to not be subject to forced authentication.
     */
    private static final String PAR_AUTH_REQ = "sling.auth.requirements";

    private static char[] EMPTY_PW = new char[0];

    /** default log */
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference
    private SlingRepository repository;

    /**
     * The DavExServlet service registration with the OSGi Whiteboard.
     */
    private ServiceRegistration davServlet;

    @Activate
    protected void activate(final BundleContext bundleContext, Config config) {
        final String davRoot = config.alias();
        final boolean createAbsoluteUri = config.dav_create$_$absolute$_$uri();
        final String protectedHandlers = config.dav_protectedhandlers();

        // prepare DavEx servlet config
        final Dictionary<String, Object> initProps = new Hashtable<String, Object>();
        initProps.put(toInitParamProperty(INIT_PARAM_RESOURCE_PATH_PREFIX), davRoot);
        initProps.put(toInitParamProperty(INIT_PARAM_CREATE_ABSOLUTE_URI), Boolean.toString(createAbsoluteUri));
        initProps.put(toInitParamProperty(INIT_PARAM_CSRF_PROTECTION), CSRFUtil.DISABLED);
        initProps.put(toInitParamProperty(INIT_PARAM_PROTECTED_HANDLERS_CONFIG), protectedHandlers);
        initProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, davRoot.concat("/*"));
        initProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT,
            "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=" + AuthHttpContext.HTTP_CONTEXT_NAME + ")");
        initProps.put(PAR_AUTH_REQ, "-" + davRoot); // make sure this is not forcible authenticated !
        this.davServlet = bundleContext.registerService(Servlet.class.getName(), this, initProps);
    }

    @Deactivate
    protected void deactivate() {
        if (this.davServlet!= null) {
            this.davServlet.unregister();
            this.davServlet = null;
        }
    }

    @Override
    protected Repository getRepository() {
        return repository;
    }

    @Override
    protected SessionProvider getSessionProvider() {
        return new SessionProvider() {

            public Session getSession(final HttpServletRequest req, final Repository repository, final String workspace)
                    throws LoginException, RepositoryException, ServletException {
                final ResourceResolver resolver = (ResourceResolver) req.getAttribute(AuthenticationSupport.REQUEST_ATTRIBUTE_RESOLVER);
                if (resolver != null) {
                    final Session session = resolver.adaptTo(Session.class);
                    if (session != null) {
                        final Session newSession = getLongLivedSession(session);
                        log.debug("getSession: Creating new Session ({}) for {}", newSession, newSession.getUserID());
                        return newSession;
                    }
                }

                throw new ServletException("ResourceResolver missing or not providing on JCR Session");
            }

            public void releaseSession(final Session session) {
                log.debug("releaseSession: Logging out long lived Session ({})", session);
                session.logout();
            }

            /**
             * Creates a new session for the user of the slingSession in the
             * same workspace as the slingSession.
             * <p>
             * Assumption: Every session can impersonate itself as it is defined by JCR 2.0.
             *
             * @param slingSession The session provided by the Sling
             *            authentication mechanism,
             * @return a new session which may (and will) outlast the request
             * @throws RepositoryException If an error occurs creating the session.
             */
            private Session getLongLivedSession(final Session slingSession) throws RepositoryException {
                final String user = slingSession.getUserID();
                try {
                    final SimpleCredentials credentials = new SimpleCredentials(user, EMPTY_PW);
                    return slingSession.impersonate(credentials);
                } catch (Exception re) {
                    throw new RepositoryException("Cannot get session for " + user, re);
                }
            }
        };
    }

    /**
     * Returns the name as a String suitable for use as a property registered with
     * and OSGi Http Whiteboard Service init parameter.
     *
     * @param name The parameter to convert. Must not be {@code null} and should not be empty.
     *
     * @return The converted name properly prefixed.
     *
     * @throws NullPointerException if {@code name} is {@code null}.
     */
    private static String toInitParamProperty(final String name) {
        return HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX.concat(name);
    }
}
