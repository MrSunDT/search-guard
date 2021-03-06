/*
 * Copyright 2015-2017 floragunn GmbH
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
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.test.helper.cluster;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.node.DiscoveryNode.Role;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.reindex.ReindexPlugin;
import org.elasticsearch.join.ParentJoinPlugin;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.node.PluginAwareNode;
import org.elasticsearch.percolator.PercolatorPlugin;
import org.elasticsearch.script.mustache.MustachePlugin;
import org.elasticsearch.search.aggregations.matrix.MatrixAggregationPlugin;
import org.elasticsearch.transport.Netty4Plugin;

import com.floragunn.searchguard.SearchGuardPlugin;
import com.floragunn.searchguard.test.NodeSettingsSupplier;
import com.floragunn.searchguard.test.helper.cluster.ClusterConfiguration.NodeSettings;
import com.floragunn.searchguard.test.helper.network.SocketUtils;

public final class ClusterHelper {

    static {
        System.setProperty("es.enforce.bootstrap.checks", "true");
        System.setProperty("sg.default_init.dir", new File("./sgconfig").getAbsolutePath());
    }
    
	protected final Logger log = LogManager.getLogger(ClusterHelper.class);

	protected final List<PluginAwareNode> esNodes = new LinkedList<>();

	private final String clustername;
	
	public ClusterHelper(String clustername) {
        super();
        this.clustername = clustername;
    }

    /**
	 * Start n Elasticsearch nodes with the provided settings
	 * 
	 * @return
     * @throws Exception 
	 */
	
	public final ClusterInfo startCluster(final NodeSettingsSupplier nodeSettingsSupplier, ClusterConfiguration clusterConfiguration) throws Exception {
	    return startCluster(nodeSettingsSupplier, clusterConfiguration, 10, null);
	}
	
	
	public final ClusterInfo startCluster(final NodeSettingsSupplier nodeSettingsSupplier, ClusterConfiguration clusterConfiguration, int timeout, Integer nodes)
			throws Exception {
	    
		if (!esNodes.isEmpty()) {
			throw new RuntimeException("There are still " + esNodes.size() + " nodes instantiated, close them first.");
		}

		FileUtils.deleteDirectory(new File("data/"+clustername));

		List<NodeSettings> internalNodeSettings = clusterConfiguration.getNodeSettings();
		
		SortedSet<Integer> tcpPorts = SocketUtils.findAvailableTcpPorts(internalNodeSettings.size());
		Iterator<Integer> tcpPortsIt = tcpPorts.iterator();
		
		SortedSet<Integer> httpPorts = SocketUtils.findAvailableTcpPorts(internalNodeSettings.size(), tcpPorts.last()+1, SocketUtils.PORT_RANGE_MAX);
        Iterator<Integer> httpPortsIt = httpPorts.iterator();
		
		final CountDownLatch latch = new CountDownLatch(internalNodeSettings.size());
        
		for (int i = 0; i < internalNodeSettings.size(); i++) {
			NodeSettings setting = internalNodeSettings.get(i);
			
			PluginAwareNode node = new PluginAwareNode(setting.masterNode,
					getMinimumNonSgNodeSettingsBuilder(i, setting.masterNode, setting.dataNode, setting.tribeNode, internalNodeSettings.size(), clusterConfiguration.getMasterNodes(), tcpPorts, tcpPortsIt.next(), httpPortsIt.next())
							.put(nodeSettingsSupplier == null ? Settings.Builder.EMPTY_SETTINGS : nodeSettingsSupplier.get(i)).build(),
					Netty4Plugin.class, SearchGuardPlugin.class, MatrixAggregationPlugin.class, MustachePlugin.class, ParentJoinPlugin.class, PercolatorPlugin.class, ReindexPlugin.class);
			System.out.println(node.settings());
			
			new Thread(new Runnable() {
                
                @Override
                public void run() {
                    try {
                        node.start();
                        latch.countDown();
                    } catch (NodeValidationException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }).start();
			
			
			
			esNodes.add(node);
		}
		
		
		latch.await();
		
		ClusterInfo cInfo = waitForCluster(ClusterHealthStatus.GREEN, TimeValue.timeValueSeconds(timeout), nodes == null?esNodes.size():nodes.intValue());
		cInfo.numNodes = internalNodeSettings.size();
		cInfo.clustername = clustername;
		return cInfo;
	}

	public final void stopCluster() throws Exception {
	    
	    //close non master nodes
	    esNodes.stream().filter(n->!n.isMasterEligible()).forEach(node->closeNode(node));
	    
	    //close master nodes
	    esNodes.stream().filter(n->n.isMasterEligible()).forEach(node->closeNode(node));		
		esNodes.clear();
		
		FileUtils.deleteDirectory(new File("data/"+clustername));
	}
	
	private static void closeNode(Node node) {
	    try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configurator.shutdown(context);
	        node.close();
	        Thread.sleep(250);
        } catch (Throwable e) {
            //ignore
        }
	}
	

	public Client nodeClient() {
	    return esNodes.get(0).client();
	}
	
	public ClusterInfo waitForCluster(final ClusterHealthStatus status, final TimeValue timeout, final int expectedNodeCount) throws IOException {
		if (esNodes.isEmpty()) {
			throw new RuntimeException("List of nodes was empty.");
		}

		ClusterInfo clusterInfo = new ClusterInfo();

		Node node = esNodes.get(0);
		Client client = node.client();
		try {
			log.debug("waiting for cluster state {} and {} nodes", status.name(), expectedNodeCount);
			final ClusterHealthResponse healthResponse = client.admin().cluster().prepareHealth()
					.setWaitForStatus(status).setTimeout(timeout).setMasterNodeTimeout(timeout).setWaitForNodes("" + expectedNodeCount).execute()
					.actionGet();
			if (healthResponse.isTimedOut()) {
				throw new IOException("cluster state is " + healthResponse.getStatus().name() + " with "
						+ healthResponse.getNumberOfNodes() + " nodes");
			} else {
				log.debug("... cluster state ok " + healthResponse.getStatus().name() + " with "
						+ healthResponse.getNumberOfNodes() + " nodes");
			}

			org.junit.Assert.assertEquals(expectedNodeCount, healthResponse.getNumberOfNodes());

			final NodesInfoResponse res = client.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet();

			final List<NodeInfo> nodes = res.getNodes();

			//final List<NodeInfo> masterNodes = nodes.stream().filter(n->n.getNode().getRoles().contains(Role.MASTER)).collect(Collectors.toList());
	        final List<NodeInfo> dataNodes = nodes.stream().filter(n->n.getNode().getRoles().contains(Role.DATA) && !n.getNode().getRoles().contains(Role.MASTER)).collect(Collectors.toList());
	        final List<NodeInfo> clientNodes = nodes.stream().filter(n->!n.getNode().getRoles().contains(Role.MASTER) && !n.getNode().getRoles().contains(Role.DATA)).collect(Collectors.toList());

	        if(!clientNodes.isEmpty()) {
	            NodeInfo nodeInfo = clientNodes.get(0);
	            if (nodeInfo.getHttp() != null && nodeInfo.getHttp().address() != null) {
                    final TransportAddress his = nodeInfo.getHttp().address()
                            .publishAddress();
                    clusterInfo.httpPort = his.getPort();
                    clusterInfo.httpHost = his.getAddress();
                    clusterInfo.httpAdresses.add(his);
                } else {
                    throw new RuntimeException("no http host/port for client node");
                }

                final TransportAddress is = nodeInfo.getTransport().getAddress()
                        .publishAddress();
                clusterInfo.nodePort = is.getPort();
                clusterInfo.nodeHost = is.getAddress();
	        } else if(!dataNodes.isEmpty()) {
	            
	            for (NodeInfo nodeInfo: dataNodes) {
	                final TransportAddress is = nodeInfo.getTransport().getAddress()
                            .publishAddress();
                    clusterInfo.nodePort = is.getPort();
                    clusterInfo.nodeHost = is.getAddress();
	                
	                if (nodeInfo.getHttp() != null && nodeInfo.getHttp().address() != null) {
	                    final TransportAddress his = nodeInfo.getHttp().address()
	                            .publishAddress();
	                    clusterInfo.httpPort = his.getPort();
	                    clusterInfo.httpHost = his.getAddress();
	                    clusterInfo.httpAdresses.add(his);
	                    break;
	                }  
	            }  
	        }  else  {
                
                for (NodeInfo nodeInfo: nodes) {
                    final TransportAddress is = nodeInfo.getTransport().getAddress()
                            .publishAddress();
                    clusterInfo.nodePort = is.getPort();
                    clusterInfo.nodeHost = is.getAddress();
                    
                    if (nodeInfo.getHttp() != null && nodeInfo.getHttp().address() != null) {
                        final TransportAddress his = nodeInfo.getHttp().address()
                                .publishAddress();
                        clusterInfo.httpPort = his.getPort();
                        clusterInfo.httpHost = his.getAddress();
                        clusterInfo.httpAdresses.add(his);
                        break;
                    }  
                }  
            }       	
		} catch (final ElasticsearchTimeoutException e) {
			throw new IOException(
					"timeout, cluster does not respond to health request, cowardly refusing to continue with operations");
		}
		return clusterInfo;
	}

	// @formatter:off
	private Settings.Builder getMinimumNonSgNodeSettingsBuilder(final int nodenum, final boolean masterNode,
			final boolean dataNode, final boolean tribeNode, int nodeCount, int masterCount, SortedSet<Integer> tcpPorts, int tcpPort, int httpPort) {

		return Settings.builder()
		        .put("node.name", "node_"+clustername+ "_num" + nodenum)
		        .put("node.data", dataNode)
				.put("node.master", masterNode)
				.put("cluster.name", clustername)
				.put("path.data", "data/"+clustername+"/data")
				.put("path.logs", "data/"+clustername+"/logs")
				.put("node.max_local_storage_nodes", nodeCount)
				.put("discovery.zen.minimum_master_nodes", minMasterNodes(masterCount))
				.put("discovery.zen.no_master_block", "all")
				.put("discovery.zen.fd.ping_timeout", "5s")
				.put("discovery.initial_state_timeout","8s")
				.putList("discovery.zen.ping.unicast.hosts", tcpPorts.stream().map(s->"127.0.0.1:"+s).collect(Collectors.toList()))
				.put("transport.tcp.port", tcpPort)
				.put("http.port", httpPort)
				.put("http.enabled", true)
				.put("cluster.routing.allocation.disk.threshold_enabled", false)
				.put("http.cors.enabled", true)
				.put("path.home", ".");
	}
	// @formatter:on
	
	private int minMasterNodes(int masterEligibleNodes) {
	    if(masterEligibleNodes <= 0) {
	        throw new IllegalArgumentException("no master eligible nodes");
	    }
	    
	    return (masterEligibleNodes/2) + 1;
	    	    
	}
}
