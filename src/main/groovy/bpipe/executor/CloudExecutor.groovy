/*
 * Copyright (c) 2019 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe.executor

import java.util.List
import java.util.Map

import bpipe.Command
import bpipe.CommandStatus
import bpipe.ExecutedProcess
import bpipe.Utils
import groovy.transform.CompileStatic
import groovy.util.logging.Log

/**
 * A cloud executor is an executor that requires additional steps to 
 * acquire the computing resource and map files between bpipe and the remote
 * system.
 * 
 * @author Simon Sadedin
 */
@Log
abstract class CloudExecutor implements PersistentExecutor {
    
    /**
     * The command executed by the executor
     */
    String commandId 
    
    /**
     * The id of the instance in the cloud provider to which this executor is attached
     */
    String instanceId
    
    /**
     * The exit code for the command run by this executor, if any
     */
    Integer exitCode = null
    
    /**
     * The command executed, but only if it was started already
     */
    transient Command command
    
    /**
     * Set to true after the command is observed to be finished
     */
    boolean finished = false
    
    /**
     * Whether the executor is currently in the process of acquiring its instance
     */
    boolean acquiring = false

    public void start(Map cfg, Command cmd, Appendable outputLog, Appendable errorLog) {
        
        // Acquire my instance
        acquiring = true
        
        // Get the instance type
        String image = cfg.get('image')
        
        
        if(this.instanceId == null) {
            log.info "Cloud executor is not connected to running instance: acquiring instance"
            this.acquireInstance(cfg, image, cmd.id)
        }
        else 
            this.acquiring = false
        
        
        // It can take a small amount of time before the instance can be ssh'd to - downstream 
        // functions will assume that an instance is available for SSH, so it's best to do
        // that check now
        this.waitForSSHAccess()
       
        this.mountStorage(cfg)
        
        // Execute the command via SSH
        this.startCommand(cmd, outputLog, errorLog)
    }
    
    @Override
    @CompileStatic
    public int waitFor() {
        
        while(true) {
            CommandStatus status = this.status()
            if(status == CommandStatus.COMPLETE) 
                return exitCode
                
            Thread.sleep(5000)
        }
    }

    abstract void acquireInstance(Map config, String image, String id)
    
    /**
     * Implementation specific method to execute a raw command over SSH
     * <p>
     * The implementation <em>must</em> throw an exception if the SSH command fails.
     */
    abstract ExecutedProcess ssh(String cmd, Closure builder=null)

    abstract void startCommand(Command command, Appendable outputLog, Appendable errorLog) 
    
    abstract void mountStorage(Map config) 
    
    protected void waitForSSHAccess() {
        Utils.withRetries(8, backoffBaseTime:3000, message:"Test connect to $instanceId") {
            canSSH()
        }
    }
    
    
    /**
     * Scenario:
     * 
     *  - start pooled gce
     *  - it creates .bpipe in *shared* bucket
     *  - kill gce
     *    - .bpipe is left behind
     *  - start new pooled gce
     *    - it sees old .bpipe
     *  - now problem: it picks up wrong state!
     * 
     * Solution:
     * 
     *  - Q: what makes a pooled executor smart enough to use GC storage instead of local?
     *  - A: it's GCE itself that runs the pool command, so when it executes it naturally does
     *       that on the VM instance, in the work directory, resulting in the .bpipe files etc
     *  - Q: how then does the pooled executor write the command file to GC storage?
     * 
     * @param command
     * @return
     */
    File getJobDir(String commandId) {
        String jobDir = ".bpipe/commandtmp/$commandId"
		File jobDirFile = new File(jobDir)
        if(!jobDirFile.exists())
		    jobDirFile.mkdirs() 
        return jobDirFile
    }
    
    boolean canSSH() {
        ssh('true')
        
        return true // if we could not SSH, the command would have thrown
    }
    
}
