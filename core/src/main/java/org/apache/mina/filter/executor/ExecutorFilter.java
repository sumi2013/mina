/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.filter.executor;

import java.util.Queue;
import java.util.concurrent.Executor;

import org.apache.mina.common.AttributeKey;
import org.apache.mina.common.IoEventType;
import org.apache.mina.common.IoFilterChain;
import org.apache.mina.common.IoFilterEvent;
import org.apache.mina.common.IoSession;
import org.apache.mina.util.CircularQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A filter that forwards I/O events to {@link Executor} to enforce a certain
 * thread model while maintaining the event order.
 * You can apply various thread model by inserting this filter to the {@link IoFilterChain}.
 * <p/>
 * Please note that this filter doesn't manage the life cycle of the underlying
 * {@link Executor}.  You have to destroy or stop it by yourself.
 * <p/>
 * This filter maintains the order of events per session and thus make sure
 * only one thread per session executes the event.  For example, let's assume
 * that messageReceived, messageSent, and sessionClosed events are fired.
 * <ul>
 * <li>All event handler methods are called exclusively.
 * (e.g. messageReceived and messageSent can't be invoked at the same time.)</li>
 * <li>The event order is never mixed up.
 * (e.g. messageReceived is always invoked before sessionClosed or messageSent.)</li>
 * </ul>
 * If you don't need to maintain the order of events per session, please use
 * {@link UnorderedExecutorFilter}.
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev: 350169 $, $Date: 2005-12-01 00:17:41 -0500 (Thu, 01 Dec 2005) $
 */
public class ExecutorFilter extends AbstractExecutorFilter {
    private final AttributeKey BUFFER = new AttributeKey(getClass(), "buffer");
    private final Logger logger = LoggerFactory.getLogger(getClass());

    // added a default constructor for IoC containers that need one
    public ExecutorFilter() {
        super();
    }

    // a constructor that only takes an Executor (for IoC containers that don't understand the varargs)
    public ExecutorFilter(Executor executor) {
      super(executor);
    }

  /**
     * Creates a new instance with the default thread pool implementation
     * (<tt>new ThreadPoolExecutor(16, 16, 60, TimeUnit.SECONDS, new LinkedBlockingQueue() )</tt>).
     */
    public ExecutorFilter(IoEventType... eventTypes) {
        super(eventTypes);
    }

    /**
     * Creates a new instance with the specified <tt>executor</tt>.
     */
    public ExecutorFilter(Executor executor, IoEventType... eventTypes) {
        super(executor, eventTypes);
    }
    
    @Override
    public String toString() {
        return "ordered";
    }

    @Override
    protected void fireEvent(IoFilterEvent event) {
        IoSession session = event.getSession();
        SessionBuffer buf = getSessionBuffer(session);

        boolean execute;
        synchronized (buf.eventQueue) {
            buf.eventQueue.add(event);
            if (buf.processingCompleted) {
                buf.processingCompleted = false;
                execute = true;
            } else {
                execute = false;
            }
        }

        if (execute) {
            if (logger.isDebugEnabled()) {
                logger.debug("Launching thread for "
                        + session.getRemoteAddress());
            }

            getExecutor().execute(buf);
        }
    }

    private SessionBuffer getSessionBuffer(IoSession session) {
        synchronized (session) {
            SessionBuffer buf = (SessionBuffer) session.getAttribute(BUFFER);
            if (buf == null) {
                buf = new SessionBuffer(session);
                session.setAttribute(BUFFER, buf);
            }
            return buf;
        }
    }

    private class SessionBuffer implements Runnable {
        
        private final IoSession session;
        private final Queue<IoFilterEvent> eventQueue = new CircularQueue<IoFilterEvent>();
        private boolean processingCompleted = true;

        private SessionBuffer(IoSession session) {
            this.session = session;
        }
        
        public void run() {
            while (true) {
                IoFilterEvent event;

                synchronized (eventQueue) {
                    event = eventQueue.poll();

                    if (event == null) {
                        processingCompleted = true;
                        break;
                    }
                }

                event.fire();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Exiting since queue is empty for "
                        + session.getRemoteAddress());
            }
        }
    }
}
