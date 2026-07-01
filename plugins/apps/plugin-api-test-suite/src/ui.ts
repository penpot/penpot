import 'plugins-styles/lib/styles.css';
import './ui.css';

import type { PluginToUIMessage, UIToPluginMessage } from './model';
import type {
  CoverageReport,
  TestMeta,
  TestResult,
  TestStatus,
} from './framework/types';

const root = document.getElementById('app') as HTMLElement;

let tests: TestMeta[] = [];
const results = new Map<string, TestResult>();
const selected = new Set<string>();
const expandedGroups = new Set<string>();
let running = false;
let reloading = false;
let statusText = '';

/** Groups tests by their `group`, preserving first-seen order. */
function groupTests(): { name: string; tests: TestMeta[] }[] {
  const order: string[] = [];
  const byGroup = new Map<string, TestMeta[]>();
  for (const test of tests) {
    let bucket = byGroup.get(test.group);
    if (!bucket) {
      bucket = [];
      byGroup.set(test.group, bucket);
      order.push(test.group);
    }
    bucket.push(test);
  }
  return order.map((name) => ({ name, tests: byGroup.get(name)! }));
}

function applyTheme(theme: string | null) {
  document.documentElement.setAttribute(
    'data-theme',
    theme === 'light' ? 'light' : 'dark',
  );
}

function sendToPlugin(message: UIToPluginMessage) {
  // `'*'` is intentional: the plugin host controls this iframe's parent and the
  // exact embedding origin isn't known ahead of time. Standard for Penpot
  // plugin iframes; nothing sensitive crosses this channel.
  parent.postMessage(message, '*');
}

/**
 * Rolls a group's leaf statuses up into a single status for its header dot:
 * running if any test is running, otherwise failed if any failed, otherwise
 * passed only when every test passed, and pending until then.
 */
function aggregateStatus(statuses: TestStatus[]): TestStatus {
  if (statuses.some((s) => s === 'running')) return 'running';
  if (statuses.some((s) => s === 'fail')) return 'fail';
  if (statuses.length > 0 && statuses.every((s) => s === 'pass')) return 'pass';
  return 'pending';
}

function statusLabel(status: TestStatus): string {
  switch (status) {
    case 'pass':
      return 'Passed';
    case 'fail':
      return 'Failed';
    case 'running':
      return 'Running…';
    default:
      return 'Not run';
  }
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> = {},
  children: (Node | string)[] = [],
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  Object.assign(node, props);
  for (const child of children) {
    node.append(child);
  }
  return node;
}

function svgIcon(paths: string[], fill: boolean): SVGSVGElement {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('width', '12');
  svg.setAttribute('height', '12');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('icon');
  for (const d of paths) {
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', d);
    if (fill) {
      path.setAttribute('fill', 'currentColor');
    } else {
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke', 'currentColor');
      path.setAttribute('stroke-width', '1.5');
      path.setAttribute('stroke-linecap', 'round');
      path.setAttribute('stroke-linejoin', 'round');
    }
    svg.append(path);
  }
  return svg;
}

/** A small triangular "play" icon used on run buttons. */
function playIcon(): SVGSVGElement {
  return svgIcon(['M4 2.5v11l9-5.5z'], true);
}

/** A spinning ring, shown wherever something is in progress. */
function spinner(): HTMLElement {
  return el('span', { className: 'spinner', ariaLabel: 'In progress' });
}

/** A circular-arrow "reload" icon. */
function reloadIcon(): SVGSVGElement {
  return svgIcon(['M13 8a5 5 0 1 1-1.46-3.54', 'M13 2.5v3h-3'], false);
}

function render() {
  root.replaceChildren(
    renderHeader(),
    renderToolbar(),
    renderList(),
    renderCoverage(),
  );
}

function renderHeader(): HTMLElement {
  const passed = [...results.values()].filter(
    (r) => r.status === 'pass',
  ).length;
  const failed = [...results.values()].filter(
    (r) => r.status === 'fail',
  ).length;
  const summary = el('p', { className: 'summary' }, [
    `${tests.length} tests · ${passed} passed · ${failed} failed`,
  ]);
  if (running) {
    summary.append(
      ' · ',
      el('span', { className: 'running-badge' }, [spinner(), 'Running…']),
    );
  }

  return el('header', { className: 'header' }, [
    el('h1', { className: 'title', textContent: 'Plugin API Test Suite' }),
    summary,
  ]);
}

function renderToolbar(): HTMLElement {
  const runAll = el('button', {
    textContent: 'Run all',
    disabled: running || tests.length === 0,
  });
  runAll.dataset.appearance = 'primary';
  runAll.addEventListener('click', () => run('all'));

  const runSelected = el('button', {
    textContent: 'Run selected',
    disabled: running || selected.size === 0,
  });
  runSelected.dataset.appearance = 'secondary';
  runSelected.addEventListener('click', () => run([...selected]));

  const reload = el('button', {
    className: `icon-button reload${reloading ? ' is-loading' : ''}`,
    title: reloading
      ? 'Reloading tests…'
      : 'Reload: fetch and apply edited tests without reopening the plugin',
    ariaLabel: 'Reload tests',
    disabled: running || reloading,
  });
  reload.dataset.appearance = 'secondary';
  reload.append(reloadIcon());
  reload.addEventListener('click', () => reloadTests());

  const toolbar = el('div', { className: 'toolbar' }, [
    runAll,
    runSelected,
    reload,
  ]);

  if (statusText) {
    toolbar.append(
      el('span', { className: 'toolbar-status', textContent: statusText }),
    );
  }

  return toolbar;
}

function renderRow(test: TestMeta): HTMLElement {
  const result = results.get(test.id);
  const status = result?.status ?? 'pending';

  const checkbox = el('input', {
    type: 'checkbox',
    className: 'checkbox-input',
    checked: selected.has(test.id),
    disabled: running,
  });
  checkbox.addEventListener('change', () => {
    if (checkbox.checked) selected.add(test.id);
    else selected.delete(test.id);
    render();
  });

  const runButton = el('button', {
    className: 'icon-button run-single',
    title: `Run "${test.name}"`,
    ariaLabel: `Run "${test.name}"`,
    disabled: running,
  });
  runButton.dataset.appearance = 'secondary';
  runButton.append(playIcon());
  runButton.addEventListener('click', () => run([test.id]));

  const durationCell =
    status === 'running'
      ? el('span', { className: 'test-duration running' }, [
          spinner(),
          'Running…',
        ])
      : el('span', {
          className: 'test-duration',
          textContent: result ? `${result.durationMs}ms` : '',
        });

  const row = el('li', { className: `test-row status-${status}` }, [
    el('label', { className: 'test-main' }, [
      checkbox,
      el('span', {
        className: `status-dot dot-${status}`,
        title: statusLabel(status),
      }),
      el('span', { className: 'test-name', textContent: test.name }),
    ]),
    durationCell,
    runButton,
  ]);

  if (result?.status === 'fail' && result.error) {
    row.append(
      el('pre', { className: 'test-error', textContent: result.error }),
    );
  }

  return row;
}

function renderGroupSummary(
  name: string,
  groupTestList: TestMeta[],
): HTMLElement {
  const statuses = groupTestList.map(
    (t) => results.get(t.id)?.status ?? 'pending',
  );
  const passed = statuses.filter((s) => s === 'pass').length;
  const failed = statuses.filter((s) => s === 'fail').length;
  const total = groupTestList.length;
  const aggregate = aggregateStatus(statuses);

  // Select-all checkbox for the group (indeterminate when partially selected).
  const ids = groupTestList.map((t) => t.id);
  const selectedCount = ids.filter((id) => selected.has(id)).length;
  const groupCheckbox = el('input', {
    type: 'checkbox',
    className: 'checkbox-input',
    checked: selectedCount === total && total > 0,
    disabled: running,
  });
  groupCheckbox.indeterminate = selectedCount > 0 && selectedCount < total;
  // Keep the checkbox from toggling the <details> when clicked.
  groupCheckbox.addEventListener('click', (e) => e.stopPropagation());
  groupCheckbox.addEventListener('change', () => {
    if (groupCheckbox.checked) ids.forEach((id) => selected.add(id));
    else ids.forEach((id) => selected.delete(id));
    render();
  });

  const runButton = el('button', {
    className: 'icon-button run-group',
    title: `Run "${name}"`,
    ariaLabel: `Run "${name}"`,
    disabled: running,
  });
  runButton.dataset.appearance = 'secondary';
  runButton.append(playIcon());
  runButton.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    run(ids);
  });

  const counts = el('span', { className: 'group-counts' }, [
    el('span', { className: 'count-pass', textContent: `${passed}` }),
    el('span', { className: 'count-sep', textContent: ' / ' }),
    el('span', { className: 'count-fail', textContent: `${failed}` }),
    el('span', {
      className: 'count-total',
      textContent: ` · ${total} test${total === 1 ? '' : 's'}`,
    }),
  ]);

  return el('summary', { className: 'group-summary' }, [
    groupCheckbox,
    el('span', {
      className: `status-dot dot-${aggregate}`,
      title: statusLabel(aggregate),
    }),
    el('span', { className: 'group-name', textContent: name }),
    counts,
    runButton,
  ]);
}

function renderList(): HTMLElement {
  const container = el('div', { className: 'groups' });

  for (const group of groupTests()) {
    const details = el('details', { className: 'group' });
    // Groups are collapsed by default; remember the ones the user expands.
    details.open = expandedGroups.has(group.name);
    details.addEventListener('toggle', () => {
      if (details.open) expandedGroups.add(group.name);
      else expandedGroups.delete(group.name);
    });

    details.append(renderGroupSummary(group.name, group.tests));

    const list = el('ul', { className: 'test-list' });
    for (const test of group.tests) {
      list.append(renderRow(test));
    }
    details.append(list);

    container.append(details);
  }

  return container;
}

let lastCoverage: CoverageReport | null = null;

function renderProgressBar(
  percent: number,
  effectivePercent: number,
): HTMLElement {
  const track = el('div', {
    className: 'progress-track',
    role: 'progressbar',
    title: `${percent}% recorded, ${effectivePercent}% effective`,
  });
  track.setAttribute('aria-valuenow', String(percent));
  track.setAttribute('aria-valuemin', '0');
  track.setAttribute('aria-valuemax', '100');
  // Layered: the static segment (lighter) spans the effective coverage, the
  // recorded fill (green) sits on top spanning the recorder-credited coverage.
  const staticFill = el('div', { className: 'progress-fill static' });
  staticFill.style.width = `${effectivePercent}%`;
  const fill = el('div', { className: 'progress-fill' });
  fill.style.width = `${percent}%`;
  track.append(staticFill, fill);
  return track;
}

function renderCoverage(): HTMLElement {
  const section = el('div', { className: 'coverage' });

  if (!lastCoverage) {
    section.append(
      el('p', {
        className: 'coverage-empty',
        textContent: 'API coverage — run tests to measure',
      }),
    );
    return section;
  }

  const {
    covered,
    staticallyCovered,
    total,
    percent,
    effectivePercent,
    byInterface,
  } = lastCoverage;

  const valueText =
    staticallyCovered > 0
      ? `${percent}% · ${effectivePercent}% eff. (${covered}+${staticallyCovered}/${total})`
      : `${percent}% (${covered}/${total})`;

  section.append(
    el('div', { className: 'coverage-header' }, [
      el('span', {
        className: 'coverage-title',
        textContent: 'API coverage',
      }),
      el('span', {
        className: 'coverage-value',
        textContent: valueText,
      }),
    ]),
    renderProgressBar(percent, effectivePercent),
  );

  const details = el('details', { className: 'coverage-details' });
  details.append(el('summary', { textContent: 'Coverage by interface' }));

  const list = el('div', { className: 'coverage-body' });
  const interfaces = Object.entries(byInterface)
    .filter(([, info]) => info.members.length > 0)
    .sort(([a], [b]) => a.localeCompare(b));

  for (const [iface, info] of interfaces) {
    const members = el('div', { className: 'coverage-members' });
    // Covered (green) first, then statically covered (blue), then uncovered.
    for (const m of info.covered) {
      members.append(
        el('span', { className: 'coverage-member covered', textContent: m }),
      );
    }
    for (const m of info.staticallyCovered) {
      members.append(
        el('span', {
          className: 'coverage-member static',
          textContent: m,
          title: 'Exercised behaviourally; not creditable via the proxy',
        }),
      );
    }
    for (const m of info.uncovered) {
      members.append(
        el('span', { className: 'coverage-member uncovered', textContent: m }),
      );
    }

    const ifaceLabel =
      info.staticallyCovered.length > 0
        ? `${iface} (${info.covered.length}+${info.staticallyCovered.length}/${info.members.length})`
        : `${iface} (${info.covered.length}/${info.members.length})`;

    list.append(
      el('div', { className: 'coverage-iface' }, [
        el('strong', {
          textContent: ifaceLabel,
        }),
        members,
      ]),
    );
  }

  details.append(list);
  section.append(details);
  return section;
}

function run(ids: string[] | 'all') {
  if (running) return;
  running = true;

  const targetIds = ids === 'all' ? tests.map((t) => t.id) : ids;
  for (const id of targetIds) {
    const test = tests.find((t) => t.id === id);
    if (test) {
      results.set(id, {
        id,
        name: test.name,
        status: 'running',
        durationMs: 0,
      });
    }
  }

  render();
  sendToPlugin({ type: 'run', ids });
}

async function reloadTests() {
  if (running || reloading) return;
  reloading = true;
  statusText = '';
  render();

  try {
    // Fetch the freshly built tests bundle from the dev server (same origin as
    // this iframe). `vite build --watch` rebuilds it on every save, so this
    // picks up edited tests. The cache-busting query avoids any stale copy.
    const response = await fetch(`./tests-bundle.js?t=${Date.now()}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch tests bundle (${response.status})`);
    }
    const code = await response.text();
    // The sandbox evaluates the bundle and replies with `reloaded` + `tests`.
    sendToPlugin({ type: 'reloadTests', code });
  } catch (err) {
    reloading = false;
    statusText = `Reload failed: ${err instanceof Error ? err.message : String(err)}`;
    render();
  }
}

window.addEventListener('message', (event: MessageEvent<PluginToUIMessage>) => {
  const message = event.data;
  if (!message || typeof message !== 'object') return;

  switch (message.type) {
    case 'tests': {
      tests = message.tests;
      // Drop results/selection for tests that no longer exist after a reload.
      const ids = new Set(tests.map((t) => t.id));
      for (const id of [...results.keys()]) {
        if (!ids.has(id)) results.delete(id);
      }
      for (const id of [...selected]) {
        if (!ids.has(id)) selected.delete(id);
      }
      render();
      break;
    }
    case 'result':
      results.set(message.result.id, message.result);
      render();
      break;
    case 'runComplete':
      running = false;
      lastCoverage = message.coverage;
      render();
      break;
    case 'reloaded':
      reloading = false;
      statusText = message.ok
        ? `Reloaded ${tests.length} tests`
        : `Reload failed: ${message.error ?? 'unknown error'}`;
      render();
      break;
    case 'theme':
      applyTheme(message.theme);
      break;
  }
});

applyTheme(new URLSearchParams(window.location.search).get('theme'));
render();
sendToPlugin({ type: 'ready' });
