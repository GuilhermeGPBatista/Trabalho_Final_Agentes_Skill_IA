package br.com.semanaacademica;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.sql.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

public class Main {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        int port = 3000;
        String portEnv = System.getenv("PORT");
        if (portEnv != null) {
            port = Integer.parseInt(portEnv);
        }
        startApp(port);
    }

    public static Javalin startApp(int port) {
        Database.initDb();

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableDevLogging();
        });

        app.before(ctx -> {
            String path = ctx.path();
            if (path.startsWith("/_teste/") || path.matches("^/certificados/[^/]+$")) {
                return;
            }

            try (Connection conn = Database.getConnection()) {
                processExpirationsAndCascades(conn);
            } catch (Exception ignored) {}

            String xUsuario = ctx.header("X-Usuario");
            if (xUsuario == null || !Database.userExists(xUsuario)) {
                ctx.status(401);
                Map<String, String> err = new HashMap<>();
                err.put("erro", "USUARIO_DESCONHECIDO");
                err.put("mensagem", "Usuário não informado ou desconhecido");
                ctx.json(err);
                ctx.skipRemainingHandlers();
                return;
            }
        });

        app.post("/_teste/reset", ctx -> {
            if (!isTestMode()) {
                ctx.status(404);
                return;
            }
            Database.resetInitialData();
            ctx.status(204);
        });

        app.put("/_teste/relogio", ctx -> {
            if (!isTestMode()) {
                ctx.status(404);
                return;
            }
            Map body = ctx.bodyAsClass(Map.class);
            String agoraStr = (String) body.get("agora");
            OffsetDateTime dt = OffsetDateTime.parse(agoraStr);
            Database.setClock(dt);
            ctx.json(Map.of("agora", dt.toString()));
        });

        app.get("/_teste/relogio", ctx -> {
            if (!isTestMode()) {
                ctx.status(404);
                return;
            }
            ctx.json(Map.of("agora", Database.getClock().toString()));
        });

        app.get("/salas", ctx -> {
            List<Map<String, Object>> salas = new ArrayList<>();
            try (Connection conn = Database.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, nome, capacidade FROM salas")) {
                while (rs.next()) {
                    Map<String, Object> sala = new HashMap<>();
                    sala.put("id", rs.getString("id"));
                    sala.put("nome", rs.getString("nome"));
                    sala.put("capacidade", rs.getInt("capacidade"));
                    salas.add(sala);
                }
            }
            ctx.json(salas);
        });

        app.get("/atividades", ctx -> {
            String dia = ctx.queryParam("dia");
            String tipo = ctx.queryParam("tipo");

            List<Map<String, Object>> atividades = new ArrayList<>();
            try (Connection conn = Database.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, titulo, tipo, salaId, vagas, cancelada FROM atividades")) {
                while (rs.next()) {
                    String atvId = rs.getString("id");
                    Map<String, Object> atv = buildAtividade(conn, atvId);
                    if (atv == null) continue;

                    boolean match = true;
                    if (tipo != null && !tipo.isEmpty()) {
                        if (!tipo.equalsIgnoreCase((String) atv.get("tipo"))) {
                            match = false;
                        }
                    }
                    if (dia != null && !dia.isEmpty()) {
                        boolean matchDia = false;
                        List<Map<String, String>> encontros = (List<Map<String, String>>) atv.get("encontros");
                        for (Map<String, String> enc : encontros) {
                            String inicio = enc.get("inicio");
                            if (inicio.startsWith(dia)) {
                                matchDia = true;
                                break;
                            }
                        }
                        if (!matchDia) match = false;
                    }

                    if (match) {
                        atividades.add(atv);
                    }
                }
            }

            atividades.sort((a, b) -> {
                List<Map<String, String>> encA = (List<Map<String, String>>) a.get("encontros");
                List<Map<String, String>> encB = (List<Map<String, String>>) b.get("encontros");
                String startA = encA.isEmpty() ? "" : encA.get(0).get("inicio");
                String startB = encB.isEmpty() ? "" : encB.get(0).get("inicio");
                int cmp = startA.compareTo(startB);
                if (cmp != 0) return cmp;
                return ((String) a.get("titulo")).compareTo((String) b.get("titulo"));
            });

            ctx.json(atividades);
        });

        app.get("/atividades/{id}", ctx -> {
            String id = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                Map<String, Object> atv = buildAtividade(conn, id);
                if (atv == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }
                ctx.json(atv);
            }
        });

        app.post("/atividades", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            Map body;
            try {
                body = ctx.bodyAsClass(Map.class);
            } catch (Exception e) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Corpo inválido"));
                return;
            }

            if (body == null || !body.containsKey("titulo") || !body.containsKey("tipo") || !body.containsKey("salaId") || !body.containsKey("vagas") || !body.containsKey("encontros")) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Campos obrigatórios ausentes"));
                return;
            }

            String titulo = (String) body.get("titulo");
            String tipo = (String) body.get("tipo");
            String salaId = (String) body.get("salaId");
            Object vagasObj = body.get("vagas");
            List<Map<String, String>> encontrosInput = (List<Map<String, String>>) body.get("encontros");

            if (!"palestra".equals(tipo) && !"minicurso".equals(tipo)) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Tipo inválido"));
                return;
            }

            if (!(vagasObj instanceof Number)) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Vagas inválidas"));
                return;
            }
            int vagas = ((Number) vagasObj).intValue();
            if (vagas <= 0) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Vagas menores ou iguais a zero"));
                return;
            }

            try (Connection conn = Database.getConnection()) {
                PreparedStatement psSala = conn.prepareStatement("SELECT capacidade FROM salas WHERE id = ?");
                psSala.setString(1, salaId);
                ResultSet rsSala = psSala.executeQuery();
                if (!rsSala.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Sala não encontrada"));
                    return;
                }
                int capacidadeSala = rsSala.getInt("capacidade");

                if (encontrosInput == null) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "QUANTIDADE_DE_ENCONTROS", "mensagem", "Encontros nulos"));
                    return;
                }

                class ParsedEnc {
                    OffsetDateTime inicio;
                    OffsetDateTime fim;
                }

                List<ParsedEnc> parsed = new ArrayList<>();
                for (Map<String, String> encMap : encontrosInput) {
                    String inicioStr = encMap.get("inicio");
                    String fimStr = encMap.get("fim");
                    if (inicioStr == null || fimStr == null) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Encontro sem início ou fim"));
                        return;
                    }
                    ParsedEnc pe = new ParsedEnc();
                    try {
                        pe.inicio = OffsetDateTime.parse(inicioStr);
                        pe.fim = OffsetDateTime.parse(fimStr);
                    } catch (Exception e) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "ENCONTRO_INVALIDO", "mensagem", "Data inválida"));
                        return;
                    }
                    parsed.add(pe);
                }

                // 1º Conflito de sala/horário (409 CONFLITO_DE_SALA)
                boolean salaConflito = false;
                PreparedStatement psCheck = conn.prepareStatement(
                    "SELECT e.inicio, e.fim FROM encontros e " +
                    "JOIN atividades a ON e.atividadeId = a.id " +
                    "WHERE a.salaId = ? AND a.cancelada = 0"
                );
                psCheck.setString(1, salaId);
                ResultSet rsCheck = psCheck.executeQuery();
                List<ParsedEnc> existing = new ArrayList<>();
                while (rsCheck.next()) {
                    ParsedEnc pe = new ParsedEnc();
                    pe.inicio = OffsetDateTime.parse(rsCheck.getString("inicio"));
                    pe.fim = OffsetDateTime.parse(rsCheck.getString("fim"));
                    existing.add(pe);
                }

                for (ParsedEnc n : parsed) {
                    for (ParsedEnc ex : existing) {
                        if (n.inicio.isBefore(ex.fim) && ex.inicio.isBefore(n.fim)) {
                            salaConflito = true;
                            break;
                        }
                    }
                    if (salaConflito) break;
                }

                if (salaConflito) {
                    ctx.status(409);
                    ctx.json(Map.of("erro", "CONFLITO_DE_SALA", "mensagem", "Conflito de horário na sala"));
                    return;
                }

                // 2º Número de encontros e validade dos encontros (422 QUANTIDADE_DE_ENCONTROS / 422 ENCONTRO_INVALIDO)
                if ("palestra".equals(tipo) && parsed.size() != 1) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "QUANTIDADE_DE_ENCONTROS", "mensagem", "Palestra deve ter exatamente 1 encontro"));
                    return;
                }
                if ("minicurso".equals(tipo) && (parsed.size() < 2 || parsed.size() > 5)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "QUANTIDADE_DE_ENCONTROS", "mensagem", "Minicurso deve ter entre 2 e 5 encontros"));
                    return;
                }

                for (int i = 0; i < parsed.size(); i++) {
                    ParsedEnc pe = parsed.get(i);
                    long mins = Duration.between(pe.inicio, pe.fim).toMinutes();
                    if (mins < 60 || mins > 240) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "ENCONTRO_INVALIDO", "mensagem", "Duração do encontro deve ser de 1 a 4 horas"));
                        return;
                    }
                    if (!pe.inicio.toLocalDate().equals(pe.fim.toLocalDate())) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "ENCONTRO_INVALIDO", "mensagem", "Encontro deve iniciar e terminar no mesmo dia"));
                        return;
                    }
                    for (int j = i + 1; j < parsed.size(); j++) {
                        ParsedEnc other = parsed.get(j);
                        if (pe.inicio.isBefore(other.fim) && other.inicio.isBefore(pe.fim)) {
                            ctx.status(422);
                            ctx.json(Map.of("erro", "ENCONTRO_INVALIDO", "mensagem", "Encontros da mesma atividade não podem se sobrepor"));
                            return;
                        }
                    }
                }

                // 3º Vagas acima da capacidade (422 VAGAS_ACIMA_DA_CAPACIDADE)
                if (vagas > capacidadeSala) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "VAGAS_ACIMA_DA_CAPACIDADE", "mensagem", "Vagas excedem a capacidade da sala"));
                    return;
                }

                String atvId = "atv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                try (PreparedStatement psIns = conn.prepareStatement("INSERT INTO atividades(id, titulo, tipo, salaId, vagas, cancelada) VALUES(?, ?, ?, ?, ?, 0)")) {
                    psIns.setString(1, atvId);
                    psIns.setString(2, titulo);
                    psIns.setString(3, tipo);
                    psIns.setString(4, salaId);
                    psIns.setInt(5, vagas);
                    psIns.executeUpdate();
                }

                for (ParsedEnc pe : parsed) {
                    String encId = "enc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    try (PreparedStatement psEncIns = conn.prepareStatement("INSERT INTO encontros(id, atividadeId, inicio, fim) VALUES(?, ?, ?, ?)")) {
                        psEncIns.setString(1, encId);
                        psEncIns.setString(2, atvId);
                        psEncIns.setString(3, pe.inicio.toString());
                        psEncIns.setString(4, pe.fim.toString());
                        psEncIns.executeUpdate();
                    }
                }

                Map<String, Object> created = buildAtividade(conn, atvId);
                ctx.status(201);
                ctx.json(created);
            }
        });

        app.patch("/atividades/{id}", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            String id = ctx.pathParam("id");
            Map body;
            try {
                body = ctx.bodyAsClass(Map.class);
            } catch (Exception e) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Corpo inválido"));
                return;
            }

            if (body == null) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Corpo ausente"));
                return;
            }

            try (Connection conn = Database.getConnection()) {
                Map<String, Object> atv = buildAtividade(conn, id);
                if (atv == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }

                if ("cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada não pode ser editada"));
                    return;
                }

                for (Object key : body.keySet()) {
                    String k = (String) key;
                    if (!"titulo".equals(k) && !"vagas".equals(k)) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "CAMPO_NAO_EDITAVEL", "mensagem", "Campo não editável"));
                        return;
                    }
                }

                String newTitulo = (String) body.get("titulo");
                Object newVagasObj = body.get("vagas");

                String sqlUpdate = "UPDATE atividades SET ";
                List<Object> params = new ArrayList<>();
                boolean hasUpdate = false;

                if (newTitulo != null) {
                    sqlUpdate += "titulo = ?";
                    params.add(newTitulo);
                    hasUpdate = true;
                }

                if (newVagasObj != null) {
                    if (!(newVagasObj instanceof Number)) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Vagas inválidas"));
                        return;
                    }
                    int newVagas = ((Number) newVagasObj).intValue();
                    if (newVagas <= 0) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Vagas menores ou iguais a zero"));
                        return;
                    }

                    String salaId = (String) atv.get("salaId");
                    PreparedStatement psSala = conn.prepareStatement("SELECT capacidade FROM salas WHERE id = ?");
                    psSala.setString(1, salaId);
                    ResultSet rsSala = psSala.executeQuery();
                    if (rsSala.next()) {
                        int cap = rsSala.getInt("capacidade");
                        if (newVagas > cap) {
                            ctx.status(422);
                            ctx.json(Map.of("erro", "VAGAS_ACIMA_DA_CAPACIDADE", "mensagem", "Vagas acima da capacidade da sala"));
                            return;
                        }
                    }

                    int ocupadas = (int) atv.get("ocupadas");
                    if (newVagas < ocupadas) {
                        ctx.status(409);
                        ctx.json(Map.of("erro", "VAGAS_ABAIXO_DOS_INSCRITOS", "mensagem", "Vagas abaixo dos inscritos"));
                        return;
                    }

                    if (hasUpdate) {
                        sqlUpdate += ", ";
                    }
                    sqlUpdate += "vagas = ?";
                    params.add(newVagas);
                    hasUpdate = true;
                }

                if (hasUpdate) {
                    sqlUpdate += " WHERE id = ?";
                    params.add(id);
                    try (PreparedStatement psUp = conn.prepareStatement(sqlUpdate)) {
                        for (int i = 0; i < params.size(); i++) {
                            psUp.setObject(i + 1, params.get(i));
                        }
                        psUp.executeUpdate();
                    }
                }

                Map<String, Object> updated = buildAtividade(conn, id);
                ctx.json(updated);
            }
        });

        app.post("/atividades/{id}/cancelamento", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            String id = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                Map<String, Object> atv = buildAtividade(conn, id);
                if (atv == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }

                if ("cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade já cancelada"));
                    return;
                }

                String situacao = (String) atv.get("situacao");
                if ("em_andamento".equals(situacao) || "encerrada".equals(situacao)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_JA_INICIADA", "mensagem", "Atividade já iniciada"));
                    return;
                }

                try (PreparedStatement psUp = conn.prepareStatement("UPDATE atividades SET cancelada = 1 WHERE id = ?")) {
                    psUp.setString(1, id);
                    psUp.executeUpdate();
                }

                Map<String, Object> updated = buildAtividade(conn, id);
                ctx.json(updated);
            }
        });

        app.get("/encontros/{id}/codigo", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização pode gerar código do encontro"));
                return;
            }

            String encontroId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psEncontro = conn.prepareStatement("SELECT atividadeId, inicio, fim FROM encontros WHERE id = ?");
                psEncontro.setString(1, encontroId);
                ResultSet rsEncontro = psEncontro.executeQuery();
                if (!rsEncontro.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Encontro não encontrado"));
                    return;
                }

                String atividadeId = rsEncontro.getString("atividadeId");
                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv != null && "cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada"));
                    return;
                }

                Map<String, Object> codigo = gerarCodigoEncontro(encontroId);
                ctx.json(codigo);
            }
        });

        app.post("/encontros/{id}/presencas", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante pode registrar presença"));
                return;
            }

            String encontroId = ctx.pathParam("id");
            Map body;
            try {
                body = ctx.bodyAsClass(Map.class);
            } catch (Exception e) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Corpo inválido"));
                return;
            }

            if (body == null || !body.containsKey("codigo")) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Código obrigatório"));
                return;
            }

            String codigo = String.valueOf(body.get("codigo"));
            String lidoEmStr = body.get("lidoEm") == null ? null : String.valueOf(body.get("lidoEm"));

            try (Connection conn = Database.getConnection()) {
                PreparedStatement psEncontro = conn.prepareStatement("SELECT atividadeId, inicio, fim FROM encontros WHERE id = ?");
                psEncontro.setString(1, encontroId);
                ResultSet rsEncontro = psEncontro.executeQuery();
                if (!rsEncontro.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Encontro não encontrado"));
                    return;
                }

                String atividadeId = rsEncontro.getString("atividadeId");
                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv != null && "cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada"));
                    return;
                }

                boolean inscrito = false;
                PreparedStatement psInscricao = conn.prepareStatement("SELECT id FROM inscricoes WHERE atividadeId = ? AND participanteId = ? AND status IN ('confirmada', 'convocada')");
                psInscricao.setString(1, atividadeId);
                psInscricao.setString(2, xUsuario);
                ResultSet rsInscricao = psInscricao.executeQuery();
                if (rsInscricao.next()) {
                    inscrito = true;
                }
                if (!inscrito) {
                    ctx.status(403);
                    ctx.json(Map.of("erro", "NAO_INSCRITO", "mensagem", "Participante não está inscrito no encontro"));
                    return;
                }

                Map<String, Object> codigoValido = gerarCodigoEncontro(encontroId);
                String codigoEsperado = String.valueOf(codigoValido.get("codigo"));
                if (!codigoEsperado.equalsIgnoreCase(codigo)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "CODIGO_INVALIDO", "mensagem", "Código inválido para este encontro"));
                    return;
                }

                OffsetDateTime agora = Database.getClock();
                OffsetDateTime lidoEm = lidoEmStr != null ? OffsetDateTime.parse(lidoEmStr) : agora;
                OffsetDateTime validoAte = OffsetDateTime.parse(String.valueOf(codigoValido.get("validoAte")));
                OffsetDateTime inicio = OffsetDateTime.parse(rsEncontro.getString("inicio"));
                OffsetDateTime fim = OffsetDateTime.parse(rsEncontro.getString("fim"));

                if (!lidoEm.isBefore(validoAte.plusMinutes(1)) && !agora.isBefore(validoAte)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "FORA_DA_JANELA", "mensagem", "Código fora da janela de presença"));
                    return;
                }
                if (lidoEm.isBefore(inicio.minusHours(1)) || lidoEm.isAfter(fim.plusHours(2))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "SINCRONIZACAO_TARDIA", "mensagem", "Leitura fora da sincronização esperada"));
                    return;
                }

                String origem = (lidoEmStr != null && !lidoEm.isEqual(agora)) ? "qr_offline" : "qr";
                String presencaId = "pre_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                PreparedStatement psExiste = conn.prepareStatement("SELECT id FROM presencas WHERE encontroId = ? AND participanteId = ?");
                psExiste.setString(1, encontroId);
                psExiste.setString(2, xUsuario);
                ResultSet rsExiste = psExiste.executeQuery();
                if (rsExiste.next()) {
                    String existenteId = rsExiste.getString("id");
                    PreparedStatement psGet = conn.prepareStatement("SELECT id, encontroId, participanteId, origem, lidoEm, registradaEm, justificativa FROM presencas WHERE id = ?");
                    psGet.setString(1, existenteId);
                    ResultSet rsGet = psGet.executeQuery();
                    if (rsGet.next()) {
                        Map<String, Object> presenca = new HashMap<>();
                        presenca.put("id", rsGet.getString("id"));
                        presenca.put("encontroId", rsGet.getString("encontroId"));
                        presenca.put("participanteId", rsGet.getString("participanteId"));
                        presenca.put("origem", rsGet.getString("origem"));
                        presenca.put("lidoEm", rsGet.getString("lidoEm"));
                        presenca.put("registradaEm", rsGet.getString("registradaEm"));
                        presenca.put("justificativa", rsGet.getString("justificativa"));
                        ctx.status(200);
                        ctx.json(presenca);
                        return;
                    }
                }

                PreparedStatement psInsert = conn.prepareStatement("INSERT INTO presencas(id, encontroId, participanteId, origem, lidoEm, registradaEm, justificativa) VALUES(?, ?, ?, ?, ?, ?, NULL)");
                psInsert.setString(1, presencaId);
                psInsert.setString(2, encontroId);
                psInsert.setString(3, xUsuario);
                psInsert.setString(4, origem);
                psInsert.setString(5, lidoEm.toString());
                psInsert.setString(6, agora.toString());
                psInsert.executeUpdate();

                Map<String, Object> presenca = new HashMap<>();
                presenca.put("id", presencaId);
                presenca.put("encontroId", encontroId);
                presenca.put("participanteId", xUsuario);
                presenca.put("origem", origem);
                presenca.put("lidoEm", lidoEm.toString());
                presenca.put("registradaEm", agora.toString());
                presenca.put("justificativa", null);
                ctx.status(201);
                ctx.json(presenca);
            }
        });

        app.post("/encontros/{id}/presencas/manual", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização pode registrar presença manual"));
                return;
            }

            String encontroId = ctx.pathParam("id");
            Map body;
            try {
                body = ctx.bodyAsClass(Map.class);
            } catch (Exception e) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Corpo inválido"));
                return;
            }

            if (body == null || !body.containsKey("participanteId")) {
                ctx.status(422);
                ctx.json(Map.of("erro", "DADOS_INVALIDOS", "mensagem", "Participante obrigatório"));
                return;
            }

            String participanteId = String.valueOf(body.get("participanteId"));
            String justificativa = body.get("justificativa") == null ? null : String.valueOf(body.get("justificativa")).trim();
            if (justificativa == null || justificativa.isEmpty()) {
                ctx.status(422);
                ctx.json(Map.of("erro", "JUSTIFICATIVA_OBRIGATORIA", "mensagem", "Justificativa obrigatória para presença manual"));
                return;
            }

            try (Connection conn = Database.getConnection()) {
                PreparedStatement psEncontro = conn.prepareStatement("SELECT atividadeId, inicio, fim FROM encontros WHERE id = ?");
                psEncontro.setString(1, encontroId);
                ResultSet rsEncontro = psEncontro.executeQuery();
                if (!rsEncontro.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Encontro não encontrado"));
                    return;
                }

                String atividadeId = rsEncontro.getString("atividadeId");
                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv != null && "cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada"));
                    return;
                }

                PreparedStatement psInscricao = conn.prepareStatement("SELECT id FROM inscricoes WHERE atividadeId = ? AND participanteId = ? AND status IN ('confirmada', 'convocada')");
                psInscricao.setString(1, atividadeId);
                psInscricao.setString(2, participanteId);
                ResultSet rsInscricao = psInscricao.executeQuery();
                if (!rsInscricao.next()) {
                    ctx.status(403);
                    ctx.json(Map.of("erro", "NAO_INSCRITO", "mensagem", "Participante não está inscrito"));
                    return;
                }

                PreparedStatement psManual = conn.prepareStatement("SELECT id FROM presencas WHERE encontroId = ? AND participanteId = ? AND origem = 'manual'");
                psManual.setString(1, encontroId);
                psManual.setString(2, participanteId);
                ResultSet rsManual = psManual.executeQuery();
                if (rsManual.next()) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "LIMITE_DE_MANUAIS", "mensagem", "Limite de presença manual atingido"));
                    return;
                }

                OffsetDateTime agora = Database.getClock();
                OffsetDateTime inicio = OffsetDateTime.parse(rsEncontro.getString("inicio"));
                OffsetDateTime fim = OffsetDateTime.parse(rsEncontro.getString("fim"));
                if (agora.isBefore(inicio.minusMinutes(30)) || agora.isAfter(fim.plusMinutes(120))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "FORA_DA_JANELA", "mensagem", "Presença manual fora da janela"));
                    return;
                }

                PreparedStatement psExiste = conn.prepareStatement("SELECT id FROM presencas WHERE encontroId = ? AND participanteId = ?");
                psExiste.setString(1, encontroId);
                psExiste.setString(2, participanteId);
                ResultSet rsExiste = psExiste.executeQuery();
                if (rsExiste.next()) {
                    String existenteId = rsExiste.getString("id");
                    PreparedStatement psGet = conn.prepareStatement("SELECT id, encontroId, participanteId, origem, lidoEm, registradaEm, justificativa FROM presencas WHERE id = ?");
                    psGet.setString(1, existenteId);
                    ResultSet rsGet = psGet.executeQuery();
                    if (rsGet.next()) {
                        Map<String, Object> presenca = new HashMap<>();
                        presenca.put("id", rsGet.getString("id"));
                        presenca.put("encontroId", rsGet.getString("encontroId"));
                        presenca.put("participanteId", rsGet.getString("participanteId"));
                        presenca.put("origem", rsGet.getString("origem"));
                        presenca.put("lidoEm", rsGet.getString("lidoEm"));
                        presenca.put("registradaEm", rsGet.getString("registradaEm"));
                        presenca.put("justificativa", rsGet.getString("justificativa"));
                        ctx.status(200);
                        ctx.json(presenca);
                        return;
                    }
                }

                String presencaId = "pre_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                PreparedStatement psInsert = conn.prepareStatement("INSERT INTO presencas(id, encontroId, participanteId, origem, lidoEm, registradaEm, justificativa) VALUES(?, ?, ?, 'manual', ?, ?, ?)");
                psInsert.setString(1, presencaId);
                psInsert.setString(2, encontroId);
                psInsert.setString(3, participanteId);
                psInsert.setString(4, agora.toString());
                psInsert.setString(5, agora.toString());
                psInsert.setString(6, justificativa);
                psInsert.executeUpdate();

                Map<String, Object> presenca = new HashMap<>();
                presenca.put("id", presencaId);
                presenca.put("encontroId", encontroId);
                presenca.put("participanteId", participanteId);
                presenca.put("origem", "manual");
                presenca.put("lidoEm", agora.toString());
                presenca.put("registradaEm", agora.toString());
                presenca.put("justificativa", justificativa);
                ctx.status(201);
                ctx.json(presenca);
            }
        });

        app.get("/encontros/{id}/presencas", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização pode consultar presenças"));
                return;
            }

            String encontroId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psEncontro = conn.prepareStatement("SELECT id FROM encontros WHERE id = ?");
                psEncontro.setString(1, encontroId);
                ResultSet rsEncontro = psEncontro.executeQuery();
                if (!rsEncontro.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Encontro não encontrado"));
                    return;
                }

                PreparedStatement ps = conn.prepareStatement("SELECT id, encontroId, participanteId, origem, lidoEm, registradaEm, justificativa FROM presencas WHERE encontroId = ? ORDER BY registradaEm ASC");
                ps.setString(1, encontroId);
                ResultSet rs = ps.executeQuery();
                List<Map<String, Object>> presencas = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("id", rs.getString("id"));
                    p.put("encontroId", rs.getString("encontroId"));
                    p.put("participanteId", rs.getString("participanteId"));
                    p.put("origem", rs.getString("origem"));
                    p.put("lidoEm", rs.getString("lidoEm"));
                    p.put("registradaEm", rs.getString("registradaEm"));
                    p.put("justificativa", rs.getString("justificativa"));
                    presencas.add(p);
                }
                ctx.json(presencas);
            }
        });

        app.post("/atividades/{id}/certificado", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante pode emitir certificado"));
                return;
            }

            String atividadeId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }

                if ((Boolean) atv.getOrDefault("cancelada", false) || "cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada"));
                    return;
                }

                PreparedStatement psInscricao = conn.prepareStatement("SELECT 1 FROM inscricoes WHERE atividadeId = ? AND participanteId = ? AND status IN ('confirmada', 'convocada')");
                psInscricao.setString(1, atividadeId);
                psInscricao.setString(2, xUsuario);
                if (!psInscricao.executeQuery().next()) {
                    ctx.status(403);
                    ctx.json(Map.of("erro", "NAO_INSCRITO", "mensagem", "Participante não está inscrito na atividade"));
                    return;
                }

                List<Map<String, String>> encontros = (List<Map<String, String>>) atv.get("encontros");
                OffsetDateTime agora = Database.getClock();
                for (Map<String, String> encontro : encontros) {
                    if (agora.isBefore(OffsetDateTime.parse(encontro.get("fim")))) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "ATIVIDADE_NAO_ENCERRADA", "mensagem", "Atividade ainda não foi encerrada"));
                        return;
                    }
                }

                PreparedStatement psPresencas = conn.prepareStatement("SELECT COUNT(DISTINCT encontroId) FROM presencas p JOIN encontros e ON e.id = p.encontroId WHERE e.atividadeId = ? AND p.participanteId = ?");
                psPresencas.setString(1, atividadeId);
                psPresencas.setString(2, xUsuario);
                ResultSet rsPresencas = psPresencas.executeQuery();
                int presencas = rsPresencas.next() ? rsPresencas.getInt(1) : 0;
                if (presencas < encontros.size()) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "PRESENCA_INSUFICIENTE", "mensagem", "Presença insuficiente para emitir certificado"));
                    return;
                }

                PreparedStatement psExistente = conn.prepareStatement("SELECT codigo, atividadeId, participanteId, cargaHorariaMinutos, presencas, encontros, emitidoEm FROM certificados WHERE atividadeId = ? AND participanteId = ?");
                psExistente.setString(1, atividadeId);
                psExistente.setString(2, xUsuario);
                ResultSet rsExistente = psExistente.executeQuery();
                if (rsExistente.next()) {
                    Map<String, Object> certificado = certificadoMap(rsExistente);
                    ctx.status(200);
                    ctx.json(certificado);
                    return;
                }

                int cargaHorariaMinutos = 0;
                for (Map<String, String> encontro : encontros) {
                    OffsetDateTime inicio = OffsetDateTime.parse(encontro.get("inicio"));
                    OffsetDateTime fim = OffsetDateTime.parse(encontro.get("fim"));
                    cargaHorariaMinutos += (int) java.time.Duration.between(inicio, fim).toMinutes();
                }

                String codigo = "SA26-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                OffsetDateTime emitidoEm = Database.getClock();
                PreparedStatement psInsert = conn.prepareStatement("INSERT INTO certificados(codigo, atividadeId, participanteId, cargaHorariaMinutos, presencas, encontros, emitidoEm) VALUES(?, ?, ?, ?, ?, ?, ?)");
                psInsert.setString(1, codigo);
                psInsert.setString(2, atividadeId);
                psInsert.setString(3, xUsuario);
                psInsert.setInt(4, cargaHorariaMinutos);
                psInsert.setInt(5, presencas);
                psInsert.setInt(6, encontros.size());
                psInsert.setString(7, emitidoEm.toString());
                psInsert.executeUpdate();

                Map<String, Object> certificado = new HashMap<>();
                certificado.put("codigo", codigo);
                certificado.put("atividadeId", atividadeId);
                certificado.put("participanteId", xUsuario);
                certificado.put("cargaHorariaMinutos", cargaHorariaMinutos);
                certificado.put("presencas", presencas);
                certificado.put("encontros", encontros.size());
                certificado.put("emitidoEm", emitidoEm.toString());
                ctx.status(201);
                ctx.json(certificado);
            }
        });

        app.get("/certificados", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante pode consultar certificados"));
                return;
            }

            try (Connection conn = Database.getConnection()) {
                PreparedStatement ps = conn.prepareStatement("SELECT codigo, atividadeId, participanteId, cargaHorariaMinutos, presencas, encontros, emitidoEm FROM certificados WHERE participanteId = ? ORDER BY emitidoEm ASC");
                ps.setString(1, xUsuario);
                ResultSet rs = ps.executeQuery();
                List<Map<String, Object>> certificados = new ArrayList<>();
                while (rs.next()) {
                    certificados.add(certificadoMap(rs));
                }
                ctx.json(certificados);
            }
        });

        app.get("/certificados/{codigo}", ctx -> {
            String codigo = ctx.pathParam("codigo");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement ps = conn.prepareStatement("SELECT c.codigo, c.atividadeId, c.participanteId, c.cargaHorariaMinutos, c.emitidoEm, u.nome AS participante, a.titulo AS atividade FROM certificados c JOIN usuarios u ON u.id = c.participanteId JOIN atividades a ON a.id = c.atividadeId WHERE c.codigo = ?");
                ps.setString(1, codigo);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Certificado não encontrado"));
                    return;
                }

                Map<String, Object> verificacao = new HashMap<>();
                verificacao.put("codigo", rs.getString("codigo"));
                verificacao.put("participante", rs.getString("participante"));
                verificacao.put("atividade", rs.getString("atividade"));
                verificacao.put("cargaHorariaMinutos", rs.getInt("cargaHorariaMinutos"));
                verificacao.put("emitidoEm", rs.getString("emitidoEm"));
                ctx.json(verificacao);
            }
        });

        app.get("/extrato", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante pode consultar o extrato"));
                return;
            }

            try (Connection conn = Database.getConnection()) {
                PreparedStatement psAtividades = conn.prepareStatement("SELECT a.id, a.titulo, a.tipo, (SELECT c.codigo FROM certificados c WHERE c.atividadeId = a.id AND c.participanteId = ?) AS codigo FROM atividades a JOIN inscricoes i ON i.atividadeId = a.id WHERE i.participanteId = ? AND i.status IN ('confirmada', 'convocada') ORDER BY a.id");
                psAtividades.setString(1, xUsuario);
                psAtividades.setString(2, xUsuario);
                ResultSet rsAtividades = psAtividades.executeQuery();
                List<Map<String, Object>> itens = new ArrayList<>();
                int palestrasMinutos = 0;
                int minicursosMinutos = 0;
                while (rsAtividades.next()) {
                    PreparedStatement psDuracao = conn.prepareStatement("SELECT inicio, fim FROM encontros WHERE atividadeId = ?");
                    psDuracao.setString(1, rsAtividades.getString("id"));
                    ResultSet rsDuracao = psDuracao.executeQuery();
                    int cargaHorariaMinutos = 0;
                    while (rsDuracao.next()) {
                        cargaHorariaMinutos += (int) java.time.Duration.between(OffsetDateTime.parse(rsDuracao.getString("inicio")), OffsetDateTime.parse(rsDuracao.getString("fim"))).toMinutes();
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("atividadeId", rsAtividades.getString("id"));
                    item.put("titulo", rsAtividades.getString("titulo"));
                    item.put("tipo", rsAtividades.getString("tipo"));
                    item.put("cargaHorariaMinutos", cargaHorariaMinutos);
                    item.put("codigo", rsAtividades.getString("codigo"));
                    itens.add(item);
                    if ("palestra".equals(rsAtividades.getString("tipo"))) {
                        palestrasMinutos += cargaHorariaMinutos;
                    } else if ("minicurso".equals(rsAtividades.getString("tipo"))) {
                        minicursosMinutos += cargaHorariaMinutos;
                    }
                }

                int totalMinutos = palestrasMinutos + minicursosMinutos;
                ctx.json(Map.of("itens", itens, "palestrasMinutos", palestrasMinutos, "minicursosMinutos", minicursosMinutos, "totalMinutos", totalMinutos, "aproveitadoMinutos", totalMinutos));
            }
        });

        app.get("/painel/atividades", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            List<Map<String, Object>> painel = new ArrayList<>();
            try (Connection conn = Database.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, titulo, vagas FROM atividades")) {
                while (rs.next()) {
                    String atvId = rs.getString("id");
                    String titulo = rs.getString("titulo");
                    int vagas = rs.getInt("vagas");

                    int ocupadas = 0;
                    int emEspera = 0;
                    try (PreparedStatement psIns = conn.prepareStatement("SELECT status, count(*) FROM inscricoes WHERE atividadeId = ? GROUP BY status")) {
                        psIns.setString(1, atvId);
                        ResultSet rsIns = psIns.executeQuery();
                        while (rsIns.next()) {
                            String st = rsIns.getString(1);
                            int count = rsIns.getInt(2);
                            if ("confirmada".equals(st) || "convocada".equals(st)) {
                                ocupadas += count;
                            } else if ("em_espera".equals(st)) {
                                emEspera += count;
                            }
                        }
                    }

                    double ocupacaoPercentual = 0.0;
                    if (vagas > 0) {
                        ocupacaoPercentual = java.math.BigDecimal.valueOf(ocupadas)
                                .divide(java.math.BigDecimal.valueOf(vagas), 4, java.math.RoundingMode.HALF_UP)
                                .multiply(java.math.BigDecimal.valueOf(100))
                                .setScale(1, java.math.RoundingMode.HALF_UP)
                                .doubleValue();
                    }

                    List<String> encontroIds = new ArrayList<>();
                    List<String> fimEncontros = new ArrayList<>();
                    try (PreparedStatement psEnc = conn.prepareStatement("SELECT id, fim FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC")) {
                        psEnc.setString(1, atvId);
                        ResultSet rsEnc = psEnc.executeQuery();
                        while (rsEnc.next()) {
                            encontroIds.add(rsEnc.getString("id"));
                            fimEncontros.add(rsEnc.getString("fim"));
                        }
                    }

                    int confirmadasCount = 0;
                    try (PreparedStatement psConf = conn.prepareStatement("SELECT count(*) FROM inscricoes WHERE atividadeId = ? AND status = 'confirmada'")) {
                        psConf.setString(1, atvId);
                        ResultSet rsConf = psConf.executeQuery();
                        if (rsConf.next()) {
                            confirmadasCount = rsConf.getInt(1);
                        }
                    }

                    OffsetDateTime agora = Database.getClock();
                    List<Double> frequenciasEncontros = new ArrayList<>();
                    for (int i = 0; i < encontroIds.size(); i++) {
                        String encId = encontroIds.get(i);
                        String fimStr = fimEncontros.get(i);
                        if (fimStr != null) {
                            OffsetDateTime fim = OffsetDateTime.parse(fimStr);
                            if (!agora.isBefore(fim)) {
                                int presencasCount = 0;
                                try (PreparedStatement psPres = conn.prepareStatement(
                                    "SELECT count(DISTINCT p.participanteId) FROM presencas p JOIN inscricoes i ON i.participanteId = p.participanteId WHERE p.encontroId = ? AND i.atividadeId = ? AND i.status = 'confirmada'"
                                )) {
                                    psPres.setString(1, encId);
                                    psPres.setString(2, atvId);
                                    ResultSet rsPres = psPres.executeQuery();
                                    if (rsPres.next()) {
                                        presencasCount = rsPres.getInt(1);
                                    }
                                }
                                double freq = 0.0;
                                if (confirmadasCount > 0) {
                                    freq = ((double) presencasCount / confirmadasCount) * 100.0;
                                }
                                frequenciasEncontros.add(freq);
                            }
                        }
                    }

                    Double frequenciaPercentual = null;
                    if (!frequenciasEncontros.isEmpty()) {
                        double soma = 0.0;
                        for (double f : frequenciasEncontros) {
                            soma += f;
                        }
                        frequenciaPercentual = java.math.BigDecimal.valueOf(soma)
                                .divide(java.math.BigDecimal.valueOf(frequenciasEncontros.size()), 4, java.math.RoundingMode.HALF_UP)
                                .setScale(1, java.math.RoundingMode.HALF_UP)
                                .doubleValue();
                    }

                    Map<String, Object> linha = new HashMap<>();
                    linha.put("atividadeId", atvId);
                    linha.put("titulo", titulo);
                    linha.put("vagas", vagas);
                    linha.put("ocupadas", ocupadas);
                    linha.put("emEspera", emEspera);
                    linha.put("ocupacaoPercentual", ocupacaoPercentual);
                    linha.put("frequenciaPercentual", frequenciaPercentual);
                    painel.add(linha);
                }
            } catch (SQLException e) {
                ctx.status(500);
            }

            ctx.json(painel);
        });

        app.get("/painel/atividades/{id}/sem-chance", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            String atividadeId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psAtv = conn.prepareStatement("SELECT id FROM atividades WHERE id = ?");
                psAtv.setString(1, atividadeId);
                ResultSet rsAtv = psAtv.executeQuery();
                if (!rsAtv.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }

                List<String> vencidoEncontroIds = new ArrayList<>();
                OffsetDateTime agora = Database.getClock();
                PreparedStatement psEnc = conn.prepareStatement("SELECT id, fim FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC");
                psEnc.setString(1, atividadeId);
                ResultSet rsEnc = psEnc.executeQuery();
                while (rsEnc.next()) {
                    String encId = rsEnc.getString("id");
                    String fimStr = rsEnc.getString("fim");
                    if (fimStr != null) {
                        OffsetDateTime fim = OffsetDateTime.parse(fimStr);
                        OffsetDateTime deadline = fim.plusHours(2);
                        if (!agora.isBefore(deadline)) {
                            vencidoEncontroIds.add(encId);
                        }
                    }
                }

                int N = vencidoEncontroIds.size();
                int faltasPermitidas = N / 4;

                List<Map<String, Object>> semChanceList = new ArrayList<>();
                PreparedStatement psPart = conn.prepareStatement("SELECT u.id, u.nome FROM inscricoes i JOIN usuarios u ON u.id = i.participanteId WHERE i.atividadeId = ? AND i.status = 'confirmada' ORDER BY u.nome ASC");
                psPart.setString(1, atividadeId);
                ResultSet rsPart = psPart.executeQuery();
                while (rsPart.next()) {
                    String partId = rsPart.getString("id");
                    String partNome = rsPart.getString("nome");

                    int faltas = 0;
                    if (N > 0) {
                        for (String encId : vencidoEncontroIds) {
                            PreparedStatement psPres = conn.prepareStatement("SELECT 1 FROM presencas WHERE encontroId = ? AND participanteId = ?");
                            psPres.setString(1, encId);
                            psPres.setString(2, partId);
                            ResultSet rsPres = psPres.executeQuery();
                            boolean hasPresenca = rsPres.next();
                            rsPres.close();
                            psPres.close();
                            if (!hasPresenca) {
                                faltas++;
                            }
                        }
                    }

                    if (N > 0 && faltas > faltasPermitidas) {
                        Map<String, Object> sc = new HashMap<>();
                        sc.put("participanteId", partId);
                        sc.put("nome", partNome);
                        sc.put("faltas", faltas);
                        sc.put("faltasPermitidas", faltasPermitidas);
                        semChanceList.add(sc);
                    }
                }

                ctx.json(semChanceList);
            } catch (SQLException e) {
                ctx.status(500);
            }
        });

        app.get("/painel/atividades/{id}/frequencia.csv", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            String atividadeId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psAtv = conn.prepareStatement("SELECT id FROM atividades WHERE id = ?");
                psAtv.setString(1, atividadeId);
                ResultSet rsAtv = psAtv.executeQuery();
                if (!rsAtv.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }

                List<String> encontroIds = new ArrayList<>();
                List<OffsetDateTime> encontroFins = new ArrayList<>();
                PreparedStatement psEnc = conn.prepareStatement("SELECT id, fim FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC");
                psEnc.setString(1, atividadeId);
                ResultSet rsEnc = psEnc.executeQuery();
                while (rsEnc.next()) {
                    encontroIds.add(rsEnc.getString("id"));
                    encontroFins.add(OffsetDateTime.parse(rsEnc.getString("fim")));
                }

                int totalEncontros = encontroIds.size();
                OffsetDateTime agora = Database.getClock();

                StringBuilder sb = new StringBuilder();
                sb.append("\uFEFF");
                sb.append("nome");
                for (int i = 1; i <= totalEncontros; i++) {
                    sb.append(";E").append(i);
                }
                sb.append(";frequencia;certificado\n");

                PreparedStatement psPart = conn.prepareStatement("SELECT u.id, u.nome FROM inscricoes i JOIN usuarios u ON u.id = i.participanteId WHERE i.atividadeId = ? AND i.status = 'confirmada' ORDER BY u.nome ASC");
                psPart.setString(1, atividadeId);
                ResultSet rsPart = psPart.executeQuery();
                while (rsPart.next()) {
                    String partId = rsPart.getString("id");
                    String partNome = rsPart.getString("nome");

                    sb.append(partNome);

                    int presencasTotal = 0;
                    for (int i = 0; i < totalEncontros; i++) {
                        String encId = encontroIds.get(i);
                        OffsetDateTime fim = encontroFins.get(i);

                        PreparedStatement psPres = conn.prepareStatement("SELECT 1 FROM presencas WHERE encontroId = ? AND participanteId = ?");
                        psPres.setString(1, encId);
                        psPres.setString(2, partId);
                        ResultSet rsPres = psPres.executeQuery();
                        boolean hasPresenca = rsPres.next();
                        rsPres.close();
                        psPres.close();

                        if (hasPresenca) {
                            sb.append(";P");
                            presencasTotal++;
                        } else {
                            OffsetDateTime deadline = fim.plusHours(2);
                            if (agora.isAfter(deadline) || agora.equals(deadline)) {
                                sb.append(";F");
                            } else {
                                sb.append(";-");
                            }
                        }
                    }

                    double freq = totalEncontros > 0 ? ((double) presencasTotal / totalEncontros) * 1.0 : 0.0;
                    String freqStr = String.format(java.util.Locale.GERMAN, "%.1f", freq);
                    sb.append(";").append(freqStr);

                    PreparedStatement psCert = conn.prepareStatement("SELECT 1 FROM certificados WHERE atividadeId = ? AND participanteId = ?");
                    psCert.setString(1, atividadeId);
                    psCert.setString(2, partId);
                    ResultSet rsCert = psCert.executeQuery();
                    boolean hasCert = rsCert.next();
                    rsCert.close();
                    psCert.close();

                    sb.append(";").append(hasCert ? "sim" : "nao").append("\n");
                }

                ctx.contentType("text/csv; charset=UTF-8");
                ctx.result(sb.toString());
            } catch (SQLException e) {
                ctx.status(500);
            }
        });

        app.get("/painel/bloqueios", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            List<Map<String, Object>> bloqueios = new ArrayList<>();
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psPart = conn.prepareStatement("SELECT id, nome FROM usuarios WHERE papel = 'participante' ORDER BY nome ASC");
                ResultSet rsPart = psPart.executeQuery();
                while (rsPart.next()) {
                    String partId = rsPart.getString("id");
                    String partNome = rsPart.getString("nome");
                    Map<String, Object> b = getBloqueioInfo(conn, partId, partNome);
                    if (b != null) {
                        bloqueios.add(b);
                    }
                }
                rsPart.close();
                psPart.close();
            } catch (SQLException e) {
                ctx.status(500);
            }
            ctx.json(bloqueios);
        });

        app.delete("/painel/bloqueios/{participanteId}", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"organizacao".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_ORGANIZACAO", "mensagem", "Apenas organização"));
                return;
            }

            String participanteId = ctx.pathParam("participanteId");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement psUser = conn.prepareStatement("SELECT nome FROM usuarios WHERE id = ? AND papel = 'participante'");
                psUser.setString(1, participanteId);
                ResultSet rsUser = psUser.executeQuery();
                if (!rsUser.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Participante não encontrado"));
                    return;
                }
                String nome = rsUser.getString("nome");
                rsUser.close();
                psUser.close();

                Map<String, Object> b = getBloqueioInfo(conn, participanteId, nome);
                if (b == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Participante não está bloqueado"));
                    return;
                }

                PreparedStatement psIns = conn.prepareStatement("INSERT INTO desbloqueios(participanteId, desbloqueadoEm) VALUES(?, ?)");
                psIns.setString(1, participanteId);
                psIns.setString(2, Database.getClock().toString());
                psIns.executeUpdate();
                psIns.close();

                ctx.status(204);
            } catch (SQLException e) {
                ctx.status(500);
            }
        });

        app.post("/atividades/{id}/inscricoes", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante pode se inscrever"));
                return;
            }

            String atividadeId = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                if (isParticipanteBloqueado(conn, xUsuario)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "INSCRICAO_BLOQUEADA", "mensagem", "Participante bloqueado"));
                    return;
                }
                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv == null) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Atividade não encontrada"));
                    return;
                }
                if ((Boolean) atv.getOrDefault("cancelada", false) || "cancelada".equals(atv.get("situacao"))) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "ATIVIDADE_CANCELADA", "mensagem", "Atividade cancelada"));
                    return;
                }

                List<Map<String, String>> encontros = (List<Map<String, String>>) atv.get("encontros");
                if (!encontros.isEmpty()) {
                    OffsetDateTime primeiroInicio = OffsetDateTime.parse(encontros.get(0).get("inicio"));
                    OffsetDateTime agora = Database.getClock();
                    if (!agora.isBefore(primeiroInicio.minusMinutes(30))) {
                        ctx.status(422);
                        ctx.json(Map.of("erro", "INSCRICOES_ENCERRADAS", "mensagem", "Inscrições encerradas"));
                        return;
                    }
                }

                PreparedStatement psCheck = conn.prepareStatement("SELECT 1 FROM inscricoes WHERE atividadeId = ? AND participanteId = ? AND status IN ('confirmada', 'em_espera', 'convocada')");
                psCheck.setString(1, atividadeId);
                psCheck.setString(2, xUsuario);
                ResultSet rsCheck = psCheck.executeQuery();
                if (rsCheck.next()) {
                    ctx.status(409);
                    ctx.json(Map.of("erro", "JA_INSCRITO", "mensagem", "Já inscrito"));
                    return;
                }

                int vagas = (Integer) atv.get("vagas");
                int ocupadas = (Integer) atv.get("ocupadas");
                boolean willOccupyVacancy = (ocupadas < vagas);

                if (willOccupyVacancy) {
                    List<Map<String, String>> encontrosNew = (List<Map<String, String>>) atv.get("encontros");
                    boolean hasConflict = false;

                    PreparedStatement psConflict = conn.prepareStatement(
                        "SELECT e.inicio, e.fim FROM encontros e " +
                        "JOIN inscricoes i ON e.atividadeId = i.atividadeId " +
                        "WHERE i.participanteId = ? AND i.status IN ('confirmada', 'convocada')"
                    );
                    psConflict.setString(1, xUsuario);
                    try (ResultSet rsConflict = psConflict.executeQuery()) {
                        while (rsConflict.next()) {
                            OffsetDateTime exStart = OffsetDateTime.parse(rsConflict.getString("inicio"));
                            OffsetDateTime exEnd = OffsetDateTime.parse(rsConflict.getString("fim"));
                            for (Map<String, String> ne : encontrosNew) {
                                OffsetDateTime nStart = OffsetDateTime.parse(ne.get("inicio"));
                                OffsetDateTime nEnd = OffsetDateTime.parse(ne.get("fim"));
                                if (nStart.isBefore(exEnd) && exStart.isBefore(nEnd)) {
                                    hasConflict = true;
                                    break;
                                }
                            }
                            if (hasConflict) break;
                        }
                    }

                    if (hasConflict) {
                        ctx.status(409);
                        ctx.json(Map.of("erro", "CONFLITO_DE_HORARIO", "mensagem", "Conflito de horário"));
                        return;
                    }
                }

                if ("minicurso".equals(atv.get("tipo"))) {
                    if (willOccupyVacancy) {
                        PreparedStatement psMiniCount = conn.prepareStatement(
                            "SELECT count(*) FROM inscricoes i " +
                            "JOIN atividades a ON i.atividadeId = a.id " +
                            "WHERE i.participanteId = ? AND i.status IN ('confirmada', 'convocada') AND a.tipo = 'minicurso'"
                        );
                        psMiniCount.setString(1, xUsuario);
                        try (ResultSet rsMini = psMiniCount.executeQuery()) {
                            if (rsMini.next() && rsMini.getInt(1) >= 3) {
                                ctx.status(422);
                                ctx.json(Map.of("erro", "LIMITE_DE_MINICURSOS", "mensagem", "Limite de minicursos atingido"));
                                return;
                            }
                        }
                    }
                }

                String status;
                Integer posicaoNaEspera = null;

                if (ocupadas < vagas) {
                    status = "confirmada";
                } else {
                    status = "em_espera";
                    PreparedStatement psEspera = conn.prepareStatement("SELECT count(*) FROM inscricoes WHERE atividadeId = ? AND status = 'em_espera'");
                    psEspera.setString(1, atividadeId);
                    ResultSet rsEspera = psEspera.executeQuery();
                    int countEspera = 0;
                    if (rsEspera.next()) {
                        countEspera = rsEspera.getInt(1);
                    }
                    posicaoNaEspera = countEspera + 1;
                }

                String insId = "ins_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                String criadaEm = Database.getClock().toString();

                PreparedStatement psIns = conn.prepareStatement("INSERT INTO inscricoes(id, atividadeId, participanteId, status, posicaoNaEspera, convocadaAte, criadaEm) VALUES(?, ?, ?, ?, ?, NULL, ?)");
                psIns.setString(1, insId);
                psIns.setString(2, atividadeId);
                psIns.setString(3, xUsuario);
                psIns.setString(4, status);
                if (posicaoNaEspera != null) {
                    psIns.setInt(5, posicaoNaEspera);
                } else {
                    psIns.setNull(5, java.sql.Types.INTEGER);
                }
                psIns.setString(6, criadaEm);
                psIns.executeUpdate();

                Map<String, Object> inscricao = new HashMap<>();
                inscricao.put("id", insId);
                inscricao.put("atividadeId", atividadeId);
                inscricao.put("participanteId", xUsuario);
                inscricao.put("status", status);
                inscricao.put("posicaoNaEspera", posicaoNaEspera);
                inscricao.put("convocadaAte", null);
                inscricao.put("criadaEm", criadaEm);

                ctx.status(201);
                ctx.json(inscricao);
            }
        });

        app.get("/inscricoes", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            String atividadeIdFiltro = ctx.queryParam("atividadeId");

            List<Map<String, Object>> lista = new ArrayList<>();
            try (Connection conn = Database.getConnection()) {
                String sql = "SELECT id, atividadeId, participanteId, status, posicaoNaEspera, convocadaAte, criadaEm FROM inscricoes WHERE 1=1";
                List<Object> params = new ArrayList<>();

                if ("participante".equals(role)) {
                    sql += " AND participanteId = ?";
                    params.add(xUsuario);
                }

                if (atividadeIdFiltro != null && !atividadeIdFiltro.isEmpty()) {
                    sql += " AND atividadeId = ?";
                    params.add(atividadeIdFiltro);
                }

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> ins = new HashMap<>();
                            ins.put("id", rs.getString("id"));
                            ins.put("atividadeId", rs.getString("atividadeId"));
                            ins.put("participanteId", rs.getString("participanteId"));
                            ins.put("status", rs.getString("status"));
                            int pos = rs.getInt("posicaoNaEspera");
                            if (rs.wasNull()) {
                                ins.put("posicaoNaEspera", null);
                            } else {
                                ins.put("posicaoNaEspera", pos);
                            }
                            ins.put("convocadaAte", rs.getString("convocadaAte"));
                            ins.put("criadaEm", rs.getString("criadaEm"));
                            lista.add(ins);
                        }
                    }
                }
            }
            ctx.json(lista);
        });

        app.post("/inscricoes/{id}/cancelamento", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante"));
                return;
            }

            String id = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                PreparedStatement ps = conn.prepareStatement("SELECT id, atividadeId, participanteId, status, posicaoNaEspera, convocadaAte, criadaEm FROM inscricoes WHERE id = ?");
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                    return;
                }

                String partId = rs.getString("participanteId");
                if (!partId.equals(xUsuario)) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                    return;
                }

                String status = rs.getString("status");
                String atividadeId = rs.getString("atividadeId");

                if (!"confirmada".equals(status) && !"em_espera".equals(status) && !"convocada".equals(status)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "INSCRICAO_INATIVA", "mensagem", "Inscrição já está inativa ou cancelada"));
                    return;
                }

                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv != null) {
                    List<Map<String, String>> encontros = (List<Map<String, String>>) atv.get("encontros");
                    if (!encontros.isEmpty()) {
                        OffsetDateTime primeiroInicio = OffsetDateTime.parse(encontros.get(0).get("inicio"));
                        OffsetDateTime agora = Database.getClock();
                        if (!agora.isBefore(primeiroInicio)) {
                            ctx.status(422);
                            ctx.json(Map.of("erro", "ATIVIDADE_JA_INICIADA", "mensagem", "Atividade já iniciada"));
                            return;
                        }
                    }
                }

                try (PreparedStatement psUp = conn.prepareStatement("UPDATE inscricoes SET status = 'cancelada' WHERE id = ?")) {
                    psUp.setString(1, id);
                    psUp.executeUpdate();
                }

                checkAndConvokeWaitingList(conn, atividadeId);

                Map<String, Object> ins = new HashMap<>();
                ins.put("id", rs.getString("id"));
                ins.put("atividadeId", atividadeId);
                ins.put("participanteId", partId);
                ins.put("status", "cancelada");
                int pos = rs.getInt("posicaoNaEspera");
                if (rs.wasNull()) {
                    ins.put("posicaoNaEspera", null);
                } else {
                    ins.put("posicaoNaEspera", pos);
                }
                ins.put("convocadaAte", rs.getString("convocadaAte"));
                ins.put("criadaEm", rs.getString("criadaEm"));
                ctx.json(ins);
            }
        });

        app.get("/inscricoes/{id}", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            String id = ctx.pathParam("id");
            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, atividadeId, participanteId, status, posicaoNaEspera, convocadaAte, criadaEm FROM inscricoes WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ctx.status(404);
                        ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                        return;
                    }
                    String partId = rs.getString("participanteId");
                    if ("participante".equals(role) && !partId.equals(xUsuario)) {
                        ctx.status(404);
                        ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                        return;
                    }

                    Map<String, Object> ins = new HashMap<>();
                    ins.put("id", rs.getString("id"));
                    ins.put("atividadeId", rs.getString("atividadeId"));
                    ins.put("participanteId", rs.getString("participanteId"));
                    ins.put("status", rs.getString("status"));
                    int pos = rs.getInt("posicaoNaEspera");
                    if (rs.wasNull()) {
                        ins.put("posicaoNaEspera", null);
                    } else {
                        ins.put("posicaoNaEspera", pos);
                    }
                    ins.put("convocadaAte", rs.getString("convocadaAte"));
                    ins.put("criadaEm", rs.getString("criadaEm"));
                    ctx.json(ins);
                }
            }
        });

        app.post("/inscricoes/{id}/confirmacao", ctx -> {
            String xUsuario = ctx.header("X-Usuario");
            String role = Database.getUserRole(xUsuario);
            if (!"participante".equals(role)) {
                ctx.status(403);
                ctx.json(Map.of("erro", "SOMENTE_PARTICIPANTE", "mensagem", "Apenas participante"));
                return;
            }

            String id = ctx.pathParam("id");
            try (Connection conn = Database.getConnection()) {
                processExpirationsAndCascades(conn);

                PreparedStatement ps = conn.prepareStatement("SELECT id, atividadeId, participanteId, status, posicaoNaEspera, convocadaAte, criadaEm FROM inscricoes WHERE id = ?");
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                    return;
                }

                String partId = rs.getString("participanteId");
                if (!partId.equals(xUsuario)) {
                    ctx.status(404);
                    ctx.json(Map.of("erro", "NAO_ENCONTRADO", "mensagem", "Inscrição não encontrada"));
                    return;
                }

                String status = rs.getString("status");
                String atividadeId = rs.getString("atividadeId");
                String convocadaAteStr = rs.getString("convocadaAte");
                String criadaEm = rs.getString("criadaEm");

                if ("expirada".equals(status)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "CONVOCACAO_EXPIRADA", "mensagem", "Convocação expirada"));
                    return;
                }

                if (!"convocada".equals(status)) {
                    ctx.status(422);
                    ctx.json(Map.of("erro", "SEM_CONVOCACAO", "mensagem", "Inscrição não está convocada"));
                    return;
                }

                if (convocadaAteStr != null) {
                    OffsetDateTime ate = OffsetDateTime.parse(convocadaAteStr);
                    OffsetDateTime agora = Database.getClock();
                    if (!agora.isBefore(ate)) {
                        try (PreparedStatement psExp = conn.prepareStatement("UPDATE inscricoes SET status = 'expirada', convocadaAte = NULL WHERE id = ?")) {
                            psExp.setString(1, id);
                            psExp.executeUpdate();
                        }
                        checkAndConvokeWaitingList(conn, atividadeId);

                        ctx.status(422);
                        ctx.json(Map.of("erro", "CONVOCACAO_EXPIRADA", "mensagem", "Convocação expirada"));
                        return;
                    }
                }

                Map<String, Object> atv = buildAtividade(conn, atividadeId);
                if (atv != null) {
                    List<Map<String, String>> encontrosNew = (List<Map<String, String>>) atv.get("encontros");
                    boolean hasConflict = false;
                    PreparedStatement psConflict = conn.prepareStatement(
                        "SELECT e.inicio, e.fim FROM encontros e " +
                        "JOIN inscricoes i ON e.atividadeId = i.atividadeId " +
                        "WHERE i.participanteId = ? AND i.status IN ('confirmada', 'convocada') AND i.id <> ?"
                    );
                    psConflict.setString(1, xUsuario);
                    psConflict.setString(2, id);
                    try (ResultSet rsConflict = psConflict.executeQuery()) {
                        while (rsConflict.next()) {
                            OffsetDateTime exStart = OffsetDateTime.parse(rsConflict.getString("inicio"));
                            OffsetDateTime exEnd = OffsetDateTime.parse(rsConflict.getString("fim"));
                            for (Map<String, String> ne : encontrosNew) {
                                OffsetDateTime nStart = OffsetDateTime.parse(ne.get("inicio"));
                                OffsetDateTime nEnd = OffsetDateTime.parse(ne.get("fim"));
                                if (nStart.isBefore(exEnd) && exStart.isBefore(nEnd)) {
                                    hasConflict = true;
                                    break;
                                }
                            }
                            if (hasConflict) break;
                        }
                    }

                    if (hasConflict) {
                        ctx.status(409);
                        ctx.json(Map.of("erro", "CONFLITO_DE_HORARIO", "mensagem", "Conflito de horário"));
                        return;
                    }

                    if ("minicurso".equals(atv.get("tipo"))) {
                        PreparedStatement psMiniCount = conn.prepareStatement(
                            "SELECT count(*) FROM inscricoes i " +
                            "JOIN atividades a ON i.atividadeId = a.id " +
                            "WHERE i.participanteId = ? AND i.status IN ('confirmada', 'convocada') AND a.tipo = 'minicurso' AND i.id <> ?"
                        );
                        psMiniCount.setString(1, xUsuario);
                        psMiniCount.setString(2, id);
                        try (ResultSet rsMini = psMiniCount.executeQuery()) {
                            if (rsMini.next() && rsMini.getInt(1) >= 3) {
                                ctx.status(422);
                                ctx.json(Map.of("erro", "LIMITE_DE_MINICURSOS", "mensagem", "Limite de minicursos atingido"));
                                return;
                            }
                        }
                    }
                }

                try (PreparedStatement psUp = conn.prepareStatement("UPDATE inscricoes SET status = 'confirmada', convocadaAte = NULL WHERE id = ?")) {
                    psUp.setString(1, id);
                    psUp.executeUpdate();
                }

                Map<String, Object> ins = new HashMap<>();
                ins.put("id", id);
                ins.put("atividadeId", atividadeId);
                ins.put("participanteId", partId);
                ins.put("status", "confirmada");
                ins.put("posicaoNaEspera", null);
                ins.put("convocadaAte", null);
                ins.put("criadaEm", criadaEm);
                ctx.json(ins);
            }
        });

        app.start(port);
        return app;
    }

    private static boolean isTestMode() {
        return "1".equals(System.getenv("MODO_TESTE")) || "1".equals(System.getProperty("MODO_TESTE"));
    }

    private static Map<String, Object> gerarCodigoEncontro(String encontroId) {
        Map<String, Object> codigoInfo = new HashMap<>();
        OffsetDateTime agora = Database.getClock();
        String base = encontroId + "-" + agora.toEpochSecond();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int code = (base.charAt((i * 3 + 1) % base.length()) + i * 17 + (int) (Math.abs(base.hashCode()) % 36)) % 36;
            char c = (char) (code < 10 ? code + '0' : (code - 10) + 'A');
            sb.append(c);
        }
        codigoInfo.put("encontroId", encontroId);
        codigoInfo.put("codigo", sb.toString());
        codigoInfo.put("trocaEm", agora.plusMinutes(2).toString());
        codigoInfo.put("validoAte", agora.plusMinutes(15).toString());
        return codigoInfo;
    }

    private static Map<String, Object> certificadoMap(ResultSet rs) throws SQLException {
        Map<String, Object> certificado = new HashMap<>();
        certificado.put("codigo", rs.getString("codigo"));
        certificado.put("atividadeId", rs.getString("atividadeId"));
        certificado.put("participanteId", rs.getString("participanteId"));
        certificado.put("cargaHorariaMinutos", rs.getInt("cargaHorariaMinutos"));
        certificado.put("presencas", rs.getInt("presencas"));
        certificado.put("encontros", rs.getInt("encontros"));
        certificado.put("emitidoEm", rs.getString("emitidoEm"));
        return certificado;
    }

    public static Map<String, Object> buildAtividade(Connection conn, String atvId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT id, titulo, tipo, salaId, vagas, cancelada FROM atividades WHERE id = ?");
        ps.setString(1, atvId);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            return null;
        }

        Map<String, Object> atv = new HashMap<>();
        atv.put("id", rs.getString("id"));
        atv.put("titulo", rs.getString("titulo"));
        atv.put("tipo", rs.getString("tipo"));
        atv.put("salaId", rs.getString("salaId"));
        atv.put("vagas", rs.getInt("vagas"));
        boolean isCancelada = rs.getInt("cancelada") == 1;

        List<Map<String, String>> encontros = new ArrayList<>();
        PreparedStatement psEnc = conn.prepareStatement("SELECT id, inicio, fim FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC");
        psEnc.setString(1, atvId);
        ResultSet rsEnc = psEnc.executeQuery();
        long cargaHorariaMinutos = 0;
        OffsetDateTime primeiroInicio = null;
        OffsetDateTime ultimoFim = null;

        while (rsEnc.next()) {
            Map<String, String> enc = new HashMap<>();
            enc.put("id", rsEnc.getString("id"));
            String inicioStr = rsEnc.getString("inicio");
            String fimStr = rsEnc.getString("fim");
            enc.put("inicio", inicioStr);
            enc.put("fim", fimStr);
            encontros.add(enc);

            OffsetDateTime dtInicio = OffsetDateTime.parse(inicioStr);
            OffsetDateTime dtFim = OffsetDateTime.parse(fimStr);

            if (primeiroInicio == null || dtInicio.isBefore(primeiroInicio)) {
                primeiroInicio = dtInicio;
            }
            if (ultimoFim == null || dtFim.isAfter(ultimoFim)) {
                ultimoFim = dtFim;
            }

            cargaHorariaMinutos += Duration.between(dtInicio, dtFim).toMinutes();
        }

        atv.put("encontros", encontros);
        atv.put("cargaHorariaMinutos", cargaHorariaMinutos);

        String situacao = "prevista";
        if (isCancelada) {
            situacao = "cancelada";
        } else if (primeiroInicio != null && ultimoFim != null) {
            OffsetDateTime agora = Database.getClock();
            if (agora.isBefore(primeiroInicio)) {
                situacao = "prevista";
            } else if (!agora.isAfter(ultimoFim)) {
                situacao = "em_andamento";
            } else {
                situacao = "encerrada";
            }
        }
        atv.put("situacao", situacao);

        int ocupadas = 0;
        int emEspera = 0;
        try {
            PreparedStatement psIns = conn.prepareStatement("SELECT status, count(*) FROM inscricoes WHERE atividadeId = ? GROUP BY status");
            psIns.setString(1, atvId);
            ResultSet rsIns = psIns.executeQuery();
            while (rsIns.next()) {
                String st = rsIns.getString(1);
                int count = rsIns.getInt(2);
                if ("confirmada".equals(st) || "convocada".equals(st)) {
                    ocupadas += count;
                } else if ("em_espera".equals(st)) {
                    emEspera += count;
                }
            }
        } catch (SQLException ignored) {}

        atv.put("ocupadas", ocupadas);
        atv.put("vagasRestantes", Math.max(0, rs.getInt("vagas") - ocupadas));
        atv.put("emEspera", emEspera);

        return atv;
    }

    public static void processExpirationsAndCascades(Connection conn) {
        try {
            OffsetDateTime agora = Database.getClock();
            PreparedStatement psExp = conn.prepareStatement(
                "SELECT id, atividadeId, convocadaAte FROM inscricoes WHERE status = 'convocada' AND convocadaAte IS NOT NULL"
            );
            ResultSet rsExp = psExp.executeQuery();
            List<Map<String, String>> expiredList = new ArrayList<>();
            while (rsExp.next()) {
                String ateStr = rsExp.getString("convocadaAte");
                if (ateStr != null) {
                    OffsetDateTime ate = OffsetDateTime.parse(ateStr);
                    if (!agora.isBefore(ate)) {
                        Map<String, String> m = new HashMap<>();
                        m.put("id", rsExp.getString("id"));
                        m.put("atividadeId", rsExp.getString("atividadeId"));
                        expiredList.add(m);
                    }
                }
            }
            rsExp.close();
            psExp.close();

            Set<String> affectedAtividades = new HashSet<>();
            for (Map<String, String> exp : expiredList) {
                String insId = exp.get("id");
                String atvId = exp.get("atividadeId");
                try (PreparedStatement psUp = conn.prepareStatement(
                    "UPDATE inscricoes SET status = 'expirada', convocadaAte = NULL WHERE id = ? AND status = 'convocada'"
                )) {
                    psUp.setString(1, insId);
                    psUp.executeUpdate();
                }
                affectedAtividades.add(atvId);
            }

            for (String atvId : affectedAtividades) {
                checkAndConvokeWaitingList(conn, atvId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void checkAndConvokeWaitingList(Connection conn, String atvId) throws SQLException {
        while (true) {
            PreparedStatement psAtv = conn.prepareStatement("SELECT vagas FROM atividades WHERE id = ?");
            psAtv.setString(1, atvId);
            ResultSet rsAtv = psAtv.executeQuery();
            if (!rsAtv.next()) {
                rsAtv.close();
                psAtv.close();
                break;
            }
            int vagas = rsAtv.getInt("vagas");
            rsAtv.close();
            psAtv.close();

            PreparedStatement psOcc = conn.prepareStatement(
                "SELECT count(*) FROM inscricoes WHERE atividadeId = ? AND status IN ('confirmada', 'convocada')"
            );
            psOcc.setString(1, atvId);
            ResultSet rsOcc = psOcc.executeQuery();
            int ocupadas = 0;
            if (rsOcc.next()) {
                ocupadas = rsOcc.getInt(1);
            }
            rsOcc.close();
            psOcc.close();

            if (ocupadas >= vagas) {
                break;
            }

            PreparedStatement psNext = conn.prepareStatement(
                "SELECT id FROM inscricoes WHERE atividadeId = ? AND status = 'em_espera' ORDER BY criadaEm ASC, id ASC LIMIT 1"
            );
            psNext.setString(1, atvId);
            ResultSet rsNext = psNext.executeQuery();
            if (!rsNext.next()) {
                rsNext.close();
                psNext.close();
                break;
            }
            String nextInsId = rsNext.getString("id");
            rsNext.close();
            psNext.close();

            OffsetDateTime agora = Database.getClock();
            OffsetDateTime prazo2h = agora.plusHours(2);
            OffsetDateTime primeiroInicio = getPrimeiroInicio(conn, atvId);
            OffsetDateTime convocadaAte = (primeiroInicio != null && prazo2h.isAfter(primeiroInicio)) ? primeiroInicio : prazo2h;

            PreparedStatement psConv = conn.prepareStatement(
                "UPDATE inscricoes SET status = 'convocada', posicaoNaEspera = NULL, convocadaAte = ? WHERE id = ?"
            );
            psConv.setString(1, convocadaAte.toString());
            psConv.setString(2, nextInsId);
            psConv.executeUpdate();
            psConv.close();

            reindexEspera(conn, atvId);
        }
    }

    public static void reindexEspera(Connection conn, String atvId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM inscricoes WHERE atividadeId = ? AND status = 'em_espera' ORDER BY criadaEm ASC, id ASC"
        );
        ps.setString(1, atvId);
        ResultSet rs = ps.executeQuery();
        int pos = 1;
        List<String> ids = new ArrayList<>();
        while (rs.next()) {
            ids.add(rs.getString("id"));
        }
        rs.close();
        ps.close();

        for (String insId : ids) {
            PreparedStatement psUp = conn.prepareStatement("UPDATE inscricoes SET posicaoNaEspera = ? WHERE id = ?");
            psUp.setInt(1, pos++);
            psUp.setString(2, insId);
            psUp.executeUpdate();
            psUp.close();
        }
    }

    public static OffsetDateTime getPrimeiroInicio(Connection conn, String atvId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT inicio FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC LIMIT 1");
        ps.setString(1, atvId);
        ResultSet rs = ps.executeQuery();
        OffsetDateTime dt = null;
        if (rs.next()) {
            String s = rs.getString("inicio");
            if (s != null) {
                dt = OffsetDateTime.parse(s);
            }
        }
        rs.close();
        ps.close();
        return dt;
    }

    public static Map<String, Object> getBloqueioInfo(Connection conn, String participanteId, String nome) {
        try {
            OffsetDateTime unblockTime = null;
            PreparedStatement psUnblock = conn.prepareStatement("SELECT MAX(desbloqueadoEm) FROM desbloqueios WHERE participanteId = ?");
            psUnblock.setString(1, participanteId);
            ResultSet rsUnblock = psUnblock.executeQuery();
            if (rsUnblock.next() && rsUnblock.getString(1) != null) {
                unblockTime = OffsetDateTime.parse(rsUnblock.getString(1));
            }
            rsUnblock.close();
            psUnblock.close();

            PreparedStatement psAtv = conn.prepareStatement(
                "SELECT DISTINCT a.id FROM atividades a JOIN inscricoes i ON i.atividadeId = a.id WHERE i.participanteId = ? AND i.status = 'confirmada' AND a.cancelada = 0"
            );
            psAtv.setString(1, participanteId);
            ResultSet rsAtv = psAtv.executeQuery();
            List<String> atividadesZeroPresenca = new ArrayList<>();
            OffsetDateTime ultimaFimZeroPresenca = null;
            OffsetDateTime agora = Database.getClock();

            while (rsAtv.next()) {
                String atvId = rsAtv.getString("id");
                List<OffsetDateTime> fimEncontros = new ArrayList<>();
                PreparedStatement psEnc = conn.prepareStatement("SELECT fim FROM encontros WHERE atividadeId = ? ORDER BY inicio ASC");
                psEnc.setString(1, atvId);
                ResultSet rsEnc = psEnc.executeQuery();
                while (rsEnc.next()) {
                    fimEncontros.add(OffsetDateTime.parse(rsEnc.getString("fim")));
                }
                rsEnc.close();
                psEnc.close();

                if (fimEncontros.isEmpty()) continue;
                OffsetDateTime ultimoFim = fimEncontros.get(fimEncontros.size() - 1);

                if (agora.isBefore(ultimoFim)) continue;
                if (unblockTime != null && !ultimoFim.isAfter(unblockTime)) continue;

                boolean hasPresence = false;
                PreparedStatement psPres = conn.prepareStatement(
                    "SELECT 1 FROM presencas p JOIN encontros e ON e.id = p.encontroId WHERE e.atividadeId = ? AND p.participanteId = ?"
                );
                psPres.setString(1, atvId);
                psPres.setString(2, participanteId);
                ResultSet rsPres = psPres.executeQuery();
                if (rsPres.next()) {
                    hasPresence = true;
                }
                rsPres.close();
                psPres.close();

                if (!hasPresence) {
                    atividadesZeroPresenca.add(atvId);
                    if (ultimaFimZeroPresenca == null || ultimoFim.isAfter(ultimaFimZeroPresenca)) {
                        ultimaFimZeroPresenca = ultimoFim;
                    }
                }
            }
            rsAtv.close();
            psAtv.close();

            if (atividadesZeroPresenca.size() >= 2) {
                Map<String, Object> bloqueio = new HashMap<>();
                bloqueio.put("participanteId", participanteId);
                bloqueio.put("nome", nome);
                bloqueio.put("atividades", atividadesZeroPresenca);
                bloqueio.put("bloqueadoDesde", ultimaFimZeroPresenca != null ? ultimaFimZeroPresenca.toString() : agora.toString());
                return bloqueio;
            }
        } catch (SQLException e) {}
        return null;
    }

    public static boolean isParticipanteBloqueado(Connection conn, String participanteId) {
        return getBloqueioInfo(conn, participanteId, "") != null;
    }
}
