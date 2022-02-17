;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.changes-builder
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]))

;; Auxiliary functions to help create a set of changes (undo + redo)

(defn empty-changes
  ([origin page-id]
   (let [changes (empty-changes origin)]
     (with-meta changes
       {::page-id page-id})))

  ([origin]
   {:redo-changes []
    :undo-changes []
    :origin origin}))

(defn with-page [changes page]
  (vary-meta changes assoc
             ::page page
             ::page-id (:id page)
             ::objects (:objects page)))

(defn with-objects [changes objects]
  (vary-meta changes assoc ::objects objects))

(defn add-obj
  ([changes obj]
   (add-obj changes obj nil))

  ([changes obj {:keys [index ignore-touched] :or {index ::undefined ignore-touched false}}]
   (let [obj (cond-> obj
               (not= index ::undefined)
               (assoc :index index))

         add-change
         {:type           :add-obj
          :id             (:id obj)
          :page-id        (::page-id (meta changes))
          :parent-id      (:parent-id obj)
          :frame-id       (:frame-id obj)
          :index          (::index obj)
          :ignore-touched ignore-touched
          :obj            (dissoc obj ::index :parent-id)}

         del-change
         {:type :del-obj
          :id (:id obj)
          :page-id (::page-id (meta changes))}]

     (-> changes
         (update :redo-changes conj add-change)
         (update :undo-changes d/preconj del-change)))))

(defn change-parent
  ([changes parent-id shapes] (change-parent changes parent-id shapes nil))
  ([changes parent-id shapes index]
   (assert (contains? (meta changes) ::objects) "Call (with-objects) first to use this function")

   (let [objects (::objects (meta changes))
         set-parent-change
         (cond-> {:type :mov-objects
                  :parent-id parent-id
                  :page-id (::page-id (meta changes))
                  :shapes (->> shapes (mapv :id))}

           (some? index)
           (assoc :index index))

         mk-undo-change
         (fn [change-set shape]
           (d/preconj
             change-set
             {:type :mov-objects
              :page-id (::page-id (meta changes))
              :parent-id (:parent-id shape)
              :shapes [(:id shape)]
              :index (cph/get-position-on-parent objects (:id shape))}))]

     (-> changes
         (update :redo-changes conj set-parent-change)
         (update :undo-changes #(reduce mk-undo-change % shapes))))))

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

                 attrs (or attrs (d/concat-set (keys old-obj) (keys new-obj)))

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
              :index (cph/get-position-on-parent objects id)
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

(defn set-page-option
  [chdata option-key option-val]
  (let [page-id (::page-id (meta chdata))
        page (::page (meta chdata))
        old-val (get-in page [:options option-key])]

    (-> chdata
        (update :redo-changes conj {:type :set-option
                                    :page-id page-id
                                    :option option-key
                                    :value option-val})
        (update :undo-changes conj {:type :set-option
                                    :page-id page-id
                                    :option option-key
                                    :value old-val}))))

(defn reg-objects
  [chdata shape-ids]
  (let [page-id (::page-id (meta chdata))]
    (-> chdata
        (update :redo-changes conj {:type :reg-objects :page-id page-id :shapes shape-ids}))))
        ;; No need to do anything to undo

(defn amend-last-change
  "Modify the last redo-changes added with an update function."
  [chdata f]
  (update chdata :redo-changes
          #(conj (pop %) (f (peek %)))))

(defn amend-changes
  "Modify all redo-changes with an update function."
  [chdata f]
  (update chdata :redo-changes #(mapv f %)))

