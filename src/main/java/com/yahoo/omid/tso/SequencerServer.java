/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import com.yahoo.omid.tso.serialization.SeqDecoder;
import com.yahoo.omid.tso.serialization.TSOEncoder;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Properties;
import java.io.IOException;
import java.util.concurrent.Executor;

import com.yahoo.omid.OmidConfiguration;
import com.yahoo.omid.client.TSOClient;
import org.apache.hadoop.conf.Configuration;

import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.channel.ChannelHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.apache.zookeeper.ZooKeeper;


/**
 * Sequencer Server
 */
public class SequencerServer extends TSOServer {

    private static final Log LOG = LogFactory.getLog(SequencerServer.class);

    /**
     * The interface to the tsos
     */
    TSOClient[] tsoClients;

    ZooKeeper zk;

    public SequencerServer(TSOServerConfig config, Properties[] soConfs, ZooKeeper zk) {
        super(config);
        this.zk = zk;
        tsoClients = new TSOClient[soConfs.length];
        try {
            for (int i = 0; i < soConfs.length; i++) {
                final int id = 0;//the id does not matter here
                tsoClients[i] = new TSOClient(soConfs[i], 0, false);
            }
        } catch (IOException e) {
            LOG.error("SO is not available " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Take two arguments :<br>
     * -port to listen to<br>
     * -nb of connections before shutting down
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        TSOServerConfig config = TSOServerConfig.parseConfig(args);
        OmidConfiguration omidConf = OmidConfiguration.create();
        omidConf.loadServerConfs(config.getZkServers());
        ZooKeeper zk = new ZooKeeper(config.getZkServers(), 
                Integer.parseInt(System.getProperty("SESSIONTIMEOUT", Integer.toString(10000))), 
                null);
        new SequencerServer(config, omidConf.getStatusOracleConfs(), zk).run();
    }

    SequencerHandler sequencerHandler;
    @Override
    protected ChannelHandler newMessageHandler() {
        sequencerHandler = new SequencerHandler(channelGroup, tsoClients, zk);
        return sequencerHandler;
    }

    @Override
    protected ChannelPipelineFactory newPipelineFactory(Executor pipelineExecutor, ChannelHandler handler) {
        return new SeqPipelineFactory(pipelineExecutor, handler);
    }

    @Override
    protected void stopHandler(ChannelHandler handler) {
        //((SequencerHandler)handler).stop();
    }

    class SeqPipelineFactory implements ChannelPipelineFactory {

        private Executor pipelineExecutor = null;
        ExecutionHandler x = null;// = new ExecutionHandler(pipelineExecutor);
        ChannelHandler handler = null;

        public SeqPipelineFactory(Executor pipelineExecutor, ChannelHandler handler) {
            super();
            this.pipelineExecutor = pipelineExecutor;
            this.handler = handler;
        }

        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = Channels.pipeline();
            pipeline.addLast("decoder", new SeqDecoder());
            synchronized (this) {
                if (x == null)
                    x = new ExecutionHandler(pipelineExecutor);
            }
            pipeline.addLast("pipelineExecutor", x);
            pipeline.addLast("handler", handler);
            return pipeline;
        }
    }

}