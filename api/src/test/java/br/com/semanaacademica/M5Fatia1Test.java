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

public class M5Fatia1Test {
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
    public void organizacao_acessa_painel_atividades_com_ocupacao_e_frequencia() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/relogio"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"agora\":\"2026-10-20T12:00:00-03:00\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        try (java.sql.Connection conn = Database.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_m5_1', 'Atividade Painel', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_m5_1', 'atv_m5_1', '2026-10-19T10:00:00-03:00', '2026-10-19T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_m5_1', 'atv_m5_1', 'p-carla', 'confirmada', '2026-10-18T09:00:00-03:00')");
            stmt.execute("INSERT INTO presencas(id, encontroId, participanteId, origem, lidoEm, registradaEm) VALUES('pre_m5_1', 'enc_m5_1', 'p-carla', 'qr', '2026-10-19T10:10:00-03:00', '2026-10-19T10:10:00-03:00')");
        }

        HttpResponse<String> response = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/atividades"))
                .header("X-Usuario", "org-ana").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        List<?> painel = mapper.readValue(response.body(), List.class);
        boolean found = false;
        for (Object obj : painel) {
            Map<?, ?> item = (Map<?, ?>) obj;
            if ("atv_m5_1".equals(item.get("atividadeId"))) {
                found = true;
                assertEquals(10, item.get("vagas"));
                assertEquals(1, item.get("ocupadas"));
                assertEquals(0, item.get("emEspera"));
                assertEquals(10.0, ((Number) item.get("ocupacaoPercentual")).doubleValue());
                assertEquals(100.0, ((Number) item.get("frequenciaPercentual")).doubleValue());
            }
        }
        assertTrue(found);
    }

    @Test
    public void participante_nao_acessa_painel_atividades() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/atividades"))
                .header("X-Usuario", "p-carla").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertEquals("SOMENTE_ORGANIZACAO", mapper.readValue(response.body(), Map.class).get("erro"));
    }
}
