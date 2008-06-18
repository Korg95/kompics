package se.sics.kompics.p2p.son.ps.events;

import java.io.Serializable;
import java.util.List;

import se.sics.kompics.api.Event;
import se.sics.kompics.api.annotation.EventType;
import se.sics.kompics.network.Address;

/**
 * The <code>GetRingNeighborsResponse</code> class
 * 
 * @author Cosmin Arad
 * @version $Id: GetRingNeighborsResponse.java 158 2008-06-16 10:42:01Z Cosmin $
 */
@EventType
public final class GetRingNeighborsResponse implements Event, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -28992129362440344L;

	private final Address localPeer;

	private final Address predecessorPeer;

	private final Address successorPeer;

	private final List<Address> successorList;

	public GetRingNeighborsResponse(Address localPeer, Address successor,
			Address predecessor, List<Address> successorList) {
		super();
		this.localPeer = localPeer;
		this.predecessorPeer = predecessor;
		this.successorPeer = successor;
		this.successorList = successorList;
	}

	public Address getLocalPeer() {
		return localPeer;
	}

	public Address getSuccessorPeer() {
		return successorPeer;
	}

	public Address getPredecessorPeer() {
		return predecessorPeer;
	}

	public List<Address> getSuccessorList() {
		return successorList;
	}
}
