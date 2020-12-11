/*
 * Safari and Edge polyfill for createImageBitmap
 * https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/createImageBitmap
 *
 * Support source image types Blob and ImageData.
 *
 * From: https://dev.to/nektro/createimagebitmap-polyfill-for-safari-and-edge-228
 * Updated by Yoan Tournade <yoan@ytotech.com>
 */
if (!('createImageBitmap' in window)) {
	  window.createImageBitmap = async function (data) {
		    return new Promise((resolve,reject) => {
			      let dataURL;
			      if (data instanceof Blob) {
				        dataURL = URL.createObjectURL(data);
			      } else if (data instanceof ImageData) {
				        const canvas = document.createElement('canvas');
				        const ctx = canvas.getContext('2d');
				        canvas.width = data.width;
				        canvas.height = data.height;
				        ctx.putImageData(data,0,0);
				        dataURL = canvas.toDataURL();
			      } else {
				        throw new Error('createImageBitmap does not handle the provided image source type');
			      }
			      const img = document.createElement('img');
			      img.addEventListener('load',function () {
				        resolve(this);
			      });
			      img.src = dataURL;
		    });
	  };
}
