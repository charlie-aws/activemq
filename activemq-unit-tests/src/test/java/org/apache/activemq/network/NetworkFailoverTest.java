/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.network;

import java.io.IOException;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.transport.TransportFilter;
import org.apache.activemq.transport.failover.FailoverTransport;
import org.apache.activemq.xbean.BrokerFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

public class NetworkFailoverTest extends TestCase {

    protected static final int MESSAGE_COUNT = 10;
    private static final Logger LOG = LoggerFactory.getLogger(NetworkFailoverTest.class);

    protected AbstractApplicationContext context;
    protected Connection localConnection;
    protected Connection remoteConnection;
    protected BrokerService localBroker;
    protected BrokerService remoteBroker;
    protected Session localSession;
    protected Session remoteSession;
    protected ActiveMQQueue included = new ActiveMQQueue("include.test.foo");
    protected String consumerName = "durableSubs";

    public void testRequestReply() throws Exception {
        final MessageProducer remoteProducer = remoteSession.createProducer(null);
        MessageConsumer remoteConsumer = remoteSession.createConsumer(included);
        remoteConsumer.setMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message msg) {
                try {
                    TextMessage textMsg = (TextMessage) msg;
                    String payload = "REPLY: " + textMsg.getText();
                    Destination replyTo;
                    replyTo = msg.getJMSReplyTo();
                    textMsg.clearBody();
                    textMsg.setText(payload);
                    LOG.info("*** Sending response: {}", textMsg.getText());
                    remoteProducer.send(replyTo, textMsg);
                } catch (Exception e) {
                    LOG.warn("*** Responder listener caught exception: ", e);
                    e.printStackTrace();
                }
            }
        });

        Queue tempQueue = localSession.createTemporaryQueue();
        MessageProducer requestProducer = localSession.createProducer(included);
        requestProducer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        MessageConsumer requestConsumer = localSession.createConsumer(tempQueue);

        // allow for consumer infos to perculate arround
        Thread.sleep(2000);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String payload = "test msg " + i;
            TextMessage msg = localSession.createTextMessage(payload);
            msg.setJMSReplyTo(tempQueue);
            requestProducer.send(msg);
            LOG.info("*** Failing over for iteration: #{}", i);
            ((FailoverTransport) ((TransportFilter) ((TransportFilter) ((ActiveMQConnection) localConnection).getTransport()).getNext()).getNext())
                .handleTransportFailure(new IOException("Forcing failover from test"));
            TextMessage result = (TextMessage) requestConsumer.receive(10000);
            assertNotNull(result);
            LOG.info("*** Iteration #{} got response: {}", i, result.getText());
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        doSetUp(true);
    }

    @Override
    protected void tearDown() throws Exception {
        doTearDown();
        super.tearDown();
    }

    protected void doTearDown() throws Exception {
        try {
            localConnection.close();
            remoteConnection.close();
        } catch(Exception ex) {}

        try {
            localBroker.stop();
        } catch(Exception ex) {}
        try {
            remoteBroker.stop();
        } catch(Exception ex) {}
    }

    protected void doSetUp(boolean deleteAllMessages) throws Exception {

        remoteBroker = createRemoteBroker();
        remoteBroker.setDeleteAllMessagesOnStartup(deleteAllMessages);
        remoteBroker.start();

        localBroker = createLocalBroker();
        localBroker.setDeleteAllMessagesOnStartup(deleteAllMessages);
        localBroker.start();

        String localURI = "tcp://localhost:61616";
        String remoteURI = "tcp://localhost:61617";
        ActiveMQConnectionFactory fac = new ActiveMQConnectionFactory("failover:(" + localURI + "," + remoteURI
            + ")?randomize=false&backup=true&trackMessages=true");
        // ActiveMQConnectionFactory fac = new ActiveMQConnectionFactory(localURI);
        localConnection = fac.createConnection();
        localConnection.setClientID("local");
        localConnection.start();
        fac = new ActiveMQConnectionFactory("failover:(" + remoteURI + "," + localURI + ")?randomize=false&backup=true&trackMessages=true");
        fac.setWatchTopicAdvisories(false);
        remoteConnection = fac.createConnection();
        remoteConnection.setClientID("remote");
        remoteConnection.start();

        localSession = localConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        remoteSession = remoteConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    protected String getRemoteBrokerURI() {
        return "org/apache/activemq/network/remoteBroker.xml";
    }

    protected String getLocalBrokerURI() {
        return "org/apache/activemq/network/localBroker.xml";
    }

    protected BrokerService createBroker(String uri) throws Exception {
        Resource resource = new ClassPathResource(uri);
        BrokerFactoryBean factory = new BrokerFactoryBean(resource);
        resource = new ClassPathResource(uri);
        factory = new BrokerFactoryBean(resource);
        factory.afterPropertiesSet();
        BrokerService result = factory.getBroker();
        return result;
    }

    protected BrokerService createLocalBroker() throws Exception {
        return createBroker(getLocalBrokerURI());
    }

    protected BrokerService createRemoteBroker() throws Exception {
        return createBroker(getRemoteBrokerURI());
    }
}
