# Spec — M5: Painel da Organização

## 1. Objetivo
Fornecer ferramentas analíticas e operacionais para a organização gerenciar o andamento das atividades, taxa de ocupação, frequência, verificar participantes "sem chance" de obter certificado, exportar dados de frequência em CSV e gerenciar bloqueios de participantes que excederam limites de faltas.

## 2. Fora de escopo
- Criação e alocação de atividades e salas (M1).
- Inscrições, lista de espera e confirmações de participantes (M2).
- Registro de presença por QR code ou manual (M3).
- Emissão e verificação de certificados e extrato de horas (M4).
- Cadastro de usuários e salas (utiliza dados iniciais fixos).

## 3. Modelo

### LinhaDoPainel
- `atividadeId`: identificador da atividade (string).
- `titulo`: título da atividade (string).
- `vagas`: número total de vagas (inteiro).
- `ocupadas`: número de vagas ocupadas por inscrições confirmadas e convocadas (inteiro).
- `emEspera`: número de inscrições na lista de espera (inteiro).
- `ocupacaoPercentual`: percentual de ocupação em relação às vagas (número decimal).
- `frequenciaPercentual`: percentual médio de frequência nos encontros encerrados (número decimal ou `null`).

### SemChance
- `participanteId`: identificador do participante (string).
- `nome`: nome do participante (string).
- `faltas`: número de faltas acumuladas em encontros com prazo vencido (inteiro).
- `faltasPermitidas`: número máximo de faltas permitidas para ainda manter elegibilidade ao certificado (inteiro).

### Bloqueio
- `participanteId`: identificador do participante (string).
- `nome`: nome do participante (string).
- `atividades`: lista de IDs de atividades que motivaram o bloqueio (lista de strings).
- `bloqueadoDesde`: data e hora em que o bloqueio ocorreu (ISO 8601).

## 4. Endpoints

| Método | Rota | Quem | Sucesso |
|---|---|---|---|
| GET | `/painel/atividades` | organização | 200 `[LinhaDoPainel]` |
| GET | `/painel/atividades/:id/sem-chance` | organização | 200 `[SemChance]` |
| GET | `/painel/atividades/:id/frequencia.csv` | organização | 200 `text/csv` |
| GET | `/painel/bloqueios` | organização | 200 `[Bloqueio]` |
| DELETE | `/painel/bloqueios/:participanteId` | organização | 204 (404 se não bloqueado) |

## 5. Regras

- **R1 (RN-503):** `ocupacaoPercentual` é calculado como `(confirmadas + convocadas) / vagas * 100`, arredondado com uma casa decimal, meio para cima.
- **R2 (RN-504):** `frequenciaPercentual` é a média das frequências dos encontros já encerrados (frequência de cada encontro = presenças / confirmadas). Se nenhum encontro estiver encerrado, retorna `null`.
- **R3 (RN-505):** "Sem chance" identifica o participante confirmado que, considerando somente encontros cujo prazo de registro já venceu (fim do encontro + 2 horas), já faltou mais do que o permitido pela regra de frequência mínima (ex: mais de 25% de faltas permitidas).
- **R4 (RN-506):** `GET /painel/atividades/:id/frequencia.csv` retorna `text/csv` com separador `;` e UTF-8 com BOM, contendo uma linha por participante confirmado (em ordem de nome). As colunas são `nome;E1;…;En;frequencia;certificado`. Os status de presença por encontro são: `P` (presente), `F` (prazo vencido sem presença), `-` (prazo ainda aberto). Frequência = presenças / encontros (com vírgula e uma casa decimal). Certificado = `sim` ou `nao`.
- **R5 (RN-507):** O participante é bloqueado (`Bloqueio`) quando estiver confirmado em 2 atividades encerradas nas quais teve zero presença. Enquanto bloqueado, não pode se inscrever em nenhuma outra atividade do evento nem entrar na lista de espera (`INSCRICAO_BLOQUEADA` 422). As inscrições já existentes continuam ativas.
- **R6 (RN-509):** Ao remover o bloqueio (`DELETE /painel/bloqueios/:participanteId`), quem não está bloqueado retorna `404`. Após desbloquear, somente atividades encerradas depois do desbloqueio contam para um novo bloqueio.
- **R7 (Contrato):** Todas as rotas do painel exigem autenticação de organização (`SOMENTE_ORGANIZACAO` 403 se participante, `USUARIO_DESCONHECIDO` 401 se cabeçalho ausente ou inválido).

## 6. Critérios de aceite

1. (R1, R7) `GET /painel/atividades` chamado por usuário da organização retorna 200 com a lista de linhas e o percentual de ocupação calculado corretamente com arredondamento.
2. (R2, R7) `GET /painel/atividades` retorna o percentual médio de frequência dos encontros encerrados ou `null` se nenhum encontro encerrou.
3. (R3, R7) `GET /painel/atividades/:id/sem-chance` retorna os participantes confirmados com prazo de encontro vencido que ultrapassaram o limite de faltas para o certificado.
4. (R4, R7) `GET /painel/atividades/:id/frequencia.csv` retorna arquivo CSV com BOM, delimitador `;`, ordenado por nome, com colunas detalhadas por encontro (`P`, `F`, `-`), frequência e status de certificado.
5. (R5, R7) `GET /painel/bloqueios` lista os participantes bloqueados por zero presença em 2 atividades encerradas; tentativa de inscrição subsequente retorna `422 INSCRICAO_BLOQUEADA`.
6. (R6, R7) `DELETE /painel/bloqueios/:participanteId` remove o bloqueio com sucesso (204); tentar remover bloqueio de usuário não bloqueado retorna `404`.

## 7. Como isto será verificado
Testes automatizados de API em JUnit 5 utilizando Javalin e SQLite, executados em modo de teste (`MODO_TESTE=1`) com controle explícito do relógio via `PUT /_teste/relogio` e limpeza via `POST /_teste/reset`.

## 8. Fatias de entrega
- **Fatia 1:** Métricas do Painel (`GET /painel/atividades`, ocupação e frequência).
- **Fatia 2:** Sem Chance e Exportação CSV (`GET /painel/atividades/:id/sem-chance`, `GET /painel/atividades/:id/frequencia.csv`).
- **Fatia 3:** Bloqueios e Desbloqueio (`GET /painel/bloqueios`, `DELETE /painel/bloqueios/:participanteId`, validação de `INSCRICAO_BLOQUEADA`).
