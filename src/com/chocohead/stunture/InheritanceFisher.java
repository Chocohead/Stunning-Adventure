package com.chocohead.stunture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Light weight version of {@link ClassNode} which only visits superclass and interfaces
 * 
 * @author Chocohead
 */
class InheritanceFisher extends ClassVisitor {
	public String superName;
	public final List<String> interfaces = new ArrayList<>();

	public InheritanceFisher() {
		super(Opcodes.ASM7);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		this.superName = superName;
		Collections.addAll(this.interfaces, interfaces);
	}
}