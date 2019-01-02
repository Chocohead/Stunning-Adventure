package com.chocohead.stunture;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;

import com.chocohead.rift.ClassMapping;
import com.chocohead.rift.MappingBlob;

/**
 * Variety of methods for producing Notch to {@link ClassMapping}s maps
 * 
 * @author Chocohead
 */
public class MappingsLoader {
	private static class Party {
		public final Set<ClassMapping> parents = new HashSet<>();
		public final Set<ClassMapping> children = new HashSet<>();
		public final ClassMapping mapping;

		public Party(ClassMapping mapping) {
			this.mapping = mapping;
		}

		public boolean isRoot() {
			return parents.isEmpty();
		}
	}

	/**
	 * Create mappings for the given Minecraft version and MCP mappings then save to the given path
	 * 
	 * @param mcVersion The Minecraft version to use Notch names from
	 * @param mcpVersion The MCP mappings to get MCP names from
	 * @param to The file location to save the produced mapping blob
	 * 
	 * @return The saved {@link MappingBlob}
	 */
	public static MappingBlob saveDefault(String mcVersion, String mcpVersion, Path to) {
		Map<String, ClassMapping> mappings = loadDefault(mcVersion, mcpVersion);

		MappingBlob blob = new MappingBlob(mappings);
		blob.write(to.toFile());
		return blob;
	}

	/**
	 * Create mappings for the given Minecraft version and MCP mappings
	 * 
	 * @param mcVersion The Minecraft version to use Notch names from
	 * @param mcpVersion The MCP mappings to get MCP names from
	 * 
	 * @return The produced mappings, from Notch name to {@link ClassMapping}
	 */
	public static Map<String, ClassMapping> loadDefault(String mcVersion, String mcpVersion) {
		File gradleCache = new File(System.getProperty("user.home") + "/.gradle/caches/minecraft");
		if (!gradleCache.isDirectory()) {
			throw new RuntimeException("Unable to find Minecraft Gradle cache (have you run setupDecompWorkspace?)");
		}

		File minecraft = new File(gradleCache, "net/minecraft/minecraft/"+mcVersion+"/minecraft-"+mcVersion+".jar");
		if (!minecraft.exists()) {
			throw new RuntimeException("Unable to find Vanilla jar for " + mcVersion);
		}

		File mcp = new File(gradleCache, "de/oceanlabs/mcp");
		File srg = new File(mcp, "mcp/"+mcVersion+"/config/joined.srg");
		if (!srg.exists()) {
			throw new RuntimeException("Unable to find SRG mapping for " + mcVersion);
		}

		File constructors = new File(mcp, "mcp/"+mcVersion+"/config/constructors.txt");
		if (!constructors.exists()) {
			throw new RuntimeException("Unable to find constructors for " + mcVersion);
		}

		mcp = new File(mcp, "mcp_snapshot/"+mcpVersion);
		File methods = new File(mcp, "methods.csv");
		if (!methods.exists()) {
			throw new RuntimeException("Unable to find MCP methods for " + mcpVersion);
		}
		File fields = new File(mcp, "fields.csv");
		if (!fields.exists()) {
			throw new RuntimeException("Unable to find MCP fields for " + mcpVersion);
		}

		try {
			return load(() -> new FileReader(srg), () -> new FileReader(constructors), () -> new FileReader(methods), () -> new FileReader(fields), () -> new JarFile(minecraft));
		} catch (ExecutionException e) {
			throw new RuntimeException("Unexpected error loading mappings!", e);
		} catch (InterruptedException e) {
			throw new IllegalStateException("Interrupted whilst loaded mappings?", e);
		}
	}

	/**
	 * Create mappings for the given SRG and MCP names, and the given (obfuscated) Minecraft jar
	 * 
	 * @param srgs A supplier of a {@link Reader} for a <code>joined.srg</code> file
	 * @param constructors A supplier of a {@link Reader} for a <code>constructors.txt</code> file
	 * @param methodFile A supplier of a {@link Reader} for an MCP <code>methods.csv</code> file
	 * @param fieldFile A supplier of a {@link Reader} for an MCP <code>fields.csv</code> file
	 * @param minecraft A supplier of a {@link JarFile} for an obfuscated Minecraft jar
	 * 
	 * @return The produced mappings, from Notch name to {@link ClassMapping}
	 * 
	 * @throws ExecutionException If an unexpected error occurs whilst computing the mappings
	 * @throws InterruptedException If an interrupt is raised during the computation of mappings 
	 */
	public static Map<String, ClassMapping> load(MappingSupplier<Reader> srgs, MappingSupplier<Reader> constructors, MappingSupplier<Reader> methodFile, MappingSupplier<Reader> fieldFile, MappingSupplier<JarFile> minecraft) throws ExecutionException, InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(3);

		Future<Map<String, ClassMapping>> srgTask = executor.submit(() -> {
			try (BufferedReader contents = new BufferedReader(srgs.get())) {
				Map<String, ClassMapping> classes = new HashMap<>();

				for (String line = contents.readLine(); line != null; line = contents.readLine()) {
					if (line.isEmpty() || line.startsWith("#")) continue;

					String type = line.substring(0, 2);
					String[] args = line.substring(4).split(" ");

					switch (type) {
					case "PK":
						break; //We don't care about packages

					case "CL":
						classes.put(args[0], new ClassMapping(args[0], args[1]));
						break;

					case "FD": {
						String obf = args[0];
						String srg = args[1];

						int end = obf.lastIndexOf('/');
						String owner = obf.substring(0, end++);
						String field = obf.substring(end);

						if (classes.get(owner).fields.put(srg.substring(srg.lastIndexOf('/') + 1), field) != null) {
							throw new IllegalStateException("Duplicate field mappings for " + field + " in " + classes.get(owner).mcpName);
						}
						break;
					}

					case "MD": {
						String obf = args[0];
						String srg = args[2];

						int end = obf.lastIndexOf('/');
						String owner = obf.substring(0, end++);
						String method = obf.substring(end);

						String srged = srg.substring(srg.lastIndexOf('/') + 1);
						if (method.equals(srged)) continue; //Skip methods which don't change name

						String old = classes.get(owner).methods.put(method + args[1], srged);
						if (old != null) {
							throw new IllegalStateException("Duplicate field mappings for " + method + args[1] + " in " + classes.get(owner).mcpName + ": " + old + " => " + srged);
						}
						break;
					}

					default:
						throw new RuntimeException("Unexpected line type: " + type + " from " + line);
					}
				}

				return classes;
			} catch (IOException e) {
				throw new RuntimeException("Error processing SRG mappings", e);
			}
		});
		Future<Map<String, String>> methodTask = executor.submit(() -> mcpTask(methodFile));
		Future<Map<String, String>> fieldTask = executor.submit(() -> mcpTask(fieldFile));

		JarFile jar;
		try {
			jar = minecraft.get();
		} catch (IOException e) {
			throw new RuntimeException("Error getting vanilla jar", e);
		}

		Map<String, ClassMapping> srg = srgTask.get();
		Future<?> constructorTask = executor.submit(() -> {
			try (BufferedReader contents = new BufferedReader(srgs.get())) {
				Map<String, ClassMapping> backwardsSrg = srg.entrySet().parallelStream().collect(Collectors.toMap(mapping -> mapping.getValue().mcpName, Entry::getValue));
				Pattern classFinder = Pattern.compile("L([^;]+);");

				for (String line = contents.readLine(); line != null; line = contents.readLine()) {
					if (line.startsWith("#")) continue;
	                String[] parts = line.split(" ");

	                if (parts.length != 3)
	                	throw new IllegalStateException("Unexpected constructor line length: " + Arrays.toString(parts) + " from " + line);

	                ClassMapping mapping = backwardsSrg.get(parts[1]);
	                if (mapping == null) {
	                	//System.err.println("Unable to find " + parts[1] + " for constructor");
	                	continue; //Anonymous classes will often not have mappings
	                }

					String desc = parts[2];
					StringBuffer buf = new StringBuffer("<init> ");

			        Matcher matcher = classFinder.matcher(desc);
			        while (matcher.find()) {
			        	ClassMapping type = backwardsSrg.get(matcher.group(1)); //This will miss for non-Notch types
			            matcher.appendReplacement(buf, Matcher.quoteReplacement('L' + (type == null ? matcher.group(1) : type.notchName) + ';'));
			        }
			        matcher.appendTail(buf);

			        mapping.constructors.add(buf.toString());
				}
			} catch (IOException e) {
				throw new RuntimeException("Error processing constructors", e);
			}
		});
		Future<?> fieldMushing = executor.submit(new Callable<Void>() {
			public Void call() throws Exception {
				Map<String, String> fields = fieldTask.get();

				for (ClassMapping mapping : srg.values()) {
					if (!mapping.fields.isEmpty()) {
						Map<String, String> descs = getFieldDescs(mapping);
						Map<String, String> mushed = new HashMap<>();

						for (Entry<String, String> entry : mapping.fields.entrySet()) {
							String srg = entry.getKey();

							String mcp = fields.getOrDefault(srg, srg);
							String notch = entry.getValue();

							String desc = descs.get(notch);
							if (desc == null) throw new IllegalStateException("Unable to find description for " + mapping.notchName + '/' + notch + " (" + mapping.mcpName + '/' + mcp + ')');
							mushed.put(notch + ";;" + desc, mcp);
						}

						mapping.fields.clear();
						mapping.fields.putAll(mushed);
					}
				}

				return null;
			}

			private Map<String, String> getFieldDescs(ClassMapping mapping) {
				try {
					FieldFisher fisher = new FieldFisher();

					byte[] bytes = getVanillaClass(jar, mapping.notchName);
					if (bytes == null) throw new IllegalStateException("Unable to find vanilla class: " + mapping.notchName + " (" + mapping.mcpName + ')');
					new ClassReader(bytes).accept(fisher, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

					return fisher.fields.stream().collect(Collectors.toMap(f -> f.name, f -> f.desc));
				} catch (IOException e) {
					throw new UncheckedIOException("Error getting vanilla class: " + mapping.notchName + " (" + mapping.mcpName + ')', e);
				}
			}
		});
		Future<?> methodMushing = executor.submit(() -> {
			Map<String, String> methods = methodTask.get();

			for (ClassMapping mapping : srg.values()) {
				if (!mapping.methods.isEmpty()) {
					Map<String, String> mushed = new HashMap<>();

					for (Entry<String, String> entry : mapping.methods.entrySet()) {
						String srgX = entry.getValue();

						String mcp = methods.getOrDefault(srgX, srgX);
						String notch = entry.getKey();

						mushed.put(notch, mcp);
					}

					mapping.methods.clear();
					mapping.methods.putAll(mushed);
				}
			}

			return null;
		});

		executor.shutdown();
		constructorTask.get();
		methodMushing.get();
		fieldMushing.get();

		Map<String, Party> watchPool = new HashMap<>();
		for (ClassMapping mapping : srg.values()) {
			try {
				InheritanceFisher fisher = new InheritanceFisher();

				byte[] bytes = getVanillaClass(jar, mapping.notchName);
				if (bytes == null) throw new IllegalStateException("Unable to find vanilla class: " + mapping.notchName + " (" + mapping.mcpName + ')');
				new ClassReader(bytes).accept(fisher, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

				String superType = fisher.superName.replace('.', '/');
				if (srg.containsKey(superType)) {
					ClassMapping superMap = srg.get(superType);
					watchPool.computeIfAbsent(superType, k -> new Party(superMap)).children.add(mapping);
					watchPool.computeIfAbsent(mapping.notchName, k -> new Party(mapping)).parents.add(superMap);
				}

				for (String rawInterface : fisher.interfaces) {
					String interfaceType = rawInterface.replace('.', '/');
					if (srg.containsKey(interfaceType)) {
						ClassMapping interfaceMap = srg.get(interfaceType);
						watchPool.computeIfAbsent(interfaceType, k -> new Party(interfaceMap)).children.add(mapping);
						watchPool.computeIfAbsent(mapping.notchName, k -> new Party(mapping)).parents.add(interfaceMap);
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Error getting vanilla class: " + mapping.notchName + " (" + mapping.mcpName + ')', e);
			}
		}

		Queue<Party> rootParties = watchPool.values().parallelStream().filter(Party::isRoot).collect(Collectors.toCollection(ArrayDeque::new));
		Party party;
		while ((party = rootParties.poll()) != null) {
			Collection<Entry<String, String>> methods = party.mapping.methods.entrySet();
			Collection<Entry<String, String>> fields = party.mapping.fields.entrySet();

			for (ClassMapping child : party.children) {
				for (Entry<String, String> entry : methods) {
					child.methods.putIfAbsent(entry.getKey(), entry.getValue());
				}
				for (Entry<String, String> entry : fields) {
					child.fields.putIfAbsent(entry.getKey(), entry.getValue());
				}
				rootParties.add(watchPool.get(child.notchName));
			}
		}

		return srg;
	}

	private static Map<String, String> mcpTask(MappingSupplier<Reader> target) {
		try (BufferedReader contents = new BufferedReader(target.get())) {
			Map<String, String> mappings = new HashMap<>();
			contents.readLine(); //Skip the header line
	
			for (String line = contents.readLine(); line != null; line = contents.readLine()) {
				int first = line.indexOf(',');
	
				String srg = line.substring(0, first++);
				String mcp = line.substring(first, line.indexOf(',', first));
	
				mappings.put(srg, mcp);
			}

			return mappings;
		} catch (IOException e) {
			throw new RuntimeException("Error processing MCP mappings", e);
		}
	}

	static byte[] getVanillaClass(JarFile jar, String name) throws IOException {
		JarEntry entry = jar.getJarEntry(name.replace('.', '/').concat(".class"));
		if (entry == null) return null;

		try (InputStream in = jar.getInputStream(entry)) {			
            byte[] buffer = new byte[4096];

            int read, totalLength = 0;
            while ((read = in.read(buffer, totalLength, buffer.length - totalLength)) != -1) {
                totalLength += read;

                if (totalLength >= buffer.length - 1) {
                    byte[] newBuffer = new byte[buffer.length + 4096];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    buffer = newBuffer;
                }
            }

            byte[] result = new byte[totalLength];
            System.arraycopy(buffer, 0, result, 0, totalLength);
            return result;
		}
	}
}