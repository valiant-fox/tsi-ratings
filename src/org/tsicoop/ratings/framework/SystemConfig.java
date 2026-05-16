package org.tsicoop.ratings.framework;

import jakarta.servlet.ServletContext;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Properties;


public class SystemConfig {
    private static Properties appConfig;
    private static Properties schemaConfig;
    private static Properties processorConfig;

    private static HashMap<String,JSONObject> jsonTemplateCache = new HashMap<String,JSONObject>();

    private static ServletContext appCtx;

    private static byte[] masterAESKey;

    public static void loadProcessorConfig(ServletContext ctx) {
       if (processorConfig == null) {
            processorConfig = new Properties();
            try {
                processorConfig.load(ctx.getResourceAsStream("/WEB-INF/_processor.tsi"));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            System.out.println("Loaded _processor.tsi");
        }
    }

   public static void loadAppConfig(ServletContext ctx) {
    appCtx = ctx;
    if (appConfig == null) {
        appConfig = new Properties();
    }

    appConfig.setProperty("framework.db.name", "tsi_ratings");
    appConfig.setProperty("framework.db.user", "postgres");
    appConfig.setProperty("framework.db.password", "Harsh@2005");
    appConfig.setProperty("framework.db.host", "localhost");
    appConfig.setProperty("framework.db.port", "5432");
}

    public static Properties getAppConfig() {
        return appConfig;
    }
    public static Properties getSchema() { return schemaConfig;}

    public static Properties getProcessorConfig(){
        return processorConfig;
    }

    private static ServletContext getAppCtx(){
        return appCtx;
    }

    public static JSONObject readJSONTemplate(String filePath) {
        JSONParser parser = new JSONParser();
        JSONObject jsonObject = new JSONObject();
        StringBuffer buff = new StringBuffer();
        if(jsonTemplateCache.get(filePath) != null){
            jsonObject =  (JSONObject) jsonTemplateCache.get(filePath);
        }
        else {
            try {
                InputStream inputStream = SystemConfig.getAppCtx().getResourceAsStream(filePath);
                if (inputStream != null) {
                    try (InputStreamReader isReader = new InputStreamReader(inputStream);
                         BufferedReader reader = new BufferedReader(isReader)) { // BufferedReader for efficient line reading
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buff.append(line);
                        }
                    } catch (Exception e) {
                        System.err.println("Error reading stream: " + e.getMessage());
                    }
                }

                jsonObject = (JSONObject) parser.parse(buff.toString());
                jsonTemplateCache.put(filePath,jsonObject);
            } catch (Exception e) {
                System.err.println("Error reading JSON file from path " + filePath + ": " + e.getMessage());
            }
        }
        return jsonObject;
    }
}
