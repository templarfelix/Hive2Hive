package org.hive2hive.core.test.processes.implementations.common.base;

import java.util.List;

import net.tomp2p.peers.Number160;

import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.network.data.IDataManager;
import org.hive2hive.core.network.data.NetworkContent;
import org.hive2hive.core.processes.framework.exceptions.InvalidProcessStateException;
import org.hive2hive.core.processes.framework.exceptions.ProcessExecutionException;
import org.hive2hive.core.processes.implementations.common.base.BaseGetProcessStep;
import org.hive2hive.core.test.H2HJUnitTest;
import org.hive2hive.core.test.H2HTestData;
import org.hive2hive.core.test.network.NetworkTestUtil;
import org.hive2hive.core.test.processes.util.TestProcessComponentListener;
import org.hive2hive.core.test.processes.util.UseCaseTestUtil;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the {@link BaseGetProcessStep} class. Checks the methods to properly trigger success or rollback.
 * 
 * @author Seppi
 */
public class BaseGetProcessStepTest extends H2HJUnitTest {

	private final static int networkSize = 2;
	private static List<NetworkManager> network;

	@BeforeClass
	public static void initTest() throws Exception {
		testClass = BaseGetProcessStepTest.class;
		beforeClass();
		network = NetworkTestUtil.createNetwork(networkSize);
	}

	@Test
	public void testGetProcessStepSuccess() throws NoPeerConnectionException {
		H2HTestData data = new H2HTestData(NetworkTestUtil.randomString());
		NetworkManager getter = network.get(0);
		NetworkManager holder = network.get(1);

		String locationKey = holder.getNodeId();
		Number160 lKey = Number160.createHash(locationKey);
		Number160 dKey = Number160.ZERO;
		String contentKey = NetworkTestUtil.randomString();
		Number160 cKey = Number160.createHash(contentKey);

		// put in the memory of 2nd peer
		holder.getDataManager().put(lKey, dKey, cKey, data, null).awaitUninterruptibly();

		TestGetProcessStep getStep = new TestGetProcessStep(locationKey, contentKey, getter.getDataManager());
		UseCaseTestUtil.executeProcess(getStep);

		Assert.assertEquals(data.getTestString(), ((H2HTestData) getStep.getContent()).getTestString());
	}

	@Test
	public void testGetProcessStepRollBack() throws NoPeerConnectionException, InvalidProcessStateException {
		NetworkManager getter = network.get(0);
		NetworkManager holder = network.get(1);

		String locationKey = holder.getNodeId();
		String contentKey = NetworkTestUtil.randomString();

		TestGetProcessStepRollBack getStepRollBack = new TestGetProcessStepRollBack(locationKey, contentKey,
				getter.getDataManager());
		TestProcessComponentListener listener = new TestProcessComponentListener();
		getStepRollBack.attachListener(listener);
		getStepRollBack.start();

		// wait for the process to finish
		UseCaseTestUtil.waitTillFailed(listener, 10);

		Assert.assertNull(getStepRollBack.getContent());
	}

	/**
	 * A simple get process step which always succeeds.
	 * 
	 * @author Seppi
	 */
	private class TestGetProcessStep extends BaseGetProcessStep {

		private final String locationKey;
		private final String contentKey;

		private NetworkContent content;

		public TestGetProcessStep(String locationKey, String contentKey, IDataManager dataManager) {
			super(dataManager);
			this.locationKey = locationKey;
			this.contentKey = contentKey;
		}

		@Override
		protected void doExecute() throws InvalidProcessStateException {
			this.content = get(locationKey, contentKey);
		}

		public NetworkContent getContent() {
			return content;
		}
	}

	/**
	 * A simple get process step which always roll backs.
	 * 
	 * @author Seppi
	 */
	private class TestGetProcessStepRollBack extends BaseGetProcessStep {

		private final String locationKey;
		private final String contentKey;

		private NetworkContent content;

		public TestGetProcessStepRollBack(String locationKey, String contentKey, IDataManager dataManager) {
			super(dataManager);
			this.locationKey = locationKey;
			this.contentKey = contentKey;
		}

		@Override
		protected void doExecute() throws InvalidProcessStateException, ProcessExecutionException {
			NetworkContent content = get(locationKey, contentKey);
			this.content = content;
			if (content == null)
				throw new ProcessExecutionException("Content is null.");
		}

		public NetworkContent getContent() {
			return content;
		}
	}

	@AfterClass
	public static void cleanAfterClass() {
		NetworkTestUtil.shutdownNetwork(network);
		afterClass();
	}

}