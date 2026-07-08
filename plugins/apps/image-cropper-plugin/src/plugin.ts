import type { Shape, Fill, ImageData } from '@penpot/plugin-types';

// Open the UI iframe
penpot.ui.open('Image Cropper', `?theme=${penpot.theme}`, {
  width: 380,
  height: 480,
});

// Watch for theme changes
penpot.on('themechange', (theme) => {
  sendMessage({ type: 'theme', content: theme });
});

// Helper to find a selected shape with an image fill
interface SelectedImageInfo {
  shape: Shape;
  fillIndex: number;
  fillImage: ImageData;
}

function getSelectedImage(): SelectedImageInfo | null {
  for (const shape of penpot.selection) {
    if (shape.fills && Array.isArray(shape.fills)) {
      for (let i = 0; i < shape.fills.length; i++) {
        const fill = shape.fills[i] as Fill;
        if (fill.fillImage) {
          return {
            shape,
            fillIndex: i,
            fillImage: fill.fillImage,
          };
        }
      }
    }
  }
  return null;
}

// Track currently active shape to prevent redundant loading
let currentShapeId: string | null = null;

async function sendSelectedImage() {
  const selectionInfo = getSelectedImage();

  if (!selectionInfo) {
    currentShapeId = null;
    sendMessage({ type: 'no-selection' });
    return;
  }

  const { shape, fillImage } = selectionInfo;

  // If the same shape is already loaded, do not re-send
  if (shape.id === currentShapeId) {
    return;
  }

  currentShapeId = shape.id;
  sendMessage({ type: 'loading' });

  try {
    const data = await fillImage.data();
    sendMessage({
      type: 'image-data',
      name: fillImage.name || 'image',
      width: fillImage.width,
      height: fillImage.height,
      mtype: fillImage.mtype || 'image/png',
      bytes: data,
    });
  } catch (err: any) {
    currentShapeId = null;
    sendMessage({
      type: 'error',
      message: `Failed to load image: ${err.message || err}`,
    });
  }
}

// Listen to selection changes
penpot.on('selectionchange', () => {
  sendSelectedImage();
});

// Listen to messages from the UI
penpot.ui.onMessage<any>(async (message) => {
  if (message.type === 'ready') {
    await sendSelectedImage();
  } else if (message.type === 'crop') {
    const selectionInfo = getSelectedImage();
    if (!selectionInfo) {
      sendMessage({
        type: 'error',
        message: 'No selected shape with an image fill found.',
      });
      return;
    }

    try {
      const { shape, fillIndex } = selectionInfo;

      // Upload the cropped image bytes to Penpot
      const newImageData = await penpot.uploadMediaData(
        'cropped-image',
        message.bytes,
        message.mtype || 'image/png'
      );

      // Adjust geometry to match the crop in place if crop coordinates are provided
      if (message.cropData && message.originalImageSize) {
        // Temporarily disable aspect ratio lock (proportionLock) to allow resizing to the cropped aspect ratio
        const wasLocked = shape.proportionLock;
        shape.proportionLock = false;

        // Use a uniform scale factor to preserve aspect ratio and prevent distortion
        const scale = shape.width / message.originalImageSize.width;
        console.log('[Plugin Sandbox] Calculated Scale:', scale);

        const newX = shape.x + message.cropData.x * scale;
        const newY = shape.y + message.cropData.y * scale;
        const newW = message.cropData.width * scale;
        const newH = message.cropData.height * scale;

        console.log('[Plugin Sandbox] Target Shape:', { x: newX, y: newY, w: newW, h: newH });

        shape.x = newX;
        shape.y = newY;

        // Resize the shape using the uniform scale factor
        shape.resize(newW, newH);

        // Restore the original lock state (it will lock to the new correct aspect ratio)
        shape.proportionLock = wasLocked;
      }

      // Create new fills list and replace the cropped fill
      const updatedFills = [...(shape.fills as Fill[])];
      updatedFills[fillIndex] = {
        ...updatedFills[fillIndex],
        fillImage: newImageData,
      };

      shape.fills = updatedFills;

      // Notify UI of success
      sendMessage({ type: 'crop-success' });
    } catch (err: any) {
      sendMessage({
        type: 'error',
        message: `Failed to apply crop: ${err.message || err}`,
      });
    }
  }
});

function sendMessage(message: any) {
  penpot.ui.sendMessage(message);
}
