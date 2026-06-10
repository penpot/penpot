;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.api.webgl
  "WebGL utilities for pixel capture and rendering"
  (:require
   [app.common.logging :as log]
   [app.render-wasm.wasm :as wasm]
   [promesa.core :as p]))

(defn get-webgl-context
  "Gets the WebGL context from the WASM module"
  []
  (when wasm/context-initialized?
    (let [gl-obj (unchecked-get wasm/internal-module "GL")]
      (when gl-obj
        ;; Get the current WebGL context from Emscripten
        ;; The GL object has a currentContext property that contains the context handle
        (let [current-ctx (.-currentContext ^js gl-obj)]
          (when current-ctx
            (.-GLctx ^js current-ctx)))))))

(defn create-webgl-texture-from-image
  "Creates a WebGL texture from an HTMLImageElement or ImageBitmap and returns the texture object"
  [gl image-element]
  (let [texture (.createTexture ^js gl)]
    (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) texture)
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_S ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_T ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MIN_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MAG_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texImage2D ^js gl (.-TEXTURE_2D ^js gl) 0 (.-RGBA ^js gl) (.-RGBA ^js gl) (.-UNSIGNED_BYTE ^js gl) image-element)
    (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) nil)
    texture))

;; FIXME: temporary function until we are able to keep the same <canvas> across pages.
(defn- draw-imagedata-to-webgl
  "Draws ImageData to a WebGL2 context by creating a texture"
  [gl image-data]
  (let [width (.-width ^js image-data)
        height (.-height ^js image-data)
        texture (.createTexture ^js gl)]
    ;; Bind texture and set parameters
    (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) texture)
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_S ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_WRAP_T ^js gl) (.-CLAMP_TO_EDGE ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MIN_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texParameteri ^js gl (.-TEXTURE_2D ^js gl) (.-TEXTURE_MAG_FILTER ^js gl) (.-LINEAR ^js gl))
    (.texImage2D ^js gl (.-TEXTURE_2D ^js gl) 0 (.-RGBA ^js gl) (.-RGBA ^js gl) (.-UNSIGNED_BYTE ^js gl) image-data)

    ;; Set up viewport
    (.viewport ^js gl 0 0 width height)

    ;; Vertex & Fragment shaders
    ;; Since we are only calling this function once (on page switch), we don't need
    ;; to cache the compiled shaders somewhere else (cannot be reused in a differen context).
    (let [vertex-shader-source "#version 300 es
in vec2 a_position;
in vec2 a_texCoord;
out vec2 v_texCoord;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
  v_texCoord = a_texCoord;
}"
          fragment-shader-source "#version 300 es
precision highp float;
in vec2 v_texCoord;
uniform sampler2D u_texture;
out vec4 fragColor;
void main() {
  fragColor = texture(u_texture, v_texCoord);
}"
          vertex-shader (.createShader ^js gl (.-VERTEX_SHADER ^js gl))
          fragment-shader (.createShader ^js gl (.-FRAGMENT_SHADER ^js gl))
          program (.createProgram ^js gl)]
      (.shaderSource ^js gl vertex-shader vertex-shader-source)
      (.compileShader ^js gl vertex-shader)
      (when-not (.getShaderParameter ^js gl vertex-shader (.-COMPILE_STATUS ^js gl))
        (log/error :hint "Vertex shader compilation failed"
                   :log (.getShaderInfoLog ^js gl vertex-shader)))

      (.shaderSource ^js gl fragment-shader fragment-shader-source)
      (.compileShader ^js gl fragment-shader)
      (when-not (.getShaderParameter ^js gl fragment-shader (.-COMPILE_STATUS ^js gl))
        (log/error :hint "Fragment shader compilation failed"
                   :log (.getShaderInfoLog ^js gl fragment-shader)))

      (.attachShader ^js gl program vertex-shader)
      (.attachShader ^js gl program fragment-shader)
      (.linkProgram ^js gl program)

      (if (.getProgramParameter ^js gl program (.-LINK_STATUS ^js gl))
        (do
          (.useProgram ^js gl program)

          ;; Create full-screen quad vertices (normalized device coordinates)
          (let [position-location (.getAttribLocation ^js gl program "a_position")
                texcoord-location (.getAttribLocation ^js gl program "a_texCoord")
                position-buffer (.createBuffer ^js gl)
                texcoord-buffer (.createBuffer ^js gl)
                positions #js [-1.0 -1.0  1.0 -1.0  -1.0 1.0  -1.0 1.0  1.0 -1.0  1.0 1.0]
                texcoords #js [0.0 0.0  1.0 0.0  0.0 1.0  0.0 1.0  1.0 0.0  1.0 1.0]]
            ;; Set up position buffer
            (.bindBuffer ^js gl (.-ARRAY_BUFFER ^js gl) position-buffer)
            (.bufferData ^js gl (.-ARRAY_BUFFER ^js gl) (js/Float32Array. positions) (.-STATIC_DRAW ^js gl))
            (.enableVertexAttribArray ^js gl position-location)
            (.vertexAttribPointer ^js gl position-location 2 (.-FLOAT ^js gl) false 0 0)
            ;; Set up texcoord buffer
            (.bindBuffer ^js gl (.-ARRAY_BUFFER ^js gl) texcoord-buffer)
            (.bufferData ^js gl (.-ARRAY_BUFFER ^js gl) (js/Float32Array. texcoords) (.-STATIC_DRAW ^js gl))
            (.enableVertexAttribArray ^js gl texcoord-location)
            (.vertexAttribPointer ^js gl texcoord-location 2 (.-FLOAT ^js gl) false 0 0)
            ;; Set texture uniform
            (.activeTexture ^js gl (.-TEXTURE0 ^js gl))
            (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) texture)
            (let [texture-location (.getUniformLocation ^js gl program "u_texture")]
              (.uniform1i ^js gl texture-location 0))

            ;; draw
            (.drawArrays ^js gl (.-TRIANGLES ^js gl) 0 6)

            ;; cleanup
            (.deleteBuffer ^js gl position-buffer)
            (.deleteBuffer ^js gl texcoord-buffer)
            (.deleteShader ^js gl vertex-shader)
            (.deleteShader ^js gl fragment-shader)
            (.deleteProgram ^js gl program)))
        (log/error :hint "Program linking failed"
                   :log (.getProgramInfoLog ^js gl program)))

      (.bindTexture ^js gl (.-TEXTURE_2D ^js gl) nil)
      (.deleteTexture ^js gl texture))))

;; Codec for the transition snapshot. The snapshot is only ever shown as a
;; heavily-blurred overlay (see TRANSITION_BLUR_RADIUS), so a lossy, alpha-capable
;; codec is fine and encodes much faster than lossless PNG -- on a full-viewport
;; canvas, PNG `toBlob` of millions of pixels costs ~1s+ on the main thread.
;; WebP keeps the alpha channel (unlike JPEG) and encodes in a fraction of that.
(def ^:private SNAPSHOT_MIME "image/webp")
(def ^:private SNAPSHOT_QUALITY 0.6)

;; The snapshot is only shown heavily blurred, so it is downscaled to this max
;; side before encoding to keep the WebP encode cheap on big/high-DPI canvases.
(def ^:private SNAPSHOT_MAX_DIM 1024)

(defonce ^:private snapshot-scratch-canvas
  (delay (js/document.createElement "canvas")))

(defn capture-canvas-snapshot-url
  "Captures the current viewport canvas as a downscaled WebP `blob:` URL and
  stores it in `wasm/canvas-snapshot-url`. Returns a promise of the URL or nil."
  []
  (if-let [^js canvas wasm/canvas]
    (p/create
     (fn [resolve _reject]
       ;; Revoke previous snapshot to avoid leaking blob URLs.
       (when-let [prev wasm/canvas-snapshot-url]
         (when (and (string? prev) (.startsWith ^js prev "blob:"))
           (js/URL.revokeObjectURL prev)))
       (set! wasm/canvas-snapshot-url nil)
       (let [cw      (.-width canvas)
             ch      (.-height canvas)
             ;; Cap the longest side to SNAPSHOT_MAX_DIM (never upscale).
             scale   (min 1.0 (/ SNAPSHOT_MAX_DIM (max 1 cw ch)))
             tw      (max 1 (js/Math.round (* cw scale)))
             th      (max 1 (js/Math.round (* ch scale)))
             ^js sc  @snapshot-scratch-canvas
             ^js ctx (.getContext sc "2d")]
         (set! (.-width sc) tw)
         (set! (.-height sc) th)
         (.drawImage ctx canvas 0 0 tw th)
         (.toBlob sc
                  (fn [^js blob]
                    (if blob
                      (let [url (js/URL.createObjectURL blob)]
                        (set! wasm/canvas-snapshot-url url)
                        (resolve url))
                      (resolve nil)))
                  SNAPSHOT_MIME
                  SNAPSHOT_QUALITY))))
    (p/resolved nil)))

(defn draw-thumbnail-to-canvas
  "Loads an image from `uri` and draws it stretched to fill the WebGL canvas.
   Returns a promise that resolves to true if drawn, false otherwise."
  [uri]
  (if (and uri wasm/canvas wasm/gl-context)
    (p/create
     (fn [resolve _reject]
       (let [img (js/Image.)]
         (set! (.-crossOrigin img) "anonymous")
         (set! (.-onload img)
               (fn []
                 (if wasm/gl-context
                   (let [gl     wasm/gl-context
                         width  (.-width wasm/canvas)
                         height (.-height wasm/canvas)
                         ;; Draw image to an offscreen 2D canvas, scaled to fill
                         canvas2d (js/OffscreenCanvas. width height)
                         ctx2d   (.getContext canvas2d "2d")]
                     (.drawImage ctx2d img 0 0 width height)
                     (let [image-data (.getImageData ctx2d 0 0 width height)]
                       (draw-imagedata-to-webgl gl image-data))
                     (resolve true))
                   (resolve false))))
         (set! (.-onerror img)
               (fn [_]
                 (resolve false)))
         (set! (.-src img) uri))))
    (p/resolved false)))
