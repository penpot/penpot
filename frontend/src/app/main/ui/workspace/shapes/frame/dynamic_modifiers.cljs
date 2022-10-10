;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.dynamic-modifiers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph] ; TODO: move this to ctst
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.dom :as dom]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

(defn- transform-no-resize
  "If we apply a scale directly to the texts it will show deformed so we need to create this
  correction matrix to \"undo\" the resize but keep the other transformations."
  [{:keys [x y width height points transform transform-inverse] :as shape} current-transform modifiers]

  (let [corner-pt (first points)
        corner-pt (cond-> corner-pt (some? transform-inverse) (gpt/transform transform-inverse))

        resize-x? (some? (:resize-vector modifiers))
        resize-y? (some? (:resize-vector-2 modifiers))

        flip-x? (neg? (get-in modifiers [:resize-vector :x]))
        flip-y? (or (neg? (get-in modifiers [:resize-vector :y]))
                    (neg? (get-in modifiers [:resize-vector-2 :y])))

        result (cond-> (gmt/matrix)
                 (and (some? transform) (or resize-x? resize-y?))
                 (gmt/multiply transform)

                 resize-x?
                 (gmt/scale (gpt/inverse (:resize-vector modifiers)) corner-pt)

                 resize-y?
                 (gmt/scale (gpt/inverse (:resize-vector-2 modifiers)) corner-pt)

                 flip-x?
                 (gmt/scale (gpt/point -1 1) corner-pt)

                 flip-y?
                 (gmt/scale (gpt/point 1 -1) corner-pt)

                 (and (some? transform) (or resize-x? resize-y?))
                 (gmt/multiply transform-inverse))

        [width height]
        (if (or resize-x? resize-y?)
          (let [pc (cond-> (gpt/point x y)
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))

                pw (cond-> (gpt/point (+ x width) y)
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))

                ph (cond-> (gpt/point x (+ y height))
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))]
            [(gpt/distance pc pw) (gpt/distance pc ph)])
          [width height])]

    [result width height]))

(defn get-nodes
  "Retrieve the DOM nodes to apply the matrix transformation"
  [base-node {:keys [id type masked-group?] :as shape}]
  (when (some? base-node)
    (let [shape-node (if (= (.-id base-node) (dm/str "shape-" id))
                       base-node
                       (dom/query base-node (dm/str "#shape-" id)))

          frame? (= :frame type)
          group? (= :group type)
          text? (= :text type)
          mask?  (and group? masked-group?)]
      (cond
        frame?
        [shape-node
         (dom/query shape-node ".frame-children")
         (dom/query (dm/str "#thumbnail-container-" id))
         (dom/query (dm/str "#thumbnail-" id))
         (dom/query (dm/str "#frame-title-" id))]

        ;; For groups we don't want to transform the whole group but only
        ;; its filters/masks
        mask?
        [(dom/query shape-node ".mask-clip-path")
         (dom/query shape-node ".mask-shape")]

        group?
        (let [shape-defs (dom/query shape-node "defs")]
          (d/concat-vec
           (dom/query-all shape-defs ".svg-def")
           (dom/query-all shape-defs ".svg-mask-wrapper")))

        text?
        [shape-node
         (dom/query shape-node ".text-container")]

        :else
        [shape-node]))))

(defn transform-region!
  [node modifiers]

  (let [{:keys [x y width height]}
        (-> (gsh/make-selrect
             (-> (dom/get-attribute node "data-old-x") d/parse-double)
             (-> (dom/get-attribute node "data-old-y") d/parse-double)
             (-> (dom/get-attribute node "data-old-width") d/parse-double)
             (-> (dom/get-attribute node "data-old-height") d/parse-double))
            (gsh/transform-selrect modifiers))]
    (dom/set-attribute! node "x" x)
    (dom/set-attribute! node "y" y)
    (dom/set-attribute! node "width" width)
    (dom/set-attribute! node "height" height)))

(defn start-transform!
  [base-node shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (doseq [node nodes]
        (let [old-transform (dom/get-attribute node "transform")]
          (when (some? old-transform)
            (dom/set-attribute! node "data-old-transform" old-transform))

          (when (or (= (dom/get-tag-name node) "linearGradient")
                    (= (dom/get-tag-name node) "radialGradient"))
            (let [gradient-transform (dom/get-attribute node "gradientTransform")]
              (when (some? gradient-transform)
                (dom/set-attribute! node "data-old-gradientTransform" gradient-transform))))

          (when (= (dom/get-tag-name node) "pattern")
            (let [pattern-transform (dom/get-attribute node "patternTransform")]
              (when (some? pattern-transform)
                (dom/set-attribute! node "data-old-patternTransform" pattern-transform))))

          (when (or (= (dom/get-tag-name node) "mask")
                    (= (dom/get-tag-name node) "filter"))
            (let [old-x (dom/get-attribute node "x")
                  old-y (dom/get-attribute node "y")
                  old-width (dom/get-attribute node "width")
                  old-height (dom/get-attribute node "height")]
              (dom/set-attribute! node "data-old-x" old-x)
              (dom/set-attribute! node "data-old-y" old-y)
              (dom/set-attribute! node "data-old-width" old-width)
              (dom/set-attribute! node "data-old-height" old-height))))))))

(defn set-transform-att!
  [node att value]
  
  (let [old-att (dom/get-attribute node (dm/str "data-old-" att))
        new-value (if (some? old-att)
                    (dm/str value " " old-att)
                    (str value))]
    (dom/set-attribute! node att (str new-value))))

(defn override-transform-att!
  [node att value]
  (dom/set-attribute! node att (str value)))

(defn update-transform!
  [base-node shapes transforms modifiers]
  (doseq [{:keys [id type] :as shape} shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])
            text? (= type :text)
            transform-text? (and text? (and (nil? (:resize-vector modifiers)) (nil? (:resize-vector-2 modifiers))))]

        (doseq [node nodes]
          (cond
            ;; Text shapes need special treatment because their resize only change
            ;; the text area, not the change size/position
            (dom/class? node "frame-thumbnail")
            (let [[transform] (transform-no-resize shape transform modifiers)]
              (set-transform-att! node "transform" transform))

            (dom/class? node "frame-children")
            (set-transform-att! node "transform" (gmt/inverse transform))

            (dom/class? node "text-container")
            (let [modifiers (dissoc modifiers :displacement :rotation)]
              (when (not (gsh/empty-modifiers? modifiers))
                (let [mtx (-> shape
                              (assoc :modifiers modifiers)
                              (gsh/transform-shape)
                              (gsh/transform-matrix {:no-flip true}))]
                  (override-transform-att! node "transform" mtx))))

            (dom/class? node "frame-title")
            (let [shape (-> shape (assoc :modifiers modifiers) gsh/transform-shape)
                  zoom (get-in @st/state [:workspace-local :zoom] 1)
                  mtx  (vwu/title-transform shape zoom)]
              (override-transform-att! node "transform" mtx))

            (or (= (dom/get-tag-name node) "mask")
                (= (dom/get-tag-name node) "filter"))
            (transform-region! node modifiers)

            (or (= (dom/get-tag-name node) "linearGradient")
                (= (dom/get-tag-name node) "radialGradient"))
            (set-transform-att! node "gradientTransform" transform)

            (= (dom/get-tag-name node) "pattern")
            (set-transform-att! node "patternTransform" transform)

            (and (some? transform) (some? node) (or (not text?) transform-text?))
            (set-transform-att! node "transform" transform)))))))

(defn remove-transform!
  [base-node shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (doseq [node nodes]
        (when (some? node)
          (cond
            (= (dom/get-tag-name node) "foreignObject")
            ;; The shape width/height will be automatically setup when the modifiers are applied
            nil

            (or (= (dom/get-tag-name node) "mask")
                (= (dom/get-tag-name node) "filter"))
            (do
              (dom/remove-attribute! node "data-old-x")
              (dom/remove-attribute! node "data-old-y")
              (dom/remove-attribute! node "data-old-width")
              (dom/remove-attribute! node "data-old-height"))

            :else
            (let [old-transform (dom/get-attribute node "data-old-transform")]
              (if (some? old-transform)
                (dom/remove-attribute! node "data-old-transform")
                (dom/remove-attribute! node "transform")))))))))

(defn- get-copies
  "If one or more of the shapes belongs to a component's main instance, find all copies of
  the component in the same page.
 
  Return a map {<main-root-id> [<main-root> [<copy-root> <copy-root>...]] ...}"
  [shapes objects modifiers]
  (letfn [(get-copies-one [shape]
            (let [root-shape (ctn/get-root-shape objects shape)]
              (when (:main-instance? root-shape)
                (let [children (->> root-shape
                                    :shapes
                                    (map #(get objects %))
                                    (map #(gsh/apply-modifiers % (get-in modifiers [(:id %) :modifiers]))))
                      root-shape (gsh/update-group-selrect root-shape children)]
                  [(:id root-shape) [root-shape (ctn/get-instances objects root-shape)]]))))]

    (into {} (map get-copies-one shapes))))

(defn- reposition-shape
  [shape origin-root dest-root]
  (let [shape-pos (fn [shape]
                    (gpt/point (get-in shape [:selrect :x])
                               (get-in shape [:selrect :y])))

        origin-root-pos (shape-pos origin-root)
        dest-root-pos   (shape-pos dest-root)
        delta           (gpt/subtract dest-root-pos origin-root-pos)]
    (gsh/move shape delta)))

(defn- sync-shape
  [main-shape copy-shape copy-root main-root]
  ;; (js/console.log "+++")
  ;; (js/console.log "main-shape" (clj->js main-shape))
  ;; (js/console.log "copy-shape" (clj->js copy-shape))
  (if (ctk/touched-group? copy-shape :geometry-group)
    {}
    (let [main-shape  (reposition-shape main-shape main-root copy-root)

          translation (gpt/subtract (gsh/orig-pos main-shape)
                                    (gsh/orig-pos copy-shape))

          center      (gsh/orig-pos copy-shape)
          mult-w      (/ (gsh/width main-shape) (gsh/width copy-shape))
          mult-h      (/ (gsh/height main-shape) (gsh/height copy-shape))
          resize      (gpt/point mult-w mult-h)]

      (cond-> {}
        (not (gpt/almost-zero? translation))
        (assoc :displacement (gmt/translate-matrix translation))

        (not (gpt/close? resize (gpt/point 1 1)))
        (assoc :resize-vector resize
               :resize-origin center)))))

;; (defn- sync-shape
;;   [main-shape copy-shape copy-root main-root]
;;   ;; (js/console.log "+++")
;;   ;; (js/console.log "main-shape" (clj->js main-shape))
;;   ;; (js/console.log "copy-shape" (clj->js copy-shape))
;;   (if (ctk/touched-group? copy-shape :geometry-group)
;;     (gmt/matrix)
;;     (let [main-shape  (reposition-shape main-shape main-root copy-root)
;;
;;           translation (gpt/subtract (gsh/orig-pos main-shape)
;;                                     (gsh/orig-pos copy-shape))
;;
;;           center      (gsh/orig-pos copy-shape)
;;           mult-w      (/ (gsh/width main-shape) (gsh/width copy-shape))
;;           mult-h      (/ (gsh/height main-shape) (gsh/height copy-shape))
;;           resize      (gpt/point mult-w mult-h)]
;;
;;       (cond-> (gmt/matrix)
;;         (not (gpt/almost-zero? translation))
;;         (gmt/multiply (gmt/translate-matrix translation))
;;
;;         (not (gpt/almost-zero? resize))
;;         (gmt/multiply (gmt/scale-matrix resize center))))))

(defn- process-text-modifiers
  "For texts we only use the displacement because resize
  needs to recalculate the text layout"
  [shape modifiers]
  (cond-> modifiers
    (= :text (:type shape))
    (select-keys [:displacement :rotation])))

(defn- add-copies-modifiers
  "Add modifiers to all necessary shapes inside the copies"
  [copies objects modifiers]
  ;; (js/console.log "copies" (clj->js copies))
  (letfn [(add-copy-modifiers-one [modifiers copy-shape copy-root main-root main-shapes main-shapes-modif]
            ;; (assert (not (contains? modifiers (:id copy-shape))) "Si peta esto, we have a problem")
            (let [main-shape-modif (d/seek #(ctk/is-main-of? % copy-shape) main-shapes-modif)
                  ;; copy-shape       (cond-> copy-shape
                  ;;                    (some? (:transform-inverse copy-shape))
                  ;;                    (gsh/apply-transform (:transform-inverse copy-shape)))
                  modifier         (cond-> (sync-shape main-shape-modif copy-shape copy-root main-root)
                                     (some? (:rotation (get-in modifiers [(:id main-shape-modif) :modifiers])))
                                     (assoc :rotation (:rotation (get-in modifiers [(:id main-shape-modif) :modifiers])))
                                     )]
              (if (seq modifier)
                (assoc-in modifiers [(:id copy-shape) :modifiers] modifier)
                modifiers)))

          ;; $$$
          ;; (add-copy-modifiers-one [modifiers copy-shape copy-root main-root main-shapes main-shapes-modif]
          ;;   (update modifiers (:id copy-shape)
          ;;           (fn [modifier]
          ;;             (let [modifier (or modifier (gmt/matrix))
          ;;                   main-shape-modif (d/seek #(ctk/is-main-of? % copy-shape) main-shapes-modif)]
          ;;               (gmt/multiply modifier (sync-shape main-shape-modif copy-shape copy-root main-root))))))

          (add-copy-modifiers [modifiers copy-root main-root main-shapes main-shapes-modif]
            (let [copy-shapes (into [copy-root] (cph/get-children objects (:id copy-root)))]
              (reduce #(add-copy-modifiers-one %1 %2 copy-root main-root main-shapes main-shapes-modif)
                      modifiers
                      copy-shapes)))

          (add-copies-modifiers-one [modifiers [main-root copy-roots]]
            (let [main-shapes       (into [main-root] (cph/get-children objects (:id main-root)))
                  main-shapes-modif (map (fn [shape]
                                           (let [; shape (cond-> shape 
                                                 ;         (some? (:transform-inverse shape))
                                                 ;         (gsh/apply-transform (:transform-inverse shape)))
                                                 ]
                                                 (->> (get-in modifiers [(:id shape) :modifiers])
                                                      (process-text-modifiers shape)
                                                      (gsh/apply-modifiers shape))))
                                         main-shapes)]
              (reduce #(add-copy-modifiers %1 %2 main-root main-shapes main-shapes-modif)
                      modifiers
                      copy-roots)))]

    (reduce add-copies-modifiers-one
            modifiers
            (vals copies))))

;; $$$
;; (defn- add-copies-transforms
;;   "Add transform to all necessary shapes inside the copies"
;;   [copies objects modifiers transforms]
;;   ;; (js/console.log "copies" (clj->js copies))
;;   (letfn [(add-copy-transforms-one [transforms copy-shape copy-root main-root main-shapes main-shapes-modif]
;;             (update transforms (:id copy-shape)
;;                     (fn [transform]
;;                       (let [transform (or transform (gmt/matrix))
;;                             main-shape-modif (d/seek #(ctk/is-main-of? % copy-shape) main-shapes-modif)]
;;                         (gmt/multiply transform (sync-shape main-shape-modif copy-shape copy-root main-root))))))
;;
;;           (add-copy-transforms [transforms copy-root main-root main-shapes main-shapes-modif]
;;             (let [copy-shapes (into [copy-root] (cph/get-children objects (:id copy-root)))]
;;               (reduce #(add-copy-transforms-one %1 %2 copy-root main-root main-shapes main-shapes-modif)
;;                       transforms
;;                       copy-shapes)))
;;
;;           (add-copies-transforms-one [transforms [main-root copy-roots]]
;;             (let [main-shapes       (into [main-root] (cph/get-children objects (:id main-root)))
;;                   main-shapes-modif (map (fn [shape]
;;                                            (->> (get-in modifiers [(:id shape) :modifiers])
;;                                                 (process-text-modifiers shape)
;;                                                 (gsh/apply-modifiers shape)))
;;                                          main-shapes)]
;;               (reduce #(add-copy-transforms %1 %2 main-root main-shapes main-shapes-modif)
;;                       transforms
;;                       copy-roots)))]
;;
;;     (reduce add-copies-transforms-one
;;             transforms
;;             (vals copies))))

;; (defn get-copy-shapes
;;   "If one or more of the shapes belongs to a component's main instance, find all copies of
;;   the component in the same page. Ignore copies with the geometry values touched."
;;   [shapes objects]
;;   (letfn [(get-copy-shapes-one [shape]
;;             (let [root-shape (ctn/get-root-shape objects shape)]
;;               (when (:main-instance? root-shape)
;;                 (->> (ctn/get-instances objects shape)
;;                      (filter #(not (ctk/touched-group? % :geometry-group)))))))
;;
;;           (pack-main-copies [shape]
;;             (map #(vector shape %) (get-copy-shapes-one shape)))]
;;
;;     (mapcat pack-main-copies shapes)))

(defn use-dynamic-modifiers
  [objects node modifiers]

  (let [prev-shapes (mf/use-var nil)
        prev-modifiers (mf/use-var nil)
        prev-transforms (mf/use-var nil)
        ;; prev-copies (mf/use-var nil)

        ;; copies
        ;; (mf/use-memo   ; TODO: ojo estas deps hay que revisarlas
        ;;   (mf/deps modifiers (and (d/not-empty? @prev-modifiers) (d/not-empty? modifiers)))
        ;;   (fn []
        ;;     (let [shapes (->> (keys modifiers)
        ;;                       (mapv (d/getf objects)))]
        ;;       (get-copies shapes objects modifiers))))

        ;; modifiers
        ;; (mf/use-memo
        ;;   (mf/deps objects modifiers copies @prev-copies)
        ;;   (fn []
        ;;     (if (= (count copies) (count @prev-copies))
        ;;       modifiers
        ;;       (let [new-modifiers (add-copies-modifiers copies objects modifiers)]
        ;;         (js/console.log "==================")
        ;;         (js/console.log "modifiers (antes)" (clj->js modifiers))
        ;;         (js/console.log "copies" (clj->js copies))
        ;;         (js/console.log "modifiers (después)" (clj->js new-modifiers))
        ;;         (when (seq new-modifiers)
        ;;           (tm/schedule #(st/emit! (dwt/set-modifiers-raw new-modifiers))))
        ;;         new-modifiers))))

        transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           ;; (js/console.log "****modifiers" (clj->js modifiers))
           (when (seq modifiers)
             (d/mapm (fn [id {modifiers :modifiers}]
                       (let [shape (get objects id)
                             center (gsh/center-shape shape)
                             modifiers (cond-> modifiers
                                         ;; For texts we only use the displacement because
                                         ;; resize needs to recalculate the text layout
                                         (= :text (:type shape))
                                         (select-keys [:displacement :rotation]))]
                         (gsh/modifiers->transform center modifiers)))
                     modifiers))))

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           ;; (js/console.log "transforms" (clj->js transforms))
           (->> (keys transforms)
                (map (d/getf objects)))))

        ;; $$$
        ;; transforms
        ;; (mf/use-memo
        ;;   (mf/deps objects modifiers transforms copies)
        ;;   (fn []
        ;;     ;; (js/console.log "modifiers" (clj->js modifiers))
        ;;     (add-copies-transforms copies objects modifiers transforms)))

        ;; copy-shapes
        ;; (mf/use-memo
        ;;   (mf/deps (and (d/not-empty? @prev-modifiers) (d/not-empty? modifiers)))
        ;;   (fn []
        ;;     (get-copy-shapes shapes objects)))

        ;; transforms
        ;; (mf/use-memo
        ;;   (mf/deps objects modifiers transforms copy-shapes)
        ;;   (fn []
        ;;     (let [add-copy-transforms
        ;;           (fn [transforms main-shape copy-shape]
        ;;             (let [main-bounds (gsh/bounding-box main-shape)
        ;;                   copy-bounds (gsh/bounding-box copy-shape)
        ;;                   delta       (gpt/subtract (gpt/point (:x copy-bounds) (:y copy-bounds))
        ;;                                             (gpt/point (:x main-bounds) (:y main-bounds)))
        ;;
        ;;                   ;; Move the modifier origin points to the position of the copy.
        ;;                   main-modifiers (get-in modifiers [(:id main-shape) :modifiers])
        ;;                   copy-modifiers (let [origin   (:resize-origin main-modifiers)
        ;;                                        origin-2 (:resize-origin-2 main-modifiers)]
        ;;                                    (cond-> main-modifiers
        ;;                                      (some? origin)
        ;;                                      (assoc :resize-origin (gpt/add origin delta))
        ;;
        ;;                                      (some? origin-2)
        ;;                                      (assoc :resize-origin-2 (gpt/add origin-2 delta))))
        ;;
        ;;                   center (gsh/center-shape copy-shape)]
        ;;
        ;;               (update transforms (:id copy-shape)
        ;;                       #(let [transform (or % (gmt/matrix))]
        ;;                          (gmt/multiply transform
        ;;                                        (gsh/modifiers->transform center copy-modifiers))))))
        ;;
        ;;           apply-delta
        ;;           (fn [transforms shape-id delta]
        ;;             (let [shape-ids (-> (cph/get-children-ids objects shape-id)
        ;;                                 (conj shape-id))
        ;;
        ;;                   add-delta (fn [transform]
        ;;                               (let [transform (or transform (gmt/matrix))]
        ;;                                 (gmt/multiply transform (gmt/translate-matrix delta))))]
        ;;
        ;;               (reduce #(update %1 %2 add-delta)
        ;;                       transforms
        ;;                       shape-ids)))
        ;;
        ;;           manage-root
        ;;           (fn [transforms main-shape copy-shape]
        ;;             (let [main-modifiers (get-in modifiers [(:id main-shape) :modifiers])
        ;;                   modified-main  (gsh/apply-modifiers main-shape main-modifiers)
        ;;
        ;;                   delta          (gpt/subtract (gsh/orig-pos main-shape)
        ;;                                                (gsh/orig-pos modified-main))]
        ;;
        ;;               (cond-> transforms
        ;;                 (not (gpt/almost-zero? delta))
        ;;                 (apply-delta (:id copy-shape) delta))))
        ;;
        ;;           manage-nonroot
        ;;           (fn [transforms main-shape copy-shape]
        ;;             ; TODO: comparar el orig-pos de la main-shape modificada con el del su propio
        ;;             ;       root también modificado (antes de rotación). Si es menor que cero en alguno
        ;;             ;       de los dos ejes, añadir un desplazamiento al root y todos sus hijos
        ;;             (let [main-root            (ctn/get-root-shape objects main-shape)
        ;;                   main-root-modifiers  (get-in modifiers [(:id main-root) :modifiers])
        ;;                   modified-main-root   (gsh/apply-modifiers main-root main-root-modifiers)
        ;;
        ;;                   main-shape-modifiers (get-in modifiers [(:id main-shape) :modifiers])
        ;;                   modified-main-shape  (gsh/apply-modifiers main-shape main-shape-modifiers)
        ;;
        ;;                   delta     (gpt/subtract (gsh/orig-pos modified-main-shape)
        ;;                                           (gsh/orig-pos modified-main-root))
        ;;
        ;;                   delta-x (- (min 0 (:x delta)))
        ;;                   delta-y (- (min 0 (:y delta)))]
        ;;
        ;;               (if (or (pos? delta-x) (pos? delta-y))
        ;;                 (let [copy-root (ctn/get-root-shape objects copy-shape)]
        ;;                   (apply-delta transforms (:id copy-root) (gpt/point delta-x delta-y)))
        ;;                 transforms)))
        ;;
        ;;           add-all-transforms
        ;;           (fn [transforms [main-shape copy-shape]]
        ;;             ;; (js/console.log "----------------------")
        ;;             ;; (js/console.log "main-shape" (clj->js main-shape))
        ;;             ;; (js/console.log "copy-shape" (clj->js copy-shape))
        ;;             (as-> transforms $
        ;;               (add-copy-transforms $ main-shape copy-shape)
        ;;               (if (ctk/instance-root? main-shape)
        ;;                 (manage-root $ main-shape copy-shape)
        ;;                 (manage-nonroot $ main-shape copy-shape))))]
        ;;
        ;;       ;; (js/console.log "==================")
        ;;       (reduce add-all-transforms
        ;;               transforms
        ;;               copy-shapes))))

        ;; ---- old

            ;; (let [translate1
            ;;       (fn [shape modifiers]
            ;;         (let [root-shape     (ctn/get-root-shape objects shape)
            ;;               root-pos       (gsh/orig-pos root-shape)
            ;;
            ;;               modified-shape (gsh/apply-modifiers shape modifiers)
            ;;               modified-pos   (gsh/orig-pos modified-shape)]
            ;;           ;; (js/console.log "root-pos" (clj->js root-pos))
            ;;           ;; (js/console.log "modified-pos" (clj->js modified-pos))
            ;;           (if (or (< (:x modified-pos) (:x root-pos))
            ;;                   (< (:y modified-pos) (:y root-pos)))
            ;;             (let [displacement (get modifiers :displacement (gmt/matrix))
            ;;                   delta        (gpt/point (max 0 (- (:x root-pos) (:x modified-pos)))
            ;;                                           (max 0 (- (:y root-pos) (:y modified-pos))))]
            ;;               [(assoc modifiers :displacement
            ;;                       (gmt/add-translate displacement
            ;;                                          (gmt/translate-matrix delta)))
            ;;                delta])
            ;;             [modifiers (gpt/point 0 0)])))
            ;;
            ;;       get-copy-transform
            ;;       (fn [[main-shape copy-shape]]
            ;;         (js/console.log "----------------------")
            ;;         (js/console.log "main-shape" (clj->js main-shape))
            ;;         (js/console.log "copy-shape" (clj->js copy-shape))
            ;;         (let [[main-modifiers deltaa] (->> (get-in modifiers [(:id main-shape) :modifiers])
            ;;                                           (translate1 main-shape))
            ;;
            ;;               main-bounds    (gsh/bounding-box main-shape)
            ;;               copy-bounds    (gpt/add (gpt/point
            ;;                                         (:x (gsh/bounding-box copy-shape))
            ;;                                         (:y (gsh/bounding-box copy-shape)))
            ;;                                             deltaa)
            ;;               delta          (gpt/subtract (gpt/point (:x copy-bounds) (:y copy-bounds))
            ;;                                            (gpt/point (:x main-bounds) (:y main-bounds)))
            ;;
            ;;               root-shape     (ctn/get-root-shape objects main-shape)
            ;;               root-modifiers (get-in modifiers [(:id root-shape) :modifiers])
            ;;               _ (js/console.log "main-modifiers" (clj->js main-modifiers))
            ;;               _ (js/console.log "root-modifiers" (clj->js root-modifiers))
            ;;
            ;;               ;; root-shape     (ctn/get-root-shape objects copy-shape)
            ;;               ;; root-pos       (gsh/orig-pos root-shape)
            ;;               ;; copy-pos       (gsh/orig-pos copy-shape)
            ;;
            ;;               ;; Move the modifier origin points to the position of the copy.
            ;;               copy-modifiers (let [origin   (:resize-origin main-modifiers)
            ;;                                    origin-2 (:resize-origin-2 main-modifiers)]
            ;;                                (cond-> main-modifiers
            ;;                                  (some? origin)
            ;;                                  (assoc :resize-origin (gpt/add origin delta))
            ;;
            ;;                                  (some? origin-2)
            ;;                                  (assoc :resize-origin-2 (gpt/add origin-2 delta))
            ;;
            ;;                                  ;; (gpt/close? root-pos copy-pos)
            ;;                                  ;; (dissoc :displacement)
            ;;                                  ))
            ;;
            ;;               center (gsh/center-shape copy-shape)]
            ;;
            ;;           ;; (js/console.log "delta" (clj->js delta))
            ;;           ;; (js/console.log "main-modifiers" (clj->js main-modifiers))
            ;;           ;; (js/console.log "main-transform" (str (gsh/modifiers->transform
            ;;           ;;                                         (gsh/center-shape main-shape)
            ;;           ;;                                         main-modifiers)))
            ;;           ;; (js/console.log "copy-modifiers" (clj->js copy-modifiers))
            ;;           ;; (js/console.log "copy-transform" (str (gsh/modifiers->transform
            ;;           ;;                                         center copy-modifiers)))
            ;;           (gsh/modifiers->transform center copy-modifiers)))]
            ;;
            ;;   (reduce #(assoc %1 (:id (second %2)) (get-copy-transform %2))
            ;;           transforms
            ;;           copy-shapes))))
        ]

    (mf/use-layout-effect
     (mf/deps transforms)
     (fn []
       (let [is-prev-val? (d/not-empty? @prev-modifiers)
             is-cur-val? (d/not-empty? modifiers)]

         (when (and (not is-prev-val?) is-cur-val?)
           (start-transform! node shapes))

         (when is-cur-val?
           (update-transform! node shapes transforms modifiers))

         (when (and is-prev-val? (not is-cur-val?))
           (remove-transform! node @prev-shapes))

         (reset! prev-modifiers modifiers)
         (reset! prev-transforms transforms)
         (reset! prev-shapes shapes)
         ;; (reset! prev-copies copies)
         )))))
