/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.utils;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.entities.SemanticPicture;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.lists.ListItem;
import org.verapdf.wcag.algorithms.entities.lists.PDFList;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.consumers.ContrastRatioConsumer;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class ImagesUtils {
    private static final Logger LOGGER = Logger.getLogger(ImagesUtils.class.getCanonicalName());
    private static final Logger CONTRAST_RATIO_LOGGER =
        Logger.getLogger("org.verapdf.wcag.algorithms.semanticalgorithms.consumers.ContrastRatioConsumer");
    private static final String WIDTH_WARNING_FRAGMENT = "width is <= 0";
    private static final String HEIGHT_WARNING_FRAGMENT = "height is <= 0";

    private ContrastRatioConsumer contrastRatioConsumer;
    private final Set<Integer> failedPages = new LinkedHashSet<>();
    private Integer currentRenderingPageNumber;
    private boolean currentImageHasContrastWarning;

    private final Handler contrastWarningHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getMessage() == null || record.getLevel() == null) {
                return;
            }
            String message = record.getMessage();
            if (record.getLevel().intValue() >= Level.WARNING.intValue()
                && (message.contains(WIDTH_WARNING_FRAGMENT) || message.contains(HEIGHT_WARNING_FRAGMENT))) {
                currentImageHasContrastWarning = true;
                markPageAsFailed(currentRenderingPageNumber,
                    "ContrastRatioConsumer warning: " + message);
            }
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    };

    public ContrastRatioConsumer getContrastRatioConsumer() {
        return contrastRatioConsumer;
    }

    public Set<Integer> getFailedPages() {
        return Collections.unmodifiableSet(failedPages);
    }

    public void createImagesDirectory(String path) {
        File directory = new File(path);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void write(List<List<IObject>> contents, String pdfFilePath, String password) {
        attachContrastWarningHandler();
        try {
            for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                for (IObject content : contents.get(pageNumber)) {
                    writeFromContents(content, pdfFilePath, password, pageNumber);
                }
            }
        } finally {
            currentRenderingPageNumber = null;
            detachContrastWarningHandler();
        }
    }

    private void writeFromContents(IObject content, String pdfFilePath, String password, int pageNumber) {
        if (failedPages.contains(pageNumber)) {
            return;
        }
        if (content instanceof ImageChunk) {
            writeImage((ImageChunk) content, pdfFilePath, password, pageNumber);
        } else if (content instanceof SemanticPicture) {
            writePicture((SemanticPicture) content, pdfFilePath, password, pageNumber);
        } else if (content instanceof PDFList) {
            for (ListItem listItem : ((PDFList) content).getListItems()) {
                for (IObject item : listItem.getContents()) {
                    writeFromContents(item, pdfFilePath, password, pageNumber);
                }
            }
        } else if (content instanceof TableBorder) {
            for (TableBorderRow row : ((TableBorder) content).getRows()) {
                TableBorderCell[] cells = row.getCells();
                for (int columnNumber = 0; columnNumber < cells.length; columnNumber++) {
                    TableBorderCell cell = cells[columnNumber];
                    if (cell.getColNumber() == columnNumber && cell.getRowNumber() == row.getRowNumber()) {
                        for (IObject item : cell.getContents()) {
                            writeFromContents(item, pdfFilePath, password, pageNumber);
                        }
                    }
                }
            }
        } else if (content instanceof SemanticHeaderOrFooter) {
            for (IObject item : ((SemanticHeaderOrFooter) content).getContents()) {
                writeFromContents(item, pdfFilePath, password, pageNumber);
            }
        }
    }

    protected void writeImage(ImageChunk chunk, String pdfFilePath, String password) {
        writeImage(chunk, pdfFilePath, password, chunk != null ? chunk.getPageNumber() : null);
    }

    private void writeImage(ImageChunk chunk, String pdfFilePath, String password, Integer pageNumber) {
        int currentImageIndex = StaticLayoutContainers.incrementImageIndex();
        if (currentImageIndex == 1) {
            createImagesDirectory(StaticLayoutContainers.getImagesDirectory());
            contrastRatioConsumer = StaticLayoutContainers.getContrastRatioConsumer(pdfFilePath, password, false, null);
        }
        String imageFormat = StaticLayoutContainers.getImageFormat();
        String fileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT,
            StaticLayoutContainers.getImagesDirectory(), File.separator, currentImageIndex, imageFormat);
        chunk.setIndex(currentImageIndex);
        createImageFile(chunk.getBoundingBox(), fileName, imageFormat, pageNumber);
    }

    protected void writePicture(SemanticPicture picture, String pdfFilePath, String password) {
        writePicture(picture, pdfFilePath, password, picture != null ? picture.getPageNumber() : null);
    }

    private void writePicture(SemanticPicture picture, String pdfFilePath, String password, Integer pageNumber) {
        int pictureIndex = picture.getPictureIndex();
        if (contrastRatioConsumer == null) {
            createImagesDirectory(StaticLayoutContainers.getImagesDirectory());
            contrastRatioConsumer = StaticLayoutContainers.getContrastRatioConsumer(pdfFilePath, password, false, null);
        }
        String imageFormat = StaticLayoutContainers.getImageFormat();
        String fileName = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT,
            StaticLayoutContainers.getImagesDirectory(), File.separator, pictureIndex, imageFormat);
        createImageFile(picture.getBoundingBox(), fileName, imageFormat, pageNumber);
    }

    private void createImageFile(BoundingBox imageBox, String fileName, String imageFormat, Integer pageNumber) {
        Integer effectivePageNumber = resolvePageNumber(pageNumber, imageBox);
        currentRenderingPageNumber = effectivePageNumber;
        currentImageHasContrastWarning = false;
        try {
            if (imageBox == null || imageBox.getWidth() <= 0 || imageBox.getHeight() <= 0) {
                markPageAsFailed(effectivePageNumber,
                    "Invalid image bounding box ("
                        + (imageBox == null ? "null" : imageBox.getWidth() + "x" + imageBox.getHeight()) + ")");
                return;
            }
            File outputFile = new File(fileName);
            BufferedImage targetImage = contrastRatioConsumer != null ? contrastRatioConsumer.getPageSubImage(imageBox) : null;
            if (currentImageHasContrastWarning) {
                LOGGER.log(Level.WARNING,
                    "Skipping image save on page {0} due to ContrastRatioConsumer width/height warning.",
                    effectivePageNumber != null ? effectivePageNumber + 1 : -1);
                return;
            }
            if (targetImage == null) {
                markPageAsFailed(effectivePageNumber, "Rendered image is null");
                return;
            }
            ImageIO.write(targetImage, imageFormat, outputFile);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to create image files: " + e.getMessage());
        } catch (RuntimeException e) {
            markPageAsFailed(effectivePageNumber, "Runtime error while rendering image: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Unable to create image files: " + e.getMessage());
        } finally {
            currentRenderingPageNumber = null;
            currentImageHasContrastWarning = false;
        }
    }

    public static boolean isImageFileExists(String fileName) {
        File outputFile = new File(fileName);
        return outputFile.exists();
    }

    private void attachContrastWarningHandler() {
        CONTRAST_RATIO_LOGGER.addHandler(contrastWarningHandler);
    }

    private void detachContrastWarningHandler() {
        CONTRAST_RATIO_LOGGER.removeHandler(contrastWarningHandler);
    }

    private Integer resolvePageNumber(Integer pageNumber, BoundingBox imageBox) {
        if (pageNumber != null && pageNumber >= 0) {
            return pageNumber;
        }
        if (imageBox != null) {
            try {
                int imagePageNumber = imageBox.getPageNumber();
                if (imagePageNumber >= 0) {
                    return imagePageNumber;
                }
            } catch (RuntimeException ignored) {
                // Keep fallback behavior when page number is unavailable
            }
        }
        return null;
    }

    private void markPageAsFailed(Integer pageNumber, String reason) {
        if (pageNumber == null || pageNumber < 0) {
            return;
        }
        if (failedPages.add(pageNumber)) {
            LOGGER.log(Level.WARNING,
                "Image rendering issue on page {0}: {1}",
                new Object[]{pageNumber + 1, reason});
        }
    }
}
