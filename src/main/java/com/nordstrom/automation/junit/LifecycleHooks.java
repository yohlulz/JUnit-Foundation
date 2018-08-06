package com.nordstrom.automation.junit;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.TestClass;
import com.nordstrom.automation.junit.JUnitConfig.JUnitSettings;
import com.nordstrom.common.base.UncheckedThrow;
import com.nordstrom.common.file.PathUtils.ReportsDirectory;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.attribute.AnnotationRetention;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.pool.TypePool;

/**
 * This class implements the hooks and utility methods that activate the core functionality of <b>JUnit Foundation</b>.
 */
public class LifecycleHooks {

    private static Map<Class<?>, Class<?>> proxyMap = new HashMap<>();
    private static JUnitConfig config;
    
    private LifecycleHooks() {
        throw new AssertionError("LifecycleHooks is a static utility class that cannot be instantiated");
    }
    
    /**
     * This static initializer installs a shutdown hook for each specified listener. It also rebases the ParentRunner
     * and BlockJUnit4ClassRunner classes to enable the core functionality of JUnit Foundation.
     */
    static {
        for (ShutdownListener listener : ServiceLoader.load(ShutdownListener.class)) {
            Runtime.getRuntime().addShutdownHook(getShutdownHook(listener));
        }
    }
    
    public static void premain(String args, Instrumentation instrumentation) {
        installTransformer(instrumentation);
    }
    
    public static ClassFileTransformer installTransformer(Instrumentation instrumentation) {
        TypeDescription type1 = TypePool.Default.ofClassPath().describe("org.junit.runners.ParentRunner").resolve();
        TypeDescription type2 = TypePool.Default.ofClassPath().describe("org.junit.runners.BlockJUnit4ClassRunner").resolve();
        
        return new AgentBuilder.Default()
                .type(isSubTypeOf(type1))
                .transform((builder, type, classLoader, module) -> 
                        builder.method(named("createTestClass")).intercept(MethodDelegation.to(CreateTestClass.class))
                               .method(named("run")).intercept(MethodDelegation.to(Run.class))
                               .implement(Hooked.class))
                .type(isSubTypeOf(type2))
                .transform((builder, type, classLoader, module) -> 
                        builder.method(named("createTest")).intercept(MethodDelegation.to(CreateTest.class))
                               .method(named("runChild")).intercept(MethodDelegation.to(RunChild.class))
                               .implement(Hooked.class))
                .installOn(instrumentation);
    }
    
    /**
     * Create a {@link Thread} object that encapsulated the specified shutdown listener.
     * 
     * @param listener shutdown listener object
     * @return shutdown listener thread object
     */
    static Thread getShutdownHook(final ShutdownListener listener) {
        return new Thread() {
            @Override
            public void run() {
                listener.onShutdown();
            }
        };
    }
    
    /**
     * Get the configuration object for JUnit Foundation.
     * 
     * @return JUnit Foundation configuration object
     */
    static synchronized JUnitConfig getConfig() {
        if (config == null) {
            config = JUnitConfig.getConfig();
        }
        return config;
    }
    
    @SuppressWarnings("squid:S1118")
    public static class CreateTestClass {
        static final ServiceLoader<TestClassWatcher> classWatcherLoader;
        static final Map<TestClass, Object> CLASS_TO_RUNNER = new ConcurrentHashMap<>();
        
        static {
            classWatcherLoader = ServiceLoader.load(TestClassWatcher.class);
        }
        
        public static TestClass intercept(@This Object runner, @SuperCall Callable<?> proxy) throws Exception {
            TestClass testClass = (TestClass) proxy.call();
            CLASS_TO_RUNNER.put(testClass, runner);
            
            for (TestClassWatcher watcher : classWatcherLoader) {
                watcher.testClassCreated(testClass, runner);
            }
            
            return testClass;
        }
    }
    
    @SuppressWarnings("squid:S1118")
    public static class Run {
        static final ServiceLoader<RunListener> runListenerLoader;
        private static final Set<RunNotifier> NOTIFIERS = new HashSet<>();
        
        static {
            runListenerLoader = ServiceLoader.load(RunListener.class);
        }
        
        public static void intercept(@This Object runner, @SuperCall Callable<?> proxy, @Argument(0) RunNotifier notifier) throws Exception {
            if (NOTIFIERS.add(notifier)) {
                Description description = invoke(runner, "getDescription");
                for (RunListener listener : runListenerLoader) {
                    notifier.addListener(listener);
                    listener.testRunStarted(description);
                }
            }
            proxy.call();
        }        
    }
    
    /**
     * This class declares the interceptor for the {@link org.junit.runners.BlockJUnit4ClassRunner#createTest createTest}
     * method.
     */
    @SuppressWarnings("squid:S1118")
    public static class CreateTest {
        
        static final ServiceLoader<TestObjectWatcher> objectWatcherLoader;
        static final Map<Object, TestClass> INSTANCE_TO_CLASS = new ConcurrentHashMap<>();
        
        static {
            objectWatcherLoader = ServiceLoader.load(TestObjectWatcher.class);
        }
        
        /**
         * Interceptor for the {@link org.junit.runners.BlockJUnit4ClassRunner#createTest createTest} method.
         * 
         * @param runner target {@link org.junit.runners.BlockJUnit4ClassRunner BlockJUnit4ClassRunner} object
         * @param proxy callable proxy for the intercepted method
         * @return {@code anything}
         * @throws Exception if something goes wrong
         */
        public static Object intercept(@This Object runner, @SuperCall Callable<?> proxy) throws Exception {
            Object testObj = LifecycleHooks.installHooks(proxy.call());
            INSTANCE_TO_CLASS.put(testObj, LifecycleHooks.invoke(runner, "getTestClass"));
            LifecycleHooks.applyTimeout(testObj);
            
            for (TestObjectWatcher watcher : objectWatcherLoader) {
                watcher.testObjectCreated(testObj, INSTANCE_TO_CLASS.get(testObj));
            }
            
            return testObj;
        }
    }
    
    /**
     * Get the test class object that wraps the specified instance.
     * 
     * @param instance instance object (either test class or Suite)
     * @return {@link TestClass} associated with specified instance object
     */
    public static TestClass getTestClassFor(Object instance) {
        TestClass testClass = CreateTest.INSTANCE_TO_CLASS.get(instance);
        if (testClass != null) {
            return testClass;
        }
        throw new IllegalStateException("No associated test class was found for specified instance");
    }
    
    /**
     * Get the parent runner that owns the specified test class object;
     * 
     * @param testClass {@link TestClass} object
     * @return {@link org.junit.runners.ParentRunner ParentRunner} that owns the specified test class object
     */
    public static Object getRunnerFor(TestClass testClass) {
        Object runner = CreateTestClass.CLASS_TO_RUNNER.get(testClass);
        if (runner != null) {
            return runner;
        }
        throw new IllegalStateException("No associated runner was for for specified test class");
    }
    
    public static Description getDescription(Object instance, Object target) {
        TestClass testClass = getTestClassFor(instance);
        Object runner = getRunnerFor(testClass);
        
        
        return invoke(runner, "getDescription", target);
    }
    
    /**
     * If configured for default test timeout, apply this value to every test that doesn't already specify a longer
     * timeout interval.
     * 
     * @param testObj test class object
     */
    static void applyTimeout(Object testObj) {
        // if default test timeout is defined
        if (getConfig().containsKey(JUnitSettings.TEST_TIMEOUT.key())) {
            // get default test timeout
            long defaultTimeout = getConfig().getLong(JUnitSettings.TEST_TIMEOUT.key());
            // iterate over test object methods
            for (Method method : testObj.getClass().getDeclaredMethods()) {
                // get @Test annotation
                Test annotation = method.getDeclaredAnnotation(Test.class);
                // if annotation declared and current timeout is less than default
                if ((annotation != null) && (annotation.timeout() < defaultTimeout)) {
                    // set test timeout interval
                    MutableTest.proxyFor(method).setTimeout(defaultTimeout);
                }
            }
        }
    }
    
    /**
     * Create an enhanced instance of the specified test class object.
     * 
     * @param testObj test class object to be enhanced
     * @return enhanced test class object
     */
    static synchronized Object installHooks(Object testObj) {
        Class<?> testClass = testObj.getClass();
        MethodInterceptor.attachWatchers(testClass);
        
        if (testObj instanceof Hooked) {
            return testObj;
        }
        
        Class<?> proxyType = proxyMap.get(testClass);
        
        if (proxyType == null) {
            try {
                proxyType = new ByteBuddy()
                        .with(AnnotationRetention.ENABLED)
                        .subclass(testClass)
                        .name(getSubclassName(testObj))
                        .method(isAnnotatedWith(anyOf(Test.class, Before.class, After.class)))
                        .intercept(MethodDelegation.to(MethodInterceptor.class))
                        .implement(Hooked.class)
                        .make()
                        .load(testClass.getClassLoader())
                        .getLoaded();
                proxyMap.put(testClass, proxyType);
            } catch (SecurityException | IllegalArgumentException e) {
                throw UncheckedThrow.throwUnchecked(e);
            }
        }
            
        try {
            return proxyType.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw UncheckedThrow.throwUnchecked(e);
        }
    }
    
    /**
     * Get class of specified test class instance.
     * 
     * @param instance test class instance
     * @return class of test class instance
     */
    public static Class<?> getInstanceClass(Object instance) {
        Class<?> clazz = instance.getClass();      
        return (instance instanceof Hooked) ? clazz.getSuperclass() : clazz;
    }
    
    /**
     * Get fully-qualified name to use for hooked test class.
     * 
     * @param testObj test class object being hooked
     * @return fully-qualified name for hooked subclass
     */
    static String getSubclassName(Object testObj) {
        Class<?> testClass = testObj.getClass();
        String testClassName = testClass.getSimpleName();
        String testPackageName = testClass.getPackage().getName();
        ReportsDirectory constant = ReportsDirectory.fromObject(testObj);
        
        switch (constant) {
            case FAILSAFE_2:
            case FAILSAFE_3:
            case SUREFIRE_2:
            case SUREFIRE_3:
            case SUREFIRE_4:
                return testPackageName + ".Hooked" + testClassName;
                
            default:
                return testClass.getCanonicalName() + "Hooked";
        }
        
    }
    
    /**
     * Invoke the named method with the specified parameters on the specified target object.
     * 
     * @param target target object
     * @param methodName name of the desired method
     * @param parameters parameters for the method invocation
     * @return result of method invocation
     */
    @SuppressWarnings("unchecked")
    static <T> T invoke(Object target, String methodName, Object... parameters) {
        Class<?>[] parameterTypes = new Class<?>[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            parameterTypes[i] = parameters[i].getClass();
        }
        
        Throwable thrown = null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return (T) method.invoke(target, parameters);
            } catch (NoSuchMethodException e) {
                thrown = e;
            } catch (SecurityException | IllegalAccessException | IllegalArgumentException
                            | InvocationTargetException e) {
                thrown = e;
                break;
            }
        }
        
        throw UncheckedThrow.throwUnchecked(thrown);
    }
}
