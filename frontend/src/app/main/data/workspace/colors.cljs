;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.colors
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.main.data.modal :as md]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.repo :as rp]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :color-for-rename] nil))))

(declare rename-color-result)

(defn rename-color
  [file-id color-id name]
  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation! :rename-color {:id color-id :name name})
           (rx/map (partial rename-color-result file-id))))))

(defn rename-color-result
  [_file-id color]
  (ptk/reify ::rename-color-result
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-file :colors] #(d/replace-by-id % color)))))

(defn change-palette-selected
  "Change the library used by the general palette tool"
  [selected]
  (ptk/reify ::change-palette-selected
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :selected-palette] selected)))))

(defn change-palette-selected-colorpicker
  "Change the library used by the color picker"
  [selected]
  (ptk/reify ::change-palette-selected-colorpicker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :selected-palette-colorpicker] selected)))))

(defn show-palette
  "Show the palette tool and change the library it uses"
  [selected]
  (ptk/reify ::show-palette
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-layout conj :colorpalette)
          (assoc-in [:workspace-global :selected-palette] selected)))))

(defn start-picker
  []
  (ptk/reify ::start-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picking-color?] true)))))

(defn stop-picker
  []
  (ptk/reify ::stop-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-global dissoc :picked-color-select :picked-shift?)
          (assoc-in [:workspace-global :picking-color?] false)))))

(defn pick-color
  [rgba]
  (ptk/reify ::pick-color
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picked-color] rgba)))))

(defn pick-color-select
  [value shift?]
  (ptk/reify ::pick-color-select
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :picked-color-select] value)
          (assoc-in [:workspace-global :picked-shift?] shift?)))))

(defn transform-fill
  [state ids color transform]
  (let [objects   (wsh/lookup-page-objects state)

        is-text?  #(= :text (:type (get objects %)))
        text-ids  (filter is-text? ids)
        shape-ids (remove is-text? ids)

        attrs
        (cond-> {}
          (contains? color :color)
          (assoc :fill-color (:color color))

          (contains? color :id)
          (assoc :fill-color-ref-id (:id color))

          (contains? color :file-id)
          (assoc :fill-color-ref-file (:file-id color))

          (contains? color :gradient)
          (assoc :fill-color-gradient (:gradient color))

          (contains? color :opacity)
          (assoc :fill-opacity (:opacity color))

          :always
          (d/without-nils))

        transform-attrs #(transform % attrs)]

    (rx/concat
     (rx/from (map #(dwt/update-text-with-function % transform-attrs) text-ids))
     (rx/of (dch/update-shapes shape-ids transform-attrs)))))

(defn swap-attrs [shape attr index new-index]
  (let [first (get-in shape [attr index])
        second (get-in shape [attr new-index])]
    (-> shape
        (assoc-in [attr index] second)
        (assoc-in [attr new-index] first))))

(defn reorder-fills
  [ids index new-index]
  (ptk/reify ::reorder-fills
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects   (wsh/lookup-page-objects state)

            is-text?  #(= :text (:type (get objects %)))
            text-ids  (filter is-text? ids)
            shape-ids (remove is-text? ids)
            transform-attrs #(swap-attrs % :fills index new-index)]

        (rx/concat
         (rx/from (map #(dwt/update-text-with-function % transform-attrs) text-ids))
         (rx/of (dch/update-shapes shape-ids transform-attrs)))))))

(defn change-fill
  [ids color position]
  (ptk/reify ::change-fill
    ptk/WatchEvent
    (watch [_ state _]
      (let [change (fn [shape attrs]
                     (-> shape
                         (cond-> (not (contains? shape :fills))
                           (assoc :fills []))
                         (assoc-in [:fills position] (into {} attrs))))]
        (transform-fill state ids color change)))))

(defn change-fill-and-clear
  [ids color]
  (ptk/reify ::change-fill-and-clear
    ptk/WatchEvent
    (watch [_ state _]
      (let [set (fn [shape attrs] (assoc shape :fills [attrs]))]
        (transform-fill state ids color set)))))

(defn add-fill
  [ids color]
  (ptk/reify ::add-fill
    ptk/WatchEvent
    (watch [_ state _]
      (let [add (fn [shape attrs]
                  (-> shape
                      (update :fills #(into [attrs] %))))]
        (transform-fill state ids color add)))))

(defn remove-fill
  [ids color position]
  (ptk/reify ::remove-fill
    ptk/WatchEvent
    (watch [_ state _]
      (let [remove-fill-by-index (fn [values index] (->> (d/enumerate values)
                                                         (filterv (fn [[idx _]] (not= idx index)))
                                                         (mapv second)))

            remove (fn [shape _] (update shape :fills remove-fill-by-index position))]
        (transform-fill state ids color remove)))))

(defn remove-all-fills
  [ids color]
  (ptk/reify ::remove-all-fills
    ptk/WatchEvent
    (watch [_ state _]
      (let [remove-all (fn [shape _] (assoc shape :fills []))]
        (transform-fill state ids color remove-all)))))


(defn change-hide-fill-on-export
  [ids hide-fill-on-export]
  (ptk/reify ::change-hide-fill-on-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            is-text?  #(= :text (:type (get objects %)))
            shape-ids (filter (complement is-text?) ids)
            attrs {:hide-fill-on-export hide-fill-on-export}]
        (rx/of (dch/update-shapes shape-ids (fn [shape]
                                              (if (= (:type shape) :frame)
                                                (d/merge shape attrs)
                                                shape))))))))

(defn change-stroke
  [ids attrs index]
  (ptk/reify ::change-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (let [color-attrs (cond-> {}
                          (contains? attrs :color)
                          (assoc :stroke-color (:color attrs))

                          (contains? attrs :id)
                          (assoc :stroke-color-ref-id (:id attrs))

                          (contains? attrs :file-id)
                          (assoc :stroke-color-ref-file (:file-id attrs))

                          (contains? attrs :gradient)
                          (assoc :stroke-color-gradient (:gradient attrs))

                          (contains? attrs :opacity)
                          (assoc :stroke-opacity (:opacity attrs)))
            attrs (merge attrs color-attrs)]

        (rx/of (dch/update-shapes ids (fn [shape]
                                        (assoc-in shape [:strokes index] (merge (get-in shape [:strokes index]) attrs)))))))))

(defn add-stroke
  [ids stroke]
  (ptk/reify ::add-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (let [add (fn [shape attrs] (assoc shape :strokes (into [attrs] (:strokes shape))))]
        (rx/of (dch/update-shapes
                ids
                #(add % stroke)))))))

(defn remove-stroke
  [ids position]
  (ptk/reify ::remove-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (let [remove-fill-by-index (fn [values index] (->> (d/enumerate values)
                                                         (filterv (fn [[idx _]] (not= idx index)))
                                                         (mapv second)))

            remove (fn [shape] (update shape :strokes remove-fill-by-index position))]
        (rx/of (dch/update-shapes
                ids
                #(remove %)))))))

(defn remove-all-strokes
  [ids]
  (ptk/reify ::remove-all-strokes
    ptk/WatchEvent
    (watch [_ _ _]
      (let [remove-all (fn [shape] (assoc shape :strokes []))]
        (rx/of (dch/update-shapes
                ids
                #(remove-all %)))))))

(defn reorder-strokes
  [ids index new-index]
  (ptk/reify ::reorder-strokes
    ptk/WatchEvent
    (watch [_ _ _]
           (rx/of (dch/update-shapes
                   ids
                   #(swap-attrs % :strokes index new-index))))))

(defn picker-for-selected-shape
  []
  (let [sub (rx/subject)]
    (ptk/reify ::picker-for-selected-shape
      ptk/WatchEvent
      (watch [_ state stream]
        (let [ids   (wsh/lookup-selected state)
              stop? (rx/filter (ptk/type? ::stop-picker) stream)

              update-events
              (fn [color]
                (rx/of (change-fill ids color 0)))]

          (rx/merge
           ;; Stream that updates the stroke/width and stops if `esc` pressed
           (->> sub
                (rx/take-until stop?)
                (rx/flat-map update-events))

           ;; Hide the modal if the stop event is emitted
           (->> stop?
                (rx/take 1)
                (rx/map #(md/hide))))))

      ptk/UpdateEvent
      (update [_ state]
        (let [handle-change-color (fn [color] (rx/push! sub color))]
          (-> state
              (assoc-in [:workspace-global :picking-color?] true)
              (assoc ::md/modal {:id (random-uuid)
                                 :data {:color clr/black :opacity 1}
                                 :type :colorpicker
                                 :props {:on-change handle-change-color}
                                 :allow-click-outside true})))))))

(defn start-gradient
  [gradient]
  (ptk/reify ::start-gradient
    ptk/UpdateEvent
    (update [_ state]
      (let [id (-> state wsh/lookup-selected first)]
        (-> state
            (assoc-in [:workspace-global :current-gradient] gradient)
            (assoc-in [:workspace-global :current-gradient :shape-id] id))))))

(defn stop-gradient
  []
  (ptk/reify ::stop-gradient
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-global dissoc :current-gradient)))))

(defn update-gradient
  [changes]
  (ptk/reify ::update-gradient
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-global :current-gradient] merge changes)))))

(defn select-gradient-stop
  [spot]
  (ptk/reify ::select-gradient-stop
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-global :editing-stop] spot)))))
