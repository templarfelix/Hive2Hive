package org.hive2hive.core.network.messages.direct.testmessages;

import net.tomp2p.peers.PeerAddress;

import org.hive2hive.core.H2HConstants;
import org.hive2hive.core.H2HJUnitTest;
import org.hive2hive.core.H2HTestData;
import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.network.data.parameters.Parameters;
import org.hive2hive.core.network.messages.AcceptanceReply;
import org.hive2hive.core.network.messages.direct.response.IResponseCallBackHandler;
import org.hive2hive.core.network.messages.direct.response.ResponseMessage;
import org.hive2hive.core.network.messages.request.DirectRequestMessage;
import org.junit.Assert;

public class TestDirectMessageWithReply extends DirectRequestMessage {

	private static final long serialVersionUID = 718405880279379041L;

	private final String contentKey;

	public TestDirectMessageWithReply(PeerAddress targetAddress, String contentKey) {
		super(targetAddress);
		this.contentKey = contentKey;
	}

	@Override
	public void run() {
		String secret = H2HJUnitTest.randomString();

		try {
			networkManager.getDataManager().put(
					new Parameters().setLocationKey(networkManager.getNodeId()).setContentKey(contentKey)
							.setNetworkContent(new H2HTestData(secret)));
		} catch (NoPeerConnectionException e) {
			Assert.fail();
		}

		sendDirectResponse(createResponse(secret));
	}

	@Override
	public AcceptanceReply accept() {
		return AcceptanceReply.OK;
	}

	public class TestCallBackHandler implements IResponseCallBackHandler {

		private final NetworkManager networkManager;

		public TestCallBackHandler(NetworkManager aNetworkManager) {
			networkManager = aNetworkManager;
		}

		@Override
		public void handleResponseMessage(ResponseMessage responseMessage) {
			String receivedSecret = (String) responseMessage.getContent();
			try {
				networkManager.getDataManager().put(
						new Parameters().setLocationKey(networkManager.getNodeId()).setContentKey(contentKey)
								.setNetworkContent(new H2HTestData(receivedSecret)));
			} catch (NoPeerConnectionException e) {
				Assert.fail();
			}
		}

	}

	@Override
	public int getDirectDownloadWaitMs()
	{
		return H2HConstants.DIRECT_DOWNLOAD_AWAIT_MS;
	}

}
