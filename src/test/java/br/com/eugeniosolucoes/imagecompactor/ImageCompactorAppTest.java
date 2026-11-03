package br.com.eugeniosolucoes.imagecompactor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageCompactorAppTest {

    private static final long LIMIT = ImageCompressor.DEFAULT_LIMIT_BYTES;

    @TempDir
    Path dir;

    @Test
    void copiaPequenasSemAlteracaoECompactaGrandesAbaixoDoLimite() throws IOException {
        Path source = Files.createDirectories(dir.resolve("fotos-originais"));
        Path target = dir.resolve("fotos-compactadas");
        Path log = dir.resolve("compactacao.log");

        Path small = source.resolve("pequena.jpg");
        Files.write(small, jpeg(noise(400, 300, 1), 0.9f));
        Path large = source.resolve("grande.jpg");
        Files.write(large, jpeg(noise(2000, 1500, 2), 1.0f));
        Path bmp = source.resolve("ruido.bmp");
        ImageIO.write(noise(1200, 900, 3), "bmp", bmp.toFile());
        Files.writeString(source.resolve("dados.json"), "{}");

        assertTrue(Files.size(small) < LIMIT);
        assertTrue(Files.size(large) >= LIMIT, "imagem de teste deve ter >= 2 MB");
        assertTrue(Files.size(bmp) >= LIMIT, "bmp de teste deve ter >= 2 MB");

        new ImageCompactorApp(LIMIT).run(source, target, log);

        assertArrayEquals(Files.readAllBytes(small), Files.readAllBytes(target.resolve("pequena.jpg")));

        Path compressedJpg = target.resolve("grande.jpg");
        assertTrue(Files.size(compressedJpg) < LIMIT);
        assertNotNull(ImageIO.read(compressedJpg.toFile()));

        // Ruído não cabe em PNG sem perdas, então o BMP vira JPEG
        assertTrue(Files.size(target.resolve("ruido.jpg")) < LIMIT);
        assertFalse(Files.exists(target.resolve("dados.json")));

        List<String> lines = Files.readAllLines(log);
        assertTrue(lines.stream().anyMatch(l -> l.contains("pequena.jpg") && l.contains("compactação: 0,00%")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("grande.jpg") && l.contains("COMPACTADA")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("ruido.bmp") && l.contains("gravada como ruido.jpg") && l.contains("COMPACTADA")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("dados.json") && l.contains("IGNORADO")));
    }

    @Test
    void reduzDimensoesSomenteQuandoQualidadeMinimaNaoBasta() throws IOException {
        Path file = dir.resolve("enorme.jpg");
        Files.write(file, jpeg(noise(4000, 3000, 4), 1.0f));

        CompressedImage result = new ImageCompressor(LIMIT).compress(file);

        assertTrue(result.data().length < LIMIT);
        assertFalse(result.dimensionsKept());
        assertTrue(result.quality() >= ImageCompressor.MIN_QUALITY_PCT / 100f);
    }

    @Test
    void calculaPercentual() {
        assertEquals("0,00%", CompactionLog.percent(1000, 1000));
        assertEquals("75,00%", CompactionLog.percent(4000, 1000));
    }

    private static BufferedImage noise(int w, int h, long seed) {
        Random random = new Random(seed);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
