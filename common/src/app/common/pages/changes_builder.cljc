;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.changes-builder
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.features :as ffeat]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.types.file :as ctf]
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

(defn set-save-undo?
  [changes save-undo?]
  (assoc changes :save-undo? save-undo?))

(defn set-stack-undo?
  [changes stack-undo?]
  (assoc changes :stack-undo? stack-undo?))

(defn set-undo-group
  [changes undo-group]
  (cond-> changes
          (some? undo-group)
          (assoc :undo-group undo-group)))

(defn with-page
  [changes page]
  (vary-meta changes assoc
             ::page page
             ::page-id (:id page)))

(defn with-container
  [changes container]
  (if (cph/page? container)
    (vary-meta changes assoc ::page-id (:id container))
    (vary-meta changes assoc ::component-id (:id container))))

(defn with-objects
  [changes objects]
  (let [fdata (binding [ffeat/*current* #{"components/v2"}]
                (ctf/make-file-data (uuid/next) uuid/zero))
        fdata (assoc-in fdata [:pages-index uuid/zero :objects] objects)]
    (vary-meta changes assoc
               ::file-data fdata
               ::applied-changes-count 0)))

(defn with-library-data
  [changes data]
  (vary-meta changes assoc
             ::library-data data))

(defn amend-last-change
  "Modify the last redo-changes added with an update function."
  [changes f]
  (update changes :redo-changes
          #(conj (pop %) (f (peek %)))))

(defn amend-changes
  "Modify all redo-changes with an update function."
  [changes f]
  (update changes :redo-changes #(mapv f %)))

(defn concat-changes
  [changes1 changes2]
  {:redo-changes (d/concat-vec (:redo-changes changes1) (:redo-changes changes2))
   :undo-changes (d/concat-vec (:undo-changes changes1) (:undo-changes changes2))
   :origin (:origin changes1)
   :undo-group (:undo-group changes1)})

; TODO: remove this when not needed
(defn- assert-page-id
  [changes]
  (assert (contains? (meta changes) ::page-id) "Give a page-id or call (with-page) before using this function"))

(defn- assert-container-id
  [changes]
  (assert (or (contains? (meta changes) ::page-id)
              (contains? (meta changes) ::component-id))
          "Give a page-id or call (with-container) before using this function"))

(defn- assert-page
  [changes]
  (assert (contains? (meta changes) ::page) "Call (with-page) before using this function"))

(defn- assert-objects
  [changes]
  (assert (contains? (meta changes) ::file-data) "Call (with-objects) before using this function"))

(defn- assert-library
  [changes]
  (assert (contains? (meta changes) ::library-data) "Call (with-library-data) before using this function"))

(defn- lookup-objects
  [changes]
  (let [data (::file-data (meta changes))]
    (dm/get-in data [:pages-index uuid/zero :objects])))

(defn- apply-changes-local
  [changes]
  (if-let [file-data (::file-data (meta changes))]
    (let [index         (::applied-changes-count (meta changes))
          redo-changes  (:redo-changes changes)
          new-changes   (if (< index (count redo-changes))
                          (->> (subvec (:redo-changes changes) index)
                               (map #(-> %
                                       (assoc :page-id uuid/zero)
                                       (dissoc :component-id))))
                          [])
          new-file-data (cp/process-changes file-data new-changes)]
      (vary-meta changes assoc ::file-data new-file-data
                               ::applied-changes-count (count redo-changes)))
    changes))

;; Page changes

(defn add-empty-page
  [changes id name]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :name name})
      (update :undo-changes d/preconj {:type :del-page :id id})
      (apply-changes-local)))

(defn add-page
  [changes id page]
  (-> changes
      (update :redo-changes conj {:type :add-page :id id :page page})
      (update :undo-changes d/preconj {:type :del-page :id id})
      (apply-changes-local)))

(defn mod-page
  [changes page new-name]
  (-> changes
      (update :redo-changes conj {:type :mod-page :id (:id page) :name new-name})
      (update :undo-changes d/preconj {:type :mod-page :id (:id page) :name (:name page)})
      (apply-changes-local)))

(defn del-page
  [changes page]
  (-> changes
      (update :redo-changes conj {:type :del-page :id (:id page)})
      (update :undo-changes d/preconj {:type :add-page :id (:id page) :page page})
      (apply-changes-local)))

(defn move-page
  [changes page-id index prev-index]
  (-> changes
      (update :redo-changes conj {:type :mov-page :id page-id :index index})
      (update :undo-changes d/preconj {:type :mov-page :id page-id :index prev-index})
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
        (update :undo-changes d/preconj {:type :set-option
                                         :page-id page-id
                                         :option option-key
                                         :value old-val})
        (apply-changes-local))))

(defn update-page-option
  [changes option-key update-fn & args]
  (assert-page changes)
  (let [page-id (::page-id (meta changes))
        page (::page (meta changes))
        old-val (get-in page [:options option-key])
        new-val (apply update-fn old-val args)]

    (-> changes
        (update :redo-changes conj {:type :set-option
                                    :page-id page-id
                                    :option option-key
                                    :value new-val})
        (update :undo-changes d/preconj {:type :set-option
                                         :page-id page-id
                                         :option option-key
                                         :value old-val})
        (apply-changes-local))))

;; Shape tree changes

(defn add-object
  ([changes obj]
   (add-object changes obj nil))

  ([changes obj {:keys [index ignore-touched] :or {index ::undefined ignore-touched false}}]
   (assert-page-id changes)
   (let [obj (cond-> obj
               (not= index ::undefined)
               (assoc ::index index))
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

(defn add-objects
  ([changes objects]
   (add-objects changes objects nil))

  ([changes objects params]
   (reduce #(add-object %1 %2 params)
           changes
           objects)))

(defn change-parent
  ([changes parent-id shapes]
   (change-parent changes parent-id shapes nil))

  ([changes parent-id shapes index]
   (assert-page-id changes)
   (assert-objects changes)
   (let [objects (lookup-objects changes)
         set-parent-change
         (cond-> {:type :mov-objects
                  :parent-id parent-id
                  :page-id (::page-id (meta changes))
                  :shapes (->> shapes reverse (mapv :id))}

           (some? index)
           (assoc :index index))

         mk-undo-change
         (fn [change-set shape]
           (let [prev-sibling (cph/get-prev-sibling objects (:id shape))]
           (d/preconj
             change-set
             {:type :mov-objects
              :page-id (::page-id (meta changes))
              :parent-id (:parent-id shape)
              :shapes [(:id shape)]
              :after-shape prev-sibling
              :index 0})))] ; index is used in case there is no after-shape (moving bottom shapes)

     (-> changes
         (update :redo-changes conj set-parent-change)
         (update :undo-changes #(reduce mk-undo-change % shapes))
         (apply-changes-local)))))

(defn changed-attrs
  "Returns the list of attributes that will change when `update-fn` is applied"
  [object update-fn {:keys [attrs]}]
  (let [changed?
        (fn [old new attr]
          (let [old-val (get old attr)
                new-val (get new attr)]
            (not= old-val new-val)))
        new-obj (update-fn object)]
    (when-not (= object new-obj)
      (let [attrs (or attrs (d/concat-set (keys object) (keys new-obj)))]
        (filter (partial changed? object new-obj) attrs)))))

(defn update-shapes
  "Calculate the changes and undos to be done when a function is applied to a
  single object"
  ([changes ids update-fn]
   (update-shapes changes ids update-fn nil))

  ([changes ids update-fn {:keys [attrs ignore-geometry? ignore-touched]
                           :or {ignore-geometry? false ignore-touched false}}]
   (assert-container-id changes)
   (assert-objects changes)
   (let [page-id      (::page-id (meta changes))
         component-id (::component-id (meta changes))
         objects (lookup-objects changes)

         generate-operation
         (fn [operations attr old new ignore-geometry?]
           (let [old-val (get old attr)
                 new-val (get new attr)]
             (if (= old-val new-val)
               operations
               (-> operations
                   (update :rops conj {:type :set :attr attr :val new-val
                                       :ignore-geometry ignore-geometry?
                                       :ignore-touched ignore-touched})
                   (update :uops d/preconj {:type :set :attr attr :val old-val
                                            :ignore-touched true})))))

         update-shape
         (fn [changes id]
           (let [old-obj (get objects id)
                 new-obj (update-fn old-obj)]
             (if (= old-obj new-obj)
               changes
               (let [attrs (or attrs (d/concat-set (keys old-obj) (keys new-obj)))

                     {rops :rops uops :uops}
                     (reduce #(generate-operation %1 %2 old-obj new-obj ignore-geometry?)
                             {:rops [] :uops []}
                             attrs)

                     uops (cond-> uops
                            (seq uops)
                            (d/preconj {:type :set-touched :touched (:touched old-obj)}))

                     change (cond-> {:type :mod-obj
                                     :id id}

                              (some? page-id)
                              (assoc :page-id page-id)

                              (some? component-id)
                              (assoc :component-id component-id))]

                 (cond-> changes
                   (seq rops)
                   (update :redo-changes conj (assoc change :operations rops))

                   (seq uops)
                   (update :undo-changes d/preconj (assoc change :operations uops)))))))]

             (-> (reduce update-shape changes ids)
                 (apply-changes-local)))))

(defn remove-objects
  [changes ids]
  (assert-page-id changes)
  (assert-objects changes)
  (let [page-id (::page-id (meta changes))
        objects (lookup-objects changes)

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
              :id id
              :page-id page-id
              :parent-id (:frame-id shape)
              :frame-id (:frame-id shape)
              :index (cph/get-position-on-parent objects id)
              :obj (cond-> shape
                     (contains? shape :shapes)
                     (assoc :shapes []))})))

        add-undo-change-parent
        (fn [change-set id]
          (let [shape (get objects id)
                prev-sibling (cph/get-prev-sibling objects (:id shape))]
            (d/preconj
             change-set
             {:type :mov-objects
              :page-id page-id
              :parent-id (:parent-id shape)
              :shapes [id]
              :after-shape prev-sibling
              :index 0
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

        objects (lookup-objects changes)
        xform   (comp
                  (mapcat #(cons % (cph/get-parent-ids objects %)))
                  (map (d/getf objects))
                  (filter #(contains? #{:group :bool} (:type %)))
                  (distinct))
        all-parents (sequence xform ids)

        generate-operation
        (fn [operations attr old new]
          (let [old-val (get old attr)
                new-val (get new attr)

                equal?  (cond
                          (and (number? old-val) (number? new-val))
                          (mth/close? old-val new-val)

                          (and (gmt/matrix? old-val) (gmt/matrix? new-val))
                          (gmt/close? old-val new-val)

                          (= attr :points)
                          (every? #(apply gpt/close? %) (d/zip old-val new-val))

                          (= attr :selrect)
                          (gsh/close-selrect? old-val new-val)

                          :else
                          (= old-val new-val))]
            (if equal?
              operations
              (-> operations
                  (update :rops conj {:type :set :attr attr :val new-val :ignore-touched true})
                  (update :uops d/preconj {:type :set :attr attr :val old-val :ignore-touched true})))))

        resize-parent
        (fn [changes parent]
          (let [objects (lookup-objects changes)
                children (->> parent :shapes (map (d/getf objects)))
                resized-parent (cond
                                 (empty? children) ;; a parent with no children will be deleted,
                                 nil               ;; so it does not need resize

                                 (= (:type parent) :bool)
                                 (gsh/update-bool-selrect parent children objects)

                                 (= (:type parent) :group)
                                 (if (:masked-group? parent)
                                   (gsh/update-mask-selrect parent children)
                                   (gsh/update-group-selrect parent children)))]
            (if resized-parent
              (let [{rops :rops uops :uops}
                    (reduce #(generate-operation %1 %2 parent resized-parent)
                            {:rops [] :uops []}
                            (keys parent))

                    change {:type :mod-obj
                            :page-id page-id
                            :id (:id parent)}]

                (if (seq rops)
                  (-> changes
                      (update :redo-changes conj (assoc change :operations rops))
                      (update :undo-changes d/preconj (assoc change :operations uops))
                      (apply-changes-local))
                  changes))
              changes)))]

    (reduce resize-parent changes all-parents)))

;; Library changes

(defn add-recent-color
  [changes color]
  (-> changes
      (update :redo-changes conj {:type :add-recent-color :color color})
      (apply-changes-local)))

(defn add-color
  [changes color]
  (-> changes
      (update :redo-changes conj {:type :add-color :color color})
      (update :undo-changes d/preconj {:type :del-color :id (:id color)})
      (apply-changes-local)))

(defn update-color
  [changes color]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-color (get-in library-data [:colors (:id color)])]
    (-> changes
        (update :redo-changes conj {:type :mod-color :color color})
        (update :undo-changes d/preconj {:type :mod-color :color prev-color})
        (apply-changes-local))))

(defn delete-color
  [changes color-id]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-color (get-in library-data [:colors color-id])]
    (-> changes
        (update :redo-changes conj {:type :del-color :id color-id})
        (update :undo-changes d/preconj {:type :add-color :color prev-color})
        (apply-changes-local))))

(defn add-media
  [changes object]
  (-> changes
      (update :redo-changes conj {:type :add-media :object object})
      (update :undo-changes d/preconj {:type :del-media :id (:id object)})
      (apply-changes-local)))

(defn update-media
  [changes object]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-object (get-in library-data [:media (:id object)])]
    (-> changes
        (update :redo-changes conj {:type :mod-media :object object})
        (update :undo-changes d/preconj {:type :mod-media :object prev-object})
        (apply-changes-local))))

(defn delete-media
  [changes id]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-object (get-in library-data [:media id])]
    (-> changes
        (update :redo-changes conj {:type :del-media :id id})
        (update :undo-changes d/preconj {:type :add-media :object prev-object})
        (apply-changes-local))))

(defn add-typography
  [changes typography]
  (-> changes
      (update :redo-changes conj {:type :add-typography :typography typography})
      (update :undo-changes d/preconj {:type :del-typography :id (:id typography)})
      (apply-changes-local)))

(defn update-typography
  [changes typography]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-typography (get-in library-data [:typographies (:id typography)])]
    (-> changes
        (update :redo-changes conj {:type :mod-typography :typography typography})
        (update :undo-changes d/preconj {:type :mod-typography :typography prev-typography})
        (apply-changes-local))))

(defn delete-typography
  [changes typography-id]
  (assert-library changes)
  (let [library-data (::library-data (meta changes))
        prev-typography (get-in library-data [:typographies typography-id])]
    (-> changes
        (update :redo-changes conj {:type :del-typography :id typography-id})
        (update :undo-changes d/preconj {:type :add-typography :typography prev-typography})
        (apply-changes-local))))

(defn add-component
  [changes id path name new-shapes updated-shapes main-instance-id main-instance-page]
  (assert-page-id changes)
  (assert-objects changes)
  (let [page-id (::page-id (meta changes))
        objects (lookup-objects changes)
        lookupf (d/getf objects)

        mk-change (fn [shape]
                    {:type :mod-obj
                     :page-id page-id
                     :id (:id shape)
                     :operations [{:type :set
                                   :attr :component-id
                                   :val (:component-id shape)}
                                  {:type :set
                                   :attr :component-file
                                   :val (:component-file shape)}
                                  {:type :set
                                   :attr :component-root?
                                   :val (:component-root? shape)}
                                  {:type :set
                                   :attr :main-instance?
                                   :val (:main-instance? shape)}
                                  {:type :set
                                   :attr :shape-ref
                                   :val (:shape-ref shape)}
                                  {:type :set
                                   :attr :touched
                                   :val (:touched shape)}]}) ]
    (-> changes
        (update :redo-changes
                (fn [redo-changes]
                  (-> redo-changes
                      (conj (cond-> {:type :add-component
                                     :id id
                                     :path path
                                     :name name
                                     :main-instance-id main-instance-id
                                     :main-instance-page main-instance-page}
                                    (some? new-shapes)  ;; this will be null in components-v2
                                    (assoc :shapes new-shapes)))
                      (into (map mk-change) updated-shapes))))
        (update :undo-changes
                (fn [undo-changes]
                  (-> undo-changes
                      (d/preconj {:type :del-component
                                  :id id
                                  :skip-undelete? true})
                      (into (comp (map :id)
                                  (map lookupf)
                                  (map mk-change))
                            updated-shapes))))
        (apply-changes-local))))

(defn update-component
  [changes id update-fn]
  (assert-library changes)
  (let [library-data   (::library-data (meta changes))
        prev-component (get-in library-data [:components id])
        new-component  (update-fn prev-component)]
    (if new-component
      (-> changes
          (update :redo-changes conj {:type :mod-component
                                      :id id
                                      :name (:name new-component)
                                      :path (:path new-component)
                                      :objects (:objects new-component)}) ;; this won't exist in components-v2
          (update :undo-changes d/preconj {:type :mod-component
                                           :id id
                                           :name (:name prev-component)
                                           :path (:path prev-component)
                                           :objects (:objects prev-component)}))
      changes)))

(defn delete-component
  [changes id]
  (assert-library changes)
  (-> changes
      (update :redo-changes conj {:type :del-component
                                  :id id})
      (update :undo-changes d/preconj {:type :restore-component
                                        :id id})))

(defn restore-component
  ([changes id]
   (restore-component changes id nil))
  ([changes id page-id]
   (assert-library changes)
   (-> changes
       (update :redo-changes conj {:type :restore-component
                                   :id id
                                   :page-id page-id})
       (update :undo-changes d/preconj {:type :del-component
                                        :id id}))))

(defn ignore-remote
  [changes]
  (letfn [(add-ignore-remote
            [change-list]
            (->> change-list
                 (mapv #(assoc % :ignore-remote? true))))]
    (-> changes
        (update :redo-changes add-ignore-remote)
        (update :undo-changes add-ignore-remote))))
