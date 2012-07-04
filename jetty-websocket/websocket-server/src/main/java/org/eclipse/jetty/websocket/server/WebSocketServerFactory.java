// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.EventMethodsCache;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.WebSocketEventDriver;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.extensions.deflate.DeflateFrameExtension;
import org.eclipse.jetty.websocket.extensions.fragment.FragmentExtension;
import org.eclipse.jetty.websocket.extensions.identity.IdentityExtension;
import org.eclipse.jetty.websocket.io.WebSocketAsyncConnection;
import org.eclipse.jetty.websocket.server.handshake.HandshakeHixie76;
import org.eclipse.jetty.websocket.server.handshake.HandshakeRFC6455;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends AbstractLifeCycle implements WebSocketCreator
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);
    private final Queue<WebSocketAsyncConnection> connections = new ConcurrentLinkedQueue<WebSocketAsyncConnection>();

    // TODO: replace with ExtensionRegistry in websocket-core
    private final Map<String, Class<? extends Extension>> extensionClasses = new HashMap<>();
    {
        extensionClasses.put("identity",IdentityExtension.class);
        extensionClasses.put("fragment",FragmentExtension.class);
        extensionClasses.put("x-deflate-frame",DeflateFrameExtension.class);
    }

    private final Map<Integer, WebSocketHandshake> handshakes = new HashMap<>();
    {
        handshakes.put(HandshakeRFC6455.VERSION,new HandshakeRFC6455());
        handshakes.put(HandshakeHixie76.VERSION,new HandshakeHixie76());
    }

    private final String supportedVersions;
    private WebSocketPolicy basePolicy;
    private WebSocketCreator creator;
    private EventMethodsCache methodsCache;
    private Class<?> firstRegisteredClass;

    public WebSocketServerFactory(WebSocketPolicy policy)
    {
        this.basePolicy = policy;
        this.methodsCache = new EventMethodsCache();
        this.creator = this;

        // Create supportedVersions
        List<Integer> versions = new ArrayList<>();
        for (int v : handshakes.keySet())
        {
            versions.add(v);
        }
        Collections.sort(versions,Collections.reverseOrder()); // newest first
        StringBuilder rv = new StringBuilder();
        for (int v : versions)
        {
            if (rv.length() > 0)
            {
                rv.append(", ");
            }
            rv.append(v);
        }
        supportedVersions = rv.toString();
    }

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        ServletWebSocketRequest sockreq = new ServletWebSocketRequest(request);
        ServletWebSocketResponse sockresp = new ServletWebSocketResponse(response);

        WebSocketCreator creator = getCreator();

        Object websocketPojo = creator.createWebSocket(sockreq,sockresp);
        // TODO: Handle forbidden?

        if (websocketPojo == null)
        {
            // no creation, sorry
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return false;
        }

        // TODO: discover type, create proxy

        // Send the upgrade
        WebSocketPolicy objPolicy = this.basePolicy.clonePolicy();
        WebSocketEventDriver websocket = new WebSocketEventDriver(methodsCache,objPolicy,websocketPojo);
        return upgrade(sockreq,sockresp,websocket);
    }

    protected boolean addConnection(WebSocketAsyncConnection connection)
    {
        return isRunning() && connections.add(connection);
    }

    protected void closeConnections()
    {
        for (WebSocketAsyncConnection connection : connections)
        {
            connection.getEndPoint().close();
        }
    }

    @Override
    public Object createWebSocket(WebSocketRequest req, WebSocketResponse resp)
    {
        if (methodsCache.count() < 1)
        {
            throw new WebSocketException("No WebSockets have been registered with the factory.  Cannot use default implementation of WebSocketCreator.");
        }

        if (methodsCache.count() > 1)
        {
            LOG.warn("You have registered more than 1 websocket object, and are using the default WebSocketCreator! Using first registered websocket.");
        }

        try
        {
            return firstRegisteredClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Unable to create instance of " + firstRegisteredClass,e);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
    }

    public WebSocketCreator getCreator()
    {
        return this.creator;
    }

    /**
     * @return A modifiable map of extension name to extension class
     */
    public Map<String, Class<? extends Extension>> getExtensionClassesMap()
    {
        return extensionClasses;
    }

    /**
     * Get the base policy in use for WebSockets.
     * <p>
     * Note: individual WebSocket implementations can override some of the values in here by using the {@link WebSocket &#064;WebSocket} annotation.
     * 
     * @return the base policy
     */
    public WebSocketPolicy getPolicy()
    {
        return basePolicy;
    }

    public List<Extension> initExtensions(List<ExtensionConfig> requested)
    {
        List<Extension> extensions = new ArrayList<Extension>();

        for (ExtensionConfig cfg : requested)
        {
            Extension extension = newExtension(cfg.getName());

            if (extension == null)
            {
                continue;
            }

            extension.setConfig(cfg);
            LOG.debug("added {}",extension);
            extensions.add(extension);
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    public boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response)
    {
        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null)
        {
            // Quietly fail
            return false;
        }

        if (!"websocket".equalsIgnoreCase(upgrade))
        {
            LOG.warn("Not a 'Upgrade: WebSocket' (was [Upgrade: " + upgrade + "])");
            return false;
        }

        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            LOG.warn("Not a 'HTTP/1.1' request (was [" + request.getProtocol() + "])");
            return false;
        }

        return true;
    }

    private Extension newExtension(String name)
    {
        try
        {
            Class<? extends Extension> extClass = extensionClasses.get(name);
            if (extClass != null)
            {
                return extClass.newInstance();
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        return null;
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]
            { null };
        }
        protocol = protocol.trim();
        if ((protocol == null) || (protocol.length() == 0))
        {
            return new String[]
            { null };
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public void register(Class<?> websocketClass)
    {
        if (firstRegisteredClass == null)
        {
            firstRegisteredClass = websocketClass;
        }
        methodsCache.register(websocketClass);
    }

    protected boolean removeConnection(WebSocketAsyncConnection connection)
    {
        return connections.remove(connection);
    }

    public void setCreator(WebSocketCreator creator)
    {
        this.creator = creator;
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>
     * This method will not normally return, but will instead throw a UpgradeConnectionException, to exit HTTP handling and initiate WebSocket handling of the
     * connection.
     * 
     * @param request
     *            The request to upgrade
     * @param response
     *            The response to upgrade
     * @param websocket
     *            The websocket handler implementation to use
     * @throws IOException
     *             in case of I/O errors
     */
    public boolean upgrade(ServletWebSocketRequest request, ServletWebSocketResponse response, WebSocketEventDriver websocket) throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            throw new IllegalStateException("Not a 'WebSocket: Upgrade' request");
        }
        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            throw new IllegalStateException("Not a 'HTTP/1.1' request");
        }

        int version = request.getIntHeader("Sec-WebSocket-Version");
        if (version < 0)
        {
            // Old pre-RFC version specifications (header not present in RFC-6455)
            version = request.getIntHeader("Sec-WebSocket-Draft");
        }

        WebSocketHandshake handshaker = handshakes.get(version);
        if (handshaker == null)
        {
            LOG.warn("Unsupported Websocket version: " + version);
            // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
            // Using the examples as outlined
            response.setHeader("Sec-WebSocket-Version",supportedVersions);
            response.sendError(HttpStatus.BAD_REQUEST_400,"Unsupported websocket version specification");
            return false;
        }

        // Create connection
        HttpConnection http = HttpConnection.getCurrentConnection();
        AsyncEndPoint endp = http.getEndPoint();
        Executor executor = http.getConnector().findExecutor();
        ByteBufferPool bufferPool = http.getConnector().getByteBufferPool();
        WebSocketAsyncConnection connection = new WebSocketAsyncConnection(endp,executor,websocket.getPolicy(),bufferPool);
        // Tell jetty about the new connection
        request.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTR,connection);

        LOG.debug("HttpConnection: {}",http);
        LOG.debug("AsyncWebSocketConnection: {}",connection);

        // Initialize / Negotiate Extensions
        List<Extension> extensions = initExtensions(request.getExtensions());
        // TODO : bind extensions? layer extensions? how?
        // TODO : wrap websocket with extension processing Parser.Listener list
        connection.getParser().addListener(websocket);
        // TODO : connection.setWriteExtensions(extensions);
        // TODO : implement endpoint.write() layer for outgoing extension frames.

        // Process (version specific) handshake response
        LOG.debug("Handshake Response: {}",handshaker);
        handshaker.doHandshakeResponse(request,response,extensions);
        LOG.debug("EndPoint: {}",endp);
        LOG.debug("Handshake Complete: {}",connection);

        // Add connection
        addConnection(connection);

        // Notify POJO of connection
        // TODO move to WebSocketAsyncConnection.onOpen
        websocket.setConnection(connection);
        websocket.onConnect();

        LOG.debug("Websocket upgrade {} {} {} {}",request.getRequestURI(),version,response.getAcceptedSubProtocol(),connection);
        return true;
    }
}
