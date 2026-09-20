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

public class M5Fatia3Test {
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
    public void participante_bloqueado_nao_consegue_inscrever() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/relogio"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"agora\":\"2026-10-21T15:00:00-03:00\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        try (java.sql.Connection conn = Database.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_1', 'Atividade 1', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_1', 'atv_b_1', '2026-10-19T10:00:00-03:00', '2026-10-19T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_b_1', 'atv_b_1', 'p-heitor', 'confirmada', '2026-10-18T09:00:00-03:00')");

            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_2', 'Atividade 2', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_2', 'atv_b_2', '2026-10-20T10:00:00-03:00', '2026-10-20T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_b_2', 'atv_b_2', 'p-heitor', 'confirmada', '2026-10-18T09:00:00-03:00')");

            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_3', 'Atividade Nova', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_3', 'atv_b_3', '2026-10-22T10:00:00-03:00', '2026-10-22T12:00:00-03:00')");
        }

        // Check GET /painel/bloqueios
        HttpResponse<String> respBloqueios = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/bloqueios"))
                .header("X-Usuario", "org-ana").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respBloqueios.statusCode());
        List<?> bloqueios = mapper.readValue(respBloqueios.body(), List.class);
        assertEquals(1, bloqueios.size());

        // Try registering p-heitor in atv_b_3 -> should fail with 422 INSCRICAO_BLOQUEADA
        HttpResponse<String> respIns = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/atividades/atv_b_3/inscricoes"))
                .header("X-Usuario", "p-heitor").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, respIns.statusCode());
        assertEquals("INSCRICAO_BLOQUEADA", mapper.readValue(respIns.body(), Map.class).get("erro"));
    }

    @Test
    public void organizacao_desbloqueia_participante() throws Exception {
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/_teste/relogio"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"agora\":\"2026-10-21T15:00:00-03:00\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        try (java.sql.Connection conn = Database.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_1', 'Atividade 1', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_1', 'atv_b_1', '2026-10-19T10:00:00-03:00', '2026-10-19T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_b_1', 'atv_b_1', 'p-heitor', 'confirmada', '2026-10-18T09:00:00-03:00')");

            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_2', 'Atividade 2', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_2', 'atv_b_2', '2026-10-20T10:00:00-03:00', '2026-10-20T12:00:00-03:00')");
            stmt.execute("INSERT INTO inscricoes(id, atividadeId, participanteId, status, criadaEm) VALUES('ins_b_2', 'atv_b_2', 'p-heitor', 'confirmada', '2026-10-18T09:00:00-03:00')");

            stmt.execute("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES('atv_b_3', 'Atividade Nova', 'minicurso', 'lab-3', 10, 0)");
            stmt.execute("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES('enc_b_3', 'atv_b_3', '2026-10-22T10:00:00-03:00', '2026-10-22T12:00:00-03:00')");
        }

        // DELETE /painel/bloqueios/p-heitor -> 204
        HttpResponse<String> respDel = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/bloqueios/p-heitor"))
                .header("X-Usuario", "org-ana").DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, respDel.statusCode());

        // Try deleting again -> 404
        HttpResponse<String> respDel2 = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/painel/bloqueios/p-heitor"))
                .header("X-Usuario", "org-ana").DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, respDel2.statusCode());

        // Now p-heitor can register in atv_b_3 -> 201
        HttpResponse<String> respIns = client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/atividades/atv_b_3/inscricoes"))
                .header("X-Usuario", "p-heitor").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, respIns.statusCode());
    }
}
