package br.com.eugeniosolucoes.imagecompactor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.w3c.dom.Node;

/**
 * Compacta uma imagem para que fique abaixo de um limite de bytes, priorizando:
 * <ol>
 * <li>manter as dimensões e a maior qualidade JPEG possível (busca binária entre {@value #MAX_QUALITY_PCT}% e
 * {@value #MIN_QUALITY_PCT}%);</li>
 * <li>somente se nem a qualidade mínima couber no limite, reduzir as dimensões proporcionalmente.</li>
 * </ol>
 * Imagens que não são JPEG (BMP, PNG, TIFF...) primeiro tentam PNG, que é sem perdas; se não couber, viram JPEG.
 * Os metadados do JPEG original (EXIF, orientação etc.) são preservados sempre que compatíveis.
 */
public final class ImageCompressor {

    public static final long DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024;

    static final int MAX_QUALITY_PCT = 95;
    static final int MIN_QUALITY_PCT = 70;
    private static final double MIN_SCALE = 0.05;
    private static final String JPEG_NATIVE_FORMAT = "javax_imageio_jpeg_image_1.0";

    private final long limitBytes;

    public ImageCompressor(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    public CompressedImage compress(Path file) throws IOException {
        LoadedImage loaded = read(file);
        BufferedImage original = loaded.image();
        int origW = original.getWidth();
        int origH = original.getHeight();

        if (!loaded.jpeg()) {
            byte[] png = encodePng(original);
            if (png.length < limitBytes) {
                return new CompressedImage(png, "png", 1f, origW, origH, origW, origH);
            }
        }

        BufferedImage source = toJpegCompatible(original);
        ImageWriter writer = jpegWriter();
        try {
            IIOMetadata metadata = loaded.jpeg() ? adaptMetadata(writer, loaded.metadata(), source) : null;
            BufferedImage current = source;
            double scale = 1.0;
            while (true) {
                // Melhor caso: qualidade máxima já cabe no limite
                byte[] best = encodeJpeg(writer, current, metadata, MAX_QUALITY_PCT);
                int bestQuality = MAX_QUALITY_PCT;
                if (best.length >= limitBytes) {
                    byte[] atMin = encodeJpeg(writer, current, metadata, MIN_QUALITY_PCT);
                    if (atMin.length < limitBytes) {
                        // Busca binária pela maior qualidade que cabe: lo sempre cabe, hi nunca cabe
                        int lo = MIN_QUALITY_PCT;
                        int hi = MAX_QUALITY_PCT;
                        best = atMin;
                        bestQuality = lo;
                        while (hi - lo > 1) {
                            int mid = (lo + hi) >>> 1;
                            byte[] candidate = encodeJpeg(writer, current, metadata, mid);
                            if (candidate.length < limitBytes) {
                                lo = mid;
                                best = candidate;
                                bestQuality = mid;
                            } else {
                                hi = mid;
                            }
                        }
                    } else {
                        // Nem a qualidade mínima coube: reduz as dimensões na proporção do excesso
                        double factor = Math.sqrt((double) limitBytes / atMin.length) * 0.97;
                        scale *= Math.min(factor, 0.95);
                        if (scale < MIN_SCALE) {
                            throw new IOException("Não foi possível compactar abaixo do limite: " + file);
                        }
                        current = resize(source, scale);
                        continue;
                    }
                }
                return new CompressedImage(best, "jpg", bestQuality / 100f,
                        origW, origH, current.getWidth(), current.getHeight());
            }
        } finally {
            writer.dispose();
        }
    }

    private record LoadedImage(BufferedImage image, IIOMetadata metadata, boolean jpeg) {
    }

    private static LoadedImage read(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("Não foi possível abrir: " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Formato de imagem não suportado: " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, false);
                IIOImage image = reader.readAll(0, null);
                boolean jpeg = "jpeg".equalsIgnoreCase(reader.getFormatName());
                return new LoadedImage((BufferedImage) image.getRenderedImage(), image.getMetadata(), jpeg);
            } finally {
                reader.dispose();
            }
        }
    }

    /** JPEG não suporta transparência nem tipos de imagem customizados: converte para RGB sobre fundo branco. */
    private static BufferedImage toJpegCompatible(BufferedImage image) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_3BYTE_BGR || type == BufferedImage.TYPE_INT_RGB
                || type == BufferedImage.TYPE_BYTE_GRAY) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** Redimensiona com interpolação bicúbica, em etapas de no máximo 50% para evitar serrilhado. */
    private static BufferedImage resize(BufferedImage source, double scale) {
        int targetW = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        do {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            BufferedImage step = new BufferedImage(w, h, source.getType());
            Graphics2D g = step.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(current, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            current = step;
        } while (w != targetW || h != targetH);
        return current;
    }

    /** Prefere o writer JPEG do JDK, que suporta otimização das tabelas de Huffman. */
    private static ImageWriter jpegWriter() {
        ImageWriter fallback = null;
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg"); it.hasNext(); ) {
            ImageWriter writer = it.next();
            if (writer.getDefaultWriteParam() instanceof JPEGImageWriteParam) {
                return writer;
            }
            if (fallback == null) {
                fallback = writer;
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("Nenhum ImageWriter JPEG disponível");
        }
        return fallback;
    }

    /**
     * Converte os metadados do JPEG original para o writer, preservando EXIF/XMP. O perfil ICC é removido porque os
     * pixels já foram convertidos para sRGB na leitura. Retorna {@code null} se os metadados forem incompatíveis
     * com a imagem (por exemplo, JPEG CMYK original).
     */
    private static IIOMetadata adaptMetadata(ImageWriter writer, IIOMetadata original, BufferedImage image) {
        if (original == null || !JPEG_NATIVE_FORMAT.equals(original.getNativeMetadataFormatName())) {
            return null;
        }
        try {
            Node tree = original.getAsTree(JPEG_NATIVE_FORMAT);
            if (countComponents(tree) != image.getColorModel().getNumComponents()) {
                return null;
            }
            removeIccProfile(tree);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
            metadata.setFromTree(JPEG_NATIVE_FORMAT, tree);
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    private static int countComponents(Node root) {
        Node sof = findChild(findChild(root, "markerSequence"), "sof");
        if (sof == null) {
            return -1;
        }
        int count = 0;
        for (Node n = sof.getFirstChild(); n != null; n = n.getNextSibling()) {
            if ("componentSpec".equals(n.getNodeName())) {
                count++;
            }
        }
        return count;
    }

    private static void removeIccProfile(Node root) {
        Node jfif = findChild(findChild(root, "JPEGvariety"), "app0JFIF");
        Node icc = findChild(jfif, "app2ICC");
        if (icc != null) {
            jfif.removeChild(icc);
        }
    }

    private static Node findChild(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) {
                return n;
            }
        }
        return null;
    }

    private static byte[] encodeJpeg(ImageWriter writer, BufferedImage image, IIOMetadata metadata, int qualityPct)
            throws IOException {
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(qualityPct / 100f);
        if (param instanceof JPEGImageWriteParam jpegParam) {
            jpegParam.setOptimizeHuffmanTables(true);
        }
        try {
            return write(writer, new IIOImage(image, null, metadata), param);
        } catch (IOException | RuntimeException e) {
            if (metadata == null) {
                throw e;
            }
            // Metadados rejeitados pelo writer: grava sem eles
            return write(writer, new IIOImage(image, null, null), param);
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IllegalStateException("Nenhum ImageWriter PNG disponível");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0f); // máxima compressão (sem perdas)
            }
            return write(writer, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] write(ImageWriter writer, IIOImage image, ImageWriteParam param) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, image, param);
            out.flush();
        } finally {
            writer.reset();
        }
        return bytes.toByteArray();
    }
}
