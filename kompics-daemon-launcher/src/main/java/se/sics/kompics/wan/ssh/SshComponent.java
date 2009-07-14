package se.sics.kompics.wan.ssh;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.timer.Timer;
import se.sics.kompics.wan.config.PlanetLabConfiguration;
import se.sics.kompics.wan.ssh.events.CommandRequest;
import se.sics.kompics.wan.ssh.events.CommandResponse;
import se.sics.kompics.wan.ssh.events.DownloadFileRequest;
import se.sics.kompics.wan.ssh.events.DownloadFileResponse;
import se.sics.kompics.wan.ssh.events.HaltRequest;
import se.sics.kompics.wan.ssh.events.HaltResponse;
import se.sics.kompics.wan.ssh.events.SshConnectRequest;
import se.sics.kompics.wan.ssh.events.SshConnectResponse;
import se.sics.kompics.wan.ssh.events.SshHeartbeatRequest;
import se.sics.kompics.wan.ssh.events.SshHeartbeatResponse;
import se.sics.kompics.wan.ssh.events.UploadFileRequest;
import se.sics.kompics.wan.ssh.events.UploadFileResponse;
import se.sics.kompics.wan.ssh.scp.DownloadUploadPort;
import se.sics.kompics.wan.ssh.scp.FileInfo;
import se.sics.kompics.wan.ssh.scp.LocalDirMD5Info;
import se.sics.kompics.wan.ssh.scp.events.DownloadMD5Request;
import se.sics.kompics.wan.ssh.scp.events.DownloadMD5Response;
import se.sics.kompics.wan.ssh.scp.events.UploadMD5Request;
import se.sics.kompics.wan.ssh.scp.events.UploadMD5Response;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.ConnectionInfo;
import ch.ethz.ssh2.ConnectionMonitor;
import ch.ethz.ssh2.HTTPProxyData;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.Session;

public class SshComponent extends ComponentDefinition {

	public static final int LOG_ERROR = 1;

	public static final int LOG_FULL = 3;

	public static final int LOG_DEVEL = 2;

	public static final int LOG_LEVEL = 3;

	public static final int SSH_CONNECT_TIMEOUT = 15000;

	public static final int SSH_KEY_EXCHANGE_TIMEOUT = 30000;

	public static final String EXIT_CODE_IDENTIFIER = "=:=:=EXIT STATUS==";

	private Negative<SshPort> sshPort = negative(SshPort.class);

	private Positive<Timer> timer = positive(Timer.class);

	private Positive<DownloadUploadPort> downloadUploadPort = positive(DownloadUploadPort.class);

	private int sessionIdCounter = 0;

	private int commandIdCounter = 0;

	// (sessionId, sshConn)
	private Map<Integer, SshConn> activeSshConnections = new ConcurrentHashMap<Integer, SshConn>();
	// (sessionId, session)
	private Map<Integer, Session> activeSessions = new ConcurrentHashMap<Integer, Session>();

	// (commandId, sshCommand)
	private Map<Integer, SshCommand> activeSshCommands = new ConcurrentHashMap<Integer, SshCommand>();
	// (commandId, numFilesRemaining)
	private Map<Integer, Integer> outstandingUploadFiles = new HashMap<Integer, Integer>();

	// public static enum FileType { HIERARCHICAL, FLAT} ;

	public static final String FLAT = "flat";
	public static final String HIERARCHY = "hierarchy";

	// public static final String[] NAMING_TYPES = { HIERARCHY, FLAT };

	//	
	// private volatile String downloadDirectoryType = HIERARCHY;

	public class SshConn implements ConnectionMonitor, Comparable<SshConn> {

		private String status;
		private final ExperimentHost hostname;
		private boolean isConnected;
		private boolean wasConnected;
		private final Credentials credentials;
		private Connection connection;

		public SshConn(ExperimentHost host, Credentials credentials, Connection connection) {
			super();
			this.status = "created";
			this.hostname = host;
			this.credentials = credentials;
			this.connection = connection;

			if (this.connection.isAuthenticationComplete() == true) {
				isConnected = true;
			}
		}

		public void connectionLost(Throwable reason) {
			statusChange("connection lost, (HANDLE THIS?)", LOG_ERROR);
			isConnected = false;
		}

		/**
		 * @return the credentials
		 */
		public Credentials getCredentials() {
			return credentials;
		}

		/**
		 * @return the plHost
		 */
		public ExperimentHost getExpHost() {
			return hostname;
		}

		/**
		 * @return the status
		 */
		public String getStatus() {
			return status;
		}

		/**
		 * @return the isConnected
		 */
		public boolean isConnected() {
			return isConnected;
		}

		/**
		 * @return the wasConnected
		 */
		public boolean isWasConnected() {
			return wasConnected;
		}

		/**
		 * @return the connection
		 */
		public Connection getConnection() {
			return connection;
		}

		/**
		 * @param isConnected
		 *            the isConnected to set
		 */
		public void setConnected(boolean isConnected) {
			this.isConnected = isConnected;
		}

		/**
		 * @param wasConnected
		 *            the wasConnected to set
		 */
		public void setWasConnected(boolean wasConnected) {
			this.wasConnected = wasConnected;
		}

		/**
		 * @param status
		 *            the status to set
		 */
		public void setStatus(String status) {
			this.status = status;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Comparable#compareTo(java.lang.Object)
		 */
		@Override
		public int compareTo(SshConn that) {
			// we can have several connections to the same host with different
			// usernames
			if ((!credentials.equals(that.credentials)) || (hostname.compareTo(that.hostname) != 0)) {
				return -1;
			}

			if (new ConnectionComparator().compare(connection, that.connection) != 0) {
				return -1;
			}

			return 0;

		}

		private void statusChange(String status, int level) {

			setStatus(status);
			if (level <= LOG_LEVEL) {
				System.out.println(getExpHost() + ": " + status);
			}
		}
	}

	public class SshCommand implements Runnable {
		private CommandRequest event;
		private int sessionId;
		private int commandId;
		private SshConn conn;
		private Session session;
		private CommandSpec commandSpec;

		public SshCommand(CommandRequest event, int sessionId, int commandId, SshConn conn,
				Session session, CommandSpec commandSpec) {
			this.event = event;
			this.sessionId = sessionId;
			this.commandId = commandId;
			this.conn = conn;
			this.session = session;
			this.commandSpec = commandSpec;
		}

		public CommandSpec getCommandSpec() {
			return commandSpec;
		}

		public SshConn getConn() {
			return conn;
		}

		public CommandRequest getSshCommandRequest() {
			return event;
		}

		public int getSessionId() {
			return sessionId;
		}

		public int getCommandId() {
			return commandId;
		}

		public Session getSession() {
			return session;
		}

		@Override
		public void run() {

			// List<Thread> t = activeThreads.get(session);
			// if (t != null && t.size() > 0) {
			// System.out.println("Currently waiting on " + t.size() +
			// " commands to finish.");
			// }

			runCommand();
		}

		public void stop() {
			Thread.currentThread().interrupt();
		}

		public int runCommand() {
			int res = -1;

			// synchronized (session) {
			String command = commandSpec.getCommand();

			String commandResponse = "success";

			boolean commandFailed = true;

			if (conn == null || session == null || validSession(session) == false) {
				commandResponse = "SshConn or Session obj was null";
			} else { // process command
				if (command.startsWith("#")) {
					runSpecialCommand(conn, commandSpec);
					commandFailed = false;
				}// run the command in the current session
				else {
					try {
						res = runNormalCommand(conn, session, commandSpec);
						if (res == 0) {
							commandFailed = false;
						}
					} catch (IOException e) {
						res = -1;
						e.printStackTrace();
					} catch (InterruptedException e) {
						res = -1;
						e.printStackTrace();
					}
					System.out.println("Result of ssh command was: " + res);

					// not necessary for DownloadRequest and UploadRequest
					// commands
					sendCommandResponse(event, sessionId, commandId, commandResponse, commandFailed);
				}
			}

			return res;
		}

		public void runSpecialCommand(SshConn conn, CommandSpec commandSpec) {
			// handle these in a special way...
			String[] command = parseParameters(commandSpec.getCommand());
			if (command.length > 0) {
				if (command[0].equals(PlanetLabConfiguration.SPECIAL_COMMAND_UPLOAD_DIR)) {
					if (command.length == 3) {
						File fileOrDir = new File(command[1]);
						String remotePath = command[2];
						if (fileOrDir.exists()) {
							upload(conn, fileOrDir, remotePath, commandSpec);
						} else {
							System.err.println("File not found for uploading: "
									+ fileOrDir.getPath());
						}
					}
				} else if (command[0]
						.startsWith(PlanetLabConfiguration.SPECIAL_COMMAND_DOWNLOAD_DIR)) {
					if (command.length == 5) {
						String remotePath = command[1];
						File localFileOrDir = new File(command[2]);
						String fileFilter = command[3];
						String localNameType = command[4];
						if (localFileOrDir.exists()) {

							download(conn, remotePath, localFileOrDir, fileFilter, localNameType,
									commandSpec);
						}
					} else {
						System.err.println("parse error '" + commandSpec.getCommand() + "'"
								+ "length=" + command.length);
					}
				} else {
					System.err.println("unknown command '" + command[0] + "'");
				}
			} else {
				System.out.println("parameter parsing problem: '" + commandSpec.getCommand() + "'");
			}
		}

		public boolean upload(SshComponent.SshConn conn, File baseDir, String remotePath,
				CommandSpec commandSpec) {
			try {
				List<FileInfo> listMD5FileHashes = LocalDirMD5Info.getInstance().getFileInfo(
						baseDir);

				for (FileInfo f : listMD5FileHashes) {
					f.setRemotePath(f.getLocalFile(), remotePath);
				}

				SCPClient scpClient = conn.getConnection().createSCPClient();

				int commandId = commandSpec.getCommandId();
				int numFiles = listMD5FileHashes.size();
				outstandingUploadFiles.put(commandId, numFiles);

				UploadMD5Request req = new UploadMD5Request(commandId, scpClient,
						listMD5FileHashes, commandSpec);

				trigger(req, downloadUploadPort);

			} catch (InterruptedException e) {
				commandSpec.receivedErr("local i/o error, " + e.getMessage());
			} catch (IOException e) {
				commandSpec.receivedErr("local i/o error, " + e.getMessage());
			}
			return false;
		}

		public boolean download(SshConn conn, String remotePath, File localBaseDir,
				String fileFilter, String localNamingType, CommandSpec commandSpec) {
			// sanity checks
			if (fileFilter == null || fileFilter.length() == 0) {
				// match everything
				fileFilter = ".";
			}
			return downloadDir(conn, remotePath, localBaseDir, fileFilter, localNamingType,
					commandSpec);
		}

		private boolean downloadDir(SshComponent.SshConn conn, String remotePath,
				File localBaseDir, String fileFilter, String localNamingType,
				CommandSpec commandSpec) {

			createLocalDir(localBaseDir);

			String downloadDirectoryType = getLocalFilenameType(localNamingType);

			System.out.println("Getting file list");
			List<FileInfo> fileList;
			try {
				fileList = getRemoteFileList(conn, remotePath, fileFilter, commandSpec);
				for (FileInfo info : fileList) {
					if (downloadDirectoryType.compareTo(FLAT) == 0) {
						info.setLocalFlatFile(localBaseDir);
					} else if (downloadDirectoryType.compareTo(HIERARCHY) == 0) {
						info.setLocalHierarchicalFile(localBaseDir);
					}
				}
				System.out.println("starting md5 check thread");

				// MD5Check md5Check = new DownloadMD5CheckThread(conn,
				// fileList,
				// commandSpec);
				// md5Check.run();

				// XXX send this as event to another component that will return
				// the result asynchronously
				// XXX other component will launch a thread that returns the
				// result

				// sendDownloadRequest(sessionId, conn, fileList, commandSpec);

				// Session s1 = startShell(conn);
				SCPClient scpClient = conn.getConnection().createSCPClient();
				// if (s1 != null) {
				// int sId = addSession(session, conn);
				trigger(new DownloadMD5Request(commandId, scpClient, fileList, commandSpec),
						downloadUploadPort);
				// }

				return true;
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return false;
		}

		private boolean createLocalDir(File baseDir) {
			if (baseDir.isDirectory()) {
				return true;
			} else if (baseDir.mkdirs()) {
				return true;
			} else {
				System.err.println("could not create local directory for downloads: " + baseDir);
				return false;
			}
		}

		public List<FileInfo> getRemoteFileList(SshConn sshConn, String remoteDir, String filter,
				CommandSpec baseCommand) throws IOException, InterruptedException {
			baseCommand.started();
			baseCommand.receivedData("getting remote file list");
			CommandSpec command = this.generateCommand(remoteDir, filter, baseCommand.getTimeout());
			ArrayList<FileInfo> remoteFiles = new ArrayList<FileInfo>();
			System.out.println("Starting shell");

			Session sessionFileList = startShell(sshConn);
			if (sessionFileList != null) {
				// no need to store this session locally, it's closed at end of
				// if stmt
				// int sflId = addSession(sessionFileList, sshConn);

				System.out.println("Running command: " + command.getCommand());
				runNormalCommand(sshConn, sessionFileList, command);
				int numFiles = command.getLineNum();
				// System.out.println("got " + numFiles + " lines");
				for (int i = 1; i < numFiles; i++) {
					String line = command.getProcLine(i);
					int index = line.indexOf(" ");

					if (index > 0) {
						String md5 = line.substring(0, index);
						String path = line.substring(index + 2);
						// System.out.println(line);
						// System.out.println(md5 + "." + path);
						remoteFiles
								.add(new FileInfo(path, md5, sshConn.getExpHost().getHostname()));
					}

				}
				sessionFileList.close();
			}
			baseCommand.receivedData("calculated md5 of " + remoteFiles.size() + " files");
			baseCommand.setExitCode(0);

			return remoteFiles;
		}

		private CommandSpec generateCommand(String remoteDir, String filter, double timeout) {
			if (filter != null && filter != "") {
				return new CommandSpec("md5sum `find " + remoteDir + " | grep " + filter
						+ "` 2> /dev/null", timeout, ++commandIdCounter, false);
			} else {
				return new CommandSpec("md5sum `find " + remoteDir + "` 2> /dev/null", timeout,
						++commandIdCounter, false);
			}
		}

		private String getLocalFilenameType(String type) {
			if (FLAT.compareTo(type) == 0) {
				return FLAT;
			} else if (HIERARCHY.compareTo(type) == 0) {
				return HIERARCHY;
			} else {
				System.out.println("unknown local naming type: '" + type + "', using default '"
						+ HIERARCHY + "'");
				return HIERARCHY;
			}
		}

	}

	public SshComponent() {

		subscribe(handleSshCommandRequest, sshPort);
		subscribe(handleSshConnectRequest, sshPort);
		subscribe(handleHaltRequest, sshPort);
		subscribe(handleDownloadFileRequest, sshPort);
		subscribe(handleUploadFileRequest, sshPort);

		subscribe(handleDownloadMD5Response, downloadUploadPort);
		subscribe(handleUploadMD5Response, downloadUploadPort);

	}

	public Handler<CommandRequest> handleSshCommandRequest = new Handler<CommandRequest>() {
		public void handle(CommandRequest event) {

			if (event.getClass().toString().compareTo(CommandRequest.class.toString()) != 0) {
				return;
			}

			CommandSpec commandSpec = new CommandSpec(event.getCommand(), event.getTimeout(),
					commandIdCounter++, event.isStopOnError());

			int sessionId = event.getSessionId();
			int commandId = commandIdCounter;

			SshConn conn = activeSshConnections.get(sessionId);
			Session session = activeSessions.get(sessionId);

			if (session != null) {
				runSshCommand(event, sessionId, conn, session, commandSpec);
			} else {
				System.err.println("No session available to execute the command");
			}

		}
	};

	public Handler<SshHeartbeatRequest> handleSshSshHeartbeatRequest = new Handler<SshHeartbeatRequest>() {
		public void handle(SshHeartbeatRequest event) {
			int sessionId = event.getSessionId();
			SshConn conn = activeSshConnections.get(sessionId);
			
			boolean active = false;
			Session tempSession = null;
			try {
				tempSession = conn.getConnection().openSession();
				active = true;
			} catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				if (tempSession != null)
				{
					tempSession.close();
				}
			}

			SshHeartbeatResponse resp = new SshHeartbeatResponse(event, sessionId, active);
			trigger(resp, sshPort);
		}
	};
	
	
	/**
	 * Called by SshCommand thread.
	 * 
	 * @param event
	 * @param sessionId
	 * @param commandResponse
	 * @param commandFailed
	 */
	private void sendCommandResponse(CommandRequest event, int sessionId, int commandId,
			String commandResponse, boolean commandFailed) {
		trigger(new CommandResponse(event, sessionId, commandResponse, !commandFailed), sshPort);

		activeSshCommands.remove(commandId);
	}

	public void runSshCommand(CommandRequest event, int sessionId, SshConn conn, Session session,
			CommandSpec commandSpec) {

		int commandId = commandSpec.getCommandId();

		SshCommand sshCommand = new SshCommand(event, sessionId, commandId, conn, session,
				commandSpec);
		Thread t = new Thread(sshCommand);
		t.run();
		activeSshCommands.put(commandId, sshCommand);
	}

	private boolean validSession(Session session) {
		if (session.getExitStatus() == null) {
			return true;
		}
		return false;
	}

	public Handler<SshConnectRequest> handleSshConnectRequest = new Handler<SshConnectRequest>() {
		public void handle(SshConnectRequest event) {

			int sessionId = connect(event.getCredentials(), event.getHostname(), new CommandSpec(
					"#connect", SSH_CONNECT_TIMEOUT, commandIdCounter++, true));

			trigger(new SshConnectResponse(event, sessionId, event.getHostname()), sshPort);
		}
	};

	private int addSession(Session session, SshConn sshConnection) {
		sessionIdCounter++;

		activeSshConnections.put((int) sessionIdCounter, sshConnection);

		activeSessions.put((int) sessionIdCounter, session);

		return sessionIdCounter;
	}

	private int connect(Credentials credentials, ExperimentHost expHost, CommandSpec commandSpec) {

		Connection connection = new Connection(expHost.getHostname());

		SshConn sshConnection = new SshConn(expHost, credentials, connection);

		List<SshConn> listActiveConns = new ArrayList<SshConn>(activeSshConnections.values());

		if (listActiveConns.contains(sshConnection) == true) {

			Set<Integer> sessions = activeSshConnections.keySet();
			for (Integer sId : sessions) {
				if (activeSshConnections.get(sId).compareTo(sshConnection) == 0) {
					return sId;
				}
			}
			throw new IllegalStateException("Found active connection, but no session object.");
		}

		commandSpec.started();

		sshConnection = new SshConn(expHost, credentials, connection);

		connection.addConnectionMonitor(sshConnection);

		// if (Main.getConfig(Constants.HTTP_PROXY_HOST) != null
		// && Main.getConfig(Constants.HTTP_PROXY_PORT) != null) {

		if (PlanetLabConfiguration.getHttpProxyHost().compareTo(
				PlanetLabConfiguration.DEFAULT_HTTP_PROXY_HOST) != 0
				&& PlanetLabConfiguration.getHttpProxyPort() != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_PORT) {
			int port = PlanetLabConfiguration.getHttpProxyPort();
			String hostname = PlanetLabConfiguration.getHttpProxyHost();
			String username = PlanetLabConfiguration.getHttpProxyUsername();
			String password = PlanetLabConfiguration.getHttpProxyPassword();
			// if username AND password is specified
			if (username != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_USERNAME
					&& password != PlanetLabConfiguration.DEFAULT_HTTP_PROXY_PASSWORD) {
				connection.setProxyData(new HTTPProxyData(hostname, port, username, password));
				System.out.println("ssh connect with http proxy and auth, host=" + hostname
						+ " port=" + port + "user=" + username);
			} else {
				// ok, only hostname and port
				connection.setProxyData(new HTTPProxyData(
						PlanetLabConfiguration.getHttpProxyHost(), port));
				System.out.println("ssh connect with http proxy, host=" + hostname + " port="
						+ port);
			}
		}

		// try to open the connection
		int sessionId;
		try {
			// try to connect
			ConnectionInfo cInfo = connection.connect(null, SSH_CONNECT_TIMEOUT,
					SSH_KEY_EXCHANGE_TIMEOUT);

			// try to authenticate
			// if (sshConn.authenticateWithPublicKey(controller
			// .getCredentials().getSlice(), new File(controller
			// .getCredentials().getKeyPath()), controller
			// .getCredentials().getKeyFilePassword())) {

			if (connection.authenticateWithPublicKey(credentials.getUsername(), new File(
					credentials.getKeyPath()), credentials.getKeyFilePassword())) {

				// ok, authentiaction succesfull, return the connection
				commandSpec.receivedControlData("connect successful");
				// isConnected = true;
				sshConnection.setConnected(true);

				Session session = startShell(sshConnection);
				sessionId = addSession(session, sshConnection);
			} else if (connection.authenticateWithPassword(credentials.getUsername(), credentials
					.getPassword())) {
				// ok, authentiaction succesfull, return the connection
				commandSpec.receivedControlData("connect successful");
				// isConnected = true;
				sshConnection.setConnected(true);

				Session session = startShell(sshConnection);
				sessionId = addSession(session, sshConnection);
			} else {
				// well, authentication failed
				sshConnection.statusChange("auth failed", LOG_DEVEL);
				commandSpec.setExitCode(1, "auth failed");
				commandSpec.receivedControlErr("auth failed");
				sessionId = -1;
				System.err.println("Authentication failed!");
			}

			// handle errors...
		} catch (SocketTimeoutException e) {
			sshConnection.statusChange("connection timeout: " + e.getMessage(), LOG_DEVEL);
			if (e.getMessage().contains("kex")) {
				commandSpec.setExitCode(4, "kex timeout");
			} else {
				commandSpec.setExitCode(3, "conn timeout");
			}
			commandSpec.receivedControlErr(e.getMessage());
			sessionId = -1;
		} catch (IOException e) {

			if (e.getCause() != null) {
				commandSpec.receivedControlErr(e.getCause().getMessage());
				if (e.getCause().getMessage().contains("Connection reset")) {
					sshConnection.statusChange(e.getCause().getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "conn reset");

				} else if (e.getCause().getMessage().contains("Connection refused")) {
					sshConnection.statusChange(e.getCause().getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "conn refused");

				} else if (e.getCause().getMessage().contains("Premature connection close")) {
					sshConnection.statusChange(e.getCause().getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "prem close");

				} else if (e.getCause() instanceof java.net.UnknownHostException) {
					sshConnection.statusChange(e.getCause().getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "dns unknown");

				} else if (e.getCause() instanceof NoRouteToHostException) {
					sshConnection.statusChange(e.getCause().getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "no route");
				} else if (e.getMessage().contains("Publickey")) {
					sshConnection.statusChange(e.getMessage(), LOG_DEVEL);
					commandSpec.setExitCode(2, "auth error");
				} else {
					System.err.println("NEW EXCEPTION TYPE, handle...");

					e.printStackTrace();
				}
			} else {
				commandSpec.receivedErr(e.getMessage());
				commandSpec.setExitCode(255, "other");

				sshConnection.statusChange(e.getMessage(), LOG_DEVEL);
			}

			sessionId = -1;
		}

		return sessionId;

	}

	public Session startShell(SshConn conn) {
		Session session = null;
		if (conn.isConnected()) {
			try {
				session = conn.getConnection().openSession();
			} catch (IOException e) {
				conn.statusChange("could not open session: " + e.getMessage(), LOG_ERROR);
				return null;
			}

			try {
				session.startShell();
			} catch (IOException e) {
				conn.statusChange("could not start shell: " + e.getMessage(), LOG_ERROR);
				return null;
			}
		}
		return session;
	}

	public Handler<DownloadMD5Response> handleDownloadMD5Response = new Handler<DownloadMD5Response>() {
		public void handle(DownloadMD5Response event) {
			int commandId = event.getCommandId();
			boolean status = event.isStatus();

			SshCommand sc = activeSshCommands.get(commandId);

			if (sc == null && status == false) {
				return;
			}

			int sessionId = sc.getCommandId();

			DownloadFileRequest req = (DownloadFileRequest) sc.getSshCommandRequest();
			if (req != null) {
				String[] commands = parseParameters(req.getCommand());
				File file = new File(commands[1]);
				trigger(new DownloadFileResponse(req, sessionId, file, status), sshPort);
			} else {
				throw new IllegalStateException("Couldnt find request for command: " + commandId);
			}

			activeSshCommands.remove(commandId);
		}
	};

	public Handler<UploadMD5Response> handleUploadMD5Response = new Handler<UploadMD5Response>() {
		public void handle(UploadMD5Response event) {
			int commandId = event.getCommandId();
			boolean status = event.isStatus();
			SshCommand sc = activeSshCommands.get(commandId);

			if (sc == null && status == false) {
				return;
			}

			Session session = sc.getSession();
			SshConn conn = sc.getConn();
			CommandSpec command = sc.getCommandSpec();

			FileInfo fileInfo = event.getFile();

			try {
				checkRemoteFile(conn, session, command, fileInfo);
				int uploadsRemaining = outstandingUploadFiles.get(commandId);
				uploadsRemaining--;
				if (uploadsRemaining <= 0) {
					UploadFileRequest req = (UploadFileRequest) sc.getSshCommandRequest();

					int sessionId = sc.getCommandId();
					trigger(new UploadFileResponse(req, sessionId, fileInfo.getLocalFile()),
							sshPort);
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				activeSshCommands.remove(commandId);
			}

		}
	};

	public Handler<HaltRequest> handleHaltRequest = new Handler<HaltRequest>() {
		public void handle(HaltRequest event) {

			// Session session = event.getSession();
			int sessionId = event.getSessionId();

			// this.isConnected = false;
			// commandQueue.clear();
			// this.quit = true;
			// this.interrupt();
			// this.disconnect();

			removeSession(sessionId);

			trigger(new HaltResponse(event, true), sshPort);
		}
	};

	private boolean removeSession(int sessionId) {
		// XXX check semantics of close()

		Session session = this.activeSessions.get(sessionId);

		if (session == null) {
			return false;
		}

		session.close();
		boolean status = (activeSshConnections.remove(session) == null) ? false : true;

		// sessionCommandsMap.remove(session);
		activeSessions.remove(sessionId);

		return status;
	}

	// synchronized private void sendDownloadRequest(int sessionId,
	// SshComponent.SshConn conn, List<FileInfo> fileList,
	// CommandSpec commandSpec) throws IOException
	// {
	// Session s1 = null;
	// SCPClient scpClient = conn.getConnection().createSCPClient();
	// if (null != (s1 = startShell(conn))) {
	// trigger(new DownloadMD5Request(sessionId, scpClient, fileList,
	// commandSpec),
	// downloadUploadPort); // md5Checker.getPositive(DownloadMgrPort.class)
	// // downloadMD5Checker(conn, fileList, commandSpec);
	// }
	// }

	public static int runNormalCommand(SshConn sshConn, Session session, CommandSpec commandSpec)
			throws IOException, InterruptedException {

		LineReader stdout = new LineReader(session.getStdout());
		LineReader stderr = new LineReader(session.getStderr());
		OutputStream stdin = session.getStdin();

		sshConn.statusChange("executing: '" + commandSpec.getCommand() + "'", LOG_FULL);
		stdin.write(commandSpec.getCommand().getBytes());
		stdin.write(("\necho \"" + EXIT_CODE_IDENTIFIER + "$?\"\n").getBytes());
		commandSpec.started();

		String line;
		String errLine;

		boolean quit = false;
		do {

			// session.waitForCondition(ChannelCondition.STDOUT_DATA
			// | ChannelCondition.STDERR_DATA, Math
			// .round(1000.0 / DATA_POLLING_FREQ));
			// XXX why sleep here?
			// Thread.sleep(50);
			line = stdout.readLine();
			errLine = stderr.readLine();

			// check if we got any data on stderr
			while (errLine != null) {
				commandSpec.receivedErr(errLine);
				errLine = stderr.readLine();
				// quit = true;
			}
			// check for data on stdout
			while (line != null) {
				if (line.startsWith(EXIT_CODE_IDENTIFIER)) {
					String[] split = line.split("==");
					commandSpec.setExitCode(Integer.parseInt(split[1]));
					sshConn.statusChange(commandSpec.getCommand() + " completed, code="
							+ commandSpec.getExitCode() + " time=" + commandSpec.getExecutionTime()
							/ 1000.0, LOG_FULL);
					return commandSpec.getExitCode();
				}
				commandSpec.receivedData(line);

				System.out.println(line);
				line = stdout.readLine();
			}

			if (commandSpec.isTimedOut()) {
				commandSpec.setExitCode(CommandSpec.RETURN_TIMEDOUT, "timed out");
				commandSpec.receivedControlErr("timeout after "
						+ (Math.round(commandSpec.getExecutionTime() * 10.0) / 10.0) + " s");
				if (commandSpec.isStopOnError()) {
					commandSpec.receivedControlErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			// handle the case when the command is killed
			if (commandSpec.isKilled()) {
				commandSpec.setExitCode(CommandSpec.RETURN_KILLED, "killed");
				commandSpec.receivedControlErr("killed after "
						+ (Math.round(commandSpec.getExecutionTime() * 10.0) / 10.0) + " s");
				if (commandSpec.isStopOnError()) {
					commandSpec.receivedControlErr("command is stop on error, halting");
				}
				return commandSpec.getExitCode();
			}

			if (line == null) {
				line = "";
			}

		} while (!line.startsWith(EXIT_CODE_IDENTIFIER) && quit == false);

		// we should never make it down here... unless quiting
		return Integer.MIN_VALUE;
	}

	public boolean checkRemoteFile(SshComponent.SshConn conn, Session session,
			CommandSpec commandResults, FileInfo file) throws IOException, InterruptedException {

		CommandSpec commandSpec = this.md5CheckCommand(file);
		if (SshComponent.runNormalCommand(conn, session, commandSpec) < 0) {
			// timeout or killed...

			return false;
		}
		boolean md5match = false;
		// does the file exists? md5sum returns 0 on success
		if (commandSpec.getExitCode() == 0) {
			// does the md5 match?
			String localMD5 = file.getMd5();
			String remoteMD5 = commandSpec.getProcLine(1).split(" ")[0];
			// System.out.println("checking "
			// + file.getRemoteFileName());
			if (localMD5.equals(remoteMD5)) {
				md5match = true;
				// System.out.println("passed");
				commandResults.receivedControlData("passed: " + file.getFullRemotePath());
			} else {
				commandResults.receivedControlErr("copying (md5 failed):"
						+ file.getFullRemotePath());
			}
			// System.out.println("size: "
			// + commandSpec.getProcOutput(0).size());
		} else {
			commandResults.receivedControlErr("copying (missing):" + file.getFullRemotePath());
		}
		return md5match;

	}

	private CommandSpec md5CheckCommand(FileInfo file) {
		return new CommandSpec("md5sum " + file.getFullRemotePath(), 0, 0, false);
	}

	public Handler<DownloadFileRequest> handleDownloadFileRequest = new Handler<DownloadFileRequest>() {
		public void handle(DownloadFileRequest event) {

			CommandSpec commandSpec = new CommandSpec(event.getCommand(), event.getTimeout(),
					commandIdCounter++, event.isStopOnError());

			int sessionId = event.getSessionId();
			SshConn conn = activeSshConnections.get(sessionId);
			Session session = activeSessions.get(sessionId);

			runSshCommand(event, sessionId, conn, session, commandSpec);

		}
	};

	public Handler<UploadFileRequest> handleUploadFileRequest = new Handler<UploadFileRequest>() {
		public void handle(UploadFileRequest event) {

			CommandSpec commandSpec = new CommandSpec(event.getCommand(), event.getTimeout(),
					commandIdCounter++, event.isStopOnError());

			int sessionId = event.getSessionId();
			SshConn conn = activeSshConnections.get(sessionId);
			Session session = activeSessions.get(sessionId);

			runSshCommand(event, sessionId, conn, session, commandSpec);

		}
	};

	private String[] parseParameters(String parameters) {
		String[] split = new String[0];
		if (parameters.contains("\"") && parameters.contains("'")) {
			System.err.println("sorry... arguments can only contain either \" or ', not both");
			return split;
		}

		if (parameters.contains("\"") || parameters.contains("'")) {
			// handle specially

			ArrayList<String> params = new ArrayList<String>();
			boolean withinQuotes = false;
			StringBuffer tmpBuffer = new StringBuffer();
			for (int i = 0; i < parameters.length(); i++) {

				char c = parameters.charAt(i);
				// System.out.println("processing: " + c);
				if (c == '"' || c == '\'') {
					withinQuotes = !withinQuotes;
					// System.out.println("w=" + withinQuotes);
					// continue to the next character
				} else {
					if (c == ' ' && !withinQuotes) {
						// we reached a space, and we are not between
						// quotes, add to list and flush buffer
						params.add(tmpBuffer.toString());

						// System.out.println("found: " +
						// tmpBuffer.toString()
						// + "(" + params.size() + ")");
						tmpBuffer = new StringBuffer();
					} else {
						// if the char is not ' ' or '"' or '\'', append to
						// stringbuffer

						tmpBuffer.append(c);
						// System.out.println("adding: " +
						// tmpBuffer.toString());
					}
				}
			}
			if (tmpBuffer.length() > 0) {
				params.add(tmpBuffer.toString());
			}
			split = params.toArray(split);
		} else {
			split = parameters.split(" ");
		}

		return split;
	}

}