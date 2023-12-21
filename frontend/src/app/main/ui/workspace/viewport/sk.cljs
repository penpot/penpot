(ns app.main.ui.workspace.viewport.sk
  (:require-macros [app.main.style :as stl])
  (:require
   ;; TODO el cp de node_modules/canvaskit-wasm/bin/canvaskit.wasm
   ["./sk_impl.js" :as impl]
   [rumext.v2 :as mf]))

(defn get-objects-as-iterable
  [objects]
  (.values js/Object (clj->js objects)))

(mf/defc canvas
  {::mf/wrap-props false}
  [props]
  (let [objects    (unchecked-get props "objects")
        vbox       (unchecked-get props "vbox")
        canvas-ref (mf/use-ref nil)
        canvas-kit (mf/use-state nil)]

    (mf/with-effect [vbox]
      (let [k @canvas-kit
            objects (get-objects-as-iterable objects)]
        (when (some? k)
          (.setVbox ^js k vbox)
          (.draw k objects))))

    (mf/with-effect [objects]
      (js/console.log "whatever")
      (let [k @canvas-kit
            objects (get-objects-as-iterable objects)]
        (when (some? k)
          (.draw k objects))))

    (mf/with-effect [canvas-ref vbox]
      (let [canvas (mf/ref-val canvas-ref)
            objects (get-objects-as-iterable objects)]
        (when (and (some? canvas) (some? vbox))
          (set! (.-width canvas) (.-clientWidth canvas))
          (set! (.-height canvas) (.-clientHeight canvas))
          (println "init vbox" vbox)
          (-> (.initialize impl/CanvasKit "skia-canvas" vbox)
              (.then (fn [k]
                       (reset! canvas-kit k)
                       (println "init complete")
                       (.draw k objects)))))))

    [:canvas {:id "skia-canvas"
              :class (stl/css :canvas)
              :ref canvas-ref}]))
