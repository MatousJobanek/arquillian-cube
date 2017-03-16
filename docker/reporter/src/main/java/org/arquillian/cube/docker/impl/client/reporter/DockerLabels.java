package org.arquillian.cube.docker.impl.client.reporter;

import org.arquillian.reporter.api.model.entry.data.Label;

import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.LIMIT;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.MAX_USAGE;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.USAGE;

/**
 * @author <a href="mailto:mjobanek@redhat.com">Matous Jobanek</a>
 */
public class DockerLabels {

    public static final Label ADAPTER_LABEL = new Label("Adapter");
    public static final Label RX_BYTES_LABEL = new Label("rx_bytes");
    public static final Label TX_BYTES_LABEL = new Label("tx_bytes");
    public static final Label BEFORE_TEST_LABEL = new Label("Before Test");
    public static final Label AFTER_TEST_LABEL = new Label("After Test");
    public static final Label USE_LABEL = new Label("Use");


    public static final Label USAGE_LABEL = new Label(USAGE);
    public static final Label MAX_USAGE_LABEL = new Label(MAX_USAGE);
    public static final Label LIMIT_LABEL = new Label(LIMIT);

    public static final Label IO_BYTES_READ_LABEL = new Label("io_bytes_read");
    public static final Label IO_BYTES_WRITE_LABEL = new Label("io_bytes_write");
}
