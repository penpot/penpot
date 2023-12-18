(ns app.main.ui.workspace.viewport.gl
  (:require-macros [app.main.style :as stl])
  (:require-macros [app.util.gl.macros :refer [slurp]])
  (:require
   [app.common.math :as math]
   [rumext.v2 :as mf]))

(def CANVAS_CONTEXT_ID "webgl2")

(def default-shader (slurp "src/app/util/gl/shaders/default.v.glsl"))

(defn resize-canvas-to
  [canvas width height]
  (let [resized (or (not= (.-width canvas) width)
                    (not= (.-height canvas) height))]
    (when (not= (.-width canvas) width)
      (set! (.-width canvas) width))
    (when (not= (.-height canvas) height)
      (set! (.-height canvas) height))
    resized))

(defn resize-canvas
  [canvas]
  (let [width  (math/floor (.-clientWidth canvas))
        height (math/floor (.-clientHeight canvas))]
    (resize-canvas-to canvas width height)))

(defn render-canvas
  [gl objects]
  (.clearColor gl 1.0 0.0 1.0 1.0)
  (.clear gl (.COLOR_BUFFER_BIT gl))

  (.viewport gl 0 0 (.-width gl) (.-height gl))

  (for [object objects]
    (.drawArrays gl (.TRIANGLES gl) 0 4)))

(mf/defc canvas
  "A canvas element with a WebGL context."
  {::mf/wrap-props false}
  [props]
  (js/console.log props)
  (js/console.log "default-shader" default-shader)
  (let [objects    (unchecked-get props "objects")
        canvas-ref (mf/use-ref nil)
        gl-ref     (mf/use-ref nil)]

    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
            (mf/set-ref-val! gl-ref gl)
            (resize-canvas canvas)
            (render-canvas gl objects)
            (js/console.log "gl" gl)))))

    [:canvas {:class (stl/css :canvas)
              :ref canvas-ref}]))

