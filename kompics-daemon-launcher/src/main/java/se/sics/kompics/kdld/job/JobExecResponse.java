package se.sics.kompics.kdld.job;

import java.io.Serializable;

import se.sics.kompics.Response;
import se.sics.kompics.kdld.daemon.maven.MavenLauncher.ProcessWrapper;


public class JobExecResponse extends Response implements Serializable {

	private static final long serialVersionUID = 2993973136500802022L;

	public enum Status {
		SUCCESS, FAIL, DUPLICATE
	};

	private final Status status;

	private final int jobId;
	
	private final ProcessWrapper processWrapper;

	public JobExecResponse(JobExec request, int jobId, ProcessWrapper process, Status status) {
		super(request);
		this.jobId = jobId;
		this.processWrapper = process;
		this.status = status;
	}

	public int getJobId() {
		return jobId;
	}

	public Status getStatus() {
		return status;
	}

	public ProcessWrapper getProcessWrapper() {
		return processWrapper;
	}
}
