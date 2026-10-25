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
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.typography :as cty]
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
(declare remove-shape)
(declare move-shape)
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

  (let [asset-user?   (asset-user? asset-type asset-id library-id)
        linked-shapes (persistent!
                       (reduce-kv (fn [shapes _ shape]
                                    (cond-> shapes (asset-user? shape) (conj! shape)))
                                  (transient [])
                                  (:objects container)))]
    (loop [shapes (seq linked-shapes)
           changes (-> changes
                       (pcb/with-container container)
                       (pcb/with-objects (:objects container)))]
      (if-let [shape (first shapes)]
        (recur (next shapes)
               (let [objects' (pcb/get-objects changes)
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
                     :typographies (clla/generate-sync-typographies changes library-id container
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

(defn- main-in-parent-orientation
  "Place a content main in the orientation of a swapped head, or compose
   the placement of an ordinary parent that survived detach."
  [main-container shape-main container shape-inst reset?]
  (let [parent (ctn/get-shape container (:parent-id shape-inst))
        main-parent (get-in main-container [:objects (:parent-id shape-main)])
        swapped? (and (not reset?) (ctk/swapped? shape-inst))
        origin (if swapped? shape-main main-parent)
        target (if swapped? shape-inst parent)
        relative (gmt/multiply (or (:transform target) (gmt/matrix))
                               (or (:transform-inverse origin) (gmt/matrix)))]
    (if (or (nil? target) (and (:shape-ref parent) (not reset?) (not swapped?))
            (gmt/unit? relative))
      main-container
      (let [transform (gmt/transform-in (gsh/shape->center shape-main) relative)
            angle (- (or (:rotation target) 0) (or (:rotation origin) 0))
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

(defn generate-sync-shape-direct
  "Generate changes to synchronize one shape that is the root of a component
  instance, and all its children, from the given component."
  [changes file libraries container shape-id reset?]
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
          (sync-copy-recursive changes
                               {:container      container
                                :main-container main-container
                                :component      component
                                :library        library
                                ;; Reset also places new children in the main's frame.
                                :root-inst      (cond-> shape-inst reset? (dissoc :touched))
                                :root-main      shape-main
                                :reset?         reset?}
                               shape-inst
                               shape-main))

        ;; If the component is not found, because the master component has been
        ;; deleted or the library unlinked, do nothing.
        changes)
      changes)))

(defn generate-sync-transformed-placement
  "Settle untouched swapped heads carried through a parent turn in the
   same change group. Their position frame can have a different size from
   their content, so a rigid turn alone does not settle their placement."
  [changes file libraries page before ids]
  (let [objects     (pcb/get-objects changes)
        orientation (fn [shape]
                      [(select-keys (or (:transform shape) (gmt/matrix)) [:a :b :c :d])
                       (:rotation shape) (boolean (:flip-x shape)) (boolean (:flip-y shape))])
        roots       (into (d/ordered-set)
                          (keep (fn [id]
                                  (let [shape (get objects id)]
                                    (when (and (ctk/swapped? shape)
                                               (not (ctk/touched-group? shape :geometry-group))
                                               (not= (orientation (get before id)) (orientation shape)))
                                      (:id (ctn/get-component-shape objects shape))))))
                          ids)]
    (reduce (fn [changes id]
              (let [objects   (pcb/get-objects changes)
                    page      (assoc page :objects objects)
                    file      (assoc-in file [:data :pages-index (:id page) :objects] objects)
                    container (ctn/make-container page :page)]
                (-> changes
                    (pcb/with-container container)
                    (generate-sync-shape-direct file (assoc libraries (:id file) file)
                                                container id false))))
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

(defn- close-geometry?
  [shape other]
  (and (gsh/close-attrs? :selrect (:selrect shape) (:selrect other))
       (gsh/close-attrs? :points (:points shape) (:points other))
       (mth/close? (:content-scale shape 1) (:content-scale other 1))))

(defn- placed-like
  "The main `objects` with the subtree of `shape-main` placed like `placed`,
  a copy shape expressed in the main's frame: resized to its size, its
  children following through their constraints and layouts, then turned
  and moved with it as one piece."
  [objects shape-main placed]
  (let [scale   (/ (:content-scale placed 1) (:content-scale shape-main 1))
        width   (-> placed :selrect :width)
        height  (-> placed :selrect :height)
        objects (if (and (mth/close? width (-> shape-main :selrect :width))
                         (mth/close? height (-> shape-main :selrect :height)))
                  objects
                  (let [modif-tree (gm/set-objects-modifiers
                                    {(:id shape-main) {:modifiers (ctm/change-size shape-main width height)}}
                                    objects)]
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

(defn- sync-descendant-orientation
  "A parent turned by sync carries its whole subtree, including geometry
   overrides. Resize remains the job of constraints, not matrix scale."
  [changes container before]
  (let [after    (get (pcb/get-objects changes) (:id before))
        changed? (and after
                      (or (not (mth/close? (or (:rotation before) 0) (or (:rotation after) 0) 1e-10))
                          (not= (boolean (:flip-x before)) (boolean (:flip-x after)))
                          (not= (boolean (:flip-y before)) (boolean (:flip-y after)))))
        ids      (when changed? (cfh/get-children-ids (:objects container) (:id before)))]
    (if (seq ids)
      (let [orientation (fn [shape]
                          (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
                                horizontal (gpt/unit (gpt/point a b))
                                vertical   (gpt/unit (gpt/point c d))
                                matrix     (gmt/matrix (:x horizontal) (:y horizontal)
                                                       (:x vertical) (:y vertical) 0 0)]
                            (assoc shape :transform matrix :transform-inverse (gmt/inverse matrix))))
            turn        (orient-like (orientation before) (orientation after) (gsh/shape->center before))
            delta       (gpt/subtract (gsh/shape->center after) (gsh/shape->center before))]
        (pcb/update-shapes changes ids
                           #(cond-> % (some? turn) turn :always (gsh/move delta))
                           {:ignore-touched true}))
      changes)))

(defn- sync-copy-recursive
  "Sync the copy shape `shape-inst` from `shape-main`, the main shape it
  pairs with, then pair their children (`compare-children`) and walk each
  pair. A normal sync leaves the copy's overrides (touched groups) as they
  are and only places a swapped child; a reset (`:reset?`) overwrites the
  overrides and clears them. `ctx` holds what stays the same along the
  walk: the copy's `:container`, the `:main-container` of the main shapes,
  the `:component` and its `:library`, the roots `:root-inst` and
  `:root-main`, and `:reset?`. The main shapes of the children of a copy
  shape that keeps its own geometry are placed like it first, so they get
  their expected geometry."
  [changes {:keys [container main-container library component reset?] :as ctx} shape-inst shape-main]
  (shape-log :debug (:id shape-inst) container
             :msg "Sync shape direct recursive"
             :shape-inst (str (:name shape-inst) " " (pretty-uuid (:id shape-inst)))
             :component (:name component))
  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [;; a copy shape that keeps its own geometry passes it on to its
          ;; children, which follow it as they follow their main's
          placed         (when (and (not reset?) (ctk/touched-group? shape-inst :geometry-group))
                           (in-main-frame shape-inst (:root-inst ctx) (:root-main ctx)))
          main-container (cond-> main-container
                           (and (some? placed) (not (close-geometry? placed shape-main)))
                           (update :objects placed-like shape-main placed))
          ctx            (assoc ctx :main-container main-container)
          shape-main     (get-in main-container [:objects (:id shape-main)])
          changes        (-> changes
                             (sync-copy-shape ctx shape-inst shape-main)
                             (sync-descendant-orientation container shape-inst))
          container      (assoc container :objects (pcb/get-objects changes))
          ctx            (assoc ctx :container container)
          shape-inst     (ctn/get-shape container (:id shape-inst))
          children-inst  (vec (ctn/get-direct-children container shape-inst))
          children-main  (vec (ctn/get-direct-children main-container shape-main))]
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
                  (reorder-copy-children shape-inst children-main container))

        (ctsl/grid-layout? shape-inst)
        (update-grid-copy-attrs (:id shape-inst) shape-main library component (not reset?))))))

(defn generate-rename-component
  "Generate the changes for rename the component with the given id, in the current file library."
  [changes id new-name library-data]
  (let [[path name]   (cpn/split-group-name new-name)]
    (-> changes
        (pcb/with-library-data library-data)
        (pcb/update-component id #(assoc % :path path :name name)))))

(defn generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children."
  [changes libraries container shape-id]
  (shape-log :debug shape-id container :msg "Sync shape inverse" :shape (str shape-id))
  (let [shape-inst     (ctn/get-shape container shape-id)
        library        (dm/get-in libraries [(:component-file shape-inst) :data])
        component      (ctkl/get-component library (:component-id shape-inst))

        changes        (cond-> changes
                         (and component (contains? (:touched shape-inst) :name-group))
                         (generate-rename-component (:component-id shape-inst) (:name shape-inst) library))]

    (if component
      (let [synced-ids (volatile! #{})]
        (-> (update-main-recursive changes
                                   {:container      container
                                    :main-container (ctf/get-component-container library component)
                                    :component      component
                                    :libraries      libraries
                                    :root-inst      shape-inst
                                    :root-main      (ctf/get-component-root library component)
                                    :remote-synced  (when-not (:component-root shape-inst) true)
                                    :synced-ids     synced-ids}
                                   shape-inst
                                   (ctf/find-remote-shape container libraries shape-inst))
            (mark-local-changes @synced-ids)))
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

(defn- update-main-shape
  "Push the copy shape `shape-inst` into `shape-main`, the main shape it
  pairs with: the copy's values, overrides included, go into the main, and
  its touched groups move there (the copy keeps a swap)."
  [changes {:keys [container main-container root-inst root-main remote-synced]} shape-inst shape-main]
  (cond-> (-> changes
              (update-attrs shape-main shape-inst root-main root-inst main-container false)
              ;; the copy keeps its swap, and the main takes the copy's overrides
              (set-touched shape-inst container (when (ctk/swapped? shape-inst) #{:swap-group}))
              ;; A previous update is not a reset of the main's authored
              ;; overrides. Repeating it must retain the groups it pushed.
              (set-touched shape-main main-container
                           (set/union (:touched shape-main) (:touched shape-inst))))
    (ctsl/any-layout? shape-main)
    (update-layout-children main-container shape-main shape-inst
                            #(ctf/get-shape-in-copy container % shape-inst) false)

    :always
    (change-remote-synced shape-inst container remote-synced)))

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
          added         (volatile! {})]
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
                    :moved     (fn [changes child-inst child-main]
                                 (move-shape changes
                                             child-main
                                             (d/index-of children-main child-main)
                                             (d/index-of children-inst child-inst)
                                             main-container))
                    :inverse?  true}))

        (ctsl/grid-layout? shape-main)
        (update-grid-main-attrs shape-main shape-inst children-inst @added main-container)))))

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
   orientation becomes authored when it differs from its own main."
  [shape main container]
  (let [shape (-> shape
                  (assoc :shape-ref (:id main))
                  (ctk/remove-swap-group))
        main-container (when main
                         (main-in-parent-orientation (:container (meta main)) main
                                                     container shape false))
        expected (get-in main-container [:objects (:id main)])]
    (cond-> shape
      (and expected (orient-like shape expected (gsh/shape->center shape)))
      (update :touched ctk/set-touched-group :geometry-group))))

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
                        (txt/override-groups (:content reference) (:content shape)))
          groups (cond-> (into groups text-groups)
                   (seq text-groups) (conj :content-group))]
      (cond-> shape
        (seq groups) (update :touched (fnil into #{}) groups)))))

(defn- add-shape-to-main
  "Add to the main shape `parent-main`, at `index`, a copy of `child-inst`,
  a child of the copy shape that pairs with it; `child-inst` then refers to
  the new main shape."
  [changes {:keys [container main-container root-inst root-main libraries added synced-ids]}
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
        ;; The parent's inverse sync already pushed its geometry. Compare
        ;; against that frame, rather than its original container value.
        placement-container
        (cond-> main-container
          content-main
          (assoc-in [:objects (:id parent-main)]
                    (-> (ctn/get-shape container (:parent-id child-inst))
                        (reposition-shape root-inst root-main)
                        (assoc :id (:id parent-main)
                               :shape-ref (:shape-ref parent-main)))))

        update-new-shape (fn [new-shape original-shape]
                           (cond-> (reposition-shape new-shape root-inst root-main)
                             (and (= (:id original-shape) (:id child-inst))
                                  (ctk/swapped? child-inst)
                                  (not swap-in-copy?))
                             (rehead-pushed-swap content-main placement-container)))

        ;; the copy now follows the main, swap included
        update-original-shape (fn [original-shape new-shape]
                                (-> original-shape
                                    (assoc :shape-ref (:id new-shape))
                                    (dissoc :touched)))

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
                position-objects (get-in (meta position) [:container :objects])]
            (mapv (fn [shape]
                    (let [shape (take-position shape)]
                      (author-pushed-content shape (get position-objects (:shape-ref shape)))))
                  new-shapes))
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

(defn- remove-shape
  [changes shape container]
  (shape-log :info (:id shape) container
             :msg (str "REMOVE-SHAPE " (shape-label shape container)))
  (let [objects (get container :objects)
        parents (cfh/get-parent-ids objects (:id shape))
        ;; from the bottom up
        ids     (reverse (into [(:id shape)] (cfh/get-children-ids objects (:id shape))))]
    (pcb/add-changes changes container
                     (conj (mapv del-obj-change ids)
                           {:type :reg-objects :shapes (vec parents)})
                     ;; the parents are recalculated after the shapes are restored
                     (cons {:type :reg-objects :shapes (vec parents)}
                           (map #(add-obj-change (get objects %)
                                                 (or (cfh/get-position-on-parent (pcb/get-objects changes) %)
                                                     (cfh/get-position-on-parent objects %)))
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
  the update of its parents."
  [changes dest-shape container roperations uoperations]
  (let [update-parents {:type :reg-objects
                        :shapes (cfh/get-parent-ids (:objects container) (:id dest-shape))}]
    (pcb/add-changes changes container
                     [{:type :mod-obj :id (:id dest-shape) :operations roperations}
                      update-parents]
                     ;; undone as the shape first, then its parents
                     [update-parents
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
   copy attributes into `dest-shape`. For each of `updatable-attrs`,
   `attr-ops` gives the `[attr value]` pairs to set, in order: none to
   leave it alone, and possibly some derived attributes before it."
  [dest-shape attr-ops]
  (reduce (fn [operations attr]
            (reduce (fn [[roperations uoperations] [attr value]]
                      (add-update-attr-operations attr dest-shape roperations uoperations value))
                    operations
                    (attr-ops attr)))
          [[] '()]
          updatable-attrs))

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
             (not= (:position-data origin-shape) (:position-data dest-shape))
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
                 (= (get origin-shape attr) (get dest-shape attr)))
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
      (or skip? (= attr-val (get dest-shape attr)))
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
        origin-shape (reposition-shape origin-shape origin-root dest-root)
        touched      (get dest-shape :touched #{})

        [roperations uoperations]
        (attr-operations dest-shape
                         #(sync-attr-operations dest-shape origin-shape touched omit-touched? %))]

    (cond-> changes
      (seq roperations)
      (add-update-attr-changes dest-shape container roperations uoperations)
      :always
      (check-detached-main dest-shape origin-shape)
      :always
      (check-swapped-main dest-shape origin-shape)
      :always
      (generate-update-tokens container dest-shape origin-shape touched omit-touched? nil))))

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

        position   (fn [shape]
                     (gpt/point (dm/get-in shape [:selrect :x])
                                (dm/get-in shape [:selrect :y])))
        delta      (gpt/subtract (position placed) (position shape-inst))
        move?      (and (untouched? :x)
                        (not layout-placed?)
                        (not (gpt/almost-zero? delta)))
        orient     (when (untouched? :rotation)
                     (orient-like shape-inst placed (gsh/shape->center shape-inst)))]
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

(defn- update-swapped-placement
  "Synchronizes a swapped copy head with the main shape it replaces, in the
  attributes that place it in its parent."
  [changes {:keys [root-inst root-main container]} shape-inst shape-main]
  (let [objects (:objects container)]
    (generate-take-placement changes
                             shape-inst
                             (reposition-shape shape-main root-main root-inst)
                             objects
                             (and (ctsl/any-layout-immediate-child? objects shape-inst)
                                  (not (ctsl/position-absolute? shape-inst))))))

(defn- update-layout-children
  "Copy into `dest`, a shape of `container`, the layout attributes of the
  flex or grid shape `src` it pairs with: its sizing, when `src` hugs or
  fixes it, and the layout child attributes of each of its children, from
  the child of `src` it pairs with (`src-child`); a child with none is left
  as it is. With `omit-touched?`, an attribute whose group is touched is
  left as it is."
  [changes container dest src src-child omit-touched?]
  (pcb/concat-changes
   changes
   (-> (pcb/empty-changes)
       (pcb/with-container container)
       (pcb/with-objects (:objects container))
       (pcb/update-shapes
        [(:id dest)]
        (fn [dest]
          (cond-> dest
            (contains? #{:auto :fix} (:layout-item-h-sizing src))
            (propagate-attrs src #{:layout-item-h-sizing} omit-touched?)

            (contains? #{:auto :fix} (:layout-item-v-sizing src))
            (propagate-attrs src #{:layout-item-v-sizing} omit-touched?)))
        {:ignore-touched true})
       (pcb/update-shapes
        (:shapes dest)
        #(if-let [src-child (src-child %)]
           (propagate-attrs % src-child layout-child-attrs omit-touched?)
           %)
        {:ignore-touched true}))))

(defn- update-grid-copy-attrs
  "Synchronizes the `layout-grid-cells` property from the main shape to the copies"
  [changes shape-copy-id shape-main _library _component omit-touched?]
  (-> changes
      (pcb/apply-changes-local)
      (pcb/update-shapes
       [shape-copy-id]
       (fn [shape-copy objects]
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
                     (:shapes shape-copy))

               remove-orphan-cells
               (fn [cells {:keys [shapes]}]
                 (let [child? (set shapes)]
                   (-> cells
                       (update-vals
                        (fn [cell]
                          (update cell :shapes #(filterv child? %)))))))
               ;; Take cells from main and remap the shapes to assign it to the copy
               copy-cells (-> shape-copy :layout-grid-cells (remove-orphan-cells shape-copy))
               main-cells (-> shape-main (ctsl/remap-grid-cells ids-map) :layout-grid-cells)]
           (-> shape-copy
               (assoc :layout-grid-cells
                      (if (and omit-touched?
                               (contains? (:touched shape-copy) :layout-grid-cells))
                        copy-cells
                        main-cells))
               (ctsl/assign-cells objects))))
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

  If the dest root's geometry IS overridden (e.g. the user rotated the copy as a
  whole), the instance keeps its own placement transform, so the origin shape is
  additionally transformed by the roots' relative transformation (rotation /
  flips) around the dest root center — geometric changes then land expressed in
  the dest instance's own frame instead of wiping its placement."
  [shape origin-root dest-root]
  (let [shape-pos        (fn [shape]
                           (gpt/point (dm/get-in shape [:selrect :x])
                                      (dm/get-in shape [:selrect :y])))

        origin-root-pos  (shape-pos origin-root)
        dest-root-pos    (shape-pos dest-root)
        delta            (gpt/subtract dest-root-pos origin-root-pos)

        shape            (gsh/move shape delta)]
    (if-not (ctk/touched-group? dest-root :geometry-group)
      shape
      (if-let [turn (orient-like origin-root dest-root
                                 (grc/rect->center (:selrect dest-root)))]
        (turn shape)
        shape))))

(defn orient-like
  "Turn the subtree of `head` around `center` to match `target`'s matrix.
   Compose flip flags with the roots, independent of rotation step size."
  [head target center]
  (let [flip-x? (not= (boolean (:flip-x head)) (boolean (:flip-x target)))
        flip-y? (not= (boolean (:flip-y head)) (boolean (:flip-y target)))]
    (when-not (and (= (:transform head) (:transform target))
                   (mth/close? (or (:rotation head) 0) (or (:rotation target) 0) 0.01)
                   (not flip-x?) (not flip-y?))
      (let [relative (gmt/multiply (or (:transform target) (gmt/matrix))
                                   (or (:transform-inverse head) (gmt/matrix)))
            transform (gmt/transform-in center relative)
            turned (gsh/apply-transform head transform)
            angle (- (or (:rotation target) 0) (or (:rotation turned) 0))]
        (when (or (not (gmt/unit? relative)) (not (mth/close? (mod angle 360) 0 0.01))
                  flip-x? flip-y?)
          (fn [shape]
            (let [flip-x (cond-> (:flip-x shape) flip-x? not)
                  flip-y (cond-> (:flip-y shape) flip-y? not)]
              (-> shape
                  (assoc-in [:modifiers :rotation] angle)
                  (gsh/apply-transform transform)
                  (dissoc :modifiers)
                  (assoc :flip-x flip-x :flip-y flip-y)))))))))

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

(defn generate-reset-component
  "Reset the copy head to its near main. A swapped head turns back into the
  component of the main shape it refers to, keeping its id."
  [changes file-full libraries container id]
  (-> changes
      (pcb/with-container container)
      (pcb/with-objects (:objects container))
      (generate-sync-shape-direct file-full libraries container id true)))
