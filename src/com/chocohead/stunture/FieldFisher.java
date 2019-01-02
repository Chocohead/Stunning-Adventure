package com.chocohead.stunture;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * Light weight version of {@link ClassNode} which only visits {@code FieldNode}s
 * 
 * @author Chocohead
 */
class FieldFisher extends ClassVisitor {
	public final List<FieldNode> fields = new ArrayList<>();

	public FieldFisher() {
		super(Opcodes.ASM7);
	}

	@Override
	public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
		FieldNode field = new FieldNode(access, name, descriptor, signature, value);
	    fields.add(field);
	    return field;
	}
}