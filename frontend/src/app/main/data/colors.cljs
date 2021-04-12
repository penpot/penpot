;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.colors
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as md]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
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
    (watch [_ state stream]
      (->> (rp/mutation! :rename-color {:id color-id
                                        :name name})
           (rx/map (partial rename-color-result file-id))))))

(defn rename-color-result
  [file-id color]
  (ptk/reify ::rename-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :colors] #(d/replace-by-id % color))))))

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
          (update :workspace-local dissoc :picked-color-select)
          (update :workspace-local dissoc :picked-shift?)
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
  ([ids color]
   (ptk/reify ::change-fill
     ptk/WatchEvent
     (watch [_ state s]
       (let [pid (:current-page-id state)
             objects (get-in state [:workspace-data :pages-index pid :objects])
             not-frame (fn [shape-id] (not= (get-in objects [shape-id :type]) :frame))
             is-text? #(= :text (:type (get objects %)))
             text-ids (filter is-text? ids)
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
                     (assoc :fill-opacity (:opacity color)))

             update-fn (fn [shape] (merge shape attrs))
             editors (get-in state [:workspace-local :editors])
             reduce-fn (fn [state id]
                         (update-in state [:workspace-data :pages-index pid :objects id]  update-fn))]

         (rx/from (conj
                   (map #(dwt/update-text-attrs {:id % :editor (get editors %) :attrs attrs}) text-ids)
                   (dwc/update-shapes shape-ids update-fn))))))))

(defn change-stroke
  [ids color]
  (ptk/reify ::change-stroke
    ptk/WatchEvent
    (watch [_ state s]
      (let [pid (:current-page-id state)
            objects (get-in state [:workspace-data :pages-index pid :objects])
            not-frame (fn [shape-id] (not= (get-in objects [shape-id :type]) :frame))

            color-attrs (cond-> {}
                          (contains? color :color)
                          (assoc :stroke-color (:color color))

                          (contains? color :id)
                          (assoc :stroke-color-ref-id (:id color))

                          (contains? color :file-id)
                          (assoc :stroke-color-ref-file (:file-id color))

                          (contains? color :gradient)
                          (assoc :stroke-color-gradient (:gradient color))

                          (contains? color :opacity)
                          (assoc :stroke-opacity (:opacity color)))

            update-fn (fn [shape]
                        (-> shape
                            (merge color-attrs)
                            (cond-> (= (:stroke-style s) :none)
                              (assoc :stroke-style :solid
                                     :stroke-width 1
                                     :stroke-opacity 1))))]
        (rx/of (dwc/update-shapes ids update-fn))))))

(defn picker-for-selected-shape
  []
  (let [sub (rx/subject)]
    (ptk/reify ::picker-for-selected-shape
      ptk/WatchEvent
      (watch [_ state stream]
        (let [ids (get-in state [:workspace-local :selected])
              stop? (->> stream
                         (rx/filter (ptk/type? ::stop-picker)))

              update-events (fn [[color shift?]]
                              (rx/of  (if shift?
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
      (let [id (first (get-in state [:workspace-local :selected]))]
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
