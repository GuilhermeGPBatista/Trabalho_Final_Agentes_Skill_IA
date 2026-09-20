const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function criarAmbiente(fetchImpl) {
  const elementos = new Map();
  const domCallbacks = [];
  const localStorageData = new Map([['X-Usuario', 'org-ana']]);
  const criarElemento = (id) => ({
    id,
    value: '',
    hidden: false,
    className: '',
    innerHTML: '',
    textContent: '',
    listeners: {},
    addEventListener(evento, callback) {
      this.listeners[evento] = callback;
    }
  });
  const document = {
    getElementById(id) {
      if (!elementos.has(id)) elementos.set(id, criarElemento(id));
      return elementos.get(id);
    },
    addEventListener(evento, callback) {
      if (evento === 'DOMContentLoaded') domCallbacks.push(callback);
    }
  };
  const context = {
    console,
    document,
    window: { 
      addEventListener() {},
      URL: {
        createObjectURL: () => 'blob:url',
        revokeObjectURL: () => {}
      }
    },
    localStorage: {
      getItem: (chave) => localStorageData.get(chave) || null,
      setItem: (chave, valor) => localStorageData.set(chave, valor)
    },
    fetch: fetchImpl,
    confirm: () => true,
    setTimeout,
    clearTimeout
  };
  vm.createContext(context);
  for (const arquivo of ['app.js', 'painel.js']) {
    const codigo = fs.readFileSync(path.join(__dirname, '..', arquivo), 'utf8');
    vm.runInContext(codigo, context, { filename: arquivo });
  }
  for (const callback of domCallbacks) callback();
  return { context, elementos };
}

test('carregamento das atividades e renderizacao de ocupacao/frequencia', async () => {
  const ambiente = criarAmbiente(async (url) => {
    if (url.includes('/painel/atividades')) {
      return {
        ok: true,
        status: 200,
        async json() {
          return [{
            atividadeId: 'atv_1',
            titulo: 'Atividade Teste',
            vagas: 20,
            ocupadas: 10,
            emEspera: 2,
            ocupacaoPercentual: 50.0,
            frequenciaPercentual: 80.0
          }];
        }
      };
    }
    return { ok: true, status: 200, async json() { return []; } };
  });

  await ambiente.context.carregarPainelAtividades();

  const tbody = ambiente.context.document.getElementById('tabela-painel-atividades');
  assert.match(tbody.innerHTML, /Atividade Teste/);
  assert.match(tbody.innerHTML, /50\.0%/);
  assert.match(tbody.innerHTML, /80\.0%/);
});

test('consulta de participantes sem chance', async () => {
  const ambiente = criarAmbiente(async (url) => {
    if (url.includes('/sem-chance')) {
      return {
        ok: true,
        status: 200,
        async json() {
          return [{
            participanteId: 'p-heitor',
            nome: 'Heitor Campos',
            faltas: 2,
            faltasPermitidas: 1
          }];
        }
      };
    }
    return { ok: true, status: 200, async json() { return []; } };
  });

  const select = ambiente.context.document.getElementById('select-atividade-sem-chance');
  select.value = 'atv_1';

  await ambiente.context.carregarSemChance();

  const tbody = ambiente.context.document.getElementById('tabela-sem-chance');
  assert.match(tbody.innerHTML, /Heitor Campos/);
  assert.match(tbody.innerHTML, /p-heitor/);
});

test('carregamento de bloqueios e acionamento de desbloqueio', async () => {
  let deleted = false;
  const ambiente = criarAmbiente(async (url, options) => {
    if (options && options.method === 'DELETE') {
      deleted = true;
      return { ok: true, status: 204, async json() { return null; } };
    }
    if (url.includes('/painel/bloqueios')) {
      return {
        ok: true,
        status: 200,
        async json() {
          return [{
            participanteId: 'p-heitor',
            nome: 'Heitor Campos',
            atividades: ['atv_1', 'atv_2'],
            bloqueadoDesde: '2026-10-20T12:00:00-03:00'
          }];
        }
      };
    }
    return { ok: true, status: 200, async json() { return []; } };
  });

  await ambiente.context.carregarBloqueios();
  const tbody = ambiente.context.document.getElementById('tabela-bloqueios');
  assert.match(tbody.innerHTML, /Heitor Campos/);

  await ambiente.context.desbloquearParticipante('p-heitor');
  assert.equal(deleted, true);
});

test('tratamento de erro da API no painel', async () => {
  const ambiente = criarAmbiente(async () => ({
    ok: false,
    status: 403,
    async json() {
      return { erro: 'SOMENTE_ORGANIZACAO', mensagem: 'Apenas organização' };
    }
  }));

  await ambiente.context.carregarPainelAtividades();

  const alerta = ambiente.context.document.getElementById('alert-box');
  assert.equal(alerta.className, 'alert error');
  assert.match(alerta.innerHTML, /SOMENTE_ORGANIZACAO/);
});
