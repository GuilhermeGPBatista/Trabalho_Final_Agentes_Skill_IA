package br.com.semanaacademica;

import java.io.File;
import java.sql.*;
import java.time.OffsetDateTime;

public class Database {
    private static String dbPath = "../database.db";
    private static OffsetDateTime testClock = OffsetDateTime.parse("2026-10-13T09:00:00-03:00");

    public static Connection getConnection() throws SQLException {
        File parent = new File(dbPath).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public static void initDb() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS usuarios (" +
                    "id TEXT PRIMARY KEY, " +
                    "nome TEXT NOT NULL, " +
                    "papel TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS salas (" +
                    "id TEXT PRIMARY KEY, " +
                    "nome TEXT NOT NULL, " +
                    "capacidade INTEGER NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS atividades (" +
                    "id TEXT PRIMARY KEY, " +
                    "titulo TEXT NOT NULL, " +
                    "tipo TEXT NOT NULL, " +
                    "salaId TEXT NOT NULL, " +
                    "vagas INTEGER NOT NULL, " +
                    "situacao TEXT NOT NULL DEFAULT 'prevista', " +
                    "cancelada INTEGER NOT NULL DEFAULT 0)");

            stmt.execute("CREATE TABLE IF NOT EXISTS encontros (" +
                    "id TEXT PRIMARY KEY, " +
                    "atividadeId TEXT NOT NULL, " +
                    "inicio TEXT NOT NULL, " +
                    "fim TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS inscricoes (" +
                    "id TEXT PRIMARY KEY, " +
                    "atividadeId TEXT NOT NULL, " +
                    "participanteId TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "posicaoNaEspera INTEGER, " +
                    "convocadaAte TEXT, " +
                    "criadaEm TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS presencas (" +
                    "id TEXT PRIMARY KEY, " +
                    "encontroId TEXT NOT NULL, " +
                    "participanteId TEXT NOT NULL, " +
                    "origem TEXT NOT NULL, " +
                    "lidoEm TEXT NOT NULL, " +
                    "registradaEm TEXT NOT NULL, " +
                    "justificativa TEXT)");

                stmt.execute("CREATE TABLE IF NOT EXISTS certificados (" +
                    "codigo TEXT PRIMARY KEY, " +
                    "atividadeId TEXT NOT NULL, " +
                    "participanteId TEXT NOT NULL, " +
                    "cargaHorariaMinutos INTEGER NOT NULL, " +
                    "presencas INTEGER NOT NULL, " +
                    "encontros INTEGER NOT NULL, " +
                    "emitidoEm TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS desbloqueios (" +
                    "participanteId TEXT NOT NULL, " +
                    "desbloqueadoEm TEXT NOT NULL)");

            resetInitialData();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inicializar banco de dados", e);
        }
    }

    public static void resetInitialData() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("DELETE FROM usuarios");
            stmt.execute("DELETE FROM salas");
            stmt.execute("DELETE FROM atividades");
            stmt.execute("DELETE FROM encontros");
            stmt.execute("DELETE FROM inscricoes");
            stmt.execute("DELETE FROM presencas");
            stmt.execute("DELETE FROM certificados");
            stmt.execute("DELETE FROM desbloqueios");

            String[][] usuarios = {
                {"org-ana", "Ana Beatriz Lima", "organizacao"},
                {"org-bruno", "Bruno Tavares", "organizacao"},
                {"p-carla", "Carla Mendes Souza", "participante"},
                {"p-diego", "Diego Alves", "participante"},
                {"p-elisa", "Elisa Fernandes da Rocha", "participante"},
                {"p-fabio", "Fábio Nogueira", "participante"},
                {"p-gabriela", "Gabriela Moura Castro", "participante"},
                {"p-heitor", "Heitor Campos", "participante"},
                {"p-isadora", "Isadora Ribeiro dos Santos", "participante"},
                {"p-joao", "João Pedro Martins", "participante"}
            };
            for (String[] u : usuarios) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO usuarios(id, nome, papel) VALUES(?, ?, ?)")) {
                    ps.setString(1, u[0]);
                    ps.setString(2, u[1]);
                    ps.setString(3, u[2]);
                    ps.executeUpdate();
                }
            }

            Object[][] salas = {
                {"auditorio", "Auditório Central", 200},
                {"sala-101", "Sala 101", 40},
                {"sala-102", "Sala 102", 40},
                {"lab-3", "Laboratório 3", 20}
            };
            for (Object[] s : salas) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO salas(id, nome, capacidade) VALUES(?, ?, ?)")) {
                    ps.setString(1, (String) s[0]);
                    ps.setString(2, (String) s[1]);
                    ps.setInt(3, (Integer) s[2]);
                    ps.executeUpdate();
                }
            }

            testClock = OffsetDateTime.parse("2026-10-13T09:00:00-03:00");
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao resetar dados iniciais", e);
        }
    }

    public static boolean userExists(String userId) {
        if (userId == null || userId.isEmpty()) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM usuarios WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static String getUserRole(String userId) {
        if (userId == null || userId.isEmpty()) return null;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT papel FROM usuarios WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("papel");
                }
            }
        } catch (SQLException e) {}
        return null;
    }

    public static OffsetDateTime getClock() {
        if ("1".equals(System.getenv("MODO_TESTE")) || "1".equals(System.getProperty("MODO_TESTE"))) {
            return testClock;
        }
        return OffsetDateTime.now();
    }

    public static void setClock(OffsetDateTime clock) {
        testClock = clock;
    }
}
