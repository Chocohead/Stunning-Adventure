package com.chocohead.stunture;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * A version of {@link Supplier} which can throw an {@link IOException}, designed for use with {@link MappingsLoader}
 * 
 * @param <T> The return type of the supplied object
 *  
 * @author Chocohead
 */
@FunctionalInterface
public interface MappingSupplier<T> {
	T get() throws IOException;
}