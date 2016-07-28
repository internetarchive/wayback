package org.archive.wayback.replay.html.rewrite;

import java.util.List;
import java.util.StringTokenizer;

import org.archive.wayback.archivalurl.FastArchivalUrlReplayParseEventHandler;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.replay.html.StringTransformer;

/**
 * {@link StringTransformer} that manages a collection of named
 * {@link RewriteRule}s, and applies one or more of them whose name matching
 * {@link ReplayParseContext#getOraclePolicy()} value.
 * <p>
 * Typically set up for
 * {@link FastArchivalUrlReplayParseEventHandler#setJsBlockTrans(StringTransformer)}
 * for rewriting embedded JavaScript based on external rewrite rule database.
 * </p>
 * <p>Caveat: While this may be useful for rewriting other kinds of text, current
 * implementation works only for embedded JavaScript block (it has explicit context
 * check.)
 * </p>
 */
public class RewritingStringTransformer implements StringTransformer {

	private List<RewriteRule> policyRules;

	public List<RewriteRule> getPolicyRules() {
		return policyRules;
	}

	/**
	 * Configure a collection of pre-defined {@link RewriteRule}s.
	 * @param policyRules
	 */
	public void setPolicyRules(List<RewriteRule> policyRules) {
		this.policyRules = policyRules;
	}

	public String transform(ReplayParseContext rpContext, String input) {

		if (policyRules == null) {
			return input;
		}

                if (input.startsWith("javascript:")) {
                        rpContext.setInScriptText(true);
                }

		if (!rpContext.isInScriptText() && !rpContext.isInJS()) {
			return input;
		}

		String allPolicies = rpContext.getOraclePolicy();

		if (allPolicies == null) {
			return input;
		}

		StringTokenizer tokens = new StringTokenizer(allPolicies, ",");

		while (tokens.hasMoreElements()) {
			String policy = tokens.nextToken();

			for (RewriteRule rule : policyRules) {
				if (policy.startsWith(rule.getName())) {
					input = rule.rewrite(rpContext, policy, input);
				}
			}
		}

		return input;
	}
}
