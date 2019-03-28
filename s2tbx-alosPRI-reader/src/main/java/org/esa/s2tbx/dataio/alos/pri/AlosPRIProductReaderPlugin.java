package org.esa.s2tbx.dataio.alos.pri;

import org.esa.s2tbx.dataio.alos.pri.internal.AlosPRIConstants;
import org.esa.s2tbx.dataio.readers.BaseProductReaderPlugIn;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.RGBImageProfile;
import org.esa.snap.core.datamodel.RGBImageProfileManager;

import java.util.Locale;

/**
 * Plugin for reading ALOS PRISM files.
 * The files are GeoTIFF with DIMAP metadata
 *
 * @author Denisa Stefanescu
 */

public class AlosPRIProductReaderPlugin extends BaseProductReaderPlugIn {
    private static final String COLOR_PALETTE_FILE_NAME = "AlosPRI_color_palette.cpd";

    public AlosPRIProductReaderPlugin() {
        super("org/esa/s2tbx/dataio/alos/pri/" + AlosPRIProductReaderPlugin.COLOR_PALETTE_FILE_NAME);
    }

    @Override
    public Class[] getInputTypes() {
        return AlosPRIConstants.READER_INPUT_TYPES;
    }

    @Override
    public ProductReader createReaderInstance() {
        return new AlosPRIProductReader(this);
    }

    @Override
    public String[] getFormatNames() {
        return AlosPRIConstants.FORMAT_NAMES;
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return AlosPRIConstants.DEFAULT_EXTENSIONS;
    }

    @Override
    public String getDescription(Locale locale) {
        return AlosPRIConstants.DESCRIPTION;
    }

    @Override
    protected String[] getMinimalPatternList() {
        return AlosPRIConstants.MINIMAL_PRODUCT_PATTERNS;
    }

    @Override
    protected String[] getExclusionPatternList() {
        return new String[0];
    }

    @Override
    protected void registerRGBProfile() {
        RGBImageProfileManager.getInstance().addProfile(new RGBImageProfile("Alos PRISM", new String[]{"Band_1(pan)", "Band_1(pan)", "Band_1(pan)"}));
    }
}
