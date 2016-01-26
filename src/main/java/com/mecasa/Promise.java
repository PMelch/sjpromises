package com.mecasa;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Vector;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * User: peter
 * Date: 17.01.2016
 * Time: 15:39
 */
public class Promise  {
    // default executor
//    private static ExecutorService sExecutor = new ThreadPoolExecutor(1, 10, 100, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));
    private final Vector<Future<?>> _futures = new Vector<>();
    private final Vector<Object> _values = new Vector<>();
    private final Vector<Deferrable<?>> _deferrables = new Vector<>();
    private Object[] _params = null;
    private Throwable _rejected;
    private ExecutorService _executor;
    private static ExecutorServiceProvider sExecutorServiceProvider = new ExecutorServiceProvider() {
        @Override
        public ExecutorService getExecutorService() {
            return new ThreadPoolExecutor(1, 10, 100, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));
        }
    };

    public interface ExecutorServiceProvider {
        ExecutorService getExecutorService();
    }

    public static void setExecutorServiceProvider(@NotNull ExecutorServiceProvider provider) {
        sExecutorServiceProvider = provider;
    }

    public static Promise when(Deferrable<String> deferrable) {
        return new Promise(deferrable);
    }

    public static Promise all(Deferrable<?>... deferrableList) {
        return new Promise(deferrableList, null);
    }

    public Promise() {
    }

    private Promise(Throwable rejected) {
        this();
        _rejected = rejected;
    }

    public <T> Promise(final Deferrable<T> deferrable) {
        this();

        _params = null;
        _deferrables.add(deferrable);
    }

    private void submitPendingDeferrables() {
        if (_executor == null) {
            _executor = sExecutorServiceProvider.getExecutorService();
        }
        for (Deferrable deferrable : _deferrables) {
            _futures.add(_executor.submit(() -> deferrable.call(_params)));
        }
    }


    public Promise(@NotNull Deferrable<?>[] deferrableList, @Nullable final Vector _values) {
        this();

        _params = _values != null ? _values.toArray(new Object[_values.size()]) : null;
        for (Deferrable<?> deferrable : deferrableList) {
            _deferrables.add(deferrable);
        }
    }



    public void waitForAll() throws InterruptedException {
        submitPendingDeferrables();

        _futures.stream().forEach((future) -> {
            synchronized (_values) {
                try {
                    _values.add(future.get());
                } catch (InterruptedException e) {
                    _rejected = _rejected == null ? e : _rejected;
                } catch (ExecutionException e) {
                    _rejected = _rejected == null ? e.getCause() : _rejected;
                }
            }
        });

        // remove all pending futures
        _futures.clear();
    }


    public Promise then(Deferrable<?>... deferrableList) {
        submitPendingDeferrables();
        try {
            waitForAll();
        } catch (InterruptedException ignored) {
        }
        // forward any rejected error to the next promise
        if (_rejected != null) {
            return new Promise(_rejected).setExecutor(_executor);
        }
        return new Promise(deferrableList, _values).setExecutor(_executor);
    }


    public Promise resolve(Consumer<Object[]> result) {
        submitPendingDeferrables();
        try {
            waitForAll();
        } catch (InterruptedException ignored) {
        }
        if (_rejected == null ) {
            result.accept(_values.toArray(new Object[_values.size()]));
        }
        return this;
    }

    public Promise reject(Consumer<Throwable> e) {
        submitPendingDeferrables();
        try {
            waitForAll();
        } catch (InterruptedException ignored) {
        }
        if (_rejected != null) {
            e.accept(_rejected);
        }

        return this;
    }

    public Promise setExecutor(ExecutorService executorService) {
        _executor = executorService;
        return this;
    }
}
