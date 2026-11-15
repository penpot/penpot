;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.swap
  "Component swap: replace a copy head with a copy of another component, and
   carry the overrides of a copy across a variant switch."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.common :as gco]
   [app.common.logic.instances :as cli]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.path.segment :as segment]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [clojure.set :as set]))

(defn- set-path-new-values
  [current-shape prev-shape transform]
  (let [new-content   (segment/transform-content
                       (:content current-shape)
                       (gmt/transform-in (gpt/point 0 0) transform))
        new-points    (-> (segment/content->selrect new-content)
                          (grc/rect->points))
        points-center (gco/points->center new-points)
        new-selrect   (gsh/calculate-selrect new-points points-center)
        shape         (assoc current-shape
                             :content new-content
                             :points new-points
                             :selrect new-selrect)

        prev-center   (segment/content-center (:content prev-shape))
        delta         (gpt/subtract points-center (first new-points))
        new-pos       (gpt/subtract prev-center delta)]
    (gsh/absolute-move shape new-pos)))

(defn- switch-path-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   ref-shape            ; The referenced shape on the main component
                        ; before the switch
   attr]
  (let [old-width (-> ref-shape :selrect :width)
        new-width (-> prev-shape :selrect :width)

        old-height (-> ref-shape :selrect :height)
        new-height (-> prev-shape :selrect :height)

        transform (-> (gpt/point (/ new-width old-width)
                                 (/ new-height old-height))
                      (gmt/scale-matrix))

        shape     (set-path-new-values current-shape prev-shape transform)]
    (get shape attr)))


(defn- sized-geometry-value
  "The value of `attr`, `:selrect` or `:points`, of `shape` resized to
  `width` and `height`, at the same position and with the same transform."
  [shape width height attr]
  (let [{:keys [x y] :as selrect} (:selrect shape)
        selrect (assoc selrect
                       :width width
                       :height height
                       :x1 x
                       :y1 y
                       :x2 (+ x width)
                       :y2 (+ y height))]
    (case attr
      :selrect
      selrect

      :points
      (-> selrect
          (grc/rect->points)
          (gsh/transform-points
           (grc/rect->center selrect)
           (or (:transform shape) (gmt/matrix)))))))

(defn- switch-size-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   origin-shape         ; The original shape
   owned                ; The dimensions the layout of the shape before gave it
   attr]
  ;; Each dimension keeps the copy's size when both variants agree on it,
  ;; and takes the new variant's otherwise (selrect and points follow); a
  ;; size the copy's layout gave it is no override
  (let [size   (fn [dimension]
                 (let [prev    (get prev-shape dimension)
                       current (get current-shape dimension)
                       origin  (get origin-shape dimension)]
                   (if (and (mth/close? origin current) (not (contains? owned dimension))) prev current)))
        width  (size :width)
        height (size :height)
        ;; the turn and flips the switch carries, when both variants agree on them
        shape  (cond-> current-shape
                 (gsh/close-attrs? :transform (:transform origin-shape) (:transform current-shape))
                 (assoc :transform (:transform prev-shape)))]
    (case attr
      :width  width
      :height height
      (sized-geometry-value shape width height attr))))


(defn- equal-geometry?
  "Returns true when the value of `attr` in `shape` is considered equal
   to the corresponding value in `origin-shape`, ignoring positional
   displacement (x/y).
   For :selrect we compare width/height only;
   for :points we normalise each vector so the first point is the
   origin before comparing.
   For :content on path shapes we compare the bounding-box width/height
   of the path segments, again ignoring absolute position.

   Comparisons use `mth/close?` (and `gpt/close?` for points) rather than
   exact `=` because `previous-shape` here may carry sub-pixel drift from
   interactive transform modifiers (e.g. an alt-drag duplicate of a
   variant whose children are component instances). Without tolerance
   this guard would miss equivalent geometries and let the `:else` branch
   in `update-attrs-on-switch` carry stale `:selrect`/`:points` from the
   pre-switch shape onto the freshly instantiated target."
  [shape origin-shape attr]
  (or (and (= attr :selrect)
           (mth/close? (-> shape :selrect :width)  (-> origin-shape :selrect :width))
           (mth/close? (-> shape :selrect :height) (-> origin-shape :selrect :height)))
      (and (= attr :points)
           (let [normalize-pts (fn [pts]
                                 (when (seq pts)
                                   (let [f (first pts)]
                                     (mapv #(gpt/subtract % f) pts))))
                 a (normalize-pts (get shape :points))
                 b (normalize-pts (get origin-shape :points))]
             (and (= (count a) (count b))
                  (every? identity (map gpt/close? a b)))))
      (and (= attr :content)
           (cfh/path-shape? shape)
           (let [ca (:content shape)
                 cb (:content origin-shape)]
             (and (some? ca) (some? cb)
                  (let [selrect-a (segment/content->selrect ca)
                        selrect-b (segment/content->selrect cb)]
                    (and (some? selrect-a) (some? selrect-b)
                         (mth/close? (:width selrect-a) (:width selrect-b))
                         (mth/close? (:height selrect-a) (:height selrect-b)))))))))

(defn- carried-text-overrides
  "The text overrides of `touched` a switch carries from the copy text
   `previous-shape` into `current-shape`, its copy of the new variant:
   those both variants, `origin-ref-shape` and the new one, agree on and
   where the copy differs from the new variant. A carried typography takes
   every unit it sets along."
  [touched previous-shape current-shape origin-ref-shape]
  (let [;; the overrides both variants agree on
        agreed  (set/difference (txt/text-overrides touched)
                                (txt/override-groups (:content origin-ref-shape) (:content current-shape)))
        carried (set/intersection agreed
                                  (txt/override-groups (:content previous-shape) (:content current-shape)))]
    (cond-> carried
      (contains? carried :text-content-typography)
      (into (filter agreed) txt/typography-units))))

(defn- mirrored?
  "Whether `shape` has exactly one flip."
  [shape]
  (not= (boolean (:flip-x shape)) (boolean (:flip-y shape))))

(defn- close-geometry?
  "Whether `a` and `b` hold the geometry `attr` within its precision. A
   mirror stores its turn either way, so the turns of two mirrors are also
   close where both point their axes alike."
  [attr a b]
  (or (gsh/close-attrs? attr (get a attr) (get b attr))
      (and (= attr :rotation)
           (mirrored? a)
           (mirrored? b)
           (gmt/close? (cll/axes-matrix a) (cll/axes-matrix b)))))

(defn- switch-attr-operations
  "The `[attr value]` pairs that carry `attr` over a variant switch, from
   `previous-shape`, the copy shape before the switch placed like
   `current-shape`, into `current-shape`, its clean copy of the new
   variant. Only an override (a group in `touched`) is carried, and only
   where `origin-ref-shape`, the main shape before the switch, and the new
   copy agree on the attribute (decision (a) of the component contract);
   geometry carries the override's size at the new position."
  [current-shape previous-shape origin-ref-shape touched text-auto? owned attr]
  (let [sync-group
        (ctk/resolve-sync-group (:type previous-shape) attr)

        skip-attr?
        (or
         ;; For auto text, avoid copying geometry-driven attrs on switch.
         (and text-auto?
              (contains? #{:points :selrect :width :height :position-data} attr))

         ;; If the attribute is not valid for the destiny, don't copy it
         (not (cts/is-allowed-switch-keep-attr? attr (:type current-shape)))

         ;; The position is the new variant's; geometry carries only a size
         (contains? #{:x :y} attr)

         ;; If the values are already equal, don't copy them; geometry
         ;; within its precision
         (if (= sync-group :geometry-group)
           (close-geometry? attr previous-shape current-shape)
           (= (get previous-shape attr) (get current-shape attr)))

         ;; If :selrect/:points values are already equal ignoring displacement,
         ;; don't copy them
         (equal-geometry? previous-shape origin-ref-shape attr)

         ;; If the attr is not touched, don't copy it
         (not (touched sync-group))

         ;; If both variants (origin and destiny) don't have the same value
         ;; for that attribute, don't copy it.
         ;; Exceptions: :points :selrect and :content can be different
         ;;
         ;; Sample:
         ;; 1. We have a variant with C1 (bg red) and C2 (bg blue).
         ;; 2. We make a copy of C1 called Copy.
         ;; 3. We set Copy’s bg to green (so it it has an override on the bg).
         ;; 4. We switch Copy to use C2 as base.
         ;; 5. The bg of Copy now is blue (we ignore the override)
         (and
          (not (contains? #{:points :selrect :content} attr))
          (not (if (= sync-group :geometry-group)
                 (close-geometry? attr origin-ref-shape current-shape)
                 (= (get origin-ref-shape attr) (get current-shape attr)))))

         ;; The :content attr cant't be copied to elements of different type
         (and (= attr :content) (not= (:type previous-shape) (:type current-shape))))

        ;; On texts, both text (the actual letters)
        ;; and attrs (bold, font, etc) are in the same attr :content.
        ;; If only one of them is touched, we want to address this case and
        ;; only update the untouched one
        text-change?
        (and (not skip-attr?)
             (cfh/text-shape? current-shape)
             (cfh/text-shape? previous-shape)
             (= :content attr)
             (touched sync-group))

        path-change?
        (and (= :path (:type current-shape))
             (contains? #{:points :selrect :content} attr))

        ;; position-data is a special case because can be affected by :geometry-group and :content-group
        ;; so, if the position-data changes but the geometry is touched we need to reset the position-data
        ;; so it's calculated again
        reset-pos-data?
        (and (not skip-attr?)
             (cfh/text-shape? previous-shape)
             (or (= attr :position-data) (= attr :selrect))
             (not= (:position-data previous-shape) (:position-data current-shape))
             (touched :geometry-group))

        skip?
        (or skip-attr?
            ;; If we are going to reset the position data, skip the selrect attr
            (and reset-pos-data? (= attr :selrect)))

        attr-val
        (when-not skip?
          (cond
            ;; If position data changes and the geometry group is touched
            ;; we need to put to nil so we can regenerate it
            reset-pos-data?
            nil

            ;; Each text override the switch carries goes over the new
            ;; main's content
            text-change?
            (txt/merge-overrides (:content previous-shape)
                                 (:content current-shape)
                                 (carried-text-overrides touched previous-shape current-shape
                                                         origin-ref-shape))

            path-change?
            (switch-path-change-value previous-shape
                                      current-shape
                                      origin-ref-shape
                                      attr)

            ;; Both scalar dimensions and composite geometry must describe
            ;; the same result when only one dimension carries an override.
            (contains? #{:points :selrect :width :height} attr)
            (switch-size-change-value previous-shape current-shape origin-ref-shape owned attr)

            :else
            (get previous-shape attr)))]

    ;; If the final attr-value is the actual value, skip. Geometry values
    ;; equal up to float noise are not an override.
    (if (or skip?
            (cond
              (contains? #{:points :selrect :width :height :rotation} attr)
              (gsh/close-attrs? attr attr-val (get current-shape attr))

              ;; a text showing the letters and styles of the new copy is no override
              text-change?
              (empty? (txt/override-groups (get current-shape attr) attr-val))

              :else
              (= attr-val (get current-shape attr))))
      []
      (cond-> []
        ;; On a text-change, we want to force a position-data reset
        ;; so it's calculated again
        text-change?
        (conj [:position-data nil])

        ;; On a rotation operation we need to keep also the transformation matrixes
        (= attr :rotation)
        (conj [:transform (:transform previous-shape)]
              [:transform-inverse (:transform-inverse previous-shape)])

        :always
        (conj [attr attr-val])))))

(defn- reoriented-operations
  "The switch operations `[roperations uoperations]` into `current-shape`
   with, when they carry a turn or a flip, the transform and the corners
   the resulting turn and flips give it, so they describe one orientation.
   Of a turn before or after the flips, the one nearer the axes of
   `previous-shape`."
  [[roperations uoperations] current-shape previous-shape]
  (if-not (some (comp #{:rotation :flip-x :flip-y} :attr) roperations)
    [roperations uoperations]
    (let [carried  (reduce (fn [shape {:keys [attr val]}] (assoc shape attr val))
                           current-shape roperations)
          oriented (cll/unskewed (assoc carried :transform (:transform previous-shape)))
          values   {:transform         (:transform oriented)
                    :transform-inverse (:transform-inverse oriented)
                    :points            (sized-geometry-value oriented (:width carried) (:height carried) :points)}
          kept?    #(not (contains? values (:attr %)))
          [rops uops] (cll/attr-operations current-shape (fn [attr] [[attr (get values attr)]]) (keys values))]
      [(into (filterv kept? roperations) rops)
       (into (apply list (filter kept? uoperations)) uops)])))

(defn update-attrs-on-switch
  "Copy attributes that have changed in the shape previous to the switch
   to the current shape (post switch). Used only on variants switch"
  ;; NOTE: This function have similitudes but is very different to
  ;; cll/update-attrs:
  ;; In components (cll/update-attrs), the source shape is "clean", and the destination
  ;; shape may have touched elements that shouldn't be overwritten.
  ;; In variants (update-attrs-on-switch), the destination shape is "clean",
  ;; and it's the source shape that may have touched elements, and we only want
  ;; to copy those touched elements.
  [changes current-shape previous-shape current-root prev-root origin-ref-shape container]
  (let [;; the dimensions the layout of the copy gave it before the switch
        owned          (cll/layout-sized (:objects container) previous-shape)
        ;; We need to sync only the position relative to the origin of the component.
        ;; (see cll/update-attrs for a full explanation)
        previous-shape (-> previous-shape
                           (cll/resize-with-parent (get (:objects container) (:parent-id previous-shape))
                                                   (get (pcb/get-objects changes) (:parent-id current-shape)))
                           (cll/reposition-shape prev-root current-root))
        touched        (get previous-shape :touched #{})
        text-auto?     (and (cfh/text-shape? current-shape)
                            (contains? #{:auto-height :auto-width} (:grow-type current-shape)))
        ;; a child carries its layout item overrides too; the head keeps its own placement
        attrs          (cond-> cll/updatable-attrs
                         (not= (:id current-shape) (:id current-root))
                         (concat (disj ctk/swap-keep-attrs :interactions)))

        [roperations uoperations]
        (-> (cll/attr-operations current-shape
                                 #(switch-attr-operations current-shape previous-shape origin-ref-shape
                                                          touched text-auto? owned %)
                                 attrs)
            (reoriented-operations current-shape previous-shape))

        updated-attrs (into #{} (map :attr) roperations)
        updated-sync-groups (into #{}
                                  (keep #(ctk/resolve-sync-group (:type previous-shape) %))
                                  updated-attrs)
        new-content (some #(when (= (:attr %) :content) (:val %)) roperations)
        text-groups (when (and (cfh/text-shape? current-shape) (some? new-content))
                      (let [carried (carried-text-overrides touched previous-shape current-shape
                                                            origin-ref-shape)]
                        (cond-> (txt/override-groups (:content current-shape) new-content)
                          ;; the copy's own styles drop a typography it does not apply
                          (txt/drops-typography? carried)
                          (disj :text-content-typography))))
        new-touched (set/union (or (:touched current-shape) #{})
                               updated-sync-groups
                               text-groups
                               ;; a carried typography owns every unit it sets, equal ones too
                               (when (and (contains? text-groups :text-content-typography)
                                          (txt/typography-owner? new-content))
                                 txt/typography-units))
        roperations (into [{:type :set-touched :touched new-touched}] roperations)
        uoperations (into (list {:type :set-touched :touched (:touched current-shape)}) uoperations)]
    (cond-> changes
      (> (count roperations) 1)
      (-> (cll/add-update-attr-changes current-shape container roperations uoperations)
          (cll/generate-update-tokens container current-shape previous-shape touched false updated-attrs
                                      (cll/oriented-by current-shape roperations))))))

(defn- generate-keep-orientation
  "Give the new head of a swap, and its subtree, the flips and the rotation
  of the shape it replaces, around the head's own center. They come from
  its parent, as when the parent is rotated, and are not overrides. The
  turned or flipped head takes the place of the old one at their first
  corner, so it turns rigidly with its parent, also when it already has
  the old one's orientation."
  [changes new-shape shape]
  (let [orient (cll/orient-like new-shape shape (gsh/shape->center new-shape))
        ids    (cfh/get-children-ids-with-self (pcb/get-objects changes) (:id new-shape))
        delta  (gpt/subtract (first (:points shape))
                             (first (:points (cond-> new-shape (some? orient) (orient)))))]
    (cond
      (some? orient)
      (pcb/update-shapes changes ids
                         (cond->> orient
                           (not (gpt/almost-zero? delta))
                           (comp #(gsh/move % delta)))
                         {:ignore-touched true})

      (and (not (gmt/unit? (or (:transform shape) (gmt/matrix))))
           (not (gpt/almost-zero? delta)))
      (pcb/update-shapes changes ids #(gsh/move % delta) {:ignore-touched true})

      :else
      changes)))

(defn- generate-swap-in-copy
  "A swap inside a copy keeps the position: the new head takes the place of
  the main shape the old one referred to."
  [changes new-shape shape file page libraries]
  (let [file*    {:id (:id file) :data file}
        objects  (pcb/get-objects changes)
        position (ctf/find-ref-shape file* page libraries shape
                                     :include-deleted? true
                                     :with-context? true)]
    (pcb/update-shapes changes
                       (cfh/get-children-ids-with-self objects (:id new-shape))
                       (ctf/take-position file* libraries objects new-shape (:shape-ref shape) position)
                       {:ignore-touched true})))

(defn generate-sync-swap-constraints
  "Give a nested swap the constraints of its position, after a switch has
  carried any compatible overrides. Instantiation supplies content-main
  constraints, which do not own the swapped head's placement."
  [changes page libraries shape-id]
  (let [objects  (pcb/get-objects changes)
        shape    (get objects shape-id)
        parent   (get objects (:parent-id shape))
        position (when (and (ctk/in-component-copy? parent)
                            (not (ctk/touched-group? shape :constraints-group)))
                   (ctf/find-ref-shape nil (assoc page :objects objects) libraries shape
                                       :include-deleted? true))]
    (cond-> changes
      position
      (pcb/update-shapes [shape-id]
                         #(d/patch-object % {:constraints-h (:constraints-h position)
                                             :constraints-v (:constraints-v position)
                                             :fixed-scroll (:fixed-scroll position)})
                         {:ignore-touched true}))))

(defn generate-new-shape-for-swap
  [changes shape file page libraries id-new-component index target-cell keep-props-values]
  (let [objects      (:objects page)
        position     (gpt/point (:x shape) (:y shape))
        changes      (-> changes
                         (pcb/with-objects objects))
        position     (-> position (with-meta {:cell target-cell}))
        parent       (get objects (:parent-id shape))
        inside-copy? (ctk/in-component-copy? parent)

        [new-shape changes]
        ;; When we make a swap of an item, there can be copies which refer to that item,
        ;; so we want to assign the item id to the new instanciated copy, to mantain that reference
        (cli/generate-instantiate-component changes
                                            objects
                                            (:id file)
                                            id-new-component
                                            position
                                            page
                                            libraries
                                            (:id shape)
                                            (:parent-id shape)
                                            (:frame-id shape)
                                            {(:id shape) (:id shape)} ;; keep the id of the original shape
                                            {:force-frame? true})

        changes
        (cond-> changes
          inside-copy?
          (generate-swap-in-copy new-shape shape file page libraries))

        new-shape
        (get (pcb/get-objects changes) (:id new-shape))

        changes
        (generate-keep-orientation changes new-shape shape)

        ;; the touched groups of the restored properties, so they stay overrides
        kept-touched-groups
        (into #{}
              (comp (keep #(ctk/resolve-sync-group (:type new-shape) %))
                    (filter #(ctk/touched-group? shape %)))
              (keys keep-props-values))]

    [new-shape (-> changes
                   ;; Restore the properties
                   (pcb/update-shapes [(:id new-shape)]
                                      #(cond-> (d/patch-object % keep-props-values)
                                         (seq kept-touched-groups)
                                         (update :touched (fnil into #{}) kept-touched-groups))
                                      {:ignore-touched true})

                   ;; We need to set the same index as the original shape
                   (pcb/change-parent (:parent-id shape) [new-shape] index {:allow-altering-copies true
                                                                            :ignore-touched true}))]))

(defn generate-component-swap
  "Replace the copy head `shape` with a copy of `id-new-component`. With
   `ignore-swapped?`, a set of the swapped shapes of its subtree that the
   caller keeps (or true for all of them), those are not deleted with it."
  [changes objects shape file page libraries id-new-component
   index target-cell keep-props-values ignore-swapped?]
  (let [;; When we keep the touched properties, we can't delete the
        ;; swapped children we keep
        ignore-swapped-fn
        (cond
          (set? ignore-swapped?) #(contains? ignore-swapped? %)
          ignore-swapped?        #(ctk/swapped? (get objects %))
          :else                  (constantly false))

        ;; In the cases where the swapped shape was the first element of the masked group it would make the group to loose the
        ;; mask property as part of the sanitization check on generate-delete-shapes, passing "ignore-mask" to prevent this
        [all-parents changes]
        (-> changes
            (cls/generate-delete-shapes
             file page objects (d/ordered-set (:id shape))
             {:allow-altering-copies true :ignore-children-fn ignore-swapped-fn :ignore-mask true :ignore-flows-for #{(:id shape)}}))
        [new-shape changes]
        (-> changes
            (generate-new-shape-for-swap shape file page libraries id-new-component index target-cell keep-props-values))
        ;; A switch carries overrides before taking untouched constraints.
        changes (cond-> changes
                  (not ignore-swapped?)
                  (generate-sync-swap-constraints page libraries (:id new-shape)))]
    [new-shape all-parents changes]))
