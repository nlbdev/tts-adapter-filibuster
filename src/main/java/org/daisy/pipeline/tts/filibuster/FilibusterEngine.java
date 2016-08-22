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
import org.daisy.pipeline.tts.MarklessTTSEngine;
import org.daisy.pipeline.tts.TTSRegistry.TTSResource;
import org.daisy.pipeline.tts.TTSService.SynthesisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.daisy.pipeline.tts.Voice;

public class FilibusterEngine extends MarklessTTSEngine {
	private Logger logger = LoggerFactory.getLogger(FilibusterEngine.class);

	private AudioFormat audioFormat;
	private String[] cmd;
	private String[] env;
	private File filibusterPath;
	private int priority;
    
	private static Map<FilibusterInstance, List<Thread>> filibusterInstances = Collections.synchronizedMap(new HashMap<FilibusterInstance, List<Thread>>());
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
	public Collection<AudioBuffer> synthesize(String sentence, XdmNode xmlSentence, Voice voice, TTSResource threadResources,
											  AudioBufferAllocator bufferAllocator, boolean retry)
													  	throws SynthesisException,InterruptedException, MemoryException {
		
		logger.debug(threadId()+"synthesizing: '"+sentence+"'");
		
		FilibusterInstance filibusterInstance = null;
		
		synchronized (filibusterInstances) {
			for (FilibusterInstance instance : filibusterInstances.keySet()) {
				List<Thread> threadList = filibusterInstances.get(instance);
				if (threadList.contains(Thread.currentThread())) {
					filibusterInstance = instance;
				}
			}
			if (filibusterInstance == null && filibusterInstances.size() < MAX_FILIBUSTER_INSTANCES) {
				filibusterInstance = new FilibusterInstance(cmd, env, filibusterPath);
				List<Thread> threadList = new ArrayList<Thread>();
				threadList.add(Thread.currentThread());
				filibusterInstances.put(filibusterInstance, threadList);
			}
			if (filibusterInstance == null) {
				int minThreads = -1;
				FilibusterInstance instanceWithLeastThreads = null;
				for (FilibusterInstance instance : filibusterInstances.keySet()) {
					List<Thread> threadList = filibusterInstances.get(instance);
					if (minThreads < 0 || threadList.size() < minThreads) {
						minThreads = threadList.size();
						instanceWithLeastThreads = instance;
					}
				}
				filibusterInstances.get(instanceWithLeastThreads).add(Thread.currentThread());
				filibusterInstance = instanceWithLeastThreads;
			}
		}
		
		return filibusterInstance.synthesize(sentence, bufferAllocator);
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
		return new TTSResource();
	}

	@Override
	public void releaseThreadResources(TTSResource resource) throws SynthesisException, InterruptedException {
		super.releaseThreadResources(resource);
		
		synchronized (filibusterInstances) {
			List<Thread> threadList = null;
			FilibusterInstance filibusterInstance = null;
			for (FilibusterInstance instance : filibusterInstances.keySet()) {
				threadList = filibusterInstances.get(instance);
				if (threadList.contains(Thread.currentThread())) {
					filibusterInstance = instance;
					break;
				}
			}
			if (threadList != null) {
				threadList.remove(Thread.currentThread());
				if (threadList.isEmpty()) {
					// Try stopping Filibuster
					filibusterInstance.stopFilibuster();
					filibusterInstances.remove(filibusterInstance);
				}
			}
		}
		
	}

}