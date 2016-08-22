package org.daisy.pipeline.tts.filibuster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.daisy.pipeline.audio.AudioBuffer;
import org.daisy.pipeline.tts.AudioBufferAllocator;
import org.daisy.pipeline.tts.SoundUtil;
import org.daisy.pipeline.tts.AudioBufferAllocator.MemoryException;
import org.daisy.pipeline.tts.TTSService.SynthesisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilibusterInstance {
	private Logger logger = LoggerFactory.getLogger(FilibusterInstance.class);
	
	private String[] cmd;
	private String[] env;
	private File filibusterPath;

	private Process process = null;
	private BufferedOutputStream stdin = null;
	private BufferedInputStream stdout = null;
	
	private Date instanceStartTime = null;
	
	private final static int MIN_CHUNK_SIZE = 2048;

	public String threadId() {
		// for debugging
		return (Thread.currentThread().getId()+"").replaceAll("^.*(..)$", "$1")+": ";
	}
	
	public FilibusterInstance(String[] cmd, String[] env, File filibusterPath) {
		this.cmd = cmd;
		this.env = env;
		this.filibusterPath = filibusterPath;
	}
	
	public synchronized void startFilibuster(boolean force) throws SynthesisException, InterruptedException {
		// Start Filibuster instance
		if (force || process == null || !process.isAlive()) {
			if (process != null && process.isAlive()) {
				stopFilibuster();
			}
			logger.debug(threadId()+"starting Filibuster instance...");
			try {
				process = Runtime.getRuntime().exec(cmd, env, filibusterPath);
				stdin = new BufferedOutputStream((process.getOutputStream()));
				stdout = new BufferedInputStream(process.getInputStream());
				instanceStartTime = new Date();
				logger.debug(threadId()+"instance started: "+process);
				
			} catch (Exception e) {
				StringWriter sw = new StringWriter();
				e.printStackTrace(new PrintWriter(sw));
				stopFilibuster();
				throw new SynthesisException(e);
			}
		}
	}

	public synchronized void stopFilibuster() throws SynthesisException, InterruptedException {
		logger.debug(threadId()+"stopping Filibuster instance...");
		try {
			if (stdin != null) {
				stdin.write("\n\n".getBytes("utf-8")); // send empty string to try and make filibuster shut down voluntarily
				stdin.close();
				stdout.close();
			}
			if (process != null) {
				process.waitFor(1, TimeUnit.SECONDS);
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (process != null)
				process.destroy();
			throw e;

		} catch (Exception e) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (process != null)
				process.destroy();
			throw new SynthesisException(e);
		} finally {
			process = null;
			stdin = null;
			stdout = null;
		}
		instanceStartTime = null;
	}
	
	public synchronized Collection<AudioBuffer> synthesize(String sentence, AudioBufferAllocator bufferAllocator) throws SynthesisException, MemoryException, InterruptedException {
		Date startTime = new Date();
		
		Collection<AudioBuffer> result = new ArrayList<AudioBuffer>();
		
		startFilibuster(false);
		
		try {
			// write the text
			logger.debug(threadId()+"writing the text to process "+process);
			stdin.write((sentence.replaceAll("\n", " ")+" \n").getBytes("utf-8"));
			logger.debug(threadId()+"flushing the text to process "+process);
			stdin.flush();
			
			// read the wave on the standard output
			
			/* debugging stuff */
			File file = null;
			FileOutputStream fos = null;
			String byteString = "";
			if (logger.isDebugEnabled()) {
				file = Files.createTempFile("out", ".wav").toFile();
				fos = new FileOutputStream(file);
			}
			
			int bytesAvailable = stdout.available();
			byte[] header = new byte[44];
			int headerPos = 0;
			int bytesRead = 0;
			int bytesExpected = 44;
			Date timeout = new Date();
			while (bytesAvailable > 0 || bytesRead < bytesExpected) {
				if (bytesAvailable <= 0) {
					if (new Date().getTime() - timeout.getTime() < 1000 || new Date().getTime() - instanceStartTime.getTime() < 60000) {
						logger.debug(threadId()+"sleeping 100ms to see if more data arrives...");
						Thread.sleep(100);
						
					} else {
						break;
					}
					
				} else {
					logger.debug(threadId()+"allocating buffer for "+bytesAvailable+" available bytes");
					AudioBuffer b = bufferAllocator.allocateBuffer(MIN_CHUNK_SIZE + bytesAvailable);
					logger.debug(threadId()+"reading data into buffer of size '"+b.size+"': "+b+" ("+new Date()+")");
					int ret = stdout.read(b.data, 0, b.size);
					if (ret == -1) {
						logger.debug(threadId()+"end of stream reached");
						bufferAllocator.releaseBuffer(b);
						break;
					} else if (ret > 0) {
						logger.debug(threadId()+"Read bytes from process '"+process+"': "+ret+" ("+new Date()+")");
						timeout = new Date();
					}
					
					// store header in `header` and check expected file size.
					for (int dataPos = 0; headerPos < header.length && dataPos < b.data.length; dataPos++) {
						header[headerPos] = b.data[dataPos];
						headerPos++;
					}
					if (bytesExpected == 44 && headerPos > 7) {
						ByteBuffer bb = ByteBuffer.wrap(new byte[]{header[4],header[5],header[6],header[7]});
						bb.order(ByteOrder.LITTLE_ENDIAN);
						bytesExpected = bb.getInt()+8;
						logger.debug(threadId()+"WAV file should be "+bytesExpected+" bytes in size. "+bytesRead+" bytes have been read so far.");
					}
					
					b.size = ret;
					bytesRead += ret;
					result.add(b);
					
					if (logger.isDebugEnabled()) {
						byteString += new String(b.data, "utf-8");
						fos.write(b.data);
					}
					
				}
				
				try {
					bytesAvailable = stdout.available();
				} catch (IOException e) {
					logger.debug(threadId()+" stream closed");
					bytesAvailable = 0; // stream closed
				}
			}
			
			/* debugging */
			if (logger.isDebugEnabled()) {
				String badData = byteString.startsWith("RIFF") ? "(GOOD DATA)" : "(BAD DATA)";
				if (byteString.length() > 50) {
					logger.debug(threadId()+badData+" ["+byteString.substring(0, 50)+"...]");
				} else {
					logger.debug(threadId()+badData+" ["+byteString+"]");
				}
				fos.close();
				logger.debug(threadId()+"wrote: "+file.getAbsolutePath());
			}
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					// `fos` is for debugging so any exception here should be safe to ignore
				}
			}
			
			// hopefully it's ok to not close audioStream ? Otherwise, how can I close the audioStream without closing the underlying stdout?
			
		} catch (MemoryException e) {
			SoundUtil.cancelFootPrint(result, bufferAllocator);
			process.destroy();
			throw e;
			
		} catch (Exception e) {
			SoundUtil.cancelFootPrint(result, bufferAllocator);
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (process != null)
				process.destroy();
			throw new SynthesisException(e);
		}
		logger.debug(threadId()+"done synthesizing: '"+sentence+"'");
		logger.debug(threadId()+"time spent: "+(new Date().getTime() - startTime.getTime())/1000L+"s");
		logger.debug(threadId()+"----------");
		return result;
	}
}
