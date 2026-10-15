;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.comp-processors
  "Repair, migration or transformation utilities for components."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.files.swap-slots :as cfss]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.text :as cttx]
   [clojure.set :as set]))

(log/set-level! :warn)

;; ---- Swap slots

(defn find-swap-slot
  "The deepest main child of the position the shape takes: its swap slot,
  or the first one along its ref chain, or the last shape of the chain
  before a main root."
  ([shape container file libraries]
   (find-swap-slot shape container file libraries #{}))
  ([shape container file libraries viewed-ids]
   (if (contains? viewed-ids (:id shape)) ;; prevent cycles
     nil
     (if-let [swap-slot (cfss/get-swap-slot shape)]
       swap-slot
       (let [ref-shape (ctf/find-ref-shape file
                                           container
                                           libraries
                                           shape
                                           :include-deleted? true
                                           :with-context? true)
             shape-meta (meta ref-shape)
             ref-file (:file shape-meta)
             ref-container (:container shape-meta)]
         (when ref-shape
           (if-let [swap-slot (cfss/get-swap-slot ref-shape)]
             swap-slot
             (if (ctk/main-instance? ref-shape)
               (:id shape)
               (find-swap-slot ref-shape ref-container ref-file libraries (conj viewed-ids (:id shape)))))))))))

(defn find-near-match
  "Locate the shape that occupies the same position in the near main component.
  This will be the ref-shape except if the shape is a copy subhead that has been
  swapped. In this case, the near match will be the ref-shape that was before
  the swap."
  [file container libraries shape & {:keys [include-deleted? with-context?] :or {include-deleted? false with-context? false}}]
  (let  [parent-shape     (ctn/get-shape container (:parent-id shape))
         parent-ref-shape (when parent-shape
                            (ctf/find-ref-shape file container libraries parent-shape :include-deleted? include-deleted? :with-context? true))
         ref-container    (when parent-ref-shape
                            (:container (meta parent-ref-shape)))
         shape-index      (when parent-shape
                            (d/index-of (:shapes parent-shape) (:id shape)))
         near-match-id    (when (and parent-ref-shape shape-index)
                            (get (:shapes parent-ref-shape) shape-index))
         near-match       (when near-match-id
                            (cond-> (ctn/get-shape ref-container near-match-id)
                              with-context?
                              (with-meta (meta parent-ref-shape))))]
    near-match))

(defn- find-next-related-swap-shape-id
  "Go up from the chain of references shapes that will eventually lead to the shape
   with swap-slot-id as id. Returns the next shape on the chain. `container`
   holds `parent`."
  [parent container swap-slot-id libraries]
  (let [children          (cfh/get-children (:objects container) (:id parent))
        original-shape-id (->> children
                               (filter #(= swap-slot-id (:id %)))
                               first
                               :id)]
    (if original-shape-id
      ;; Return the children which id is the swap-slot-id
      original-shape-id
      ;; No children with swap-slot-id as id, go up
      (let [referenced-shape (ctf/find-ref-shape nil container libraries parent :with-context? true)
            ;; Recursive call that will get the id of the next shape on
            ;; the chain that ends on a shape with swap-slot-id as id
            next-shape-id    (when referenced-shape
                               (find-next-related-swap-shape-id referenced-shape
                                                                (:container (meta referenced-shape))
                                                                swap-slot-id
                                                                libraries))]
        ;; Return the children which shape-ref points to the next-shape-id
        (->> children
             (filter #(= next-shape-id (:shape-ref %)))
             first
             :id)))))

(defn find-ref-id-for-swapped
  "When a shape has a swap slot, find the original ref-id that the shape had
   before the swap"
  [shape container libraries]
  (let [swap-slot   (cfss/get-swap-slot shape)
        objects     (:objects container)

        parent      (get objects (:parent-id shape))
        parent-head (ctn/get-head-shape objects parent)
        parent-ref  (ctf/find-ref-shape nil container libraries parent-head :with-context? true)]

    (when (and swap-slot parent-ref)
      (find-next-related-swap-shape-id parent-ref (:container (meta parent-ref)) swap-slot libraries))))

;; ---- Repairs

(defn remove-unneeded-objects-in-components
  "Some components have an :objects attribute, despite not being deleted. This removes it.
   It also adds an empty :objects if it's deleted and does not have it."
  [file-data]
  (ctf/update-components
   file-data
   (fn [component]
     (if (:deleted component)
       (if (nil? (:objects component))
         (do
           (log/warn :hint "adding empty :objects to deleted component"
                     :component-id (str (:id component))
                     :component-name (:name component)
                     :file-id (str (:id file-data)))
           (assoc component :objects {}))
         component)
       (if (contains? component :objects)
         (do
           (log/warn :hint "removing :objects from non-deleted component"
                     :component-id (str (:id component))
                     :component-name (:name component)
                     :file-id (str (:id file-data)))
           (dissoc component :objects))
         component)))))

(defn normalize-component-root
  "Some old files have shapes with an explicit :component-root false. This is semantically
   equivalent to the attribute being absent (instance-root? only treats true as root), but
   breaks the subcopy-head? predicate, which expects nil. Remove the explicit false so the
   downstream fixers can recognize these shapes as nested copy heads."
  [file-data]
  (try
    (ctf/update-all-shapes
     file-data
     (fn [shape]
       (if (false? (:component-root shape))
         (do
           (log/warn :hint "normalizing :component-root false on shape"
                     :shape-id (str (:id shape))
                     :shape-name (:name shape)
                     :file-id (str (:id file-data)))
           {:result :update :updated-shape (dissoc shape :component-root)})
         {:result :keep})))
    (catch #?(:clj Throwable :cljs :default) e
      (log/warn :hint "failed to normalize :component-root on shapes"
                :file-id (str (:id file-data))
                :cause e)
      file-data)))

(defn fix-missing-swap-slots
  "Locate shapes that have been swapped (their ref is outside the near main, see
   `ctf/ref-outside-near-main?`) but don't have a swap slot.
   In this case, add one pointing to the near match."
  [file-data libraries]
  (try
    (ctf/update-all-shapes
     file-data
     (fn [shape]
       (if (ctk/subcopy-head? shape)
         (let [container (:container (meta shape))
               file {:id (:id file-data) :data file-data}
               swapped? (ctf/ref-outside-near-main?
                         shape container
                         #(ctf/find-ref-shape file container libraries % :include-deleted? true))
               near-match (when swapped?
                            (find-near-match file container libraries shape :include-deleted? true :with-context? false))]
           (if (and (some? near-match)
                    (not= (:shape-ref shape) (:id near-match))
                    (nil? (cfss/get-swap-slot shape)))
             (let [updated-shape (cfss/set-swap-slot shape (:id near-match))]
               (log/warn :hint "adding missing swap slot to shape"
                         :shape-id (str (:id shape))
                         :shape-name (:name shape)
                         :swap-slot (str (:id near-match))
                         :file-id (str (:id file))
                         :container-id (str (:id container))
                         :container-type (:type container))
               {:result :update :updated-shape updated-shape})
             {:result :keep}))
         {:result :keep})))
    (catch #?(:clj Throwable :cljs :default) e
      (log/warn :hint "failed to fix missing swap slots on shapes"
                :file-id (str (:id file-data))
                :cause e)
      file-data)))

(defn sync-component-id-with-ref-shape
  "Ensure that all copies heads have the same component id and file as the referenced shape.
   There may be bugs that cause them to get out of sync."
  [file-data libraries]
  (letfn [(sync-one-iteration
            [file-data libraries]
            (try
              (ctf/update-all-shapes
               file-data
               (fn [shape]
                 (if (and (ctk/subcopy-head? shape)
                          (nil? (cfss/get-swap-slot shape))
                          (not (ctk/swapped? shape)))
                   (let [container (:container (meta shape))
                         file {:id (:id file-data) :data file-data}
                         ref-shape (ctf/find-ref-shape file container libraries shape {:include-deleted? true :with-context? true})]
                     (if (and (some? ref-shape)
                              (or (not= (:component-id shape) (:component-id ref-shape))
                                  (not= (:component-file shape) (:component-file ref-shape))))
                       (let [shape' (cond-> shape
                                      (some? (:component-id ref-shape))
                                      (assoc :component-id (:component-id ref-shape))

                                      (nil? (:component-id ref-shape))
                                      (dissoc :component-id)

                                      (some? (:component-file ref-shape))
                                      (assoc :component-file (:component-file ref-shape))

                                      (nil? (:component-file ref-shape))
                                      (dissoc :component-file))]
                         (log/warn :hint "syncing component id and file with ref shape"
                                   :shape-id (str (:id shape))
                                   :shape-name (:name shape)
                                   :component-id (str (:component-id shape'))
                                   :component-file (str (:component-file shape'))
                                   :ref-shape-id (str (:id ref-shape))
                                   :file-id (str (:id file))
                                   :container-id (str (:id container))
                                   :container-type (:type container))
                         {:result :update :updated-shape shape'})
                       {:result :keep}))
                   {:result :keep})))
              (catch #?(:clj Throwable :cljs :default) e
                (log/warn :hint "failed to sync component id and file with ref shape"
                          :file-id (str (:id file-data))
                          :cause e)
                file-data)))]
    ;; If a copy inside a main is updated, we need to repeat the process for the change to be
    ;; propagated to all copies.
    (loop [current-data file-data
           iteration    0]
      (let [next-data (sync-one-iteration current-data libraries)]
        (if (or (= current-data next-data)
                (> iteration 20))     ;; safety bound
          next-data
          (recur next-data (inc iteration)))))))

(def ^:dynamic *defer-unreachable*
  "When true, the conversions of older forms that need a copy's near main
  (`convert-swap-slots`, `convert-text-overrides`) leave as they are the
  shapes whose near main they cannot reach, for a later pass with more
  libraries."
  false)

(defn- find-swap-position
  "Among the children of `parent-ref`, the near main of the parent of a
  swapped head, the one that stands for the position `origin`: the deepest
  main child of that position, as a swap slot holds it."
  [parent-ref libraries origin]
  (let [container (:container (meta parent-ref))
        file      (:file (meta parent-ref))]
    (d/seek #(or (= (:id %) origin)
                 (= origin (find-swap-slot % container file libraries)))
            (keep #(ctn/get-shape container %) (:shapes parent-ref)))))

(defn find-replaced-shape
  "The child of the near main of the parent of the copy head `shape` whose
  position the head takes, with context: the one that stands for the deepest
  main child the head refers to, or else the free one at the same index."
  [file container libraries shape]
  (let [parent     (ctn/get-shape container (:parent-id shape))
        parent-ref (when parent
                     (ctf/find-content-ref-shape file container libraries parent
                                                 :include-deleted? true
                                                 :with-context? true))]
    (when parent-ref
      (some-> (or (find-swap-position parent-ref libraries (:shape-ref shape))
                  (let [taken   (into #{} (keep #(:shape-ref (ctn/get-shape container %))) (:shapes parent))
                        index   (d/index-of (:shapes parent) (:id shape))
                        near-id (when index (get (:shapes parent-ref) index))]
                    (when (and (some? near-id) (not (contains? taken near-id)))
                      (ctn/get-shape (:container (meta parent-ref)) near-id))))
              (with-meta (meta parent-ref))))))

(defn- swap-made-in-copy?
  "Whether a copy head with the swap slot `slot` was swapped in its copy,
  and not only inherited the slot from its main: it refers to the root of
  its own component, not to a child of its near main. When the near main
  cannot be reached, a head that refers to its slot inherited it, since a
  swap made in the copy refers to the root of another component; otherwise
  its own component tells, and when that cannot be reached either, the swap
  counts as made in the copy, so that it is kept."
  [shape slot parent-ref file libraries]
  (cond
    (some? parent-ref)
    (not-any? #(= % (:shape-ref shape)) (:shapes parent-ref))

    (= slot (:shape-ref shape))
    false

    :else
    (let [component (ctf/resolve-component shape file libraries :include-deleted? true)]
      (or (nil? component)
          (= (:shape-ref shape) (:main-instance-id component))))))

(defn- away-from-position?
  "Whether the swapped head sits elsewhere than the main child it replaces
  would place it. Sync did not move a head with a swap slot, so the main may
  have moved that child since the swap. A position a layout owns does not
  count."
  [file container libraries shape position]
  (let [objects   (:objects container)
        ;; the head sync places it against: the top copy, or a swapped head
        ;; around it, which syncs from its own component
        root      (d/seek #(or (ctk/instance-root? %)
                               (some? (cfss/get-swap-slot %))
                               (ctk/swapped? %))
                          (map #(get objects %) (cfh/get-parent-ids objects (:id shape))))
        root-main (when root
                    (ctf/find-content-ref-shape file container libraries root :include-deleted? true))
        point     (fn [shape]
                    (gpt/point (dm/get-in shape [:selrect :x])
                               (dm/get-in shape [:selrect :y])))]
    (and (some? root-main)
         (not (ctk/touched-group? shape :geometry-group))
         (or (not (ctsl/any-layout-immediate-child? objects shape))
             (ctsl/position-absolute? shape))
         (not (gpt/close? (point shape)
                          (gpt/add (point position)
                                   (gpt/subtract (point root) (point root-main))))))))

(defn convert-swap-slots
  "Store the swaps of the file as override groups. Files older than the
  feature components/swap-group mark a swapped copy head with a swap slot
  (a `swap-slot-<id>` touched group) and point its :shape-ref at the root of
  its own component. Now the head keeps the :shape-ref of the near main child
  it replaces and carries the touched group :swap-group.

  A swap made in a copy is paired with the child of the near main that holds
  the same position, as sync paired it. Its geometry is marked as touched when
  it sits elsewhere than that child would place it, since sync now moves it
  with that child. One with no such child, or whose near
  main cannot be reached, refers to its slot, so validation reports it. Any
  other slot (a swap in a main, or a slot a copy inherited) is dropped.

  Every pairing is found on the data as it was. With
  `*defer-unreachable*`, a slot whose near main cannot be reached is
  left, and the data carries `::deferred-swaps` in its metadata."
  [file-data libraries]
  (let [file      {:id (:id file-data) :data file-data}
        deferred? (volatile! false)

        convert-shape
        (fn [container shape slot]
          (let [parent (ctn/get-shape container (:parent-id shape))
                shape' (cfss/remove-swap-slot shape)]
            (if-not (and (ctk/instance-head? shape)
                         (ctk/in-component-copy? parent))
              shape'
              (let [parent-ref (ctf/find-content-ref-shape file container libraries parent
                                                           :include-deleted? true
                                                           :with-context? true)]
                (cond
                  (not (swap-made-in-copy? shape slot parent-ref file libraries))
                  shape'

                  (and (nil? parent-ref) *defer-unreachable*)
                  (do (vreset! deferred? true)
                      shape)

                  :else
                  (let [position (when parent-ref
                                   (find-swap-position parent-ref libraries slot))]
                    (cond-> (-> shape'
                                (assoc :shape-ref (or (:id position) slot))
                                (update :touched (fnil conj #{}) :swap-group))
                      ;; keep it where the user sees it
                      (and (some? position)
                           (away-from-position? file container libraries shape position))
                      (update :touched conj :geometry-group))))))))

        convert-container
        (fn [container]
          (let [converted (into []
                                (keep (fn [shape]
                                        (when-let [slot (cfss/get-swap-slot shape)]
                                          (convert-shape container shape slot))))
                                (vals (:objects container)))]
            (reduce #(assoc-in %1 [:objects (:id %2)] %2) container converted)))

        file-data (ctf/update-containers file-data convert-container)]
    (cond-> file-data
      @deferred?
      (vary-meta assoc ::deferred-swaps true))))

(def ^:private legacy-text-groups
  #{:text-content-attribute :text-content-structure})

(defn legacy-text-touched?
  "Whether `shape` holds the text override groups of files older than the
  feature components/text-overrides."
  [shape]
  (boolean (some legacy-text-groups (:touched shape))))

(defn- content-group-alone?
  "Whether the copy text `shape` has `:content-group` touched with no text
  override group, as files older than the text override groups mark a copy
  text whose content changed in any way."
  [shape]
  (let [touched (:touched shape)]
    (and (cfh/text-shape? shape)
         (ctk/in-component-copy? shape)
         (contains? touched :content-group)
         (not-any? cttx/text-groups touched)
         (not-any? legacy-text-groups touched))))

(defn recompute-text-touched
  "The touched groups of a copy text `shape` that holds the text override
  groups of files older than the feature components/text-overrides, given
  its near main `ref`: each text override group the old ones allowed
  (`cttx/text-overrides`) where the copy differs from `ref`, or all of them
  when `ref` is nil. A shape that is not a copy keeps none."
  [shape ref]
  (let [touched (:touched shape)
        allowed (cond
                  (not (ctk/in-component-copy? shape)) #{}
                  (content-group-alone? shape)         cttx/text-groups
                  :else                                (cttx/text-overrides touched))
        groups  (if (some? ref)
                  (set/intersection allowed (cttx/override-groups (:content ref) (:content shape)))
                  allowed)]
    (cond-> (set/difference touched cttx/text-groups legacy-text-groups #{:content-group})
      (seq groups)
      (into (conj groups :content-group)))))

(defn convert-text-overrides
  "Store the text overrides of the file's copies per letters and per style.
  Files older than the feature components/text-overrides mark a copy text
  with `:text-content-attribute` (any style) and `:text-content-structure`
  (the number of paragraphs or runs); now it holds `:text-content-text` and
  one group per style unit (`cttx/style-units`). Older files still mark it
  with `:content-group` alone, which allows every group. Each copy text with
  the old groups is compared with its near main, and takes the groups the
  old ones allowed where it differs. When the near main cannot be reached it
  takes every group they allowed, so that it loses no override; with
  `*defer-unreachable*` it is left, and the data carries
  `::deferred-texts` in its metadata."
  [file-data libraries]
  (let [file      {:id (:id file-data) :data file-data}
        deferred? (volatile! false)

        convert-container
        (fn [container]
          (reduce (fn [container shape]
                    (if-not (or (legacy-text-touched? shape) (content-group-alone? shape))
                      container
                      (let [ref (when (ctk/in-component-copy? shape)
                                  (ctf/find-ref-shape file container libraries shape
                                                      :include-deleted? true))]
                        (if (and (nil? ref) (ctk/in-component-copy? shape) *defer-unreachable*)
                          (do (vreset! deferred? true)
                              container)
                          (assoc-in container [:objects (:id shape) :touched]
                                    (recompute-text-touched shape ref))))))
                  container
                  (vals (:objects container))))

        file-data (ctf/update-containers file-data convert-container)]
    (cond-> file-data
      @deferred?
      (vary-meta assoc ::deferred-texts true))))
