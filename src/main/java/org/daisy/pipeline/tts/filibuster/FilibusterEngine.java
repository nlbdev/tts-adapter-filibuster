package org.daisy.pipeline.tts.filibuster;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import net.sf.saxon.s9api.XdmNode;

import org.daisy.pipeline.audio.AudioBuffer;
import org.daisy.pipeline.tts.AudioBufferAllocator;
import org.daisy.pipeline.tts.AudioBufferAllocator.MemoryException;
import org.daisy.pipeline.tts.TTSEngine;
import org.daisy.pipeline.tts.TTSRegistry.TTSResource;
import org.daisy.pipeline.tts.TTSService.Mark;
import org.daisy.pipeline.tts.TTSService.SynthesisException;
import org.daisy.pipeline.tts.Voice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilibusterEngine extends TTSEngine {
	private Logger logger = LoggerFactory.getLogger(FilibusterEngine.class);

	private AudioFormat audioFormat;
	private String[] cmd;
	private String[] env;
	private File filibusterPath;
	private int priority;
    
	class FilibusterTTSResource {
		public TTSResource ttsResource;
		public FilibusterInstance filibusterInstance;
		public FilibusterTTSResource(TTSResource ttsResource, FilibusterInstance filibusterInstance) {
			this.ttsResource = ttsResource;
			this.filibusterInstance = filibusterInstance;
		}
	}
	
	private Map<FilibusterTTSResource, List<Thread>> filibusterInstances = Collections.synchronizedMap(new HashMap<FilibusterTTSResource, List<Thread>>());
	private static final int MAX_FILIBUSTER_INSTANCES;
	
	// set MAX_FILIBUSTER_INSTANCES based on environment variable FILIBUSTER_INSTANCES or system property filibuster.instances
	// default to 6 if neither is an integer
	static {
		int instances;
		String instancesString = System.getenv("FILIBUSTER_INSTANCES");
		try {
			instances = Integer.parseInt(instancesString);
		} catch (Exception e1) {
			instancesString = System.getProperty("filibuster.instances");
			try {
				instances = Integer.parseInt(instancesString);
			} catch (Exception e2) {
				instances = 6;
			}
		}
		MAX_FILIBUSTER_INSTANCES = instances > 0 ? instances : 1;
	}

	public FilibusterEngine(FilibusterService filibusterService, String filibusterPath, String tclshPath, int priority) {
		super(filibusterService);
		this.filibusterPath = new File(filibusterPath);
		this.priority = priority;
		this.cmd = new String[]{ tclshPath, "narraFil2.tcl", "no" };
		this.env = new String[]{ "USER=user" };
		
		this.audioFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 22050.0f, 16, 1, 2, 22050.0f, false);
	}
	
	public String threadId() {
		// for debugging
		return (Thread.currentThread().getId()+"").replaceAll("^.*(..)$", "$1")+": ";
	}

	@Override
	public Collection<AudioBuffer> synthesize(String sentence, XdmNode xmlSentence,
	        Voice voice, TTSResource threadResources, List<Mark> marks,
	        AudioBufferAllocator bufferAllocator, boolean retry) throws SynthesisException,
	        InterruptedException, MemoryException {
		
		logger.debug(threadId()+"synthesizing: '"+sentence+"'");
		
		FilibusterTTSResource filibusterResource = null;
		
		logger.debug(threadId()+"synthesize() -- waiting for engine lock");
		synchronized (this) {
			logger.debug(threadId()+"synthesize() -- got engine lock");
			for (FilibusterTTSResource instance : filibusterInstances.keySet()) {
				List<Thread> threadList = filibusterInstances.get(instance);
				if (threadList.contains(Thread.currentThread())) {
					filibusterResource = instance;
				}
			}
			if (filibusterResource == null && filibusterInstances.size() < MAX_FILIBUSTER_INSTANCES) {
				filibusterResource = new FilibusterTTSResource(new TTSResource(), new FilibusterInstance(cmd, env, filibusterPath));
				List<Thread> threadList = new ArrayList<Thread>();
				threadList.add(Thread.currentThread());
				filibusterInstances.put(filibusterResource, threadList);
			}
			if (filibusterResource == null) {
				logger.debug(threadId()+"no room for more filibuster instances; reusing a filibuster instance");
				int minThreads = -1;
				FilibusterTTSResource instanceWithLeastThreads = null;
				for (FilibusterTTSResource instance : filibusterInstances.keySet()) {
					List<Thread> threadList = filibusterInstances.get(instance);
					if (minThreads < 0 || threadList.size() < minThreads) {
						minThreads = threadList.size();
						instanceWithLeastThreads = instance;
					}
				}
				filibusterInstances.get(instanceWithLeastThreads).add(Thread.currentThread());
				filibusterResource = instanceWithLeastThreads;
			}
		}
		logger.debug(threadId()+"synthesize() -- released engine lock");
		
		try {
			synchronized (filibusterResource.filibusterInstance) {
				return filibusterResource.filibusterInstance.synthesize(sentence, bufferAllocator);
			}
			
		} catch (SynthesisException e) {
			for (StackTraceElement line : e.getStackTrace()) {
				logger.warn(threadId()+line.toString());
			}
			throw e;
			
		} catch (InterruptedException e) {
			for (StackTraceElement line : e.getStackTrace()) {
				logger.warn(threadId()+line.toString());
			}
			throw e;
			
		} catch (MemoryException e) {
			for (StackTraceElement line : e.getStackTrace()) {
				logger.warn(threadId()+line.toString());
			}
			throw e;
		}
	}

	@Override
	public AudioFormat getAudioOutputFormat() {
		return audioFormat;
	}

	@Override
	public Collection<Voice> getAvailableVoices() {
		Collection<Voice> result = new ArrayList<Voice>();
		result.add(new Voice(getProvider().getName(), "Brage"));
		return result;
	}

	@Override
	public int getOverallPriority() {
		return priority;
	}

	@Override
	public TTSResource allocateThreadResources() throws SynthesisException, InterruptedException {
		for (FilibusterTTSResource filibusterResource : filibusterInstances.keySet()) {
			for (Thread thread : filibusterInstances.get(filibusterResource)) {
				if (thread == Thread.currentThread()) {
					return filibusterResource.ttsResource;
				}
			}
		}
		return null;
	}

	@Override
	public void releaseThreadResources(TTSResource resource) throws SynthesisException, InterruptedException {
		logger.debug(threadId()+"releaseThreadResources() -- waiting for engine lock");
		synchronized (this) {
			logger.debug(threadId()+"releaseThreadResources() -- got engine lock");
			List<Thread> threadList = null;
			FilibusterTTSResource filibusterInstance = null;
			for (FilibusterTTSResource filibusterResource : filibusterInstances.keySet()) {
				threadList = filibusterInstances.get(filibusterResource);
				if (threadList.contains(Thread.currentThread())) {
					filibusterInstance = filibusterResource;
					break;
				}
			}
			if (threadList != null) {
				threadList.remove(Thread.currentThread());
				logger.debug(threadId()+"thread removed itself from filibuster instance");
				if (threadList.isEmpty()) {
					logger.debug(threadId()+"no more threads using this filibuster instance; stopping filibuster...");
					// Try stopping Filibuster
					synchronized (filibusterInstance.filibusterInstance) {
						filibusterInstance.filibusterInstance.stopFilibuster();
					}
					filibusterInstances.remove(filibusterInstance);
				}
			}
		}
		super.releaseThreadResources(resource);
		logger.debug(threadId()+"releaseThreadResources() -- released engine lock");
		
	}

}
