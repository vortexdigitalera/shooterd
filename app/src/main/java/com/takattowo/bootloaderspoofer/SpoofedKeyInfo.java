package com.takattowo.bootloaderspoofer;

import android.security.keystore.KeyInfo;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Creates a proxy around a {@link KeyInfo} that forces
 * {@code isInsideSecureHardware()} to return {@code true}.
 * Uses {@link Proxy} instead of subclassing because many
 * {@link KeyInfo} methods are {@code @hide} and not available
 * at compile time.
 */
final class SpoofedKeyInfo {

    private SpoofedKeyInfo() {}

    /**
     * Wrap the given {@link KeyInfo} in a dynamic proxy that
     * overrides {@code isInsideSecureHardware()} to return true.
     * All other method calls are delegated to the original instance.
     */
    static KeyInfo wrap(KeyInfo delegate) {
        ClassLoader cl = delegate.getClass().getClassLoader();
        if (cl == null) cl = KeyInfo.class.getClassLoader();

        return (KeyInfo) Proxy.newProxyInstance(
                cl,
                new Class<?>[]{KeyInfo.class},
                new SecureHardwareHandler(delegate)
        );
    }

    private static final class SecureHardwareHandler implements InvocationHandler {
        private final KeyInfo delegate;

        SecureHardwareHandler(KeyInfo delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("isInsideSecureHardware".equals(method.getName())
                    && method.getParameterCount() == 0) {
                return true;
            }
            try {
                return method.invoke(delegate, args);
            } catch (Exception e) {
                Log.w(ModuleMain.TAG, "SpoofedKeyInfo delegate call failed: " + method.getName(), e);
                throw e;
            }
        }
    }
}
