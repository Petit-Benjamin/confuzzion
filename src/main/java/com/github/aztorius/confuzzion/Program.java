package com.github.aztorius.confuzzion;

import soot.Body;
import soot.SootClass;
import soot.SootMethod;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Program is a collection of classes with at least one "main" class.
 * Each class is represented as a Mutant that can evolve.
 * The evolution is represented as a Mutation and apply only to one of the
 * Mutant.
 */
public class Program {
    private String classBaseName;
    private ArrayList<Mutant> mutants;
    private HashSet<SootMethod> executedMethods;
    private RandomGenerator rand;

    private static long MUTANTS_NUMBER_LIMIT = 10;
    private static final Logger logger = LoggerFactory.getLogger(Program.class);

    class Handler implements UncaughtExceptionHandler {
        private Throwable e;

        public Handler() {
            e = null;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            e = throwable;
        }

        public void throwException() throws Throwable {
            if (e != null) {
                throw e;
            }
        }
    }

    class Launcher implements Runnable {
        private ByteClassLoader loader;
        private byte[] bytecode;
        private String className;

        public Launcher(ByteClassLoader loader, byte[] bytecode, String className) {
            this.loader = loader;
            this.bytecode = bytecode;
            this.className = className;
        }

        @Override
        public void run() {
            try {
                Class<?> clazz = loader.load(className, bytecode);
                clazz.newInstance();
                //TODO: Method[] methods = clazz.getMethods();
                //TODO: invoke methods ?: method.invoke(clazz.newInstance());
            } catch(Throwable e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Program constructor
     * @param classBaseName the base name of all classes inside this program
     * @param rand          the RandomGenerator that will be used
     */
    public Program(String classBaseName, RandomGenerator rand) {
        // Initialize class fields
        this.classBaseName = classBaseName;
        this.rand = rand;
        mutants = new ArrayList<Mutant>();
        executedMethods = new HashSet<SootMethod>();

        // Create first empty Mutant (main class)
        MutantGenerator generator = new MutantGenerator(rand, classBaseName + "0");
        Mutant firstMutant = generator.genEmptyClass("java.lang.Object");
        mutants.add(firstMutant);
        rand.addStrMutant(firstMutant.getClassName());
    }

    public Mutant genNewClass() {
        MutantGenerator generator = new MutantGenerator(rand, classBaseName + mutants.size());
        String superClass = rand.randClassName(classBaseName + mutants.size());
        Mutant addedMutant = generator.genEmptyClass(superClass);
        this.mutants.add(addedMutant);
        this.rand.addStrMutant(addedMutant.getClassName());
        return addedMutant;
    }

    public void removeClass(Mutant mutant) {
        for (SootMethod method : mutant.getSootClass().getMethods()) {
            executedMethods.remove(method);
        }
        mutants.remove(mutant);
        rand.removeStrMutant(mutant.getClassName());
    }

    public ArrayList<BodyMutation> addContractsChecks(
            ArrayList<Contract> contracts,
            Mutation mutation) {
        Body body = null;
        ArrayList<BodyMutation> mutations =
            new ArrayList<BodyMutation>(contracts.size());

        if (mutation instanceof MethodMutation) {
            // Get the body and put checks at the end
            MethodMutation mMutation = (MethodMutation)mutation;
            body = mMutation.getBody();
        } else if (mutation instanceof ClassMutation) {
            // TODO: put checks inside constructors
            return mutations; //TODO: remove
        } else if (mutation instanceof ProgramMutation) {
            return mutations;
        } else {
            throw new IllegalArgumentException("mutation is unknown");
        }

        for (Contract contract : contracts) {
            // Apply contract check on the body
            mutations.add(contract.applyCheck(body));
        }

        return mutations;
    }

    /**
     * Remove all contracts checks that were previously applied.
     * @param mutations an ArrayList of the BodyMutations that were done
     */
    public void removeContractsChecks(ArrayList<BodyMutation> mutations) {
        // Remove contracts checks
        for (BodyMutation mutation : mutations) {
            mutation.undo();
        }
    }

    public BodyMutation addContractCheck(Contract contract, Mutation mutation) {
        Body body = null;
        BodyMutation bodyMutation = null;

        if (mutation instanceof MethodMutation) {
            // Get the body and put checks at the end
            MethodMutation mMutation = (MethodMutation)mutation;
            body = mMutation.getBody();
        } else if (mutation instanceof ClassMutation) {
            // TODO: put checks inside constructors
            return bodyMutation; //TODO: remove
        } else if (mutation instanceof ProgramMutation) {
            // TODO: if ProgramMutation, put checks somewhere ?
            return bodyMutation; //TODO: remove
        } else {
            throw new IllegalArgumentException("mutation is unknown");
        }

        // Apply contract check on the body
        bodyMutation = contract.applyCheck(body);

        return bodyMutation;
    }

    public void removeContractCheck(BodyMutation mutation) {
        mutation.undo();
    }

    private SootMethod randomSootMethod() {
        // return a random method that is already executed
        SootClass sClass = this.randomSootClass();
        ArrayList<SootMethod> methods = new ArrayList<SootMethod>();
        for (SootMethod method : sClass.getMethods()) {
            if (executedMethods.contains(method)) {
                methods.add(method);
            } else if (method.getName().startsWith("<")) {
                // <init> or <clinit>
                methods.add(method);
            }
        }
        int idMethod = rand.nextUint(methods.size());
        return methods.get(idMethod);
    }

    private SootClass randomSootClass() {
        int idMutant = rand.nextUint(mutants.size());
        return mutants.get(idMutant).getSootClass();
    }

    /**
     * Randomly choose and apply a MethodMutation
     * @param  method            the SootMethod which will be mutated
     * @return                   a MethodMutation
     * @throws MutationException if mutation failed
     */
    private MethodMutation randomMethodMutation(SootMethod method)
            throws MutationException {
        MethodMutation mutation = null;
        switch (rand.randLimits(0.05, 0.2, 1.0)) {
        case 0:
            mutation = new AddLocalMutation(rand, method);
            break;
        case 1:
            mutation = new AssignMutation(rand, method);
            break;
        case 2:
        default:
            mutation = new CallMethodMutation(rand, method, executedMethods, mutants);
            break;
        }
        return mutation;
    }

    /**
     * Randomly choose and apply a ClassMutation
     * @param  sootClass         the SootClass which will be mutated
     * @return                   a ClassMutation
     * @throws MutationException if mutation failed
     */
    private ClassMutation randomClassMutation(SootClass sootClass)
            throws MutationException {
        ClassMutation mutation = null;
        switch (rand.randLimits(0.5, 1.0)) {
        case 0:
            mutation = new AddFieldMutation(rand, sootClass);
            break;
        case 1:
        default:
            mutation = new AddMethodMutation(rand, sootClass);
            break;
        }
        return mutation;
    }

    /**
     * Randomly choose and apply a ProgramMutation
     * @return a ProgramMutation
     * @throws MutationException if mutation failed
     */
    private ProgramMutation randomProgramMutation() throws MutationException {
        ProgramMutation mutation = null;
        switch (rand.nextUint(1)) {
        case 0:
        default:
            mutation = new AddClassMutation(rand, this);
            break;
        }
        return mutation;
    }

    /**
     * Randomly choose and apply a Mutation between a ProgramMutation,
     * a ClassMutation or a MethodMutation
     * @return random Mutation
     * @throws MutationException if mutation failed
     */
    public Mutation randomMutation() throws MutationException {
        Mutation mutation = null;
        switch (rand.randLimits(0.01, 0.05, 1.0)) {
        case 0: // P = 0,005 : Program level mutation
            if (mutants.size() < Program.MUTANTS_NUMBER_LIMIT) {
                mutation = this.randomProgramMutation();
                break;
            }
            // else fall through
        case 1: // P = 0,005 : Class level mutation
            SootClass sootClass = this.randomSootClass();
            mutation = this.randomClassMutation(sootClass);
            break;
        case 2: // P = 0,99 : Method/Body level mutation
        default:
            SootMethod method = this.randomSootMethod();
            mutation = this.randomMethodMutation(method);
            break;
        }
        return mutation;
    }

    /**
     * Generate and launch program within a separate Thread
     * Warning: this will not kill threads within a while(true) loop. Use genAndLaunchWithJVM() instead
     * @param timeout in milliseconds before interrupting the Thread
     * @throws Throwable can throw any type of Throwable or InterruptedException
     */
    public void genAndLaunch(int timeout) throws Throwable {
        ByteClassLoader loader =
                new ByteClassLoader(Thread.currentThread().getContextClassLoader());
        for (int i = mutants.size() - 1; i >= 0; i--) {
            Mutant mut = mutants.get(i);
            if (logger.isDebugEnabled()) {
                logger.debug("===Class {}{}===", classBaseName, i);
                logger.debug(mut.toString());
            }
            byte[] array = mut.toClass();
            Launcher launcher = new Launcher(loader, array, classBaseName + i);
            Thread thread = new Thread(launcher);
            Handler handler = new Handler();
            thread.setUncaughtExceptionHandler(handler);
            thread.start();
            thread.join(timeout);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new InterruptedException();
            }
            handler.throwException();
        }
    }

    /**
     * Generate and launch program within a separate JVM
     * @param folder
     * @param timeout in milliseconds before killing the JVM
     * @throws Throwable
     */
    public void genAndLaunchWithJVM(String folder, int timeout) throws Throwable {
        this.saveAsClassFiles(folder);
        MutantGenerator gen = new MutantGenerator(rand, "Main");
        Mutant mut = gen.genMainLoader(mutants);
        mut.toClassFile(folder);
        Util.startJVM(folder, mut.getClassName(), timeout);
    }

    /**
     * Save all classes of this program
     * @param folder destination
     */
    public void saveAsClassFiles(String folder) {
        for (Mutant mut : mutants) {
            mut.toClassFile(folder);
        }
    }

    /**
     * Save all classes as Jimple source files
     * @param folder destination
     */
    public void saveAsJimpleFiles(String folder) {
        for (Mutant mut : mutants) {
            mut.toJimpleFile(folder);
        }
    }

    @Override
    public String toString() {
        String str = new String();
        for (Mutant mut : mutants) {
            str += mut.toString();
        }
        return str;
    }
}
