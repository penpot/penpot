;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.libraries
  "Component sync: the changes that bring copies up to date with their mains
   (sync and reset) and mains up to date with a copy (update main), and the
   sync of a file with the libraries it uses (`generate-sync-file-changes`).
   Creating, detaching, swapping and duplicating components live in
   `app.common.logic.instances`, `.swap` and `.duplicate`; colors and
   typographies in `app.common.logic.library-assets`."
  #?(:cljs (:require-macros [app.common.logic.libraries :refer [shape-log container-log]]))
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.logic.library-assets :as clla]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path.segment :as segment]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

;; Add uuids here to filter logs to only show specific shapes or containers (and all shapes
;; contained in them).
(def log-shape-ids #{})
(def log-container-ids #{})

(def updatable-attrs
  (->> (keys ctk/sync-attrs)
       ;; We don't update the flex-child attrs
       (remove ctk/swap-keep-attrs)
       ;; We don't do automatic update of the `layout-grid-cells` property.
       (remove #(= :layout-grid-cells %))))

;; Attrs whose value drifts by float noise when a shape is moved; they
;; are compared with tolerance during sync. The rest compare exactly.
(def ^:private drifting-geometry-attrs
  #{:x :y :width :height :selrect :points})

(defn- position-data-close?
  "Compares text position-data entry by entry: numbers with tolerance,
  every other field (fills, fonts, text) exactly."
  [a b]
  (let [a (vec a)
        b (vec b)]
    (and (= (count a) (count b))
         (every? true? (map mth/close-map? a b)))))

(defn- attr-equal?
  [attr origin-shape dest-shape]
  (let [origin-val (get origin-shape attr)
        dest-val   (get dest-shape attr)]
    (or (= origin-val dest-val)
        (cond
          ;; close-attrs? zips :points and would ignore a length mismatch
          (= :points attr)
          (and (= (count origin-val) (count dest-val))
               (gsh/close-attrs? attr origin-val dest-val))

          (contains? drifting-geometry-attrs attr)
          (gsh/close-attrs? attr origin-val dest-val)

          (and (= :content attr)
               (or (cfh/path-shape? origin-shape) (cfh/bool-shape? origin-shape)))
          (segment/content-close? origin-val dest-val)

          (= :position-data attr)
          (position-data-close? origin-val dest-val)

          :else
          false))))

;; Layout child attrs synced between main and copy (interactions are only kept on swap)
(def ^:private layout-child-attrs
  (disj ctk/swap-keep-attrs :interactions))

;; Attributes that place a shape in its parent
(def ^:private placement-attrs
  (conj layout-child-attrs :constraints-h :constraints-v :fixed-scroll))

;; Touched groups of the placement attributes and of the position
(def placement-groups
  (into #{:geometry-group} (map #(ctk/resolve-sync-group :rect %)) placement-attrs))

(defn enabled-shape?
  [id container]
  (or (empty? log-shape-ids)
      (nil? id)
      (let [ids (if container
                  (into #{} (cfh/get-parent-ids-seq-with-self (:objects container) id))
                  #{id})]
        (seq (set/intersection log-shape-ids ids)))))

(defmacro shape-log
  [level id container & params]
  `(when (enabled-shape? ~id ~container)
     (log/log ~level ~@params)))

(defn enabled-container?
  [id]
  (or (empty? log-container-ids)
      (log-container-ids id)))

(defmacro container-log
  [level id & params]
  `(when (enabled-container? ~id)
     (log/log ~level ~@params)))

(declare generate-sync-container)
(declare asset-user?)

(declare generate-sync-shape-direct)
(declare sync-copy-recursive)
(declare update-main-recursive)
(declare mark-local-changes)

(declare compare-children)
(declare reorder-copy-children)
(declare add-shape-to-instance)
(declare add-shape-to-main)
(declare axes-matrix)
(declare layout-skewed?)
(declare unskewed)
(declare remove-shape)
(declare move-shape)
(declare reorder-main-children)
(declare set-touched)
(declare change-remote-synced)
(declare update-attrs)
(declare update-grid-main-attrs)
(declare update-grid-copy-attrs)
(declare update-layout-children)
(declare update-swapped-placement)
(declare orient-like)
(declare reposition-shape)

(defn pretty-file
  [file-id libraries current-file-id]
  (if (= file-id current-file-id)
    "<local>"
    (str "<" (get-in libraries [file-id :name]) ">")))

(defn pretty-uuid
  [uuid]
  (let [uuid-str (str uuid)]
    (subs uuid-str (- (count uuid-str) 6))))

(defn swap-content-root-id
  "The id of the main root a swapped head takes its content from."
  [libraries shape]
  (:main-instance-id (ctf/get-component libraries
                                        (:component-file shape)
                                        (:component-id shape)
                                        :include-deleted? true)))

;; ---- General library synchronization functions ----

(defn- generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the given file,
  that use assets of the given type in the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized.

  If early-return? is true, stops as soon as the first change is generated."
  [changes file-id asset-type asset-id library-id libraries current-file-id early-return?]
  (assert (contains? #{:colors :components :typographies} asset-type))
  (assert (or (nil? asset-id) (uuid? asset-id)))
  (assert (uuid? file-id))
  (assert (uuid? library-id))

  (container-log :info asset-id
                 :msg "Sync file with library"
                 :asset-type asset-type
                 :asset-id asset-id
                 :file (pretty-file file-id libraries current-file-id)
                 :library (pretty-file library-id libraries current-file-id))

  (let [file (get-in libraries [file-id :data])]
    (loop [containers (ctf/object-containers-seq file)
           changes    changes]
      (let [container (first containers)]
        (if (or (nil? container)
                (and early-return? (seq (:redo-changes changes))))
          changes
          (recur (next containers)
                 (pcb/concat-changes
                  changes
                  (generate-sync-container (pcb/empty-changes nil)
                                           asset-type
                                           asset-id
                                           library-id
                                           container
                                           libraries
                                           current-file-id))))))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container (a page
  or a component) that use assets of the given type in the given library."
  [changes asset-type asset-id library-id container libraries current-file-id]

  (if (cfh/page? container)
    (container-log :debug (:id container) :msg "Sync page in local file" :page-id (:id container))
    (container-log :debug (:id container) :msg "Sync component in local library" :component-id (:id container)))

  (let [objects       (:objects container)
        asset-user?   (asset-user? asset-type asset-id library-id)
        ;; Deepest first, so a copy places its swapped children after their own sync
        linked-shapes (->> (persistent!
                            (reduce-kv (fn [shapes _ shape]
                                         (cond-> shapes (asset-user? shape) (conj! shape)))
                                       (transient [])
                                       objects))
                           (sort-by #(- (count (cfh/get-parent-ids objects (:id %))))))]
    (loop [shapes (seq linked-shapes)
           changes (-> changes
                       (pcb/with-container container)
                       (pcb/with-objects objects))]
      (if-let [shape (first shapes)]
        (recur (next shapes)
               (let [changes  (pcb/apply-changes-local changes)
                     objects' (pcb/get-objects changes)
                     shape'   (get objects' (:id shape))]
                 ;; An earlier change of this loop may have deleted the shape (the
                 ;; sync of a copy removes the nested copies its main removed);
                 ;; then it is skipped.
                 (if shape'
                   (case asset-type
                     :components   (generate-sync-shape-direct changes
                                                               (get libraries current-file-id)
                                                               libraries
                                                               (assoc container :objects objects')
                                                               (:id shape')
                                                               false)
                     :colors       (clla/generate-sync-colors changes library-id shape' libraries)
                     :typographies (clla/generate-sync-typographies changes library-id (assoc container :objects objects')
                                                                    shape' libraries))
                   changes)))
        changes))))

(defn- asset-user?
  "A predicate of the shapes that use the asset `asset-id` of type
  `asset-type` in the library `library-id`, or any asset of that type in it
  when `asset-id` is nil."
  [asset-type asset-id library-id]
  (case asset-type
    :components   (if (nil? asset-id)
                    #(ctk/uses-library-components? % library-id)
                    #(ctk/instance-of? % library-id asset-id))
    :colors       (if (nil? asset-id)
                    #(cts/uses-library-colors? % library-id)
                    #(cts/uses-library-color? % library-id asset-id))
    :typographies (if (nil? asset-id)
                    #(cty/uses-library-typographies? % library-id)
                    #(cty/uses-library-typography? % library-id asset-id))))

;; ---- Component synchronization ----

;; A copy follows its near main: sync and reset write the main's values into
;; the copy (`sync-copy-recursive`), and update main writes the copy's values
;; into the main (`update-main-recursive`). What each operation changes, and
;; which touched groups it sets or clears, is in the component sync contract
;; (`.serena/memories/common/component-sync-contract.md`).

(defn- find-main-container
  "Find the container that has the main shape.

  When walking up through nested component copies, each intermediate component
  may live in a different library/file. We resolve the right file-data for each
  component via `ctf/head-component` so we can locate components that span
  multiple libraries."
  [container-inst shape-inst shape-main library file libraries component]
  (loop [shape-inst' shape-inst
         library'    library
         component'  component]
    (let [container (ctf/get-component-container library' component')]
      (if (some? (ctn/get-shape container (:id shape-main)))
        container
        (let [parent      (ctn/get-shape container-inst (:parent-id shape-inst'))
              shape-inst' (ctn/get-head-shape (:objects container-inst) parent)]
          (when-let [[next-data component'] (when (some? shape-inst')
                                              (ctf/head-component file libraries shape-inst'
                                                                  :include-deleted? true))]
            (recur shape-inst' next-data component')))))))

(defn- follow-parent-modifiers
  "Modifiers that make `child` follow the change of its parent from
  `parent-before` to `parent-after`, through the child constraints."
  [child parent-before parent-after]
  (let [sr-before (:selrect parent-before)
        sr-after  (:selrect parent-after)
        modifiers (-> (ctm/empty)
                      (ctm/resize (gpt/point (/ (:width sr-after) (max 0.01 (:width sr-before)))
                                             (/ (:height sr-after) (max 0.01 (:height sr-before))))
                                  (first (:points parent-before))
                                  (:transform parent-before)
                                  (:transform-inverse parent-before))
                      (ctm/move (gpt/subtract (first (:points parent-after))
                                              (first (:points parent-before)))))]
    ;; A move doesn't need the constraints
    (if (or (ctm/empty? modifiers) (ctm/only-move? modifiers))
      modifiers
      (gsh/calc-child-modifiers parent-before child modifiers false
                                (:points child)
                                (:points parent-before)
                                (delay (:points parent-after))))))

(defn- follow-parent-modif-tree
  "Modifiers that make `child` and its children follow the change of the
  parent of `child` from `parent-before` to `parent-after`, each one through
  its own constraints."
  [objects child parent-before parent-after]
  (let [modifiers (follow-parent-modifiers child parent-before parent-after)]
    (if (ctm/empty? modifiers)
      {}
      (gm/set-objects-modifiers {(:id child) {:modifiers modifiers}}
                                (select-keys objects (cfh/get-children-ids-with-self objects (:id child)))))))

(defn resize-with-parent
  "Resizes `shape` the way its constraints do when its parent changes from
  `parent-before` to `parent-after`, keeping its top-left."
  [shape parent-before parent-after]
  (let [modifiers (follow-parent-modifiers shape parent-before parent-after)]
    (if (ctm/empty? modifiers)
      shape
      (let [shape' (gsh/transform-shape shape modifiers)]
        (gsh/move shape' (gpt/subtract (grc/rect->point (:selrect shape))
                                       (grc/rect->point (:selrect shape'))))))))

(defn- update-geometry-untouched
  "Applies `update-fn` to the shapes without marking them touched."
  [changes ids update-fn container]
  (pcb/concat-changes
   changes
   (-> (pcb/empty-changes)
       (pcb/with-container container)
       (pcb/with-objects (:objects container))
       (pcb/update-shapes ids update-fn {:ignore-touched true}))))

(defn generate-follow-parent
  "Makes `child` and its children follow the change of the parent of `child`
  from `parent-before` to `parent-after`, each one through its own
  constraints, without marking them touched. `delta-fn`, if given, receives
  `child` after that and returns the offset to move the subtree by."
  [changes container child parent-before parent-after delta-fn]
  (let [objects    (:objects container)
        modif-tree (follow-parent-modif-tree objects child parent-before parent-after)
        follow     #(gsh/transform-shape % (dm/get-in modif-tree [(:id %) :modifiers]))
        delta      (if delta-fn (delta-fn (follow child)) (gpt/point 0 0))]
    (cond-> changes
      (or (seq modif-tree) (not (gpt/almost-zero? delta)))
      (update-geometry-untouched (cfh/get-children-ids-with-self objects (:id child))
                                 #(gsh/move (follow %) delta)
                                 container))))

(defn- main-in-parent-orientation
  "Place a content main in the orientation of a swapped head, or compose
   the placement of an ordinary parent that survived detach."
  [main-container shape-main container shape-inst reset?]
  (let [parent (ctn/get-shape container (:parent-id shape-inst))
        main-parent (get-in main-container [:objects (:parent-id shape-main)])
        swapped? (and (not reset?) (ctk/swapped? shape-inst))
        origin (if swapped? shape-main main-parent)
        ;; a head that the layout skews takes the turn it skews
        target (if swapped?
                 (cond-> shape-inst (layout-skewed? (:objects container) shape-inst) (unskewed))
                 parent)
        ;; the axes only: a tiny shape's transform carries its rounding
        relative (gmt/multiply (axes-matrix target) (gmt/inverse (axes-matrix origin)))]
    (if (or (nil? target) (and (:shape-ref parent) (not reset?) (not swapped?))
            (gmt/unit? relative))
      main-container
      (let [transform (gmt/transform-in (gsh/shape->center shape-main) relative)
            ;; a mirror negates the rotation before the turn adds to it
            angle (- (or (:rotation target) 0)
                     (or (some-> origin (gsh/apply-transform transform) :rotation) 0))
            flip (fn [shape key]
                   (cond-> (get shape key)
                     (not= (boolean (get origin key)) (boolean (get target key))) not))
            turn (fn [shape]
                   (-> shape
                       (assoc-in [:modifiers :rotation] angle)
                       (gsh/apply-transform transform)
                       (dissoc :modifiers)
                       (assoc :flip-x (flip shape :flip-x)
                              :flip-y (flip shape :flip-y))))]
        (update main-container :objects
                (fn [objects]
                  (reduce #(update %1 %2 turn) objects
                          (cfh/get-children-ids-with-self objects (:id shape-main)))))))))

(defn reassign-parent-grid-cells
  "`changes` with the cells of the grid that holds the copy head `id`
   assigned again when they take the head in or out of the grid's flow, as
   showing or hiding a child does."
  [changes container id]
  (let [before    (:objects container)
        parent-id (:parent-id (get before id))]
    (if (ctsl/grid-layout? before parent-id)
      (let [changes (pcb/apply-changes-local changes)
            after   (pcb/get-objects changes)]
        (if (and (contains? after id)
                 (not= (ctsl/position-absolute? before id) (ctsl/position-absolute? after id)))
          (-> changes
              (pcb/update-shapes [parent-id] ctsl/assign-cells {:with-objects? true :ignore-touched true})
              (pcb/reorder-grid-children [parent-id]))
          changes))
      changes)))

(defn generate-sync-shape-direct
  "Generate changes to synchronize one shape that is the root of a component
  instance, and all its children, from the given component. With
  `subtree-id`, only that shape of the copy and its children are synced."
  ([changes file libraries container shape-id reset?]
   (generate-sync-shape-direct changes file libraries container shape-id reset? nil))
  ([changes file libraries container shape-id reset? subtree-id]
   (shape-log :debug shape-id container
              :msg "Sync shape direct" :shape-inst (str shape-id) :reset? reset?)
   (let [changes    (if (pcb/get-objects changes)
                      changes
                      (-> changes
                          (pcb/with-container container)
                          (pcb/with-objects (:objects container))))
         shape-inst (ctn/get-shape container shape-id)
         library    (dm/get-in libraries [(:component-file shape-inst) :data])
         component  (ctkl/get-component library (:component-id shape-inst) true)]
     (if (and (ctk/in-component-copy? shape-inst)
              (or (ctf/direct-copy? shape-inst component container nil libraries) reset?)) ; In a normal sync, we don't want to sync remote mains, only direct/near
       (if component
         (let [shape-main (if reset?
                            ;; the reset is against the ref-shape, not against the original shape of the component
                            (ctf/find-ref-shape file container libraries shape-inst :include-deleted? true)
                            (ctf/get-content-ref-shape library component shape-inst))
               main-container (find-main-container container shape-inst shape-main
                                                   library file libraries component)
               main-container (main-in-parent-orientation main-container shape-main container shape-inst reset?)
               shape-main (ctn/get-shape main-container (:id shape-main))]
           (-> (sync-copy-recursive changes
                                    {:container      container
                                     :main-container main-container
                                     :component      component
                                     :library        library
                                     :file           file
                                     :libraries      libraries
                                     ;; Reset also places new children in the main's frame.
                                     :root-inst      (cond-> shape-inst reset? (dissoc :touched))
                                     :root-main      shape-main
                                     :reset?         reset?
                                     :subtree        subtree-id
                                     ;; the copy shapes from the root down to the subtree
                                     :path           (when subtree-id
                                                       (into #{subtree-id}
                                                             (cfh/get-parent-ids (:objects container) subtree-id)))}
                                    shape-inst
                                    shape-main)
               (cond-> (nil? subtree-id) (reassign-parent-grid-cells container shape-id))))

         ;; If the component is not found, because the master component has been
         ;; deleted or the library unlinked, do nothing.
         changes)
       changes))))

(def ^:private placement-geometry-attrs
  #{:x :y :width :height :selrect :points :transform :transform-inverse
    :rotation :flip-x :flip-y :content-scale})

(defn- same-placement?
  "Whether two shapes cover the same corners with the same transform, so
   they differ at most in how rotation and flips decompose it."
  [shape other]
  (let [corners   (:points other)
        transform (or (:transform shape) (gmt/matrix))
        other-tf  (or (:transform other) (gmt/matrix))]
    (and (= (count (:points shape)) (count corners))
         (every? (fn [point] (some #(gpt/close? point %) corners)) (:points shape))
         (every? #(mth/close? (get transform %) (get other-tf %)) [:a :b :c :d]))))

(defn generate-sync-transformed-placement
  "Settle copy descendants after a reflection and untouched swapped heads
   after a parent turn, in the transform's change group. Reflection can
   leave descendant flip flags in another decomposition, so it only
   re-decomposes shapes whose corners and transform it keeps; swapped
   content can have a different size from its position frame. Keep this
   correction within existing geometry: transforming a stale library copy
   must not accept pending content, style, size, flip or structural
   changes."
  [changes file libraries page before ids]
  (let [objects     (pcb/get-objects changes)
        orientation (fn [shape]
                      [(select-keys (or (:transform shape) (gmt/matrix)) [:a :b :c :d])
                       (:rotation shape) (boolean (:flip-x shape)) (boolean (:flip-y shape))])
        ;; Each root to settle, and whether its settlement may resize shapes.
        roots       (reduce (fn [roots [id resize?]] (update roots id #(or % resize?)))
                            (d/ordered-map)
                            (keep (fn [id]
                                    (let [shape (get objects id)
                                          root (when (:shape-ref shape) (ctn/get-component-shape objects shape))
                                          old (get before id)
                                          ;; a copy head, or a plain copy shape with children
                                          reflected? (and (some? root)
                                                          (or (:component-id shape) (seq (:shapes shape)))
                                                          (or (not= (boolean (:flip-x old)) (boolean (:flip-x shape)))
                                                              (not= (boolean (:flip-y old)) (boolean (:flip-y shape)))))]
                                      (cond
                                        (and (some? root)
                                             (ctk/swapped? shape)
                                             (not (ctk/touched-group? shape :geometry-group))
                                             (not= (orientation old) (orientation shape)))
                                        [(:id root) true]

                                        reflected?
                                        [(:id root) false])))
                                  ids))]
    (reduce (fn [changes [id resize?]]
              (let [objects   (pcb/get-objects changes)
                    page      (assoc page :objects objects)
                    file      (assoc-in file [:data :pages-index (:id page) :objects] objects)
                    container (ctn/make-container page :page)
                    changes   (pcb/with-container changes container)
                    proposal  (generate-sync-shape-direct changes file (assoc libraries (:id file) file)
                                                          container id false)
                    placed    (pcb/get-objects proposal)]
                (pcb/update-shapes
                 changes (cfh/get-children-ids-with-self objects id)
                 (fn [shape]
                   (let [expected (get placed (:id shape))
                         attrs (cond-> placement-geometry-attrs
                                 (= :path (:type shape)) (conj :content))]
                     (if (and (= (:type shape) (:type expected))
                              (or resize? (same-placement? shape expected)))
                       (merge shape (select-keys expected attrs))
                       shape)))
                 {:ignore-touched true})))
            changes roots)))

(defn- sync-copy-shape
  "Sync the copy shape `shape-inst` from `shape-main`, the main shape it
  pairs with. A reset also clears its overrides and its `:remote-synced`,
  as it follows its near main."
  [changes {:keys [container main-container root-inst root-main reset?]}
   shape-inst shape-main]
  (let [omit-touched? (not reset?)]
    (cond-> (update-attrs changes shape-inst shape-main root-inst root-main container omit-touched?)
      ;; the children refer to the children of `shape-main`, in the
      ;; container of the walk's main shapes
      (ctsl/any-layout? shape-main)
      (update-layout-children container shape-inst shape-main
                              #(ctn/get-shape main-container (:shape-ref %)) omit-touched?)

      reset?
      (-> (set-touched shape-inst container nil)
          (change-remote-synced shape-inst container nil)))))

(defn- in-main-frame
  "The copy shape `shape-inst` expressed in the frame of the main, as
  `reposition-shape` would place it from there: turned back by the roots'
  relative rotation and flips when the copy root has them, and moved by the
  roots' offset."
  [shape-inst root-inst root-main]
  (let [turn  (when (ctk/touched-group? root-inst :geometry-group)
                (orient-like root-inst root-main (grc/rect->center (:selrect root-inst))))
        shape (cond-> shape-inst (some? turn) turn)
        delta (gpt/subtract (gpt/point (-> root-main :selrect :x) (-> root-main :selrect :y))
                            (gpt/point (-> root-inst :selrect :x) (-> root-inst :selrect :y)))]
    (gsh/move shape delta)))

(declare flips)

(defn- close-geometry?
  "Whether `shape` and `other` have the same geometry: a bool keeps its
   corners in one order whatever its flips, so they count too."
  [shape other]
  (and (gsh/close-attrs? :selrect (:selrect shape) (:selrect other))
       (gsh/close-attrs? :points (:points shape) (:points other))
       (mth/close? (:content-scale shape 1) (:content-scale other 1))
       (or (not (#{:group :bool} (:type shape)))
           (= (flips shape) (flips other)))))

(defn- authored-constraints
  "The constraints that the copy shapes `ids` author, by the id of the main
  shape each one pairs with."
  [objects ids]
  (into {}
        (keep (fn [id]
                (let [shape (get objects id)]
                  (when (ctk/touched-group? shape :constraints-group)
                    [(:shape-ref shape) (d/without-nils (select-keys shape [:constraints-h :constraints-v]))]))))
        ids))

(defn- sized-like
  "`shape` resized to the size of `source` about its first corner."
  [shape source]
  (let [width  (-> source :selrect :width)
        height (-> source :selrect :height)]
    (if (and (mth/close? width (-> shape :selrect :width))
             (mth/close? height (-> shape :selrect :height)))
      shape
      (gsh/transform-shape shape (ctm/change-size shape width height)))))

(defn placed-like
  "The main `objects` with the subtree of `shape-main` placed like `placed`,
  a copy shape expressed in the main's frame: resized to its size, its
  children following through their constraints and layouts, then turned
  and moved with it as one piece. A child resizes through the constraints
  its copy authors (`authored`, by main shape id) instead of the main's."
  [objects shape-main placed authored]
  (let [scale   (/ (:content-scale placed 1) (:content-scale shape-main 1))
        width   (-> placed :selrect :width)
        height  (-> placed :selrect :height)
        objects (if (and (mth/close? width (-> shape-main :selrect :width))
                         (mth/close? height (-> shape-main :selrect :height)))
                  objects
                  (let [modif-tree (gm/set-objects-modifiers
                                    {(:id shape-main) {:modifiers (ctm/change-size shape-main width height)}}
                                    ;; the subtree alone, under the root: no layout of the main's
                                    ;; parent owns the copy's size
                                    (-> (reduce-kv #(d/update-when %1 %2 merge %3) objects authored)
                                        (update (:id shape-main) assoc :parent-id uuid/zero)))]
                    (reduce-kv (fn [objects id {:keys [modifiers]}]
                                 (d/update-when objects id gsh/transform-shape modifiers))
                               objects
                               modif-tree)))
        ids     (cfh/get-children-ids-with-self objects (:id shape-main))
        objects (if (mth/close? scale 1)
                  objects
                  (let [modifiers (ctm/scale-content (ctm/empty) scale)]
                    (reduce #(update %1 %2 gsh/transform-shape modifiers) objects ids)))
        resized (get objects (:id shape-main))
        turn    (orient-like resized placed (grc/rect->center (:selrect resized)))
        objects (cond-> objects
                  (some? turn) (as-> objects (reduce #(update %1 %2 turn) objects ids)))
        delta   (gpt/subtract (grc/rect->center (:selrect placed))
                              (grc/rect->center (:selrect (get objects (:id shape-main)))))]
    (reduce #(update %1 %2 gsh/move delta) objects ids)))

(defn axes-matrix
  "The directions of the axes of the transform of `shape`, without their
   lengths: the transform of a tiny shape scales them by its rounding."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        horizontal (gpt/unit (gpt/point a b))
        vertical   (gpt/unit (gpt/point c d))]
    (gmt/matrix (:x horizontal) (:y horizontal) (:x vertical) (:y vertical) 0 0)))

(defn- skewed?
  "Whether the axes of the transform of `shape` are not perpendicular."
  [shape]
  (let [{:keys [a b c d]} (axes-matrix shape)]
    (not (mth/close? (+ (* a c) (* b d)) 0 0.0001))))

(defn unskewed
  "`shape` with the transform that its rotation and flips give it, without
  the skew a layout gives a turned child that it resizes along its parent's
  axes. Of a turn before or after the flips, the one nearer its axes."
  [shape]
  (let [turn      (gmt/rotate-matrix (d/nilv (:rotation shape) 0))
        flips     (gmt/scale-matrix (gpt/point (if (:flip-x shape) -1 1) (if (:flip-y shape) -1 1)))
        axes      (axes-matrix shape)
        ;; how close the axes of `m` are to those of the shape
        closeness (fn [m] (+ (* (:a m) (:a axes)) (* (:b m) (:b axes))
                             (* (:c m) (:c axes)) (* (:d m) (:d axes))))
        transform (max-key closeness (gmt/multiply turn flips) (gmt/multiply flips turn))]
    (assoc shape :transform transform :transform-inverse (gmt/inverse transform))))

(defn- layout-skewed?
  "Whether the layout of its parent skews the copy shape `shape` of
  `objects`: it fills in a direction, it does not author its geometry, and
  the layout resizes it along the parent's axes."
  [objects shape]
  (and (not (ctk/touched-group? shape :geometry-group))
       (some #(= :fill (get shape %)) [:layout-item-h-sizing :layout-item-v-sizing])
       (ctsl/any-layout-immediate-child? objects shape)
       (not (ctsl/position-absolute? shape))
       (skewed? shape)))

(defn- sync-descendant-orientation
  "A parent turned or moved by sync carries its whole subtree, including
   geometry overrides. Resize remains the job of constraints, not matrix
   scale: a parent turned and resized at once resizes its subtree through
   their constraints first, then turns and moves it as one piece. A swapped
   head the resize gives another size authors that size."
  [changes container before]
  (let [after    (get (pcb/get-objects changes) (:id before))
        resized? (and after
                      (not (and (mth/close? (-> before :selrect :width) (-> after :selrect :width))
                                (mth/close? (-> before :selrect :height) (-> after :selrect :height)))))
        moved?   (and after (not resized?)
                      (not (gpt/close? (gsh/shape->center before) (gsh/shape->center after))))
        changed? (and after
                      (or moved?
                          resized?
                          (not (mth/close? (or (:rotation before) 0) (or (:rotation after) 0) 1e-10))
                          (not= (boolean (:flip-x before)) (boolean (:flip-x after)))
                          (not= (boolean (:flip-y before)) (boolean (:flip-y after)))))
        ids      (when changed? (cfh/get-children-ids (:objects container) (:id before)))]
    (cond
      ;; a group or bool takes its bounds from its children, which sync places
      ;; one by one: a move or resize of its bounds carries nothing, and it
      ;; fits them again
      (and (seq ids)
           (#{:group :bool} (:type before))
           (or (and (or moved? resized?)
                    (mth/close? (or (:rotation before) 0) (or (:rotation after) 0) 1e-10)
                    (= (boolean (:flip-x before)) (boolean (:flip-x after)))
                    (= (boolean (:flip-y before)) (boolean (:flip-y after))))
               ;; a masked group takes all its geometry from its mask
               (:masked-group before)))
      (let [refit {:type :reg-objects :shapes [(:id before)]}]
        (pcb/add-changes changes container [refit] [refit]))

      (seq ids)
      (let [orientation (fn [shape]
                          (let [matrix (axes-matrix shape)]
                            (assoc shape :transform matrix :transform-inverse (gmt/inverse matrix))))
            turn        (orient-like (orientation before) (orientation after) (gsh/shape->center before))
            delta       (gpt/subtract (gsh/shape->center after) (gsh/shape->center before))
            ;; the subtree as the sync left it, under the parent placed as
            ;; before the sync, its grid holding the children the sync left
            placed      (when resized?
                          (let [objects (pcb/get-objects changes)
                                parent  (cond-> (merge after (select-keys before placement-geometry-attrs))
                                          (ctsl/grid-layout? after) (ctsl/assign-cells objects))]
                            (placed-like (assoc objects (:id before) parent) before after {})))
            ;; untouched swapped heads the resize gives another size
            scaled      (when resized?
                          (into #{}
                                (filter #(let [shape (get (:objects container) %)
                                               moved (get placed %)]
                                           (and (ctk/swapped? shape)
                                                (not (ctk/touched-group? shape :geometry-group))
                                                (some? moved)
                                                (not (and (mth/close? (:width shape) (:width moved))
                                                          (mth/close? (:height shape) (:height moved)))))))
                                ids))]
        (pcb/update-shapes changes ids
                           (if resized?
                             #(cond-> (get placed (:id %) %)
                                (contains? scaled (:id %))
                                (update :touched ctk/set-touched-group :geometry-group))
                             #(cond-> % (some? turn) turn :always (gsh/move delta)))
                           {:ignore-touched true}))

      :else
      changes)))

(defn- constraint-sized-main
  "`shape-main` resized to the dimensions the constraints of `shape-inst`, a
   copy head synced as a root, tie to its plain parent board: they follow the
   board's resizes, which are not overrides, so sync keeps them. Nil when no
   dimension is tied."
  [container shape-inst shape-main]
  (let [parent  (ctn/get-shape container (:parent-id shape-inst))
        width?  (contains? #{:scale :leftright} (:constraints-h shape-inst))
        height? (contains? #{:scale :topbottom} (:constraints-v shape-inst))]
    (when (and (cfh/frame-shape? parent)
               (not (cfh/root? parent))
               (nil? (:shape-ref parent))
               (or width? height?))
      (let [width  (:width (:selrect (if width? shape-inst shape-main)))
            height (:height (:selrect (if height? shape-inst shape-main)))]
        (gsh/transform-shape shape-main (ctm/change-size shape-main width height))))))

(defn- constraint-kept?
  "Whether the copy shape `shape-inst` keeps the geometry it shows at sync:
   its copy authors a constraint that ties one of its dimensions to its copy
   parent. A constraint applies to the resizes after it, so sync does not
   resize the shape through it again. A swap reaching the shape from its
   main (`shape-main` shows another component) brings the new size."
  [container shape-inst shape-main]
  (and (ctk/touched-group? shape-inst :constraints-group)
       (= (:component-id shape-inst) (:component-id shape-main))
       (or (contains? #{:scale :leftright} (:constraints-h shape-inst))
           (contains? #{:scale :topbottom} (:constraints-v shape-inst)))
       (some? (:shape-ref (ctn/get-shape container (:parent-id shape-inst))))))

(defn- sync-copy-recursive
  "Sync the copy shape `shape-inst` from `shape-main`, the main shape it
  pairs with, then pair their children (`compare-children`) and walk each
  pair. A normal sync leaves the copy's overrides (touched groups) as they
  are and only places a swapped child; a reset (`:reset?`) overwrites the
  overrides and clears them. `ctx` holds what stays the same along the
  walk: the copy's `:container`, the `:main-container` of the main shapes,
  the `:component` and its `:library`, the sync's `:file` and
  `:libraries`, the roots `:root-inst` and `:root-main`, and `:reset?`.
  The main shapes of the children of a copy shape that keeps its own
  geometry are placed like it first, so they get their expected geometry.
  The children of a grid copy with its own cells follow the flow of those
  cells instead of the main's order. With a `:subtree` in `ctx`, the shapes
  in `:path` above it are only walked through, towards that subtree."
  [changes {:keys [container main-container component reset? path] :as ctx} shape-inst shape-main]
  (shape-log :debug (:id shape-inst) container
             :msg "Sync shape direct recursive"
             :shape-inst (str (:name shape-inst) " " (pretty-uuid (:id shape-inst)))
             :component (:name component))
  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [;; a copy shape that keeps its own geometry passes it on to its
          ;; children, which follow it as they follow their main's
          shape-before   shape-inst
          ;; a grid copy with its own cells orders its children as they flow
          own-flow?      (and (not reset?) (ctsl/grid-layout? shape-inst)
                              (ctk/touched-group? shape-inst :layout-grid-cells))
          placed         (cond
                           (and (not reset?) (or (ctk/touched-group? shape-inst :geometry-group)
                                                 (constraint-kept? container shape-inst shape-main)))
                           (in-main-frame shape-inst (:root-inst ctx) (:root-main ctx))

                           (and (not reset?) (= (:id shape-inst) (:id (:root-inst ctx))))
                           (constraint-sized-main container shape-inst shape-main))
          main-container (cond-> main-container
                           (and (some? placed) (not (close-geometry? placed shape-main)))
                           (update :objects placed-like shape-main placed
                                   (authored-constraints (:objects container)
                                                         (cfh/get-children-ids (:objects container) (:id shape-inst)))))
          ;; a shape above the synced subtree only passes on its main's context
          passing?       (and (some? path) (not= (:id shape-inst) (:subtree ctx)))
          ctx            (cond-> (assoc ctx :main-container main-container)
                           (not passing?) (dissoc :path :subtree))
          shape-main     (get-in main-container [:objects (:id shape-main)])
          changes        (cond-> changes
                           (not passing?)
                           (-> (sync-copy-shape ctx shape-inst shape-main)
                               (sync-descendant-orientation container shape-inst)))
          container      (assoc container :objects (pcb/get-objects changes))
          ctx            (assoc ctx :container container)
          shape-inst     (ctn/get-shape container (:id shape-inst))
          children-inst  (vec (ctn/get-direct-children container shape-inst))
          children-main  (vec (ctn/get-direct-children main-container shape-main))]
      (if passing?
        (let [child-inst (d/seek #(contains? path (:id %)) children-inst)
              child-main (when child-inst (d/seek #(ctk/is-main-of? % child-inst) children-main))]
          (if (and child-main (not (and (not reset?) (ctk/swapped? child-inst)
                                        (ctk/instance-head? child-main))))
            (sync-copy-recursive changes ctx child-inst child-main)
            changes))
        (cond-> (-> changes
                    (compare-children
                     children-inst
                     children-main
                     {:pair?     ctk/is-main-of?
                      :on-pair   (fn [changes child-inst child-main]
                                   (if (and (not reset?) (ctk/swapped? child-inst)
                                            (ctk/instance-head? child-main))
                                     (update-swapped-placement changes ctx child-inst child-main)
                                     (sync-copy-recursive changes ctx child-inst child-main)))
                      :only-inst (fn [changes child-inst]
                                   (remove-shape changes child-inst container))
                      :only-main (fn [changes child-main]
                                   (add-shape-to-instance changes ctx shape-inst child-main
                                                          (d/index-of children-main child-main)))})
                    (cond-> (not own-flow?) (reorder-copy-children shape-inst children-main container)))

          (ctsl/grid-layout? shape-inst)
          (update-grid-copy-attrs shape-before shape-main (not reset?))

          (and own-flow? (= :page (:type container)))
          (pcb/reorder-grid-children [(:id shape-inst)])

          ;; cells only carry between grids, so a copy that sync leaves
          ;; without one drops its own
          (and (not (ctsl/grid-layout? shape-inst)) (seq (:layout-grid-cells shape-inst)))
          (pcb/update-shapes [(:id shape-inst)]
                             #(-> % (dissoc :layout-grid-cells) (update :touched disj :layout-grid-cells))
                             {:ignore-touched true}))))))

(defn generate-rename-component
  "Generate the changes for rename the component with the given id, in the current file library."
  [changes id new-name library-data]
  (let [[path name]   (cpn/split-group-name new-name)]
    (-> changes
        (pcb/with-library-data library-data)
        (pcb/update-component id #(assoc % :path path :name name)))))

(defn- settle-updated-main-placement
  [changes libraries component-file component]
  (let [file      (get libraries component-file)
        container (ctf/get-component-container (:data file) component)
        objects   (:objects container)
        ids       (filter #(ctk/swapped? (get objects %))
                          (cfh/get-children-ids objects (:main-instance-id component)))]
    (if (seq ids)
      (let [redo      (into [] (remove :local-change?) (:redo-changes changes))
            file      (update file :data cfc/process-changes redo false)
            page      (ctf/get-component-container (:data file) component)
            placement (-> (pcb/empty-changes)
                          (pcb/with-container page)
                          (pcb/with-objects (:objects page))
                          (generate-sync-transformed-placement file libraries page objects ids))]
        (pcb/concat-changes changes placement))
      changes)))

(defn update-main-nesting-loop?
  "Whether updating the main of the copy head `shape-id` of `container` from
   the copy would nest its component inside itself: the copy holds a copy of
   its component, or of a component whose main holds one, through every
   level."
  [libraries container shape-id]
  (let [component-id (:component-id (ctn/get-shape container shape-id))]
    (contains? (ctn/held-component-ids (cfh/get-children (:objects container) shape-id) libraries)
               component-id)))

(defn generate-author-constraint-places
  "A copy shape of `ids` whose constraint an update changed, from the shapes
   `before` to those of `changes`, keeps its place as an override where its
   board has another size than its main's along the changed axis: its place
   came through the old constraint, and the new one applies only to the
   resizes after it. A sync would otherwise place it through the new one."
  [changes container libraries before ids]
  (let [after (pcb/get-objects changes)
        authored
        (filter
         (fn [id]
           (let [old   (get before id)
                 shape (get after id)
                 frame (get after (:frame-id shape))
                 h?    (not= (:constraints-h old) (:constraints-h shape))
                 v?    (not= (:constraints-v old) (:constraints-v shape))]
             (when (and (or h? v?)
                        (:shape-ref shape)
                        (:shape-ref frame)
                        (not (ctk/touched-group? shape :geometry-group))
                        (not (and (ctsl/any-layout-immediate-child? after shape)
                                  (not (ctsl/position-absolute? shape)))))
               (when-let [main-frame (ctf/find-ref-shape nil (assoc container :objects after) libraries frame
                                                         :include-deleted? true)]
                 (or (and h? (not (mth/close? (:width frame) (:width main-frame) 0.01)))
                     (and v? (not (mth/close? (:height frame) (:height main-frame) 0.01))))))))
         ids)]
    (cond-> changes
      (seq authored)
      (pcb/update-shapes authored #(update % :touched ctk/set-touched-group :geometry-group)
                         {:ignore-touched true}))))

(defn generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children. An update that would nest
  the component inside itself changes nothing."
  [changes libraries container shape-id]
  (shape-log :debug shape-id container :msg "Sync shape inverse" :shape (str shape-id))
  (let [shape-inst     (ctn/get-shape container shape-id)
        library        (dm/get-in libraries [(:component-file shape-inst) :data])
        component      (when-not (update-main-nesting-loop? libraries container shape-id)
                         (ctkl/get-component library (:component-id shape-inst)))

        ;; a variant takes its name from its set, so the copy keeps its own
        keep-name?     (ctk/is-variant? component)

        ;; The component name is normalized (path separators with one space at
        ;; each side), so the name synced to the main instance root must be
        ;; normalized too. Otherwise the root would not match the component's
        ;; path+name and the file would fail :invalid-main-instance-name.
        rename?        (and (some? component)
                            (not keep-name?)
                            (contains? (:touched shape-inst) :name-group))
        original-name  (:name shape-inst)
        clean-name     (when rename?
                         (cpn/clean-path original-name))
        shape-inst     (cond-> shape-inst
                         (some? clean-name)
                         (assoc :name clean-name))

        ;; When the synced shape is itself the component's main instance root,
        ;; `find-remote-shape` returns nil and the recursive sync never reaches
        ;; it, so it must be renamed explicitly to keep it matching the
        ;; component's path+name.
        main-root?     (and (some? component)
                            (= (:id shape-inst) (:main-instance-id component)))

        changes        (cond-> changes
                         (some? clean-name)
                         (generate-rename-component (:component-id shape-inst) clean-name library)

                         (and (some? clean-name)
                              main-root?
                              (not= clean-name original-name))
                         (pcb/add-changes container
                                          [{:type :mod-obj
                                            :id (:id shape-inst)
                                            :local-change? true
                                            :operations [{:type :set :attr :name :val clean-name}]}]
                                          [{:type :mod-obj
                                            :id (:id shape-inst)
                                            :local-change? true
                                            :operations [{:type :set :attr :name :val original-name}]}]))]

    (if component
      (let [synced-ids     (volatile! #{})
            main-container (ctf/get-component-container library component)
            root-main      (ctf/get-component-root library component)
            center         (grc/rect->center (:selrect shape-inst))]
        (-> (update-main-recursive changes
                                   {:container      container
                                    :main-container main-container
                                    :component      component
                                    :libraries      libraries
                                    :root-inst      shape-inst
                                    :root-main      root-main
                                    :keep-name?     keep-name?
                                    ;; An orientation the copy root inherits places it
                                    ;; and is not pushed into the main. Sync composes a
                                    ;; root with its parent's turn; a top copy root that
                                    ;; does not author its geometry inherits all of it.
                                    :inherited-turn (if (and (:component-root shape-inst)
                                                             (not (ctk/touched-group? shape-inst :geometry-group)))
                                                      (orient-like shape-inst root-main center)
                                                      (when-let [parent (ctn/get-shape container (:parent-id shape-inst))]
                                                        (orient-like parent
                                                                     (or (ctn/get-shape main-container (:parent-id root-main)) {})
                                                                     center)))
                                    :remote-synced  (when-not (:component-root shape-inst) true)
                                    :synced-ids     synced-ids}
                                   shape-inst
                                   (ctf/find-remote-shape container libraries shape-inst))
            (mark-local-changes @synced-ids)
            (settle-updated-main-placement libraries (:component-file shape-inst) component)))
      changes)))

(defn split-local-changes
  "`changes` of an update main (`generate-sync-shape-inverse`) as `[local
  main]`: the changes of the copy, in the current file, and those of the
  main, which may live in a library; both without the mark that tells them
  apart."
  [changes]
  (let [part (fn [pred]
               (let [xf (comp (filter pred) (map #(dissoc % :local-change?)))]
                 (-> changes
                     (update :redo-changes #(into [] xf %))
                     (update :undo-changes #(into [] xf %)))))]
    [(part :local-change?) (part (complement :local-change?))]))

(defn- kept-geometry?
  "Whether the copy shape `shape-inst` shows a geometry that sync kept for
   it instead of one it authors: its own constraint keeps its size
   (`constraint-kept?`), or it follows an ancestor that does (`kept?`)."
  [container shape-inst shape-main kept?]
  (and (not (ctk/touched-group? shape-inst :geometry-group))
       (or kept? (constraint-kept? container shape-inst shape-main))))

(defn- with-main-geometry
  "`shape-inst` with the geometry of `shape-main`, its main shape, placed in
   the copy's frame."
  [shape-inst shape-main root-inst root-main]
  (let [attrs (cond-> placement-geometry-attrs
                (= :path (:type shape-inst)) (conj :content))]
    (merge shape-inst (select-keys (reposition-shape shape-main root-main root-inst) attrs))))

(defn- set-sizing
  "Set the layout `sizing` of `shape` of `container`, with its undo."
  [changes container shape sizing]
  (let [operations (fn [values]
                     (mapv (fn [attr] {:type :set :attr attr :val (get values attr) :ignore-touched true})
                           (keys sizing)))]
    (pcb/add-changes changes container
                     [{:type :mod-obj :id (:id shape) :operations (operations sizing)}]
                     [{:type :mod-obj :id (:id shape) :operations (operations shape)}])))

(defn- pushed-size-sizing
  "The layout sizing that `shape-main` of `objects` takes when an update main
   pushes the geometry of `source`, the copy shape `shape-inst` of
   `inst-objects`, into it: fixed on each axis that a layout sizes (fill or
   auto) and that the push resizes, or on both when it turns the shape in its
   parent, since a layout resizes a turned child. Only a copy that authors
   its geometry resizes or turns the main, and no copy authors a size its own
   layout gives it; the layout sizes the rest. A shape
   that is not a layout drops the sizing, which leaves it fixed as its copies
   are. A sizing the copy authors is pushed as it is."
  [objects shape-main inst-objects shape-inst source]
  (let [laid?    (and (ctk/touched-group? shape-inst :geometry-group)
                      (or (ctsl/any-layout-immediate-child? objects shape-main)
                          (ctsl/any-layout? shape-main)))
        ;; the axes of a shape relative to its parent's
        in-parent (fn [objects shape]
                    (cond-> (axes-matrix shape)
                      (cfh/frame-shape? (get objects (:parent-id shape)))
                      (gmt/multiply (gmt/inverse (axes-matrix (get objects (:parent-id shape)))))))
        turned?  (not (gmt/unit? (gmt/multiply (in-parent inst-objects shape-inst)
                                               (gmt/inverse (in-parent objects shape-main)))))
        ;; whether the copy's own layout gives it its size on an axis
        copy-sized? (fn [sizing]
                      (let [value (get shape-inst sizing)]
                        (or (and (contains? #{:fill :auto} value)
                                 (ctsl/any-layout-immediate-child? inst-objects shape-inst)
                                 (not (ctsl/position-absolute? shape-inst)))
                            (and (= :auto value) (ctsl/any-layout? shape-inst)))))
        fix?     (fn [sizing size]
                   (and laid?
                        (not (ctk/touched-group? shape-inst sizing))
                        (contains? #{:fill :auto} (get shape-main sizing))
                        ;; no layout fills a shape the push leaves absolute
                        (not (and (= :fill (get shape-main sizing))
                                  (ctsl/position-absolute? shape-inst)))
                        (or turned?
                            (and (not (copy-sized? sizing))
                                 (not (mth/close? (get source size) (get shape-main size)))))))
        fixed    (when (ctsl/any-layout? shape-main) :fix)]
    (cond-> {}
      (fix? :layout-item-h-sizing :width) (assoc :layout-item-h-sizing fixed)
      (fix? :layout-item-v-sizing :height) (assoc :layout-item-v-sizing fixed))))

(defn- update-main-shape
  "Push the copy shape `shape-inst` into `shape-main`, the main shape it
  pairs with: the copy's values, overrides included, go into the main, and
  its touched groups move there (the copy keeps a swap). A geometry that
  sync kept for the copy (`:kept?`) is not the copy's, so the main keeps
  its own. Only a layout parent pushes the layout child attributes of its
  children; under any other parent, the copy keeps those it authored."
  [changes {:keys [container main-container root-inst root-main remote-synced inherited-turn kept? keep-name?]} shape-inst shape-main]
  (let [;; the root of a variant keeps its name, and the copy its override of it
        keep-name? (and keep-name? (= (:id shape-inst) (:id root-inst)))
        inst-touched (cond-> (:touched shape-inst) keep-name? (disj :name-group))
        source (cond-> shape-inst
                 keep-name? (assoc :name (:name shape-main))
                 (some? inherited-turn) inherited-turn
                 kept? (with-main-geometry shape-main
                         (cond-> root-inst (some? inherited-turn) inherited-turn)
                         root-main))
        ;; the axes a layout sized that take the pushed size, fixed as a resize fixes them
        fixed (when-not kept? (pushed-size-sizing (:objects main-container) shape-main
                                                  (:objects container) shape-inst source))
        ;; the layout child groups the copy authored that no layout parent pushes
        kept-items (when (and (not= (:id shape-inst) (:id root-inst))
                              (not (ctsl/any-layout? (:objects main-container) (:parent-id shape-main))))
                     (set/intersection inst-touched
                                       (into #{} (map #(ctk/resolve-sync-group (:type shape-inst) %))
                                             layout-child-attrs)))]
    (cond-> (-> changes
                (update-attrs shape-main
                              source
                              root-main
                              (cond-> root-inst (some? inherited-turn) inherited-turn)
                              main-container false)
                ;; the copy keeps the sizing it pushed
                (cond-> (seq fixed)
                  (-> (set-sizing main-container shape-main fixed)
                      (set-sizing container shape-inst fixed)))
                ;; the copy keeps its swap and the overrides it does not push,
                ;; and the main takes the others
                (set-touched shape-inst container
                             (not-empty (cond-> (set kept-items)
                                          (ctk/swapped? shape-inst) (conj :swap-group)
                                          (and keep-name? (ctk/touched-group? shape-inst :name-group))
                                          (conj :name-group))))
                ;; A previous update is not a reset of the main's authored
                ;; overrides. Repeating it must retain the groups it pushed.
                (set-touched shape-main main-container
                             (set/union (:touched shape-main) (set/difference inst-touched kept-items))))
      (ctsl/any-layout? shape-main)
      (update-layout-children main-container shape-main shape-inst
                              #(ctf/get-shape-in-copy container % shape-inst) false)

      :always
      (change-remote-synced shape-inst container remote-synced))))

(defn- update-main-recursive
  "Push the copy shape `shape-inst` into `shape-main`, the main shape it
  pairs with, then pair their children (`compare-children`) and walk each
  pair: the update main of a copy. A swapped child does not pair, so the
  main child it replaces is removed and the swapped child is added in its
  place. `ctx` is that of `sync-copy-recursive`, with the `:libraries`, the
  `:remote-synced` the copy shapes take (true when it starts below the copy
  root), and `:synced-ids`, which collects the synced copy shapes."
  [changes {:keys [container main-container component synced-ids] :as ctx} shape-inst shape-main]
  (shape-log :debug (:id shape-inst) container
             :msg "Sync shape inverse recursive"
             :shape-inst (str (:name shape-inst) " " (pretty-uuid (:id shape-inst)))
             :component (:name component))
  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [children-inst (vec (ctn/get-direct-children container shape-inst))
          children-main (vec (ctn/get-direct-children main-container shape-main))
          ;; the main shapes added for copy children, by copy child id
          added         (volatile! {})
          kept?         (kept-geometry? container shape-inst shape-main (:kept? ctx))
          ctx           (assoc ctx :kept? kept?)]
      (vswap! synced-ids conj (:id shape-inst))
      (cond-> (-> changes
                  (update-main-shape ctx shape-inst shape-main)
                  (compare-children
                   children-inst
                   children-main
                   {:pair?     (fn [child-main child-inst]
                                 (and (ctk/is-main-of? child-main child-inst)
                                      (not (ctk/swapped? child-inst))))
                    :on-pair   (fn [changes child-inst child-main]
                                 (update-main-recursive changes ctx child-inst child-main))
                    :only-inst (fn [changes child-inst]
                                 (add-shape-to-main changes (assoc ctx :added added) shape-main child-inst
                                                    (d/index-of children-inst child-inst)))
                    :only-main (fn [changes child-main]
                                 (remove-shape changes child-main main-container))
                    :inverse?  true})
                  (reorder-main-children shape-main children-inst @added main-container))

        (ctsl/grid-layout? shape-main)
        (update-grid-main-attrs shape-main shape-inst children-inst @added main-container)))))

(defn- reorder-main-children
  "Order the children of `shape-main` as the copy's `children-inst`, each by
  the main shape it pairs with or the one `added` for it, in one change that
  its undo reverts."
  [changes shape-main children-inst added main-container]
  (let [order (into [] (keep #(get added (:id %) (:shape-ref %))) children-inst)
        reorder (fn [shapes]
                  {:type :reorder-children
                   :parent-id (:id shape-main)
                   :shapes shapes
                   :allow-altering-copies true})]
    (if (= order (vec (:shapes shape-main)))
      changes
      (pcb/add-changes changes main-container
                       [(reorder order)]
                       [(reorder (vec (:shapes shape-main)))]))))

(defn- mark-local-changes
  "Mark as `:local-change?` the changes of the copy shapes `ids`. The inverse
  sync may update a component of a remote library, so the frontend commits
  the copy's changes and the main's apart."
  [changes ids]
  (let [check-local (fn [change]
                      (cond-> change
                        (contains? ids (:id change))
                        (assoc :local-change? true)))]
    (-> changes
        (update :redo-changes #(mapv check-local %))
        (update :undo-changes #(apply list (map check-local %))))))


;; ---- Operation generation helpers ----

(defn- index-positions
  "A map of each value of `key-fn` among the elements of the vector `v` to
  their positions, in order."
  [key-fn v]
  (reduce-kv (fn [index position x]
               (update index (key-fn x) (fnil conj []) position))
             {}
             v))

(defn- compare-children
  "Pair the children of a copy shape with the children of its main and call
  the callback of each case:
    - `on-pair` for a copy child and the main child it pairs with
      (`(pair? child-main child-inst)`);
    - `only-inst` and `only-main` for a child that pairs with none;
    - `moved` (optional), after `on-pair`, for a pair found out of place.
      The main's order wins, so the copy child is looked up; with
      `inverse?` the copy's order wins, and the main child is looked up.

  The children are walked in order, side by side. A child out of place is
  looked up by its reference (`index-positions`), not searched, so the walk
  takes linear time; a child taken out of order is skipped when the walk
  reaches it."
  [changes children-inst children-main {:keys [pair? on-pair only-inst only-main moved inverse?]}]
  (let [insts        (vec children-inst)
        mains        (vec children-main)
        insts-by-ref (index-positions :shape-ref insts)
        mains-by-id  (index-positions :id mains)

        ;; the first position from `position` on whose child was not taken
        ;; out of order
        next-position
        (fn [v position taken]
          (loop [position position]
            (if (and (< position (count v))
                     (contains? taken (:id (nth v position))))
              (recur (inc position))
              position)))

        ;; the first child from `position` on, with `key` in `index`, not
        ;; taken and accepted by `pred`
        find-child
        (fn [index key v position taken pred]
          (some (fn [p]
                  (let [child (nth v p)]
                    (when (and (>= p position)
                               (not (contains? taken (:id child)))
                               (pred child))
                      child)))
                (get index key)))

        remaining
        (fn [v position taken]
          (remove #(contains? taken (:id %)) (subvec v (min position (count v)))))]

    (loop [i           0
           j           0
           taken-insts #{}
           taken-mains #{}
           changes     changes]
      (let [i          (next-position insts i taken-insts)
            j          (next-position mains j taken-mains)
            child-inst (get insts i)
            child-main (get mains j)]
        (cond
          (and (nil? child-inst) (nil? child-main))
          changes

          (nil? child-inst)
          (reduce only-main changes (remaining mains j taken-mains))

          (nil? child-main)
          (reduce only-inst changes (remaining insts i taken-insts))

          (pair? child-main child-inst)
          (recur (inc i) (inc j) taken-insts taken-mains
                 (on-pair changes child-inst child-main))

          :else
          (let [child-inst' (find-child insts-by-ref (:id child-main) insts i taken-insts
                                        #(pair? child-main %))
                child-main' (find-child mains-by-id (:shape-ref child-inst) mains j taken-mains
                                        #(pair? % child-inst))]
            (cond
              (nil? child-inst')
              (recur i (inc j) taken-insts taken-mains
                     (only-main changes child-main))

              (nil? child-main')
              (recur (inc i) j taken-insts taken-mains
                     (only-inst changes child-inst))

              inverse?
              (recur (inc i) j taken-insts (conj taken-mains (:id child-main'))
                     (cond-> (on-pair changes child-inst child-main')
                       moved (moved child-inst child-main')))

              :else
              (recur i (inc j) (conj taken-insts (:id child-inst')) taken-mains
                     (cond-> (on-pair changes child-inst' child-main)
                       moved (moved child-inst' child-main))))))))))

(defn- shape-label
  "The container and the shape, for the log."
  [shape container]
  (str (if (cfh/page? container) "[P " "[C ")
       (pretty-uuid (:id container)) "] "
       (:name shape)
       " "
       (pretty-uuid (:id shape))))

(defn- add-obj-change
  "The change that adds `shape` at `index` of its parent."
  [shape index]
  (cond-> {:type :add-obj
           :id (:id shape)
           :parent-id (:parent-id shape)
           :index index
           :ignore-touched true
           :obj shape}
    (:frame-id shape)
    (assoc :frame-id (:frame-id shape))))

(defn- del-obj-change
  [id]
  {:type :del-obj
   :id id
   :ignore-touched true})

(defn- add-shape-to-instance
  "Add to the copy shape `parent-inst`, at `index`, a copy of `child-main`,
  a child of the main shape it pairs with."
  [changes {:keys [container main-container root-inst root-main]}
   parent-inst child-main index]
  (shape-log :info (:id child-main) main-container
             :msg (str "ADD " (shape-label child-main container)))
  (let [all-parents      (into [(:id parent-inst)]
                               (cfh/get-parent-ids (:objects container) (:id parent-inst)))

        ;; a new shape is synced with its main shape
        update-new-shape (fn [new-shape original-shape]
                           (-> (reposition-shape new-shape root-main root-inst)
                               (assoc :shape-ref (:id original-shape))
                               (dissoc :touched)))

        [_ new-shapes _]
        (ctst/clone-shape child-main
                          (:id parent-inst)
                          (:objects main-container)
                          :update-new-shape update-new-shape
                          :dest-objects (:objects container))]

    (pcb/add-changes changes container
                     (conj (mapv #(add-obj-change % index) new-shapes)
                           {:type :reg-objects :shapes all-parents})
                     ;; the parents are recalculated after the shapes are removed
                     (cons {:type :reg-objects :shapes all-parents}
                           (map (comp del-obj-change :id) new-shapes)))))

(defn- rehead-pushed-swap
  "A pushed swap becomes a plain nested copy. Its old position's
   orientation and constraints become authored when they differ from its
   own main."
  [shape main container]
  (let [shape (-> shape
                  (assoc :shape-ref (:id main))
                  (ctk/remove-swap-group))
        main-container (when main
                         (main-in-parent-orientation (:container (meta main)) main
                                                     container shape false))
        expected (get-in main-container [:objects (:id main)])
        ;; a mirrored shape can store another rotation for the same axes
        turned?  (and expected
                      (not (gmt/unit? (gmt/multiply (axes-matrix expected)
                                                    (gmt/inverse (axes-matrix shape))))))]
    (cond-> shape
      turned?
      (update :touched ctk/set-touched-group :geometry-group)

      (and main (some #(not= (get shape %) (get main %)) [:constraints-h :constraints-v :fixed-scroll]))
      (update :touched ctk/set-touched-group :constraints-group))))

(defn- author-pushed-content
  "Taking a new content reference makes the pushed differences authored.
   Placement still follows the position, and existing overrides survive."
  [shape reference]
  (if (or (nil? reference) (ctk/swapped? shape))
    shape
    (let [text? (cfh/text-shape? shape)
          groups (into #{}
                       (keep (fn [attr]
                               (let [group (ctk/resolve-sync-group (:type shape) attr)]
                                 (when (and group
                                            (not (contains? placement-groups group))
                                            (not= attr :position-data)
                                            (not (and text? (= attr :content)))
                                            (not= (get shape attr) (get reference attr)))
                                   group))))
                       updatable-attrs)
          text-groups (when text?
                        (cond-> (txt/override-groups (:content reference) (:content shape))
                          (txt/drops-typography? (txt/text-overrides (:touched shape)))
                          (disj :text-content-typography)))
          groups (cond-> (into groups text-groups)
                   (seq text-groups) (conj :content-group))]
      (cond-> shape
        (seq groups) (update :touched (fnil into #{}) groups)))))

(defn- add-shape-to-main
  "Add to the main shape `parent-main`, at `index`, a copy of `child-inst`,
  a child of the copy shape that pairs with it; `child-inst` then refers to
  the new main shape."
  [changes {:keys [container main-container root-inst root-main libraries added synced-ids inherited-turn]}
   parent-main child-inst index]
  (shape-log :info (:id child-inst) container
             :msg (str "ADD " (shape-label child-inst main-container)))
  (let [all-parents  (into [(:id parent-main)]
                           (cfh/get-parent-ids (:objects main-container) (:id parent-main)))

        ;; A swapped head pushed to the main replaces the main child it refers to.
        ;; Inside a copy of the main it takes the position of that child;
        ;; elsewhere it is a plain copy of its own component.
        swap-in-copy?    (and (ctk/swapped? child-inst)
                              (seq (ctn/get-parent-copy-heads (:objects main-container) parent-main)))
        replaced         (when swap-in-copy?
                           (ctn/get-shape main-container (:shape-ref child-inst)))
        content-main     (when (and (ctk/swapped? child-inst) (not swap-in-copy?))
                           (ctf/find-content-ref-shape nil container libraries child-inst
                                                       :include-deleted? true
                                                       :with-context? true))
        ;; copy shapes in the main's orientation, without the turn the copy inherits
        in-main          (fn [shape]
                           (-> (cond-> shape (some? inherited-turn) inherited-turn)
                               (reposition-shape (cond-> root-inst (some? inherited-turn) inherited-turn)
                                                 root-main)))

        ;; The parent's inverse sync already pushed its geometry. Compare
        ;; against that frame, rather than its original container value.
        placement-container
        (cond-> main-container
          content-main
          (assoc-in [:objects (:id parent-main)]
                    (-> (ctn/get-shape container (:parent-id child-inst))
                        (in-main)
                        (assoc :id (:id parent-main)
                               :shape-ref (:shape-ref parent-main)))))

        ;; the layout child groups the copy authored under a parent with no
        ;; layout, which stay with the copy as an update of a pair keeps them
        kept-items (fn [shape]
                     (let [layout? (if (= (:id shape) (:id child-inst))
                                     (ctsl/any-layout? parent-main)
                                     (ctsl/any-layout? (:objects container) (:parent-id shape)))]
                       (when-not (or layout? (= (:id shape) (:id root-inst)))
                         (set/intersection (set (:touched shape))
                                           (into #{} (map #(ctk/resolve-sync-group (:type shape) %))
                                                 layout-child-attrs)))))

        update-new-shape (fn [new-shape original-shape]
                           (cond-> (in-main new-shape)
                             (and (= (:id original-shape) (:id child-inst))
                                  (ctk/swapped? child-inst)
                                  (not swap-in-copy?))
                             (rehead-pushed-swap content-main placement-container)

                             (seq (kept-items original-shape))
                             (update :touched #(not-empty (set/difference % (kept-items original-shape))))))

        ;; the copy now follows the main, swap included
        update-original-shape (fn [original-shape new-shape]
                                (-> original-shape
                                    (assoc :shape-ref (:id new-shape))
                                    (assoc :touched (not-empty (kept-items original-shape)))))

        [new-head new-shapes updated-shapes]
        (ctst/clone-shape child-inst
                          (:id parent-main)
                          (:objects container)
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape
                          :frame-id (if (cfh/frame-shape? parent-main)
                                      (:id parent-main)
                                      (:frame-id parent-main)))

        _ (some-> added (vswap! assoc (:id child-inst) (:id new-head)))
        ;; the copy shapes it relinks are changes of the copy's file
        _ (some-> synced-ids (vswap! into (map :id) updated-shapes))

        new-shapes
        (if swap-in-copy?
          (let [objects          (d/index-by :id new-shapes)
                position         (ctf/find-ref-shape nil main-container libraries replaced
                                                     :include-deleted? true
                                                     :with-context? true)
                take-position    (ctf/take-position nil libraries objects new-head (:shape-ref replaced) position)
                position-objects (get-in (meta position) [:container :objects])
                ;; the head takes the place the replaced child authored
                placement        (set/intersection (set (:touched replaced)) placement-groups)
                ;; a plain copy of the position has its size, unless the replaced child or the copy authored its geometry
                sized?           (fn [shape]
                                   (and (= (:id shape) (:id new-head)) position
                                        (not (ctk/swapped? shape))
                                        (not (contains? placement :geometry-group))
                                        (not (ctk/touched-group? shape :geometry-group))))
                positioned       (mapv (fn [shape]
                                         (let [shape (take-position shape)]
                                           (cond-> shape (sized? shape) (sized-like position))))
                                       new-shapes)
                by-id            (d/index-by :id positioned)
                head             (get by-id (:id new-head))
                ;; the position's subtree placed like a head that is a plain copy of it
                placed           (when (and position (not (ctk/swapped? head)))
                                   (placed-like position-objects position head
                                                (authored-constraints by-id (cfh/get-children-ids by-id (:id head)))))
                ;; the shapes that keep their pushed geometry: the head and the subtrees of those that author theirs or are swapped
                kept-ids         (into #{(:id head)}
                                       (comp (filter #(or (ctk/touched-group? % :geometry-group) (ctk/swapped? %)))
                                             (mapcat #(cfh/get-children-ids-with-self by-id (:id %))))
                                       positioned)
                follow-position  (fn [shape]
                                   (let [expected (get placed (:shape-ref shape))]
                                     (if (and expected
                                              (not (contains? kept-ids (:id shape)))
                                              (= (:type shape) (:type expected)))
                                       (merge shape (select-keys expected (cond-> placement-geometry-attrs
                                                                            (= :path (:type shape)) (conj :content))))
                                       shape)))]
            (mapv (fn [shape]
                    (let [shape (cond-> (follow-position shape)
                                  (and (= (:id shape) (:id new-head)) (seq placement))
                                  (update :touched (fnil into #{}) placement))]
                      (author-pushed-content shape (get position-objects (:shape-ref shape)))))
                  positioned))
          new-shapes)

        ;; the link of a copy shape with its main
        set-link
        (fn [shape]
          {:type :mod-obj
           :id (:id shape)
           :operations (mapv (fn [attr] {:type :set :attr attr :val (get shape attr)})
                             [:component-id :component-file :component-root :shape-ref :touched])})]

    (-> changes
        ;; each undo goes with its redo, and the undo list runs backwards:
        ;; the new shapes are deleted, children first, then the parents refit
        (pcb/add-changes main-container
                         (conj (mapv #(assoc (add-obj-change % index) :frame-id (:frame-id %)) new-shapes)
                               {:type :reg-objects :shapes all-parents})
                         (into [{:type :reg-objects :shapes all-parents}]
                               (map (comp del-obj-change :id))
                               new-shapes))
        (pcb/add-changes container
                         (map set-link updated-shapes)
                         (map #(set-link (ctn/get-shape container (:id %))) updated-shapes)))))

(defn- position-in-batch
  "The position of the shape `id` of `container` among the children of its
  parent, as the changes in `changes` so far leave them."
  [changes container id]
  (let [objects    (:objects container)
        parent-id  (:parent-id (get objects id))
        page?      (cfh/page? container)
        ;; a change on the objects of `container`
        in-container? (fn [change]
                        (= (:id container) (if page? (:page-id change) (:component-id change))))
        insert     (fn [shapes index ids]
                     (if (some? index)
                       (d/insert-at-index shapes index ids)
                       (into (filterv (complement (set ids)) shapes) ids)))
        children   (reduce (fn [shapes change]
                             (if-not (in-container? change)
                               shapes
                               (case (:type change)
                                 :del-obj          (filterv #(not= % (:id change)) shapes)
                                 :add-obj          (cond-> shapes
                                                     (= parent-id (:parent-id change))
                                                     (insert (:index change) [(:id change)]))
                                 :mov-objects      (let [moved (:shapes change)]
                                                     (if (= parent-id (:parent-id change))
                                                       (insert shapes (:index change) moved)
                                                       (filterv (complement (set moved)) shapes)))
                                 :reorder-children (cond-> shapes
                                                     (= parent-id (:parent-id change))
                                                     (-> (:shapes change) (vec)))
                                 shapes)))
                           (vec (get-in objects [parent-id :shapes]))
                           (:redo-changes changes))]
    (d/index-of children id)))

(defn- remove-shape
  "Delete `shape` of `container` with its children. Each undo restores a
  shape at the position it had when the changes so far deleted it, so
  siblings that one batch deletes come back in their order."
  [changes shape container]
  (shape-log :info (:id shape) container
             :msg (str "REMOVE-SHAPE " (shape-label shape container)))
  (let [objects (get container :objects)
        parents (cfh/get-parent-ids objects (:id shape))
        ;; from the bottom up
        ids     (reverse (into [(:id shape)] (cfh/get-children-ids objects (:id shape))))
        ;; the builder's objects, when it applies its changes as it goes
        local   (pcb/get-objects changes)]
    (pcb/add-changes changes container
                     (conj (mapv del-obj-change ids)
                           {:type :reg-objects :shapes (vec parents)})
                     ;; the parents are recalculated after the shapes are restored
                     (cons {:type :reg-objects :shapes (vec parents)}
                           (map #(add-obj-change (get objects %)
                                                 (or (cfh/get-position-on-parent local %)
                                                     (if (= % (:id shape))
                                                       (position-in-batch changes container %)
                                                       (cfh/get-position-on-parent objects %))))
                                ids)))))

(defn- longest-increasing
  "The set of the elements of `xs` that form the longest run, not
  necessarily contiguous, whose `key-fn` values increase."
  [key-fn xs]
  (let [xs (vec xs)
        ;; tails[l]: position of the smallest last key of a run of length l+1
        [tails prev]
        (reduce (fn [[tails prev] i]
                  (let [k (key-fn (nth xs i))
                        l (loop [lo 0 hi (count tails)]
                            (if (< lo hi)
                              (let [mid (quot (+ lo hi) 2)]
                                (if (< (key-fn (nth xs (nth tails mid))) k)
                                  (recur (inc mid) hi)
                                  (recur lo mid)))
                              lo))]
                    [(assoc tails l i)
                     (assoc prev i (when (pos? l) (nth tails (dec l))))]))
                [[] {}]
                (range (count xs)))]
    (loop [i   (peek tails)
           run #{}]
      (if (some? i)
        (recur (get prev i) (conj run (nth xs i)))
        run))))

(defn- reorder-copy-children
  "Move the children of the copy shape `shape-inst`, as the changes so far
  left them, into the order of their main shapes among `children-main`,
  moving the fewest: the longest run already in order stays, and each other
  child goes right after the one before it in the main's order."
  [changes shape-inst children-main container]
  (let [main-index (into {} (map-indexed (fn [i child] [(:id child) i])) children-main)
        objects    (pcb/get-objects changes)
        current    (vec (get-in objects [(:id shape-inst) :shapes]))
        index-of   #(get main-index (get-in objects [% :shape-ref]) ##Inf)
        target     (vec (sort-by index-of current))]
    (if (= current target)
      changes
      (let [in-place (longest-increasing index-of current)]
        (first
         (reduce (fn [[changes order] [k id]]
                   (if (contains? in-place id)
                     [changes order]
                     (let [before  (when (pos? k) (nth target (dec k)))
                           after   (fn [order neighbour]
                                     (if (some? neighbour) (inc (d/index-of order neighbour)) 0))
                           from    (d/index-of order id)
                           order'  (d/insert-at-index order (after order before) [id])]
                       [(move-shape changes (get objects id)
                                    (after order' (when (pos? from) (nth order (dec from))))
                                    (after order before)
                                    container)
                        order'])))
                 [changes current]
                 (map-indexed vector target)))))))

(defn- move-shape
  [changes shape index-before index-after container]
  (shape-log :info (:id shape) container
             :msg (str "MOVE " (shape-label shape container) " " index-before " -> " index-after))
  (let [move-to (fn [index]
                  {:type :mov-objects
                   :parent-id (:parent-id shape)
                   :shapes [(:id shape)]
                   :index index
                   :ignore-touched true
                   :allow-altering-copies true})]
    (pcb/add-changes changes container [(move-to index-after)] [(move-to index-before)])))

(defn- set-touched
  "Set the touched groups of the copy shape `shape` to `touched`."
  [changes shape container touched]
  (if (nil? (:shape-ref shape))
    changes
    (do
      (shape-log :info (:id shape) container
                 :msg (str "SET-TOUCHED " (shape-label shape container))
                 :touched touched)
      (let [set-touched (fn [touched]
                          {:type :mod-obj
                           :id (:id shape)
                           :operations [{:type :set-touched :touched touched}]})]
        (pcb/add-changes changes container
                         [(set-touched touched)]
                         [(set-touched (:touched shape))])))))

(defn- change-remote-synced
  [changes shape container remote-synced?]
  (if (nil? (:shape-ref shape))
    changes
    (do
      (shape-log :info (:id shape) container
                 :msg (str "CHANGE-REMOTE-SYNCED? " (shape-label shape container))
                 :remote-synced remote-synced?)
      (let [set-remote-synced (fn [remote-synced]
                                {:type :mod-obj
                                 :id (:id shape)
                                 :operations [{:type :set-remote-synced
                                               :remote-synced remote-synced}]})]
        (pcb/add-changes changes container
                         [(set-remote-synced remote-synced?)]
                         [(set-remote-synced (:remote-synced shape))])))))

(defn- update-tokens
  "Token synchronization algorithm. Copy the applied tokens that have changed
   in the origin shape to the dest shape (applying or removing as necessary).

   Only the given token attributes are synced."
  [changes container dest-shape orig-shape token-attrs]
  (let [orig-tokens  (get orig-shape :applied-tokens {})
        dest-tokens  (get dest-shape :applied-tokens {})
        dest-tokens' (reduce (fn [dest-tokens' token-attr]
                               (let [orig-token (get orig-tokens token-attr)
                                     dest-token (get dest-tokens token-attr)]
                                 (if (= orig-token dest-token)
                                   dest-tokens'
                                   (if (nil? orig-token)
                                     (dissoc dest-tokens' token-attr)
                                     (assoc dest-tokens' token-attr orig-token)))))
                             dest-tokens
                             token-attrs)
        set-tokens   (fn [tokens]
                       {:type :mod-obj
                        :id (:id dest-shape)
                        :operations [{:type :set
                                      :attr :applied-tokens
                                      :val tokens
                                      :ignore-touched true}]})]
    (if (= dest-tokens dest-tokens')
      changes
      (pcb/add-changes changes container
                       [(set-tokens dest-tokens')]
                       [(set-tokens (:applied-tokens dest-shape))]))))

;; The token attributes of each attribute whose tokens sync; the
;; `layout-grid-cells` are not synced automatically.
(def ^:private token-attrs-by-sync-attr
  (into {}
        (comp (remove #(= :layout-grid-cells %))
              (map (juxt identity cto/shape-attr->token-attrs)))
        (keys ctk/sync-attrs)))

(defn generate-update-tokens
  "Copy into `dest-shape` the tokens `origin-shape` applies to the attributes
  it syncs: with `omit-touched?`, only those whose group is not in `touched`;
  with `valid-attrs`, only those attributes (all when nil). Nothing to copy
  or remove when neither shape applies tokens."
  [changes container dest-shape origin-shape touched omit-touched? valid-attrs]
  (if (and (empty? (:applied-tokens origin-shape))
           (empty? (:applied-tokens dest-shape)))
    changes
    (let [;; the styles of a text live in its content, so its tokens follow
          ;; the text override groups
          text?   (cfh/text-shape? dest-shape)
          touched (cond-> touched
                    text? (-> set (into (txt/text-overrides touched))))

          applied-tokens
          (reduce-kv (fn [applied-tokens attr token-attrs]
                       (let [sync-group (or (when text? (txt/style-unit-of attr))
                                            (ctk/resolve-sync-group (:type origin-shape) attr)
                                            (ctk/resolve-sync-group (:type dest-shape) attr))]
                         (if (and (or (not omit-touched?) (not (touched sync-group)))
                                  (or (empty? valid-attrs) (contains? valid-attrs attr)))
                           (into applied-tokens token-attrs)
                           applied-tokens)))
                     #{}
                     token-attrs-by-sync-attr)]
      (cond-> changes
        (seq applied-tokens)
        (update-tokens container dest-shape origin-shape applied-tokens)))))

(defn add-update-attr-changes
  "Add to `changes` the operations of `attr-operations` on `dest-shape`, and
  the update of its parents. The undo fits the shape itself too, when it is a
  group or bool: its children are undone before it, while it keeps the
  transform the change gave it."
  [changes dest-shape container roperations uoperations]
  (let [update-parents {:type :reg-objects
                        :shapes (cfh/get-parent-ids (:objects container) (:id dest-shape))}
        update-self    {:type :reg-objects
                        :shapes [(:id dest-shape)]}]
    (pcb/add-changes changes container
                     [{:type :mod-obj :id (:id dest-shape) :operations roperations}
                      update-parents]
                     ;; undone as the shape first, then itself and its parents
                     [update-self
                      {:type :mod-obj :id (:id dest-shape) :operations (vec uoperations)}])))


(defn- add-update-attr-operations
  [attr dest-shape roperations uoperations attr-val]
  (let [roperation {:type :set
                    :attr attr
                    :val attr-val
                    :ignore-touched true}
        uoperation {:type :set
                    :attr attr
                    :val (get dest-shape attr)
                    :ignore-touched true}]
    [(conj roperations roperation)
     (conj uoperations uoperation)]))

(defn- check-detached-main
  [changes dest-shape origin-shape]
  ;; Only for direct updates (from main to copy). Check if the main shape
  ;; has been detached. If so, the copy shape must be unheaded (i.e. converted
  ;; into a normal copy and not a nested instance).
  (if (and (= (:shape-ref dest-shape) (:id origin-shape))
           (ctk/subcopy-head? dest-shape)
           (not (ctk/instance-head? origin-shape)))
    (pcb/update-shapes changes [(:id dest-shape)]
                       #(-> % ctk/remove-swap-group ctk/unhead-shape)
                       {:ignore-touched true})
    changes))

(defn- check-swapped-main
  [changes dest-shape origin-shape]
  ;; Only for direct updates (from main to copy). Check if the main shape
  ;; has been swapped. If so, the new component-id and component-file must
  ;; be put into the copy.
  (if (and (= (:shape-ref dest-shape) (:id origin-shape))
           (ctk/instance-head? dest-shape)
           (ctk/instance-head? origin-shape)
           (or (not= (:component-id dest-shape) (:component-id origin-shape))
               (not= (:component-file dest-shape) (:component-file origin-shape))))
    (pcb/update-shapes changes [(:id dest-shape)]
                       #(assoc %
                               :component-id (:component-id origin-shape)
                               :component-file (:component-file origin-shape))
                       {:ignore-touched true})
    changes))

(defn attr-operations
  "The redo and undo `:set` operations, as `[roperations uoperations]`, that
   copy attributes into `dest-shape`. For each of `attrs`, by default
   `updatable-attrs`, `attr-ops` gives the `[attr value]` pairs to set, in
   order: none to leave it alone, and possibly some derived attributes
   before it."
  ([dest-shape attr-ops] (attr-operations dest-shape attr-ops updatable-attrs))
  ([dest-shape attr-ops attrs]
   (reduce (fn [operations attr]
             (reduce (fn [[roperations uoperations] [attr value]]
                       (add-update-attr-operations attr dest-shape roperations uoperations value))
                     operations
                     (attr-ops attr)))
           [[] '()]
           attrs)))

(defn- automatic-name
  "The name the editors give a text after its `content`, or nil when it has
  no letters."
  [content]
  (let [name (txt/generate-shape-name (txt/content->text content))]
    (when (not= name "") name)))

(defn- synced-text-name
  "The name an untouched copy text takes from `origin-shape`, its main: the
  main's own name, or, when that one follows the main's letters, the name
  that follows the letters the copy shows after the sync."
  [dest-shape origin-shape touched omit-touched?]
  (let [origin-name (:name origin-shape)]
    (if (and (some? origin-name)
             (= origin-name (automatic-name (:content origin-shape))))
      (let [content (if (and omit-touched? (touched :content-group))
                      (txt/merge-overrides (:content dest-shape)
                                           (:content origin-shape)
                                           (txt/text-overrides touched))
                      (:content origin-shape))]
        (or (automatic-name content) origin-name))
      origin-name)))

(defn- same-sync-value?
  "Compare numeric values exactly, including integer/float representations
   inside geometry. Every nonzero difference still requires a sync."
  [attr value other]
  (or (= value other)
      (let [same-number? (fn [a b] (and (number? a) (number? b) (== a b)))]
        (case attr
          :selrect (and (map? value) (map? other)
                        (= (set (keys value)) (set (keys other)))
                        (every? #(same-number? (get value %) (get other %)) (keys value)))
          :points (and (sequential? value) (sequential? other)
                       (= (count value) (count other))
                       (every? (fn [[a b]]
                                 (and (same-number? (:x a) (:x b))
                                      (same-number? (:y a) (:y b))))
                               (map vector value other)))
          (same-number? value other)))))

(defn- sync-attr-operations
  "The `[attr value]` pairs that sync `attr` from `origin-shape`, a main
   shape placed like `dest-shape`, into `dest-shape`, its copy. With
   `omit-touched?`, an attribute whose group is in `touched` (the copy's
   overrides) is left as it is; a text's content then takes the main's
   letters and styles the copy did not override."
  [dest-shape origin-shape touched omit-touched? attr]
  (let [sync-group
        (or (ctk/resolve-sync-group (:type origin-shape) attr)
            (ctk/resolve-sync-group (:type dest-shape) attr))

        ;; position-data is a special case because can be affected by
        ;; :geometry-group and :content-group so, if the
        ;; position-data changes but the geometry is touched
        ;; we need to reset the position-data so it's
        ;; calculated again
        reset-pos-data?
        (and (cfh/text-shape? origin-shape)
             (= attr :position-data)
             (not (attr-equal? :position-data origin-shape dest-shape))
             (touched :geometry-group))

        ;; On texts, when we want to omit the touched attrs, both text (the actual letters)
        ;; and attrs (bold, font, etc) are in the same attr :content.
        ;; If only one of them is touched, we want to address this case and
        ;; only update the untouched one
        text-content-change?
        (and omit-touched?
             (cfh/text-shape? origin-shape)
             (= :content attr)
             (touched sync-group))

        ;; the name of a text may follow its letters
        text-name?
        (and (cfh/text-shape? origin-shape)
             (cfh/text-shape? dest-shape)
             (= :name attr))

        skip?
        (or (and (not text-name?)
                 (or (attr-equal? attr origin-shape dest-shape)
                     (same-sync-value? attr (get origin-shape attr) (get dest-shape attr))))
            (and (touched sync-group)
                 omit-touched?
                 ;; When it is a text-partial-change, we should generate operations
                 ;; even when omit-touched? is true, but updating only the text or
                 ;; the attributes, omiting the other part
                 (not text-content-change?)))

        attr-val
        (when-not skip?
          (cond
            ;; If position data changes and the geometry group is touched
            ;; we need to put to nil so we can regenerate it
            reset-pos-data?
            nil

            text-content-change?
            (txt/merge-overrides (:content dest-shape)
                                 (:content origin-shape)
                                 (txt/text-overrides touched))

            text-name?
            (synced-text-name dest-shape origin-shape touched omit-touched?)

            :else
            (get origin-shape attr)))]

    (cond
      ;; If the final attr-value is the actual value, skip
      (or skip? (same-sync-value? attr attr-val (get dest-shape attr)))
      []

      ;; On a text-partial-change, we want to force a position-data reset
      ;; so it's calculated again
      text-content-change?
      [[:position-data nil] [attr attr-val]]

      :else
      [[attr attr-val]])))

(defn- update-attrs
  "The main function that implements the attribute sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.

  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will not be copied."
  [changes dest-shape origin-shape dest-root origin-root container omit-touched?]

  (shape-log :info (:id dest-shape) container
             :msg (str "SYNC "
                       (:name origin-shape)
                       " "
                       (pretty-uuid (:id origin-shape))
                       " -> "
                       (shape-label dest-shape container)))

  (let [;; To synchronize geometry attributes we need to make a prior
        ;; operation, because coordinates are absolute, but we need to
        ;; sync only the position relative to the origin of the component.
        ;; We solve this by moving the origin shape so it is aligned with
        ;; the dest root before syncing.
        ;; In case of subinstances, the comparison is always done with the
        ;; near component, because this is that we are syncing with.
        origin-shape  (reposition-shape origin-shape origin-root dest-root)
        touched       (get dest-shape :touched #{})
        ;; Direct sync of a swapped head reads its content main. Its
        ;; placement comes from the replaced child in the enclosing main.
        content-only? (and omit-touched?
                           (ctk/swapped? dest-shape)
                           (= (:id dest-shape) (:id dest-root)))
        valid-attrs   (when content-only?
                        (set/difference (set (keys ctk/sync-attrs)) placement-attrs))

        [roperations uoperations]
        (attr-operations dest-shape
                         #(when-not (and content-only? (contains? placement-attrs %))
                            (sync-attr-operations dest-shape origin-shape touched omit-touched? %)))]

    (cond-> changes
      (seq roperations)
      (add-update-attr-changes dest-shape container roperations uoperations)
      :always
      (check-detached-main dest-shape origin-shape)
      :always
      (check-swapped-main dest-shape origin-shape)
      :always
      (generate-update-tokens container dest-shape origin-shape touched omit-touched? valid-attrs))))

(defn- propagate-attrs
  "Helper that puts the origin attributes (attrs) into dest but only if
  not touched the group or if omit-touched? flag is true"
  [dest origin attrs omit-touched?]
  (let [touched (get dest :touched #{})]
    (->> attrs
         (reduce
          (fn [dest attr]
            (let [sync-group
                  (or (ctk/resolve-sync-group (:type origin) attr)
                      (ctk/resolve-sync-group (:type dest) attr))]
              (cond-> dest
                (or (not (touched sync-group))
                    (not omit-touched?))
                (assoc attr (get origin attr)))))
          dest))))

(defn generate-take-placement
  "Give the swapped copy head `shape-inst` the placement of `placed`, the
  shape whose position it takes, expressed in the same coordinates: the
  attributes that place it in its parent (position, rotation, flips,
  constraints and layout item attributes) that the user did not change. Its
  content comes from its own component. With `layout-placed?`, the position
  is left to the layout.
  `objects` holds the subtree of `shape-inst`, which moves with it."
  [changes shape-inst placed objects layout-placed?]
  (let [touched    (get shape-inst :touched #{})
        untouched? (fn [attr]
                     (not (touched (ctk/resolve-sync-group (:type shape-inst) attr))))

        placement  (reduce (fn [attrs attr]
                             (let [value (get placed attr)]
                               (if (and (untouched? attr)
                                        (not= value (get shape-inst attr)))
                                 (assoc attrs attr value)
                                 attrs)))
                           {}
                           placement-attrs)

        orient     (when (untouched? :rotation)
                     (orient-like shape-inst placed (gsh/shape->center shape-inst)))
        oriented   (cond-> shape-inst (some? orient) (orient))
        ;; turned alike, both take their place at their first corner
        turned?    (and (some? (:transform placed))
                        (not (gmt/unit? (axes-matrix placed)))
                        (gmt/close? (axes-matrix placed) (axes-matrix oriented)))
        position   (fn [shape]
                     (or (when turned? (first (:points shape)))
                         (gpt/point (dm/get-in shape [:selrect :x])
                                    (dm/get-in shape [:selrect :y]))))
        delta      (gpt/subtract (position placed) (position oriented))
        move?      (and (untouched? :x)
                        (not layout-placed?)
                        (not (gpt/almost-zero? delta)))]
    (cond-> changes
      (seq placement)
      (pcb/update-shapes [(:id shape-inst)]
                         (fn [shape]
                           (reduce-kv (fn [shape attr value]
                                        (if (nil? value)
                                          (dissoc shape attr)
                                          (assoc shape attr value)))
                                      shape
                                      placement))
                         {:ignore-touched true})

      (or move? (some? orient))
      (pcb/update-shapes (cfh/get-children-ids-with-self objects (:id shape-inst))
                         #(cond-> %
                            (some? orient) (orient)
                            move?          (gsh/move delta))
                         {:ignore-touched true}))))

(defn generate-sync-advanced-placement
  "Settle the placement of swapped heads whose position reference advanced.
   Read only the position frame; content may still use an older library."
  [changes container libraries ids]
  (reduce
   (fn [changes id]
     (let [objects      (pcb/get-objects changes)
           container    (assoc container :objects objects)
           shape        (get objects id)
           parent       (get objects (:parent-id shape))
           main         (ctf/find-ref-shape nil container libraries shape
                                            :include-deleted? true :with-context? true)
           main-objects (:objects (:container (meta main)))
           main-parent  (get main-objects (:parent-id main))]
       (if (and parent main-parent)
         (let [placed (get (placed-like main-objects main-parent parent (authored-constraints objects [id])) (:id main))]
           (generate-take-placement changes shape placed objects
                                    (and (ctsl/any-layout-immediate-child? objects shape)
                                         (not (ctsl/position-absolute? shape)))))
         changes)))
   changes ids))

(defn- turned-like-parent?
  "Whether `shape` has the size of `main` and the orientation `main` takes
   when turned from `main-parent` to `parent`, wherever each one is. A
   main at the root of its page has an unturned parent. The dimensions in
   `sized`, which a layout gives the shape, are left out."
  ([shape main main-parent parent] (turned-like-parent? shape main main-parent parent #{}))
  ([shape main main-parent parent sized]
   (let [main-parent (when-not (cfh/root? main-parent) main-parent)
         flip?       (fn [key] (odd? (count (filter key [main main-parent parent]))))]
     (and (or (contains? sized :width) (mth/close? (-> shape :selrect :width) (-> main :selrect :width)))
          (or (contains? sized :height) (mth/close? (-> shape :selrect :height) (-> main :selrect :height)))
          (gmt/close? (axes-matrix shape)
                      (gmt/multiply (axes-matrix parent)
                                    (gmt/inverse (axes-matrix main-parent))
                                    (axes-matrix main)))
          (= (boolean (:flip-x shape)) (flip? :flip-x))
          (= (boolean (:flip-y shape)) (flip? :flip-y))))))

(defn- layout-sized
  "The dimensions a layout gives `shape`: those it fills in its layout
   parent or hugs as a layout itself, and both once it is turned in a
   layout parent, which resizes it along its own axes."
  [objects shape]
  (let [child? (and (ctsl/any-layout-immediate-child? objects shape)
                    (not (ctsl/position-absolute? shape)))
        owned? #(and (or child? (ctsl/any-layout? shape)) (#{:fill :auto} %))
        sized  (cond-> #{}
                 (owned? (:layout-item-h-sizing shape)) (conj :width)
                 (owned? (:layout-item-v-sizing shape)) (conj :height))]
    (if (and child? (seq sized) (not (mth/close? (mod (or (:rotation shape) 0) 360) 0)))
      #{:width :height}
      sized)))

(defn generate-keep-advanced-placement
  "Make authored the geometry of the copy shapes `ids`, whose references
   advanced, when their next sync would place them elsewhere: their copy
   parent keeps overrides that the new main lacks. Under a plain parent a
   copy is placed freely, and only its orientation and size follow its
   main, turned like that parent, as does one a layout places. Parents come before their children,
   so each child is checked in its parent's settled frame."
  [changes container libraries ids]
  (reduce
   (fn [changes id]
     (let [objects      (pcb/get-objects changes)
           container    (assoc container :objects objects)
           shape        (get objects id)
           parent       (get objects (:parent-id shape))
           main         (ctf/find-ref-shape nil container libraries shape
                                            :include-deleted? true :with-context? true)
           main-objects (:objects (:container (meta main)))
           main-parent  (get main-objects (:parent-id main))
           ;; a group or bool takes its bounds from its children, so they are
           ;; placed in the board around it
           board?       (and (#{:group :bool} (:type parent)) (#{:group :bool} (:type main-parent))
                             (not (ctsl/any-layout? (get objects (:frame-id shape)))))
           parent       (if board? (get objects (:frame-id shape)) parent)
           main-parent  (if board? (get main-objects (:frame-id main)) main-parent)
           ;; a layout places the shape, so only its size and orientation can be its own
           layout-placed? (and (ctsl/any-layout-immediate-child? objects shape)
                               (not (ctsl/position-absolute? shape)))]
       (if (and (some? main)
                (not (ctk/touched-group? shape :geometry-group))
                ;; the bounds of a group or bool follow its children's places
                (not (#{:group :bool} (:type shape)))
                (if (and (some? (:shape-ref parent)) (not layout-placed?))
                  (and (some? main-parent)
                       (not (close-geometry? shape (get (placed-like main-objects main-parent parent
                                                                     (authored-constraints objects [id]))
                                                        (:id main)))))
                  (not (turned-like-parent? shape main main-parent parent
                                            (if layout-placed? (layout-sized objects shape) #{})))))
         (pcb/update-shapes changes [id] #(update % :touched ctk/set-touched-group :geometry-group)
                            {:ignore-touched true})
         changes)))
   changes ids))

(defn- update-swapped-placement
  "Synchronizes a swapped copy head with the main shape it replaces, in the
  attributes that place it in its parent. A head with untouched geometry
  that stops filling its parent in a direction is synced with its own
  component, as the layout no longer sizes it. One that fills where the
  layout skews that shape takes the lengths of its own component and the
  turn of that shape, which the layout then skews as it skewed the shape."
  [changes {:keys [root-inst root-main container file libraries]} shape-inst shape-main]
  (let [objects  (:objects container)
        placed   (reposition-shape shape-main root-main root-inst)
        sizings  [:layout-item-h-sizing :layout-item-v-sizing]
        ;; whether the layout may have sized the head
        filling? (and (not (ctk/touched-group? shape-inst :geometry-group))
                      (some #(= :fill (get shape-inst %)) sizings))
        layout-placed? (and (ctsl/any-layout-immediate-child? objects shape-inst)
                            (not (ctsl/position-absolute? shape-inst)))
        skew?    (and filling? layout-placed? (skewed? placed))
        changes  (if skew?
                   (let [changes (pcb/apply-changes-local changes)]
                     (-> (generate-sync-shape-direct changes file libraries
                                                     (assoc container :objects (pcb/get-objects changes))
                                                     (:id shape-inst) false)
                         (pcb/apply-changes-local)))
                   changes)
        objects  (if skew? (pcb/get-objects changes) objects)
        changes  (generate-take-placement changes
                                          (get objects (:id shape-inst))
                                          (cond-> placed skew? (unskewed))
                                          objects
                                          layout-placed?)]
    (if filling?
      (let [changes (pcb/apply-changes-local changes)
            objects (pcb/get-objects changes)
            placed  (get objects (:id shape-inst))]
        (if (some #(and (= :fill (get shape-inst %)) (not= :fill (get placed %))) sizings)
          (generate-sync-shape-direct changes file libraries (assoc container :objects objects)
                                      (:id shape-inst) false)
          changes))
      changes)))

(defn- update-layout-children
  "Copy into `dest`, a shape of `container`, the layout attributes of the
  flex or grid shape `src` it pairs with: its sizing, when `src` hugs or
  fixes it, and the layout child attributes of each of its children, from
  the child of `src` it pairs with (`src-child`); a child with none is left
  as it is. With `omit-touched?`, an attribute whose group is touched is
  left as it is. A grid `dest` assigns its cells again when that takes a
  child in or out of its flow."
  [changes container dest src src-child omit-touched?]
  (let [objects    (:objects container)
        propagated #(if-let [src-child (src-child %)]
                      (propagate-attrs % src-child layout-child-attrs omit-touched?)
                      %)
        ;; whether a child enters or leaves the flow of a grid
        reflow?    (and (ctsl/grid-layout? dest)
                        (some #(let [child (get objects %)]
                                 (and child
                                      (not= (ctsl/position-absolute? child)
                                            (ctsl/position-absolute? (propagated child)))))
                              (:shapes dest)))]
    (pcb/concat-changes
     changes
     (cond-> (-> (pcb/empty-changes)
                 (pcb/with-container container)
                 (pcb/with-objects objects)
                 (pcb/update-shapes
                  [(:id dest)]
                  (fn [dest]
                    (cond-> dest
                      (contains? #{:auto :fix} (:layout-item-h-sizing src))
                      (propagate-attrs src #{:layout-item-h-sizing} omit-touched?)

                      (contains? #{:auto :fix} (:layout-item-v-sizing src))
                      (propagate-attrs src #{:layout-item-v-sizing} omit-touched?)))
                  {:ignore-touched true})
                 (pcb/update-shapes (:shapes dest) propagated {:ignore-touched true}))
       reflow?
       (pcb/update-shapes [(:id dest)] ctsl/assign-cells {:with-objects? true :ignore-touched true})

       (and reflow? (= :page (:type container)))
       (pcb/reorder-grid-children [(:id dest)])))))

(defn- add-inherited-grid-cells
  "Add default cells in newly inherited tracks without replacing authored cells."
  [shape before]
  (let [old-rows (count (:layout-grid-rows before))
        old-cols (count (:layout-grid-columns before))
        rows     (count (:layout-grid-rows shape))
        cols     (count (:layout-grid-columns shape))
        added    (concat (for [row (range (inc old-rows) (inc rows))
                               column (range 1 (inc cols))] [row column])
                         (for [row (range 1 (inc (min old-rows rows)))
                               column (range (inc old-cols) (inc cols))] [row column]))]
    (reduce (fn [shape [row column]]
              (if (ctsl/cell-by-row-column shape row column)
                shape
                (ctsl/create-cells shape [column row 1 1])))
            shape added)))

(defn- assign-inherited-grid-cells
  [shape before objects]
  (if-not (or (< (count (:layout-grid-rows shape)) (count (:layout-grid-rows before)))
              (< (count (:layout-grid-columns shape)) (count (:layout-grid-columns before))))
    (ctsl/assign-cells shape objects)
    (let [active? (some #(not (ctsl/position-absolute? objects %)) (:shapes shape))
          ;; Like assign-cells, a grid with participating children needs at
          ;; least one track per axis, including an initially empty grid.
          target  (cond-> shape
                    (and active? (empty? (:layout-grid-rows shape)))
                    (assoc :layout-grid-rows [ctsl/default-track-value])

                    (and active? (empty? (:layout-grid-columns shape)))
                    (assoc :layout-grid-columns [ctsl/default-track-value]))]
      (-> target
          (ctsl/check-deassigned-cells objects)
          (ctsl/fit-cells-to-tracks)
          (ctsl/assign-cells objects)
          ;; New children can exhaust the remaining slots. Fit any overflow
          ;; back into inherited tracks instead of deriving extra tracks.
          (assoc :layout-grid-rows (:layout-grid-rows target)
                 :layout-grid-columns (:layout-grid-columns target))
          (ctsl/fit-cells-to-tracks)))))

(defn- keep-cell-ids
  "`shape` with the cells its tracks add, those its main `shape-main` lacks,
   under the ids of the cells of `shape-copy` in the same place, so each sync
   gives them the same ids."
  [shape shape-copy shape-main]
  (let [place      (juxt :row :column :row-span :column-span)
        main-ids   (set (keys (:layout-grid-cells shape-main)))
        previous   (into {}
                         (comp (map val)
                               (remove #(contains? main-ids (:id %)))
                               (map (juxt place :id)))
                         (:layout-grid-cells shape-copy))]
    (update shape :layout-grid-cells
            (fn [cells]
              (into {}
                    (map (fn [[id cell]]
                           (let [id (if (contains? main-ids id) id (get previous (place cell) id))]
                             [id (assoc cell :id id)])))
                    cells)))))

(defn copy-grid-cells
  "The grid copy `shape-copy` with the cells of its main `shape-main`, its
   own children in place of the main's, laid out in its own direction: the
   cells of a copy without cell overrides."
  [shape-copy shape-main objects]
  (let [ids-map
        (into {}
              (comp
               (map #(get objects %))
               (keep
                (fn [copy-shape]
                  ;; Paired children refer to this near main, which can
                  ;; belong to another component when resetting nested copies.
                  (when-let [ref (:shape-ref copy-shape)]
                    [ref (:id copy-shape)]))))
              (:shapes shape-copy))]
    (-> shape-copy
        (assoc :layout-grid-cells (-> shape-main (ctsl/remap-grid-cells ids-map) :layout-grid-cells))
        ;; a copy with its own track lists keeps its main's cells within them
        (cond-> (or (not= (count (:layout-grid-rows shape-copy)) (count (:layout-grid-rows shape-main)))
                    (not= (count (:layout-grid-columns shape-copy)) (count (:layout-grid-columns shape-main))))
          (-> (ctsl/fit-cells-to-tracks)
              (keep-cell-ids shape-copy shape-main)))
        (ctsl/assign-cells objects))))

(defn grid-direction-cells
  "The update that gives a grid shape of `page` the cells it has once its
   direction changes to `dir`: its single-span auto cells reflow in the new
   order. In a copy without cell overrides, a reflow that moves items makes
   the cells an override, and one that moves none leaves the cells its
   main gives it, laid out as sync lays them out in the new direction, so a
   copy that changes its direction back has its main's cells again. The main
   of a swapped head is the root of its own component, which its content
   follows."
  [file page libraries dir]
  (let [container (ctn/make-container page :page)
        objects   (:objects page)]
    (fn [shape]
      (let [from-dir (ctsl/grid-direction shape)]
        (if (or (not= :grid (:layout shape)) (= from-dir dir))
          shape
          (let [reflowed (ctsl/reflow-grid-auto-items-for-direction shape from-dir dir)
                main     (when (and (:shape-ref shape)
                                    (not (ctk/touched-group? shape :layout-grid-cells)))
                           (ctf/find-content-ref-shape file container libraries shape :include-deleted? true))]
            (cond
              (not= :grid (:layout main))
              reflowed

              (not= (:layout-grid-cells reflowed) (:layout-grid-cells shape))
              (update reflowed :touched ctk/set-touched-group :layout-grid-cells)

              :else
              (copy-grid-cells (assoc shape :layout-grid-dir dir) main objects))))))))

(defn- update-grid-copy-attrs
  "Synchronizes the `layout-grid-cells` property from the main shape to the copies"
  [changes shape-before shape-main omit-touched?]
  (-> changes
      (pcb/apply-changes-local)
      (pcb/update-shapes
       [(:id shape-before)]
       (fn [shape-copy objects]
         (let [remove-orphan-cells
               (fn [cells {:keys [shapes]}]
                 (let [child? (set shapes)]
                   (-> cells
                       (update-vals
                        (fn [cell]
                          (update cell :shapes #(filterv child? %)))))))
               copy-cells  (-> shape-copy :layout-grid-cells (remove-orphan-cells shape-copy))
               keep-cells? (and omit-touched? (contains? (:touched shape-copy) :layout-grid-cells))]
           (if keep-cells?
             (-> shape-copy
                 (assoc :layout-grid-cells copy-cells)
                 (add-inherited-grid-cells shape-before)
                 (assign-inherited-grid-cells shape-before objects))
             ;; Take cells from main and remap the shapes to assign it to the copy
             (copy-grid-cells shape-copy shape-main objects))))
       {:ignore-touched true :with-objects? true})))

(defn- update-grid-main-attrs
  "Synchronizes the `layout-grid-cells` property from the copy to the main
  shape, once the update main has paired `children-copy`, the copy's
  children, with the main's: each with the main shape `added` for it, or
  else with the one it refers to."
  [changes shape-main shape-copy children-copy added main-container]
  (let [ids-map
        (into {}
              (keep (fn [child]
                      (when-let [main-id (get added (:id child) (:shape-ref child))]
                        [(:id child) main-id])))
              children-copy)

        new-changes
        (-> (pcb/empty-changes)
            (pcb/with-page main-container)
            (pcb/with-objects (:objects main-container))
            (pcb/update-shapes
             [(:id shape-main)]
             (fn [shape-main]
               ;; Take cells from copy and remap the shapes to assign it to the copy
               (let [new-cells (-> (ctsl/remap-grid-cells shape-copy ids-map) :layout-grid-cells)]
                 (assoc shape-main :layout-grid-cells new-cells)))
             {:ignore-touched true}))]
    (pcb/concat-changes changes new-changes)))

(defn reposition-shape
  "Expresses the shape (belonging to the origin-root instance) in the frame of the
  dest-root instance, making the geometry of both instances directly comparable —
  and copyable.

  If the dest root's geometry is NOT overridden (touched), the instance follows
  the origin's transformation verbatim (including rotation and flips), so a
  translation by the roots' (untransformed) position delta suffices — position is
  free per-instance placement.

  A turned or flipped instance lines up with the origin at their first
  corner, the one a resize keeps, so it follows a resize in its own frame.

  If the dest root's geometry IS overridden (e.g. the user rotated the copy as a
  whole), the instance keeps its own placement transform, so the origin shape is
  additionally transformed by the roots' relative transformation (rotation /
  flips) around the dest root center — geometric changes then land expressed in
  the dest instance's own frame instead of wiping its placement."
  [shape origin-root dest-root]
  (let [touched?         (ctk/touched-group? dest-root :geometry-group)
        oriented?        (and (not touched?)
                              (some? (:transform dest-root))
                              (not (gmt/unit? (:transform dest-root)))
                              (gmt/close? (or (:transform origin-root) (gmt/matrix))
                                          (:transform dest-root)))
        shape-pos        (fn [shape]
                           (or (when oriented? (first (:points shape)))
                               (gpt/point (dm/get-in shape [:selrect :x])
                                          (dm/get-in shape [:selrect :y]))))

        origin-root-pos  (shape-pos origin-root)
        dest-root-pos    (shape-pos dest-root)
        delta            (gpt/subtract dest-root-pos origin-root-pos)

        shape            (gsh/move shape delta)]
    (if-not touched?
      shape
      (if-let [turn (orient-like origin-root dest-root
                                 (grc/rect->center (:selrect dest-root)))]
        (turn shape)
        shape))))

(defn- stored-turn
  "The rotation that `shape`, just turned by `angle` with its subtree, stores:
   `rotation` plus `angle`, or minus it when only that one decomposes its
   transform with its flips. A mirror that flips after its turn turns the
   other way. When neither does, as when the turn changes its flips, the
   rotation its transform decomposes into."
  [shape rotation angle]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        mirrored (not= (boolean (:flip-x shape)) (boolean (:flip-y shape)))
        sign     (if (:flip-x shape) -1 1)
        turn     (mth/degrees (mth/atan2 (* sign b) (* sign a)))
        same?    #(mth/close? (mod (- %1 %2 -180) 360) 180 0.01)
        stores?  (fn [value]
                   (or (same? value turn)
                       (and mirrored (same? value (- turn)))
                       (and (:flip-x shape) (:flip-y shape) (same? value (+ turn 180)))))
        plus     (mod (+ rotation angle) 360)
        minus    (mod (- rotation angle) 360)]
    (cond
      (and (= mirrored (neg? (- (* a d) (* b c))))
           (not (stores? plus)) (stores? minus))
      minus

      ;; a turn that drops or adds a flip leaves neither; the transform decides
      (and (not (stores? plus)) (not (stores? minus)))
      (mod turn 360)

      :else
      plus)))

(defn- flips
  "The flips of `shape`: its flags, or for a group or bool, which takes its
   bounds from its children and keeps no flags, whether its transform
   mirrors, as a horizontal flip."
  [shape]
  (if (#{:group :bool} (:type shape))
    (let [{:keys [a b c d]} (axes-matrix shape)]
      {:flip-x (neg? (- (* a d) (* b c))) :flip-y false})
    {:flip-x (boolean (:flip-x shape)) :flip-y (boolean (:flip-y shape))}))

(defn orient-like
  "Turn the subtree of `head` around `center` so its axes point like
   `target`'s; the lengths of the axes are left to constraints. Compose flip
   flags with the roots, independent of rotation step size."
  [head target center]
  (let [head-flips   (flips head)
        target-flips (flips target)
        flip-x?      (not= (:flip-x head-flips) (:flip-x target-flips))
        flip-y?      (not= (:flip-y head-flips) (:flip-y target-flips))]
    (when-not (and (= (:transform head) (:transform target))
                   (mth/close? (or (:rotation head) 0) (or (:rotation target) 0) 0.01)
                   (not flip-x?) (not flip-y?))
      (let [relative (gmt/multiply (axes-matrix target) (gmt/inverse (axes-matrix head)))
            transform (gmt/transform-in center relative)
            turned (gsh/apply-transform head transform)
            angle (- (or (:rotation target) 0) (or (:rotation turned) 0))]
        (when (or (not (gmt/unit? relative)) (not (mth/close? (mod angle 360) 0 0.01))
                  flip-x? flip-y?)
          (fn [shape]
            (let [flip-x (cond-> (:flip-x shape) flip-x? not)
                  flip-y (cond-> (:flip-y shape) flip-y? not)
                  turned (-> shape
                             (assoc-in [:modifiers :rotation] angle)
                             (gsh/apply-transform transform)
                             (dissoc :modifiers)
                             (assoc :flip-x flip-x :flip-y flip-y))]
              (assoc turned :rotation (stored-turn turned (or (:rotation shape) 0) angle)))))))))

(defn- maybe-sync
  [c enabled? done? f]
  (if (and enabled? (not (done? c)))
    (f c)
    c))

(defn generate-sync-file-changes
  ([changes undo-group asset-type file-id asset-id library-id libraries current-file-id]
   (generate-sync-file-changes changes undo-group asset-type file-id asset-id library-id libraries current-file-id false))
  ([changes undo-group asset-type file-id asset-id library-id libraries current-file-id early-return?]
   (let [sync-components?   (or (nil? asset-type) (= asset-type :components))
         sync-colors?       (or (nil? asset-type) (= asset-type :colors))
         sync-typographies? (or (nil? asset-type) (= asset-type :typographies))
         done?              (fn [c] (and early-return? (seq (:redo-changes c))))]
     (-> (pcb/set-undo-group changes undo-group)
         (maybe-sync sync-components? done? #(generate-sync-file % file-id :components asset-id library-id libraries current-file-id early-return?))
         (maybe-sync sync-colors? done? #(generate-sync-file % file-id :colors asset-id library-id libraries current-file-id early-return?))
         (maybe-sync sync-typographies? done? #(generate-sync-file % file-id :typographies asset-id library-id libraries current-file-id early-return?))))))


;; Bound repeated processing of a component, not the width of the graph.
(def ^:private max-sync-passes 100)

(defn changed-components
  "Ids of the components whose main `changes` modified, judged against
   `old-data`, the file data before `changes` applied."
  [old-data changes]
  (into #{}
        (mapcat (partial cfc/components-changed old-data))
        (:redo-changes changes)))

(defn- sync-cascade
  "Sync the copies of the components `pending` of the file `file-id` in
   that file, one per pass, and queue the components each pass changed,
   until none is left. `data` is the file data after `changes`. Returns
   `{:changes :data :component-ids :passes}`, with `component-ids` the
   components synced."
  [changes data file-id pending libraries current-file-id passes]
  (loop [changes  changes
         data     data
         pending  (vec pending)
         synced   #{}
         attempts {}
         passes   passes]
    (cond
      (empty? pending)
      {:changes changes :data data :component-ids synced :passes passes}

      (>= (get attempts (first pending) 0) max-sync-passes)
      (throw (ex-info "Component sync did not settle"
                      {:type ::sync-not-settled
                       :pending pending
                       :passes passes}))

      :else
      (let [component-id (first pending)
            libraries    (assoc-in libraries [file-id :data] data)
            sync         (generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     file-id
                                                     component-id
                                                     file-id
                                                     libraries
                                                     current-file-id)
            data'        (cfc/process-changes data (:redo-changes sync) false)
            more         (->> (changed-components data sync)
                              (remove (set pending)))]
        (recur (pcb/concat-changes changes sync)
               data'
               (into (subvec pending 1) more)
               (conj synced component-id)
               (update attempts component-id (fnil inc 0))
               (inc passes))))))

(defn generate-sync-components-cascade
  "Sync the copies of the components `component-ids` of the file `file-id`
   in that file, then those of the components each sync changed, until
   none is left (`mem:common/component-sync-contract`, decision (c)).
   `libraries` holds every file by id, `file-id` included. Returns
   `{:changes :data :component-ids :passes}`: the changes of every pass in
   order, the file data after them, the components synced and the number
   of passes."
  [changes file-id component-ids libraries current-file-id]
  (sync-cascade changes (get-in libraries [file-id :data]) file-id component-ids
                libraries current-file-id 0))

(defn generate-sync-file-cascade
  "As `generate-sync-file-changes`, then the cascade of
   `generate-sync-components-cascade` from the components of `file-id`
   that sync changed. Returns `{:changes :data :component-ids :passes}`."
  [changes undo-group asset-type file-id asset-id library-id libraries current-file-id]
  (let [data  (get-in libraries [file-id :data])
        sync  (generate-sync-file-changes (pcb/empty-changes) nil asset-type file-id asset-id
                                          library-id libraries current-file-id)
        data' (cfc/process-changes data (:redo-changes sync) false)]
    (sync-cascade (-> (pcb/set-undo-group changes undo-group)
                      (pcb/concat-changes sync))
                  data' file-id (changed-components data sync)
                  libraries current-file-id 1)))

(defn- settle-reset-placement
  "Place a reset nested head as an ordinary copy of its position when its
   geometry was overridden, or a copy between it and the head that owns its
   ref keeps its own: its subtree takes the placement a sync of that head
   gives. Other heads keep the placement they have."
  [changes file libraries container shape-id]
  (let [objects (pcb/get-objects changes)
        shape   (get objects shape-id)
        parent  (get objects (:parent-id shape))
        ;; the head as it was before the reset cleared its overrides
        before  (ctn/get-shape container shape-id)]
    (if (and (= :page (:type container))
             (:shape-ref parent)
             (not (ctk/swapped? shape)))
      (let [container (ctn/make-container (assoc container :objects objects) :page)
            component (ctf/find-ref-component file container libraries shape :include-deleted? true)
            head      (d/seek #(= (:id component) (:component-id %))
                              (ctn/get-parent-copy-heads objects parent))
            ;; the copies from the parent up to that head
            between   (when head
                        (into [] (d/take-until #(= (:id head) (:id %)))
                              (cfh/get-parents-with-self objects (:id parent))))]
        (if (and head
                 (some #(ctk/touched-group? % :geometry-group)
                       (cons before between)))
          (let [file     (assoc-in file [:data :pages-index (:id container) :objects] objects)
                proposal (generate-sync-shape-direct (-> (pcb/empty-changes)
                                                         (pcb/with-container container)
                                                         (pcb/with-objects objects))
                                                     file (assoc libraries (:id file) file)
                                                     container (:id head) false)
                placed   (pcb/get-objects proposal)
                ;; the sync places the parent before its layout does; follow the parent where it is
                offset   (if-let [proposed (get placed (:id parent))]
                           (gpt/subtract (first (:points parent)) (first (:points proposed)))
                           (gpt/point 0 0))]
            (pcb/update-shapes
             changes (cfh/get-children-ids-with-self objects shape-id)
             (fn [shape]
               (let [expected (cond-> (get placed (:id shape))
                                (not (gpt/close? offset (gpt/point 0 0))) (gsh/move offset))
                     attrs    (cond-> placement-geometry-attrs
                                (= :path (:type shape)) (conj :content))]
                 (if (= (:type shape) (:type expected))
                   (merge shape (select-keys expected attrs))
                   shape)))
             {:ignore-touched true}))
          changes))
      changes)))

(defn- reset-layout-item
  "Give a reset head the layout item values it inherits: those of its ref
   when its parent is the copy of the ref's layout parent, or a swapped head
   whose main is that parent. Sync passes them from parent to children, so
   the reset of the head alone keeps them."
  [changes file libraries container shape-id]
  (let [objects (pcb/get-objects changes)
        shape   (get objects shape-id)
        parent  (get objects (:parent-id shape))
        ref     (when (and (:layout parent) (:shape-ref parent))
                  (ctf/find-ref-shape file (assoc container :objects objects) libraries shape
                                      :include-deleted? true :with-context? true))
        ref-parent (get-in (meta ref) [:container :objects (:parent-id ref)])
        ;; whether sync pairs the parent with the ref's parent
        paired? (or (= (:id ref-parent) (:shape-ref parent))
                    (and (ctk/main-instance? ref-parent)
                         (= (:component-id ref-parent) (:component-id parent))))]
    (if (and ref (:layout ref-parent) paired?)
      (pcb/update-shapes changes [shape-id] #(propagate-attrs % ref layout-child-attrs false)
                         {:ignore-touched true})
      changes)))

(defn generate-reset-component
  "Reset the copy head to its near main. A swapped head turns back into the
  component of the main shape it refers to, keeping its id. A nested head
  also takes its position's placement and inherited layout item values,
  and its grid parent's cells follow when that takes it in or out of the
  flow."
  [changes file-full libraries container id]
  (-> changes
      (pcb/with-container container)
      (pcb/with-objects (:objects container))
      (generate-sync-shape-direct file-full libraries container id true)
      (settle-reset-placement file-full libraries container id)
      (reset-layout-item file-full libraries container id)
      (reassign-parent-grid-cells container id)))
