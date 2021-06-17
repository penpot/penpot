;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.colors
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as md]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.repo :as rp]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

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

(defn change-palette-size
  [size]
  (s/assert #{:big :small} size)
  (ptk/reify ::change-palette-size
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :selected-palette-size] size)))))

(defn change-palette-selected
  "Change the library used by the general palette tool"
  [selected]
  (ptk/reify ::change-palette-selected
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :selected-palette] selected)))))

(defn change-palette-selected-colorpicker
  "Change the library used by the color picker"
  [selected]
  (ptk/reify ::change-palette-selected-colorpicker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :selected-palette-colorpicker] selected)))))

(defn show-palette
  "Show the palette tool and change the library it uses"
  [selected]
  (ptk/reify ::change-palette-selected
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-layout conj :colorpalette)
          (assoc-in [:workspace-local :selected-palette] selected)))))

(defn start-picker
  []
  (ptk/reify ::start-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picking-color?] true)))))

(defn stop-picker
  []
  (ptk/reify ::stop-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :picked-color-select :picked-shift?)
          (assoc-in [:workspace-local :picking-color?] false)))))

(defn pick-color
  [rgba]
  (ptk/reify ::pick-color
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picked-color] rgba)))))

(defn pick-color-select
  [value shift?]
  (ptk/reify ::pick-color-select
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picked-color-select] value)
          (assoc-in [:workspace-local :picked-shift?] shift?)))))

(defn change-fill
  [ids color]
  (ptk/reify ::change-fill
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)

            is-text?  #(= :text (:type (get objects %)))
            text-ids  (filter is-text? ids)
            shape-ids (filter (comp not is-text?) ids)

            attrs (cond-> {}
                    (contains? color :color)
                    (assoc :fill-color (:color color))

                    (contains? color :id)
                    (assoc :fill-color-ref-id (:id color))

                    (contains? color :file-id)
                    (assoc :fill-color-ref-file (:file-id color))

                    (contains? color :gradient)
                    (assoc :fill-color-gradient (:gradient color))

                    (contains? color :opacity)
                    (assoc :fill-opacity (:opacity color)))]

        (rx/concat
         (rx/from (map #(dwt/update-text-attrs {:id % :attrs attrs}) text-ids))
         (rx/of (dch/update-shapes shape-ids (fn [shape] (d/merge shape attrs)))))))))

(defn change-stroke
  [ids color]
  (ptk/reify ::change-stroke
    ptk/WatchEvent
    (watch [_ _ _]
      (let [attrs (cond-> {}
                    (contains? color :color)
                    (assoc :stroke-color (:color color))

                    (contains? color :id)
                    (assoc :stroke-color-ref-id (:id color))

                    (contains? color :file-id)
                    (assoc :stroke-color-ref-file (:file-id color))

                    (contains? color :gradient)
                    (assoc :stroke-color-gradient (:gradient color))

                    (contains? color :opacity)
                    (assoc :stroke-opacity (:opacity color)))]

        (rx/of (dch/update-shapes ids (fn [shape]
                                        (cond-> (d/merge shape attrs)
                                          (= (:stroke-style shape) :none)
                                          (assoc :stroke-style :solid
                                                 :stroke-width 1
                                                 :stroke-opacity 1)))))))))


(defn picker-for-selected-shape
  []
  (let [sub (rx/subject)]
    (ptk/reify ::picker-for-selected-shape
      ptk/WatchEvent
      (watch [_ state stream]
        (let [ids   (wsh/lookup-selected state)
              stop? (rx/filter (ptk/type? ::stop-picker) stream)

              update-events
              (fn [[color shift?]]
                (rx/of (if shift?
                         (change-stroke ids color)
                         (change-fill ids color))
                       (stop-picker)))]
          (rx/merge
           ;; Stream that updates the stroke/width and stops if `esc` pressed
           (->> sub
                (rx/take-until stop?)
                (rx/flat-map update-events))

           ;; Hide the modal if the stop event is emitted
           (->> stop?
                (rx/first)
                (rx/map #(md/hide))))))

      ptk/UpdateEvent
      (update [_ state]
        (let [handle-change-color (fn [color shift?] (rx/push! sub [color shift?]))]
          (-> state
              (assoc-in [:workspace-local :picking-color?] true)
              (assoc ::md/modal {:id (random-uuid)
                                 :data {:color "#000000" :opacity 1}
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
            (assoc-in [:workspace-local :current-gradient] gradient)
            (assoc-in [:workspace-local :current-gradient :shape-id] id))))))

(defn stop-gradient
  []
  (ptk/reify ::stop-gradient
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :current-gradient)))))

(defn update-gradient
  [changes]
  (ptk/reify ::update-gradient
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-local :current-gradient] merge changes)))))

(defn select-gradient-stop
  [spot]
  (ptk/reify ::select-gradient-stop
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :editing-stop] spot)))))
