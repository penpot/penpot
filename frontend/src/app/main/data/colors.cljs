;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.data.colors
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [clojure.set :as set]
   [potok.core :as ptk]
   [app.main.streams :as ms]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.modal :as md]
   [app.common.pages-helpers :as cph]))

(declare create-color-result)

(defn create-color
  [file-id color]
  (s/assert (s/nilable uuid?) file-id)
  (ptk/reify ::create-color
    ptk/WatchEvent
    (watch [_ state s]

      (->> (rp/mutation! :create-color {:file-id file-id
                                        :content color
                                        :name color})
           (rx/map (partial create-color-result file-id))))))

(defn create-color-result
  [file-id color]
  (ptk/reify ::create-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :colors] #(conj % color))
          (assoc-in [:workspace-local :color-for-rename] (:id color))))))

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

(declare update-color-result)

(defn update-color
  [file-id color-id content]
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :update-color {:id color-id
                                        :content content})
           (rx/map (partial update-color-result file-id))))))

(defn update-color-result
  [file-id color]
  (ptk/reify ::update-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :colors] #(d/replace-by-id % color))))))

(declare delete-color-result)

(defn delete-color
  [file-id color-id]
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :delete-color {:id color-id})
           (rx/map #(delete-color-result file-id color-id))))))

(defn delete-color-result
  [file-id color-id]
  (ptk/reify ::delete-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :colors]
                     (fn [colors] (filter #(not= (:id %) color-id) colors)))))))

(defn change-palette-size [size]
  (s/assert #{:big :small} size)
  (ptk/reify ::change-palette-size
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :selected-palette-size] size)))))

(defn change-palette-selected [selected]
  (ptk/reify ::change-palette-selected
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :selected-palette] selected)))))

(defn show-palette [selected]
  (ptk/reify ::change-palette-selected
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-layout conj :colorpalette)
          (assoc-in [:workspace-local :selected-palette] selected)))))

(defn start-picker []
  (ptk/reify ::start-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picking-color?] true)))))

(defn stop-picker []
  (ptk/reify ::stop-picker
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :picked-color-select)
          (update :workspace-local dissoc :picked-shift?)
          (assoc-in [:workspace-local :picking-color?] false)))))

(defn pick-color [rgba]
  (ptk/reify ::pick-color
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picked-color] rgba)))))

(defn pick-color-select [value shift?]
  (ptk/reify ::pick-color-select
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :picked-color-select] value)
          (assoc-in [:workspace-local :picked-shift?] shift?)))))


(defn change-fill
  ([ids color id file-id]
   (change-fill ids color 1 id file-id))
  ([ids color opacity id file-id]
   (ptk/reify ::change-fill
     ptk/WatchEvent
     (watch [_ state s]
       (let [pid (:current-page-id state)
             objects (get-in state [:workspace-data :pages-index pid :objects])
             children (mapcat #(cph/get-children % objects) ids)
             ids (into ids children)

             is-text? #(= :text (:type (get objects %)))
             text-ids (filter is-text? ids)
             shape-ids (filter (comp not is-text?) ids)
             update-fn (fn [shape] (assoc shape
                                          :fill-color color
                                          :fill-opacity opacity
                                          :fill-color-ref-id id
                                          :fill-color-ref-file file-id))
             editor (get-in state [:workspace-local :editor])
             converted-attrs {:fill color}

             reduce-fn (fn [state id]
                         (update-in state [:workspace-data :pages-index pid :objects id]  update-fn))]

         (rx/from (conj
                   (map #(dwt/update-text-attrs {:id % :editor editor :attrs converted-attrs}) text-ids)
                   (dwc/update-shapes shape-ids update-fn))))))))

(defn change-stroke [ids color id file-id]
  (ptk/reify ::change-stroke
    ptk/WatchEvent
    (watch [_ state s]
      (let [objects (get-in state [:workspace-data :pages-index (:current-page-id state) :objects])
            children (mapcat #(cph/get-children % objects) ids)
            ids (into ids children)

            update-fn (fn [s]
                        (cond-> s
                          true
                          (assoc :stroke-color color
                                 :stroke-color-ref-id id
                                 :stroke-color-ref-file file-id)

                          (= (:stroke-style s) :none)
                          (assoc :stroke-style :solid
                                 :stroke-width 1
                                 :stroke-opacity 1)))]
        (rx/of (dwc/update-shapes ids update-fn))))))

(defn picker-for-selected-shape []
  ;; TODO: replace st/emit! by a subject push and set that in the WatchEvent
  (let [handle-change-color (fn [color opacity id file-id shift?]
                              (let [ids (get-in @st/state [:workspace-local :selected])]
                                (st/emit!
                                 (if shift?
                                   (change-stroke ids color nil nil)
                                   (change-fill ids color nil nil))
                                 (md/hide-modal))))]
    (ptk/reify ::start-picker
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc-in [:workspace-local :picking-color?] true)
            (assoc ::md/modal {:id (random-uuid)
                               :type :colorpicker
                               :props {:on-change handle-change-color}
                               :allow-click-outside true}))))))
