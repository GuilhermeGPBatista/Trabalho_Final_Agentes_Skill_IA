# Entrevista M5 — Painel da Organização

> Documento de acompanhamento da entrevista de requisitos para o Módulo M5.
> Status: Entrevista concluída — todas as pendências respondidas.

---

## Decisões tomadas (Rodada 1 e Rodada 2)

### P1 — Cálculo de Ocupação e Frequência Percentual (`GET /painel/atividades`)
- **Pergunta:** Como devem ser calculados e arredondados os campos `ocupacaoPercentual` e `frequenciaPercentual` na listagem do painel de atividades?
- **Resposta:** Ocupação = (confirmadas + convocadas) / vagas × 100. Arredondamento com uma casa decimal, meio para cima. Frequência do encontro = presenças / confirmadas. Frequência da atividade = média das frequências dos encontros já encerrados. Se nenhum encontro estiver encerrado, `frequenciaPercentual` = `null`.
- **Fonte:** RN-503 e RN-504.
- **Status:** RESPONDIDA

### P2 — Critério para Participantes "Sem Chance" (`GET /painel/atividades/:id/sem-chance`)
- **Pergunta:** Qual é o critério exato para considerar que um participante não tem mais chance de obter certificado em uma atividade?
- **Resposta:** "Sem chance" é o participante confirmado que, considerando somente encontros cujo prazo de registro já venceu (fim + 2 horas), já faltou mais do que o permitido pela regra de frequência mínima.
- **Fonte:** RN-505, baseada na RN-404.
- **Status:** RESPONDIDA

### P3 — Estrutura e Formato do CSV de Frequência (`GET /painel/atividades/:id/frequencia.csv`)
- **Pergunta:** Quais colunas, cabeçalhos e delimitadores devem ser utilizados no arquivo CSV exportado de frequência (`text/csv`)?
- **Resposta:** CSV usa separador `;` e UTF-8 com BOM. Uma linha por participante confirmado, em ordem de nome. Colunas: `nome;E1;…;En;frequencia;certificado`. Presença: `P`. Prazo vencido sem presença: `F`. Prazo ainda aberto: `-`. Frequência = presenças / encontros, com vírgula e uma casa decimal. Certificado: sim/nao.
- **Fonte:** RN-506.
- **Status:** RESPONDIDA

### P4 — Critérios de Bloqueio de Participantes (`GET /painel/bloqueios`)
- **Pergunta:** Em que circunstâncias e regras um participante entra na lista de bloqueio do sistema?
- **Resposta:** O participante é bloqueado quando estiver confirmado em 2 atividades encerradas nas quais teve zero presença. Enquanto bloqueado, não pode se inscrever em nenhuma outra atividade do evento, nem entrar na lista de espera. As inscrições que ele já possui continuam.
- **Fonte:** RN-507.
- **Status:** RESPONDIDA

### P5 — Efeito da Remoção de Bloqueio (`DELETE /painel/bloqueios/:participanteId`)
- **Pergunta:** Ao remover o bloqueio de um participante, o que acontece com as inscrições dele que foram afetadas ou bloqueadas anteriormente?
- **Resposta:** Após desbloquear, somente atividades que forem encerradas depois do desbloqueio podem voltar a contar para um novo bloqueio.
- **Fonte:** RN-509.
- **Status:** RESPONDIDA

---

## Rodada 2
As respostas foram conferidas no documento de requisitos e registradas com as fontes RN-503 a RN-509.
