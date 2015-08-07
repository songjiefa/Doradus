package com.dell.doradus.dory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import com.dell.doradus.client.Credentials;
import com.dell.doradus.client.RESTClient;
import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.ContentType;
import com.dell.doradus.common.HttpMethod;
import com.dell.doradus.common.RESTResponse;
import com.dell.doradus.common.Utils;
import com.dell.doradus.dory.command.Command;

public class DoradusClient implements AutoCloseable {
		
	private final RESTClient restClient;
	private String applicationName;
	private String storageService;
	private JsonObject restMetadataJson = null;
	
	/**
	 * Constructor
	 * @param host
	 * @param port
	 */
	public DoradusClient(String host, int port) {
        this(host, port, "", null, null);	
	}
	
	public DoradusClient(String host, int port, Credentials credentials) {
        this(host, port, "", credentials, null);	        
	}
		
    /**
     * setCredentials
     * @param credentials
     */
    public void setCredentials(Credentials credentials) {
    	restClient.setCredentials(credentials);
    }   
    
    /**
     * setCredentials such as tenant, username, password
     * @param tenant
     * @param username
     * @param userpassword
     */
	public void setCredentials(String tenant, String username, String userpassword) {
		Credentials credentials = new Credentials(tenant, username, userpassword);
		restClient.setCredentials(credentials);
	}
	
	/**
	 * retrieve the map of commands by service name
	 * @return
	 */
	public Map<String, List<String>> listCommands() {
		Map<String, List<String>> result = new HashMap<String, List<String>>();
		for (String cat : restMetadataJson.keySet())  {
			JsonObject commands = restMetadataJson.getJsonObject(cat);
			List<String> names = new ArrayList<String>();
			for (String commandName: commands.keySet()) {
				names.add(commandName);
			}		
			result.put(cat, names);
    	}
		return result;
	} 	
	
	
	/**
	 * describeCommand
	 * @param service
	 * @param command
	 * @return JsonObject
	 */
	public JsonObject describeCommand(String service, String command) {
		return Command.matchCommand(restMetadataJson, command, service);
		
	}
	
	public RESTResponse runCommand(Command command) throws IOException {
		if (this.applicationName != null) {
			command.setApplicationName(this.applicationName);
		}
		if (this.storageService != null) {
			command.setStorageService(this.storageService);
		}		
		if (this.restMetadataJson != null) {
			command.setRestMetadataJson(this.restMetadataJson);
		}
		command.validate(restClient);
		return command.call(restClient);		
	}
	
	
	public RESTClient getRestClient() {
		return restClient;
	}
	
	/**
	 * private constructor
	 * @param host
	 * @param port
	 * @param applicationName
	 */
	private DoradusClient(String host, int port, String apiPrefix, Credentials credentials, String applicationName) {
		this.restClient = new RESTClient(host, port, "");
		this.restClient.setCredentials(credentials);
		this.applicationName = applicationName;
		this.storageService = null;
		loadRESTRulesIfNotExist(restClient);
	}


	/**
	 * static factory method
	 * @param host
	 * @param port
	 * @param applicationName
	 * @return
	 */
	public static DoradusClient open(String host, int port, Credentials credentials, String applicationName) {	
		DoradusClient doradusClient = new DoradusClient(host, port, null, credentials, applicationName);	
		doradusClient.setCredentials(credentials);
		String storageService = lookupStorageServiceByApp(doradusClient.getRestClient(), applicationName);
		doradusClient.setStorageService(storageService);
		return doradusClient;
	}

	private static String lookupStorageServiceByApp(RESTClient restClient, String applicationName) {
		Utils.require(applicationName != null, "Missing application name");
		if (applicationName != null) {
			ApplicationDefinition appDef =  Command.getAppDef(restClient, applicationName);
		    Utils.require(appDef != null, "Unknown application: %s", applicationName);
		    return appDef.getStorageService();
		}
		return null;
	}
	
	private void setStorageService(String storageService) {
		this.storageService = storageService;
		
	}

	@Override
	public void close() throws Exception {
		if (this.restClient != null) {
			this.restClient.close();
		}
		
	}

	protected synchronized void loadRESTRulesIfNotExist(RESTClient restClient)  {		
		if (restMetadataJson == null || restMetadataJson.isEmpty()) {
			restMetadataJson = loadRestRulesFromServer(restClient);
		}		
	}
	
	private JsonObject loadRestRulesFromServer(RESTClient restClient) {
		RESTResponse response = null;
		try {
			response = restClient.sendRequest(HttpMethod.GET, "/_commands", ContentType.APPLICATION_JSON, null);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (!response.getCode().isError()) {
			JsonReader jsonReader = Json.createReader(new StringReader(response.getBody()));			
			JsonObject result = jsonReader.readObject().getJsonObject("commands");
			jsonReader.close();
			return result;
		}
		else {
			throw new RuntimeException("Describe command error: " + response.getBody());
		}
	}




}