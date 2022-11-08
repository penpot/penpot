;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.selection :as dwse]
   [app.main.data.workspace.shapes :as dws]
   [app.main.data.workspace.shapes-update-layout :as wsul]
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
   ])


(def initial-flex-layout
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :simple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :strech
   :layout-wrap-type       :no-wrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

(def initial-grid-layout ;; TODO
  {:layout :grid})

;; TODO: Remove constraints from children
(defn create-layout
  [ids type]
  (ptk/reify ::create-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (if (= type :flex)
        (rx/of (dwc/update-shapes ids #(merge % initial-flex-layout))
               (wsul/update-layout-positions ids))
        (rx/of (dwc/update-shapes ids #(merge % initial-grid-layout))
               (wsul/update-layout-positions ids))))))

(defn create-layout-from-selection
  [type]
  (ptk/reify ::create-layout-from-selection
    ptk/WatchEvent
    (watch [_ state _]

      (let [page-id         (:current-page-id state)
            objects         (wsh/lookup-page-objects state page-id)
            selected        (wsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected)
            single?         (= (count selected-shapes) 1)
            has-group?      (->> selected-shapes (d/seek cph/group-shape?))
            is-group?       (and single? has-group?)]
        (if is-group?
          (let [parent-id    (:parent-id (first selected-shapes))
                new-shape-id (uuid/next)
                shapes-ids   (:shapes (first selected-shapes))
                ordered-ids  (into (d/ordered-set) shapes-ids)]
            (rx/of (dwse/select-shapes ordered-ids)
                   (dws/create-artboard-from-selection new-shape-id parent-id)
                   (create-layout [new-shape-id] type)
                   (dws/delete-shapes page-id selected)))

          (let [new-shape-id (uuid/next)]
            (rx/of (dws/create-artboard-from-selection new-shape-id)
                   (create-layout [new-shape-id] type))))))))

(defn remove-layout
  [ids]
  (ptk/reify ::remove-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwc/update-shapes ids #(apply dissoc % layout-keys))
             (wsul/update-layout-positions ids)))))

(defn toogle-layout-flex
  []
  (ptk/reify ::toogle-layout-flex
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id         (:current-page-id state)
            objects         (wsh/lookup-page-objects state page-id)
            selected        (wsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected)
            single?         (= (count selected-shapes) 1)
            has-flex-layout? (and single? (= :flex (:layout (first selected-shapes))))]

        (if has-flex-layout?
          (rx/of (remove-layout selected))
          (rx/of (create-layout-from-selection :flex)))))))

(defn update-layout
  [ids changes]
  (ptk/reify ::update-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwc/update-shapes ids #(d/deep-merge % changes))
             (wsul/update-layout-positions ids)))))

(defn update-layout-child
  [ids changes]
  (ptk/reify ::update-layout-child
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            parent-ids (->> ids (map #(cph/get-parent-id objects %)))]
        (rx/of (dwc/update-shapes ids #(d/deep-merge (or % {}) changes))
               (wsul/update-layout-positions parent-ids))))))
