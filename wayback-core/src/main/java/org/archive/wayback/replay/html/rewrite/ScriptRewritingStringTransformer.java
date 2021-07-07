package org.archive.wayback.replay.html.rewrite;

import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.lang.StringEscapeUtils;
import org.archive.wayback.replay.html.ReplayParseContext;

public class ScriptRewritingStringTransformer extends
        RewritingStringTransformer {
    
    private ScriptEngine scriptEngine = null;
    private static final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    
    private static final Logger LOGGER = Logger.getLogger(ScriptRewritingStringTransformer.class.getName());

    private String scriptEngineType = null;    
    
    protected void initScriptEngine() {
        if (scriptEngine == null) {
            scriptEngine = scriptEngineManager.getEngineByName(scriptEngineType);
            
            if (scriptEngine == null) {
                LOGGER.log(Level.SEVERE, "Could not create a scriptEngine of type: " + scriptEngineType + "! Rewriting will be disabled for this rewriter!");
            }
        }
    }
    
    public String getScriptEngineType() {
        return scriptEngineType;
    }

    public void setScriptEngineType(String scriptEngineType) {
        this.scriptEngineType = scriptEngineType;
    }
    
    
    @Override
    public String transform(ReplayParseContext rpContext, String input) {
        
        initScriptEngine();
        
        if (scriptEngine == null) {
            return input;
        }
        
        if (!rpContext.isInScriptText() && !rpContext.isInJS()) {
            return input;
        }

        String policy = rpContext.getOraclePolicy();

        if (policy == null) {
            return input;
        }

        if (policy.startsWith(scriptEngineType.toLowerCase() + ":")) {
            String script = parseScriptFromPolicy(policy);
            
            if (script == null) {
                LOGGER.log(Level.SEVERE, "Could not find script code in policy: " + policy + "! Script engine is type: " + scriptEngineType);
            }
            
            try {
                scriptEngine.eval("var content='" + StringEscapeUtils.escapeJavaScript(input) + "'; ");
                scriptEngine.eval(script);
                input = (String)scriptEngine.get("content");
            }
            catch (ScriptException se) {
                LOGGER.log(Level.SEVERE, "Error executing script: " + script.toString() + "! Script engine is type: " + scriptEngineType, se);
            }
        }

        return input;
    }
    
    protected String parseScriptFromPolicy(String policy) {
        
        if (policy != null) {
            if (policy.startsWith(scriptEngineType.toLowerCase() + ":")) {
                return policy.substring(policy.indexOf(":") + 1);
            }
        }

        return null;
    }
}
