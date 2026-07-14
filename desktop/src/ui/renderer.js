const root = document.documentElement;
const title = document.querySelector('[data-status-title]');
const detail = document.querySelector('[data-status-detail]');
const progressBar = document.querySelector('[data-progress-bar]');
const progressLabel = document.querySelector('[data-progress-label]');
const retryButton = document.querySelector('[data-retry]');
const copyButton = document.querySelector('[data-copy]');
const errorPanel = document.querySelector('[data-error-panel]');
const errorDetails = document.querySelector('[data-error-details]');
const steps = [...document.querySelectorAll('[data-step]')];
const desktopApi = window.penpotDesktop || {
  retry: async () => false,
  copyDetails: async () => false,
  onStatus: () => () => {}
};

const phaseOrder = ['checking', 'docker', 'starting', 'waiting', 'ready'];

function updateSteps(phase) {
  const currentIndex = phaseOrder.indexOf(phase);
  steps.forEach((step) => {
    const stepPhases = step.dataset.step.split(',');
    const stepIndexes = stepPhases.map((item) => phaseOrder.indexOf(item)).filter((index) => index >= 0);
    const lastStepIndex = Math.max(...stepIndexes);
    step.dataset.state = stepPhases.includes(phase) ? 'active' : currentIndex > lastStepIndex ? 'complete' : 'pending';
  });
}

function renderStatus(status) {
  const phase = status.phase || 'checking';
  const progress = Math.max(0, Math.min(1, Number(status.progress) || 0));
  const isError = phase === 'error';
  const isStopped = phase === 'stopped';

  root.dataset.phase = phase;
  title.textContent = status.title || 'Preparing Penpot';
  detail.textContent = status.detail || 'Checking the local environment…';
  progressBar.style.transform = `scaleX(${progress})`;
  progressLabel.textContent = `${Math.round(progress * 100)}%`;
  errorPanel.hidden = !isError;
  errorDetails.textContent = isError ? status.detail || 'No additional information is available.' : '';
  retryButton.hidden = !(isError || isStopped);
  retryButton.textContent = isStopped ? 'Start Penpot' : 'Try again';
  copyButton.hidden = !isError;

  if (isError || isStopped) {
    steps.forEach((step) => {
      if (step.dataset.state === 'active') step.dataset.state = isError ? 'error' : 'pending';
    });
  } else {
    updateSteps(phase);
  }
}

retryButton.addEventListener('click', async () => {
  retryButton.disabled = true;
  retryButton.textContent = 'Starting…';
  await desktopApi.retry();
  retryButton.disabled = false;
});

copyButton.addEventListener('click', async () => {
  await desktopApi.copyDetails();
  const original = copyButton.textContent;
  copyButton.textContent = 'Copied';
  setTimeout(() => {
    copyButton.textContent = original;
  }, 1800);
});

desktopApi.onStatus(renderStatus);

renderStatus({
  phase: 'checking',
  title: 'Preparing Penpot',
  detail: 'Checking the local environment…',
  progress: 0.04
});
