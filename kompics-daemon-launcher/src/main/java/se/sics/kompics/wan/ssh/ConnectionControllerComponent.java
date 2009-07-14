package se.sics.kompics.wan.ssh;

import se.sics.kompics.Component;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Start;
import se.sics.kompics.wan.master.plab.plc.PLControllerComponent;
import se.sics.kompics.wan.master.plab.plc.PLControllerInit;
import se.sics.kompics.wan.master.plab.rpc.ControllerPort;

public class ConnectionControllerComponent extends ComponentDefinition {

	Negative<ControllerPort> ccPort = negative(ControllerPort.class);
	
	private Component plController;

	private Component sshConnections;


	public ConnectionControllerComponent() {
		plController = create(PLControllerComponent.class);
		sshConnections = create(SshComponent.class);
		
		
		subscribe(handleControllerInit, control);
		subscribe(handleStart, control);
	}

	private Handler<ControllerInit> handleControllerInit = new Handler<ControllerInit>() {
		public void handle(ControllerInit event) {

			PLControllerInit pInit = new PLControllerInit(event.getCredentials());
			trigger(pInit, plController.getControl());
		}
	};

	private Handler<Start> handleStart = new Handler<Start>() {
		public void handle(Start event) {

		}
	};

}