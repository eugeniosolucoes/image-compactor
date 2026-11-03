package br.com.eugeniosolucoes.imagecompactor;

/**
 * Resultado da compactação de uma imagem em memória.
 *
 * @param data           bytes da imagem compactada
 * @param extension      extensão do arquivo gerado ("jpg" ou "png")
 * @param quality        qualidade JPEG utilizada (1.0 para PNG, sem perdas)
 * @param originalWidth  largura original
 * @param originalHeight altura original
 * @param width          largura final
 * @param height         altura final
 */
public record CompressedImage(byte[] data, String extension, float quality,
                              int originalWidth, int originalHeight, int width, int height) {

    public boolean dimensionsKept() {
        return originalWidth == width && originalHeight == height;
    }
}
