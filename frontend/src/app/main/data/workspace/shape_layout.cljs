;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dwse]
   [app.main.data.workspace.shapes :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
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
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :no-wrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

(def initial-grid-layout ;; TODO
  {:layout :grid})

(defn get-layout-initializer
  [type]
  (let [initial-layout-data (if (= type :flex) initial-flex-layout initial-grid-layout)]
    (fn [shape]
      (-> shape
          (merge shape initial-layout-data)))))

(defn create-layout-from-id
  [ids type]
  (ptk/reify ::create-layout-from-id
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            children-ids (into [] (mapcat #(get-in objects [% :shapes])) ids)
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids (get-layout-initializer type))
               (ptk/data-event :layout/update ids)
               (dwc/update-shapes children-ids #(dissoc % :constraints-h :constraints-v))
               (dwu/commit-undo-transaction undo-id))))))


;; Never call this directly but through the data-event `:layout/update`
;; Otherwise a lot of cycle dependencies could be generated
(defn- update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ _ _]
      (if (d/not-empty? ids)
        (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
          (rx/of (dwm/apply-modifiers {:modifiers modif-tree})))
        (rx/empty)))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::finalize) stream)]
        (->> stream
             (rx/filter (ptk/type? :layout/update))
             (rx/map deref)
             (rx/map #(update-layout-positions %))
             (rx/take-until stopper))))))

(defn finalize
  []
  (ptk/reify ::finalize))

(defn shapes->flex-params
  "Given the shapes calculate its flex parameters (horizontal vs vertical etc)"
  [objects shapes]

  (let [points
        (->> shapes
             (map :id)
             (ctt/sort-z-index objects)
             (map (comp gsh/center-shape (d/getf objects))))

        start (first points)
        end (reduce (fn [acc p] (gpt/add acc (gpt/to-vec start p))) points)

        angle (gpt/signed-angle-with-other
               (gpt/to-vec start end)
               (gpt/point 1 0))

        angle (mod angle 360)

        t1 (min (abs (-  angle 0)) (abs (-  angle 360)))
        t2 (abs (- angle 90))
        t3 (abs (- angle 180))
        t4 (abs (- angle 270))

        tmin (min t1 t2 t3 t4)

        direction
        (cond
          (mth/close? tmin t1) :row
          (mth/close? tmin t2) :reverse-column
          (mth/close? tmin t3) :reverse-row
          (mth/close? tmin t4) :column)]

    {:layout-flex-dir direction}))

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
          (let [new-shape-id (uuid/next)
                parent-id    (:parent-id (first selected-shapes))
                shapes-ids   (:shapes (first selected-shapes))
                ordered-ids  (into (d/ordered-set) shapes-ids)
                undo-id      (js/Symbol)]
            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dwse/select-shapes ordered-ids)
             (dws/create-artboard-from-selection new-shape-id parent-id)
             (cl/remove-all-fills [new-shape-id] {:color clr/black
                                                  :opacity 1})
             (create-layout-from-id [new-shape-id] type)
             (dwc/update-shapes
              [new-shape-id]
              (fn [shape]
                (-> shape
                    (assoc :layout-item-h-sizing :auto
                           :layout-item-v-sizing :auto))))

             (ptk/data-event :layout/update [new-shape-id])
             (dws/delete-shapes page-id selected)
             (dwu/commit-undo-transaction undo-id)))

          (let [new-shape-id (uuid/next)
                undo-id      (js/Symbol)
                flex-params     (shapes->flex-params objects selected-shapes)]
            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dws/create-artboard-from-selection new-shape-id)
             (cl/remove-all-fills [new-shape-id] {:color clr/black
                                                  :opacity 1})
             (create-layout-from-id [new-shape-id] type)
             (dwc/update-shapes
              [new-shape-id]
              (fn [shape]
                (-> shape
                    (merge flex-params)
                    (assoc :layout-item-h-sizing :auto
                           :layout-item-v-sizing :auto))))

             (ptk/data-event :layout/update [new-shape-id])
             (dwu/commit-undo-transaction undo-id))))))))

(defn remove-layout
  [ids]
  (ptk/reify ::remove-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwc/update-shapes ids #(apply dissoc % layout-keys))
         (ptk/data-event :layout/update ids)
         (dwu/commit-undo-transaction undo-id))))))

(defn create-layout
  []
  (ptk/reify ::create-layout
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id          (:current-page-id state)
            objects          (wsh/lookup-page-objects state page-id)
            selected         (wsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            is-frame?        (= :frame (:type (first selected-shapes)))
            undo-id          (js/Symbol)]

        (if (and single? is-frame?)
          (rx/of
           (dwu/start-undo-transaction undo-id)
           (create-layout-from-id [(first selected)] :flex)
           (dwu/commit-undo-transaction undo-id))
          (rx/of
           (dwu/start-undo-transaction undo-id)
           (create-layout-from-selection :flex)
           (dwu/commit-undo-transaction undo-id)))))))

(defn toggle-layout-flex
  []
  (ptk/reify ::toggle-layout-flex
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id          (:current-page-id state)
            objects          (wsh/lookup-page-objects state page-id)
            selected         (wsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            has-flex-layout? (and single? (ctl/layout? objects (:id (first selected-shapes))))]

        (when (not= 0 (count selected))
          (if has-flex-layout?
            (rx/of (remove-layout selected))
            (rx/of (create-layout))))))))

(defn update-layout
  [ids changes]
  (ptk/reify ::update-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids #(d/deep-merge % changes))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn update-layout-child
  [ids changes]
  (ptk/reify ::update-layout-child
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            parent-ids (->> ids (map #(cph/get-parent-id objects %)))
            layout-ids (->> ids (filter (comp ctl/layout? (d/getf objects))))
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids #(d/deep-merge (or % {}) changes))
               (ptk/data-event :layout/update (d/concat-vec layout-ids parent-ids))
               (dwu/commit-undo-transaction undo-id))))))
