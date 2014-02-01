package bpipe

import groovy.util.ConfigObject;

import groovy.util.logging.Log;

/**
 * Global configuration properties for Bpipe.
 * 
 * @author simon.sadedin@mcri.edu.au
 */
@Log
class Config {
    
	/**
	 * Lower level configuration values.  These are not directly user 
	 * exposed.
	 */
    static Map<String,Object> config = [
        columns: 100,
        
        // Default mode is "run", but "define" will just produce a definition
        // of the pipeline without executing it.
        mode : "run",
        
        // By default all outputs get created in the current directory, but
        // the user can override it from the command line and in the future
        // some features might make outputs go to separate directories
        // (eg: per sample, etc.)
        defaultOutputDirectory : ".",

	    // Default name of doc pipeline html file
		defaultDocHtml: "index.html",

        // The maximum number of threads that Bpipe will launch 
        // when running jobs
        maxThreads : 32,
        
        // Whether the user set the threads or not by command line / config
        customThreads : false,
        
        // Whether to enable detection of changes to commands so
        // that outputs produced by them can be invalidated
        // This feature is still experimental, so off by 
        // default for now
        enableCommandTracking : false, 
		
		// For many commands we don't want the log files to persist
		// eg: the output of the help info
		// This flag is read on shutdown and if true, a 
		// marker file is written that causes the log files to be 
		// ignored / cleaned up 
		eraseLogsOnExit : true, 
		
		// If set to true an HTML report of the run is generated
		report: false, 
		
		// The PID of the Bpipe Java process (set immediately after startup)
		pid: null,
		
		// The path to the file that is capturing the output for this 
		// Bpipe run 
		outputLogPath: null,
        
        // Stages where the pipeline should break at (set with -u on command line)
        breakAt: [],
        
        // Whether a break has been triggered
        breakTriggered : false
    ]
    
    /**
     * Configuration loaded from the local directory
     */
    public static ConfigObject userConfig
    
    /**
     * Read user controlled configuration file from several cascading locations.
     * The different configuration files are read in order so that they take increasing
     * priority.
     * <li>.bpipeconfig in the user's home directory (lowest priority)
     * <li>bpipe.config in the location of the pipeline definition file
     * <li>bpipe.config in the location where the pipeline is running
     * 
     * (the last two above will often be the same, but when one pipeline for running many
     * different analyses it will be useful)
     */
    public static void readUserConfig() {
		
        ConfigSlurper slurper = new ConfigSlurper()
        
		
		File builtInConfigFile = new File(System.getProperty("bpipe.home") +"/bpipe.config")
		
		// Allows running in-situ in project source distro root dir to work
		if(!builtInConfigFile.exists()) {
			builtInConfigFile = new File(System.getProperty("bpipe.home") + "/src/main/config", "bpipe.config")
		}
		
		ConfigObject builtInConfig = slurper.parse(builtInConfigFile.toURI().toURL())
        
        // The default way to prompt user for information is to ask at the console
        builtInConfig.prompts.handler = { msg ->
            print msg
            return System.in.withReader { it.readLine() }
        }
		
        // Configuration in user home directory
		File homeConfigFile = new File(System.getProperty("user.home"), ".bpipeconfig")
		ConfigObject homeConfig
		if(homeConfigFile.exists()) {
            homeConfig = slurper.parse(homeConfigFile.toURI().toURL())
		}
        
        File configFile = new File("bpipe.config")
        
        // Configuration in directory next to main pipeline script
        
		ConfigObject pipelineConfig
        if(config.script) {
            File pipelineConfigFile = new File(new File(config.script).absoluteFile.parentFile, "bpipe.config")
            if(pipelineConfigFile.exists() && (pipelineConfigFile.absolutePath != configFile.absolutePath)) {
                log.info "Reading Bpipe configuration from ${pipelineConfigFile.absolutePath}"
                pipelineConfig = slurper.parse(pipelineConfigFile.toURI().toURL())
            }
            else {
                log.info "No configuration file found in same dir as pipeline file"
            }
        }
  		
        // Configuration in local directory (where pipeline is running)
		ConfigObject localConfig
        if(configFile.exists()) {
            log.info "Reading Bpipe configuration from ${configFile.absolutePath}"
            localConfig = slurper.parse(configFile.toURI().toURL())
        }
        else {
            log.info "No local configuration file found"
        }
		
        userConfig = builtInConfig ? builtInConfig : new ConfigObject()
		if(homeConfig) {
			log.info "Merging home config file"
			userConfig.merge(homeConfig)
		}
        
        if(pipelineConfig) {
            log.info "Merging pipeline config file"
			userConfig.merge(pipelineConfig)
        }
        
		if(localConfig) {
			log.info "Merging local config file"
			userConfig.merge(localConfig)
		}
		
        if(!userConfig.executor) {
            userConfig.executor = "local"
        }
        else
            log.info "Default executor is $userConfig.executor"
            
            
       // Allow user to over ride any value in config with local config 
       userConfig.flatten().each { k,v ->
           
           if(k == "mode") 
               return
           
           if(config.containsKey(k)) {
               log.info "Overriding default config value ${config[k]} with user defined value ${v}"
               config[k] = v
           }
       }
    }
}
