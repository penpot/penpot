(ns app.main.ui.workspace.viewport.sk
  (:require-macros [app.main.style :as stl])
  (:require
   ;; TODO el cp de node_modules/canvaskit-wasm/bin/canvaskit.wasm
   ["./sk_impl.js" :as impl]
   [app.main.store :as st]
   [app.util.path.format :as upf]
   [rumext.v2 :as mf]))

(mf/defc canvas
  {::mf/wrap-props false}
  [props]
  (let [objects    (unchecked-get props "objects")
        vbox       (unchecked-get props "vbox")
        canvas-ref (mf/use-ref nil)
        kit        (mf/use-state nil)
        zoom       (get-in @st/state [:workspace-local :zoom] 1)]
    (println "zoom " zoom)

    (mf/with-effect [objects vbox]
      (when @kit
        (let [canvas (mf/ref-val canvas-ref)]
          (do
            (impl/clear @kit "skia-canvas")
            (doseq [[_ object] objects]
              (let [selrect    (:selrect object)
                    x (:x selrect)
                    y (:y selrect)
                    width (+ (:width selrect) x)
                    height (+ (:height selrect) y)]
                (impl/rect @kit "skia-canvas" x y width height (:x vbox) (:y vbox) zoom)
                (when (:content object)
                  (impl/path @kit "skia-canvas" x y (upf/format-path (:content object)) (:x vbox) (:y vbox) zoom))))))))

    (mf/with-effect [canvas-ref]
      (let [canvas (mf/ref-val canvas-ref)]
        (when (some? canvas)
          (set! (.-width canvas) (.-clientWidth canvas))
          (set! (.-height canvas) (.-clientHeight canvas))
          (-> (impl/init "skia-canvas")
              (.then (fn [canvas-kit]
                       (js/console.log "canvas-kit" canvas-kit)
                       (reset! kit canvas-kit)))))))

    [:canvas {:id "skia-canvas"
              :class (stl/css :canvas)
              :ref canvas-ref}]))