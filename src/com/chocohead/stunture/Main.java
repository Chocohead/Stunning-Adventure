package com.chocohead.stunture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import com.chocohead.rift.ClassMapping;
import com.chocohead.rift.MappingBlob;

public class Main {
	public static void main(String... args) {
		if (args == null || args.length < 3) {
			System.out.println("Usage: <input> <output> <mappings> [<classpath>...]");
			System.exit(1);
		}

		Path input = Paths.get(args[0]);
		if (!Files.isReadable(input)) {
			System.out.println("Can't read input file " + input + '.');
			System.exit(2);
		}

		Path output = Paths.get(args[1]);
		if (Files.exists(output)) {
			System.out.println("Output file already exists at " + output + '.');
			System.exit(3);
		}

		Path mappings = Paths.get(args[2]);
		if (!Files.isReadable(mappings) || Files.isDirectory(mappings)) {
			System.out.println("Invalid mappings file " + mappings + '.');
			System.exit(4);
		}

		Path[] classpath = Arrays.stream(args).skip(3).map(Paths::get).toArray(Path[]::new);
		//Filter for all the non-readable paths which indicate missing files from the classpath
		List<Path> missingClasspath = Arrays.stream(classpath).filter(((Predicate<Path>) Files::isReadable).negate()).collect(Collectors.toList());
		if (!missingClasspath.isEmpty()) {
			System.out.println("Missing files from classpath:");
			missingClasspath.forEach(System.out::println);
			System.exit(5);
		}
		
		//Read in the provided mappings blob
		MappingBlob blob = MappingBlob.read(mappings.toFile());

		TinyRemapper remapper = TinyRemapper.newRemapper().withMappings((classMap, fieldMap, methodMap) -> {
			for (ClassMapping mapping : blob.mappings.values()) {
				classMap.put(mapping.notchName, mapping.mcpName);

				for (Entry<String, String> entry : mapping.methods.entrySet()) {
					methodMap.put(mapping.notchName + '/' + entry.getKey(), mapping.mcpName + '/' + entry.getValue());
				}

				for (Entry<String, String> entry : mapping.fields.entrySet()) {
					fieldMap.put(mapping.notchName + '/' + entry.getKey(), mapping.mcpName + '/' + entry.getValue());
				}
			}
		}).build();

		try (OutputConsumerPath out = new OutputConsumerPath(output)) {
			out.addNonClassFiles(input);

			remapper.read(classpath);
			remapper.read(input);

			remapper.apply(input, out);
		} catch (IOException e) {
			throw new UncheckedIOException("Error remapping jar!", e);
		} finally {
			remapper.finish();
		}
	}
}