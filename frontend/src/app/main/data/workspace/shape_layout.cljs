;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.transforms :as dwt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def layout-keys
  [:layout
   :layout-dir
   :layout-gap
   :layout-type
   :layout-wrap-type
   :layout-padding-type
   :layout-padding
   :layout-h-orientation
   :layout-v-orientation])

(def initial-layout
  {:layout true
   :layout-dir :left
   :layout-gap 0
   :layout-type :packed
   :layout-wrap-type :wrap
   :layout-padding-type :simple
   :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-h-orientation :top
   :layout-v-orientation :left})

(defn update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids     (->> ids (filter #(get-in objects [% :layout])))]
        (if (d/not-empty? ids)
          (rx/of (dwt/set-modifiers ids)
                 (dwt/apply-modifiers))
          (rx/empty))))))

;; TODO: Remove constraints from children
(defn create-layout
  [ids]
  (ptk/reify ::create-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwc/update-shapes ids #(merge % initial-layout))
             (update-layout-positions ids)))))

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

