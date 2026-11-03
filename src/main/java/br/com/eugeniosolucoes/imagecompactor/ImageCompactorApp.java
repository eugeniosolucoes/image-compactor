package br.com.eugeniosolucoes.imagecompactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Copia as imagens de {@code fotos-originais} para {@code fotos-compactadas}. Imagens com {@code >= 2 MB} são
 * compactadas para ficar abaixo desse limite; as demais são copiadas byte a byte.
 *
 * <p>Uso: {@code java -jar image-compactor.jar [pastaOrigem] [pastaDestino] [arquivoLog]}
 */
public final class ImageCompactorApp {

    static final String DEFAULT_SOURCE = "fotos-originais";
    static final String DEFAULT_TARGET = "fotos-compactadas";
    static final String DEFAULT_LOG = "compactacao.log";

    private final long limitBytes;
    private final ImageCompressor compressor;
    private final Set<String> imageSuffixes;

    public ImageCompactorApp(long limitBytes) {
        this.limitBytes = limitBytes;
        this.compressor = new ImageCompressor(limitBytes);
        this.imageSuffixes = Arrays.stream(ImageIO.getReaderFileSuffixes())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static void main(String[] args) throws IOException {
        Path source = Path.of(args.length > 0 ? args[0] : DEFAULT_SOURCE);
        Path target = Path.of(args.length > 1 ? args[1] : DEFAULT_TARGET);
        Path logFile = Path.of(args.length > 2 ? args[2] : DEFAULT_LOG);

        if (!Files.isDirectory(source)) {
            System.err.println("Pasta de origem não encontrada: " + source.toAbsolutePath());
            System.exit(1);
        }
        new ImageCompactorApp(ImageCompressor.DEFAULT_LIMIT_BYTES).run(source, target, logFile);
    }

    public void run(Path source, Path target, Path logFile) throws IOException {
        Files.createDirectories(target);
        List<Path> files;
        try (Stream<Path> list = Files.list(source)) {
            files = list.filter(Files::isRegularFile).sorted().toList();
        }

        try (CompactionLog log = new CompactionLog(logFile)) {
            log.start(source, target, limitBytes);
            for (Path file : files) {
                process(file, target, log);
            }
            log.finish();
        }
    }

    private void process(Path file, Path target, CompactionLog log) throws IOException {
        String name = file.getFileName().toString();
        if (!imageSuffixes.contains(extension(name))) {
            log.skipped(name);
            return;
        }
        long size = Files.size(file);
        try {
            if (size < limitBytes) {
                Files.copy(file, target.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                log.copied(name, size);
                return;
            }
            CompressedImage result = compressor.compress(file);
            String outputName = withExtension(name, result.extension());
            Path output = target.resolve(outputName);
            Files.write(output, result.data());
            Files.setLastModifiedTime(output, Files.getLastModifiedTime(file));
            log.compressed(name, outputName, size, result);
        } catch (IOException | RuntimeException e) {
            log.failed(name, size, e);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Mantém a extensão original quando ela já corresponde ao formato gerado (ex.: ".JPEG" continua ".JPEG"). */
    private static String withExtension(String name, String newExtension) {
        String current = extension(name);
        boolean sameFormat = current.equals(newExtension)
                || ("jpg".equals(newExtension) && (current.equals("jpeg") || current.equals("jpe")));
        if (sameFormat) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "." + newExtension;
    }
}
