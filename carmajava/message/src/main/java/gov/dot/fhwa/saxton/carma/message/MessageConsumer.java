/*
 * TODO: Copyright (C) 2017 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.message;

import cav_msgs.*;
import cav_srvs.*;
import gov.dot.fhwa.saxton.carma.rosutils.AlertSeverity;
import gov.dot.fhwa.saxton.carma.rosutils.SaxtonBaseNode;
import gov.dot.fhwa.saxton.carma.rosutils.RosServiceSynchronizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.ros.message.MessageListener;
import org.ros.node.topic.Subscriber;
import org.ros.concurrent.CancellableLoop;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.topic.Publisher;

//Services
import org.ros.node.service.ServiceClient;
import org.ros.node.service.ServiceResponseListener;
import org.ros.exception.RemoteException;
import org.ros.exception.RosRuntimeException;

/*
 * The Message package is part of the Vehicle Environment package.
 * It processes all V2V and V2I messages coming from Drivers.Comms ROS node.
 * Command line test: rosrun carma message gov.dot.fhwa.saxton.carma.message.MessageConsumer
 * rostopic pub /system_alert cav_msgs/SystemAlert '{type: 5, description: hello}'
 * rosparam set /interface_mgr/driver_wait_time 10
 * rosrun carma interfacemgr gov.dot.fhwa.saxton.carma.interfacemgr.InterfaceMgr
 * rostopic pub /saxton_cav/drivers/arada_application/comms/recv cav_msgs/ByteArray '{messageType: "BSM"}'
 * rostopic pub /host_bsm cav_msgs/BSM '{}'
 */

public class MessageConsumer extends SaxtonBaseNode {

	private boolean driversReady = false;

	// Publishers
	private Publisher<ByteArray> outboundPub; //outgoing byte array, after encode
	private Publisher<BSM> bsmPub; //incoming BSM, after decoded
	// protected Publisher<cav_msgs.MobilityAck> mobilityAckPub;
	// protected Publisher<cav_msgs.MobilityGreeting> mobilityGreetingPub;
	// protected Publisher<cav_msgs.MobilityIntro> mobilityIntroPub;
	// protected Publisher<cav_msgs.MobilityNack> mobilityNAckPub;
	// protected Publisher<cav_msgs.MobilityPlan> mobilityPlanPub;
	// protected Publisher<cav_msgs.Map> mapPub;
	// protected Publisher<cav_msgs.Spat> spatPub;
	// protected Publisher<cav_msgs.Tim> timPub;

	// Subscribers
	private Subscriber<SystemAlert> alertSub;
	private Subscriber<ByteArray> inboundSub; //incoming byte array, need to decode
	private Subscriber<BSM> bsmSub; //outgoing BSM, need to encode
	// protected Subscriber<cav_msgs.MobilityAck> mobilityAckOutboundSub;
	// protected Subscriber<cav_msgs.MobilityGreeting> mobilityGreetingOutboundSub;
	// protected Subscriber<cav_msgs.MobilityIntro> mobilityIntroOutboundSub;
	// protected Subscriber<cav_msgs.MobilityNack> mobilityNAckOutboundSub;
	// protected Subscriber<cav_msgs.MobilityPlan> mobilityPlanOutboundSub;

	// Used Services
	private ServiceClient<GetDriversWithCapabilitiesRequest, GetDriversWithCapabilitiesResponse> getDriversWithCapabilitiesClient;
	private List<String> drivers_data = new ArrayList<>();

	//Log for this node
	private Log log = null;
	
	//Connected Node
	private ConnectedNode connectedNode = null;
	
	@Override
	public GraphName getDefaultNodeName() {
		return GraphName.of("message_consumer");
	}

	@Override
	public void onSaxtonStart(final ConnectedNode connectedNode) {
		
		//initialize connectedNode and log
		this.connectedNode = connectedNode;
		this.log = connectedNode.getLog();
		
		//initialize alert sub, pub
		alertSub = connectedNode.newSubscriber("system_alert", SystemAlert._TYPE);
		alertSub.addMessageListener(new MessageListener<SystemAlert>() {
			@Override
			public void onNewMessage(SystemAlert message) {
				try {
					if(message.getType() == SystemAlert.FATAL || message.getType() == SystemAlert.SHUTDOWN) {
						connectedNode.shutdown();
					}
					else if(message.getType() == SystemAlert.DRIVERS_READY) {
						driversReady = true;
					}
				} catch (Exception e) {
					handleException(e);
				}
			}
		});
		
		//initialize bsm pub
		bsmPub = connectedNode.newPublisher("incoming_bsm", BSM._TYPE);
		
		//Use cav_srvs.GetDriversWithCapabilities and wait for driversReady signal
		try {
			while(getDriversWithCapabilitiesClient == null) {
				if(driversReady) {
					getDriversWithCapabilitiesClient = this.waitForService("get_drivers_with_capabilities", GetDriversWithCapabilities._TYPE, connectedNode, 5000);
					if(getDriversWithCapabilitiesClient == null) {
						log.warn(connectedNode.getName() + " Node could not find service get_drivers_with_capabilities and is keeping trying...");
					}
				}
				Thread.sleep(1000);
			}	
		} catch (Exception e) {
			handleException(e);
		}
		
		//initialize outboundPub
		while(outboundPub == null) {
			if(driversReady) {
				try {
					GetDriversWithCapabilitiesRequest request = getDriversWithCapabilitiesClient.newMessage();
					request.setCapabilities(Arrays.asList("inbound_binary_msg", "outbound_binary_msg"));
					int counter = 0;
					while(drivers_data.size() == 0 && counter++ < 10) {
						RosServiceSynchronizer.callSync(getDriversWithCapabilitiesClient, request, new ServiceResponseListener<GetDriversWithCapabilitiesResponse>() {
							
							@Override
							public void onSuccess(GetDriversWithCapabilitiesResponse response) {
								drivers_data = response.getDriverData();
								log.info("MessageConsumer GetDriversWithCapabilitiesResponse: " + drivers_data);
							}
							
							@Override
							public void onFailure(RemoteException e) {
								throw new RosRuntimeException(e);
							}
							
						});
					}
					
					if(counter == 10 && drivers_data.size() == 0) {
						throw new RosRuntimeException("MessageConsumer can not find drivers.");
					}
					
					String J2735_inbound_binary_msg = null;
					String J2735_outbound_binary_msg = null;
					
					for(String s : drivers_data) {
						if(s.endsWith("/arada_application/comms/inbound_binary_msg") || s.endsWith("/dsrc/comms/inbound_binary_msg")) {
							J2735_inbound_binary_msg = s;
						}
						else if(s.endsWith("/arada_application/comms/outbound_binary_msg") || s.endsWith("/dsrc/comms/outbound_binary_msg")) {
							J2735_outbound_binary_msg = s;
						}
					}
					
					if(J2735_inbound_binary_msg == null || J2735_outbound_binary_msg == null) {
						log.error("MessageConsumer unable to find suitable dsrc driver!");
						throw new RosRuntimeException("Unable to find suitable dsrc driver!");
					}
					
					outboundPub = connectedNode.newPublisher(J2735_outbound_binary_msg, ByteArray._TYPE);
					inboundSub = connectedNode.newSubscriber(J2735_inbound_binary_msg, ByteArray._TYPE);
					
				} catch(Exception e) {
					handleException(e);
				}
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				handleException(e);
			}
		}
		
		//Subscribers
		bsmSub = connectedNode.newSubscriber("outgoing_bsm", BSM._TYPE);
		bsmSub.addMessageListener(new MessageListener<BSM>() {
			@Override
			public void onNewMessage(BSM bsm) {
				try {
					if(outboundPub != null && driversReady) {
						log.info("MessageConsumer received BSM. Calling factory to encode data...");
						ByteArray byteArray = outboundPub.newMessage();
						BSMFactory.encode(bsm, byteArray, log);
						log.info("MessageConsumer finished encoding BSM and is going to publish...");
						outboundPub.publish(byteArray);
					}
				} catch (NullPointerException exx) {
					log.info("MessageConsumer: BSM message is not ready");
				} catch (IllegalArgumentException ex) {
					log.info("MessageConsumer: Invalid BSM is not published");
				} catch (Exception e) {
					handleException(e);
				}
			}
		});

		inboundSub.addMessageListener(new MessageListener<ByteArray>() {

			@Override
			public void onNewMessage(ByteArray arg0) {
				BSM decodedBSM = bsmPub.newMessage();
				decodedBSM.getHeader().setFrameId("MessageNode");
				BSMFactory.decode(arg0, decodedBSM, log);
				bsmPub.publish(decodedBSM);
			}
			
		});
		
		// This CancellableLoop will be canceled automatically when the node shuts down.
		connectedNode.executeCancellableLoop(new CancellableLoop() {
			private int sequenceNumber;
			@Override
			protected void setup() {
				sequenceNumber = 0;
			}
			@Override
			protected void loop() throws InterruptedException {
				sequenceNumber++;
				Thread.sleep(1000);
			}
		});

	}

	/***
	 * Handles unhandled exceptions and reports to SystemAlert topic, and log the alert.
	 * @param e The exception to handle
	 */
	@Override
	protected void handleException(Throwable e) {

		String msg = "Uncaught exception in " + connectedNode.getName() + " caught by handleException";
		publishSystemAlert(AlertSeverity.FATAL, msg, e);
		connectedNode.shutdown();
	}
}
