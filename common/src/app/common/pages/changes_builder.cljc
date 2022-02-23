;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.changes-builder
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bool :as gshb]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]))

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
  (let [file-data (-> (cp/make-file-data (uuid/next) uuid/zero)
                      (assoc-in [:pages-index uuid/zero :objects] objects))]
    (vary-meta changes assoc ::file-data file-data
                             ::applied-changes 0)))

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
  (assert (contains? (meta changes) ::file-data) "Call (with-objects) before using this function"))

(defn- apply-changes-local
  [changes]
  (if-let [file-data (::file-data (meta changes))]
    (let [index         (::applied-changes (meta changes))
          redo-changes  (:redo-changes changes)
          new-changes   (if (< index (count redo-changes))
                          (->> (subvec (:redo-changes changes) index)
                               (map #(assoc % :page-id uuid/zero)))
                          [])
          new-file-data (cp/process-changes file-data new-changes)]
      (vary-meta changes assoc ::file-data new-file-data
                               ::applied-changes (count redo-changes)))
    changes))

;; Page changes

(defn add-empty-page
  [changes id name]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :name name})
      (update :undo-changes conj {:type :del-page :id id})
      (apply-changes-local)))

(defn add-page
  [changes id page]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :page page})
      (update :undo-changes conj {:type :del-page :id id})
      (apply-changes-local)))

(defn mod-page
  [changes page new-name]
  (-> changes
      (update :redo-changes conj {:type :mod-page :id (:id page) :name new-name})
      (update :undo-changes conj {:type :mod-page :id (:id page) :name (:name page)})
      (apply-changes-local)))

(defn del-page
  [changes page]
  (-> changes
      (update :redo-changes conj {:type :del-page :id (:id page)})
      (update :undo-changes conj {:type :add-page :id (:id page) :page page})
      (apply-changes-local)))

(defn move-page
  [changes page-id index prev-index]
  (-> changes
      (update :redo-changes conj {:type :mov-page :id page-id :index index})
      (update :undo-changes conj {:type :mov-page :id page-id :index prev-index})
      (apply-changes-local)))

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
                                    :value old-val})
        (apply-changes-local))))

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
         (update :undo-changes d/preconj del-change)
         (apply-changes-local)))))

(defn change-parent
  ([changes parent-id shapes]
   (change-parent changes parent-id shapes nil))

  ([changes parent-id shapes index]
   (assert-page-id changes)
   (assert-objects changes)
   (let [objects (-> changes meta ::file-data (get-in [:pages-index uuid/zero :objects]))

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
         (update :undo-changes #(reduce mk-undo-change % shapes))
         (apply-changes-local)))))

(defn update-shapes
  "Calculate the changes and undos to be done when a function is applied to a
  single object"
  ([changes ids update-fn]
   (update-shapes changes ids update-fn nil))

  ([changes ids update-fn {:keys [attrs ignore-geometry?] :or {attrs nil ignore-geometry? false}}]
   (assert-page-id changes)
   (assert-objects changes)
   (let [objects (-> changes meta ::file-data (get-in [:pages-index uuid/zero :objects]))

         generate-operation
         (fn [operations attr old new ignore-geometry?]
           (let [old-val (get old attr)
                 new-val (get new attr)]
             (if (= old-val new-val)
               operations
               (-> operations
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

     (-> (reduce update-shape changes ids)
         (apply-changes-local)))))

(defn remove-objects
  [changes ids]
  (assert-page-id changes)
  (assert-objects changes)
  (let [page-id (::page-id (meta changes))
        objects (-> changes meta ::file-data (get-in [:pages-index uuid/zero :objects]))

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
                                 (reduce add-undo-change-shape $ ids)))
        (apply-changes-local))))

(defn resize-parents
  [changes ids]
  (assert-page-id changes)
  (assert-objects changes)
  (let [page-id (::page-id (meta changes))
        objects (-> changes meta ::file-data (get-in [:pages-index uuid/zero :objects]))

        xform   (comp
                  (mapcat #(cons % (cph/get-parent-ids objects %)))
                  (map (d/getf objects))
                  (filter #(contains? #{:group :bool} (:type %)))
                  (distinct))
        all-parents (sequence xform ids)

        generate-operation
        (fn [operations attr old new]
          (let [old-val (get old attr)
                new-val (get new attr)]
            (if (= old-val new-val)
              operations
              (-> operations
                  (update :rops conj {:type :set :attr attr :val new-val :ignore-touched true})
                  (update :uops conj {:type :set :attr attr :val old-val :ignore-touched true})))))

        resize-parent
        (fn [changes parent]
          (let [children (->> parent :shapes (map (d/getf objects)))
                resized-parent (cond
                                 (empty? children)
                                 changes

                                 (= (:type parent) :bool)
                                 (gshb/update-bool-selrect parent children objects)

                                 (= (:type parent) :group)
                                 (if (:masked-group? parent)
                                   (gsh/update-mask-selrect parent children)
                                   (gsh/update-group-selrect parent children)))

                {rops :rops uops :uops}
                (reduce #(generate-operation %1 %2 parent resized-parent)
                        {:rops [] :uops []}
                        (keys parent))

                change {:type :mod-obj
                        :page-id page-id
                        :id (:id parent)}]

            (if (seq rops)
              (-> changes
                  (update :redo-changes conj (assoc change :operations rops))
                  (update :undo-changes conj (assoc change :operations uops)))
              changes)))]

    (-> (reduce resize-parent changes all-parents)
        (apply-changes-local))))

