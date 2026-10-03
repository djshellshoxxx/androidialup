package io.circuitdrift.androidialup.platform.dte;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Device-specific privileged provider for a serial USB gadget endpoint such as Linux CDC-ACM
 * backed by /dev/ttyGS*. The shared AndroidDialup code never assumes configfs paths, UDC names,
 * root commands or SELinux policy; a concrete system/root/vendor integration supplies this
 * interface only on supported builds.
 */
public interface SerialGadgetProvider {

    /** Opens a configured serial gadget byte endpoint. */
    Endpoint open() throws IOException;

    interface Endpoint extends AutoCloseable {
        InputStream input();
        OutputStream output();
        Capabilities capabilities();
        @Override void close() throws IOException;
    }

    /** Real modem-control signals exposed by the provider. Unsupported signals remain false. */
    record Capabilities(boolean dtrInput, boolean dcdOutput, boolean rtsInput, boolean ctsOutput) {
        public static final Capabilities NONE = new Capabilities(false, false, false, false);
    }
}
