(ns app.main.ui.workspace.viewport.gl
  (:require-macros [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(def CANVAS_CONTEXT_ID "webgl2")

(mf/defc canvas
  "A canvas element with a WebGL context."
  {::mf/wrap-props false}
  [props]
  (js/console.log props)
  (let [canvas-ref (mf/use-ref nil)
        gl-ref     (mf/use-ref nil)]
    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (let [gl (.getContext canvas CANVAS_CONTEXT_ID)]
            (.clearColor gl 1.0 0.0 1.0 0.5)
            (.clear gl (.-COLOR_BUFFER_BIT gl))
            (mf/set-ref-val! gl-ref gl)
            (js/console.log "gl" gl)))))

    [:canvas {:class (stl/css :canvas)
              :ref canvas-ref}]))

