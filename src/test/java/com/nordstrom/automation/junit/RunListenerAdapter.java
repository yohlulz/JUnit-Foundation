package com.nordstrom.automation.junit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * This run listener tracks the results of executed tests.
 */
public class RunListenerAdapter extends RunListener {
    
    private List<Description> m_allTestMethods = Collections.synchronizedList(new ArrayList<>());
    private List<Failure> m_testFailures = Collections.synchronizedList(new ArrayList<>());
    private List<Description> m_failedTests = Collections.synchronizedList(new ArrayList<>());
    private List<Failure> m_assumptionFailures = Collections.synchronizedList(new ArrayList<>());
    private List<Description> m_failedAssumptions = Collections.synchronizedList(new ArrayList<>());
    private List<Description> m_ignoredTests = Collections.synchronizedList(new ArrayList<>());
    private List<Description> m_retriedTests = Collections.synchronizedList(new ArrayList<>());
    private List<Description> m_passedTests = Collections.synchronizedList(new ArrayList<>());
                
    /**
     * Called when an atomic test is about to be started.
     * 
     * @param description the description of the test that is about to be run 
     * (generally a class and method name)
     */
    @Override
    public void testStarted(Description description) throws Exception {
        m_allTestMethods.add(description);
    }

    /** 
     * Called when an atomic test fails.
     * 
     * @param failure describes the test that failed and the exception that was thrown
     */
    @Override
    public void testFailure(Failure failure) throws Exception {
        m_testFailures.add(failure);
        m_failedTests.add(failure.getDescription());
    }

    /**
     * Called when an atomic test flags that it assumes a condition that is
     * false
     * 
     * @param failure
     *            describes the test that failed and the
     *            {@link AssumptionViolatedException} that was thrown
     */
    @Override
    public void testAssumptionFailure(Failure failure) {
        m_assumptionFailures.add(failure);
        m_failedAssumptions.add(failure.getDescription());
    }

    /**
     * Called when a test will not be run, generally because a test method is annotated 
     * with {@link org.junit.Ignore}.
     * 
     * @param description describes the test that will not be run
     */
    @Override
    public void testIgnored(Description description) throws Exception {
        if (null != description.getAnnotation(RetriedTest.class)) {
            m_retriedTests.add(description);
        } else {
            m_ignoredTests.add(description);
        }
    }
    
    /**
     * Get list of all tests that were run.
     * 
     * @return list of all tests
     */
    public List<Description> getAllTestMethods() {
        return m_allTestMethods;
    }
    
    /**
     * Get list of passed tests.
     * 
     * @return list of passed tests
     */
    public List<Description> getPassedTests() {
        m_passedTests.clear();
        m_passedTests.addAll(m_allTestMethods);
        m_failedTests.forEach(m_passedTests::remove);
        m_failedAssumptions.forEach(m_passedTests::remove);
        m_ignoredTests.forEach(m_passedTests::remove);
        m_retriedTests.forEach(m_passedTests::remove);
        return m_passedTests;
    }
    
    /**
     * Get list of test failure objects.
     * 
     * @return list of failure objects
     */
    public List<Failure> getTestFailures() {
        return m_testFailures;
    }

    /**
     * Get list of failed tests.
     * 
     * @return list of failed tests
     */
    public List<Description> getFailedTests() {
        return m_failedTests;
    }
    
    /**
     * Get list of assumption failures.
     * 
     * @return list of assumption failures
     */
    public List<Failure> getAssumptionFailures() {
        return m_assumptionFailures;
    }
    
    /**
     * Get list of failed assumptions.
     * 
     * @return list of failed assumptions
     */
    public List<Description> getFailedAssumptions() {
        return m_failedAssumptions;
    }
    
    /**
     * Get list of ignored tests.
     * 
     * @return list of ignored tests
     */
    public List<Description> getIgnoredTests() {
        return m_ignoredTests;
    }
    
    /**
     * Get list of retried tests.
     * 
     * @return list of retried tests
     */
    public List<Description> getRetriedTests() {
        return m_retriedTests;
    }

}
