package com.wix.detox;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.Espresso;
import android.support.test.espresso.IdlingResource;
import android.util.Log;

import com.wix.detox.espresso.ReactBridgeIdlingResource;
import com.wix.detox.espresso.ReactNativeTimersIdlingResource;

import org.joor.Reflect;
import org.joor.ReflectException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Created by simonracz on 15/05/2017.
 */

class ReactNativeSupport {
    private static final String LOG_TAG = "Detox";
    private static final String METHOD_GET_RN_HOST = "getReactNativeHost";
    private static final String METHOD_GET_INSTANCE_MANAGER = "getReactInstanceManager";
    private final static String METHOD_GET_CATALYST_INSTANCE = "getCatalystInstance";
    private final static String METHOD_ADD_DEBUG_BRIDGE_LISTENER = "addBridgeIdleDebugListener";
    private final static String METHOD_REMOVE_DEBUG_BRIDGE_LISTENER = "removeBridgeIdleDebugListener";
    private static final String METHOD_RECREATE_RN_CONTEXT = "recreateReactContextInBackground";
    private static final String METHOD_GET_REACT_CONTEXT = "getCurrentReactContext";
    private static final String METHOD_ADD_REACT_INSTANCE_LISTENER = "addReactInstanceEventListener";
    private static final String METHOD_REMOVE_REACT_INSTANCE_LISTENER = "removeReactInstanceEventListener";
    private static final String INTERFACE_REACT_INSTANCE_EVENT_LISTENER =
            "com.facebook.react.ReactInstanceManager$ReactInstanceEventListener";
    private static final String METHOD_HAS_STARTED_CREAT_CTX = "hasStartedCreatingInitialContext";
    private static final String METHOD_CREAT_RN_CTX_IN_BG = "createReactContextInBackground";

    private static final String INTERFACE_BRIDGE_IDLE_DEBUG_LISTENER =
            "com.facebook.react.bridge.NotThreadSafeBridgeIdleDebugListener";

    private static final String FIELD_UI_MSG_QUEUE = "mUiMessageQueueThread";
    private static final String FIELD_UI_BG_MSG_QUEUE = "mUiBackgroundMessageQueueThread";
    private static final String FIELD_NATIVE_MODULES_MSG_QUEUE = "mNativeModulesMessageQueueThread";
    private static final String FIELD_JS_MSG_QUEUE = "mJSMessageQueueThread";
    private static final String METHOD_GET_LOOPER = "getLooper";

    // Espresso has a public method to register Loopers.
    // BUT, they don't give you back a handle to them.
    // Therefore you can't unregister them.
    // We create the LooperIdlingResources by ourselves to keep a handle to them.
    private static final String CLASS_ESPRESSO_LOOPER_IDLING_RESOURCE =
            "android.support.test.espresso.base.LooperIdlingResource";

    private ReactNativeSupport() {
        // static class
    }

    static boolean isReactNativeApp() {
        Class<?> found = null;
        try {
            found = Class.forName("com.facebook.react.ReactApplication");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return (found != null);
    }

    /**
     * Returns the instanceManager using reflection.
     *
     * @param reactNativeHostHolder the object that has a getReactNativeHost() method
     * @return Returns the instanceManager as an Object or null
     */
    private static Object getInstanceManager(@NonNull Object reactNativeHostHolder) {
        Object instanceManager = null;
        try {
            instanceManager = Reflect.on(reactNativeHostHolder)
                    .call(METHOD_GET_RN_HOST)
                    .call(METHOD_GET_INSTANCE_MANAGER)
                    .get();
        } catch (ReflectException e) {
            Log.e(LOG_TAG, "Problem calling getInstanceManager()", e.getCause());
        }

        return instanceManager;
    }

    /**
     * <p>
     * Reloads the React Native application.
     * </p>
     *
     * <p>
     * It is a lot faster to reload a React Native application this way,
     * than to reload the whole Activity or Application.
     * </p>
     *
     * @param reactNativeHostHolder the object that has a getReactNativeHost() method
     */
    static void reloadApp(@NonNull Object reactNativeHostHolder) {
        if (!isReactNativeApp()) {
            return;
        }
        Log.i(LOG_TAG, "Reloading React Native");

        removeEspressoIdlingResources(reactNativeHostHolder);

        final Object instanceManager = getInstanceManager(reactNativeHostHolder);
        if (instanceManager == null) {
            return;
        }

        // Must be called on the UI thread!
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    Reflect.on(instanceManager).call(METHOD_RECREATE_RN_CONTEXT);
                } catch (ReflectException e) {
                    Log.e(LOG_TAG, "Problem calling reloadApp()", e.getCause());
                }
            }
        });

        waitForReactNativeLoad(reactNativeHostHolder);
    }

    /**
     * <p>
     * The rendering of RN views are NOT guaranteed to be finished after this call.
     * However, calling Espresso methods are safe from this point.
     * </p>
     */
    static void waitForReactNativeLoad(@NonNull Object reactNativeHostHolder) {
        if (!isReactNativeApp()) {
            return;
        }

        final Object instanceManager = getInstanceManager(reactNativeHostHolder);
        if (instanceManager == null) {
            return;
        }

        final Object[] reactContextHolder = new Object[1];
        reactContextHolder[0] = Reflect.on(instanceManager).call(METHOD_GET_REACT_CONTEXT).get();
        if (reactContextHolder[0] == null) {
            Class<?> listener;
            try {
                listener = Class.forName(INTERFACE_REACT_INSTANCE_EVENT_LISTENER);
            } catch (ClassNotFoundException e) {
                Log.e(LOG_TAG, "Can't find ReactInstanceEventListener()", e);
                return;
            }
            synchronized (instanceManager) {
                Class[] proxyInterfaces = new Class[]{listener};
                final Proxy[] proxyHolder = new Proxy[1];
                final Delegator delegator = new Delegator(proxyInterfaces, new Object[] {new ReactInstanceEventListenerProxy() {
                    @Override
                    public void onReactContextInitialized(Object reactContext) {
                        Log.i(LOG_TAG, "Got react context through listener.");
                        reactContextHolder[0] = reactContext;
                        Reflect.on(instanceManager).call(METHOD_REMOVE_REACT_INSTANCE_LISTENER, (Object) proxyHolder[0]);
                        synchronized (instanceManager) {
                            instanceManager.notify();
                        }
                    }
                }});
                proxyHolder[0] = (Proxy) Proxy.newProxyInstance(
                        listener.getClassLoader(),
                        proxyInterfaces,
                        delegator);
                Reflect.on(instanceManager).call(
                        METHOD_ADD_REACT_INSTANCE_LISTENER,
                        proxyHolder[0]);
                if (!(boolean) Reflect.on(instanceManager).call(METHOD_HAS_STARTED_CREAT_CTX).get()) {
                    // Must be called on the UI thread!
                    Handler handler = new Handler(InstrumentationRegistry.getTargetContext().getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Reflect.on(instanceManager).call(METHOD_CREAT_RN_CTX_IN_BG);
                            } catch (ReflectException e) {
                                Log.e(LOG_TAG, "Problem calling createReactContextInBackground()",
                                        e.getCause());
                            }
                        }
                    });

                }
                while (true) {
                    try {
                        instanceManager.wait();
                        break;
                    } catch (InterruptedException e) {
                        Log.i(LOG_TAG, "Got interrupted.", e);
                        // go on
                    }
                }
            }
        }

        // We could call waitForBridgeAndUIIDle(..) here, but
        // Espresso will do that for us either way.
        setupEspressoIdlingResources(reactNativeHostHolder, reactContextHolder[0]);
    }

    private static Object bridgeIdleSignaler = null;

    private static void createBridgeIdleSignaler() {
        Class<?> bridgeIdleDebugListener = null;
        try {
            bridgeIdleDebugListener = Class.forName(INTERFACE_BRIDGE_IDLE_DEBUG_LISTENER);
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, "Can't find ReactBridgeIdleSignaler()", e);
            return;
        }

        rnBridgeIdlingResource = new ReactBridgeIdlingResource();

        Class[] proxyInterfaces = new Class[]{bridgeIdleDebugListener};
        bridgeIdleSignaler = Proxy.newProxyInstance(
                bridgeIdleDebugListener.getClassLoader(),
                proxyInterfaces,
                new Delegator(proxyInterfaces, new Object[] { rnBridgeIdlingResource })
        );
    }

    private static ReactNativeTimersIdlingResource rnTimerIdlingResource = null;
    private static ReactBridgeIdlingResource rnBridgeIdlingResource = null;

    private static void setupEspressoIdlingResources(
            @NonNull Object reactNativeHostHolder,
            @NonNull Object reactContext) {
        removeEspressoIdlingResources(reactNativeHostHolder, reactContext);
        Log.i(LOG_TAG, "Setting up Espresso Idling Resources for React Native.");

        setupReactNativeQueueInterrogators(reactContext);

        createBridgeIdleSignaler();
        Reflect.on(reactContext)
                .call(METHOD_GET_CATALYST_INSTANCE)
                .call(METHOD_ADD_DEBUG_BRIDGE_LISTENER, bridgeIdleSignaler);

        rnTimerIdlingResource = new ReactNativeTimersIdlingResource(reactContext);

        Espresso.registerIdlingResources(rnTimerIdlingResource, rnBridgeIdlingResource);
    }

    private static ArrayList<IdlingResource> looperIdlingResources = new ArrayList<>();

    private static void setupReactNativeQueueInterrogators(@NonNull Object reactContext) {
        HashSet<Looper> excludedLoopers = new HashSet<>();
        excludedLoopers.add(InstrumentationRegistry.getTargetContext().getMainLooper());
        setupRNQueueInterrogator(reactContext, FIELD_UI_MSG_QUEUE, excludedLoopers);
        setupRNQueueInterrogator(reactContext, FIELD_UI_BG_MSG_QUEUE, excludedLoopers);
        setupRNQueueInterrogator(reactContext, FIELD_JS_MSG_QUEUE, excludedLoopers);
        setupRNQueueInterrogator(reactContext, FIELD_NATIVE_MODULES_MSG_QUEUE, excludedLoopers);
    }

    private static void setupRNQueueInterrogator(
            @NonNull Object reactContext,
            @NonNull String field,
            @NonNull HashSet<Looper> excludedLoopers) {
        Object queue;
        Object looper;

        try {
            if ((queue = Reflect.on(reactContext).field(field).get()) != null) {
                if ((looper = Reflect.on(queue).call(METHOD_GET_LOOPER).get()) != null) {
                    if (!excludedLoopers.contains(looper)) {
                        IdlingResource looperIdlingResource =
                                Reflect.on(CLASS_ESPRESSO_LOOPER_IDLING_RESOURCE).create(looper, false).get();

                        looperIdlingResources.add(looperIdlingResource);
                        Espresso.registerIdlingResources(looperIdlingResource);
                        excludedLoopers.add((Looper)looper);
                    }
                }
            }
        } catch (ReflectException e) {
            // The mUiBackgroundMessageQueueThread field is stripped at runtime
            // in the current RN release.
            // We should still keep trying to grab it to be future proof.
            if (!field.equals("mUiBackgroundMessageQueueThread")) {
                Log.d(LOG_TAG, "Can't set up monitoring for " + field, e);
            }
        }
    }

    static void removeEspressoIdlingResources(@NonNull Object reactNativeHostHolder) {
        Object reactContext = null;
        final Object instanceManager = getInstanceManager(reactNativeHostHolder);
        if (instanceManager != null) {
            reactContext = Reflect.on(instanceManager).call(METHOD_GET_REACT_CONTEXT).get();
        }

        removeEspressoIdlingResources(reactNativeHostHolder, reactContext);
    }

    private static void removeEspressoIdlingResources(
            @NonNull Object reactNativeHostHolder,
            Object reactContext) {

        Log.i(LOG_TAG, "Removing Espresso IdlingResources for React Native.");

        if (rnBridgeIdlingResource != null && rnTimerIdlingResource != null) {
            Espresso.unregisterIdlingResources(rnTimerIdlingResource, rnBridgeIdlingResource);
            rnTimerIdlingResource = null;
            rnBridgeIdlingResource = null;
        }

        removeReactNativeQueueInterrogators();

        final Object instanceManager = getInstanceManager(reactNativeHostHolder);
        if (instanceManager == null) {
            return;
        }

        if (bridgeIdleSignaler != null) {
            if (reactContext != null) {
                Reflect.on(reactContext)
                        .call(METHOD_GET_CATALYST_INSTANCE)
                        .call(METHOD_REMOVE_DEBUG_BRIDGE_LISTENER, bridgeIdleSignaler);
            }
            bridgeIdleSignaler = null;
        }
    }

    private static void removeReactNativeQueueInterrogators() {
        for (IdlingResource res : looperIdlingResources) {
            Espresso.unregisterIdlingResources(res);
        }
        looperIdlingResources.clear();
    }
}
