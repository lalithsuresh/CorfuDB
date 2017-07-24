package org.corfudb.integration;

import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.clients.IClientRouter;
import org.corfudb.runtime.clients.LayoutClient;
import org.corfudb.runtime.clients.NettyClientRouter;
import org.corfudb.runtime.collections.SMRMap;
import org.corfudb.runtime.view.Layout;
import org.corfudb.runtime.view.stream.IStreamView;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for clojure cmdlets.
 * TODO: Implement few tests with TLS options too.
 * FIXME: Cmdlets logunit read do not work if serverEpoch > 1
 * <p>
 * Created by zlokhandwala on 5/8/17.
 */
public class CmdletIT extends AbstractIT {

    // Using port 9901 to avoid intellij port conflict.
    private final int BASE_PORT = 9901;
    private int portCounter;

    @Before
    public void incrementPortCounter() {
        portCounter++;
    }

    // We stop & start several servers. Avoid reusing TCP ports.
    private int getPort() {
        return BASE_PORT + portCounter;
    }

    private String getEndpoint() {
        return DEFAULT_HOST + ":" + getPort();
    }

    private Layout getSingleLayout() {
        return new Layout(
                Collections.singletonList(getEndpoint()),
                Collections.singletonList(getEndpoint()),
                Collections.singletonList(new Layout.LayoutSegment(Layout.ReplicationMode.CHAIN_REPLICATION,
                        0L,
                        -1L,
                        Collections.singletonList(new Layout.LayoutStripe(Collections.singletonList(getEndpoint()))))),
                Collections.EMPTY_LIST,
                0L);
    }

    static public String runCmdletGetOutput(String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
        builder.redirectErrorStream(true);
        Process cmdlet = builder.start();
        final StringBuilder output = new StringBuilder();

        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(cmdlet.getInputStream()))) {
            String line = null;
            while ((line = bufferedReader.readLine()) != null) {
                output.append(line);
            }
            cmdlet.waitFor();
        }
        return output.toString();
    }

    private Process corfuServerProcess;

    /**
     * Testing corfu_layout.
     *
     * @throws Exception
     */
    @Test
    public void testCorfuLayoutCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String command = CORFU_PROJECT_DIR + "bin/corfu_layout " + getEndpoint();
        assertThat(runCmdletGetOutput(command).contains(getSingleLayout().toString()))
                .isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    /**
     * Testing corfu_ping
     *
     * @throws Exception
     */
    @Test
    public void testCorfuPingCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String command = CORFU_PROJECT_DIR + "bin/corfu_ping " + getEndpoint();
        final String expectedSubString = "PING " + getEndpoint() + "ACK";
        assertThat(runCmdletGetOutput(command).contains(expectedSubString))
                .isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    /**
     * Testing corfu_layouts query
     * TODO: Not testing corfu_layouts -c <endpoint> edit
     *
     * @throws Exception
     */
    @Test
    public void testCorfuLayoutsCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String command = CORFU_PROJECT_DIR + "bin/corfu_layouts -c " + getEndpoint() + " query";
        // Squashing all spaces to compare JSON.
        assertThat(runCmdletGetOutput(command).replace(" ", "")
                .contains(getSingleLayout().asJSONString()))
                .isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    /**
     * Testing corfu_query
     *
     * @throws Exception
     */
    @Test
    public void testCorfuQueryCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner()
                .setPort(getPort())
                .setLogPath(getCorfuServerLogPath(DEFAULT_HOST, getPort()))
                .runServer();

        final String command = CORFU_PROJECT_DIR + "bin/corfu_query " + getEndpoint();
        final String expectedLogPath = "--log-path=" + CORFU_LOG_PATH;
        final String expectedInitialToken = "--initial-token=-1";
        final String expectedStartupArgs = new CorfuServerRunner()
                .setPort(getPort())
                .setLogPath(getCorfuServerLogPath(DEFAULT_HOST, getPort()))
                .getOptionsString();
        String output = runCmdletGetOutput(command);
        System.out.println(output);
        assertThat(output.contains(expectedLogPath)).isTrue();
        assertThat(output.contains(expectedInitialToken)).isTrue();
        assertThat(output.contains(expectedStartupArgs)).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    /**
     * Testing corfu_stream append and read.
     *
     * @throws Exception
     */
    @Test
    public void testCorfuStreamCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String streamA = "streamA";
        CorfuRuntime runtime = createRuntime(getEndpoint());
        IStreamView streamViewA = runtime.getStreamsView().get(CorfuRuntime.getStreamID(streamA));

        String payload1 = "Hello";
        streamViewA.append(payload1.getBytes());

        String commandRead = CORFU_PROJECT_DIR + "bin/corfu_stream -i " + streamA + " -c " + getEndpoint() + " read";
        String output = runCmdletGetOutput(commandRead);
        assertThat(output.contains(payload1)).isTrue();

        String payload2 = "World";
        String commandAppend = "echo '" + payload2 + "' | " + CORFU_PROJECT_DIR + "bin/corfu_stream -i " + streamA + " -c " + getEndpoint() + " append";
        runCmdletGetOutput(commandAppend);

        assertThat(streamViewA.next().getPayload(runtime)).isEqualTo(payload1.getBytes());
        assertThat(streamViewA.next().getPayload(runtime)).isEqualTo((payload2 + "\n").getBytes());
        assertThat(streamViewA.next()).isNull();
        shutdownCorfuServer(corfuServerProcess);
    }

    /**
     * Testing corfu_sequencer next-token and latest
     *
     * @throws Exception
     */
    @Test
    public void testCorfuSequencerCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String streamA = "streamA";
        CorfuRuntime runtime = createRuntime(getEndpoint());

        String commandNextToken = CORFU_PROJECT_DIR + "bin/corfu_sequencer -i " + streamA + " -c " + getEndpoint() + " next-token 3";
        runCmdletGetOutput(commandNextToken);

        Token token = runtime.getSequencerView()
                .nextToken(Collections.singleton(CorfuRuntime.getStreamID(streamA)), 0)
                .getToken();

        String commandLatest = CORFU_PROJECT_DIR + "bin/corfu_sequencer -i " + streamA + " -c " + getEndpoint() + " latest";
        String output = runCmdletGetOutput(commandLatest);
        assertThat(output.contains(token.toString())).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuAddressSpaceCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String streamA = "streamA";
        String payload = "Hello";
        String commandAppend = "echo '" + payload + "' | " + CORFU_PROJECT_DIR + "bin/corfu_as -i " + streamA + " -c " + getEndpoint() + " write 0";
        runCmdletGetOutput(commandAppend);
        String commandRead = CORFU_PROJECT_DIR + "bin/corfu_as -i " + streamA + " -c " + getEndpoint() + " read 0";
        assertThat(runCmdletGetOutput(commandRead).contains(payload)).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuHandleFailuresCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String command = CORFU_PROJECT_DIR + "bin/corfu_handle_failures -c " + getEndpoint();
        final String expectedSubString = "Failure handler on " + getEndpoint() + " started.Initiation completed !";
        assertThat(runCmdletGetOutput(command).contains(expectedSubString))
                .isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuLogunitCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        String payload = "Hello";
        String commandAppend = "echo '" + payload + "' | " + CORFU_PROJECT_DIR + "bin/corfu_logunit " + getEndpoint() + " write 0";
        runCmdletGetOutput(commandAppend);

        String commandRead = CORFU_PROJECT_DIR + "bin/corfu_logunit " + getEndpoint() + " read 0";
        assertThat(runCmdletGetOutput(commandRead).contains(payload)).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuResetCmdlet() throws Exception {

        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        final String expectedOutput = "Reset " + getEndpoint() + ":ACK";
        String commandRead = CORFU_PROJECT_DIR + "bin/corfu_reset " + getEndpoint();
        assertThat(runCmdletGetOutput(commandRead).contains(expectedOutput)).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuBootstrapCluster() throws Exception {
        corfuServerProcess = new CorfuServerRunner()
                .setPort(getPort())
                .setSingle(false)
                .runServer(false);
        File layoutFile = new File(CORFU_LOG_PATH + File.separator + "layoutFile");
        layoutFile.createNewFile();
        try (FileOutputStream fos = new FileOutputStream(layoutFile)) {
            fos.write(getSingleLayout().asJSONString().getBytes());
        }
        String command = CORFU_PROJECT_DIR + "bin/corfu_bootstrap_cluster -l " + layoutFile.getAbsolutePath();
        String expectedOutput = "New layout installed";

        assertThat(runCmdletGetOutput(command).contains(expectedOutput)).isTrue();
        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuBootstrapClusterWithStream() throws Exception {
        corfuServerProcess = new CorfuServerRunner()
                .setPort(getPort())
                .setSingle(false)
                .runServer(false);
        File layoutFile = new File(CORFU_LOG_PATH + File.separator + "layoutFile");
        layoutFile.createNewFile();
        try (FileOutputStream fos = new FileOutputStream(layoutFile)) {
            fos.write(getSingleLayout().asJSONString().getBytes());
        }
        String command = CORFU_PROJECT_DIR + "bin/corfu_bootstrap_cluster -l " + layoutFile.getAbsolutePath();
        String expectedOutput = "New layout installed";

        assertThat(runCmdletGetOutput(command).contains(expectedOutput)).isTrue();

        CorfuRuntime runtime = createRuntime(getEndpoint());
        IStreamView streamViewA = runtime.getStreamsView().get(CorfuRuntime.getStreamID("streamA"));
        assertThat(streamViewA.hasNext())
                .isFalse();

        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuManagementBootstrap() throws Exception {
        corfuServerProcess = new CorfuServerRunner()
                .setPort(getPort())
                .setSingle(false)
                .runServer(false);
        File layoutFile = new File(CORFU_LOG_PATH + File.separator + "layoutFile");
        layoutFile.createNewFile();
        try (FileOutputStream fos = new FileOutputStream(layoutFile)) {
            fos.write(getSingleLayout().asJSONString().getBytes());
        }
        String command = CORFU_PROJECT_DIR + "bin/corfu_management_bootstrap -c " + getEndpoint() + " -l " + layoutFile.getAbsolutePath();
        String expectedOutput = getEndpoint() + " bootstrapped successfully";

        assertThat(runCmdletGetOutput(command).contains(expectedOutput)).isTrue();

        shutdownCorfuServer(corfuServerProcess);
    }

    @Test
    public void testCorfuSMRObject() throws Exception {
        corfuServerProcess = new CorfuServerRunner().setPort(getPort()).runServer();
        String streamA = "streamA";
        String payload = "helloWorld";
        final String commandPut = CORFU_PROJECT_DIR + "bin/corfu_smrobject" +
                " -i " + streamA +
                " -c " + getEndpoint() +
                " " + SMRMap.class.getCanonicalName() + " putIfAbsent x " + payload;
        runCmdletGetOutput(commandPut);

        final String commandGet = CORFU_PROJECT_DIR + "bin/corfu_smrobject" +
                " -i " + streamA +
                " -c " + getEndpoint() +
                " " + SMRMap.class.getCanonicalName() + " getOrDefault x none";

        assertThat(runCmdletGetOutput(commandGet).contains(payload)).isTrue();

        shutdownCorfuServer(corfuServerProcess);
    }

}
