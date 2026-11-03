package br.com.eugeniosolucoes.imagecompactor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Arquivo de log (modo append) com uma linha por imagem e um resumo por execução. Também ecoa no console. */
public final class CompactionLog implements Closeable {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Locale PT_BR = Locale.of("pt", "BR");

    private final BufferedWriter writer;
    private long totalOriginal;
    private long totalFinal;
    private int copied;
    private int compressed;
    private int failed;

    public CompactionLog(Path logFile) throws IOException {
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void start(Path source, Path target, long limitBytes) throws IOException {
        line("==== Início da execução | origem: %s | destino: %s | limite: %s ====",
                source.toAbsolutePath(), target.toAbsolutePath(), size(limitBytes));
    }

    public void copied(String name, long size) throws IOException {
        copied++;
        totals(size, size);
        line("%s | original: %s | final: %s | compactação: %s | COPIADA SEM ALTERAÇÃO",
                name, size(size), size(size), percent(size, size));
    }

    public void compressed(String name, String outputName, long originalSize, CompressedImage result)
            throws IOException {
        compressed++;
        long finalSize = result.data().length;
        totals(originalSize, finalSize);
        String detail = "png".equals(result.extension())
                ? "PNG sem perdas"
                : String.format(PT_BR, "qualidade JPEG %d%%", Math.round(result.quality() * 100));
        String dimensions = result.dimensionsKept()
                ? String.format("dimensões %dx%d mantidas", result.width(), result.height())
                : String.format("dimensões %dx%d -> %dx%d", result.originalWidth(), result.originalHeight(),
                        result.width(), result.height());
        String renamed = name.equals(outputName) ? "" : " | gravada como " + outputName;
        line("%s | original: %s | final: %s | compactação: %s | COMPACTADA (%s, %s)%s",
                name, size(originalSize), size(finalSize), percent(originalSize, finalSize),
                detail, dimensions, renamed);
    }

    public void failed(String name, long size, Exception error) throws IOException {
        failed++;
        line("%s | original: %s | ERRO: %s", name, size(size), error.getMessage());
    }

    public void skipped(String name) throws IOException {
        line("%s | IGNORADO (não é uma imagem suportada)", name);
    }

    public void finish() throws IOException {
        line("==== Fim | copiadas sem alteração: %d | compactadas: %d | erros: %d | total: %s -> %s (%s) ====",
                copied, compressed, failed, size(totalOriginal), size(totalFinal),
                percent(totalOriginal, totalFinal));
    }

    private void totals(long original, long fin) {
        totalOriginal += original;
        totalFinal += fin;
    }

    static String percent(long original, long fin) {
        double pct = original == 0 ? 0 : (1 - (double) fin / original) * 100;
        return String.format(PT_BR, "%.2f%%", pct);
    }

    private static String size(long bytes) {
        return String.format(PT_BR, "%,d bytes (%.2f MB)", bytes, bytes / (1024.0 * 1024.0));
    }

    private void line(String format, Object... args) throws IOException {
        String text = LocalDateTime.now().format(TIMESTAMP) + " | " + String.format(format, args);
        System.out.println(text);
        writer.write(text);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
