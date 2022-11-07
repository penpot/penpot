;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def layout-keys
  [:layout
   :layout-flex-dir
   :layout-gap-type
   :layout-gap
   :layout-align-items
   :layout-justify-content
   :layout-align-content
   :layout-wrap-type
   :layout-padding-type
   :layout-padding
   :layout-gap-type])

(def initial-flex-layout
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :simple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :no-wrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

(def initial-grid-layout ;; TODO
  {:layout :grid})

(defn update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids     (->> ids (filter (partial ctl/layout? objects)))]
        (if (d/not-empty? ids)
          (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
            (rx/of (dwm/set-modifiers modif-tree)
                   (dwm/apply-modifiers)))
          (rx/empty))))))

(defn get-layout-initializer
  [type]
  (let [initial-layout-data (if (= type :flex) initial-flex-layout initial-grid-layout)]
    (fn [shape]
      (-> shape
          (merge shape initial-layout-data)))))

(defn create-layout
  [ids type]
  (ptk/reify ::create-layout
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            children-ids (into [] (mapcat #(get-in objects [% :shapes])) ids)]
        (rx/of (dwc/update-shapes ids (get-layout-initializer type))
               (update-layout-positions ids)
               (dwc/update-shapes children-ids #(dissoc % :constraints-h :constraints-v)))))))

(defn remove-layout
  [ids]
  (ptk/reify ::remove-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwc/update-shapes ids #(apply dissoc % layout-keys))
             (update-layout-positions ids)))))

(defn update-layout
  [ids changes]
  (ptk/reify ::update-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwc/update-shapes ids #(d/deep-merge % changes))
             (update-layout-positions ids)))))

(defn update-layout-child
  [ids changes]
  (ptk/reify ::update-layout-child
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            parent-ids (->> ids (map #(cph/get-parent-id objects %)))
            layout-ids (->> ids (filter (comp ctl/layout? (d/getf objects))))]
        (rx/of (dwc/update-shapes ids #(d/deep-merge (or % {}) changes))
               (update-layout-positions (d/concat-vec layout-ids parent-ids)))))))
