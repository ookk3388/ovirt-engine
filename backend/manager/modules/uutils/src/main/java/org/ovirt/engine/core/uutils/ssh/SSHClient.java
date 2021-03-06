package org.ovirt.engine.core.uutils.ssh;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.naming.AuthenticationException;
import javax.naming.TimeLimitExceededException;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.sshd.ClientChannel;
import org.apache.sshd.ClientSession;
import org.apache.sshd.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.common.signature.SignatureRSA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSHClient implements Closeable {
    private static final String COMMAND_FILE_RECEIVE =
            "test -r '%2$s' && md5sum -b '%2$s' | cut -d ' ' -f 1 >&2 && %1$s < '%2$s'";
    private static final String COMMAND_FILE_SEND = "%1$s > '%2$s' && md5sum -b '%2$s' | cut -d ' ' -f 1 >&2";
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final int CONSTRAINT_BUFFER_SIZE = 1024;
    private static final int THREAD_JOIN_WAIT_TIME = 2000;
    private static final int DEFAULT_SSH_PORT = 22;

    private static final Logger log = LoggerFactory.getLogger(SSHClient.class);

    private SshClient client;
    private ClientSession session;
    private long softTimeout = 10000;
    private long hardTimeout = 0;
    private String user;
    private String password;
    private KeyPair keyPair;
    private String host;
    private int port = DEFAULT_SSH_PORT;
    private PublicKey hostKey;

    /**
     * Create the client for testing using org.mockito.Mockito.
     *
     * @return client.
     */
    SshClient createSshClient() {
        SshClient sshClient = SshClient.setUpDefaultClient();

        /*
         * FIXME: We need to enforce only RSA signatures, because all our code around fingerprints assumes only RSA
         * public keys. This limitation can be removed when we will save all available host public keys into database
         * and perform host key verification by comparing received key with keys in our database.
         */
        sshClient.setSignatureFactories(Arrays.asList(
                new SignatureRSA.Factory()));

        return sshClient;
    }

    /**
     * Check if file is valid.
     *
     * This is required as we use shell to pipe into file, so no special charachters are allowed.
     */
    private void remoteFileName(String file) {
        if (file.indexOf('\'') != -1 ||
                file.indexOf('\n') != -1 ||
                file.indexOf('\r') != -1) {
            throw new IllegalArgumentException("File name should not contain \"'\"");
        }
    }

    /**
     * Compare string disgest to digest.
     *
     * @param digest
     *            MessageDigest.
     * @param actual
     *            String digest.
     */
    private void validateDigest(MessageDigest digest, String actual) throws IOException {
        try {
            if (!Arrays.equals(
                    digest.digest(),
                    Hex.decodeHex(actual.toCharArray()))) {
                throw new IOException("SSH copy failed, invalid localDigest");
            }
        } catch (DecoderException e) {
            throw new IOException("SSH copy failed, invalid localDigest");
        }
    }

    /**
     * Destructor.
     */
    @Override
    protected void finalize() {
        try {
            close();
        } catch (IOException e) {
            log.error("Finalize exception", ExceptionUtils.getRootCauseMessage(e));
            log.debug("Exception", e);
        }
    }

    /**
     * Set soft timeout.
     *
     * @param softTimeout
     *            timeout for network activity.
     *
     *            default is 10 seconds.
     */
    public void setSoftTimeout(long softTimeout) {
        this.softTimeout = softTimeout;
    }

    /**
     * Set hard timeout.
     *
     * @param hardTimeout
     *            timeout for the entire transaction.
     *
     *            timeout of 0 is infinite.
     *
     *            The timeout is evaluate at softTimeout intervals.
     */
    public void setHardTimeout(long hardTimeout) {
        this.hardTimeout = hardTimeout;
    }

    /**
     * Set user.
     *
     * @param user
     *            user.
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Set password.
     *
     * @param password
     *            password.
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Set keypair.
     *
     * @param keyPair
     *            key pair.
     */
    public void setKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /**
     * Set host.
     *
     * @param host
     *            host.
     * @param port
     *            port.
     */
    public void setHost(String host, int port) {
        this.host = host;
        this.port = port;
        hostKey = null;
    }

    /**
     * Set host.
     *
     * @param host
     *            host.
     * @param port
     *            port.
     */
    public void setHost(String host, Integer port) {
        setHost(host, port == null ? DEFAULT_SSH_PORT : port);
    }

    /**
     * Set host.
     *
     * @param host
     *            host.
     */
    public void setHost(String host) {
        setHost(host, DEFAULT_SSH_PORT);
    }

    /**
     * Get host.
     *
     * @return host as set by setHost()
     */
    public String getHost() {
        return host;
    }

    /**
     * Get port.
     *
     * @return port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get hard timeout.
     *
     * @return timeout.
     */
    public long getHardTimeout() {
        return hardTimeout;
    }

    /**
     * Get soft timeout.
     *
     * @return timeout.
     */
    public long getSoftTimeout() {
        return softTimeout;
    }

    /**
     * Get user.
     *
     * @return user.
     */
    public String getUser() {
        return user;
    }

    public String getDisplayHost() {
        StringBuilder ret = new StringBuilder(100);
        if (host == null) {
            ret.append("N/A");
        } else {
            if (user != null) {
                ret.append(user);
                ret.append("@");
            }
            ret.append(host);
            if (port != DEFAULT_SSH_PORT) {
                ret.append(":");
                ret.append(port);
            }
        }
        return ret.toString();
    }

    /**
     * Get host key
     *
     * @return host key.
     */
    public PublicKey getHostKey() {
        return hostKey;
    }

    /**
     * Connect to host.
     */
    public void connect() throws Exception {

        log.debug("Connecting '{}'", this.getDisplayHost());

        try {
            client = createSshClient();

            client.setServerKeyVerifier(
                (sshClientSession, remoteAddress, serverKey) -> {
                    hostKey = serverKey;
                    return true;
                });

            client.start();

            ConnectFuture cfuture = client.connect(host, port);
            if (!cfuture.await(softTimeout)) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH connection timed out connecting to '%1$s'",
                                this.getDisplayHost()));
            }

            session = cfuture.getSession();

            /*
             * Wait for authentication phase so we have host key.
             */
            int stat = session.waitFor(
                    ClientSession.CLOSED |
                            ClientSession.WAIT_AUTH |
                            ClientSession.TIMEOUT,
                    softTimeout);
            if ((stat & ClientSession.CLOSED) != 0) {
                throw new IOException(
                        String.format(
                                "SSH session closed during connection '%1$s'",
                                this.getDisplayHost()));
            }
            if ((stat & ClientSession.TIMEOUT) != 0) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH timed out waiting for authentication request '%1$s'",
                                this.getDisplayHost()));
            }
        } catch (Exception e) {
            log.debug("Connect error", e);
            throw e;
        }

        log.debug("Connected: '{}'", this.getDisplayHost());
    }

    /**
     * Authenticate to host.
     */
    public void authenticate() throws Exception {

        log.debug("Authenticating: '{}'", this.getDisplayHost());

        try {
            AuthFuture afuture;
            if (keyPair != null) {
                afuture = session.authPublicKey(user, keyPair);
            } else if (password != null) {
                afuture = session.authPassword(user, password);
            } else {
                throw new AuthenticationException(
                        String.format(
                                "SSH authentication failure '%1$s', no password or key",
                                this.getDisplayHost()));
            }
            if (!afuture.await(softTimeout)) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH authentication timed out connecting to '%1$s'",
                                this.getDisplayHost()));
            }
            if (!afuture.isSuccess()) {
                throw new AuthenticationException(
                        String.format(
                                "SSH authentication to '%1$s' failed. Please verify provided credentials. %2$s",
                                this.getDisplayHost(),
                                keyPair == null ? "Make sure host is configured for password authentication"
                                        : "Make sure key is authorized at host"));
            }
        } catch (Exception e) {
            log.debug("Connect error", e);
            throw e;
        }

        log.debug("Authenticated: '{}'", this.getDisplayHost());
    }

    /**
     * Disconnect and cleanup.
     *
     * Must be called when done with client.
     */
    public void close() throws IOException {
        try {
            if (session != null) {
                session.close(true);
                session = null;
            }
            if (client != null) {
                client.stop();
                client = null;
            }
        } catch (Exception e) {
            log.error("Failed to close session", ExceptionUtils.getRootCauseMessage(e));
            log.debug("Exception", e);
            throw new IOException(e);
        }
    }

    /**
     * Execute generic command.
     *
     * @param command
     *            command to execute.
     * @param in
     *            stdin.
     * @param out
     *            stdout.
     * @param err
     *            stderr.
     */
    public void executeCommand(
            String command,
            InputStream in,
            OutputStream out,
            OutputStream err) throws Exception {

        log.debug("Executing: '{}'", command);

        if (in == null) {
            in = new ByteArrayInputStream(new byte[0]);
        }
        if (out == null) {
            out = new ConstraintByteArrayOutputStream(CONSTRAINT_BUFFER_SIZE);
        }
        if (err == null) {
            err = new ConstraintByteArrayOutputStream(CONSTRAINT_BUFFER_SIZE);
        }

        /*
         * Redirect streams into indexed streams.
         */
        ClientChannel channel = null;
        try (
                final ProgressInputStream iin = new ProgressInputStream(in);
                final ProgressOutputStream iout = new ProgressOutputStream(out);
                final ProgressOutputStream ierr = new ProgressOutputStream(err)) {
            channel = session.createExecChannel(command);
            channel.setIn(iin);
            channel.setOut(iout);
            channel.setErr(ierr);
            channel.open();

            long hardEnd = 0;
            if (hardTimeout != 0) {
                hardEnd = System.currentTimeMillis() + hardTimeout;
            }

            boolean hardTimeout = false;
            int stat;
            boolean activity;
            do {
                stat = channel.waitFor(
                        ClientChannel.CLOSED |
                                ClientChannel.EOF |
                                ClientChannel.TIMEOUT,
                        softTimeout);

                hardTimeout = hardEnd != 0 && System.currentTimeMillis() >= hardEnd;

                /*
                 * Notice that we should visit all so do not cascade statement.
                 */
                activity = iin.wasProgress();
                activity = iout.wasProgress() || activity;
                activity = ierr.wasProgress() || activity;
            } while (!hardTimeout &&
                    (stat & ClientChannel.TIMEOUT) != 0 &&
                    activity);

            if (hardTimeout) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH session hard timeout host '%1$s'",
                                this.getDisplayHost()));
            }

            if ((stat & ClientChannel.TIMEOUT) != 0) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH session timeout host '%1$s'",
                                this.getDisplayHost()));
            }

            stat = channel.waitFor(
                    ClientChannel.CLOSED |
                            ClientChannel.EXIT_STATUS |
                            ClientChannel.EXIT_SIGNAL |
                            ClientChannel.TIMEOUT,
                    softTimeout);

            if ((stat & ClientChannel.EXIT_SIGNAL) != 0) {
                throw new IOException(
                        String.format(
                                "Signal received during SSH session host '%1$s'",
                                this.getDisplayHost()));
            }

            if ((stat & ClientChannel.EXIT_STATUS) != 0 && channel.getExitStatus() != 0) {
                throw new IOException(
                        String.format(
                                "Command returned failure code %2$d during SSH session '%1$s'",
                                this.getDisplayHost(),
                                channel.getExitStatus()));
            }

            if ((stat & ClientChannel.TIMEOUT) != 0) {
                throw new TimeLimitExceededException(
                        String.format(
                                "SSH session timeout waiting for status host '%1$s'",
                                this.getDisplayHost()));
            }

            // the PipedOutputStream does not
            // flush streams at close
            // this leads other side of pipe
            // to miss last bytes
            // not sure why it is required as
            // FilteredOutputStream does flush
            // on close.
            out.flush();
            err.flush();
        } catch (RuntimeException e) {
            log.error("Execute failed", ExceptionUtils.getRootCauseMessage(e));
            log.debug("Exception", e);
            throw e;
        } finally {
            if (channel != null) {
                int stat = channel.waitFor(
                        ClientChannel.CLOSED |
                                ClientChannel.TIMEOUT,
                        1);
                if ((stat & ClientChannel.CLOSED) != 0) {
                    channel.close(true);
                }
            }
        }

        log.debug("Executed: '{}'", command);
    }

    /**
     * Send file using compression and digest check.
     *
     * We read the file content into gzip and then pipe it into the ssh. Calculating the remoteDigest on the fly.
     *
     * The digest is printed into stderr for us to collect.
     *
     * @param file1
     *            source.
     * @param file2
     *            destination.
     *
     *
     */
    public void sendFile(String file1, String file2) throws Exception {
        log.debug("Sending: '{}' '{}'", file1, file2);

        remoteFileName(file2);

        MessageDigest localDigest = MessageDigest.getInstance("MD5");

        // file1->{}->digest->in->out->pout->pin->stdin
        Thread t = null;
        try (
                final InputStream in = new DigestInputStream(
                        new FileInputStream(file1),
                        localDigest);
                final PipedInputStream pin = new PipedInputStream(STREAM_BUFFER_SIZE);
                final OutputStream pout = new PipedOutputStream(pin);
                final OutputStream dummy = new ConstraintByteArrayOutputStream(CONSTRAINT_BUFFER_SIZE);
                final ByteArrayOutputStream remoteDigest =
                        new ConstraintByteArrayOutputStream(CONSTRAINT_BUFFER_SIZE)) {
            t = new Thread(
                    () -> {
                        try (OutputStream out = new GZIPOutputStream(pout)) {
                            byte[] b = new byte[STREAM_BUFFER_SIZE];
                            int n;
                            while ((n = in.read(b)) != -1) {
                                out.write(b, 0, n);
                            }
                        } catch (IOException e) {
                            log.debug("Exceution during stream processing", e);
                        }
                    } ,
                    "SSHClient.compress " + file1);
            t.start();

            executeCommand(
                    String.format(COMMAND_FILE_SEND, "gunzip -q", file2),
                    pin,
                    dummy,
                    remoteDigest);

            t.join(THREAD_JOIN_WAIT_TIME);
            if (t.getState() != Thread.State.TERMINATED) {
                throw new IllegalStateException("Cannot stop SSH stream thread");
            }

            validateDigest(localDigest, new String(remoteDigest.toByteArray(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            log.debug("Send failed", e);
            throw e;
        } finally {
            if (t != null) {
                t.interrupt();
            }
        }

        log.debug("Sent: '{}' '{}'", file1, file2);
    }

    /**
     * Receive file using compression and localDigest check.
     *
     * We read the stream and pipe into gunzip, and write into the file. Calculating the remoteDigest on the fly.
     *
     * The localDigest is printed into stderr for us to collect.
     *
     * @param file1
     *            source.
     * @param file2
     *            destination.
     *
     */
    public void receiveFile(String file1, String file2) throws Exception {
        log.debug("Receiving: '{}' '{}'", file1, file2);

        remoteFileName(file1);

        MessageDigest localDigest = MessageDigest.getInstance("MD5");

        // stdout->pout->pin->in->out->digest->{}->file2
        Thread t = null;
        try (
                final PipedOutputStream pout = new PipedOutputStream();
                final InputStream pin = new PipedInputStream(pout, STREAM_BUFFER_SIZE);
                final OutputStream out = new DigestOutputStream(
                        new FileOutputStream(file2),
                        localDigest);
                final InputStream empty = new ByteArrayInputStream(new byte[0]);
                final ByteArrayOutputStream remoteDigest =
                        new ConstraintByteArrayOutputStream(CONSTRAINT_BUFFER_SIZE)) {
            t = new Thread(
                    () -> {
                        try (final InputStream in = new GZIPInputStream(pin)) {

                            byte[] b = new byte[STREAM_BUFFER_SIZE];
                            int n;
                            while ((n = in.read(b)) != -1) {
                                out.write(b, 0, n);
                            }
                        } catch (IOException e) {
                            log.debug("Exceution during stream processing", e);
                        }
                    } ,
                    "SSHClient.decompress " + file2);
            t.start();

            executeCommand(
                    String.format(COMMAND_FILE_RECEIVE, "gzip -q", file1),
                    empty,
                    pout,
                    remoteDigest);

            t.join(THREAD_JOIN_WAIT_TIME);
            if (t.getState() != Thread.State.TERMINATED) {
                throw new IllegalStateException("Cannot stop SSH stream thread");
            }

            validateDigest(localDigest, new String(remoteDigest.toByteArray(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            log.debug("Receive failed", e);
            throw e;
        } finally {
            if (t != null) {
                t.interrupt();
            }
        }

        log.debug("Received: '{}' '{}'", file1, file2);
    }
}
