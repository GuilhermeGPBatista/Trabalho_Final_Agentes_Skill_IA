#!/usr/bin/env node
// Evidências do projeto final: exporta as sessões do OpenCode deste projeto e escreve,
// ao lado de cada uma, uma linha do tempo legível.
//
//   Aluno, na raiz do repositório:  node evidencias/exportar-evidencias.js
//   Git sem nome configurado:       node evidencias/exportar-evidencias.js --aluno "Carla Mendes"
//   Professor, depois do clone:     node evidencias/exportar-evidencias.js --resumir --requisitos <documento>
//
// Saída em evidencias/sessoes/<aluno>/:
//   <sessão>.json  export do OpenCode, sem as saídas de leitura de arquivo (o conteúdo está no git)
//   <sessão>.md    linha do tempo: prompts, skills, subagentes, arquivos editados, testes e alertas
//   INDICE.md      uma linha por sessão
// --resumir     refaz os resumos de todos os alunos e escreve evidencias/RESUMO.md
// --requisitos  acusa trechos do documento de requisitos colados nas conversas e copiados
//               para specs/ e entrevistas/
// Os comandos listados em "testes" no projeto.json também contam como execução de teste.
// Sem dependências; Node 18 ou mais novo.

'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const args = process.argv.slice(2);
const valorDe = (nome) => {
  const i = args.indexOf(nome);
  return i >= 0 ? args[i + 1] : undefined;
};
const SESSOES = path.resolve(valorDe('--saida') || path.join(__dirname, 'sessoes'));
const RAIZ = path.resolve(valorDe('--raiz') || path.join(__dirname, '..'));
const DOCUMENTO = valorDe('--requisitos');

const normalizar = (texto) => String(texto || '').replace(/\s+/g, ' ').trim();
const umaLinha = (texto, limite) => {
  const t = normalizar(texto);
  return t.length > limite ? `${t.slice(0, limite - 1)}…` : t;
};
const contar = (contagem, chave) => { contagem[chave] = (contagem[chave] || 0) + 1; };
const lerJson = (arquivo) => {
  try {
    return JSON.parse(fs.readFileSync(arquivo, 'utf8'));
  } catch {
    return null;
  }
};
const umaVez = (calcular) => {
  let pronto = false;
  let valor;
  return () => {
    if (!pronto) { valor = calcular(); pronto = true; }
    return valor;
  };
};

// ---------------------------------------------------------------- exportação

// A saída vai direto para um arquivo, e não por pipe: pelo pipe, o opencode sai antes de
// esvaziar o buffer e o export chega cortado (medido: 146 KB de 591 KB).
function executar(programa, parametros) {
  const temporario = path.join(os.tmpdir(), `evidencias-${process.pid}-${Date.now()}.txt`);
  const destino = fs.openSync(temporario, 'w');
  try {
    const r = spawnSync(programa, parametros, {
      stdio: ['ignore', destino, 'pipe'],
      encoding: 'utf8',
      shell: process.platform === 'win32', // no Windows, o opencode instalado pelo npm é um .cmd
    });
    if (r.error) throw r.error;
    if (r.status !== 0) {
      throw new Error(`"${programa} ${parametros.join(' ')}" falhou (saída ${r.status}):\n${r.stderr}`);
    }
    return fs.readFileSync(temporario, 'utf8');
  } finally {
    fs.closeSync(destino);
    fs.rmSync(temporario, { force: true });
  }
}

// A CLI pode escrever avisos antes do JSON: pega do primeiro delimitador ao último.
function extrairJson(texto, abre, fecha) {
  const inicio = texto.indexOf(abre);
  const fim = texto.lastIndexOf(fecha);
  if (inicio < 0 || fim < inicio) throw new Error('a saída do opencode não trouxe JSON');
  return JSON.parse(texto.slice(inicio, fim + 1));
}

const paraPasta = (nome) =>
  nome.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

function nomeDoAluno() {
  if (valorDe('--aluno')) return valorDe('--aluno');
  try {
    return executar('git', ['config', 'user.name']).trim();
  } catch {
    return '';
  }
}

const LEITURAS = new Set(['read', 'glob', 'grep', 'list', 'codesearch', 'webfetch', 'websearch']);

// As saídas de leitura são conteúdo de arquivo que já está no git. Tirá-las encolhe o
// export sem apagar nenhuma decisão do agente: o que ele leu continua registrado.
function compactar(sessao) {
  for (const mensagem of sessao.messages || []) {
    for (const parte of mensagem.parts || []) {
      if (parte.type !== 'tool' || !LEITURAS.has(parte.tool) || !parte.state) continue;
      if (typeof parte.state.output === 'string') {
        parte.state.output = `[${parte.state.output.length} caracteres omitidos]`;
      }
      delete parte.state.metadata;
    }
  }
  return sessao;
}

function exportar() {
  const aluno = nomeDoAluno();
  if (!aluno) {
    console.error('O git não sabe o seu nome. Rode de novo com --aluno "Seu Nome".');
    process.exit(1);
  }
  const pasta = path.join(SESSOES, paraPasta(aluno));
  fs.mkdirSync(pasta, { recursive: true });

  const lista = extrairJson(executar('opencode', ['session', 'list', '--format', 'json']), '[', ']');
  if (lista.length === 0) {
    console.error('O OpenCode não tem sessões deste projeto nesta máquina. Rode dentro da pasta do repositório.');
    process.exit(1);
  }

  let exportadas = 0;
  for (const item of lista) {
    const destino = path.join(pasta, `${item.id}.json`);
    if (lerJson(destino)?.info?.time?.updated === item.updated) continue;
    console.log(`exportando ${item.id}  ${item.title}`);
    const sessao = compactar(extrairJson(executar('opencode', ['export', item.id]), '{', '}'));
    fs.writeFileSync(destino, JSON.stringify(sessao, null, 1));
    exportadas++;
  }
  resumirPasta(pasta, aluno);
  console.log(`\n${exportadas} sessão(ões) exportada(s), ${lista.length - exportadas} sem mudança.`);
  console.log(`Índice: ${path.relative(process.cwd(), path.join(pasta, 'INDICE.md'))}`);
}

// ---------------------------------------------------------------- o que o repositório declara

const comandosDeclarados = umaVez(() =>
  (lerJson(path.join(RAIZ, 'projeto.json'))?.testes || []).map(normalizar).filter(Boolean));

const skillsConhecidas = umaVez(() => {
  const pasta = path.join(RAIZ, '.opencode', 'skills');
  const skills = [];
  for (const item of fs.existsSync(pasta) ? fs.readdirSync(pasta) : []) {
    const arquivo = path.join(pasta, item, 'SKILL.md');
    if (!fs.existsSync(arquivo)) continue;
    const texto = fs.readFileSync(arquivo, 'utf8');
    const corpo = normalizar(texto.replace(/^---[\s\S]*?\n---/, ''));
    const nome = texto.match(/^name:\s*(.+)$/m)?.[1].trim() || item;
    if (corpo.length >= 40) skills.push({ nome, corpo });
  }
  return skills;
});

// Skill chamada como comando (/grilling) chega como texto do usuário — o corpo da SKILL.md,
// sem marca nenhuma. Só dá para reconhecer comparando com as skills do repositório.
function skillDoComando(texto) {
  const t = normalizar(texto);
  return skillsConhecidas().find((s) => t.startsWith(s.corpo.slice(0, 120)))?.nome ?? null;
}

// ---------------------------------------------------------------- documento de requisitos

const TRECHO = 10; // palavras seguidas iguais às do documento contam como colagem

const palavras = (texto) =>
  String(texto || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim().split(' ').filter(Boolean);

function sequencias(texto) {
  const p = palavras(texto);
  const todas = new Set();
  for (let i = 0; i + TRECHO <= p.length; i++) todas.add(p.slice(i, i + TRECHO).join(' '));
  return todas;
}

// Sequências de 10 palavras do documento, menos as que também estão no contrato, que o
// aluno pode citar à vontade.
const sequenciasDoDocumento = umaVez(() => {
  if (!DOCUMENTO) return null;
  const documento = sequencias(fs.readFileSync(DOCUMENTO, 'utf8'));
  const contrato = path.join(RAIZ, 'contrato-api.md');
  if (fs.existsSync(contrato)) for (const s of sequencias(fs.readFileSync(contrato, 'utf8'))) documento.delete(s);
  return documento;
});

// Tamanho, em palavras, do maior trecho do texto que é cópia literal do documento.
function maiorTrechoCopiado(texto) {
  const documento = sequenciasDoDocumento();
  if (!documento) return 0;
  const p = palavras(texto);
  let maior = 0;
  let seguidas = 0;
  for (let i = 0; i + TRECHO <= p.length; i++) {
    seguidas = documento.has(p.slice(i, i + TRECHO).join(' ')) ? seguidas + 1 : 0;
    if (seguidas) maior = Math.max(maior, seguidas + TRECHO - 1);
  }
  return maior;
}

const LE_REQUISITOS =
  /\b(cat|type|more|less|head|tail|grep|rg|findstr|get-content|gc|sed|awk|find|ls|dir|pdftotext)\b[^|;&]*requisitos/i;

function agenteLeuRequisitos(ferramenta, entrada) {
  if (ferramenta === 'bash') return LE_REQUISITOS.test(entrada.command || '');
  if (!['read', 'grep', 'glob', 'list', 'codesearch'].includes(ferramenta)) return false;
  return Object.values(entrada).some((valor) => typeof valor === 'string'
    && /requisitos/i.test(valor) && !/(^|[\\/])(specs?|entrevistas?)[\\/]/i.test(valor));
}

// ---------------------------------------------------------------- análise

const TESTE_CONHECIDO = new RegExp(`\\b(${[
  'node\\b[^|;&]*--test',
  '(npm|pnpm|yarn|bun)\\s+(run\\s+)?test',
  '(bun|deno|go|cargo|dotnet|mix|flutter|dart|rails)\\s+test',
  'python[0-9.]*\\s+-m\\s+(pytest|unittest)',
  '(mvn|mvnw|gradle|gradlew)(\\.cmd|\\.bat)?\\b[^|;&]*\\btest',
  'php\\s+artisan\\s+test',
  'vitest|jest|mocha|pytest|phpunit|rspec',
].join('|')})\\b`);
// Instalar o pacote de teste, ou citá-lo num commit, não é rodar teste.
const NAO_E_TESTE = /\b(install|add|remove|uninstall|require)\b|\b(npm|pnpm|yarn|bun)\s+i\b|\bpub\s+get\b|\bgit\s/;

function ehComandoDeTeste(comando) {
  const c = normalizar(comando);
  if (!c) return false;
  if (comandosDeclarados().some((declarado) => c.includes(declarado))) return true;
  return !NAO_E_TESTE.test(c) && TESTE_CONHECIDO.test(c);
}

function classificar(arquivo) {
  const nome = arquivo.split('/').pop();
  if (/(^|\/)(AGENTS|CLAUDE)\.md$/i.test(arquivo) || /(^|\/)\.opencode\//.test(arquivo)) return 'contexto';
  if (/(^|\/)entrevistas?\//i.test(arquivo)) return 'entrevista';
  if (/(^|\/)auditorias?\//i.test(arquivo)) return 'auditoria';
  if (/\.(md|txt)$/i.test(nome)) return /(^|\/)specs?\//i.test(arquivo) || /^spec/i.test(nome) ? 'spec' : 'outro';
  if (/(^|\/)(test|tests|__tests__|spec|verificacoes|integration_test)\//i.test(arquivo)) return 'teste';
  if (/^test_/i.test(nome) || /[._-](test|tests|spec)\.\w+$/i.test(nome) || /[a-z0-9](Test|Tests|Spec)\.\w+$/.test(nome)) {
    return 'teste';
  }
  return 'código';
}

function relativo(arquivo, pastaDaSessao) {
  const a = String(arquivo).replace(/\\/g, '/');
  const base = String(pastaDaSessao || '').replace(/\\/g, '/').replace(/\/+$/, '');
  return base && a.toLowerCase().startsWith(`${base.toLowerCase()}/`) ? a.slice(base.length + 1) : a;
}

// Placar de algumas ferramentas de teste. Quando não reconhece, o vermelho e o verde vêm só
// do código de saída do processo, que vale para qualquer stack.
function placar(saida) {
  const t = saida.replace(/\x1b\[[0-9;]*m/g, '');
  const n = (x) => Number(x ?? 0);
  let m;
  const passou = t.match(/^\s*[ℹ#]\s*pass\s+(\d+)/m); // node --test
  const falhou = t.match(/^\s*[ℹ#]\s*fail\s+(\d+)/m);
  if (passou || falhou) return { ok: n(passou?.[1]), falhou: n(falhou?.[1]) };
  if ((m = [...t.matchAll(/\+(\d+)(?:\s+~\d+)?(?:\s+-(\d+))?:\s+(?:All tests passed|Some tests failed)/g)].pop())) {
    return { ok: n(m[1]), falhou: n(m[2]) }; // flutter test, dart test
  }
  if ((m = t.match(/Tests:\s+(?:(\d+) failed, )?(?:\d+ \w+, )*?(\d+) passed/))) return { ok: n(m[2]), falhou: n(m[1]) }; // jest
  if ((m = t.match(/Tests\s+(?:(\d+) failed \| )?(\d+) passed/))) return { ok: n(m[2]), falhou: n(m[1]) }; // vitest
  if ((m = [...t.matchAll(/^=+ (.*\bin [\d.]+s.*?) =+$/gm)].pop())) { // pytest
    return { ok: n(m[1].match(/(\d+) passed/)?.[1]), falhou: n(m[1].match(/(\d+) (?:failed|errors?)/)?.[1]) };
  }
  if ((m = [...t.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+)/g)].pop())) { // maven
    return { ok: n(m[1]) - n(m[2]) - n(m[3]), falhou: n(m[2]) + n(m[3]) };
  }
  if ((m = t.match(/Failed:\s+(\d+),\s+Passed:\s+(\d+)/))) return { ok: n(m[2]), falhou: n(m[1]) }; // dotnet
  const cargo = [...t.matchAll(/test result: \w+\. (\d+) passed; (\d+) failed/g)];
  if (cargo.length) return cargo.reduce((a, c) => ({ ok: a.ok + n(c[1]), falhou: a.falhou + n(c[2]) }), { ok: 0, falhou: 0 });
  if ((m = t.match(/OK \((\d+) tests?/))) return { ok: n(m[1]), falhou: 0 }; // phpunit
  if ((m = t.match(/Tests: (\d+), Assertions: \d+(?:, Errors: (\d+))?(?:, Failures: (\d+))?/))) {
    return { ok: n(m[1]) - n(m[2]) - n(m[3]), falhou: n(m[2]) + n(m[3]) };
  }
  return null;
}

function analisar(sessao) {
  const info = sessao.info || {};
  const r = {
    id: info.id,
    titulo: info.title || '(sem título)',
    pasta: info.directory || '',
    subagente: Boolean(info.parentID),
    inicio: info.time?.created,
    fim: info.time?.updated,
    modelos: new Set(),
    requisicoes: 0,
    entrada: 0,
    saida: 0,
    skills: {},
    subagentes: {},
    vermelhas: 0,
    verdes: 0,
    ciclos: 0,
    nasceuVerde: 0,
    juntos: 0,
    alertas: { colou: 0, leu: 0, anexou: 0 },
    edicoes: { teste: 0, 'código': 0, spec: 0, entrevista: 0, contexto: 0, auditoria: 0, outro: 0 },
    eventos: [],
  };
  // TDD, lido a cada execução de teste a partir do que mudou desde a execução anterior.
  let mudouTeste = false;
  let mudouCodigo = false;
  let aguardandoVerde = false;

  for (const mensagem of sessao.messages || []) {
    const m = mensagem.info || {};
    if (m.role === 'assistant' && m.modelID) r.modelos.add(`${m.providerID}/${m.modelID}`);

    for (const parte of mensagem.parts || []) {
      const quando = parte.state?.time?.start ?? parte.time?.start ?? m.time?.created;

      if (parte.type === 'step-start') r.requisicoes++;
      if (parte.type === 'step-finish' && parte.tokens) {
        r.entrada += parte.tokens.input || 0;
        r.saida += (parte.tokens.output || 0) + (parte.tokens.reasoning || 0);
      }
      if (m.role === 'user' && parte.type === 'text' && !parte.synthetic
          && !/^\s*<system-reminder>/.test(parte.text || '')) {
        const comando = skillDoComando(parte.text);
        if (comando) {
          contar(r.skills, comando);
          r.eventos.push({ quando, tipo: 'comando', texto: comando });
        } else {
          r.eventos.push({ quando, tipo: 'prompt', texto: umaLinha(parte.text, 400) });
          const copiado = maiorTrechoCopiado(parte.text);
          if (copiado >= TRECHO) {
            r.alertas.colou++;
            r.eventos.push({ quando, tipo: 'alerta', texto: `o prompt tem ${copiado} palavras seguidas copiadas do documento de requisitos` });
          }
        }
      }
      if (m.role === 'user' && parte.type === 'agent') {
        r.eventos.push({ quando, tipo: 'menciona', texto: `@${parte.name}` });
      }
      if (m.role === 'user' && parte.type === 'file') {
        const anexo = `${parte.filename || ''} ${parte.source?.path || ''} ${parte.url || ''}`;
        if (/requisitos/i.test(anexo)) {
          r.alertas.anexou++;
          r.eventos.push({ quando, tipo: 'alerta', texto: `documento de requisitos anexado à conversa (${umaLinha(parte.filename || anexo, 80)})` });
        }
      }
      if (parte.type !== 'tool') continue;

      const estado = parte.state || {};
      const entrada = estado.input || {};

      if (agenteLeuRequisitos(parte.tool, entrada)) {
        r.alertas.leu++;
        const alvo = entrada.filePath || entrada.path || entrada.pattern || entrada.command || '';
        r.eventos.push({ quando, tipo: 'alerta', texto: `o agente acessou o documento de requisitos (${parte.tool}: ${umaLinha(alvo, 100)})` });
      }

      if (parte.tool === 'skill') {
        const nome = entrada.name || '?';
        contar(r.skills, nome);
        r.eventos.push({ quando, tipo: 'skill', texto: nome });
      } else if (parte.tool === 'task') {
        const nome = entrada.subagent_type || 'general';
        contar(r.subagentes, nome);
        const resposta = umaLinha(String(estado.output || '').replace(/<\/?[a-z_]+>/g, ' '), 240);
        r.eventos.push({ quando, tipo: 'subagente', nome, descricao: entrada.description || '', resposta });
      } else if (['write', 'edit', 'multiedit'].includes(parte.tool) && entrada.filePath) {
        const arquivo = relativo(entrada.filePath, r.pasta);
        const classe = classificar(arquivo);
        r.edicoes[classe]++;
        if (classe === 'teste') mudouTeste = true;
        if (classe === 'código') mudouCodigo = true;
        const anterior = r.eventos[r.eventos.length - 1];
        if (anterior && anterior.tipo === 'edita' && anterior.arquivo === arquivo) anterior.vezes++;
        else r.eventos.push({ quando, tipo: 'edita', classe, arquivo, vezes: 1 });
      } else if (parte.tool === 'bash' && ehComandoDeTeste(entrada.command)) {
        const p = placar(String(estado.output ?? estado.metadata?.output ?? ''));
        const saidaDoProcesso = estado.metadata?.exit;
        const vermelho = (p !== null && p.falhou > 0)
          || (typeof saidaDoProcesso === 'number' && saidaDoProcesso !== 0)
          || estado.status === 'error';
        const verde = !vermelho && ((p !== null && p.falhou === 0) || saidaDoProcesso === 0);
        if (vermelho) r.vermelhas++;
        if (verde) r.verdes++;

        let leitura = '';
        if (mudouTeste && mudouCodigo) {
          r.juntos++;
          aguardandoVerde = false;
          leitura = 'teste e código mudaram juntos: não houve vermelho para ver';
        } else if (mudouTeste && vermelho) {
          aguardandoVerde = true;
          leitura = 'teste novo falhando, como deve ser';
        } else if (mudouTeste && verde) {
          r.nasceuVerde++;
          aguardandoVerde = false;
          leitura = 'teste novo já nasceu verde';
        } else if (mudouCodigo && verde && aguardandoVerde) {
          r.ciclos++;
          aguardandoVerde = false;
          leitura = 'fecha um ciclo vermelho → verde';
        }
        mudouTeste = false;
        mudouCodigo = false;
        r.eventos.push({ quando, tipo: 'teste', comando: umaLinha(entrada.command, 80), vermelho, verde, placar: p, leitura });
      }
    }
  }
  return r;
}

// ---------------------------------------------------------------- escrita

const RELOGIO = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
});
const hora = (ms) => (ms ? RELOGIO.format(new Date(ms)).replace(',', '') : '—');
const milhar = (n) => n.toLocaleString('pt-BR');
const nomes = (contagem) =>
  Object.entries(contagem).map(([nome, n]) => (n > 1 ? `${nome} (${n})` : nome)).join(', ') || '—';
const alertas = (a) =>
  [a.colou && `colou ${a.colou}`, a.leu && `leu ${a.leu}`, a.anexou && `anexou ${a.anexou}`].filter(Boolean).join(' · ') || '—';
const celula = (texto) => String(texto).replace(/\|/g, '\\|');

function linhaDoTempo(e) {
  const t = `\`${hora(e.quando)}\``;
  switch (e.tipo) {
    case 'prompt': return `- ${t} **prompt** — ${e.texto}`;
    case 'comando': return `- ${t} **/${e.texto}** (skill chamada como comando)`;
    case 'menciona': return `- ${t} menciona **${e.texto}**`;
    case 'skill': return `- ${t} carrega a skill **${e.texto}**`;
    case 'alerta': return `- ${t} **ALERTA** — ${e.texto}`;
    case 'subagente':
      return `- ${t} chama o subagente **${e.nome}**${e.descricao ? ` — ${e.descricao}` : ''}`
        + (e.resposta ? `\n  > ${e.resposta}` : '');
    case 'edita':
      return `- ${t} edita ${e.classe} \`${e.arquivo}\`${e.vezes > 1 ? ` (${e.vezes}×)` : ''}`;
    case 'teste': {
      const cor = e.vermelho ? '**vermelho**' : e.verde ? 'verde' : 'sem resultado';
      const conta = e.placar ? ` (${e.placar.ok} passaram${e.placar.falhou ? `, ${e.placar.falhou} falharam` : ''})` : '';
      return `- ${t} roda \`${e.comando}\` → ${cor}${conta}${e.leitura ? ` — _${e.leitura}_` : ''}`;
    }
    default: return '';
  }
}

function paginaDaSessao(r) {
  const pastaCurta = r.pasta.split(/[\\/]/).filter(Boolean).slice(-2).join('/');
  const e = r.edicoes;
  return [
    `# ${r.titulo}`,
    '',
    '| | |',
    '|---|---|',
    `| Sessão | \`${r.id}\`${r.subagente ? ' (sessão de subagente)' : ''} |`,
    `| Pasta | ${pastaCurta || '—'} |`,
    `| Período | ${hora(r.inicio)} → ${hora(r.fim)} |`,
    `| Modelo | ${[...r.modelos].join(', ') || '—'} |`,
    `| Requisições ao modelo | ${r.requisicoes} |`,
    `| Tokens de entrada / saída | ${milhar(r.entrada)} / ${milhar(r.saida)} |`,
    `| Skills | ${nomes(r.skills)} |`,
    `| Subagentes | ${nomes(r.subagentes)} |`,
    `| Execuções de teste | ${r.vermelhas} vermelhas, ${r.verdes} verdes |`,
    `| TDD | ${r.ciclos} ciclo(s) vermelho → verde · ${r.nasceuVerde} teste(s) que já nasceram verdes · ${r.juntos} vez(es) teste e código juntos |`,
    `| Arquivos editados | ${e.teste} de teste, ${e['código']} de código, ${e.entrevista} de entrevista, ${e.spec} de spec, ${e.contexto} de contexto, ${e.auditoria} de auditoria |`,
    `| Alertas | ${alertas(r.alertas)} |`,
    '',
    '## Linha do tempo',
    '',
    ...r.eventos.map(linhaDoTempo),
    '',
  ].join('\n');
}

function somar(sessoes) {
  const t = {
    sessoes: sessoes.length, requisicoes: 0, vermelhas: 0, verdes: 0, ciclos: 0, nasceuVerde: 0, juntos: 0,
    alertas: { colou: 0, leu: 0, anexou: 0 }, skills: {}, subagentes: {},
  };
  for (const s of sessoes) {
    for (const campo of ['requisicoes', 'vermelhas', 'verdes', 'ciclos', 'nasceuVerde', 'juntos']) t[campo] += s[campo];
    for (const campo of Object.keys(t.alertas)) t.alertas[campo] += s.alertas[campo];
    for (const [k, n] of Object.entries(s.skills)) t.skills[k] = (t.skills[k] || 0) + n;
    for (const [k, n] of Object.entries(s.subagentes)) t.subagentes[k] = (t.subagentes[k] || 0) + n;
  }
  return t;
}

const LEGENDA = [
  'Cada execução de teste é lida pelo que mudou desde a anterior:',
  '',
  '- **Ciclo** — vermelho logo depois de mexer só em teste, e depois verde logo depois de mexer só em código. É o TDD.',
  '- **Nasceu verde** — verde logo depois de mexer só em teste. Ou o comportamento já existia, ou o teste não testa o que diz.',
  '- **Juntos** — teste e código mudaram antes da mesma execução. Não houve vermelho para ver.',
  '',
  `**Alertas:** *colou* = prompt com ${TRECHO} palavras seguidas ou mais iguais às do documento de requisitos (só aparece quando o resumo é gerado com \`--requisitos\`); *leu* = o agente acessou um arquivo de requisitos; *anexou* = o documento foi anexado à conversa.`,
  '',
  'Requisições são chamadas ao modelo: cada passo do agente é uma. Skills contam tanto a ferramenta `skill` quanto o comando `/nome`.',
].join('\n');

const CABECALHO = '| Requisições | Skills | Subagentes | Vermelhas / verdes | Ciclos | Nasceu verde | Juntos | Alertas |';
const SEPARADOR = '|---|---|---|---|---|---|---|---|---|---|';
const colunas = (t) =>
  `| ${milhar(t.requisicoes)} | ${nomes(t.skills)} | ${nomes(t.subagentes)} | ${t.vermelhas} / ${t.verdes} `
  + `| ${t.ciclos} | ${t.nasceuVerde} | ${t.juntos} | ${alertas(t.alertas)} |`;

function indice(nome, sessoes) {
  const t = somar(sessoes);
  return [
    `# Sessões — ${nome}`,
    '',
    LEGENDA,
    '',
    `| Início | Sessão ${CABECALHO}`,
    SEPARADOR,
    ...sessoes.map((s) =>
      `| ${hora(s.inicio)} | [${celula(umaLinha(s.titulo, 60))}](${s.id}.md)${s.subagente ? ' (subagente)' : ''} ${colunas(s)}`),
    `| | **Total: ${t.sessoes} sessões** ${colunas(t)}`,
    '',
  ].join('\n');
}

function listarArquivos(pasta) {
  if (!fs.existsSync(pasta)) return [];
  return fs.readdirSync(pasta, { withFileTypes: true })
    .flatMap((e) => (e.isDirectory() ? listarArquivos(path.join(pasta, e.name)) : [path.join(pasta, e.name)]));
}

function copiasNosArquivos() {
  if (!sequenciasDoDocumento()) return ['Rode com `--requisitos <documento>` para procurar trechos copiados do documento.'];
  const achados = [];
  for (const arquivo of ['specs', 'entrevistas'].flatMap((p) => listarArquivos(path.join(RAIZ, p)))) {
    if (!/\.(md|txt)$/i.test(arquivo)) continue;
    const copiado = maiorTrechoCopiado(fs.readFileSync(arquivo, 'utf8'));
    if (copiado >= TRECHO) {
      achados.push(`- \`${path.relative(RAIZ, arquivo).replace(/\\/g, '/')}\` — ${copiado} palavras seguidas iguais às do documento`);
    }
  }
  return achados.length ? achados : [`Nenhum trecho de ${TRECHO} palavras ou mais copiado do documento em specs/ e entrevistas/.`];
}

function resumoGeral(alunos) {
  return [
    '# Evidências por aluno',
    '',
    LEGENDA,
    '',
    `| Aluno | Sessões ${CABECALHO}`,
    SEPARADOR,
    ...alunos.map(({ aluno, sessoes }) =>
      `| [${aluno}](sessoes/${aluno}/INDICE.md) | ${sessoes.length} ${colunas(somar(sessoes))}`),
    '',
    '## Trechos do documento de requisitos em specs/ e entrevistas/',
    '',
    ...copiasNosArquivos(),
    '',
  ].join('\n');
}

function resumirPasta(pasta, nome) {
  const sessoes = fs.readdirSync(pasta)
    .filter((f) => /^ses_.+\.json$/.test(f))
    .map((f) => {
      const analise = analisar(JSON.parse(fs.readFileSync(path.join(pasta, f), 'utf8')));
      fs.writeFileSync(path.join(pasta, f.replace(/\.json$/, '.md')), paginaDaSessao(analise));
      return analise;
    })
    .sort((a, b) => (a.inicio || 0) - (b.inicio || 0));
  fs.writeFileSync(path.join(pasta, 'INDICE.md'), indice(nome, sessoes));
  return sessoes;
}

// O nome de exibição fica no título do INDICE.md que o próprio aluno gerou.
function nomeJaRegistrado(pasta, padrao) {
  try {
    const primeira = fs.readFileSync(path.join(pasta, 'INDICE.md'), 'utf8').split('\n')[0];
    return primeira.startsWith('# Sessões — ') ? primeira.slice('# Sessões — '.length) : padrao;
  } catch {
    return padrao;
  }
}

function resumirTudo() {
  if (!fs.existsSync(SESSOES)) {
    console.error(`Não existe ${SESSOES}.`);
    process.exit(1);
  }
  const alunos = fs.readdirSync(SESSOES)
    .filter((a) => fs.statSync(path.join(SESSOES, a)).isDirectory())
    .sort()
    .map((aluno) => {
      const pasta = path.join(SESSOES, aluno);
      return { aluno, sessoes: resumirPasta(pasta, nomeJaRegistrado(pasta, aluno)) };
    });
  const destino = path.join(path.dirname(SESSOES), 'RESUMO.md');
  fs.writeFileSync(destino, resumoGeral(alunos));
  console.log(`${alunos.length} aluno(s). Resumo em ${destino}`);
}

if (require.main === module) {
  if (args.includes('--ajuda') || args.includes('-h')) {
    const linhas = fs.readFileSync(__filename, 'utf8').split('\n').slice(1);
    const fim = linhas.findIndex((l) => !l.startsWith('//'));
    console.log(linhas.slice(0, fim).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  } else if (args.includes('--resumir')) {
    resumirTudo();
  } else {
    exportar();
  }
} else {
  module.exports = { placar, classificar, ehComandoDeTeste, maiorTrechoCopiado, analisar };
}
