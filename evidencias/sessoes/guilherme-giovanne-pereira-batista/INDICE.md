# Sessões — Guilherme Giovanne Pereira Batista

Cada execução de teste é lida pelo que mudou desde a anterior:

- **Ciclo** — vermelho logo depois de mexer só em teste, e depois verde logo depois de mexer só em código. É o TDD.
- **Nasceu verde** — verde logo depois de mexer só em teste. Ou o comportamento já existia, ou o teste não testa o que diz.
- **Juntos** — teste e código mudaram antes da mesma execução. Não houve vermelho para ver.

**Alertas:** *colou* = prompt com 10 palavras seguidas ou mais iguais às do documento de requisitos (só aparece quando o resumo é gerado com `--requisitos`); *leu* = o agente acessou um arquivo de requisitos; *anexou* = o documento foi anexado à conversa.

Requisições são chamadas ao modelo: cada passo do agente é uma. Skills contam tanto a ferramenta `skill` quanto o comando `/nome`.

| Início | Sessão | Requisições | Skills | Subagentes | Vermelhas / verdes | Ciclos | Nasceu verde | Juntos | Alertas |
|---|---|---|---|---|---|---|---|---|---|
| 19/09 18:42 | [Rodada 1 da entrevista do M5](ses_f4461037bffeYkPqe2NIQ49MwE.md) | 47 | grilling, to-spec, tdd | — | 7 / 0 | 0 | 0 | 0 | — |
| 20/09 11:06 | [Implementação TDD do M5 Painel: Fatia 1](ses_f40dbe337ffe9Vo4Y7txP6Geeq.md) | 126 | tdd, construir-telas | auditor | 15 / 9 | 2 | 0 | 1 | — |
| | **Total: 2 sessões** | 173 | grilling, to-spec, tdd (2), construir-telas | auditor | 22 / 9 | 2 | 0 | 1 | — |
