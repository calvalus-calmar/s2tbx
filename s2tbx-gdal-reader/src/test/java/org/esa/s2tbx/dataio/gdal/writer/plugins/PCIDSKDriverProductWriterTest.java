package org.esa.s2tbx.dataio.gdal.writer.plugins;

import org.esa.s2tbx.dataio.gdal.reader.plugins.PCIDSKDriverProductReaderPlugIn;

/**
 * @author Jean Coravu
 */
public class PCIDSKDriverProductWriterTest extends AbstractTestDriverProductWriter {

    public PCIDSKDriverProductWriterTest() {
        super("PCIDSK", ".pix", "Byte UInt16 Int16 Float32 CInt16 CFloat32", new PCIDSKDriverProductReaderPlugIn(), new PCIDSKDriverProductWriterPlugIn());
    }
}
