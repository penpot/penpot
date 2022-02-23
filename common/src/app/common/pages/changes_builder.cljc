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

(defn amend-last-change
  "Modify the last redo-changes added with an update function."
  [changes f]
  (update changes :redo-changes
          #(conj (pop %) (f (peek %)))))

(defn amend-changes
  "Modify all redo-changes with an update function."
  [changes f]
  (update changes :redo-changes #(mapv f %)))

(defn- assert-page-id
  [changes]
  (assert (contains? (meta changes) ::page-id) "Give a page-id or call (with-page) before using this function"))

(defn- assert-page
  [changes]
  (assert (contains? (meta changes) ::page) "Call (with-page) before using this function"))

(defn- assert-objects
  [changes]
  (assert (contains? (meta changes) ::objects) "Call (with-objects) before using this function"))

;; Page changes

(defn add-empty-page
  [changes id name]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :name name})
      (update :undo-changes conj {:type :del-page :id id})))

(defn add-page
  [changes id page]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :page page})
      (update :undo-changes conj {:type :del-page :id id})))

(defn mod-page
  [changes page new-name]
  (-> changes
      (update :redo-changes conj {:type :mod-page :id (:id page) :name new-name})
      (update :undo-changes conj {:type :mod-page :id (:id page) :name (:name page)})))

(defn del-page
  [changes page]
  (-> changes
      (update :redo-changes conj {:type :del-page :id (:id page)})
      (update :undo-changes conj {:type :add-page :id (:id page) :page page})))

(defn move-page
  [changes page-id index prev-index]
  (-> changes
      (update :redo-changes conj {:type :mov-page :id page-id :index index})
      (update :undo-changes conj {:type :mov-page :id page-id :index prev-index})))

(defn set-page-option
  [changes option-key option-val]
  (assert-page changes)
  (let [page-id (::page-id (meta changes))
        page (::page (meta changes))
        old-val (get-in page [:options option-key])]

    (-> changes
        (update :redo-changes conj {:type :set-option
                                    :page-id page-id
                                    :option option-key
                                    :value option-val})
        (update :undo-changes conj {:type :set-option
                                    :page-id page-id
                                    :option option-key
                                    :value old-val}))))

;; Shape tree changes

(defn add-obj
  ([changes obj]
   (add-obj changes obj nil))

  ([changes obj {:keys [index ignore-touched] :or {index ::undefined ignore-touched false}}]
   (assert-page-id changes)
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
  ([changes parent-id shapes]
   (change-parent changes parent-id shapes nil))

  ([changes parent-id shapes index]
   (assert-page-id changes)
   (assert-objects changes)
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

(defn update-shapes
  "Calculate the changes and undos to be done when a function is applied to a
  single object"
  ([changes ids update-fn]
   (update-shapes changes ids update-fn nil))

  ([changes ids update-fn {:keys [attrs ignore-geometry?] :or {attrs nil ignore-geometry? false}}]
   (assert-page-id changes)
   (assert-objects changes)
   (let [objects (::objects (meta changes))

         generate-operation
         (fn [changes attr old new ignore-geometry?]
           (let [old-val (get old attr)
                 new-val (get new attr)]
             (if (= old-val new-val)
               changes
               (-> changes
                   (update :rops conj {:type :set :attr attr :val new-val :ignore-geometry ignore-geometry?})
                   (update :uops conj {:type :set :attr attr :val old-val :ignore-touched true})))))

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
  (assert-page-id changes)
  (assert-objects changes)
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
              :index (cph/get-position-on-parent objects (:id shape))
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

(defn resize-parents
  [changes ids]
  (assert-page-id changes)
  (let [page-id (::page-id (meta changes))
        shapes  (vec ids)]
        (-> changes
            (update :redo-changes conj {:type :reg-objects :page-id page-id :shapes shapes})
            (update :undo-changes conj {:type :reg-objects :page-id page-id :shapes shapes}))))

