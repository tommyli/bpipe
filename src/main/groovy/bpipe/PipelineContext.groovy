package bpipe

import groovy.lang.Binding;
import groovy.lang.Closure;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static Utils.*

/**
* This context defines implicit functions and variables that are
* made available to Bpipe scripts.
* <p>
* Note: currently other functions are also made available by the
* PipelineCategory, however I hope to migrate these eventually
* to all use this context.
*/
class PipelineContext {
   
   /**
    * Logger for this class to use
    */
   private static Logger log = Logger.getLogger("bpipe.PipelineContext");
   
   /**
    * Create a Pipeline Context with the specified adidtional bound variables and
    * pipeline stages as well.
    *
    * @param extraBinding    extra variables to make referenceable by pipeline stages
    *                        that execute using this context
    * @param pipelineStages  list of known pipeline stages.  These are used to resolve
    *                        inputs when the user uses the implicit 'from' by appending
    *                        an extension to the implicit input variable (eg: $input.csv).
    *                        The stages are searched to find the most recent stage with
    *                        an output that matches the specified extension.
    *
    */
   public PipelineContext(Binding extraBinding, List<PipelineStage> pipelineStages, List<Closure> pipelineJoiners) {
       super();
       if(pipelineStages == null)
           throw new IllegalArgumentException("pipelineStages cannot be null")
       this.pipelineStages = pipelineStages
       this.extraBinding = extraBinding
       this.pipelineJoiners = pipelineJoiners
   }
   
   /**
    * Additional variables that are injected into the pipeline stage when it executes.
    * In practice, these allow it to resolve pipeline stages that are loaded from external
    * files (which otherwise would not be in scope).
    */
   Binding extraBinding
   
   /**
    * The stage name for which this context is running
    */
   String stageName

   private List<PipelineStage> pipelineStages
   
   private List<Closure> pipelineJoiners
   
   /**
    * The default output is set prior to the body of the a pipeline stage being run.
    * If the pipeline stage does nothing else but references $output then the default output is
    * the one that is returned.  However the pipeline stage may modify the output
    * by use of the transform, filter or produce constructs.  If so, the actual output
    * is stored in the output property.
    */
   def defaultOutput
   
   def getDefaultOutput() {
       return this.@defaultOutput
   }
   
   def setDefaultOutput(defOut) {
       this.@defaultOutput = toOutputFolder(defOut)
   }
   
   def output
   
   def setOutput(o) {
       this.output = toOutputFolder(o)
   }
   
   def getOutput() {
       if(output == null) { // Output not set elsewhere
           // If an input property was referenced, compute the default from that instead
           if(inputWrapper?.resolvedInputs) {
               log.info("Using non-default output due to input property reference: " + inputWrapper.resolvedInputs[0])
               return inputWrapper.resolvedInputs[0] +"." + this.stageName
           }
           return defaultOutput
       }
       return output
   }
   
   /**
    * Coerce all of the arguments (which may be an array of Strings or a single String) to
    * point to files in the local directory.
    */
   def toOutputFolder(outputs) {
       File outputFolder = new File(".")
       outputs = Utils.box(outputs).collect { new File(it).name }
       return Utils.unbox(outputs)
   }
   
   /**
    * Input to a stage - can be either a single value or a list of values
    */
   def input
   
   /**
    * Wrapper that intercepts calls to resolve input properties
    */
   PipelineInput inputWrapper
   
    /**
     * The inputs to be passed to the next stage of the pipeline.
     * Usually this is the same as context.output but it doesn't have
     * to be.
     */
    def nextInputs
   
   String getInput1() {
       return Utils.first(input)
   }
   
   String getInput2() {
       if(!Utils.isContainer(input) || input.size()<2)
           throw new PipelineError("Expected 2 or more inputs but 1 provided")
       return input[1]
    }
   
   String getInput3() {
       if(!Utils.isContainer(input) || input.size()<3)
           throw new PipelineError("Expected 3 or more inputs but 1 provided")
       return input[2]
   }
   
    /**
    * Check if there is an input, if so, return it.  If not,
    * throw a helpful error message.
    * <br>
    * Note: if you wish to access the 'input' property without performing
    * this check (eg: to check if it is empty yourself) then you can use
    * direct property access - eg: ctx.@input
    * @return
    */
   def getInput() {
       if(this.@input == null || Utils.isContainer(this.@input) && this.@input.size() == 0) {
           throw new PipelineError("Input expected but not provided")
       }
       if(!inputWrapper)
           inputWrapper = new PipelineInput(this.@input, pipelineStages)
           
       return inputWrapper
   }
   
   def setInput(def inp) {
       this.@input = inp
   }
   
   def getInputs() {
       return Utils.box(this.@input).join(" ")
   }
   
   /**
    * Output stream, only opened if the stage body references
    * the "out" variable
    */
   FileOutputStream outFile
   
   /**
    * Iterate through the file line by line and pass each line to the given closure.
    * Output lines for which the closure returns true to the output.
    * If header is true, lines beginning with # will be passed to the
    * body, otherwise they will be automatically output.
    */
   def filterLines(Closure c) {
       
       if(!input)
           throw new PipelineError("Attempt to grep on input but no input available")
           
       if(Runner.opts.t)
           throw new PipelineTestAbort("Would execute filterLines on input $input")
           
       new File(isContainer(input)?input[0]:input).eachLine {  line ->
           if(line.startsWith("#") || c(line))
                  getOut() << line << "\n"
       }
   }
   
   /**
    * Search in the input file for lines matching speficied regex pattern
    * For each line found, if a function is passed, call it, otherwise
    * output the matching line to the default output file.
    * <p>
    * Lines starting with '#' are treated as comments or headers and are
    * not passed to the body.
    */
   def grep(String pattern, Closure c = null) {
       if(!input)
           throw new PipelineError("Attempt to grep on input but no input available")
           
       def regexp = Pattern.compile(pattern)
       new File(isContainer(input)?input[0]:input).eachLine {
           if(it.startsWith("#"))
               return
               
           if(it =~ regexp) {
              if(c != null)
                  c(it)
              else
                  getOut() << it << "\n"
           }
       }
   }
   
   /**
    * Returns an output stream to which the current pipeline stage can write
    * directly to create output.
    *
    * @return
    */
   OutputStream getOut() {
       
       if(!outFile) {
         String fileName = Utils.first(output)
         if(Runner.opts.t)
             throw new PipelineTestAbort("Would write to output file $fileName")
          
         outFile = new FileOutputStream(fileName)
       }
       return outFile
   }
   
    /**
     * Specifies an output that is a transformation of the input 
     * to a different format - keeps same file name but replaces
     * the extension.  Convenience wrapper for {@link #produce(Closure, Object, Closure)}
     * for the case where only the extension on the file is changed. 
     */
    Object transform(String extension, Closure body) {
        def inp = Utils.first(this.@input)
        if(!inp) 
           throw new PipelineError("Expected input but no input provided") 
        this.produce(inp.replaceAll('\\.[^\\.]*$','.'+extension),body)
    }
  
   
    /**
     * Specifies an output that keeps the same type of file but modifies 
     * it ("filters" it). Convenience wrapper for {@link #produce(Closure, Object, Closure)}
     * for the case where the same file extension is kept but a transformation
     * type is added to the name.
     */
    Object filter(String type, Closure body) {
        def inp = Utils.first(this.@input)
        log.info("Filter $type defined on inputs $inp")
        if(!inp) 
           throw new PipelineError("Expected input but no input provided") 
           
        this.produce(inp.replaceAll('(\\.[^\\.]*$)','.'+type+'$1'),body)
    }
    
       /**
     * Specifies that the given output (out) will be produced
     * by the given closure, and skips execution of the closure
     * if the output is newer than all the current inputs.  
     */
    Object produce(Object out, Closure body) { 
        log.info "Producing $out from $this"
        
        // Unwrap any wrapped inputs that may have been passed in the outputs
        out = Utils.unwrap(out)
        
        def lastInputs = this.@input
        if(Utils.isNewer(out,lastInputs)) {
          msg("Skipping steps to create $out because newer than $lastInputs ")
	      this.output = out
        }
        else {
            this.output = out
            
            // Store the list of output files so that if we are killed 
            // they can be cleaned up
            PipelineStage.UNCLEAN_FILE_PATH.text += Utils.box(this.output)?.join("\n") 
            
            PipelineDelegate.setDelegateOn(this, body)
	        log.info("Producing from inputs ${this.@input}")
            def nextIn= body()
            if(nextIn)
                this.nextInputs = nextIn
            else
                this.nextInputs = null
        }
        return out
    }
    
    /**
     * Provides an implicit "exec" function that pipeline stages can use
     * to run commands.  This variant blocks and waits for the 
     * shell command to exit.  If the command returns a failure exit code
     * (non zero) then an exception is thrown.
     * 
     * @see #async(Closure, String)
     */
    void exec(String cmd) {
      Process p = async(cmd)
      if(p.waitFor() != 0) {
        // Output is still spooling from the process.  By waiting a bit we ensure
        // that we don't interleave the exception trace with the output
        Thread.sleep(200)
        throw new PipelineError("Command failed with exit status != 0: \n$cmd")
      }
    }
    
    String capture(String cmd) {
      new File('commandlog.txt').text += '\n'+cmd
      def joined = ""
      cmd.eachLine { joined += " " + it }
      // println "Joined command is: $joined"
      
      def p = Runtime.getRuntime().exec((String[])(['bash','-c',"$joined"].toArray()))
      StringWriter outputBuffer = new StringWriter()
      p.consumeProcessOutput(outputBuffer,System.err)
      p.waitFor()
      return outputBuffer.toString()
    }
    
    /**
     * Asynchronously executes the given command by passing it to 
     * a bash shell for execution.  The exit code is not checked and
     * the command may still be running on return.  The Process object 
     * for the command that is run is returned.  Callers can use the
     * {@link Process#waitFor()} to wait for the process to finish.
     */
    Process async(String cmd) {
      if(Runner.opts.t)
          throw new PipelineTestAbort("Would execute: $cmd")
          
      def joined = ""
      cmd.eachLine { joined += " " + it }
//      println "Joined command is: $joined"
      
      new File('commandlog.txt').text += '\n'+cmd
      def p = Runtime.getRuntime().exec((String[])(['bash','-c',"$joined"].toArray()))
      p.consumeProcessOutput(System.out, System.err)
      return p
    }
    
    
    static void msg(m) {
        def date = (new Date()).format("HH:mm:ss")
        println "$date MSG:  $m"
    }
   
   /**
    * Executes the given body with 'input' defined to be the
    * most recently produced output file(s) matching the
    * extensions specified by input
    * <p>
    * TODO: this performs essentially the same function as 
    * {@link PipelineInput#propertyMissing(String)}, it would 
    * be nice to merge them together and get rid of this one.
    *
    * @param c
    * @param inputs
    * @param body
    * @return
    */
   Object from(Object stageInputs, Closure body) {
       
       log.info "Searching for inputs matching spec $stageInputs"
       
       def reversepipelineStages = pipelineStages.reverse()
       def orig = stageInputs
       
       def reverseOutputs = pipelineStages.reverse().collect { Utils.box(it.context.output) }
       
       // Add a final stage that represents the original inputs (bit of a hack)
       // You can think of it as the initial inputs being the output of some previous stage
       // that we know nothing about
       reverseOutputs.add(Utils.box(pipelineStages[0].context.@input))
       
       // Add an initial stage that represents the current input to this stage.  This way
       // if the from() spec is used and matches the actual inputs then it will go with those
       // rather than searching backwards for a previous match
       reverseOutputs.add(0,Utils.box(this.@input))
       
       def resolvedInputs = Utils.box(stageInputs).collect { String inp ->
           
           if(!inp.startsWith("."))
               inp = "." + inp
           
           for(s in reverseOutputs) {
               def o = s.find { it?.endsWith(inp) }
               if(o) {
                   log.info("Checking ${s} vs $inp  Y")
                   return o
               }
               log.info("Checking outputs ${s} vs $inp N")
               
           }
       }
       
       if(resolvedInputs.any { it == null})
           throw new PipelineError("Unable to locate one or more specified resolvedInputs matching spec $orig")
           
       log.info "Found inputs $resolvedInputs for spec $orig"
       
       resolvedInputs = Utils.unbox(resolvedInputs)
       
       def oldInputs = this.@input
       this.@input  = resolvedInputs
       
       this.getInput().resolvedInputs << resolvedInputs
       
       this.nextInputs = body()
       
       this.@input  = oldInputs
       this.@inputWrapper = null
       return this.nextInputs
   }
   
   /**
    * The current stage is always the most recent stage to have executed
    * @return
    */
   PipelineStage getCurrentStage() {
       return this.stages[-1]
   }
}
