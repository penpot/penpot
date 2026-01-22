;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api.webgl
  "WebGL utilities for pixel capture and rendering"
  (:require
   [app.common.logging :as log]
   [app.render-wasm.wasm :as wasm]
   [app.util.dom :as dom]))

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

(defn restore-previous-canvas-pixels
  "Restores previous canvas pixels into the new canvas"
  []
  (when-let [previous-canvas-pixels wasm/canvas-pixels]
    (when-let [gl wasm/gl-context]
      (draw-imagedata-to-webgl gl previous-canvas-pixels)
      (set! wasm/canvas-pixels nil))))

(defn clear-canvas-pixels
  []
  (when wasm/canvas
    (let [context wasm/gl-context]
      (.clearColor ^js context 0 0 0 0.0)
      (.clear ^js context (.-COLOR_BUFFER_BIT ^js context))
      (.clear ^js context (.-DEPTH_BUFFER_BIT ^js context))
      (.clear ^js context (.-STENCIL_BUFFER_BIT ^js context)))
    (dom/set-style! wasm/canvas "filter" "none")
    (let [controls-to-unblur (dom/query-all (dom/get-element "viewport-controls") ".blurrable")]
      (run! #(dom/set-style! % "filter" "none") controls-to-unblur))
    (set! wasm/canvas-pixels nil)))

(defn capture-canvas-pixels
  "Captures the pixels of the viewport canvas"
  []
  (when wasm/canvas
    (let [context wasm/gl-context
          width (.-width wasm/canvas)
          height (.-height wasm/canvas)
          buffer (js/Uint8ClampedArray. (* width height  4))
          _ (.readPixels ^js context 0 0 width height (.-RGBA ^js context) (.-UNSIGNED_BYTE ^js context) buffer)
          image-data (js/ImageData. buffer width height)]
      (set! wasm/canvas-pixels image-data))))
