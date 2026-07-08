import Cropper from 'cropperjs';
import 'cropperjs/dist/cropper.css';
import './styles.css';

// DOM elements
const loadingEl = document.getElementById('loading')!;
const warningEl = document.getElementById('warning')!;
const workspaceEl = document.getElementById('cropper-workspace')!;
const imageEl = document.getElementById('image-element') as HTMLImageElement;

const aspectRatioSelect = document.getElementById('aspect-ratio') as HTMLSelectElement;
const btnRotateLeft = document.getElementById('btn-rotate-left')!;
const btnRotateRight = document.getElementById('btn-rotate-right')!;
const btnFlipH = document.getElementById('btn-flip-h')!;
const btnFlipV = document.getElementById('btn-flip-v')!;
const btnApply = document.getElementById('btn-apply') as HTMLButtonElement;
const applySpinner = document.getElementById('apply-spinner')!;

// State variables
let cropper: Cropper | null = null;
let currentImageUrl: string | null = null;
let imageMimeType = 'image/png';
let scaleX = 1;
let scaleY = 1;

// Notify sandbox that UI is ready to receive messages
parent.postMessage({ type: 'ready' }, '*');

// Listen to messages from the sandbox parent window
window.addEventListener('message', (event) => {
  const message = event.data;

  switch (message.type) {
    case 'theme':
      document.body.setAttribute('data-theme', message.content);
      break;

    case 'loading':
      showState('loading');
      break;

    case 'no-selection':
      showState('warning');
      break;

    case 'image-data':
      loadImage(message);
      break;

    case 'crop-success':
      setApplyLoading(false);
      break;

    case 'error':
      setApplyLoading(false);
      alert(message.message || 'An error occurred.');
      break;
  }
});

function showState(state: 'loading' | 'warning' | 'workspace') {
  loadingEl.classList.add('hidden');
  warningEl.classList.add('hidden');
  workspaceEl.classList.add('hidden');

  if (state === 'loading') {
    loadingEl.classList.remove('hidden');
  } else if (state === 'warning') {
    warningEl.classList.remove('hidden');
    destroyCropper();
  } else if (state === 'workspace') {
    workspaceEl.classList.remove('hidden');
  }
}

function destroyCropper() {
  if (cropper) {
    cropper.destroy();
    cropper = null;
  }
  if (currentImageUrl) {
    URL.revokeObjectURL(currentImageUrl);
    currentImageUrl = null;
  }
}

function loadImage(message: { bytes: Uint8Array; mtype: string }) {
  destroyCropper();

  imageMimeType = message.mtype || 'image/png';
  scaleX = 1;
  scaleY = 1;

  // Create Object URL from bytes
  const blob = new Blob([message.bytes], { type: imageMimeType });
  currentImageUrl = URL.createObjectURL(blob);
  imageEl.src = currentImageUrl;

  // Setup onload handler to initialize Cropper only when image is ready
  imageEl.onload = () => {
    cropper = new Cropper(imageEl, {
      aspectRatio: NaN, // Free ratio by default
      viewMode: 1,      // Restrict crop box to canvas
      autoCropArea: 0.85,
      background: false,
      responsive: true,
      ready() {
        showState('workspace');
      },
    });
  };

  imageEl.onerror = () => {
    showState('warning');
    alert('Failed to load image into editor.');
  };
}

// Action: Aspect Ratio Change
aspectRatioSelect.addEventListener('change', () => {
  if (!cropper) return;
  const value = parseFloat(aspectRatioSelect.value);
  cropper.setAspectRatio(isNaN(value) ? NaN : value);
});

// Action: Rotate Left
btnRotateLeft.addEventListener('click', () => {
  if (cropper) cropper.rotate(-90);
});

// Action: Rotate Right
btnRotateRight.addEventListener('click', () => {
  if (cropper) cropper.rotate(90);
});

// Action: Flip Horizontal
btnFlipH.addEventListener('click', () => {
  if (!cropper) return;
  scaleX = scaleX === 1 ? -1 : 1;
  cropper.scaleX(scaleX);
});

// Action: Flip Vertical
btnFlipV.addEventListener('click', () => {
  if (!cropper) return;
  scaleY = scaleY === 1 ? -1 : 1;
  cropper.scaleY(scaleY);
});

// Action: Apply Crop
btnApply.addEventListener('click', () => {
  if (!cropper) return;

  setApplyLoading(true);

  // Get cropped canvas
  const canvas = cropper.getCroppedCanvas({
    imageSmoothingEnabled: true,
    imageSmoothingQuality: 'high',
  });

  if (!canvas) {
    setApplyLoading(false);
    alert('Could not crop the image.');
    return;
  }

  // Convert canvas to blob and extract raw bytes
  canvas.toBlob((blob) => {
    if (!blob) {
      setApplyLoading(false);
      alert('Could not export cropped image.');
      return;
    }

    const reader = new FileReader();
    reader.onloadend = () => {
      const bytes = new Uint8Array(reader.result as ArrayBuffer);
      parent.postMessage(
        {
          type: 'crop',
          bytes: bytes,
          mtype: imageMimeType,
        },
        '*'
      );
    };
    reader.readAsArrayBuffer(blob);
  }, imageMimeType);
});

function setApplyLoading(isLoading: boolean) {
  if (isLoading) {
    btnApply.disabled = true;
    applySpinner.classList.remove('hidden');
    btnApply.querySelector('.btn-text')?.classList.add('hidden');
  } else {
    btnApply.disabled = false;
    applySpinner.classList.add('hidden');
    btnApply.querySelector('.btn-text')?.classList.remove('hidden');
  }
}
