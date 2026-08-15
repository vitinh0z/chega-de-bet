package com.chegadebet.service.scraping;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um alvo de scraping controlado, para os testes do {@link DomainScraperClient}.
 * <p>
 * {@code com.sun.net.httpserver.HttpServer} vem no JDK, então não entra dependência nova
 * no projeto só para testar. O que se ganha em relação a um mock do {@code HttpClient} é
 * justamente o que interessa aqui: o teste passa por socket de verdade, e é assim que dá
 * para provar que o cliente parou de ler no teto de bytes em vez de baixar tudo e cortar
 * depois.
 * <p>
 * Escuta em {@code 127.0.0.1} numa porta sorteada pelo sistema, para execuções paralelas
 * não colidirem.
 */
class ServidorDeTeste implements AutoCloseable {

    /** Corpo padrão: HTML pequeno, com um sinal conhecido no título. */
    static final String HTML = """
            <html><head><title>Cassino online</title></head>
            <body><p>Depósito via Pix. Jogos de Pragmatic Play.</p></body></html>
            """;

    private final HttpServer servidor;

    /** Quantas vezes cada caminho foi pedido. É como provamos que o cliente NÃO seguiu. */
    private final Map<String, AtomicInteger> pedidos = new ConcurrentHashMap<>();

    /** O último cabeçalho Range recebido, para verificar que o cliente pediu só um pedaço. */
    private volatile String ultimoRange;

    ServidorDeTeste() throws IOException {
        this.servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        registrar("/ok", troca -> responder(troca, 200, "text/html; charset=UTF-8", HTML.getBytes(StandardCharsets.UTF_8)));

        // Ignora o Range e manda tudo: é o servidor que NÃO suporta Range (issue #134).
        // O corte tem que acontecer na leitura do cliente.
        registrar("/grande", troca -> responder(troca, 200, "text/html", encher(50_000)));

        // Respeita o Range e devolve 206 com o pedaço pedido.
        registrar("/range", troca -> {
            byte[] completo = encher(50_000);
            String range = troca.getRequestHeaders().getFirst("Range");
            if (range == null) {
                responder(troca, 200, "text/html", completo);
                return;
            }
            int fim = Math.min(Integer.parseInt(range.replaceAll("\\D+", " ").trim().split(" ")[1]),
                    completo.length - 1);
            byte[] pedaco = new byte[fim + 1];
            System.arraycopy(completo, 0, pedaco, 0, pedaco.length);
            troca.getResponseHeaders().add("Content-Range", "bytes 0-" + fim + "/" + completo.length);
            responder(troca, 206, "text/html", pedaco);
        });

        registrar("/redirect-1", troca -> redirecionar(troca, "/redirect-2"));
        registrar("/redirect-2", troca -> redirecionar(troca, "/ok"));
        // Laço infinito: cada volta manda para si mesmo.
        registrar("/laco", troca -> redirecionar(troca, "/laco"));
        // O ataque da issue #171: um alvo público mandando o scraper para o endpoint de
        // metadados da nuvem. O cliente tem que parar aqui, no segundo hop.
        registrar("/para-metadados", troca ->
                redirecionarPara(troca, "http://169.254.169.254/latest/meta-data/"));
        // Destino que o projeto já conhece: o corte da issue #133.
        registrar("/para-conhecido", troca ->
                redirecionarPara(troca, "http://ja-conhecido.invalid/pagina"));
        registrar("/sem-location", troca -> responder(troca, 302, "text/html", new byte[0]));

        registrar("/404", troca -> responder(troca, 404, "text/html", "nao existe".getBytes(StandardCharsets.UTF_8)));
        registrar("/503", troca -> responder(troca, 503, "text/html", "instavel".getBytes(StandardCharsets.UTF_8)));
        registrar("/pdf", troca -> responder(troca, 200, "application/pdf", new byte[2048]));

        // Segura a conexão além do timeout total configurado no teste.
        registrar("/lento", troca -> {
            dormir(5_000);
            responder(troca, 200, "text/html", HTML.getBytes(StandardCharsets.UTF_8));
        });

        // Declara um Content-Length pequeno e manda muito mais. É o servidor hostil: só o
        // contador de bytes na leitura protege contra ele.
        registrar("/mentiroso", troca -> {
            byte[] corpo = encher(50_000);
            troca.getResponseHeaders().add("Content-Type", "text/html");
            // 0 = comprimento desconhecido (chunked). O cliente não tem número nenhum
            // para confiar, e precisa cortar sozinho.
            troca.sendResponseHeaders(200, 0);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(corpo);
            } catch (IOException e) {
                // Esperado: o cliente fecha a conexão ao atingir o teto, e a escrita
                // restante quebra. É exatamente o comportamento sob teste.
            }
        });

        servidor.start();
    }

    /** A URL deste servidor para um caminho. */
    URI uri(String caminho) {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + caminho);
    }

    int pedidosEm(String caminho) {
        return pedidos.getOrDefault(caminho, new AtomicInteger()).get();
    }

    String ultimoRange() {
        return ultimoRange;
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    // ===== Infraestrutura =====

    private void registrar(String caminho, Manipulador manipulador) {
        pedidos.put(caminho, new AtomicInteger());
        servidor.createContext(caminho, troca -> {
            pedidos.get(caminho).incrementAndGet();
            String range = troca.getRequestHeaders().getFirst("Range");
            if (range != null) {
                ultimoRange = range;
            }
            try {
                manipulador.tratar(troca);
            } finally {
                troca.close();
            }
        });
    }

    private void responder(HttpExchange troca, int status, String tipo, byte[] corpo) throws IOException {
        troca.getResponseHeaders().add("Content-Type", tipo);
        troca.sendResponseHeaders(status, corpo.length == 0 ? -1 : corpo.length);
        if (corpo.length > 0) {
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(corpo);
            } catch (IOException e) {
                // O cliente cortou no teto de bytes. Esperado.
            }
        }
    }

    private void redirecionar(HttpExchange troca, String caminho) throws IOException {
        redirecionarPara(troca, uri(caminho).toString());
    }

    private void redirecionarPara(HttpExchange troca, String destino) throws IOException {
        troca.getResponseHeaders().add("Location", destino);
        troca.sendResponseHeaders(302, -1);
    }

    /** HTML com um sinal só no fim, para o truncamento ser detectável. */
    private static byte[] encher(int tamanho) {
        StringBuilder sb = new StringBuilder(tamanho + 128);
        sb.append("<html><body><p>");
        while (sb.length() < tamanho) {
            sb.append("conteudo de enchimento sem sinal nenhum. ");
        }
        sb.append("</p><footer>Curaçao eGaming</footer></body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Manipulador {
        void tratar(HttpExchange troca) throws IOException;
    }
}
