# Auditoria M5 — Painel da Organização (2026-09-20)

## 1. Conformidades
- **R1 (RN-503):** O cálculo e arredondamento de `ocupacaoPercentual` (`(confirmadas + convocadas) / vagas * 100`, meio para cima com uma casa decimal) estão implementados e comprovados pelo teste `organizacao_acessa_painel_atividades_com_ocupacao_e_frequencia` (`M5Fatia1Test.java`).
- **R2 (RN-504):** O cálculo de `frequenciaPercentual` (média das frequências dos encontros encerrados, retornando `null` se nenhum encontro estiver encerrado) está implementado e testado em `M5Fatia1Test.java`.
- **R3 (RN-505):** A identificação de participantes "Sem Chance" (considerando encontros com prazo vencido fim + 2h e falta acima da proporção permitida) está implementada e comprovada pelo teste `organizacao_obtem_sem_chance` (`M5Fatia2Test.java`).
- **R4 (RN-506):** A exportação de frequência em CSV (`text/csv` com separador `;`, BOM UTF-8, colunas detalhadas por encontro `P`, `F`, `-`, frequência e certificado) está implementada e comprovada pelo teste `organizacao_obtem_frequencia_csv` (`M5Fatia2Test.java`).
- **R5 (RN-507):** O bloqueio automático de participantes com 2 atividades encerradas sem presença e a restrição de inscrição com retorno `422 INSCRICAO_BLOQUEADA` estão implementados e comprovados pelo teste `participante_bloqueado_nao_consegue_inscrever` (`M5Fatia3Test.java`).
- **R6 (RN-509):** A remoção de bloqueio (`DELETE /painel/bloqueios/:participanteId` com 204 / 404 se não bloqueado) e o controle de que apenas atividades encerradas após o desbloqueio contam para novos bloqueios estão implementados e testados em `organizacao_desbloqueia_participante` (`M5Fatia3Test.java`).
- **R7 (Contrato):** A restrição de acesso às rotas do painel exclusivamente para usuários da organização (retornando 403 `SOMENTE_ORGANIZACAO` ou 401 para credenciais inválidas) está implementada e validada em `participante_nao_acessa_painel_atividades` (`M5Fatia1Test.java`).

## 2. Não conformidades
- Nenhuma não conformidade encontrada. Todas as regras da spec M5 e critérios de aceite foram integralmente atendidos e provados por testes automatizados.

## 3. Riscos ou pontos de atenção
- É fundamental garantir que o ambiente de execução utilize o controle explícito do relógio (`PUT /_teste/relogio`) em cenários de teste baseados em prazos de encontros e verificação de vencimento (`fim + 2 horas`).

## 4. Evidências/testes que sustentam cada conclusão
- **M5Fatia1Test.java**: Valida o acesso restrito da organização (`/painel/atividades`), ocupação percentual e frequência percentual (`R1`, `R2`, `R7`).
- **M5Fatia2Test.java**: Valida a listagem de participantes "Sem Chance" (`/painel/atividades/:id/sem-chance`) e a exportação do CSV de frequência (`/painel/atividades/:id/frequencia.csv`) (`R3`, `R4`, `R7`).
- **M5Fatia3Test.java**: Valida a listagem de bloqueios (`/painel/bloqueios`), bloqueio de inscrições com código `422 INSCRICAO_BLOQUEADA`, remoção de bloqueio (`DELETE /painel/bloqueios/:participanteId` com status `204` e `404`) e reabilitação de inscrições após desbloqueio (`R5`, `R6`, `R7`).
- **Saída da suíte**: Execução de `M5Fatia1Test`, `M5Fatia2Test` e `M5Fatia3Test` concluída com 100% de sucesso (6 testes executados, 0 falhas, 0 erros).

## 5. Conclusão da auditoria
O módulo M5 (Painel da Organização) está em plena conformidade com a spec (`specs/M5-painel.md`), entrevista (`entrevistas/M5-painel.md`) e contrato (`contrato-api.md`), com testes automatizados robustos e honestos. Pode ser aceito.
