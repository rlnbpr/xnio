package org.xnio;

import java.io.IOException;

/**
 * Default IOFuture implementation that allows the result or exception to be set
 *
 * @author Stuart Douglas
 */
public class DefaultIoFuture<T> extends AbstractIoFuture<T> {

    @Override
    public boolean setException(final IOException exception) {
        return super.setException(exception);
    }

    @Override
    public boolean setResult(final T result) {
        return super.setResult(result);
    }
}
