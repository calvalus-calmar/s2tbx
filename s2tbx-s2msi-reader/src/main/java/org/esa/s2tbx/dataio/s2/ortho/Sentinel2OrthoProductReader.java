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

package org.esa.s2tbx.dataio.s2.ortho;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import org.esa.s2tbx.dataio.s2.ColorIterator;
import org.esa.s2tbx.dataio.s2.S2BandAnglesGrid;
import org.esa.s2tbx.dataio.s2.S2BandAnglesGridByDetector;
import org.esa.s2tbx.dataio.s2.S2BandConstants;
import org.esa.s2tbx.dataio.s2.S2BandInformation;
import org.esa.s2tbx.dataio.s2.S2Config;
import org.esa.s2tbx.dataio.s2.S2IndexBandInformation;
import org.esa.s2tbx.dataio.s2.S2Metadata;
import org.esa.s2tbx.dataio.s2.S2SpatialResolution;
import org.esa.s2tbx.dataio.s2.S2SpectralInformation;
import org.esa.s2tbx.dataio.s2.Sentinel2ProductReader;
import org.esa.s2tbx.dataio.s2.VirtualPath;
import org.esa.s2tbx.dataio.s2.filepatterns.INamingConvention;
import org.esa.s2tbx.dataio.s2.gml.EopPolygon;
import org.esa.s2tbx.dataio.s2.ortho.metadata.S2OrthoMetadata;
import org.esa.s2tbx.dataio.s2.tiles.TileIndexMultiLevelSource;
import org.esa.s2tbx.dataio.s2.masks.MaskInfo;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.image.MosaicMatrix;
import org.esa.snap.core.image.SourceImageScaler;
import org.esa.snap.core.util.ImageUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.lib.openjpeg.jp2.TileLayout;
import org.esa.snap.lib.openjpeg.utils.StackTraceUtils;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.CropDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.awt.image.DataBuffer.TYPE_FLOAT;
import static org.esa.s2tbx.dataio.s2.ortho.metadata.S2OrthoMetadataProc.makeTileInformation;
import static org.esa.snap.utils.DateHelper.parseDate;

/**
 * <p>
 * Base class for Sentinel-2 readers of orthorectified products
 * </p>
 * <p>
 * To read single tiles, select any tile image file (IMG_*.jp2) within a product package. The reader will then
 * collect other band images for the selected tile and will also try to read the metadata file (MTD_*.xml).
 * </p>
 * <p>To read an entire scene, select the metadata file (MTD_*.xml) within a product package. The reader will then
 * collect other tile/band images and create a mosaic on the fly.
 * </p>
 *
 * @author Norman Fomferra
 * @author Nicolas Ducoin
 * modified 20200113 to support the advanced dialog for readers by Denisa Stefanescu
 */
public abstract class Sentinel2OrthoProductReader extends Sentinel2ProductReader implements S2AnglesGeometry {

    public static final String VIEW_ZENITH_PREFIX = "view_zenith";
    public static final String VIEW_AZIMUTH_PREFIX = "view_azimuth";
    public static final String SUN_ZENITH_PREFIX = "sun_zenith";
    public static final String SUN_AZIMUTH_PREFIX = "sun_azimuth";

    protected final String epsgCode;

    private S2OrthoMetadata orthoMetadataHeader;

    protected Sentinel2OrthoProductReader(ProductReaderPlugIn readerPlugIn, String epsgCode) {
        super(readerPlugIn);

        this.epsgCode = epsgCode;
    }

    protected abstract int getMaskLevel();

    protected abstract String getReaderCacheDir();

    protected S2SpatialResolution getProductResolution(INamingConvention namingConvention) {
        return namingConvention.getResolution();
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY,
                                          Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm)
                                          throws IOException {
        // Should never not come here, since we have an OpImage that reads data
    }

    @Override
    protected Product readProduct(String defaultProductName, boolean isGranule, S2Metadata metadataHeader, INamingConvention namingConvention) throws Exception {
        this.orthoMetadataHeader = (S2OrthoMetadata) metadataHeader;

        VirtualPath rootMetadataPath = orthoMetadataHeader.getPath();
        S2SpatialResolution productResolution = getProductResolution(namingConvention);

        S2OrthoSceneLayout sceneDescription = S2OrthoSceneLayout.create(orthoMetadataHeader);
        logger.fine("Scene Description: " + sceneDescription);

        // Check sceneDescription because a NullPointerException can be launched:
        // An error can be reproduced with a L2A product with 2 tiles in zone UTM30 and 2 other tiles in zone UTM31.
        // The process is stopped and the tiles in zone UTM 31 are empty
        // The execution does not finish when updating tileLayout at the beginning of this method
        // because the tile layout is obtained with the tile in zone UTM 30.
        // But the sceneLayout is computed with the tiles that are in the zone UTM 31 if we select this PlugIn
        if (sceneDescription.getTileIds().size() == 0) {
            throw new IOException(String.format("No valid tiles associated to product [%s]", rootMetadataPath.getFileName().toString()));
        }
        if (sceneDescription.getSceneDimension(productResolution) == null) {
            throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", rootMetadataPath.getFileName().toString()));
        }

        VirtualPath productPath = getProductDir(rootMetadataPath);
        initCacheDir(productPath);

        S2Metadata.ProductCharacteristics productCharacteristics = orthoMetadataHeader.getProductCharacteristics();

        String productType = "S2_MSI_" + productCharacteristics.getProcessingLevel();
        ProductSubsetDef subsetDef = getSubsetDef();
        Dimension defaultProductSize = new Dimension(sceneDescription.getSceneDimension(productResolution).width, sceneDescription.getSceneDimension(productResolution).height);
        Rectangle productBounds = ImageUtils.computeProductBounds(defaultProductSize.width, defaultProductSize.height, subsetDef);

        Product product = new Product(defaultProductName, productType, productBounds.width, productBounds.height, this);
        if (subsetDef == null || !subsetDef.isIgnoreMetadata()) {
            for (MetadataElement metadataElement : orthoMetadataHeader.getMetadataElements()) {
                product.getMetadataRoot().addElement(metadataElement);
            }
        }

        try {
            CoordinateReferenceSystem mapCRS = CRS.decode(this.epsgCode);
            CrsGeoCoding geoCoding = ImageUtils.buildCrsGeoCoding(sceneDescription.getSceneOrigin()[0], sceneDescription.getSceneOrigin()[1],
                                         productResolution.resolution, productResolution.resolution,
                                         defaultProductSize, mapCRS, productBounds);
            product.setSceneGeoCoding(geoCoding);
        } catch (FactoryException | TransformException e) {
            throw new IOException(e);
        }

        product.setPreferredTileSize(S2Config.DEFAULT_JAI_TILE_SIZE, S2Config.DEFAULT_JAI_TILE_SIZE);
        product.setNumResolutionsMax(orthoMetadataHeader.getConfig().getTileLayout(S2SpatialResolution.R10M.resolution).numResolutions);
        product.setAutoGrouping("sun:view:quality:tile:detector_footprint:nodata:partially_corrected_crosstalk:saturated_l1a:saturated_l1b:defective:ancillary_lost:ancillary_degraded:msi_lost:msi_degraded:opaque_clouds:cirrus_clouds:scl:msc:ddv:tile:" +
                "detector_footprint-B01:" +
                "detector_footprint-B02:" +
                "detector_footprint-B03:" +
                "detector_footprint-B04:" +
                "detector_footprint-B05:" +
                "detector_footprint-B06:" +
                "detector_footprint-B07:" +
                "detector_footprint-B08:" +
                "detector_footprint-B8A:" +
                "detector_footprint-B09:" +
                "detector_footprint-B10:" +
                "detector_footprint-B11:" +
                "detector_footprint-B12");

        product.setStartTime(parseDate(productCharacteristics.getProductStartTime(), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
        product.setEndTime(parseDate(productCharacteristics.getProductStopTime(), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));

        List<S2Metadata.Tile> tileList = orthoMetadataHeader.getTileList();

        List<BandInfo> bandInfoList = orthoMetadataHeader.computeBandInfoByKey(tileList);

        if (!bandInfoList.isEmpty()) {
            addBands(product, bandInfoList, sceneDescription, productResolution);

            scaleBands(product, bandInfoList, productResolution);

            addVectorMasks(product, tileList, bandInfoList);

            addIndexMasks(product, bandInfoList, sceneDescription, productResolution);
        }

        //add TileIndex if there are more than 1 tile
        if (sceneDescription.getOrderedTileIds().size() > 1 && !bandInfoList.isEmpty()) {
            ArrayList<S2SpatialResolution> resolutions = new ArrayList<>();
            //look for the resolutions used in bandInfoList for generating the tile index only for them
            for (BandInfo bandInfo : bandInfoList) {
                if (!resolutions.contains(bandInfo.getBandInformation().getResolution())) {
                    resolutions.add(bandInfo.getBandInformation().getResolution());
                }
            }
            addTileIndexes(product, resolutions, tileList, sceneDescription, productResolution);
        }

        if (!"Brief".equalsIgnoreCase(productCharacteristics.getMetaDataLevel())) {
            HashMap<String, S2BandAnglesGrid[]> anglesGridsMap = new HashMap<>();
            for (S2Metadata.Tile tile : tileList) {
                S2BandAnglesGrid[] bandAnglesGrids = createS2OrthoAnglesGrids(orthoMetadataHeader, tile.getId());
                if (bandAnglesGrids != null) {
                    anglesGridsMap.put(tile.getId(), bandAnglesGrids);
                }
            }
            addAnglesBands(defaultProductSize, productBounds, product, sceneDescription, anglesGridsMap,productResolution);
        }

        return product;
    }

    private void addAnglesBands(Dimension defaultProductSize, Rectangle productBounds, Product product,
                                S2OrthoSceneLayout sceneDescription, HashMap<String, S2BandAnglesGrid[]> bandAnglesGridsMap, S2SpatialResolution productResolution) {

        // the upper-left corner
        Point.Float masterOrigin = new Point.Float(Float.MAX_VALUE, -Float.MAX_VALUE);
        Dimension anglesTileSize = new Dimension(0, 0);
        // angle band resolution
        Point.Float resolution = new Point.Float(0.0f, 0.0f);
        // array of all angles in a tile
        Set<AngleID> angleIds = new HashSet<AngleID>();
        // search upper-left coordinates
        for (String tileId : sceneDescription.getOrderedTileIds()) {
            S2BandAnglesGrid[] bandAnglesGrid = bandAnglesGridsMap.get(tileId);
            anglesTileSize.width = bandAnglesGrid[0].getWidth();
            anglesTileSize.height = bandAnglesGrid[0].getHeight();
            resolution.x = bandAnglesGrid[0].getResolutionX();
            resolution.y = bandAnglesGrid[0].getResolutionY();
            if (masterOrigin.x > bandAnglesGrid[0].originX) {
                masterOrigin.x = bandAnglesGrid[0].originX;
            }
            if (masterOrigin.y < bandAnglesGrid[0].originY) {
                masterOrigin.y = bandAnglesGrid[0].originY;
            }
            for (S2BandAnglesGrid grid : bandAnglesGrid) {
                if (grid.getResolutionX() == resolution.x && grid.getResolutionY() == resolution.y && grid.getWidth() == anglesTileSize.width && grid.getHeight() == anglesTileSize.height) {
                    angleIds.add(new AngleID(grid.getPrefix(), grid.getBand())); //if it is repeated, the angleID is not added because it is a HashSet
                }
            }
        }

        if (masterOrigin.x == Float.MAX_VALUE || masterOrigin.y == -Float.MAX_VALUE || resolution.x == 0 || resolution.y == 0 || anglesTileSize.width == 0 || anglesTileSize.height == 0) {
            logger.warning("Invalid tile data for computing the angles mosaic");
            return;
        }

        // sort the angles
        List<AngleID> sortedList = new ArrayList(angleIds);
        Collections.sort(sortedList);
        ProductSubsetDef subsetDef = getSubsetDef();
        for (AngleID angleID : sortedList) {
            String bandName = getAngleBandName(angleID);
            if (subsetDef == null || subsetDef.isNodeAccepted(bandName)) {
                Rectangle bandBounds = null;
                PlanarImage bandSourceImage = null;
                List<PlanarImage> tileImages = buildMosaicTileImages(angleID, sceneDescription, anglesTileSize, bandAnglesGridsMap, masterOrigin);
                if (!tileImages.isEmpty()) {
                ImageLayout imageLayout = new ImageLayout();
                imageLayout.setMinX(0);
                imageLayout.setMinY(0);
                imageLayout.setTileWidth(S2Config.DEFAULT_JAI_TILE_SIZE);
                imageLayout.setTileHeight(S2Config.DEFAULT_JAI_TILE_SIZE);
                imageLayout.setTileGridXOffset(0);
                imageLayout.setTileGridYOffset(0);

                RenderingHints hints = new RenderingHints(JAI.KEY_TILE_CACHE, JAI.getDefaultInstance().getTileCache());
                hints.put(JAI.KEY_IMAGE_LAYOUT, imageLayout);

                RenderedImage[] sources = tileImages.toArray(new RenderedImage[tileImages.size()]);
                RenderedOp mosaicOp = MosaicDescriptor.create(sources, MosaicDescriptor.MOSAIC_TYPE_OVERLAY, null, null, new double[][]{{-1.0}},
                                                              new double[]{S2Config.FILL_CODE_MOSAIC_ANGLES}, hints);

                // Crop Mosaic if there are lines outside the scene
                bandSourceImage = cropBordersOutsideScene(mosaicOp, resolution.x, resolution.y, sceneDescription);

                int defaultBandWidth = bandSourceImage.getWidth();
                int defaultBandHeight = bandSourceImage.getHeight();
                bandBounds = ImageUtils.computeBandBoundsBasedOnPercent(productBounds, defaultProductSize.width, defaultProductSize.height, defaultBandWidth, defaultBandHeight);
                if (bandBounds.x > 0 || bandBounds.y > 0 || bandBounds.width != defaultBandWidth || bandBounds.height != defaultBandHeight) {
                    Raster subsetSourceData = bandSourceImage.getData();
                    WritableRaster subsetRaster = subsetSourceData.createCompatibleWritableRaster(bandBounds.width, bandBounds.height);
                    for (int x = 0; x < bandBounds.width; x++) {
                        for (int y = 0; y < bandBounds.height; y++) {
                            float value = subsetSourceData.getSampleFloat(x + bandBounds.x, y + bandBounds.y, 0);
                            subsetRaster.setSample(x, y, 0, value);
                        }
                    }
                    ColorModel colorModel = bandSourceImage.getColorModel();
                    BufferedImage image = new BufferedImage(colorModel, subsetRaster, colorModel.isAlphaPremultiplied(), null);
                    bandSourceImage = PlanarImage.wrapRenderedImage(image);
                }
            }
                if (bandSourceImage == null) {
                    logger.warning("No tile images for angles mosaic");
                    return;
                }
                Band band = new Band(bandName, ProductData.TYPE_FLOAT32, bandSourceImage.getWidth(), bandSourceImage.getHeight());
                String description = "";
                if (angleID.prefix.startsWith(VIEW_ZENITH_PREFIX)) {
                    description = "Viewing incidence zenith angle";
                } else if (angleID.prefix.startsWith(VIEW_AZIMUTH_PREFIX)) {
                    description = "Viewing incidence azimuth angle";
                } else if (angleID.prefix.startsWith(SUN_ZENITH_PREFIX)) {
                    description = "Solar zenith angle";
                } else if (angleID.prefix.startsWith(SUN_AZIMUTH_PREFIX)) {
                    description = "Solar azimuth angle";
                }
                band.setDescription(description);
                band.setUnit("°");
                band.setNoDataValue(Double.NaN);
                band.setNoDataValueUsed(true);
                try {
                    CrsGeoCoding geoCoding = ImageUtils.buildCrsGeoCoding(sceneDescription.getSceneOrigin()[0], sceneDescription.getSceneOrigin()[1],
                                                                          resolution.x, resolution.y,
                                                                          anglesTileSize.width, anglesTileSize.height,
                                                                          CRS.decode(this.epsgCode), bandBounds);
                    band.setGeoCoding(geoCoding);
                } catch (Exception e) {
                    continue;
                }

                band.setImageToModelTransform(product.findImageToModelTransform(band.getGeoCoding()));

                //set source image mut be done after setGeocoding and setImageToModelTransform
                band.setSourceImage(bandSourceImage);
                product.addBand(band);
            }
        }
    }

    private void addBands(Product product, List<BandInfo> bandInfoList, S2OrthoSceneLayout sceneDescription, S2SpatialResolution productResolution) throws IOException {
        for (BandInfo bandInfo : bandInfoList) {
            Dimension defaultBandSize = sceneDescription.getSceneDimension(bandInfo.getBandInformation().getResolution());
            if (getSubsetDef() == null || getSubsetDef().isNodeAccepted(bandInfo.getBandName())) {
                Rectangle bandBounds;
                // Get the band native resolution
                S2SpatialResolution bandNativeResolution = bandInfo.getBandInformation().getResolution();
                if (getSubsetDef() != null) {
                    if (isMultiResolution()) {
                            bandBounds = ImageUtils.computeBandBoundsBasedOnPercent(getSubsetDef().getRegion(),
                                                                  sceneDescription.getSceneDimension(productResolution).width,sceneDescription.getSceneDimension(productResolution).height,
                                                                  defaultBandSize.width, defaultBandSize.height);
                    } else {
                        bandBounds = new Rectangle(getSubsetDef().getRegion().width, getSubsetDef().getRegion().height);
                    }
                } else {
                    bandBounds = new Rectangle(bandInfo.getImageLayout().width, bandInfo.getImageLayout().height);
                }
                Band band = buildBand(bandInfo, bandBounds.width, bandBounds.height);
                band.setDescription(bandInfo.getBandInformation().getDescription());
                band.setUnit(bandInfo.getBandInformation().getUnit());

                double pixelSize;
                if (isMultiResolution()) {
                    pixelSize = (double) bandNativeResolution.resolution;
                } else {
                    pixelSize = (double) productResolution.resolution;
                }

                try {

                    CrsGeoCoding geoCoding = ImageUtils.buildCrsGeoCoding(sceneDescription.getSceneOrigin()[0], sceneDescription.getSceneOrigin()[1],
                                                 pixelSize, pixelSize, defaultBandSize,
                                                 CRS.decode(this.epsgCode), bandBounds);
                    band.setGeoCoding(geoCoding);
                } catch (FactoryException | TransformException e) {
                    throw new IOException(e);
                }
                AffineTransform imageToModelTransform = Product.findImageToModelTransform(product.getSceneGeoCoding());
                MosaicMatrix mosaicMatrix = buildBandMatrix(sceneDescription.getOrderedTileIds(), sceneDescription, bandInfo);
                BandMultiLevelSource bandScene = new BandMultiLevelSource(bandInfo.getImageLayout().numResolutions, mosaicMatrix, bandBounds, imageToModelTransform);

                band.setSourceImage(new DefaultMultiLevelImage(bandScene));
                product.addBand(band);
            }
        }
    }

    private void scaleBands(Product product, List<BandInfo> bandInfoList, S2SpatialResolution productResolution) throws IOException {
        // In MultiResolution mode, all bands are kept at their native resolution
        if (isMultiResolution()) {
            return;
        }

        // Find a reference band for rescaling the bands at other resolution
        MultiLevelImage targetImage = null;
        for (BandInfo bandInfo : bandInfoList) {
            if (bandInfo.getBandInformation().getResolution() == productResolution) {
                Band referenceBand = product.getBand(bandInfo.getBandInformation().getPhysicalBand());
                targetImage = referenceBand.getSourceImage();
                break;
            }
        }

        // If the product only has a subset of bands, we may not find what we are looking for
        if (targetImage == null) {
            String error = String.format("Products with no bands at %s m resolution currently cannot be read by the %s m reader", productResolution.resolution, productResolution.resolution);
            throw new IOException(error);
        }

        for (Band band : product.getBands()) {
            final MultiLevelImage sourceImage = band.getSourceImage();

            if (sourceImage.getWidth() == product.getSceneRasterWidth()
                    && sourceImage.getHeight() == product.getSceneRasterHeight()) {
                // Do not rescaled band which are already at the correct resolution
                continue;
            }

            ImageLayout imageLayout = new ImageLayout();
            ImageManager.getPreferredTileSize(product);
            final RenderingHints renderingHints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout);
            float[] scalings = new float[2];
            scalings[0] = product.getSceneRasterWidth() / (float) sourceImage.getWidth();
            scalings[1] = product.getSceneRasterHeight() / (float) sourceImage.getHeight();
            PlanarImage scaledImage = SourceImageScaler.scaleMultiLevelImage(targetImage, sourceImage, scalings, null, renderingHints,
                    band.getNoDataValue(),
                    Interpolation.getInstance(Interpolation.INTERP_NEAREST));
            band.setSourceImage(scaledImage);
        }
    }

    private void addIndexMasks(Product product, List<BandInfo> bandInfoList, S2OrthoSceneLayout sceneDescription, S2SpatialResolution productResolution) throws IOException {
        for (BandInfo bandInfo : bandInfoList) {
            if (bandInfo.getBandInformation() instanceof S2IndexBandInformation) {
                Dimension defaultBandSize = sceneDescription.getSceneDimension(bandInfo.getBandInformation().getResolution());
                S2IndexBandInformation indexBandInformation = (S2IndexBandInformation) bandInfo.getBandInformation();
                IndexCoding indexCoding = indexBandInformation.getIndexCoding();
                product.getIndexCodingGroup().add(indexCoding);
                Dimension dimension;
                Rectangle bandBounds = null;
                if(getSubsetDef() != null) {
                    bandBounds = ImageUtils.computeBandBoundsBasedOnPercent(getSubsetDef().getRegion(),
                                            sceneDescription.getSceneDimension(productResolution).width, sceneDescription.getSceneDimension(productResolution).height,
                                            defaultBandSize.width, defaultBandSize.height);
                    dimension = new Dimension(bandBounds.width, bandBounds.height);
                }else {
                    dimension = sceneDescription.getSceneDimension(bandInfo.getBandInformation().getResolution());
                }

                List<Color> colors = indexBandInformation.getColors();
                Iterator<Color> colorIterator = colors.iterator();

                for (String indexName : indexCoding.getIndexNames()) {
                    int indexValue = indexCoding.getIndexValue(indexName);
                    String description = indexCoding.getIndex(indexName).getDescription();
                    if (!colorIterator.hasNext()) {
                        // we should never be here : programming error.
                        throw new IOException(String.format("Unexpected error when creating index masks : colors list does not have the same size as index coding"));
                    }
                    Color color = colorIterator.next();
                    String maskName = indexBandInformation.getPrefix() + indexName.toLowerCase();
                    if (getSubsetDef() == null ||
                            (getSubsetDef().isNodeAccepted(maskName) && getSubsetDef().isNodeAccepted(indexBandInformation.getPhysicalBand()))) {
                        Mask mask = Mask.BandMathsType.create(maskName, description, dimension.width, dimension.height,
                                                              String.format("%s.raw == %d", indexBandInformation.getPhysicalBand(), indexValue), color, 0.5);

                        //set geoCoding
                        double pixelSize;
                        if (isMultiResolution()) {
                            pixelSize = (double) bandInfo.getBandInformation().getResolution().resolution;
                        } else {
                            pixelSize = (double) productResolution.resolution;
                        }

                        try {
                            CrsGeoCoding geoCoding = ImageUtils.buildCrsGeoCoding(sceneDescription.getSceneOrigin()[0], sceneDescription.getSceneOrigin()[1],
                                                         pixelSize, pixelSize , defaultBandSize,
                                                         CRS.decode(this.epsgCode), bandBounds);
                            mask.setGeoCoding(geoCoding);
                        } catch (FactoryException | TransformException e) {
                            throw new IOException(e);
                        }

                        product.addMask(mask);
                    }
                }
            }
        }
    }

    private void addVectorMasks(Product product, List<S2Metadata.Tile> tileList, List<BandInfo> bandInfoList) throws IOException {
        for (MaskInfo maskInfo : MaskInfo.values()) {
            if (!maskInfo.isPresentAtLevel(getMaskLevel()))
                continue;
            if (!maskInfo.isEnabled())
                continue;

            if (!maskInfo.isPerBand()) {
                // cloud masks are provided once and valid for all bands
                addVectorMask(product, tileList, maskInfo, null, bandInfoList);
            } else {
                // for other masks, we have one mask instance for each spectral band
                for (BandInfo bandInfo : bandInfoList) {
                    if (bandInfo.getBandInformation() instanceof S2SpectralInformation) {
                        addVectorMask(product, tileList, maskInfo, (S2SpectralInformation) bandInfo.getBandInformation(), bandInfoList);
                    }
                }
            }
        }
    }

    private void addVectorMask(Product product, List<S2Metadata.Tile> tileList, MaskInfo maskInfo, S2SpectralInformation spectralInfo, List<BandInfo> bandInfoList) {
        List<EopPolygon>[] productPolygons = new List[maskInfo.getSubType().length];
        for (int i = 0; i < maskInfo.getSubType().length; i++) {
            productPolygons[i] = new ArrayList<>();
        }


        boolean maskFilesFound = false;
        for (S2Metadata.Tile tile : tileList) {

            if (tile.getMaskFilenames() == null) {
                continue;
            }

            for (S2Metadata.MaskFilename maskFilename : tile.getMaskFilenames()) {

                // We are only interested in a single mask main type
                if (!maskFilename.getType().equals(maskInfo.getMainType())) {
                    continue;
                }

                if (spectralInfo != null) {
                    // We are only interested in masks for a certain band
                    if (!maskFilename.getBandId().equals(String.format("%s", spectralInfo.getBandId()))) {
                        continue;
                    }
                }

                maskFilesFound = true;

                List<EopPolygon> polygonsForTile;

                polygonsForTile = S2OrthoUtils.readPolygons(maskFilename.getPath());

                for (int i = 0; i < maskInfo.getSubType().length; i++) {
                    final int pos = i;
                    productPolygons[i].addAll(polygonsForTile.stream().filter(p -> p.getType().equals(maskInfo.getSubType()[pos])).collect(Collectors.toList()));
                }
            }
        }

        if (!maskFilesFound) {
            return;
        }

        for (int i = 0; i < maskInfo.getSubType().length; i++) {
            // TODO : why do we use this here ?
            final SimpleFeatureType type = Placemark.createGeometryFeatureType();
            // TODO : why "S2L1CMasks" ?
            final DefaultFeatureCollection collection = S2OrthoUtils.createDefaultFeatureCollection(productPolygons, i, type);

            if (spectralInfo == null) {
                // This mask is not specific to a band
                // So we need one version of it for each resolution present in the band list
                for (S2SpatialResolution resolution : S2SpatialResolution.values()) {
                    // Find a band with this resolution
                    Band referenceBand = null;
                    for (BandInfo bandInfo : bandInfoList) {
                        if (bandInfo.getBandInformation().getResolution() == resolution) {
                            referenceBand = product.getBand(bandInfo.getBandInformation().getPhysicalBand());
                            break;
                        }
                    }

                    // We may not find a band with this resolution
                    if (referenceBand == null) {
                        continue;
                    }

                    // We need a different name for each resolution version
                    String description = maskInfo.getDescription(i);
                    if (!maskInfo.isPerPolygon()) {
                        String snapName = String.format("%s_%dm", maskInfo.getSnapName()[i], resolution.resolution);
                        if(getSubsetDef() == null || (getSubsetDef() != null && getSubsetDef().isNodeAccepted(snapName))) {
                            VectorDataNode vdn = new VectorDataNode(snapName, collection);
                            vdn.setOwner(product);
                            product.addMask(snapName,
                                            vdn,
                                            description,
                                            maskInfo.getColor()[i],
                                            maskInfo.getTransparency()[i],
                                            referenceBand);
                        }
                    } else {
                        //Currently there are no masks with this characteristics, the code should be tested if a new mask is added
                        SimpleFeatureIterator simpleFeatureIterator = collection.features();
                        List<String> distictPolygonsOrdered = S2OrthoUtils.createDistictPolygonsOrdered(simpleFeatureIterator);
                        simpleFeatureIterator.close();

                        ColorIterator.reset();
                        for (String subId : distictPolygonsOrdered) {
                            final DefaultFeatureCollection subCollection = new DefaultFeatureCollection(subId, type);
                            simpleFeatureIterator = collection.features();
                            while (simpleFeatureIterator.hasNext()) {
                                SimpleFeature simpleFeature = simpleFeatureIterator.next();
                                if (simpleFeature.getID().startsWith(subId)) {
                                    subCollection.add(simpleFeature);
                                }
                            }
                            simpleFeatureIterator.close();
                            VectorDataNode vdnPolygon = new VectorDataNode(subId, subCollection);
                            vdnPolygon.setOwner(product);
                            String snapName = String.format("%s_%dm", subId, resolution.resolution);

                            if(getSubsetDef() == null || (getSubsetDef() != null && getSubsetDef().isNodeAccepted(snapName))) {
                                product.addMask(snapName,
                                                vdnPolygon,
                                                description,
                                                ColorIterator.next(),
                                                maskInfo.getTransparency()[i],
                                                referenceBand);
                            }
                        }
                    }
                }
            } else {

                // This mask is specific to a band
                Band referenceBand = product.getBand(spectralInfo.getPhysicalBand());
                String bandName = spectralInfo.getPhysicalBand();
                String description = maskInfo.getDescriptionForBand(bandName, i);


                if (!maskInfo.isPerPolygon()) {
                    String snapName = maskInfo.getSnapNameForBand(bandName, i);
                    if(getSubsetDef() == null || (getSubsetDef() != null && getSubsetDef().isNodeAccepted(snapName))) {
                        VectorDataNode vdn = new VectorDataNode(snapName, collection);
                        vdn.setOwner(product);
                        product.addMask(snapName,
                                        vdn,
                                        description,
                                        maskInfo.getColor()[i],
                                        maskInfo.getTransparency()[i],
                                        referenceBand);
                    }
                } else {
                    SimpleFeatureIterator simpleFeatureIterator = collection.features();
                    List<String> distictPolygonsOrdered = S2OrthoUtils.createDistictPolygonsOrdered(simpleFeatureIterator);
                    simpleFeatureIterator.close();

                    ColorIterator.reset();
                    for (String subId : distictPolygonsOrdered) {
                        final DefaultFeatureCollection subCollection = new DefaultFeatureCollection(subId, type);
                        simpleFeatureIterator = collection.features();
                        while (simpleFeatureIterator.hasNext()) {
                            SimpleFeature simpleFeature = simpleFeatureIterator.next();
                            if (simpleFeature.getID().startsWith(subId)) {
                                subCollection.add(simpleFeature);
                            }
                        }
                        simpleFeatureIterator.close();
                        VectorDataNode vdnPolygon = new VectorDataNode(subId, subCollection);
                        vdnPolygon.setOwner(product);
                        if(getSubsetDef() == null || (getSubsetDef() != null && getSubsetDef().isNodeAccepted(subId))) {
                            product.addMask(subId,
                                            vdnPolygon,
                                            description,
                                            ColorIterator.next(),
                                            maskInfo.getTransparency()[i],
                                            referenceBand);
                        }
                    }
                }
            }
        }
    }

    private void addTileIndexes(Product product, ArrayList<S2SpatialResolution> resolutions, List<S2Metadata.Tile> tileList,
                                S2OrthoSceneLayout sceneDescription, S2SpatialResolution productResolution) {

        if (resolutions.isEmpty() || tileList.isEmpty()) {
            return;
        }

        List<BandInfo> tileInfoList = new ArrayList<>();
        ArrayList<S2IndexBandInformation> listTileIndexBandInformation = new ArrayList<>();

        //for each resolution, add the tile information
        for (S2SpatialResolution res : S2SpatialResolution.values()) {
            if (resolutions.contains(res)) {
                listTileIndexBandInformation.add(makeTileInformation(res, sceneDescription));
            }
        }

        // Create BandInfo and add to tileInfoList
        for (S2BandInformation bandInformation : listTileIndexBandInformation) {
            HashMap<String, VirtualPath> tilePathMap = new HashMap<>();
            for (S2Metadata.Tile tile : tileList) {
                tilePathMap.put(tile.getId(), null); //it is not necessary any file
            }
            if (!tilePathMap.isEmpty()) {
                BandInfo tileInfo = createBandInfoFromHeaderInfo(bandInformation, tilePathMap, this.orthoMetadataHeader.getConfig());
                if (tileInfo != null) {
                    tileInfoList.add(tileInfo);
                }
            }
        }

        if (tileInfoList.isEmpty()) {
            return;
        }
        //Add the bands
        for (BandInfo bandInfo : tileInfoList) {
            try {
                addTileIndex(product, bandInfo, sceneDescription, productResolution);
            } catch (Exception e) {
                logger.warning(String.format("It has not been possible to add tile id for resolution %s\n", bandInfo.getBandInformation().getResolution().toString()));
            }
        }

        //Add the index masks
        try {
            addIndexMasks(product, tileInfoList, sceneDescription, productResolution);
        } catch (IOException e) {
            logger.warning("It has not been possible to add index mask for tiles");
        }
    }

    private void addTileIndex(Product product, BandInfo bandInfo, S2OrthoSceneLayout sceneDescription, S2SpatialResolution productResolution) throws IOException {
        Dimension defaultProductSize = sceneDescription.getSceneDimension(productResolution);
        Dimension dimension = sceneDescription.getSceneDimension(bandInfo.getBandInformation().getResolution());
        MosaicMatrix mosaicMatrix = buildIndexBandMatrix(sceneDescription.getOrderedTileIds(), sceneDescription, bandInfo);
        int defaultBandWidth = mosaicMatrix.computeTotalWidth();
        int defaultBandHeight = mosaicMatrix.computeTotalHeight();
        Rectangle bandBounds = ImageUtils.computeBandBoundsBasedOnPercent(new Rectangle(product.getSceneRasterWidth(), product.getSceneRasterHeight()), defaultProductSize.width, defaultProductSize.height, defaultBandWidth, defaultBandHeight);

        Band band = new Band(bandInfo.getBandName(), ProductData.TYPE_INT16, dimension.width, dimension.height);
        S2BandInformation bandInformation = bandInfo.getBandInformation();
        TileLayout thisBandTileLayout = bandInfo.getImageLayout();
        band.setScalingFactor(bandInformation.getScalingFactor());
        S2IndexBandInformation indexBandInfo = (S2IndexBandInformation) bandInformation;
        band.setSpectralWavelength(0);
        band.setSpectralBandwidth(0);
        band.setSpectralBandIndex(-1);
        band.setSampleCoding(indexBandInfo.getIndexCoding());
        band.setImageInfo(indexBandInfo.getImageInfo());

        band.setDescription(bandInfo.getBandInformation().getDescription());
        band.setUnit(bandInfo.getBandInformation().getUnit());

        band.setValidPixelExpression(String.format("%s.raw > 0", bandInfo.getBandInformation().getPhysicalBand()));

        double pixelSize;
        if (isMultiResolution()) {
            pixelSize = (double) bandInfo.getBandInformation().getResolution().resolution;
        } else {
            pixelSize = (double) productResolution.resolution;
        }

        try {
            GeoCoding geoCoding = ImageUtils.buildCrsGeoCoding(sceneDescription.getSceneOrigin()[0], sceneDescription.getSceneOrigin()[1],
                                                               pixelSize, pixelSize, defaultBandWidth, defaultBandHeight,
                                                               CRS.decode(this.epsgCode), bandBounds);
            band.setGeoCoding(geoCoding);
        } catch (FactoryException | TransformException e) {
            throw new IOException(e);
        }
        band.setImageToModelTransform(product.findImageToModelTransform(band.getGeoCoding()));

        TileIndexMultiLevelSource tileIndex = new TileIndexMultiLevelSource(thisBandTileLayout.numResolutions, mosaicMatrix, bandBounds, Product.findImageToModelTransform(band.getGeoCoding()));SystemUtils.LOG.fine("TileIndex: " + tileIndex);
        band.setSourceImage(new DefaultMultiLevelImage(tileIndex));
        product.addBand(band);
    }

    private static boolean isValidAngle(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    //Checks if every angleGrid has the expected size
    private static boolean checkAnglesGrids(S2Metadata.AnglesGrid[] anglesGrids, int expectedGridHeight, int expectedGridWidth) {
        if (anglesGrids == null) {
            return false;
        }
        for (S2Metadata.AnglesGrid angleGrid : anglesGrids) {
            if (angleGrid.getHeight() != expectedGridHeight || angleGrid.getWidth() != expectedGridWidth) {
                return false;
            }
        }
        return true;
    }

    private static S2BandAnglesGrid[] createS2OrthoAnglesGrids(S2Metadata metadataHeader, String tileId) throws IOException {
        S2BandAnglesGrid[] bandAnglesGrid = null;
        ArrayList<S2BandAnglesGrid> listBandAnglesGrid = new ArrayList<>();
        S2Metadata.Tile tile = metadataHeader.getTile(tileId);
        S2Metadata.AnglesGrid anglesGrid = tile.getSunAnglesGrid();
        int resolution = tile.getAnglesResolution();

        if (anglesGrid == null) {
            return bandAnglesGrid;
        }

        int gridHeight = tile.getSunAnglesGrid().getZenith().length;
        int gridWidth = tile.getSunAnglesGrid().getZenith()[0].length;
        float[] sunZeniths = new float[gridWidth * gridHeight];
        float[] sunAzimuths = new float[gridWidth * gridHeight];
        float[] viewingZeniths = new float[gridWidth * gridHeight];
        float[] viewingAzimuths = new float[gridWidth * gridHeight];
        int[] viewingZenithsCount = new int[gridWidth * gridHeight];
        int[] viewingAzimuthsCount = new int[gridWidth * gridHeight];

        Arrays.fill(viewingZeniths, Float.NaN);
        Arrays.fill(viewingAzimuths, Float.NaN);

        S2Metadata.AnglesGrid sunAnglesGrid = tile.getSunAnglesGrid();
        S2Metadata.AnglesGrid[] viewingIncidenceAnglesGrids = tile.getViewingIncidenceAnglesGrids();

        if (checkAnglesGrids(viewingIncidenceAnglesGrids, gridHeight, gridWidth)) {
            int iLastBandId = -1;
            int bandId;
            for (S2Metadata.AnglesGrid grid : viewingIncidenceAnglesGrids) {
                bandId = grid.getBandId();

                //if lastBand and the current band are different, the lecture of the last band has finished and we add it to listBandAnglesGrid
                //after that, the arrays are filled again with NaN
                if (iLastBandId != bandId) {
                    if (iLastBandId >= 0) {
                        float[] zeniths = new float[gridWidth * gridHeight];
                        float[] azimuths = new float[gridWidth * gridHeight];
                        System.arraycopy(viewingZeniths, 0, zeniths, 0, gridWidth * gridHeight);
                        System.arraycopy(viewingAzimuths, 0, azimuths, 0, gridWidth * gridHeight);
                        listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_ZENITH_PREFIX, S2BandConstants.getBand(iLastBandId), gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, zeniths));
                        listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_AZIMUTH_PREFIX, S2BandConstants.getBand(iLastBandId), gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, azimuths));
                    }
                    Arrays.fill(viewingZeniths, Float.NaN);
                    Arrays.fill(viewingAzimuths, Float.NaN);
                    iLastBandId = bandId;
                }

                for (int y = 0; y < gridHeight; y++) {
                    for (int x = 0; x < gridWidth; x++) {
                        final int index = y * gridWidth + x;
                        try {
                            if (y < grid.getZenith().length) {
                                if (x < grid.getZenith()[y].length) {
                                    if (isValidAngle(grid.getZenith()[y][x])) {
                                        viewingZeniths[index] = grid.getZenith()[y][x];
                                    }
                                }
                            }

                            if (y < grid.getAzimuth().length) {
                                if (x < grid.getAzimuth()[y].length) {
                                    if (isValidAngle(grid.getAzimuth()[y][x])) {
                                        viewingAzimuths[index] = grid.getAzimuth()[y][x];
                                    }
                                }
                            }

                        } catch (Exception e) {
                            logger.severe(StackTraceUtils.getStackTrace(e));
                        }
                    }
                }
            }

            //add the last band which is in memory
            if (iLastBandId > 0) {
                float[] zeniths = new float[gridWidth * gridHeight];
                float[] azimuths = new float[gridWidth * gridHeight];
                System.arraycopy(viewingZeniths, 0, zeniths, 0, gridWidth * gridHeight);
                System.arraycopy(viewingAzimuths, 0, azimuths, 0, gridWidth * gridHeight);
                listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_ZENITH_PREFIX, S2BandConstants.getBand(iLastBandId), gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, zeniths));
                listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_AZIMUTH_PREFIX, S2BandConstants.getBand(iLastBandId), gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, azimuths));
            }

            //Compute the mean viewing angles
            Arrays.fill(viewingZeniths, 0.0f);
            Arrays.fill(viewingAzimuths, 0.0f);
            Arrays.fill(viewingZenithsCount, 0);
            Arrays.fill(viewingAzimuthsCount, 0);
            for (S2BandAnglesGrid grid : listBandAnglesGrid) {
                for (int i = 0; i < grid.getData().length; i++) {
                    float gridData = grid.getData()[i];
                    if (grid.getPrefix().equals(VIEW_ZENITH_PREFIX)) {
                        viewingZeniths[i] = viewingZeniths[i] + gridData;
                        viewingZenithsCount[i]++;
                    }
                    if (grid.getPrefix().equals(VIEW_AZIMUTH_PREFIX)) {
                        viewingAzimuths[i] = viewingAzimuths[i] + gridData;
                        viewingAzimuthsCount[i]++;
                    }
                }
            }
            for (int i = 0; i < viewingZeniths.length; i++) {
                if (viewingZenithsCount[i] != 0) viewingZeniths[i] = viewingZeniths[i] / viewingZenithsCount[i];
                if (viewingAzimuthsCount[i] != 0) viewingAzimuths[i] = viewingAzimuths[i] / viewingAzimuthsCount[i];
            }
        }

        //out of the "if" because we want always the mean view angles (perhaps they will be NaN)
        listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_ZENITH_PREFIX, null, gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, viewingZeniths));
        listBandAnglesGrid.add(new S2BandAnglesGrid(VIEW_AZIMUTH_PREFIX, null, gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, viewingAzimuths));

        if (sunAnglesGrid != null) {
            for (int y = 0; y < gridHeight; y++) {
                for (int x = 0; x < gridWidth; x++) {
                    final int index = y * gridWidth + x;
                    sunZeniths[index] = sunAnglesGrid.getZenith()[y][x];
                    sunAzimuths[index] = sunAnglesGrid.getAzimuth()[y][x];
                }
            }
            listBandAnglesGrid.add(new S2BandAnglesGrid(SUN_ZENITH_PREFIX, null, gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, sunZeniths));
            listBandAnglesGrid.add(new S2BandAnglesGrid(SUN_AZIMUTH_PREFIX, null, gridWidth, gridHeight, (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftX(), (float) tile.getTileGeometry(S2SpatialResolution.R10M).getUpperLeftY(), resolution, resolution, sunAzimuths));
        }

        if (listBandAnglesGrid.size() > 0) {
            bandAnglesGrid = listBandAnglesGrid.toArray(new S2BandAnglesGrid[listBandAnglesGrid.size()]);
        }

        return bandAnglesGrid;
    }

    private BandInfo createBandInfoFromHeaderInfo(S2BandInformation bandInformation, Map<String, VirtualPath> tilePathMap, S2Config config) {
        S2SpatialResolution spatialResolution = bandInformation.getResolution();
        if (config.getTileLayout(spatialResolution.resolution) == null) {
            return null;
        }
        return new BandInfo(tilePathMap, bandInformation, config.getTileLayout(spatialResolution.resolution));
    }

    static VirtualPath getProductDir(VirtualPath productPath) throws IOException {
        if (!productPath.exists()) {
            throw new FileNotFoundException("File not found: " + productPath.getFullPathString());
        }
        return productPath.getParent();
    }

    /**
     * Check the content of first and last rows and columns, and if all the pixels are zero, they are removed
     *
     * @param planarImage
     * @return
     */
    private static RenderedOp cropBordersIfAreZero(RenderedOp planarImage) {
        //First row
        boolean remove = true;
        for (int i = 0; i < planarImage.getWidth(); i++) {
            if (planarImage.copyData().getSampleFloat(planarImage.getMinX() + i, planarImage.getMinY(), 0) != 0) {
                remove = false;
                break;
            }
        }
        if (remove) {
            planarImage = CropDescriptor.create(planarImage, planarImage.getMinX() + 0.0f, planarImage.getMinY() + 1.0f, (float) planarImage.getWidth(), (float) planarImage.getHeight() - 1, null);
        }

        //Last row
        remove = true;
        for (int i = 0; i < planarImage.getWidth(); i++) {
            if (planarImage.copyData().getSampleFloat(planarImage.getMinX() + i, planarImage.getMinY() + planarImage.getHeight() - 1, 0) != 0) {
                remove = false;
                break;
            }
        }
        if (remove) {
            planarImage = CropDescriptor.create(planarImage, planarImage.getMinX() + 0.0f, planarImage.getMinY() + 0.0f, (float) planarImage.getWidth(), (float) planarImage.getHeight() - 1, null);
        }

        //First column
        remove = true;
        for (int i = 0; i < planarImage.getHeight(); i++) {
            if (planarImage.copyData().getSampleFloat(planarImage.getMinX(), planarImage.getMinY() + i, 0) != 0) {
                remove = false;
                break;
            }
        }
        if (remove) {
            planarImage = CropDescriptor.create(planarImage, planarImage.getMinX() + 1.0f, planarImage.getMinY() + 0.0f, (float) planarImage.getWidth() - 1, (float) planarImage.getHeight(), null);
        }

        //Last column
        remove = true;
        for (int i = 0; i < planarImage.getHeight(); i++) {
            if (planarImage.copyData().getSampleFloat(planarImage.getMinX() + planarImage.getWidth() - 1, planarImage.getMinY() + i, 0) != 0) {
                remove = false;
                break;
            }
        }
        if (remove) {
            planarImage = CropDescriptor.create(planarImage, planarImage.getMinX() + 0.0f, planarImage.getMinY() + 0.0f, (float) planarImage.getWidth() - 1, (float) planarImage.getHeight(), null);
        }

        return planarImage;
    }

    /**
     * The origin of planarImage and sceneLayout must be the same.
     * Compute the number of the pixels needed to cover the scene and remove the rows and columns outside the scene in planarImage.
     *
     * @param planarImage
     * @param resolutionX
     * @param resolutionY
     * @param sceneLayout
     * @return
     */
    private static RenderedOp cropBordersOutsideScene(RenderedOp planarImage, float resolutionX, float resolutionY, S2OrthoSceneLayout sceneLayout) {
        if (sceneLayout.sceneDimensions.size() <= 0) {
            return planarImage;
        }
        int sceneHeight = 0;
        int sceneWidth = 0;
        for (S2SpatialResolution resolution : S2SpatialResolution.values()) {
            if (sceneLayout.sceneDimensions.get(resolution) != null) {
                sceneHeight = sceneLayout.getSceneDimension(resolution).height * resolution.resolution;
                sceneWidth = sceneLayout.getSceneDimension(resolution).width * resolution.resolution;
                break;
            }
        }

        int columnNumber = (int) Math.ceil(sceneWidth / resolutionX);
        int rowNumber = (int) Math.ceil(sceneHeight / resolutionY);
        return CropDescriptor.create(planarImage, planarImage.getMinX() + 0.0f, planarImage.getMinY() + 0.0f, (float) columnNumber, (float) rowNumber, null);
    }

    public S2BandAnglesGridByDetector[] getViewingIncidenceAnglesGrids(int bandId, int detectorId) {
        if (orthoMetadataHeader == null) return null;
        return orthoMetadataHeader.getAnglesGridByDetector(bandId, detectorId);
    }

    public S2BandAnglesGrid[] getSunAnglesGrid() {
        if (orthoMetadataHeader == null) return null;
        return orthoMetadataHeader.getSunAnglesGrid();
    }

    private static class AngleID implements Comparable<AngleID> {
        final String prefix;
        final S2BandConstants band;

        AngleID(String prefix, S2BandConstants band) {
            this.prefix = prefix;
            this.band = band;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AngleID angleID = (AngleID) o;

            if (!prefix.equals(angleID.prefix)) return false;
            return band == angleID.band;
        }

        @Override
        public int hashCode() {
            int result = prefix.hashCode();
            result = 31 * result + (band != null ? band.hashCode() : 0);
            return result;
        }

        @Override
        public int compareTo(AngleID compareAngleID) {
            int order;
            String comparePrefix = compareAngleID.prefix;
            S2BandConstants compareBand = compareAngleID.band;

            if (compareBand == null && this.band == null) {
                if (comparePrefix.equals(this.prefix)) {
                    order = 0;
                } else if (comparePrefix.contains("sun") && !this.prefix.contains("sun")) {
                    order = -1;
                } else if (!comparePrefix.contains("sun") && this.prefix.contains("sun")) {
                    order = 1;
                } else if (this.prefix.contains("zenith")) {
                    order = -1;
                } else {
                    order = 1;
                }
            } else if (compareBand == null) {
                order = 1;
            } else if (this.band == null) {
                order = -1;
            } else if (compareBand.getBandIndex() < this.band.getBandIndex()) {
                order = 1;
            } else if (compareBand.getBandIndex() > this.band.getBandIndex()) {
                order = -1;
            } else if (this.prefix.contains("zenith")) {
                order = -1;
            } else {
                order = 1;
            }
            return order;
        }
    }

    private static String getAngleBandName(AngleID angleID) {
        String bandName;
        if (angleID.band != null) {
            bandName = angleID.prefix + "_" + angleID.band.getPhysicalName();
        } else if (angleID.prefix.equals(VIEW_AZIMUTH_PREFIX) || angleID.prefix.equals(VIEW_ZENITH_PREFIX)) {
            bandName = angleID.prefix + "_mean";
        } else {
            bandName = angleID.prefix;
        }
        return bandName;
    }

    private static List<PlanarImage> buildMosaicTileImages(AngleID angleID, S2OrthoSceneLayout sceneDescription, Dimension anglesTileSize,
                                                           HashMap<String, S2BandAnglesGrid[]> bandAnglesGridsMap, Point.Float masterOrigin) {

        int[] bandOffsets = {0};
        SampleModel sampleModel = new PixelInterleavedSampleModel(TYPE_FLOAT, anglesTileSize.width, anglesTileSize.height, 1, anglesTileSize.width, bandOffsets);
        ColorSpace colorSpace = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        ColorModel colorModel = new ComponentColorModel(colorSpace, false, false, Transparency.TRANSLUCENT, TYPE_FLOAT);

        // mosaic of planar images
        List<PlanarImage> tileImages = new ArrayList<>();
        for (String tileId : sceneDescription.getOrderedTileIds()) {
            S2BandAnglesGrid[] bandAnglesGrids = bandAnglesGridsMap.get(tileId);
            // search index of angleID
            int i = -1;
            for (int j = 0; j < bandAnglesGrids.length; j++) {
                AngleID angleIDAux = new AngleID(bandAnglesGrids[j].getPrefix(), bandAnglesGrids[j].getBand());
                if (angleID.equals(angleIDAux)) {
                    i = j;
                }
            }

            float rasterPixels[];
            if (i == -1) {
                rasterPixels = new float[anglesTileSize.width * anglesTileSize.height];
                Arrays.fill(rasterPixels, Float.NaN);
            } else {
                rasterPixels = bandAnglesGrids[i].getData();
            }
            DataBuffer buffer = new DataBufferFloat(anglesTileSize.width * anglesTileSize.height);
            WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, null);
            raster.setPixels(0, 0, anglesTileSize.width, anglesTileSize.height, rasterPixels);

            // And finally create an image with this raster
            BufferedImage image = new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
            PlanarImage opImage = PlanarImage.wrapRenderedImage(image);

            // Translate tile
            float translateX = (bandAnglesGrids[0].originX - masterOrigin.x) / bandAnglesGrids[0].getResolutionX();
            float translateY = (bandAnglesGrids[0].originY - masterOrigin.y) / bandAnglesGrids[0].getResolutionY();
            RenderingHints hints = new RenderingHints(JAI.KEY_TILE_CACHE, null);
            RenderedOp translateOpImage = TranslateDescriptor.create(opImage, translateX, -translateY, Interpolation.getInstance(Interpolation.INTERP_BILINEAR), hints);

            //Crop output image because with bilinear interpolation some pixels are 0.0
            RenderedOp cropOpImage = cropBordersIfAreZero(translateOpImage);
            // Feed the image list for mosaic
            tileImages.add(cropOpImage);
        }
        return tileImages;
    }
}
