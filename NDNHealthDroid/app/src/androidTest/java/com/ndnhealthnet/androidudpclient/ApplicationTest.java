package com.ndnhealthnet.androidudpclient;

import android.app.Application;
import android.content.Context;
import android.test.ApplicationTestCase;

/**
 *
 */
public class ApplicationTest extends ApplicationTestCase<Application> {
    public ApplicationTest() {
        super(Application.class);
    }

    @Override
    protected void setUp() throws Exception {
        Context context = getContext();

        DatabaseHandlerTest dbHandlerTest = new DatabaseHandlerTest(context);
        dbHandlerTest.runTests();

        UtilsTest utilsTest = new UtilsTest(context);
        utilsTest.runTests();

        UDPListenerTest udpListenerTest = new UDPListenerTest(context);
        udpListenerTest.runTests();
    }
}