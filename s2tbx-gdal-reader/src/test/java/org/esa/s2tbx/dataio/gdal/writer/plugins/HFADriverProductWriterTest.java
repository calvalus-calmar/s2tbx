package org.esa.s2tbx.dataio.gdal.writer.plugins;

import org.esa.s2tbx.dataio.gdal.reader.plugins.HFADriverProductReaderPlugIn;

/**
 * @author Jean Coravu
 */
public class HFADriverProductWriterTest extends AbstractTestDriverProductWriter {

    public HFADriverProductWriterTest() {
        super("HFA", ".img", "Byte Int16 UInt16 Int32 UInt32 Float32 Float64 CFloat32 CFloat64", new HFADriverProductReaderPlugIn(), new HFADriverProductWriterPlugIn());
    }
}
