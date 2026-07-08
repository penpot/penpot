import Cropper from 'cropperjs';
import 'cropperjs/dist/cropper.css';
import './styles.css';

// DOM elements
const tabsEl = document.getElementById('tabs')!;
const tabBtns = document.querySelectorAll('.tab-btn');
const loadingEl = document.getElementById('loading')!;
const warningEl = document.getElementById('warning')!;
const workspaceEl = document.getElementById('cropper-workspace')!;
const eraserWorkspaceEl = document.getElementById('eraser-workspace')!;
const imageEl = document.getElementById('image-element') as HTMLImageElement;

// Crop UI elements
const aspectRatioSelect = document.getElementById('aspect-ratio') as HTMLSelectElement;
const btnRotateLeft = document.getElementById('btn-rotate-left')!;
const btnRotateRight = document.getElementById('btn-rotate-right')!;
const btnFlipH = document.getElementById('btn-flip-h')!;
const btnFlipV = document.getElementById('btn-flip-v')!;
const btnApply = document.getElementById('btn-apply') as HTMLButtonElement;
const applySpinner = document.getElementById('apply-spinner')!;

// Erase UI elements
const eraserCanvas = document.getElementById('eraser-canvas') as HTMLCanvasElement;
const btnPresetWhite = document.getElementById('btn-preset-white')!;
const btnPresetBlack = document.getElementById('btn-preset-black')!;
const colorPickerInput = document.getElementById('color-picker') as HTMLInputElement;
const sampledColorPreview = document.getElementById('sampled-color-preview')!;
const toleranceSlider = document.getElementById('tolerance') as HTMLInputElement;
const toleranceValSpan = document.getElementById('tolerance-val')!;
const btnApplyErase = document.getElementById('btn-apply-erase') as HTMLButtonElement;
const eraseSpinner = document.getElementById('erase-spinner')!;

// State variables
let cropper: Cropper | null = null;
let currentImageUrl: string | null = null;
let imageMimeType = 'image/png';
let scaleX = 1;
let scaleY = 1;

let activeTab = 'crop';
let originalImageData: ImageData | null = null;
let targetColor = { r: 255, g: 255, b: 255 };
let tolerance = 30;

// Notify sandbox that UI is ready
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
      setEraseLoading(false);
      break;

    case 'error':
      setApplyLoading(false);
      setEraseLoading(false);
      alert(message.message || 'An error occurred.');
      break;
  }
});

function showState(state: 'loading' | 'warning' | 'workspace') {
  loadingEl.classList.add('hidden');
  warningEl.classList.add('hidden');
  workspaceEl.classList.add('hidden');
  eraserWorkspaceEl.classList.add('hidden');
  tabsEl.classList.add('hidden');

  if (state === 'loading') {
    loadingEl.classList.remove('hidden');
  } else if (state === 'warning') {
    warningEl.classList.remove('hidden');
    destroyCropper();
  } else if (state === 'workspace') {
    tabsEl.classList.remove('hidden');
    if (activeTab === 'crop') {
      workspaceEl.classList.remove('hidden');
    } else {
      eraserWorkspaceEl.classList.remove('hidden');
      initEraserCanvas();
    }
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
  originalImageData = null;
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

  // Setup onload handler to initialize
  imageEl.onload = () => {
    // Show workspace BEFORE initializing Cropper/Canvas so DOM has correct dimensions!
    showState('workspace');

    if (activeTab === 'erase') {
      initEraserCanvas();
    } else {
      // Initialize Cropper for Crop tab
      cropper = new Cropper(imageEl, {
        aspectRatio: NaN,
        viewMode: 1,
        autoCropArea: 0.85,
        background: false,
        responsive: true,
      });
    }
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

  // Get crop dimensions & data
  const cropData = cropper.getData(true);
  const imgData = cropper.getImageData();

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
          cropData: {
            x: cropData.x,
            y: cropData.y,
            width: cropData.width,
            height: cropData.height,
          },
          originalImageSize: {
            width: imgData.naturalWidth,
            height: imgData.naturalHeight,
          },
        },
        '*'
      );
    };
    reader.readAsArrayBuffer(blob);
  }, imageMimeType);
});

/* Tab Toggle Logic */
tabBtns.forEach((btn) => {
  btn.addEventListener('click', (e) => {
    const target = e.currentTarget as HTMLButtonElement;
    const tabName = target.getAttribute('data-tab')!;

    if (tabName === activeTab) return;

    activeTab = tabName;

    tabBtns.forEach((b) => b.classList.remove('active'));
    target.classList.add('active');

    // Switch workspaces
    if (activeTab === 'crop') {
      eraserWorkspaceEl.classList.add('hidden');
      workspaceEl.classList.remove('hidden');
      // Re-initialize cropper if destroyed
      if (!cropper && imageEl.src) {
        cropper = new Cropper(imageEl, {
          aspectRatio: parseFloat(aspectRatioSelect.value) || NaN,
          viewMode: 1,
          autoCropArea: 0.85,
          background: false,
          responsive: true,
        });
      }
    } else {
      // Destroy cropper before going to erase tab to free up canvas resources
      if (cropper) {
        cropper.destroy();
        cropper = null;
      }
      workspaceEl.classList.add('hidden');
      eraserWorkspaceEl.classList.remove('hidden');
      initEraserCanvas();
    }
  });
});

/* --- Color Eraser Logic --- */

function initEraserCanvas() {
  if (!imageEl.naturalWidth) return;

  eraserCanvas.width = imageEl.naturalWidth;
  eraserCanvas.height = imageEl.naturalHeight;

  const ctx = eraserCanvas.getContext('2d')!;
  ctx.drawImage(imageEl, 0, 0);

  // Store original image data for slider sensitivity operations
  originalImageData = ctx.getImageData(0, 0, eraserCanvas.width, eraserCanvas.height);
  applyColorEraser();
}

function applyColorEraser() {
  if (!originalImageData) return;

  const ctx = eraserCanvas.getContext('2d')!;
  const workingData = ctx.createImageData(originalImageData.width, originalImageData.height);

  // Copy original pixel data
  workingData.data.set(originalImageData.data);

  const data = workingData.data;
  const tR = targetColor.r;
  const tG = targetColor.g;
  const tB = targetColor.b;
  const tolSq = tolerance * tolerance;

  // Process pixel transparency loop
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];

    // Compute squared Euclidean color distance
    const distSq = (r - tR) ** 2 + (g - tG) ** 2 + (b - tB) ** 2;

    if (distSq <= tolSq) {
      data[i + 3] = 0; // Set pixel to fully transparent
    }
  }

  ctx.putImageData(workingData, 0, 0);
}

function setTargetColor(r: number, g: number, b: number) {
  targetColor = { r, g, b };
  sampledColorPreview.style.backgroundColor = `rgb(${r}, ${g}, ${b})`;
  colorPickerInput.value = rgbToHex(r, g, b);
  applyColorEraser();
}

// Click Canvas to sample a pixel color
eraserCanvas.addEventListener('click', (e) => {
  if (!originalImageData) return;

  const rect = eraserCanvas.getBoundingClientRect();
  const clickX = e.clientX - rect.left;
  const clickY = e.clientY - rect.top;

  // Scale coordinates to the canvas resolution
  const scaleX = eraserCanvas.width / rect.width;
  const scaleY = eraserCanvas.height / rect.height;

  const x = Math.floor(clickX * scaleX);
  const y = Math.floor(clickY * scaleY);

  // Bounds safety checks
  if (x < 0 || x >= eraserCanvas.width || y < 0 || y >= eraserCanvas.height) return;

  const index = (y * originalImageData.width + x) * 4;
  const r = originalImageData.data[index];
  const g = originalImageData.data[index + 1];
  const b = originalImageData.data[index + 2];

  setTargetColor(r, g, b);
});

// Preset Selectors
btnPresetWhite.addEventListener('click', () => setTargetColor(255, 255, 255));
btnPresetBlack.addEventListener('click', () => setTargetColor(0, 0, 0));

// Custom Picker Selector
colorPickerInput.addEventListener('input', () => {
  const rgb = hexToRgb(colorPickerInput.value);
  if (rgb) {
    setTargetColor(rgb.r, rgb.g, rgb.b);
  }
});

// Tolerance Slider
toleranceSlider.addEventListener('input', () => {
  tolerance = parseInt(toleranceSlider.value, 10);
  toleranceValSpan.textContent = tolerance.toString();
  applyColorEraser();
});

// Apply Erase button
btnApplyErase.addEventListener('click', () => {
  setEraseLoading(true);

  eraserCanvas.toBlob((blob) => {
    if (!blob) {
      setEraseLoading(false);
      alert('Could not export transparent image.');
      return;
    }

    const reader = new FileReader();
    reader.onloadend = () => {
      const bytes = new Uint8Array(reader.result as ArrayBuffer);
      parent.postMessage(
        {
          type: 'crop', // reuse crop message type in sandbox
          bytes: bytes,
          mtype: 'image/png', // Must save as PNG to preserve transparent alpha channel!
        },
        '*'
      );
    };
    reader.readAsArrayBuffer(blob);
  }, 'image/png');
});

/* Helper formatting utilities */
function rgbToHex(r: number, g: number, b: number): string {
  return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}

function hexToRgb(hex: string) {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result
    ? {
        r: parseInt(result[1], 16),
        g: parseInt(result[2], 16),
        b: parseInt(result[3], 16),
      }
    : null;
}

/* Loader settings */
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

function setEraseLoading(isLoading: boolean) {
  if (isLoading) {
    btnApplyErase.disabled = true;
    eraseSpinner.classList.remove('hidden');
    btnApplyErase.querySelector('.btn-text')?.classList.add('hidden');
  } else {
    btnApplyErase.disabled = false;
    eraseSpinner.classList.add('hidden');
    btnApplyErase.querySelector('.btn-text')?.classList.remove('hidden');
  }
}
