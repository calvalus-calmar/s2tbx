/*
 * Copyright (C) 2014-2015 CS-SI (foss-contact@thor.si.c-s.fr)
 * Copyright (C) 2013-2015 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.s2tbx.dataio.s2;

import com.bc.ceres.glevel.MultiLevelImage;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.commons.math3.util.Pair;
import org.esa.s2tbx.commons.FilePath;
import org.esa.s2tbx.dataio.Utils;
import org.esa.s2tbx.dataio.jp2.JP2ImageFile;
import org.esa.s2tbx.dataio.jp2.TileLayout;
import org.esa.s2tbx.dataio.openjpeg.OpenJpegUtils;
import org.esa.s2tbx.dataio.s2.filepatterns.INamingConvention;
import org.esa.s2tbx.dataio.s2.filepatterns.S2GranuleDirFilename;
import org.esa.s2tbx.dataio.s2.filepatterns.S2NamingConventionUtils;
import org.esa.s2tbx.dataio.s2.l1b.AbstractS2ProductMetadataReader;
import org.esa.s2tbx.dataio.s2.l1b.filepaterns.S2L1BGranuleDirFilename;
import org.esa.s2tbx.dataio.s2.l1b.tiles.TileIndexBandMatrixCell;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.quicklooks.Quicklook;
import org.esa.snap.core.image.MosaicMatrix;
import org.esa.snap.core.util.ResourceInstaller;
import org.esa.snap.core.util.SystemUtils;

import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.esa.s2tbx.dataio.openjpeg.OpenJpegUtils.validateOpenJpegExecutables;

/**
 * Base class for all Sentinel-2 product readers
 *
 * @author Nicolas Ducoin
 */
public abstract class Sentinel2ProductReader extends AbstractProductReader {

    protected static final Logger logger = Logger.getLogger(Sentinel2ProductReader.class.getName());

    //TODO Jean remove the attribute
    @Deprecated
    private S2Config config;

    private Path cacheDir;
    private Product product;

    //TODO Jean remove the attribute
    @Deprecated
    protected INamingConvention namingConvention;

    protected Sentinel2ProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);

        this.config = null;//new S2Config();
    }

    /**
     * For a given resolution, gets the list of band names.
     * For example, for 10m L1C, {"B02", "B03", "B04", "B08"} should be returned
     *
     * @param resolution the resolution for which the band names should be returned
     * @return then band names or {@code null} if not applicable.
     */
    protected abstract String[] getBandNames(S2SpatialResolution resolution);

    /**
     * @return The configuration file specific to a product reader
     */
    //TODO Jean remove
    @Deprecated
    public S2Config getConfig() {
        return config;
    }

    public S2SpatialResolution getProductResolution() {
        return S2SpatialResolution.R10M;
    }

    public boolean isMultiResolution() {
        return true;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    protected Product readProduct(boolean isGranule, S2Metadata metadataHeader) throws Exception {
        return null;
    }

    protected abstract String getReaderCacheDir();

    protected void initCacheDir(VirtualPath productPath) throws IOException {
        Path versionFile = ResourceInstaller.findModuleCodeBasePath(getClass()).resolve("version/version.properties");
        Properties versionProp = new Properties();
        try (InputStream inputStream = Files.newInputStream(versionFile)) {
            versionProp.load(inputStream);
        }
        String version = versionProp.getProperty("project.version");
        if (version == null) {
            throw new IOException("Unable to get project.version property from " + versionFile);
        }
        String fullPathString = productPath.getFullPathString();
        String md5sum = Utils.getMD5sum(fullPathString);
        if (md5sum == null) {
            throw new IOException("Unable to get md5sum of path " + fullPathString);
        }
        String readerDirName = getReaderCacheDir();
        String productName = productPath.getFileName().toString();
        Path cacheFolderPath = SystemUtils.getCacheDir().toPath();
        cacheFolderPath = cacheFolderPath.resolve("s2tbx");
        cacheFolderPath = cacheFolderPath.resolve(readerDirName);
        cacheFolderPath = cacheFolderPath.resolve(version);
        cacheFolderPath = cacheFolderPath.resolve(md5sum);
        cacheFolderPath = cacheFolderPath.resolve(productName);
        this.cacheDir = cacheFolderPath;
        if (!Files.exists(this.cacheDir)) {
            Files.createDirectories(this.cacheDir);
        }
        if (!Files.exists(this.cacheDir) || !Files.isDirectory(this.cacheDir) || !Files.isWritable(this.cacheDir)) {
            throw new IOException("Can't access package cache directory");
        }
        logger.fine("Successfully set up cache dir for product " + productName + " to " + this.cacheDir.toString());
    }

    //TODO Jean remove
    @Deprecated
    protected Product buildMosaicProduct(VirtualPath inputVirtualPath) throws IOException {
        return null;
    }

    protected AbstractS2ProductMetadataReader buildProductMetadata(VirtualPath virtualPath) throws IOException {
        return null;
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        if (!validateOpenJpegExecutables(S2Config.OPJ_INFO_EXE, S2Config.OPJ_DECOMPRESSOR_EXE)) {
            throw new IllegalStateException("Invalid OpenJpeg executables.");
        }
        boolean success = false;
        try {
            Object inputObject = getInput();

            VirtualPath virtualPath;
            if (inputObject instanceof File) {
                File inputFile = (File) inputObject;
                Path inputPath = S2ProductNamingUtils.processInputPath(inputFile.toPath());
                virtualPath = S2NamingConventionUtils.transformToSentinel2VirtualPath(inputPath);
            } else if (inputObject instanceof VirtualPath) {
                virtualPath = (VirtualPath) getInput();
            } else if (inputObject instanceof Path) {
                Path inputPath = S2ProductNamingUtils.processInputPath((Path) inputObject);
                virtualPath = S2NamingConventionUtils.transformToSentinel2VirtualPath(inputPath);
            } else {
                throw new IllegalArgumentException("Unknown input type '" + inputObject + "'.");
            }

            AbstractS2ProductMetadataReader productMetadata = buildProductMetadata(virtualPath);

            //TODO Jean remove: this.namingConvention
            this.namingConvention = productMetadata.getNamingConvention();

            VirtualPath inputVirtualPath = productMetadata.getNamingConvention().getInputXml();
            if (inputVirtualPath.exists()) {
                S2Config config = productMetadata.readTileLayouts(inputVirtualPath, productMetadata.isGranule());
                if (config == null) {
                    throw new NullPointerException(String.format("Unable to retrieve the JPEG tile layout associated to product [%s]", inputVirtualPath.getFileName().toString()));
                }

                //TODO Jean remove: this.config = config;
                this.config = config;

                S2Metadata metadataHeader = productMetadata.readMetadataHeader(inputVirtualPath, config);

                this.product = readProduct(productMetadata.isGranule(), metadataHeader);

                File productFileLocation;
                if (inputVirtualPath.getVirtualDir().isArchive()) {
                    productFileLocation = inputVirtualPath.getVirtualDir().getBaseFile();
                } else {
                    productFileLocation = inputVirtualPath.getFilePath().getPath().toFile();
                }
                this.product.setFileLocation(productFileLocation);

                Path qlFile = getQuickLookFile(inputVirtualPath);
                if (qlFile != null) {
                    this.product.getQuicklookGroup().add(new Quicklook(product, Quicklook.DEFAULT_QUICKLOOK_NAME, qlFile.toFile()));
                }
            } else {
                throw new FileNotFoundException(inputVirtualPath.getFullPathString());
            }

            success = true;

            return product;
        } catch (RuntimeException | IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException(exception);
        } finally {
            if (!success) {
                //closeResources();
            }
        }
    }

    private Path getQuickLookFile(VirtualPath inputVirtualPath) throws IOException {
        VirtualPath parentPath = inputVirtualPath.getParent();
        if (parentPath != null) {
            String[] files = parentPath.list();
            if (files != null && files.length > 0) {
                for (String relativePath : files) {
                    if (relativePath.endsWith(".png") && (relativePath.startsWith("S2") || relativePath.startsWith("BWI_"))) {
                        return inputVirtualPath.resolveSibling(relativePath).getLocalFile();
                    }
                }
            }
        }
        return null;
    }

    /**
     * update the tile layout in S2Config
     *
     * @param metadataFilePath the path to the product metadata file
     * @param isGranule        true if it is the metadata file of a granule
     * @return false when every tileLayout is null
     */
    //TODO Jean remove the method
    @Deprecated
    protected final boolean updateTileLayout(VirtualPath metadataFilePath, boolean isGranule) {
        boolean valid = false;
        for (S2SpatialResolution layoutResolution : S2SpatialResolution.values()) {
            TileLayout tileLayout;
            if (isGranule) {
                tileLayout = retrieveTileLayoutFromGranuleMetadataFile(metadataFilePath, layoutResolution);
            } else {
                tileLayout = retrieveTileLayoutFromProduct(metadataFilePath, layoutResolution);
            }
            this.config.updateTileLayout(layoutResolution, tileLayout);
            if (tileLayout != null) {
                valid = true;
            }
        }
        return valid;
    }

    /**
     * update the tile layout in S2Config
     *
     * @param metadataFilePath the path to the product metadata file
     * @param isGranule        true if it is the metadata file of a granule
     * @return false when every tileLayout is null
     */
    private S2Config readTileLayouts(VirtualPath metadataFilePath, boolean isGranule) {
        S2Config config = null;
        for (S2SpatialResolution layoutResolution : S2SpatialResolution.values()) {
            TileLayout tileLayout;
            if (isGranule) {
                tileLayout = retrieveTileLayoutFromGranuleMetadataFile(metadataFilePath, layoutResolution);
            } else {
                tileLayout = retrieveTileLayoutFromProduct(metadataFilePath, layoutResolution);
            }
            if (tileLayout != null) {
                if (config == null) {
                    config = new S2Config();
                }
                config.updateTileLayout(layoutResolution, tileLayout);
            }
        }
        return config;
    }

    /**
     * From a granule path, search a jpeg file for the given resolution, extract tile layout
     * information and update
     *
     * @param granuleMetadataFilePath the complete path to the granule metadata file
     * @param resolution              the resolution for which we wan to find the tile layout
     * @return the tile layout for the resolution, or {@code null} if none was found
     */
    public final TileLayout retrieveTileLayoutFromGranuleMetadataFile(VirtualPath granuleMetadataFilePath, S2SpatialResolution resolution) {
        TileLayout tileLayoutForResolution = null;
        if (granuleMetadataFilePath.exists() && granuleMetadataFilePath.getFileName().toString().endsWith(".xml")) {
            VirtualPath granuleDirPath = granuleMetadataFilePath.getParent();
            tileLayoutForResolution = retrieveTileLayoutFromGranuleDirectory(granuleDirPath, resolution);
        }
        return tileLayoutForResolution;
    }

    /**
     * From a product path, search a jpeg file for the given resolution, extract tile layout
     * information and update
     *
     * @param productMetadataFilePath the complete path to the product metadata file
     * @param resolution              the resolution for which we wan to find the tile layout
     * @return the tile layout for the resolution, or {@code null} if none was found
     */
    public final TileLayout retrieveTileLayoutFromProduct(VirtualPath productMetadataFilePath, S2SpatialResolution resolution) {
        TileLayout tileLayoutForResolution = null;
        if (productMetadataFilePath.exists() && productMetadataFilePath.getFileName().toString().endsWith(".xml")) {
            VirtualPath granulesFolder = productMetadataFilePath.resolveSibling("GRANULE");
            try {
                VirtualPath[] granulesFolderList = granulesFolder.listPaths();
                if (granulesFolderList != null && granulesFolderList.length > 0) {
                    for (VirtualPath granulePath : granulesFolderList) {
                        tileLayoutForResolution = retrieveTileLayoutFromGranuleDirectory(granulePath, resolution);
                        if (tileLayoutForResolution != null) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not retrieve tile layout for product " + productMetadataFilePath.getFullPathString() + " error returned: " + e.getMessage(), e);
            }
        }
        return tileLayoutForResolution;
    }

    /**
     * From a granule path, search a jpeg file for the given resolution, extract tile layout
     * information and update
     *
     * @param granuleMetadataPath the complete path to the granule directory
     * @param resolution          the resolution for which we wan to find the tile layout
     * @return the tile layout for the resolution, or {@code null} if none was found
     */
    private TileLayout retrieveTileLayoutFromGranuleDirectory(VirtualPath granuleMetadataPath, S2SpatialResolution resolution) {
        TileLayout tileLayoutForResolution = null;
        VirtualPath pathToImages = granuleMetadataPath.resolve("IMG_DATA");
        try {
            List<VirtualPath> imageDirectories = getImageDirectories(pathToImages, resolution);
            for (VirtualPath imageFilePath : imageDirectories) {
                try {
                    if (OpenJpegUtils.canReadJP2FileHeaderWithOpenJPEG()) {
                        Path jp2FilePath = imageFilePath.getLocalFile();
                        tileLayoutForResolution = OpenJpegUtils.getTileLayoutWithOpenJPEG(S2Config.OPJ_INFO_EXE, jp2FilePath);
                    } else {
                        try (FilePath filePath = imageFilePath.getFilePath()) {
                            boolean canSetFilePosition = !imageFilePath.getVirtualDir().isArchive();
                            tileLayoutForResolution = OpenJpegUtils.getTileLayoutWithInputStream(filePath.getPath(), 5 * 1024, canSetFilePosition);
                        }
                    }
                    if (tileLayoutForResolution != null) {
                        break;
                    }
                } catch (IOException | InterruptedException e) {
                    // if we have an exception, we try with the next file (if any) // and log a warning
                    logger.log(Level.WARNING, "Could not retrieve tile layout for file " + imageFilePath.toString() + " error returned: " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not retrieve tile layout for granule " + granuleMetadataPath.toString() + " error returned: " + e.getMessage(), e);
        }

        return tileLayoutForResolution;
    }

    /**
     * Get an iterator to image files in pathToImages containing files for the given resolution
     * <p>
     * This method is based on band names, if resolution can't be based on band names or if image files are not in
     * pathToImages (like for L2A products), this method has to be overriden
     *
     * @param pathToImages the path to the directory containing the images
     * @param resolution   the resolution for which we want to get images
     * @return a {@link DirectoryStream<Path>}, iterator on the list of image path
     * @throws IOException if an I/O error occurs
     */
    protected List<VirtualPath> getImageDirectories(VirtualPath pathToImages, S2SpatialResolution resolution) throws IOException {
        long startTime = System.currentTimeMillis();

        List<VirtualPath> imageDirectories = new ArrayList<>();
        String[] bandNames = getBandNames(resolution);
        if (bandNames != null && bandNames.length > 0) {
            VirtualPath[] imagePaths = pathToImages.listPaths();
            if (imagePaths != null && imagePaths.length > 0) {
                for (String bandName : bandNames) {
                    for (VirtualPath imagePath : imagePaths) {
                        if (imagePath.getFileName().toString().endsWith(bandName + ".jp2")) {
                            imageDirectories.add(imagePath);
                        }
                    }
                }
            }
        }

        if (logger.isLoggable(Level.FINE)) {
            double elapsedTimeInSeconds = (System.currentTimeMillis() - startTime) / 1000.d;
            logger.log(Level.FINE, "Finish finding the image directories using the images folder '" +pathToImages.getFullPathString()+"', size: "+imageDirectories.size()+"', elapsed time: " + elapsedTimeInSeconds + " seconds.");
        }

        return imageDirectories;
    }

    //TODO Jean remove the method
    @Deprecated
    protected Band addBand(Product product, BandInfo bandInfo, Dimension nativeResolutionDimensions) {
        Dimension dimension = new Dimension();
        if (isMultiResolution()) {
            dimension.width = nativeResolutionDimensions.width;
            dimension.height = nativeResolutionDimensions.height;
        } else {
            dimension.width = product.getSceneRasterWidth();
            dimension.height = product.getSceneRasterHeight();
        }

        Band band = new Band(bandInfo.getBandName(), S2Config.SAMPLE_PRODUCT_DATA_TYPE, dimension.width, dimension.height);

        S2BandInformation bandInformation = bandInfo.getBandInformation();
        band.setScalingFactor(bandInformation.getScalingFactor());

        if (bandInformation instanceof S2SpectralInformation) {
            S2SpectralInformation spectralInfo = (S2SpectralInformation) bandInformation;
            band.setSpectralWavelength((float) spectralInfo.getWavelengthCentral());
            band.setSpectralBandwidth((float) spectralInfo.getSpectralBandwith());
            band.setSpectralBandIndex(spectralInfo.getBandId());

            band.setNoDataValueUsed(false);
            band.setNoDataValue(0);
            band.setValidPixelExpression(String.format("%s.raw > %s", bandInfo.getBandName(), S2Config.RAW_NO_DATA_THRESHOLD));
        } else if (bandInformation instanceof S2IndexBandInformation) {
            S2IndexBandInformation indexBandInfo = (S2IndexBandInformation) bandInformation;
            band.setSpectralWavelength(0);
            band.setSpectralBandwidth(0);
            band.setSpectralBandIndex(-1);
            band.setSampleCoding(indexBandInfo.getIndexCoding());
            band.setImageInfo(indexBandInfo.getImageInfo());
        } else {
            band.setSpectralWavelength(0);
            band.setSpectralBandwidth(0);
            band.setSpectralBandIndex(-1);
        }

        product.addBand(band);
        return band;
    }

    @Override
    public void close() throws IOException {
        if (product != null) {
            for (Band band : product.getBands()) {
                MultiLevelImage sourceImage = band.getSourceImage();
                if (sourceImage != null) {
                    sourceImage.reset();
                    sourceImage.dispose();
                }
            }
        }
        if (this.namingConvention != null && this.namingConvention.getInputXml() != null) {
            this.namingConvention.getInputXml().close();
        }

        super.close();
    }

    protected final MosaicMatrix buildBandMatrix(List<String> bandMatrixTileIds, S2SceneDescription sceneDescription, BandInfo tileBandInfo) {
        MosaicMatrixCellCallback mosaicMatrixCellCallback = new MosaicMatrixCellCallback() {
            @Override
            public MosaicMatrix.MatrixCell buildMatrixCell(String tileId, BandInfo tileBandInfo) {
                VirtualPath imagePath = tileBandInfo.getTileIdToPathMap().get(tileId);
                JP2ImageFile jp2ImageFile = new JP2ImageFile(imagePath);
                return new S2MosaicBandMatrixCell(jp2ImageFile, getCacheDir(), tileBandInfo.getImageLayout());
            }
        };
        return buildBandMatrix(bandMatrixTileIds, sceneDescription, tileBandInfo, mosaicMatrixCellCallback);
    }

    protected static Band buildBand(BandInfo bandInfo, int bandWidth, int bandHeight) {
        Band band = new Band(bandInfo.getBandName(), S2Config.SAMPLE_PRODUCT_DATA_TYPE, bandWidth, bandHeight);

        S2BandInformation bandInformation = bandInfo.getBandInformation();
        band.setScalingFactor(bandInformation.getScalingFactor());

        if (bandInformation instanceof S2SpectralInformation) {
            S2SpectralInformation spectralInfo = (S2SpectralInformation) bandInformation;
            band.setSpectralWavelength((float) spectralInfo.getWavelengthCentral());
            band.setSpectralBandwidth((float) spectralInfo.getSpectralBandwith());
            band.setSpectralBandIndex(spectralInfo.getBandId());
            band.setNoDataValueUsed(false);
            band.setNoDataValue(0);
            band.setValidPixelExpression(String.format("%s.raw > %s", bandInfo.getBandName(), S2Config.RAW_NO_DATA_THRESHOLD));
        } else if (bandInformation instanceof S2IndexBandInformation) {
            S2IndexBandInformation indexBandInfo = (S2IndexBandInformation) bandInformation;
            band.setSpectralWavelength(0);
            band.setSpectralBandwidth(0);
            band.setSpectralBandIndex(-1);
            band.setSampleCoding(indexBandInfo.getIndexCoding());
            band.setImageInfo(indexBandInfo.getImageInfo());
        } else {
            band.setSpectralWavelength(0);
            band.setSpectralBandwidth(0);
            band.setSpectralBandIndex(-1);
        }
        return band;
    }

    protected static MosaicMatrix buildIndexBandMatrix(List<String> bandMatrixTileIds, S2SceneDescription sceneDescription, BandInfo tileBandInfo) {
        MosaicMatrixCellCallback mosaicMatrixCellCallback = new MosaicMatrixCellCallback() {
            @Override
            public MosaicMatrix.MatrixCell buildMatrixCell(String tileId, BandInfo tileBandInfo) {
                TileLayout tileLayout = tileBandInfo.getImageLayout();
                S2IndexBandInformation indexBandInformation = (S2IndexBandInformation) tileBandInfo.getBandInformation();
                S2GranuleDirFilename s2GranuleDirFilename = S2L1BGranuleDirFilename.create(tileId);
                Integer indexSample = indexBandInformation.findIndexSample(s2GranuleDirFilename.getTileID());
                if (indexSample == null) {
                    throw new NullPointerException("The index sample is null.");
                }
                short indexValueShort = indexSample.shortValue ();
                int cellWidth = tileLayout.width;
                int cellHeight = tileLayout.height;
                return new TileIndexBandMatrixCell(cellWidth, cellHeight, indexValueShort);
            }
        };
        return buildBandMatrix(bandMatrixTileIds, sceneDescription, tileBandInfo, mosaicMatrixCellCallback);
    }

    private static MosaicMatrix buildBandMatrix(List<String> bandMatrixTileIds, S2SceneDescription sceneDescription, BandInfo tileBandInfo, MosaicMatrixCellCallback mosaicMatrixCellCallback) {
        Pair<String, Rectangle> topLeftRectanglePair = null;
        S2SpatialResolution bandNativeResolution = tileBandInfo.getBandInformation().getResolution();
        List<Pair<String, Rectangle>> remainingRectanglePairs = new ArrayList<>(bandMatrixTileIds.size()-1);
        for (String tileId : bandMatrixTileIds) {
            Rectangle tileRectangle = sceneDescription.getMatrixTileRectangle(tileId, bandNativeResolution);
            Pair<String, Rectangle> pair = new Pair<String, Rectangle>(tileId, tileRectangle);
            if (tileRectangle.x == 0 && tileRectangle.y == 0) {
                topLeftRectanglePair = pair;
            } else {
                remainingRectanglePairs.add(pair);
            }
        }
        if (topLeftRectanglePair == null) {
            throw new IllegalStateException("No tile images.");
        }
        List<Pair<String, Rectangle>> orderedMatrixCells = new ArrayList<>(bandMatrixTileIds.size());
        orderedMatrixCells.add(topLeftRectanglePair);
        Rectangle firstColumnRectangle = topLeftRectanglePair.getSecond();
        int rowCount = 1;
        int columnCount = 1;
        int currentX = firstColumnRectangle.x + firstColumnRectangle.width;
        int currentY = firstColumnRectangle.y;
        while (orderedMatrixCells.size() < bandMatrixTileIds.size()) {
            Pair<String, Rectangle> nextColumnRectangle = null;
            for (int i = 0; i < remainingRectanglePairs.size() && nextColumnRectangle == null; i++) {
                Pair<String, Rectangle> pair = remainingRectanglePairs.get(i);
                if (pair != null) {
                    Rectangle rectangle = pair.getSecond();
                    if (rectangle.x == currentX && rectangle.y == currentY) {
                        nextColumnRectangle = pair;
                        remainingRectanglePairs.set(i, null); // reset the position
                        break;
                    }
                }
            }
            if (nextColumnRectangle == null) {
                // new row
                if (firstColumnRectangle == null) {
                    throw new IllegalStateException("Invalid tile rectangles.");
                }
                currentX = firstColumnRectangle.x;
                currentY = firstColumnRectangle.y + firstColumnRectangle.height;
                firstColumnRectangle = null; // reset the rectangle from the first column
                rowCount++;
                columnCount = 0; // reset the column count
            } else {
                // new column
                if (firstColumnRectangle == null) {
                    firstColumnRectangle = nextColumnRectangle.getSecond();
                }
                columnCount++;
                currentX += nextColumnRectangle.getSecond().width;
                orderedMatrixCells.add(nextColumnRectangle);
            }
        }
        if (rowCount * columnCount != bandMatrixTileIds.size()) {
            throw new IllegalStateException("Invalid matrix size: row count = " + rowCount+", column count = " + columnCount);
        }
        MosaicMatrix mosaicMatrix = new MosaicMatrix(rowCount, columnCount);
        for (int i=0; i<orderedMatrixCells.size(); i++) {
            Pair<String, Rectangle> pair = orderedMatrixCells.get(i);
            MosaicMatrix.MatrixCell matrixCell = mosaicMatrixCellCallback.buildMatrixCell(pair.getFirst(), tileBandInfo);
            mosaicMatrix.addCell(matrixCell);
        }
        return mosaicMatrix;
    }

    public static class BandInfo {
        private final Map<String, VirtualPath> tileIdToPathMap;
        private final S2BandInformation bandInformation;
        private final TileLayout imageLayout;

        public BandInfo(Map<String, VirtualPath> tileIdToPathMap, S2BandInformation spectralInformation, TileLayout imageLayout) {
            this.tileIdToPathMap = Collections.unmodifiableMap(tileIdToPathMap);
            this.bandInformation = spectralInformation;
            this.imageLayout = imageLayout;
        }

        public S2BandInformation getBandInformation() {
            return bandInformation;
        }

        public Map<String, VirtualPath> getTileIdToPathMap() {
            return tileIdToPathMap;
        }

        public TileLayout getImageLayout() {
            return imageLayout;
        }

        public String getBandName() {
            return getBandInformation().getPhysicalBand();
        }

        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }
}
