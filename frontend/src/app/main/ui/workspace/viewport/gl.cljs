(ns app.main.ui.workspace.viewport.gl
   (:require-macros [app.main.style :as stl])
   (:require-macros [app.util.gl.macros :refer [slurp]])
   (:require
    ["gl-matrix" :as glm]
    [app.common.math :as math]
    [app.util.gl :as gl]
    [cuerdas.core :as str]
    [rumext.v2 :as mf]))

(def CANVAS_CONTEXT_ID "webgl2")

(def default-vertex-shader (slurp "src/app/util/gl/shaders/default.v.glsl"))
(def default-fragment-shader (slurp "src/app/util/gl/shaders/default.f.glsl"))

#_(def shaders (js/Map.))
(def programs (js/Map.))
#_(def textures (js/Map.))
#_(def framebuffers (js/Map.))

(defn parse-color-hex
  "Parses a color string and returns a vector with the RGBA values."
  [color opacity]
  (let [r (str/slice color 1 3)
        g (str/slice color 3 5)
        b (str/slice color 5 7)]
    #js [(/ (js/parseInt r 16) 255.0)
         (/ (js/parseInt g 16) 255.0)
         (/ (js/parseInt b 16) 255.0)
         opacity]))

(defn parse-color
  "Parses a color string and returns a vector with the RGBA values."
  [color opacity]
  (cond
    (str/starts-with? color "#")
    (parse-color-hex color opacity)

    :else
    #js [0.0 0.0 0.0 1.0]))

(defn get-object-type-as-int
  "Returns the object type as an integer."
  [object]
  (case (:type object)
    :rect    0
    :circle  1
    :group   2
    :path    3
    :text    4
    :image   5
    :svg-raw 6
    :bool    7
    :frame   8))

(defn resize-canvas-to
  "Resize canvas to specific coordinates."
  [canvas width height]
  (let [resized-width (not= (.-width canvas) width)
        resized-height (not= (.-height canvas) height)
        resized (or resized-width resized-height)]
    (when resized-width
      (set! (.-width canvas) width))
    (when resized-height
      (set! (.-height canvas) height))
    resized))

(defn resize-canvas
  "Resizes the canvas intrinsic size to the element size."
  [canvas]
  (let [width  (math/floor (.-clientWidth canvas))
        height (math/floor (.-clientHeight canvas))]
    (resize-canvas-to canvas width height)))

(defn prepare-gl
  "Prepares the WebGL context for rendering."
  [gl]
  (let [default-program (gl/create-program-from-sources gl default-vertex-shader default-fragment-shader)]
    (.set programs "default" default-program)))

(defn render-gl
  "Renders the whole document to the canvas."
  [gl objects vbox]
  (let [projection (.create glm/mat3)
        projection (.projection glm/mat3 projection (:width vbox) (:height vbox))]

   (.clearColor gl 1.0 0.0 1.0 0.5)
    (.clear gl (.-COLOR_BUFFER_BIT gl))

    (.viewport gl 0 0 (.-width (.-canvas gl)) (.-height (.-canvas gl)))

  ;; Enable alpha blending
    (.enable gl (.-BLEND gl))
    (.blendFunc gl (.-SRC_ALPHA gl) (.-ONE_MINUS_SRC_ALPHA gl))

    (.useProgram gl (.get programs "default"))
    (println "---------------> vbox" (:x vbox) (:width vbox) (:y vbox) (:height vbox))
    (.uniformMatrix3fv gl (.getUniformLocation gl (.get programs "default") "u_projection") false projection)
    (.uniform4f gl (.getUniformLocation gl (.get programs "default") "u_vbox") (:x vbox) (:y vbox) (:width vbox) (:height vbox))

    (doseq [[_ object] objects]
      (let [selrect (:selrect object)
            x (:x selrect)
            y (:y selrect)
            width (:width selrect)
            height (:height selrect)
            rotation (:rotation object)
          ;; Tengo que encontrar la forma de "reordenar la matriz" para que funcione la
          ;; rotación.
          ;; transform (:transform object)
          ;; {a :a b :b c :c d :d e :e f :f} transform
          ;; matrix #_(js/Float32Array. #js [a c 0 b d 0 0 0 1])
            matrix (js/Float32Array. #js [1 0 0 0 1 0 0 0 1])
            fill (first (:fills object))]
        (js/console.log "fill" fill)
        (js/console.log "matrix" matrix)
        (.uniform1i gl (.getUniformLocation gl (.get programs "default") "u_type") (get-object-type-as-int object))
        (.uniform2f gl (.getUniformLocation gl (.get programs "default") "u_size") width height)
        (.uniform2f gl (.getUniformLocation gl (.get programs "default") "u_position") x y)
        (.uniform1f gl (.getUniformLocation gl (.get programs "default") "u_rotation") (/ (* rotation js/Math.PI) 180.0))
        #_(.uniformMatrix3fv gl (.getUniformLocation gl (.get programs "default") "u_transform") false matrix)
        ;; NOTA: Esto es sólo aplicable en objetos que poseen fills (los textos no
        ;; poseen fills).
        (doseq [fill (reverse (:fills object))]
          (do
            (.uniform4fv  gl (.getUniformLocation  gl (.get  programs  "default") "u_color") (parse-color (:fill-color fill) (:fill-opacity fill)))
            (.drawArrays  gl (.-TRIANGLE_STRIP  gl) 0  4)))))))

(mf/defc canvas
  "A canvas element with a WebGL context."
  {::mf/wrap-props false}
  [props]
  (let [objects    (unchecked-get props "objects")
        vbox       (unchecked-get props "vbox")
        canvas-ref (mf/use-ref nil)
        gl-ref     (mf/use-ref nil)

        on-context-lost
        (mf/use-fn (fn []
                     (mf/set-ref-val! gl-ref nil)))

        on-context-restore
        (mf/use-fn (fn []
                     (let [canvas (mf/ref-val canvas-ref)]
                       (when (some? canvas)
                         (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
                           (mf/set-ref-val! gl-ref gl)
                           (resize-canvas canvas)
                           (prepare-gl gl)
                           (render-gl gl objects vbox))))))]

    (mf/with-effect [objects vbox]
      (let [gl (mf/ref-val gl-ref)]
        (when (some? gl)
          (render-gl gl objects vbox))))

    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (.addEventListener canvas "webglcontextlost" on-context-lost)
          (.addEventListener canvas "webglcontextrestore" on-context-restore)
          (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
            (mf/set-ref-val! gl-ref gl)
            (resize-canvas canvas)
            (prepare-gl gl)
            (render-gl gl objects vbox))))

      ;; unmount
      (fn []
        (let [canvas (mf/ref-val canvas-ref)]
          (when (some? canvas)
            (.removeEventListener canvas "webglcontextlost" on-context-lost)
            (.removeEventListener canvas "webglcontextrestore" on-context-restore)))))

    [:canvas {:class (stl/css :canvas)
              :ref canvas-ref}]))

;; TODO
;; - blend modes
;; - strokes
