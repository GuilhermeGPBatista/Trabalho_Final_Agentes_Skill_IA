async function carregarPainelAtividades() {
  clearAlert('alert-box');
  const tbody = document.getElementById('tabela-painel-atividades');
  const selectSemChance = document.getElementById('select-atividade-sem-chance');
  const selectCsv = document.getElementById('select-atividade-csv');

  if (tbody) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--text-muted);">Carregando...</td></tr>`;
  }

  try {
    const atividades = await apiFetch('/painel/atividades');

    if (tbody) {
      if (atividades.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--text-muted);">Nenhuma atividade encontrada.</td></tr>`;
      } else {
        tbody.innerHTML = atividades.map(atv => `
          <tr>
            <td><code>${escapeHtml(atv.atividadeId)}</code></td>
            <td><strong>${escapeHtml(atv.titulo)}</strong></td>
            <td>${atv.vagas}</td>
            <td>${atv.ocupadas}</td>
            <td>${atv.emEspera}</td>
            <td>${Number(atv.ocupacaoPercentual).toFixed(1)}%</td>
            <td>${atv.frequenciaPercentual !== null ? Number(atv.frequenciaPercentual).toFixed(1) + '%' : 'N/A'}</td>
          </tr>
        `).join('');
      }
    }

    const optionsHtml = '<option value="">Selecione uma atividade...</option>' + 
      atividades.map(atv => `<option value="${atv.atividadeId}">${escapeHtml(atv.titulo)} (${atv.atividadeId})</option>`).join('');

    if (selectSemChance) selectSemChance.innerHTML = optionsHtml;
    if (selectCsv) selectCsv.innerHTML = optionsHtml;

  } catch (err) {
    showError('alert-box', err);
    if (tbody) {
      tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--danger);">Erro ao carregar métricas.</td></tr>`;
    }
  }
}

async function carregarSemChance() {
  clearAlert('alert-box');
  const select = document.getElementById('select-atividade-sem-chance');
  const tbody = document.getElementById('tabela-sem-chance');
  if (!select || !tbody) return;

  const atividadeId = select.value;
  if (!atividadeId) {
    tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-muted);">Selecione uma atividade acima.</td></tr>`;
    return;
  }

  tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-muted);">Carregando...</td></tr>`;

  try {
    const lista = await apiFetch(`/painel/atividades/${atividadeId}/sem-chance`);
    if (lista.length === 0) {
      tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-muted);">Nenhum participante sem chance nesta atividade.</td></tr>`;
      return;
    }

    tbody.innerHTML = lista.map(sc => `
      <tr>
        <td><code>${escapeHtml(sc.participanteId)}</code></td>
        <td><strong>${escapeHtml(sc.nome)}</strong></td>
        <td>${sc.faltas}</td>
        <td>${sc.faltasPermitidas}</td>
      </tr>
    `).join('');
  } catch (err) {
    showError('alert-box', err);
    tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--danger);">Erro ao carregar participantes sem chance.</td></tr>`;
  }
}

async function baixarCsv() {
  clearAlert('alert-box');
  const select = document.getElementById('select-atividade-csv');
  if (!select) return;

  const atividadeId = select.value;
  if (!atividadeId) {
    alert('Selecione uma atividade para baixar o CSV.');
    return;
  }

  try {
    const user = getSelectedUser();
    const response = await fetch(`${API_URL}/painel/atividades/${atividadeId}/frequencia.csv`, {
      headers: {
        'X-Usuario': user
      }
    });

    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw {
        status: response.status,
        error: data.erro || 'ERRO_CSV',
        message: data.mensagem || 'Erro ao gerar CSV'
      };
    }

    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `frequencia-${atividadeId}.csv`;
    document.body.appendChild(a);
    a.click();
    window.URL.revokeObjectURL(url);
    a.remove();
    showSuccess('alert-box', 'Arquivo CSV baixado com sucesso!');
  } catch (err) {
    showError('alert-box', err);
  }
}

async function carregarBloqueios() {
  clearAlert('alert-box');
  const tbody = document.getElementById('tabela-bloqueios');
  if (!tbody) return;

  tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-muted);">Carregando bloqueios...</td></tr>`;

  try {
    const bloqueios = await apiFetch('/painel/bloqueios');

    if (bloqueios.length === 0) {
      tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-muted);">Nenhum participante bloqueado no momento.</td></tr>`;
      return;
    }

    tbody.innerHTML = bloqueios.map(b => `
      <tr>
        <td><code>${escapeHtml(b.participanteId)}</code></td>
        <td><strong>${escapeHtml(b.nome)}</strong></td>
        <td>${Array.isArray(b.atividades) ? b.atividades.join(', ') : b.atividades}</td>
        <td>${escapeHtml(b.bloqueadoDesde)}</td>
        <td>
          <button onclick="desbloquearParticipante('${b.participanteId}')" style="background-color: var(--danger); padding: 4px 8px; font-size: 12px;">Desbloquear</button>
        </td>
      </tr>
    `).join('');
  } catch (err) {
    showError('alert-box', err);
    tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--danger);">Erro ao carregar bloqueios.</td></tr>`;
  }
}

async function desbloquearParticipante(participanteId) {
  clearAlert('alert-box');
  if (!confirm(`Deseja realmente desbloquear o participante ${participanteId}?`)) return;

  try {
    await apiFetch(`/painel/bloqueios/${participanteId}`, {
      method: 'DELETE'
    });
    showSuccess('alert-box', `Participante ${participanteId} desbloqueado com sucesso!`);
    carregarBloqueios();
    carregarPainelAtividades();
  } catch (err) {
    showError('alert-box', err);
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

document.addEventListener('DOMContentLoaded', () => {
  const selectSemChance = document.getElementById('select-atividade-sem-chance');
  const btnCsv = document.getElementById('btn-baixar-csv');

  if (selectSemChance) {
    selectSemChance.addEventListener('change', carregarSemChance);
  }
  if (btnCsv) {
    btnCsv.addEventListener('click', baixarCsv);
  }

  carregarPainelAtividades();
  carregarBloqueios();
});

window.addEventListener('userChanged', () => {
  carregarPainelAtividades();
  carregarBloqueios();
});
