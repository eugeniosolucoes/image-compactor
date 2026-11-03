# image-compactor

Copia as imagens da pasta `fotos-originais` para a pasta `fotos-compactadas`. As imagens com **2 MB ou mais** (2.097.152 bytes) são compactadas para ficar abaixo desse limite. As demais são copiadas byte a byte, sem alteração.

A compactação tenta manter a qualidade e as dimensões originais sempre que possível. O percentual de compactação de cada imagem copiada fica registrado em um arquivo de log.

## Requisitos

- Java 21
- Maven 3.9+

## Como executar

```
mvn package
java -jar target/image-compactor-0.0.1-SNAPSHOT.jar [origem] [destino] [arquivoLog]
```

Todos os argumentos são opcionais:

| Argumento    | Padrão              |
|--------------|---------------------|
| `origem`     | `fotos-originais`   |
| `destino`    | `fotos-compactadas` |
| `arquivoLog` | `compactacao.log`   |

Também é possível executar via Maven com `mvn compile exec:java`.

> No console do Windows os acentos podem aparecer trocados; o arquivo de log é sempre gravado em UTF-8. Para corrigir também no console, use `java -Dstdout.encoding=UTF-8 -jar ...`.

## Como funciona

Para cada arquivo da pasta de origem:

- **Não é imagem** (ex.: `.json`): ignorado e registrado como `IGNORADO` no log.
- **Menor que 2 MB:** copiado sem alteração; o log registra 0,00% de compactação.
- **2 MB ou mais:** compactado da seguinte forma:
  1. Tenta salvar em JPEG com qualidade 95%. Se ficar abaixo de 2 MB, termina aqui.
  2. Se não couber, procura (busca binária) a maior qualidade entre 95% e 70% que fique abaixo do limite, mantendo as dimensões.
  3. Só se nem a qualidade 70% couber, reduz as dimensões proporcionalmente (interpolação bicúbica) e repete a busca pela melhor qualidade.

Detalhes:

- Imagens que não são JPEG (BMP, PNG, TIFF, WebP) tentam primeiro PNG, que não perde qualidade. Se o PNG não couber no limite, são convertidas para JPEG e o arquivo de destino recebe a nova extensão (indicada no log).
- Os metadados EXIF do JPEG original (orientação, data, câmera etc.) são preservados.
- A data de modificação do arquivo original é mantida na cópia.
- Arquivos já existentes no destino são sobrescritos.

## Arquivo de log

O log tem uma linha por arquivo, com tamanho original, tamanho final, percentual de compactação e o que foi feito, além de um resumo ao final. A cada execução as linhas são acrescentadas ao final do arquivo, sem apagar as execuções anteriores.

Exemplo:

```
2026-11-02 16:03:29 | ==== Início da execução | origem: ...\fotos-originais | destino: ...\fotos-compactadas | limite: 2.097.152 bytes (2,00 MB) ====
2026-11-02 16:03:28 | 1000_1.jpg | original: 749.544 bytes (0,71 MB) | final: 749.544 bytes (0,71 MB) | compactação: 0,00% | COPIADA SEM ALTERAÇÃO
2026-11-02 16:03:31 | 1486_1.jpg | original: 5.031.869 bytes (4,80 MB) | final: 1.930.484 bytes (1,84 MB) | compactação: 61,63% | COMPACTADA (qualidade JPEG 95%, dimensões 2816x2112 mantidas)
2026-11-02 16:03:53 | response.json | IGNORADO (não é uma imagem suportada)
2026-11-02 16:03:53 | ==== Fim | copiadas sem alteração: 675 | compactadas: 25 | erros: 0 | total: 373.170.907 bytes (355,88 MB) -> 306.315.110 bytes (292,12 MB) (17,92%) ====
```

## Resultado da primeira execução

Com as 701 fotos da pasta `fotos-originais` (697 JPG, 3 BMP e 1 JSON):

- 675 imagens abaixo de 2 MB copiadas sem alteração.
- 25 imagens de 2,7 a 4,9 MB compactadas para 1,2 a 1,99 MB (26% a 69% de redução), todas com as dimensões originais mantidas (2816x2112) e qualidade JPEG entre 89% e 95%.
- Total de 355,88 MB para 292,12 MB (17,92% de redução), em cerca de 25 segundos.

## Estrutura do projeto

| Arquivo | Responsabilidade |
|---------|------------------|
| [`ImageCompactorApp`](src/main/java/br/com/eugeniosolucoes/imagecompactor/ImageCompactorApp.java) | Ponto de entrada: percorre a pasta de origem e decide copiar, compactar ou ignorar cada arquivo. |
| [`ImageCompressor`](src/main/java/br/com/eugeniosolucoes/imagecompactor/ImageCompressor.java) | Algoritmo de compactação (qualidade, redimensionamento, metadados). |
| [`CompactionLog`](src/main/java/br/com/eugeniosolucoes/imagecompactor/CompactionLog.java) | Grava o arquivo de log e o resumo. |
| [`CompressedImage`](src/main/java/br/com/eugeniosolucoes/imagecompactor/CompressedImage.java) | Resultado de uma compactação. |
| [`ImageCompactorAppTest`](src/test/java/br/com/eugeniosolucoes/imagecompactor/ImageCompactorAppTest.java) | Testes JUnit 5 com imagens geradas no próprio teste. |

Dependências: [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys), para ler imagens com mais robustez (JPEG CMYK, BMP, TIFF, WebP). O `maven-shade-plugin` gera um jar executável com todas as dependências incluídas.

## Testes

```
mvn test
```
