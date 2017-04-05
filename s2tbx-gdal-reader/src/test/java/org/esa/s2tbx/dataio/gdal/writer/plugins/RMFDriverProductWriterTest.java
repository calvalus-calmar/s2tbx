package org.esa.s2tbx.dataio.gdal.writer.plugins;

import org.esa.s2tbx.dataio.gdal.reader.plugins.RMFDriverProductReaderPlugIn;

/**
 * @author Jean Coravu
 */
public class RMFDriverProductWriterTest extends AbstractTestDriverProductWriter {

    public RMFDriverProductWriterTest() {
        super("RMF", ".rsw", "Byte Int16 Int32 Float64", new RMFDriverProductReaderPlugIn(), new RMFDriverProductWriterPlugIn());
    }
}
