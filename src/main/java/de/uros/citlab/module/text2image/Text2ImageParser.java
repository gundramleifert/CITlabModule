/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.uros.citlab.module.text2image;

import com.achteck.misc.types.ConfMat;
import com.achteck.misc.types.ParamSetOrganizer;
import de.planet.imaging.types.HybridImage;
import de.uros.citlab.confmat.CharMap;
import de.uros.citlab.errorrate.types.PathCalculatorGraph;
import de.uros.citlab.errorrate.util.HeatMapUtil;
import de.uros.citlab.errorrate.util.ObjectCounter;
import de.uros.citlab.module.htr.HTRParser;
import de.uros.citlab.module.htr.HTRParserPlus;
import de.uros.citlab.module.types.HTR;
import de.uros.citlab.module.types.Key;
import de.uros.citlab.module.types.LineImage;
import de.uros.citlab.module.types.PageStruct;
import de.uros.citlab.module.util.*;
import de.uros.citlab.textalignment.HyphenationProperty;
import de.uros.citlab.textalignment.TextAligner;
import de.uros.citlab.textalignment.types.LineMatch;
import eu.transkribus.core.model.beans.pagecontent.PageType;
import eu.transkribus.core.model.beans.pagecontent.TextLineType;
import eu.transkribus.core.model.beans.pagecontent.TextRegionType;
import eu.transkribus.core.util.PageXmlUtils;
import eu.transkribus.interfaces.IText2Image;
import eu.transkribus.interfaces.types.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author gundram
 */
public class Text2ImageParser extends ParamSetOrganizer implements IText2Image {

    public static Logger LOG = LoggerFactory.getLogger(Text2ImageParser.class.getName());
    private ObjectCounter<Stat> oc = new ObjectCounter<>();
    //    IHtr htrImpl = null;
    private String toolName = "T2I";

    @Override
    public String usage() {
        return "tries to match given text to given baselines. " +
                "See " + Key.class.getCanonicalName() + ".T2I_* for possible configurations";
    }

    @Override
    public String getToolName() {
        return toolName;
    }

    @Override
    public String getVersion() {
        return MetadataUtil.getSoftwareVersion();
    }

    @Override
    public String getProvider() {
        return MetadataUtil.getProvider("Gundram Leifert", "gundram.leifert@planet-ai.de");
    }

    public enum Stat {
        REF, RECO, MATCH
    }

    public ObjectCounter<Stat> getStat() {
        return oc;
    }

    public void resetStat() {
        oc.reset();
    }

    private List<String> loadReferencePrepareBidi(File inFile, boolean ignoreHardLineBreaks) {
        List<String> in = FileUtil.readLines(inFile);
        deleteEmptyEntries(in);
        if (ignoreHardLineBreaks) {
            String oneline = BidiUtil.logical2visual(String.join(" ", in));
            in = new LinkedList<>(Arrays.asList(oneline));
            return in;
        }
        for (int i = 0; i < in.size(); i++) {
            in.set(i, BidiUtil.logical2visual(in.get(i)));
        }
        return in;
    }

    @Override
    public void matchImage(String pathToOpticalModel, String pathToLangugageResource, String pathCharMap, String txtIn, Image image, String xmlInOut, String[] region_ids, String[] props) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    private static void deleteEmptyEntries(List<String> lst) {
        for (int i = 0; i < lst.size(); i++) {
            String str = lst.get(i).replace("\n", "").replace("\r", "").replaceAll("\\s+", " ").trim();
            if (str.isEmpty()) {
                lst.remove(i--);
            } else {
                lst.set(i, str);
            }
        }
    }

    @Override
    public void matchCollection(String pathToOpticalModel, String pathToLanguageModel, String pathToCharacterMap, String pathToText, String[] images, String[] xmlInOut, String[] props) {
        matchCollection(pathToOpticalModel, pathToLanguageModel, pathToCharacterMap, pathToText, images, xmlInOut, null, props);
    }

    private static de.uros.citlab.confmat.ConfMat convert(ConfMat cm) {
        CharMap cmNew = new CharMap();
        com.achteck.misc.types.CharMap<Integer> charMap = cm.getCharMap();
        int size = charMap.keySet().size();
        for (int i = 1; i < size; i++) {
            cmNew.add(charMap.get(i));
        }
        return new de.uros.citlab.confmat.ConfMat(cmNew, cm.getDoubleMat());
    }

    private static List<de.uros.citlab.confmat.ConfMat> convert(List<ConfMat> cms) {
        List<de.uros.citlab.confmat.ConfMat> res = new ArrayList<>(cms.size());
        for (int i = 0; i < cms.size(); i++) {
            res.add(convert(cms.get(i)));
        }
        return res;
    }

    private HTRParserPlus parserPlus = new HTRParserPlus();
    private HTRParser parser = new HTRParser();

    public HTR getHTR(String folderHTR, String charMap, String storageDir) {
        try {
            return parser.getHTR(folderHTR, null, charMap, storageDir, null);
        } catch (RuntimeException ex) {
            try {
                return parserPlus.getHTR(folderHTR, null, charMap, storageDir, null);
            } catch (RuntimeException ex2) {
                LOG.error("cannot load HTR and HTR+. Error HTR: ", ex);
                LOG.error("cannot load HTR and HTR+. Error HTR+:", ex2);
                throw ex;
            }
        }
    }

    public List<String> loadReferencesFromPageStruct(List<PageStruct> pages, boolean ignoreHardLineBreaks) {
        LOG.debug("load text references from pageXML");
        List<String> res = new LinkedList<>();
        for (PageStruct page : pages) {
            res.addAll(PageXmlUtil.getText(page.getXml()));
        }
        deleteEmptyEntries(res);
        if (ignoreHardLineBreaks) {
            String oneline = BidiUtil.logical2visual(String.join(" ", res));
            return new LinkedList<>(Arrays.asList(oneline));
        }
        for (int i = 0; i < res.size(); i++) {
            res.set(i, BidiUtil.logical2visual(res.get(i)));
        }
        return res;
    }

    public void deleteT2ITextLines(PageStruct struct) {
        PageType page = struct.getXml().getPage();
        PageXmlUtils.getTextRegions(struct.getXml()).removeIf(textRegionType -> PageXmlUtil.isT2ITextRegion(textRegionType, struct.getXml()));
    }

    public void matchCollection(String pathToOpticalModel, String pathToLanguageModel, String
            pathToCharacterMap, String pathToText, String[] images, String[] xmlInOut, String[] storages, String[] props) {
        LOG.info("process xmls {}...", Arrays.asList(images));
        boolean deleteLineBreaks = PropertyUtil.isPropertyTrue(props, Key.T2I_IGNORE_LB);
        List<PageStruct> pages = PageXmlUtil.getPages(images, xmlInOut);
        List<String> in = pathToText != null && !pathToText.isEmpty() ?
                loadReferencePrepareBidi(new File(pathToText), deleteLineBreaks) :
                loadReferencesFromPageStruct(pages, deleteLineBreaks);
        final List<LineImage> linesExecution = new LinkedList<>();
        final List<PageStruct> pageExecution = new LinkedList<>();
        final List<ConfMat> confMats = new LinkedList<>();
        final Map<LineImage, HybridImage> lineMap = new LinkedHashMap<>();
        if (pathToText == null) {
            pathToText = images[0];
        }
        Set<HybridImage> freeImages = new LinkedHashSet<>();
        for (int i = 0; i < pages.size(); i++) {
            final PageStruct page = pages.get(i);
            String storage = storages != null ? storages[i] : null;
            HybridImage hi = null;
            try {
                hi = ImageUtil.getHybridImage(page.getImg(), true);
            } catch (RuntimeException ex) {
                LOG.info("loading image over transkribus throws error - use own method (errror = " + ex.getMessage() + ")");
                hi = ImageUtil.getHybridImage(page.getPathImg(), true);
            }
            HTR htr = getHTR(pathToOpticalModel, pathToCharacterMap, storage);
            for (TextRegionType textRegion : PageXmlUtils.getTextRegions(page.getXml())) {
                List<TextLineType> linesInRegion1 = textRegion.getTextLine();
                linesInRegion1.removeIf(textLineType -> PageXmlUtil.isT2ITextLine(textLineType, page.getXml()));
                for (TextLineType textLineType : linesInRegion1) {
                    LineImage lineImage = new LineImage(hi, textLineType, textRegion);
                    lineImage.deleteTextEquiv();
//                Display.addPanel(new DisplayPlanet(subImage),lineImage.getTextLine().getId());
                    try {
                        ConfMat confMat = htr.getConfMat(lineImage, props);
                        linesExecution.add(lineImage);
                        pageExecution.add(page);
                        lineMap.put(lineImage, hi);
                        confMats.add(confMat);
                    } catch (RuntimeException ex) {
                        LOG.warn("ignore text line {} of page {} in T2I", textLineType.getId(), page.getXml().getPcGtsId());
                    }
                }
            }
            freeImages.add(hi);
            htr.finalizePage();
        }
        if (confMats.isEmpty()) {
            LOG.warn("nothing to match for xmls {} because no textlines found.", Arrays.asList(images));
            for (PageStruct page : pages) {
                MetadataUtil.addMetadata(page.getXml(), this);
                page.saveXml();
            }
            return;
        }
        Double costSkipWords = PropertyUtil.hasProperty(props, Key.T2I_SKIP_WORD) ? Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_SKIP_WORD)) : null;
        Double costSkipBaseline = PropertyUtil.hasProperty(props, Key.T2I_SKIP_BASELINE) ? Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_SKIP_BASELINE)) : null;
        Double costJumpBaseline = PropertyUtil.hasProperty(props, Key.T2I_JUMP_BASELINE) ? Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_JUMP_BASELINE)) : null;
        Double bestPathOffset = PropertyUtil.hasProperty(props, Key.T2I_BEST_PATHES) ? Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_BEST_PATHES)) : null;
        int maxCount = PropertyUtil.hasProperty(props, Key.T2I_MAX_COUNT) ? Integer.parseInt(PropertyUtil.getProperty(props, Key.T2I_MAX_COUNT)) : -1;
        double threshold = Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_THRESH, "0.0"));
        if (threshold < 0) {
            threshold = 0.0;
        }
        toolName = "T2I(";
        if (props != null) {
            for (int i = 0; i < props.length; i += 2) {
                toolName += props[i] + "=" + props[i + 1] + ";";
            }
            toolName = toolName.substring(0, toolName.length() - 1) + ")";
        } else {
            toolName = toolName.substring(0, toolName.length() - 1);
        }

        String lbChars = " ";
        TextAligner textAligner = new TextAligner(
                lbChars,
                costSkipWords, //4.0
                costSkipBaseline, //0.2
                costJumpBaseline
        );
//        textAligner.setUpdateScheme(PathCalculatorGraph.UpdateScheme.ALL);
        textAligner.setNacOffset(0.0);
        textAligner.setThreshold(threshold);
        textAligner.setCostAnyChar(4.0);
        if (PropertyUtil.isPropertyTrue(props, Key.DEBUG)) {
            File folder = PropertyUtil.hasProperty(props, Key.DEBUG_DIR)
                    ? new File(PropertyUtil.getProperty(props, Key.DEBUG_DIR))
                    : new File(pathToText).getParentFile();
            folder.mkdirs();
            String name = new File(pathToText).getName();
            name = name.substring(0, name.lastIndexOf("."));
            final File file = new File(pathToText);
            textAligner.setDynMatViewer(new PathCalculatorGraph.DynMatViewer() {
                int i = 0;

                @Override
                public boolean callbackEnd(float[][] mat) {
                    try {
                        File file2 = new File(file + "_debug_" + i + ".png");
                        i++;
                        file2.getParentFile().mkdirs();
                        ImageIO.write(HeatMapUtil.getHeatMap(mat, 7), "png", file2);
                    } catch (IOException e) {
                        com.achteck.misc.log.Logger.getLogger(Text2ImageParser.class).log(com.achteck.misc.log.Logger.WARN, "cannot save image " + file, e);
                        return false;
                    }
                    return true;
                }

                @Override
                public boolean callbackUpdate(double d, float[][] mat) {
                    return false;
                }

                @Override
                public int[] getSize(int[] actual) {
                    return new int[]{Math.min(actual[0], 16384), Math.min(actual[1], 16384)};
                }
            });
//            throw new RuntimeException("so far no export for debug images implemented");
//            textAligner.setDynMatViewer(new PathCalculatorGraph.ImageExportDynMatViewer(new File(folder, name + ".png")));
        }
        if (maxCount > 0) {
            textAligner.setMaxVertexCount(maxCount);
        }
        if (bestPathOffset != null) {
            textAligner.setFilterOffset(bestPathOffset);
        }
        String property = PropertyUtil.getProperty(props, Key.T2I_HYPHEN);
        String lang = PropertyUtil.getProperty(props, Key.T2I_HYPHEN_LANG);
        textAligner.setHp(HyphenationProperty.newInstance(property, lang));
//        String ref = sb.toString();
//        ref = ref.trim();
        List<de.uros.citlab.confmat.ConfMat> confMatsConverted = convert(confMats);
        List<LineMatch> alignmentResult = textAligner.getAlignmentResult(in, confMatsConverted);
        if (alignmentResult != null) {
            for (LineMatch match : alignmentResult) {
                if (match != null) {
                    LineImage lineImage = linesExecution.get(confMatsConverted.indexOf(match.getCm()));
                    PageXmlUtil.setTextEquiv(lineImage.getTextLine(), match.getReference(), match.getConfidence());
                }
            }
        }
        for (PageStruct page : pages) {
            MetadataUtil.addMetadata(page.getXml(), this);
            page.saveXml();
        }
        if (PropertyUtil.isPropertyTrue(props, Key.DEBUG)) {
            try {
                double threshBaseline = Math.abs(Double.parseDouble(PropertyUtil.getProperty(props, Key.T2I_THRESH, "0.0")));
                for (PageStruct page : pages) {
                    File folder = PropertyUtil.hasProperty(props, Key.DEBUG_DIR)
                            ? new File(PropertyUtil.getProperty(props, Key.DEBUG_DIR))
                            : page.getPathXml().getParentFile();
                    folder.mkdirs();
                    {
                        BufferedImage debugImage = ImageUtil.getDebugImage(page.getImg().getImageBufferedImage(true), page.getXml(), 1.0, false, true, threshBaseline, false, false);
                        debugImage = ImageUtil.resize(debugImage, 6000, debugImage.getHeight() * 6000 / debugImage.getWidth());
                        String imageFilename = page.getXml().getPage().getImageFilename();
                        imageFilename += ".jpg";
                        ImageIO.write(debugImage, "jpg", new File(folder, imageFilename));
                    }
                }
            } catch (Throwable ex) {
                LOG.warn("writing debug images fails", ex);
            }
        }
    }

    @Override
    public void matchRegions(String pathToOpticalModel, String pathToLanguageModel, String
            pathToCharacterMap, String[] pathToText, String images, String xmlInOut, String[] region_ids, String[] props) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

}
