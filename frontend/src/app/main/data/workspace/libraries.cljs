;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.common.pages :as cp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(defn add-color
  [color]
  (us/assert ::us/string color)
  (ptk/reify ::add-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [id   (uuid/next)
            rchg {:type :add-color
                  :color {:id id
                          :name color
                          :value color}}
            uchg {:type :del-color
                  :id id}]
        (rx/of #(assoc-in % [:workspace-local :color-for-rename] id)
               (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-recent-color
  [color]
  (us/assert ::us/string color)
  (ptk/reify ::add-recent-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [rchg {:type :add-recent-color
                  :color color}]
        (rx/of (dwc/commit-changes [rchg] [] {:commit-local? true}))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn update-color
  [{:keys [id] :as color}]
  (us/assert ::cp/color color)
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :mod-color
                  :color color}
            uchg {:type :mod-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn delete-color
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :del-color
                  :id id}
            uchg {:type :add-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-media
  [{:keys [id] :as media}]
  (us/assert ::cp/media-object media)
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [rchg {:type :add-media
                  :object media}
            uchg {:type :del-media
                  :id id}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))


(defn delete-media
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :media id])
            rchg {:type :del-media
                  :id id}
            uchg {:type :add-media
                  :object prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(declare clone-shape)

(def add-component
  (ptk/reify ::add-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (dws/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; we need to create a group before creating the component.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (dws/prepare-create-group page-id shapes "Component-" true))

                [new-shape new-shapes updated-shapes]
                (clone-shape group nil objects)

                rchanges (conj rchanges
                               {:type :add-component
                                :id (:id new-shape)
                                :name (:name new-shape)
                                :new-shapes new-shapes})

                rchanges (into rchanges
                               (map (fn [updated-shape]
                                      {:type :mod-obj
                                       :page-id page-id
                                       :id (:id updated-shape)
                                       :operations [{:type :set
                                                     :attr :component-id
                                                     :val (:component-id updated-shape)}]})
                                    updated-shapes))

                uchanges (conj uchanges
                               {:type :del-component
                                :id (:id new-shape)})

                uchanges (into uchanges
                               (map (fn [updated-shape]
                                      {:type :mod-obj
                                       :page-id page-id
                                       :id (:id updated-shape)
                                       :operations [{:type :set
                                                     :attr :component-id
                                                     :val nil}]})
                                    updated-shapes))]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(defn- clone-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape parent-id objects]
  (let [new-id (uuid/next)]
    (if (nil? (:shapes shape))

      ; TODO: unify this case with the empty child-ids case.
      (let [new-shape (assoc shape
                             :id new-id
                             :parent-id parent-id
                             :frame-id nil)]
        [new-shape
         [new-shape]
         [(assoc shape :component-id (:id new-shape))]])

      (loop [child-ids (seq (:shapes shape))
             new-children []
             updated-children []]

        (if (empty? child-ids)
          (let [new-shape (assoc shape
                                 :id new-id
                                 :parent-id parent-id
                                 :frame-id nil
                                 :shapes (map :id new-children))]
            [new-shape
             (conj new-children new-shape)
             (conj updated-children
                   (assoc shape :component-id (:id new-shape)))])

          (let [child-id (first child-ids)
                child (get objects child-id)

                [new-child new-child-shapes updated-child-shapes]
                (clone-shape child new-id objects)]

          (recur
            (next child-ids)
            (into new-children new-child-shapes)
            (into updated-children updated-child-shapes))))))))

(defn delete-component
  [{:keys [id] :as params}]
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (get-in state [:workspace-data :components id])

            rchanges [{:type :del-component
                       :id id}]

            uchanges [{:type :add-component
                       :id id
                       :name (:name component)
                       :new-shapes (:objects component)}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

