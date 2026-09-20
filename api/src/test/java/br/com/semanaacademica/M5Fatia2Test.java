package br.com.semanaacademica;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class M5Fatia2Test {
    private static io.javalin.Javalin app;
    private static String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    public static void setUp() {
        System.setProperty("MODO_TESTE", "1");
        app = Main.startApp(0);
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterAll
    public static void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    public void organizacao_obtem_sem_chance() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/relogio"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"agora\":\"2026-10-21T15:00:00-03:00\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        try (java.sql.Connection conn = Database.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_sc_1', 'Atividade Sem Chance', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_sc_1', 'atv_sc_1', '2026-10-19T10:00:00-03:00', '2026-10-19T12:00:00-03:00')");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_sc_2', 'atv_sc_1', '2026-10-20T10:00:00-03:00', '2026-10-20T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_sc_1', 'atv_sc_1', 'p-carla', 'confirmada', '2026-10-18T09:00:00-03:00')");
        }

        HttpResponse<String> response = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/atividades/atv_sc_1/sem-chance"))
                .header("X-Usuario", "org-ana").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        List<?> list = mapper.readValue(response.body(), List.class);
        assertEquals(1, list.size());
        Map<?, ?> item = (Map<?, ?>) list.get(0);
        assertEquals("p-carla", item.get("participanteId"));
        assertEquals(2, item.get("faltas"));
        assertEquals(0, item.get("faltasPermitidas"));
    }

    @Test
    public void organizacao_obtem_frequencia_csv() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/relogio"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"agora\":\"2026-10-21T15:00:00-03:00\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        try (java.sql.Connection conn = Database.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_csv_1', 'Atividade CSV', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_csv_1', 'atv_csv_1', '2026-10-19T10:00:00-03:00', '2026-10-19T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_csv_1', 'atv_csv_1', 'p-carla', 'confirmada', '2026-10-18T09:00:00-03:00')");
            stmt.execute("INSERT INTO presencas(id, encontroId, participanteId, origem, lidoEm, registradaEm) VALUES('pre_csv_1', 'enc_csv_1', 'p-carla', 'qr', '2026-10-19T10:10:00-03:00', '2026-10-19T10:10:00-03:00')");
        }

        HttpResponse<String> response = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/atividades/atv_csv_1/frequencia.csv"))
                .header("X-Usuario", "org-ana").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("\uFEFF"));
        assertTrue(response.body().contains("Carla Mendes Souza;P;1,0;nao"));
    }
}
