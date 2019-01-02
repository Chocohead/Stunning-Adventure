package com.chocohead.rift;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The known mappings for a given class
 * 
 * @author Chocohead
 */
public class ClassMapping implements Serializable {
	private static final long serialVersionUID = -1724841536245456196L;

	/** All known constructors mapped as (<code>{@literal <}init{@literal >} (Notch named parameter signatures)V</code> */
	public final Set<String> constructors = new HashSet<>(); //Designed for Access Transformer use
	/** Notch names to MCP names (or SRG if unmapped) */
	public final Map<String, String> methods = new HashMap<>();
	/** Notch names to MCP names (or SRG if unmapped) */
	public final Map<String, String> fields = new HashMap<>();
	public final String notchName, mcpName;

	public ClassMapping(String notchName, String mcpName) {
		this.notchName = notchName;
		this.mcpName = mcpName;
	}
}