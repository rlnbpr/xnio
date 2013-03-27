package org.xnio.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.DefaultIoFuture;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Pooled;
import org.xnio.StreamConnection;
import org.xnio.XnioWorker;
import org.xnio.channels.BoundChannel;
import org.xnio.channels.PushBackStreamChannel;
import org.xnio.channels.SslConnection;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.ssl.XnioSsl;

/**
 * Simple HTTP client that can perform a HTTP upgrade.
 *
 * @author Stuart Douglas
 */
public class HttpUpgrade<T extends StreamConnection> {

    private final XnioWorker worker;
    private final XnioSsl ssl;
    private final InetSocketAddress bindAddress;
    private final URI uri;
    private final Map<String, String> headers;
    private final ChannelListener<? super T> openListener;
    private final ChannelListener<? super BoundChannel> bindListener;
    private final OptionMap optionMap;
    private final HandshakeChecker handshakeChecker;
    private final DefaultIoFuture<T> future = new DefaultIoFuture<T>();
    private T connection;


    /**
     * A class that can decide if the resulting handshake is valid. If not it should
     * throw an {@link IOException}
     */
    public interface HandshakeChecker {
        void checkHandshake(final Map<String, String> headers, final String responseBody) throws IOException;
    }


    private HttpUpgrade(final XnioWorker worker, final XnioSsl ssl, final InetSocketAddress bindAddress, final URI uri, final Map<String, String> headers, final ChannelListener<? super T> openListener, final ChannelListener<? super BoundChannel> bindListener, final OptionMap optionMap, final HandshakeChecker handshakeChecker) {
        this.worker = worker;
        this.ssl = ssl;
        this.bindAddress = bindAddress;
        this.uri = uri;
        this.headers = headers;
        this.openListener = openListener;
        this.bindListener = bindListener;
        this.optionMap = optionMap;
        this.handshakeChecker = handshakeChecker;
    }


    public static IoFuture<SslConnection> performUpgrade(final XnioWorker worker, XnioSsl ssl, InetSocketAddress bindAddress, URI uri, final Map<String, String> headers, ChannelListener<? super SslConnection> openListener, ChannelListener<? super BoundChannel> bindListener, OptionMap optionMap, HandshakeChecker handshakeChecker) {
        return new HttpUpgrade<SslConnection>(worker, ssl, bindAddress, uri, headers, openListener, bindListener, optionMap, handshakeChecker).doUpgrade();
    }


    public static IoFuture<StreamConnection> performUpgrade(final XnioWorker worker, InetSocketAddress bindAddress, URI uri, final Map<String, String> headers, ChannelListener<? super StreamConnection> openListener, ChannelListener<? super BoundChannel> bindListener, OptionMap optionMap, HandshakeChecker handshakeChecker) {
        return new HttpUpgrade<StreamConnection>(worker, null, bindAddress, uri, headers, openListener, bindListener, optionMap, handshakeChecker).doUpgrade();
    }

    private IoFuture<T> doUpgrade() {
        InetSocketAddress address = new InetSocketAddress(uri.getHost(), uri.getPort());

        final ChannelListener<StreamConnection> connectListener = new ConnectionOpenListener();
        if (uri.getScheme().equals("http")) {
            worker.openStreamConnection(bindAddress, address, connectListener, bindListener, optionMap);
        } else if (uri.getScheme().equals("https")) {
            if (ssl == null) {
                throw new IllegalArgumentException("XnioSsl was null for a https address");
            }
            ssl.openSslConnection(worker, bindAddress, address, connectListener, bindListener, optionMap);
        } else {
            throw new IllegalArgumentException("Unknown scheme " + uri.getScheme() + " must be http or https");
        }
        return future;
    }

    private final String buildHttpRequest() {

        final StringBuilder builder = new StringBuilder();
        builder.append("GET ");
        builder.append(uri.getPath());
        builder.append("HTTP/1.1\r\n");
        final Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.append(header.getKey());
            builder.append(": ");
            builder.append(header.getValue());
            builder.append("\r\n");
            seen.add(header.getKey().toLowerCase());
        }
        if (!seen.contains("host")) {
            builder.append("Host: ");
            builder.append(uri.getHost());
            builder.append("\r\n");
        }
        if (!seen.contains("connection")) {
            builder.append("Connection: upgrade\r\n");
        }
        if (!seen.contains("upgrade")) {
            throw new IllegalArgumentException("Upgrade: header was not supplied in header arguments");
        }
        builder.append("\r\n");
        return builder.toString();
    }


    private class ConnectionOpenListener implements ChannelListener<StreamConnection> {
        @Override
        public void handleEvent(final StreamConnection channel) {
            connection = (T)channel;
            final ByteBuffer buffer = ByteBuffer.wrap(buildHttpRequest().getBytes());
            int r;
            do {
                try {
                    r = channel.getSinkChannel().write(buffer);
                    if (r == 0) {
                        channel.getSinkChannel().getWriteSetter().set(new StringWriteListener(buffer));
                        channel.getSinkChannel().resumeWrites();
                        return;
                    }
                } catch (IOException e) {
                    future.setException(e);
                    return;
                }
            } while (buffer.hasRemaining());
            new UpgradeResultListener().handleEvent(new PushBackStreamChannel(connection.getSourceChannel()));
        }
    }

    private final class StringWriteListener implements ChannelListener<StreamSinkChannel> {

        final ByteBuffer buffer;

        private StringWriteListener(final ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void handleEvent(final StreamSinkChannel channel) {
            int r;
            do {
                try {
                    r = channel.write(buffer);
                    if (r == 0) {
                        channel.getWriteSetter().set(new StringWriteListener(buffer));
                        channel.resumeWrites();
                        return;
                    }
                } catch (IOException e) {
                    future.setException(e);
                    return;
                }
            } while (buffer.hasRemaining());
            new UpgradeResultListener().handleEvent(new PushBackStreamChannel(connection.getSourceChannel()));
        }
    }

    private final class UpgradeResultListener implements ChannelListener<PushBackStreamChannel> {

        private final HttpUpgradeParser parser = new HttpUpgradeParser();
        private ByteBuffer buffer = ByteBuffer.allocate(1024);

        @Override
        public void handleEvent(final PushBackStreamChannel channel) {
            int r;
            do {
                try {
                    buffer.compact();
                    r = channel.read(buffer);
                    if (r == 0) {
                        channel.getReadSetter().set(this);
                        channel.resumeReads();
                        return;
                    } else if (r == -1) {
                        throw new IOException("Connection closed reading response");
                    }
                    buffer.flip();
                    parser.parse(buffer);
                } catch (IOException e) {
                    future.setException(e);
                    return;
                }

            } while (!parser.isComplete());

            if (buffer.hasRemaining()) {
                channel.unget(new Pooled<ByteBuffer>() {
                    @Override
                    public void discard() {
                        buffer = null;
                    }

                    @Override
                    public void free() {
                        buffer = null;
                    }

                    @Override
                    public ByteBuffer getResource() throws IllegalStateException {
                        return buffer;
                    }
                });
            }

            //ok, we have a response
            if (parser.getResponseCode() == 101) {
                handleUpgrade(parser);
            } else if (parser.getResponseCode() == 301 ||
                    parser.getResponseCode() == 302 ||
                    parser.getResponseCode() == 303 ||
                    parser.getResponseCode() == 307 ||
                    parser.getResponseCode() == 308) {
                handleRedirect(parser);
            } else {
                future.setException(new IOException("Invalid response code " + parser.getResponseCode()));
            }

        }


    }

    private void handleUpgrade(final HttpUpgradeParser parser) {
        final String contentLength = parser.getHeaders().get("content-length");
        if (!"0".equals(contentLength)) {
            future.setException(new IOException("For now upgrade responses must have a content length of zero."));
            return;
        }
        if (handshakeChecker != null) {
            try {
                handshakeChecker.checkHandshake(headers, "");
            } catch (IOException e) {
                future.setException(e);
                return;
            }
        }
        future.setResult(connection);
        ChannelListeners.invokeChannelListener(connection, openListener);
    }

    private void handleRedirect(final HttpUpgradeParser parser) {
        future.setException(new IOException("Redirects are not implemented yet"));
    }


}
