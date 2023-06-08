(ns bench
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes.transforms :as gst]
   [app.common.record :as cr]
   [app.common.geom.rect :as grc]
   [app.common.geom.rect_impl :as grci]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.util.perf :as perf]))

(defn random
  []
  (js/Math.random))

(defn force-gc
  []
  (js/gc))

;; (defn bench-modifiers
;;   []
;;   (println "")
;;   (println "===> BENCH MODIFIERS <===")
;;   (let [modifiers (-> (ctm/empty)
;;                       (ctm/move (gpt/point 100 200))
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
;;                       (ctm/move (gpt/point -100 -200))
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
;;                       (ctm/rotation (gpt/point 0 0) -100)
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5)))]
;;     (perf/benchmark
;;      :gc force-gc
;;      :iterations 50000
;;      :name "modifiers->transform:old"
;;      :run-fn #(ctm/modifiers->transform-old modifiers))

;;     (perf/benchmark
;;      :gc force-gc
;;      :iterations 50000
;;      :name "modifiers->transform:new"
;;      :run-fn #(ctm/modifiers->transform modifiers))))


;; (defn bench-apply-transform
;;   []
;;   (println "")
;;   (println "===> BENCH APPLY TRANFORM <===")
;;   (let [modifiers (-> (ctm/empty)
;;                       (ctm/move (gpt/point 100 200))
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
;;                       (ctm/move (gpt/point -100 -200))
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
;;                       (ctm/rotation (gpt/point 0 0) -100)
;;                       (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5)))
;;         transform (ctm/modifiers->transform modifiers)

;;         shape     (cts/setup-shape {:type :rect
;;                                     :x 0
;;                                     :y 0
;;                                     :width 10
;;                                     :height 10})]

;;     ;; (app.common.pprint/pprint shape)

;;     (perf/benchmark
;;      :gc force-gc
;;      :iterations 400
;;      :name "apply-transform:old"
;;      :run-fn #(gst/apply-transform shape modifiers))

;;     (perf/benchmark
;;      :gc force-gc
;;      :iterations 400
;;      :name "apply-transform:new"
;;      :run-fn #(gst/apply-transform' shape modifiers))
;;     ))


(defn ^:dev/after-load after-load
  []
  ;; (bench-apply-transform)
  ;; (let [o (grc/make-rect 1 1 10 10)]
  ;;   (prn o)
  ;;   (prn (-> o
  ;;            (cr/assoc! :x 40)
  ;;            (grc/update-rect! :size)))
  ;;   )
  )

(defn main
  [& params]
  ;; (bench-apply-transform)
  ;; (bench-apply-transform)
  nil)
