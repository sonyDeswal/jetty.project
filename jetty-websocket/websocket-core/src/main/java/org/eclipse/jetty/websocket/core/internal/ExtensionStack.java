//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Extension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

/**
 * Represents the stack of Extensions.
 */
@ManagedObject("Extension Stack")
public class ExtensionStack implements IncomingFrames, OutgoingFrames, Dumpable
{
    private static final Logger LOG = Log.getLogger(ExtensionStack.class);

    private final WebSocketExtensionRegistry factory;
    private List<Extension> extensions;
    private IncomingFrames incoming;
    private OutgoingFrames outgoing;

    public ExtensionStack(WebSocketExtensionRegistry factory)
    {
        this.factory = factory;
    }

    @ManagedAttribute(name = "Extension List", readonly = true)
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    /**
     * Get the list of negotiated extensions, each entry being a full "name; params" extension configuration
     *
     * @return list of negotiated extensions
     */
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        if (extensions == null)
            return Collections.emptyList();

        return extensions.stream().filter(e -> !e.getName().startsWith("@")).map(Extension::getConfig).collect(Collectors.toList());
    }

    @ManagedAttribute(name = "Next Incoming Frames Handler", readonly = true)
    public IncomingFrames getNextIncoming()
    {
        return incoming;
    }

    @ManagedAttribute(name = "Next Outgoing Frames Handler", readonly = true)
    public OutgoingFrames getNextOutgoing()
    {
        return outgoing;
    }

    public boolean hasNegotiatedExtensions()
    {
        return (this.extensions != null) && (this.extensions.size() > 0);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (incoming == null)
            throw new IllegalStateException();
        incoming.onFrame(frame, callback);
    }

    /**
     * Perform the extension negotiation.
     * <p>
     * For the list of negotiated extensions, use {@link #getNegotiatedExtensions()}
     *
     * @param configs the configurations being requested
     */
    public void negotiate(DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool, List<ExtensionConfig> configs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Extension Configs={}", configs);

        this.extensions = new ArrayList<>();

        String rsvClaims[] = new String[3];

        for (ExtensionConfig config : configs)
        {
            Extension ext = factory.newInstance(objectFactory, bufferPool, config);
            if (ext == null)
            {
                // Extension not present on this side
                continue;
            }

            // Check RSV
            if (ext.isRsv1User() && (rsvClaims[0] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV1", config, rsvClaims[0]);
                continue;
            }
            if (ext.isRsv2User() && (rsvClaims[1] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV2", config, rsvClaims[1]);
                continue;
            }
            if (ext.isRsv3User() && (rsvClaims[2] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV3", config, rsvClaims[2]);
                continue;
            }

            // Add Extension
            extensions.add(ext);

            if (LOG.isDebugEnabled())
                LOG.debug("Adding Extension: {}", config);

            // Record RSV Claims
            if (ext.isRsv1User())
            {
                rsvClaims[0] = ext.getName();
            }
            if (ext.isRsv2User())
            {
                rsvClaims[1] = ext.getName();
            }
            if (ext.isRsv3User())
            {
                rsvClaims[2] = ext.getName();
            }
        }

        // Wire up Extensions
        if ((extensions != null) && (extensions.size() > 0))
        {
            ListIterator<Extension> exts = extensions.listIterator();

            // Connect outgoings
            while (exts.hasNext())
            {
                Extension ext = exts.next();
                ext.setNextOutgoingFrames(outgoing);
                outgoing = ext;
            }

            // Connect incomingFrames
            while (exts.hasPrevious())
            {
                Extension ext = exts.previous();
                ext.setNextIncomingFrames(incoming);
                incoming = ext;
            }
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        if (outgoing == null)
            throw new IllegalStateException();
        if (LOG.isDebugEnabled())
            LOG.debug("Extending out {} {} {}", frame, callback, batch);
        outgoing.sendFrame(frame, callback, batch);
    }

    public void connect(IncomingFrames incoming, OutgoingFrames outgoing, WebSocketChannel webSocketChannel)
    {
        if (extensions == null)
            throw new IllegalStateException();
        if (extensions.isEmpty())
        {
            this.incoming = incoming;
            this.outgoing = outgoing;
        }
        else
        {
            extensions.get(0).setNextOutgoingFrames(outgoing);
            extensions.get(extensions.size() - 1).setNextIncomingFrames(incoming);
        }

        for (Extension extension : extensions)
            extension.setWebSocketChannel(webSocketChannel);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, extensions == null?Collections.emptyList():extensions);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("ExtensionStack[extensions=");
        if (extensions == null)
        {
            s.append("<null>");
        }
        else
        {
            s.append('[');
            boolean delim = false;
            for (Extension ext : extensions)
            {
                if (delim)
                {
                    s.append(',');
                }
                if (ext == null)
                {
                    s.append("<null>");
                }
                else
                {
                    s.append(ext.getName());
                }
                delim = true;
            }
            s.append(']');
        }
        s.append(",incoming=").append((this.incoming == null)?"<null>":this.incoming.getClass().getName());
        s.append(",outgoing=").append((this.outgoing == null)?"<null>":this.outgoing.getClass().getName());
        s.append("]");
        return s.toString();
    }
}
