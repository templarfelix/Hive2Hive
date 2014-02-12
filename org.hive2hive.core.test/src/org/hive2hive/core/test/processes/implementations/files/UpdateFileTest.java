package org.hive2hive.core.test.processes.implementations.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.hive2hive.core.H2HConstants;
import org.hive2hive.core.H2HSession;
import org.hive2hive.core.api.configs.IFileConfiguration;
import org.hive2hive.core.exceptions.GetFailedException;
import org.hive2hive.core.exceptions.IllegalFileLocation;
import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.exceptions.NoSessionException;
import org.hive2hive.core.model.FileIndex;
import org.hive2hive.core.model.Index;
import org.hive2hive.core.model.MetaFile;
import org.hive2hive.core.model.UserProfile;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.processes.ProcessFactory;
import org.hive2hive.core.processes.framework.exceptions.InvalidProcessStateException;
import org.hive2hive.core.processes.framework.interfaces.IProcessComponent;
import org.hive2hive.core.security.EncryptionUtil;
import org.hive2hive.core.security.H2HEncryptionUtil;
import org.hive2hive.core.security.UserCredentials;
import org.hive2hive.core.test.H2HJUnitTest;
import org.hive2hive.core.test.H2HWaiter;
import org.hive2hive.core.test.file.FileTestUtil;
import org.hive2hive.core.test.integration.TestFileConfiguration;
import org.hive2hive.core.test.network.NetworkTestUtil;
import org.hive2hive.core.test.processes.util.TestProcessComponentListener;
import org.hive2hive.core.test.processes.util.UseCaseTestUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests uploading a new version of a file.
 * 
 * @author Nico
 * 
 */
public class UpdateFileTest extends H2HJUnitTest {

	private final int networkSize = 5;
	private final IFileConfiguration config = new TestFileConfiguration();
	private List<NetworkManager> network;
	private UserCredentials userCredentials;
	private Path root;
	private File file;

	private NetworkManager uploader;
	private NetworkManager downloader;

	@BeforeClass
	public static void initTest() throws Exception {
		testClass = UpdateFileTest.class;
		beforeClass();

	}

	@Before
	public void createProfileUploadBaseFile() throws IOException, IllegalFileLocation, NoSessionException,
			NoPeerConnectionException {
		network = NetworkTestUtil.createNetwork(networkSize);
		NetworkManager registrar = network.get(0);
		uploader = network.get(1);
		downloader = network.get(2);

		userCredentials = NetworkTestUtil.generateRandomCredentials();

		// create the roots and the file manager
		File rootUploader = new File(System.getProperty("java.io.tmpdir"), NetworkTestUtil.randomString());
		root = rootUploader.toPath();
		File rootDownloader = new File(System.getProperty("java.io.tmpdir"), NetworkTestUtil.randomString());

		// register a user
		UseCaseTestUtil.register(userCredentials, registrar);
		UseCaseTestUtil.login(userCredentials, uploader, rootUploader);
		UseCaseTestUtil.login(userCredentials, downloader, rootDownloader);

		// create a file
		file = FileTestUtil.createFileRandomContent(3, rootUploader, config);
		UseCaseTestUtil.uploadNewFile(uploader, file);
	}

	@Test
	public void testUploadNewVersion() throws IOException, GetFailedException, NoSessionException,
			NoPeerConnectionException {
		// overwrite the content in the file
		String newContent = NetworkTestUtil.randomString();
		FileUtils.write(file, newContent, false);
		byte[] md5UpdatedFile = EncryptionUtil.generateMD5Hash(file);

		// upload the new version
		UseCaseTestUtil.uploadNewVersion(uploader, file);

		// download the file and check if version is newer
		File downloaderRoot = FileUtils.getTempDirectory();
		UseCaseTestUtil.login(userCredentials, downloader, downloaderRoot);
		File downloaded = new File(downloaderRoot, file.getName());

		// give some time to synchronize
		H2HWaiter waiter = new H2HWaiter(10);
		while (!downloaded.exists()) {
			waiter.tickASecond();
		}

		// new content should be latest one
		Assert.assertEquals(newContent, FileUtils.readFileToString(downloaded));

		// check the md5 hash
		Assert.assertTrue(H2HEncryptionUtil.compareMD5(downloaded, md5UpdatedFile));
	}

	@Test
	public void testUploadSameVersion() throws IllegalFileLocation, GetFailedException, IOException,
			NoSessionException, InvalidProcessStateException, IllegalArgumentException,
			NoPeerConnectionException {
		// upload the same content again
		IProcessComponent process = ProcessFactory.instance().createUpdateFileProcess(file, uploader);
		TestProcessComponentListener listener = new TestProcessComponentListener();
		process.attachListener(listener);
		process.start();

		H2HWaiter waiter = new H2HWaiter(60);
		do {
			waiter.tickASecond();
		} while (!listener.hasFailed());

		// verify if the md5 hash did not change
		UserProfile userProfile = UseCaseTestUtil.getUserProfile(downloader, userCredentials);
		FileIndex fileNode = (FileIndex) userProfile.getFileByPath(file, root);
		Assert.assertTrue(H2HEncryptionUtil.compareMD5(file, fileNode.getMD5()));

		// verify that only one version was created
		MetaFile metaDocument = (MetaFile) UseCaseTestUtil
				.getMetaDocument(downloader, fileNode.getFileKeys());
		Assert.assertEquals(1, metaDocument.getVersions().size());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNewFolderVersion() throws IllegalFileLocation, NoSessionException,
			NoPeerConnectionException {
		// new folder version is illegal
		File folder = new File(root.toFile(), "test-folder");
		folder.mkdir();

		// upload the file
		UseCaseTestUtil.uploadNewFile(uploader, folder);

		// try to upload the same folder again (which is invalid)
		UseCaseTestUtil.uploadNewVersion(uploader, folder);
	}

	@Test
	public void testCleanupMaxNumVersions() throws IOException, GetFailedException, NoSessionException,
			IllegalArgumentException, NoPeerConnectionException {
		// overwrite config
		IFileConfiguration limitingConfig = new IFileConfiguration() {

			@Override
			public long getMaxSizeAllVersions() {
				return Integer.MAX_VALUE;
			}

			@Override
			public long getMaxNumOfVersions() {
				return 1;
			}

			@Override
			public long getMaxFileSize() {
				return Integer.MAX_VALUE;
			}

			@Override
			public long getChunkSize() {
				return H2HConstants.DEFAULT_CHUNK_SIZE;
			}
		};

		H2HSession session = uploader.getSession();
		H2HSession newSession = new H2HSession(session.getKeyPair(), session.getProfileManager(),
				limitingConfig, session.getRoot());
		uploader.setSession(newSession);

		// update the file
		FileUtils.write(file, "bla", false);
		UseCaseTestUtil.uploadNewVersion(uploader, file);

		// verify that only one version is online
		UserProfile userProfile = UseCaseTestUtil.getUserProfile(downloader, userCredentials);
		Index fileNode = userProfile.getFileByPath(file, root);
		MetaFile metaDocument = (MetaFile) UseCaseTestUtil
				.getMetaDocument(downloader, fileNode.getFileKeys());
		Assert.assertEquals(1, metaDocument.getVersions().size());
	}

	@Test
	public void testCleanupMaxSize() throws IOException, GetFailedException, NoSessionException,
			IllegalArgumentException, NoPeerConnectionException {
		// overwrite config and set the currently max limit
		final long fileSize = file.length();
		IFileConfiguration limitingConfig = new IFileConfiguration() {

			@Override
			public long getMaxSizeAllVersions() {
				return fileSize;
			}

			@Override
			public long getMaxNumOfVersions() {
				return Long.MAX_VALUE;
			}

			@Override
			public long getMaxFileSize() {
				return Long.MAX_VALUE;
			}

			@Override
			public long getChunkSize() {
				return H2HConstants.DEFAULT_CHUNK_SIZE;
			}
		};

		H2HSession session = uploader.getSession();
		H2HSession newSession = new H2HSession(session.getKeyPair(), session.getProfileManager(),
				limitingConfig, session.getRoot());
		uploader.setSession(newSession);

		// update the file (append some data)
		FileUtils.write(file, NetworkTestUtil.randomString(), true);
		FileUtils.write(file, NetworkTestUtil.randomString(), true);

		UseCaseTestUtil.uploadNewVersion(uploader, file);

		// verify that only one version is online
		UserProfile userProfile = UseCaseTestUtil.getUserProfile(downloader, userCredentials);
		Index fileNode = userProfile.getFileByPath(file, root);
		MetaFile metaDocument = (MetaFile) UseCaseTestUtil
				.getMetaDocument(downloader, fileNode.getFileKeys());
		Assert.assertEquals(1, metaDocument.getVersions().size());
	}

	@After
	public void deleteAndShutdown() throws IOException {
		NetworkTestUtil.shutdownNetwork(network);
		FileUtils.deleteDirectory(root.toFile());
	}

	@AfterClass
	public static void endTest() throws IOException {
		afterClass();
	}
}
