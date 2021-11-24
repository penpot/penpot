;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.changes-builder
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as h]))

;; Auxiliary functions to help create a set of changes (undo + redo)

(defn empty-changes
  [origin page-id]
  (let [changes {:redo-changes []
                 :undo-changes []
                 :origin origin}]
    (with-meta changes
      {::page-id page-id})))

(defn with-objects [changes objects]
  (vary-meta changes assoc ::objects objects))

(defn add-obj
  ([changes obj index]
   (add-obj changes (assoc obj ::index index)))

  ([changes obj]
   (let [add-change
         {:type      :add-obj
          :id        (:id obj)
          :page-id   (::page-id (meta changes))
          :parent-id (:parent-id obj)
          :frame-id  (:frame-id obj)
          :index     (::index obj)
          :obj       (dissoc obj ::index :parent-id)}

         del-change
         {:type :del-obj
          :id (:id obj)
          :page-id (::page-id (meta changes))}]

     (-> changes
         (update :redo-changes conj add-change)
         (update :undo-changes d/preconj del-change)))))

(defn change-parent
  [changes parent-id shapes]
  (assert (contains? (meta changes) ::objects) "Call (with-objects) first to use this function")

  (let [objects (::objects (meta changes))
        set-parent-change
        {:type :mov-objects
         :parent-id parent-id
         :page-id (::page-id (meta changes))
         :shapes (->> shapes (mapv :id))}

        mk-undo-change
        (fn [change-set shape]
          (d/preconj
           change-set
           {:type :mov-objects
            :page-id (::page-id (meta changes))
            :parent-id (:parent-id shape)
            :shapes [(:id shape)]
            :index (cp/position-on-parent (:id shape) objects)}))]

    (-> changes
        (update :redo-changes conj set-parent-change)
        (update :undo-changes #(reduce mk-undo-change % shapes)))))

(defn- generate-operation
  "Given an object old and new versions and an attribute will append into changes
  the set and undo operations"
  [changes attr old new ignore-geometry?]
  (let [old-val (get old attr)
        new-val (get new attr)]
    (if (= old-val new-val)
      changes
      (-> changes
          (update :rops conj {:type :set :attr attr :val new-val :ignore-geometry ignore-geometry?})
          (update :uops conj {:type :set :attr attr :val old-val :ignore-touched true})))))

(defn update-shapes
  "Calculate the changes and undos to be done when a function is applied to a
  single object"
  ([changes ids update-fn]
   (update-shapes changes ids update-fn nil))

  ([changes ids update-fn {:keys [attrs ignore-geometry?] :or {attrs nil ignore-geometry? false}}]
   (assert (contains? (meta changes) ::objects) "Call (with-objects) first to use this function")
   (let [objects (::objects (meta changes))

         update-shape
         (fn [changes id]
           (let [old-obj (get objects id)
                 new-obj (update-fn old-obj)

                 attrs (or attrs (d/concat #{} (keys old-obj) (keys new-obj)))

                 {rops :rops uops :uops}
                 (reduce #(generate-operation %1 %2 old-obj new-obj ignore-geometry?)
                         {:rops [] :uops []}
                         attrs)

                 uops (cond-> uops
                        (seq uops)
                        (conj {:type :set-touched :touched (:touched old-obj)}))

                 change {:type :mod-obj
                         :page-id (::page-id (meta changes))
                         :id id}]

             (cond-> changes
               (seq rops)
               (update :redo-changes conj (assoc change :operations rops))

               (seq uops)
               (update :undo-changes d/preconj (assoc change :operations uops)))))]

     (reduce update-shape changes ids))))

(defn remove-objects
  [changes ids]
  (assert (contains? (meta changes) ::objects) "Call (with-objects) first to use this function")
  (let [page-id (::page-id (meta changes))
        objects (::objects (meta changes))

        add-redo-change
        (fn [change-set id]
          (conj change-set
                {:type :del-obj
                 :page-id page-id
                 :id id}))

        add-undo-change-shape
        (fn [change-set id]
          (let [shape (get objects id)]
            (d/preconj
             change-set
             {:type :add-obj
              :page-id page-id
              :parent-id (:frame-id shape)
              :frame-id (:frame-id shape)
              :id id
              :obj (cond-> shape
                     (contains? shape :shapes)
                     (assoc :shapes []))})))

        add-undo-change-parent
        (fn [change-set id]
          (let [shape (get objects id)]
            (d/preconj
             change-set
             {:type :mov-objects
              :page-id page-id
              :parent-id (:parent-id shape)
              :shapes [id]
              :index (h/position-on-parent id objects)
              :ignore-touched true})))]

    (-> changes
        (update :redo-changes #(reduce add-redo-change % ids))
        (update :undo-changes #(as-> % $
                                 (reduce add-undo-change-parent $ ids)
                                 (reduce add-undo-change-shape $ ids))))))


(defn move-page
  [chdata index prev-index]
  (let [page-id (::page-id (meta chdata))]
    (-> chdata
        (update :redo-changes conj {:type :mov-page :id page-id :index index})
        (update :undo-changes conj {:type :mov-page :id page-id :index prev-index}))))
