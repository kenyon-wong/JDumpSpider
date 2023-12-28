/*
 * Copyright (c) 2010, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.visualvm.lib.profiler.oql.engine.api.impl;

import org.graalvm.visualvm.lib.jfluid.heap.GraalvmHeapHolder;
import org.graalvm.visualvm.lib.jfluid.heap.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author      Jaroslav Bachorik
 */
/**
 * A helper class for OQL engine allowing easy access to the underlying
 * heapwalker model
 */
public class Snapshot {

    private static final String BOOLEAN_CODE = "Z"; // NOI18N
    private static final String CHAR_CODE = "C"; // NOI18N
    private static final String BYTE_CODE = "B"; // NOI18N
    private static final String SHORT_CODE = "S"; // NOI18N
    private static final String INT_CODE = "I"; // NOI18N
    private static final String LONG_CODE = "J"; // NOI18N
    private static final String FLOAT_CODE = "F"; // NOI18N
    private static final String DOUBLE_CODE = "D"; // NOI18N
    private static final String VOID_CODE = "V"; // NOI18N
    private static final char REFERENCE = 'L'; // NOI18N

    private final Heap delegate;
    private JavaClass weakReferenceClass;
    private int referentFieldIndex;
    private ReachableExcludes reachableExcludes;
    final private GraalvmHeapHolder engine;

    public Snapshot(Heap heap, GraalvmHeapHolder engine) {
        this.delegate = heap;
        this.engine = engine;
        init();
    }

    private void init() {
        weakReferenceClass = findClass("java.lang.ref.Reference"); // NOI18N
        if (weakReferenceClass == null) {	// JDK 1.1.x
            weakReferenceClass = findClass("sun.misc.Ref"); // NOI18N
            referentFieldIndex = 0;
        } else {
            List<Field> flds = weakReferenceClass.getFields();
            int fldsCount = flds.size();

            for (int i = 0; i < fldsCount; i++) {
                if ("referent".equals(flds.get(i).getName())) { // NOI18N
                    referentFieldIndex = i;
                    break;
                }
            }
        }
    }

    public JavaClass findClass(String name) {
        try {
            long classId;
            if (name.startsWith("0x")) {
                classId = Long.parseLong(name.substring(2), 16);
            } else {
                classId = Long.parseLong(name);
            }
            return delegate.getJavaClassByID(classId);
        } catch (NumberFormatException e) {}
        return delegate.getJavaClassByName(preprocessClassName(name));
    }

    private String preprocessClassName(String className) {
        int arrDim = 0;
        if (className.startsWith("[")) { // NOI18N
            arrDim = className.lastIndexOf('[') + 1; // NOI18N

            className = className.substring(arrDim);
        }
        if (className.length() == 1) {
            if (className.equals(INT_CODE)) {
                className = int.class.toString();
            } else if (className.equals(LONG_CODE)) {
                className = long.class.toString();
            } else if (className.equals(DOUBLE_CODE)) {
                className = double.class.toString();
            } else if (className.equals(FLOAT_CODE)) {
                className = float.class.toString();
            } else if (className.equals(BYTE_CODE)) {
                className = byte.class.toString();
            } else if (className.equals(SHORT_CODE)) {
                className = short.class.toString();
            } else if (className.equals(CHAR_CODE)) {
                className = char.class.toString();
            } else if (className.equals(BOOLEAN_CODE)) {
                className = boolean.class.toString();
            }
        }
        if (arrDim > 0 && className.charAt(0) == REFERENCE) {   // class name
            className = className.substring(1);
        }
        StringBuilder sb = new StringBuilder(className);
        for (int i = 0; i < arrDim; i++) {
            sb.append("[]"); // NOI18N
        }

        return sb.toString();
    }

    public Instance findThing(long objectId) {
        return delegate.getInstanceByID(objectId);
    }

    public GCRoot findRoot(Instance object) {
        Instance gcInstance = object;
        do {
            gcInstance = gcInstance.getNearestGCRootPointer();
        } while (!gcInstance.isGCRoot());
        if (gcInstance != null) {
            Collection<GCRoot> roots = delegate.getGCRoots(gcInstance);
            if (!roots.isEmpty()) {
                // TODO getGCRoot() now returns Collection
                return roots.iterator().next();
            }
        }
        return null;
    }

    public int distanceToGCRoot(Instance object) {
        Instance gcInstance = object;
        int distance = 0;
        do {
            gcInstance = gcInstance.getNearestGCRootPointer();
            if (gcInstance == null) {
                return 0;
            }
            distance++;
        } while (!gcInstance.isGCRoot());
        return distance;
    }

    /**
     * Return an Iterator of all of the classes in this snapshot.
     **/
    public Iterator getClasses() {
        return delegate.getAllClasses().iterator();
    }

    public Iterator getClassNames(String regex)  {
        final Iterator delegated = delegate.getJavaClassesByRegExp(regex).iterator();
        return new Iterator() {

            public boolean hasNext() {
                return delegated.hasNext();
            }

            public Object next() {
                return ((JavaClass)delegated.next()).getName();
            }

            public void remove() {
                delegated.remove();
            }
        };

    }

    public Iterator getInstances(final JavaClass clazz, final boolean includeSubclasses) {
        // special case for all subclasses of java.lang.Object
        if (includeSubclasses && clazz.getSuperClass() == null) {
            return delegate.getAllInstancesIterator();
        }
        return new TreeIterator<Instance, JavaClass>(clazz) {

            
            protected Iterator<Instance> getSameLevelIterator(JavaClass popped) {
                return popped.getInstancesIterator();
            }

            
            protected Iterator<JavaClass> getTraversingIterator(JavaClass popped) {
                return includeSubclasses ? popped.getSubClasses().iterator() : Collections.EMPTY_LIST.iterator();
            }
        };
    }

    public Iterator getReferrers(Object obj, boolean includeWeak) {
        List instances = new ArrayList();
        List references = new ArrayList();

        if (obj instanceof Instance) {
            references.addAll(((Instance)obj).getReferences());
        } else if (obj instanceof JavaClass) {
            references.addAll(((JavaClass)obj).getInstances());
            references.add(((JavaClass)obj).getClassLoader());
        }
        if (!references.isEmpty()) {
            for (Object o : references) {
                if (o instanceof Value) {
                    Value val = (Value) o;
                    Instance inst = val.getDefiningInstance();
                    if (includeWeak || !isWeakRef(inst)) {
                        instances.add(inst);
                    }
                } else if (o instanceof Instance) {
                    if (includeWeak || !isWeakRef((Instance)o)) {
                        instances.add(o);
                    }
                }
            }
        }
        return instances.iterator();
    }

    public Iterator getReferees(Object obj, boolean includeWeak) {
        List instances = new ArrayList();
        List values = new ArrayList();

        if (obj instanceof Instance) {
            Instance o = (Instance)obj;
            values.addAll(o.getFieldValues());
        }
        if (obj instanceof JavaClass) {
            values.addAll(((JavaClass)obj).getStaticFieldValues());
        }
        if (obj instanceof ObjectArrayInstance) {
            ObjectArrayInstance oarr = (ObjectArrayInstance)obj;
            values.addAll(oarr.getValues());
        }
        if (!values.isEmpty()) {
            for (Object value : values) {
                if (value instanceof ObjectFieldValue && ((ObjectFieldValue) value).getInstance() != null) {
                    Instance inst = ((ObjectFieldValue) value).getInstance();
                    if (includeWeak || !isWeakRef(inst)) {
                        if (inst.getJavaClass().getName().equals("java.lang.Class")) {
                            JavaClass jc = delegate.getJavaClassByID(inst.getInstanceId());
                            if (jc != null) {
                                instances.add(jc);
                            } else {
                                instances.add(inst);
                            }
                        } else {
                            instances.add(inst);
                        }
                    }
                } else if (value instanceof Instance) {
                    if (includeWeak || !isWeakRef((Instance)value)) {
                        instances.add(value);
                    }
                }
            }
        }
        return instances.iterator();
    }

    public Iterator getFinalizerObjects() {
        JavaClass clazz = findClass("java.lang.ref.Finalizer"); // NOI18N
        Instance queue = (Instance) clazz.getValueOfStaticField("queue"); // NOI18N
        Instance head = (Instance) queue.getValueOfField("head"); // NOI18N

        List finalizables = new ArrayList();
        if (head != null) {
            while (true) {
                Instance referent = (Instance) head.getValueOfField("referent"); // NOI18N
                Instance next = (Instance) head.getValueOfField("next"); // NOI18N

                finalizables.add(referent);
                if (next == null || next.equals(head)) {
                    break;
                }
                head = next;
            }
        }
        return finalizables.iterator();
    }

    public Iterator getRoots() {
        return delegate.getGCRoots().iterator();
    }

    private Set getRootsInstances() {
        Set<Object> roots = new HashSet<Object>();
        for(GCRoot root : delegate.getGCRoots()) {
            Instance inst = root.getInstance();
            if (inst.getJavaClass().getName().equals("java.lang.Class")) {
                JavaClass jc = delegate.getJavaClassByID(inst.getInstanceId());
                if (jc != null) {
                    roots.add(jc);
                } else {
                    roots.add(inst);
                }
            } else {
                roots.add(inst);
            }
        }
        return roots;
    }

    public GCRoot[] getRootsArray() {
        Collection<GCRoot> rootList = delegate.getGCRoots();
        return rootList.toArray(new GCRoot[0]);
    }

    private boolean isAssignable(JavaClass from, JavaClass to) {
        if (from == to) {
            return true;
        } else if (from == null) {
            return false;
        } else {
            return isAssignable(from.getSuperClass(), to);
            // Trivial tail recursion:  I have faith in javac.
        }
    }

    private boolean isWeakRef(Instance inst) {
        return weakReferenceClass != null && isAssignable(inst.getJavaClass(), weakReferenceClass);
    }

    public JavaClass getWeakReferenceClass() {
        return weakReferenceClass;
    }

    public int getReferentFieldIndex() {
        return referentFieldIndex;
    }

    public void setReachableExcludes(ReachableExcludes e) {
        reachableExcludes = e;
    }

    public ReachableExcludes getReachableExcludes() {
        return reachableExcludes;
    }

    public String valueString(Instance instance) {
        if (instance == null) return null;
        try {
            if (instance.getJavaClass().getName().equals(String.class.getName())) {
                Class proxy = Class.forName("org.graalvm.visualvm.lib.jfluid.heap.HprofProxy"); // NOI18N
                Method method = proxy.getDeclaredMethod("getString", Instance.class); // NOI18N
                method.setAccessible(true);
                return (String) method.invoke(proxy, instance);
            } else if (instance.getJavaClass().getName().equals("char[]")) { // NOI18N
                Method method = instance.getClass().getDeclaredMethod("getChars", int.class, int.class);
                method.setAccessible(true);
                char[] chars = (char[])method.invoke(instance, 0, ((PrimitiveArrayInstance)instance).getLength());
                if (chars != null) {
                    return new String(chars);
                } else {
                    return "*null*"; // NOI18N
                }
            }
        } catch (Exception ex) {
            Logger.getLogger(Snapshot.class.getName()).log(Level.WARNING, "Error getting toString() value of an instance dump", ex); // NO18N
        }
        return instance.toString();
    }
}
