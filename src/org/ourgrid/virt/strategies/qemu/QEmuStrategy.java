package org.ourgrid.virt.strategies.qemu;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.AccessController;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;

import org.alfresco.jlan.server.NetworkServer;
import org.alfresco.jlan.server.ServerListener;
import org.alfresco.jlan.smb.server.SMBServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.ourgrid.virt.model.ExecutionResult;
import org.ourgrid.virt.model.SharedFolder;
import org.ourgrid.virt.model.VirtualMachine;
import org.ourgrid.virt.model.VirtualMachineConstants;
import org.ourgrid.virt.model.VirtualMachineStatus;
import org.ourgrid.virt.strategies.HypervisorStrategy;
import org.ourgrid.virt.strategies.HypervisorUtils;

public class QEmuStrategy implements HypervisorStrategy {

	private static final int RANDOM_PORT_RETRIES = 5;

	private static final int QMP_CAPABILITY_WAIT = 5000;

	private static final Logger LOGGER = Logger.getLogger(QEmuStrategy.class);

	private static final String RESTORE_SNAPSHOT = "RESTORE_SNAPSHOT";
	private static final String PROCESS = "PROCESS";
	private static final String POWERED_OFF = "POWERED_OFF";
	private static final String QMP_PORT = "QMP_PORT";
	private static final String CIFS_SERVER = "CIFS_SERVER";
	private static final String HDA_FILE = "HDA_FILE";
	private static final String SHARED_FOLDERS = "SHARED_FOLDERS";
	
	private static final String CIFS_DEVICE = "10.0.2.100";
	private static final String CIFS_PORT_GUEST = "9999";
	private static final String CURRENT_SNAPSHOT = "current";

	private static final int START_RECHECK_DELAY = 10;
	private static final int DEF_CONNECTION_TIMEOUT = 120;

	private static final int CPU_TIME_INDEX = 10;

	private String qemuLocation = System.getProperty("qemu.home");
	private String vmProcessPid;

	@Override
	public void start(final VirtualMachine virtualMachine) throws Exception {
		String hda = virtualMachine
				.getProperty(VirtualMachineConstants.DISK_IMAGE_PATH);
		String memory = virtualMachine
				.getProperty(VirtualMachineConstants.MEMORY);

		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("-net nic").append(" -net user");

		String netType = virtualMachine
				.getProperty(VirtualMachineConstants.NETWORK_TYPE);
		if (netType != null && netType.equals("host-only")) {
			Integer sshPort = randomPort();
			strBuilder.append(",restrict=yes,hostfwd=tcp:127.0.0.1:").append(
					sshPort).append("-:22");
			virtualMachine.setProperty(VirtualMachineConstants.IP, "localhost");
			virtualMachine.setProperty(VirtualMachineConstants.SSH_PORT,
					sshPort);
		}

		if (virtualMachine.getProperty(SHARED_FOLDERS) != null) {
			Integer cifsPort = randomPort();
			strBuilder.append(",guestfwd=tcp:").append(CIFS_DEVICE).append(":")
					.append(CIFS_PORT_GUEST).append("-tcp:127.0.0.1:")
					.append(cifsPort);
			createSMBServer(virtualMachine, cifsPort);
		}

		strBuilder.append(" -m ").append(memory);
		strBuilder.append(" -nodefconfig");
		Integer qmpPort = randomPort();
		strBuilder.append(" -qmp tcp:127.0.0.1:").append(qmpPort)
				.append(",server,nowait,nodelay");
		virtualMachine.setProperty(QMP_PORT, qmpPort);

		String snapshot = virtualMachine.getProperty(RESTORE_SNAPSHOT);
		String snapshotLocation = getSnapshotLocation(virtualMachine,
				CURRENT_SNAPSHOT);

		if (snapshot != null && new File(snapshotLocation).exists()) {
			strBuilder.append(" -hda \"").append(snapshotLocation).append("\"");
			virtualMachine.setProperty(HDA_FILE, snapshotLocation);
		} else {
			strBuilder.append(" -hda \"").append(hda).append("\"");
			virtualMachine.setProperty(HDA_FILE, hda);
		}

		if (checkKVM()) {
			strBuilder.append(" -enable-kvm");
		}

		try {
			kill(virtualMachine);
		} catch (Exception e) {
			// Best effort
		}
		
		strBuilder.append(" & echo $!");
		
		final ProcessBuilder builder = getSystemProcessBuilder(strBuilder.toString());
		
		final LinkedBlockingQueue<Object> lbq = new LinkedBlockingQueue<Object>();
		
		SMBServer cifsServer = virtualMachine.getProperty(CIFS_SERVER);
		if (cifsServer != null) {
			cifsServer.addServerListener(new ServerListener() {
				@Override
				public void serverStatusEvent(NetworkServer server, int event) {
					if (event == ServerListener.ServerActive) {
						try {
							startQEmuProcess(virtualMachine, builder);
							lbq.add(Void.class);
						} catch (Exception e) {
							lbq.add(e);
						}
					} else if (event == ServerListener.ServerError) {
						lbq.add(server.getException());
					}
				}
			});
			
			cifsServer.startServer();
		} else {
			startQEmuProcess(virtualMachine, builder);
			lbq.add(Void.class);
		}
		
		Object flag = lbq.take();
		if (flag instanceof Exception) {
			throw (Exception)flag;
		}
		
		checkOSStarted(virtualMachine);
	}

	private void startQEmuProcess(final VirtualMachine virtualMachine,
			ProcessBuilder builder) throws IOException, 
			SecurityException, NoSuchFieldException, 
			IllegalArgumentException, IllegalAccessException {
		virtualMachine.setProperty(PROCESS, builder.start());
		virtualMachine.setProperty(POWERED_OFF, null);

		Runnable runnable = new Runnable() {
			public void run() {
				try {
					stop(virtualMachine);
				} catch (Exception e) {
					// Best effort
				}
			}
		};
		Runtime.getRuntime().addShutdownHook(new Thread(runnable));
	}

	private boolean checkKVM() {
		if (!HypervisorUtils.isLinuxHost()) {
			return false;
		}
		if (!new File("/dev/kvm").exists()) {
			return false;
		}
		
		try {
			FilePermission fp = new FilePermission("/dev/kvm", "read,write");
			AccessController.checkPermission(fp);
		} catch (Exception e) {
			return false;
		}
		
		try {
			return new ProcessBuilder("kvm-ok").start().waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private void createSMBServer(VirtualMachine virtualMachine, Integer smbPort)
			throws Exception {
		Map<String, SharedFolder> sharedFolders = virtualMachine
				.getProperty(SHARED_FOLDERS);
		if (sharedFolders == null) {
			return;
		}
		String user = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_USER);
		String password = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_PASSWORD);
		SMBServer server = EmbeddedCifsServer.create(sharedFolders.values(),
				user, password, smbPort);
		virtualMachine.setProperty(CIFS_SERVER, server);
	}

	private static Integer randomPort() {
		int retries = RANDOM_PORT_RETRIES;
		while (retries-- > 0) {
			try {
				Integer port = new Random().nextInt(10000) + 50000;
				ServerSocket socket = new ServerSocket(port);
				socket.close();
				return port;
			} catch (Exception e) {}
		}
		throw new IllegalStateException("Could not find a suitable random port");
	}

	private void checkOSStarted(VirtualMachine virtualMachine) throws Exception {
		String startTimeout = virtualMachine
				.getProperty(VirtualMachineConstants.START_TIMEOUT);
		boolean checkTimeout = startTimeout != null;

		int remainingTries = 0;
		if (checkTimeout) {
			remainingTries = Integer.parseInt(startTimeout)
					/ START_RECHECK_DELAY;
		}

		while (true) {
			Exception ex = null;
			
			try {
				verifyProcessRunning(virtualMachine);
			} catch (Exception e) {
				stopCIFS(virtualMachine);
				throw e;
			}
			
			try {
				if (HypervisorUtils.isLinuxGuest(virtualMachine)) {
					createSSHClient(virtualMachine).disconnect();
					break;
				} else {
					ex = new Exception("Guest OS not supported");
				}
			} catch (Exception e) {
				if (checkTimeout && remainingTries-- == 0) {
					ex = new Exception(
							"Virtual Machine OS was not started. Please check you credentials.", e);
				}
			}

			if (ex != null) {
				stopCIFS(virtualMachine);
				throw ex;
			}
			Thread.sleep(1000 * START_RECHECK_DELAY);
		}
	}

	private void verifyProcessRunning(VirtualMachine virtualMachine)
			throws Exception {
		Process process = virtualMachine.getProperty(PROCESS);
		
		if (vmProcessPid == null) {
			storeVMProcessPid(process);
		}
		
		String psCmd = "ps h -p " + vmProcessPid;
		
		ProcessBuilder pBuilder = getProcessBuilder(psCmd);
		
		Process ps = pBuilder.start();
		
		ps.waitFor();
		String processStr = IOUtils.toString(ps.getInputStream());
		if (processStr.length() < 1) {
			//The process with given PID does not exist
			List<String> stderr = IOUtils.readLines(process.getInputStream());
			List<String> stdout = IOUtils.readLines(process.getErrorStream());
			
			throw new Exception("Virtual Machine was forcibly terminated. "
					+ "Stdout [" + stdout + "] stderr [" + stderr + "]");
		}
	}

	private SSHClient createSSHClient(VirtualMachine virtualMachine)
			throws Exception {
		String ip = (String) getProperty(virtualMachine,
				VirtualMachineConstants.IP);
		Integer sshPort = (Integer) getProperty(virtualMachine,
				VirtualMachineConstants.SSH_PORT);

		if (ip == null) {
			throw new Exception("Could not acquire IP.");
		}

		if (sshPort == null) {
			sshPort = VirtualMachineConstants.DEFAULT_SSH_PORT;
		}

		SSHClient ssh = new SSHClient();
		ssh.addHostKeyVerifier(createBlankHostKeyVerifier());
		ssh.connect(ip, sshPort);
		return ssh;
	}

	private HostKeyVerifier createBlankHostKeyVerifier() {
		return new HostKeyVerifier() {
			@Override
			public boolean verify(String arg0, int arg1, PublicKey arg2) {
				return true;
			}
		};
	}

	@Override
	public void stop(VirtualMachine virtualMachine) throws Exception {
		stopCIFS(virtualMachine);
		
		runQMPCommand(virtualMachine, "quit");

		Process p = virtualMachine.getProperty(PROCESS);
		p.destroy();
		
		try {
			kill(virtualMachine);
		} catch (Exception e) {
			// Best effort
		}
		
		virtualMachine.setProperty(POWERED_OFF, true);
	}
	
	private void storeVMProcessPid(Process process) throws Exception {
		InputStream psIn = process.getInputStream();
		int avBytes = psIn.available();
		byte[] b = new byte[avBytes];
		psIn.read(b, 0, avBytes);
		ByteArrayInputStream bIS = new ByteArrayInputStream(b);
		
		vmProcessPid = IOUtils.toString(bIS).replace("\n", "");  		
	}
	

	private void kill(VirtualMachine virtualMachine) throws Exception {
		new ProcessBuilder("/bin/kill", vmProcessPid).start().waitFor();
	}

	private void stopCIFS(VirtualMachine virtualMachine) {
		SMBServer smbServer = virtualMachine.getProperty(CIFS_SERVER);
		if (smbServer != null) {
			smbServer.shutdownServer(true);
		}
	}

	@Override
	public VirtualMachineStatus status(VirtualMachine virtualMachine)
			throws Exception {
		Process p = virtualMachine.getProperty(PROCESS);
		if (p == null) {
			return VirtualMachineStatus.NOT_CREATED;
		}
		Boolean poweredOff = virtualMachine.getProperty(POWERED_OFF);
		if (poweredOff != null && poweredOff) {
			return VirtualMachineStatus.POWERED_OFF;
		}
		return VirtualMachineStatus.RUNNING;
	}

	@Override
	public void createSharedFolder(VirtualMachine virtualMachine,
			String shareName, String hostPath, String guestPath)
			throws Exception {
		new File(hostPath).mkdirs();
		Map<String, SharedFolder> sharedFolders = virtualMachine
				.getProperty(SHARED_FOLDERS);
		if (sharedFolders == null) {
			sharedFolders = new HashMap<String, SharedFolder>();
			virtualMachine.setProperty(SHARED_FOLDERS, sharedFolders);
		}
		sharedFolders.put(shareName, new SharedFolder(shareName, hostPath,
				guestPath));
	}

	@Override
	public void takeSnapshot(VirtualMachine virtualMachine, String snapshotName)
			throws Exception {
		String hda = virtualMachine
				.getProperty(VirtualMachineConstants.DISK_IMAGE_PATH);
		if (!new File(hda).exists()) {
			throw new Exception(
					"Could not take snapshot. Original disk image does not exist.");
		}

		String snapshotFile = getSnapshotLocation(virtualMachine, snapshotName);
		ProcessBuilder snapBuilder = getImgProcessBuilder(" create -f qcow2 -b "
				+ hda + " " + snapshotFile);
		Process snapProcess = snapBuilder.start();
		snapProcess.waitFor();

		int exitValue = snapProcess.exitValue();
		if (exitValue != 0) {
			throw new Exception("Could not take snapshot. Exit value "
					+ exitValue);
		}

		restoreSnapshot(virtualMachine, snapshotName);
	}

	private String getSnapshotLocation(VirtualMachine virtualMachine,
			String snapshotName) {
		String hda = virtualMachine
				.getProperty(VirtualMachineConstants.DISK_IMAGE_PATH);
		String hdaLocation = new File(hda).getParent();
		String snapshotFile = new File(hdaLocation + "/" + snapshotName + "_"
				+ virtualMachine.getName() + ".img").getAbsolutePath();
		return snapshotFile;
	}

	@Override
	public void restoreSnapshot(VirtualMachine virtualMachine,
			String snapshotName) throws Exception {

		String snapshotFile = getSnapshotLocation(virtualMachine, snapshotName);
		if (!new File(snapshotFile).exists()) {
			throw new Exception(
					"Could not restore snapshot. Snapshot file does not exist.");
		}

		String currentSnapshotFile = getSnapshotLocation(virtualMachine,
				CURRENT_SNAPSHOT);
		ProcessBuilder currSnapBuilder = getImgProcessBuilder(" create -f qcow2 -b "
				+ snapshotFile + " " + currentSnapshotFile);

		Process snapProcess = currSnapBuilder.start();
		snapProcess.waitFor();

		int exitValue = snapProcess.exitValue();
		if (exitValue != 0) {
			throw new Exception("Could not restore snapshot. Exit value "
					+ exitValue);
		}

		virtualMachine.setProperty(RESTORE_SNAPSHOT, snapshotName);
	}

	@Override
	public ExecutionResult exec(VirtualMachine virtualMachine,
			String commandLine) throws Exception {

		if (status(virtualMachine) == VirtualMachineStatus.POWERED_OFF) {
			throw new Exception(
					"Unable to execute command. Machine is not started.");
		}

		SSHClient sshClient = createAuthSSHClient(virtualMachine);

		Session session = sshClient.startSession();
		Command command = session.exec(commandLine);

		command.join();

		Integer exitStatus = command.getExitStatus();
		List<String> stdOut = IOUtils.readLines(command.getInputStream());
		List<String> stdErr = IOUtils.readLines(command.getErrorStream());

		ExecutionResult executionResult = new ExecutionResult();
		executionResult.setReturnValue(exitStatus);
		executionResult.setStdErr(stdErr);
		executionResult.setStdOut(stdOut);

		session.close();

		sshClient.disconnect();

		return executionResult;
	}

	@Override
	public void create(VirtualMachine virtualMachine) throws Exception {

	}

	@Override
	public void destroy(VirtualMachine virtualMachine) throws Exception {

	}

	@Override
	public List<String> listVMs() throws Exception {
		return null;
	}

	@Override
	public List<String> listSnapshots(VirtualMachine virtualMachine)
			throws Exception {
		return null;
	}

	@Override
	public List<String> listSharedFolders(VirtualMachine virtualMachine)
			throws Exception {
		return null;
	}

	@Override
	public boolean isSupported() {
		return true;
	}

	@Override
	public void mountSharedFolder(VirtualMachine virtualMachine,
			String shareName, String hostPath, String guestPath)
			throws Exception {

		SSHClient sshClient = createAuthSSHClient(virtualMachine);

		String user = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_USER);
		String password = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_PASSWORD);
		Session session = sshClient.startSession();
		
		StringBuilder mntBuilder = new StringBuilder();
		mntBuilder.append("mkdir -p ").append(guestPath).append(";")
			.append("sudo mount -t cifs //").append(CIFS_DEVICE).append("/")
			.append(shareName).append(" ")
			.append(guestPath).append(" -o ")
			.append("port=").append(CIFS_PORT_GUEST)
			.append(",username=").append(user)
			.append(",password=").append(password)
			.append(",uid=").append(user).append(",forceuid")
			.append(",gid=").append(user).append(",forcegid,rw");
		
		session.exec(mntBuilder.toString()).join();
		session.close();
	}

	private SSHClient createAuthSSHClient(VirtualMachine virtualMachine)
			throws Exception, UserAuthException, TransportException {
		SSHClient sshClient = createSSHClient(virtualMachine);
		String user = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_USER);
		String password = virtualMachine
				.getProperty(VirtualMachineConstants.GUEST_PASSWORD);
		sshClient.getConnection().setTimeout(DEF_CONNECTION_TIMEOUT);
		sshClient.authPassword(user, password);
		return sshClient;
	}

	@Override
	public void unmountSharedFolder(VirtualMachine virtualMachine,
			String shareName, String hostPath, String guestPath)
			throws Exception {
		deleteSharedFolder(virtualMachine, shareName);
	}

	@Override
	public void deleteSharedFolder(VirtualMachine registeredVM, String shareName)
			throws Exception {
		Map<String, SharedFolder> sharedFolders = registeredVM
				.getProperty(SHARED_FOLDERS);
		if (sharedFolders == null || sharedFolders.isEmpty()) {
			return;
		}
		sharedFolders.remove(shareName);
	}

	@Override
	public void clone(String sourceDevice, String destDevice) throws Exception {
		FileUtils.copyFile(new File(sourceDevice), new File(destDevice));
	}

	@Override
	public Object getProperty(VirtualMachine registeredVM, String propertyName)
			throws Exception {
		return registeredVM.getProperty(propertyName);
	}

	@Override
	public void setProperty(VirtualMachine registeredVM, String propertyName,
			Object propertyValue) throws Exception {
		registeredVM.setProperty(propertyName, propertyValue);
	}

	@Override
	public void prepareEnvironment(Map<String, String> props) throws Exception {
	}

	private ProcessBuilder getSystemProcessBuilder(String cmd) throws Exception {
		return getProcessBuilder("./qemu-system-i386 " /*--nographic*/  + cmd);
	}

	private ProcessBuilder getImgProcessBuilder(String cmd) throws Exception {
		return getProcessBuilder("qemu-img " + cmd);
	}

	private ProcessBuilder getProcessBuilder(String cmd) throws Exception {

		LOGGER.debug("Command line: " + cmd);

		ProcessBuilder processBuilder = null;

		if (HypervisorUtils.isWindowsHost()) {
			processBuilder = new ProcessBuilder("cmd", "/C " + cmd);
		} else if (HypervisorUtils.isLinuxHost()) {
//			List<String> matchList = HypervisorUtils.splitCmdLine("/bin/bash -c " + cmd);
//			processBuilder = new ProcessBuilder(
//					matchList.toArray(new String[] {}));
			processBuilder = new ProcessBuilder("/bin/bash", "-c", cmd);
		} else {
			throw new Exception("Host OS not supported");
		}

		if (qemuLocation != null) {
			processBuilder.directory(new File(qemuLocation));
		}

		return processBuilder;
	}

	@Override
	public void reboot(VirtualMachine virtualMachine) throws Exception {
		runQMPCommand(virtualMachine, "system_reset");
		checkOSStarted(virtualMachine);
	}
	
	private void runQMPCommand(VirtualMachine virtualMachine,
			String command) throws Exception {
		Socket s = new Socket("127.0.0.1",
				(Integer) virtualMachine.getProperty(QMP_PORT));
		PrintStream ps = new PrintStream(s.getOutputStream());
		ps.println("{\"execute\":\"qmp_capabilities\"}");
		ps.flush();
		
		Thread.sleep(QMP_CAPABILITY_WAIT);
		
		ps.println("{\"execute\":\"" + command + "\"}");
		ps.flush();
		s.close();
	}

	@Override
	public long getCPUTime(VirtualMachine virtualMachine) throws Exception {
		String[] topStats;
		long cpuTime = 0;
		
		StringBuilder topCmd = new StringBuilder();
		topCmd.append("top -b -n 1 -p ");
		topCmd.append(vmProcessPid);
		topCmd.append(" | grep ");
		topCmd.append(vmProcessPid);
		
		ProcessBuilder psProcessBuilder = 
				getProcessBuilder(topCmd.toString());
		
		Process psProcess = psProcessBuilder.start();
		InputStream psIn = psProcess.getInputStream();
		int psExitValue = psProcess.waitFor();
		if (psExitValue != 0) {
			return -1;
		}
		
		String processStr = IOUtils.toString(psIn);
		topStats = processStr.trim().split("\\s+");
		
		if (topStats.length < CPU_TIME_INDEX + 1) {
			return -1;
		}
		
//		CPUTime Pattern: [DD-]mm:ss.hh
		String cpuTimeStr = topStats[CPU_TIME_INDEX];
		String[] cpuTimeArray = cpuTimeStr.split("-");
		if (cpuTimeArray.length > 1) {
			cpuTime += Long.parseLong(cpuTimeArray[0])*24*60*60;
			cpuTimeStr = cpuTimeArray[1];
		}
		long minutes = Long.parseLong(cpuTimeStr.split(":")[0]);
		long seconds = Long.parseLong(cpuTimeStr.split(":")[1].split("\\.")[0]);
		long hundredths = Long.parseLong(cpuTimeStr.split(":")[1].split("\\.")[1]);
		
		// Changing time unit to ms
		cpuTime += minutes*60*1000;
		cpuTime += seconds*1000;
		cpuTime += hundredths*10; 
		
		return cpuTime;
	}
}
