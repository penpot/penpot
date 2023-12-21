(ns app.main.ui.workspace.viewport.sk
  (:require-macros [app.main.style :as stl])
  (:require
   ;; TODO el cp de node_modules/canvaskit-wasm/bin/canvaskit.wasm
   ["./sk_impl.js" :as impl]
   [rumext.v2 :as mf]))

(mf/defc canvas
  {::mf/wrap-props false}
  [props]
  (let [objects    (unchecked-get props "objects")
        vbox       (unchecked-get props "vbox")
        canvas-ref (mf/use-ref nil)
        canvas-kit (mf/use-state nil)]
    
    (mf/with-effect [vbox]
      (when @canvas-kit
        (.setVbox ^js @canvas-kit vbox)))
    
    (mf/with-effect [objects]
      (when @canvas-kit
        (doseq [[_ object] objects]
          (.paintRect ^js @canvas-kit (clj->js object)))))

    (mf/with-effect [canvas-ref vbox]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (and (some? canvas) (some? vbox))
          (set! (.-width canvas) (.-clientWidth canvas))
          (set! (.-height canvas) (.-clientHeight canvas))
          (println "init vbox" vbox)
          (-> (.initialize impl/CanvasKit "skia-canvas" vbox)
              (.then (fn [k]
                       (reset! canvas-kit k)
                       (println "init complete")
                       (doseq [[_ object] objects]
                         (.paintRect ^js k (clj->js object)))
                       #_(.clear ^js k)))))))

    [:canvas {:id "skia-canvas"
              :class (stl/css :canvas)
              :ref canvas-ref}]))