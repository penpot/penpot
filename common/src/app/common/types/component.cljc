;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.component
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.time :as-alias ct]
   [app.common.types.page :as ctp]
   [app.common.types.path.segment :as segment]
   [app.common.types.plugins :as ctpg]
   [app.common.types.variant :as ctv]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:component
  [:merge
   [:map
    [:id ::sm/uuid]
    [:name :string]
    [:path :string]
    [:modified-at {:optional true} ::ct/inst]
    [:objects {:gen/max 10 :optional true} ctp/schema:objects]
    [:main-instance-id ::sm/uuid]
    [:main-instance-page ::sm/uuid]
    [:plugin-data {:optional true} ctpg/schema:plugin-data]]
   ctv/schema:variant-component])

(def check-component
  (sm/check-fn schema:component))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Attributes that may be synced in components, and the group they belong to.
;; When one attribute is modified in a shape inside a component, the
;; corresponding group is marked as :touched. Then, if the shape is synced with
;; the remote shape in the main component, none of the attributes of the same
;; group is changed.

(def sync-attrs
  {:name                    :name-group
   :fills                   :fill-group
   :hide-fill-on-export     :fill-group
   :content                 {:path :geometry-group
                             :text :content-group}
   :position-data           :content-group
   :hidden                  :visibility-group
   :blocked                 :modifiable-group
   :grow-type               :text-font-group
   :font-family             :text-font-group
   :font-size               :text-font-group
   :font-style              :text-font-group
   :font-weight             :text-font-group
   :letter-spacing          :text-display-group
   :line-height             :text-display-group
   :text-align              :text-display-group
   :strokes                 :stroke-group

   ;; DEPRECATED: FIXME: this attrs are deprecated for a long time but
   ;; we still have tests that uses this attribute for synchronization
   :stroke-width            :stroke-group
   :fill-color              :fill-group
   :fill-opacity            :fill-group

   :r1                      :radius-group
   :r2                      :radius-group
   :r3                      :radius-group
   :r4                      :radius-group
   :type                    :geometry-group
   :selrect                 :geometry-group
   :points                  :geometry-group
   :locked                  :geometry-group
   :proportion              :geometry-group
   :proportion-lock         :geometry-group
   :x                       :geometry-group
   :y                       :geometry-group
   :width                   :geometry-group
   :height                  :geometry-group
   :rotation                :geometry-group
   :transform               :geometry-group
   :transform-inverse       :geometry-group
   :opacity                 :layer-effects-group
   :blend-mode              :layer-effects-group
   :shadow                  :shadow-group
   :blur                    :blur-group
   :background-blur         :blur-group
   :masked-group            :mask-group
   :constraints-h           :constraints-group
   :constraints-v           :constraints-group
   :fixed-scroll            :constraints-group
   :bool-type               :content-group
   :bool-content            :content-group
   :exports                 :exports-group
   :grids                   :grids-group

   :show-content            :show-content

   :layout                  :layout-container

   :layout-align-content    :layout-align-content
   :layout-align-items      :layout-align-items
   :layout-flex-dir         :layout-flex-dir
   :layout-gap              :layout-gap
   :layout-gap-type         :layout-gap
   :layout-justify-content  :layout-justify-content
   :layout-justify-items    :layout-justify-items
   :layout-wrap-type        :layout-wrap-type
   :layout-padding-type     :layout-padding
   :layout-padding          :layout-padding

   :layout-grid-dir         :layout-grid-dir
   :layout-grid-rows        :layout-grid-rows
   :layout-grid-columns     :layout-grid-columns
   :layout-grid-cells       :layout-grid-cells

   :layout-item-margin      :layout-item-margin
   :layout-item-margin-type :layout-item-margin
   :layout-item-h-sizing    :layout-item-h-sizing
   :layout-item-v-sizing    :layout-item-v-sizing
   :layout-item-max-h       :layout-item-max-h
   :layout-item-min-h       :layout-item-min-h
   :layout-item-max-w       :layout-item-max-w
   :layout-item-min-w       :layout-item-min-w
   :layout-item-absolute    :layout-item-absolute
   :layout-item-z-index     :layout-item-z-index
   :layout-item-align-self  :layout-item-align-self})

(def swap-keep-attrs
  #{:layout-item-margin
    :layout-item-margin-type
    :layout-item-h-sizing
    :layout-item-v-sizing
    :layout-item-max-h
    :layout-item-min-h
    :layout-item-max-w
    :layout-item-min-w
    :layout-item-absolute
    :layout-item-z-index
    :layout-item-align-self
    :interactions})

(defn resolve-sync-group
  "Makes a by type resolution of the sync group. This is necessary
  because we have several properties that has different group
  depending on the shape type. Per example the attr `:content` is used
  by path and text shapes and the sync groups are different for each
  shape type."
  [type attr]
  (when-let [group (get sync-attrs attr)]
    (if (map? group)
      (get group type)
      group)))

(defn component-attr?
  "Check if some attribute is one that is involved in component synchronization.
   Note that design tokens also are involved, although they go by an alternate
   route and thus they are not part of :sync-attrs.
   Also when detaching a nested copy it also needs to trigger a synchronization,
   even though :shape-ref, :component-id or :component-file are not synced
   attributes per se."
  [attr]
  (or (contains? sync-attrs attr)
      (= :shape-ref attr)
      (= :applied-tokens attr)
      (= :component-id attr)
      (= :component-file attr)
      (= :component-root attr)))

(defn instance-root?
  "Check if this shape is the head of a top instance."
  [shape]
  (true? (:component-root shape)))

(defn instance-head?
  "Check if this shape is the head of a top instance or a subinstance."
  [shape]
  (some? (:component-id shape)))

(defn subinstance-head?
  "Check if this shape is the head of a subinstance."
  [shape]
  (and (some? (:component-id shape))
       (nil? (:component-root shape))))

(defn subcopy-head?
  "Check if this shape is the head of a subinstance that is a copy."
  [shape]
  ;; This is redundant with the previous one, but may give more security
  ;; in case of bugs.
  (and (some? (:component-id shape))
       (nil? (:component-root shape))
       (some? (:shape-ref shape))))

(defn instance-of?
  [shape file-id component-id]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) component-id)
       (= (:component-file shape) file-id)))

(defn is-main-of?
  [shape-main shape-inst]
  (= (:shape-ref shape-inst) (:id shape-main)))

(defn main-instance?
  "Check if this shape is the root of the main instance of some
  component."
  [shape]
  (true? (:main-instance shape)))

(defn in-component-copy?
  "Check if the shape is inside a component non-main instance."
  [shape]
  (some? (:shape-ref shape)))

(defn in-component-copy-not-head?
  "Check if the shape is inside a component non-main instance and
  is not the head of a subinstance."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-id shape))))

(defn in-component-copy-not-root?
  "Check if the shape is inside a component non-main instance and
  is not the root shape."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-root shape))))

(defn main-instance-of?
  "Check if this shape is the root of the main instance of the given component."
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn is-variant?
  "Check if this shape or component is a variant component"
  [item]
  (some? (:variant-id item)))

(defn is-variant-container?
  "Check if this shape is a variant container"
  [shape]
  (boolean (:is-variant-container shape)))

(defn is-variant-main?
  "True when this shape is a variant component main and not the variant
   container."
  [shape]
  (and (is-variant? shape)
       (not (is-variant-container? shape))))

(defn variant-formula-rename?
  "True when renaming this shape with `name` should update the variant
   formula instead of the layer or component name.

   `name` must already be trimmed by the caller."
  [shape name]
  (and (is-variant-main? shape)
       (some? (:variant-name shape))
       (string? name)
       (not (str/blank? name))))

(defn set-touched-group
  [touched group]
  (when group
    (conj (or touched #{}) group)))

(defn touched-group?
  [shape group]
  ((or (:touched shape) #{}) group))

(defn build-swap-slot-group
  "Convert a swap-slot into a :touched group"
  [swap-slot]
  (when swap-slot
    (keyword (str "swap-slot-" swap-slot))))

(defn swap-slot?
  [group]
  (str/starts-with? (name group) "swap-slot-"))

(def ^:private xf:normal-touched
  "Transducer that removes swap-slot touched groups."
  (remove swap-slot?))

(defn normal-touched-groups
  "Gets all touched groups that are not swap slots.
  Returns an empty set immediately when `:touched` is nil or empty,
  avoiding an unnecessary `into #{}` allocation for the common case."
  [shape]
  (let [touched (:touched shape)]
    (if (empty? touched)
      #{}
      (into #{} xf:normal-touched touched))))

(defn group->swap-slot
  [group]
  (parse-uuid (subs (name group) 10)))  ;; 10 is the length of "swap-slot-"

(defn get-swap-slot
  "If the shape has a :touched group in the form :swap-slot-<uuid>, get the id."
  [shape]
  (let [group (d/seek swap-slot? (:touched shape))]
    (when group
      (group->swap-slot group))))

(defn set-swap-slot
  "Add a touched group with a form :swap-slot-<uuid>."
  [shape swap-slot]
  (cond-> shape
    (some? swap-slot)
    (update :touched set-touched-group (build-swap-slot-group swap-slot))))

(defn match-swap-slot?
  [shape-main shape-inst]
  (let [slot-main   (get-swap-slot shape-main)
        slot-inst   (get-swap-slot shape-inst)]
    (when (some? slot-inst)
      (or (= slot-main slot-inst)
          (= (:id shape-main) slot-inst)))))

(defn remove-swap-slot
  [shape]
  (update shape :touched
          (fn [touched]
            (into #{} (remove #(str/starts-with? (name %) "swap-slot-") touched)))))

(defn get-deleted-component-root
  [component]
  (if (some? (:main-instance-id component))
    (get-in component [:objects (:main-instance-id component)])
    (get-in component [:objects (:id component)])))

(defn uses-library-components?
  "Check if the shape uses any component in the given library."
  [shape library-id]
  (and (some? (:component-id shape))
       (= (:component-file shape) library-id)))

(defn detach-shape
  "Remove the links and leave it as a plain shape, detached from any component."
  [shape]
  (dissoc shape
          :component-id
          :component-file
          :component-root
          :main-instance
          :remote-synced
          :shape-ref
          :touched))

(defn unhead-shape
  "Make the shape not be a component head, but keep its :shape-ref and :touched if it was a nested copy"
  [shape]
  (dissoc shape
          :component-root
          :component-file
          :component-id
          :main-instance))

(defn rehead-shape
  "Make the shape a component head, by adding component info"
  [shape component-file component-id]
  (assoc shape
         :component-file component-file
         :component-id component-id))

(defn- extract-ids [shape]
  (if (map? shape)
    (let [current-id (:id shape)
          child-ids  (mapcat extract-ids (:children shape))]
      (cons current-id child-ids))
    []))

(defn diff-components
  "Compare two components, and return a set of the keys with different values"
  [comp1 comp2]
  (let [eq (fn [key val1 val2]
             (if (= key :objects)
               (= (extract-ids val1) (extract-ids val2))
               (= val1 val2)))]
    (->> (concat (keys comp1) (keys comp2))
         (distinct)
         (filter #(not (eq % (get comp1 %) (get comp2 %))))
         set)))

(defn allow-duplicate?
  [objects shape]

  (let [parent (get objects (:parent-id shape))]
    ;; We don't want to change the structure of component copies
    (and (not (in-component-copy-not-head? shape))
         ;; Non instance, non copy. We allow
         (or (not (instance-head? shape))
             (not (in-component-copy? parent))))))

(def ^:private all-touched-groups
  (reduce-kv (fn [acc _ v]
               (if (map? v)
                 (into acc (vals v))
                 (conj acc v)))
             #{}
             sync-attrs))

(defn valid-touched-group?
  [group]
  (ex/ignoring
   (or (contains? all-touched-groups group)
       (and (swap-slot? group)
            (some? (group->swap-slot group))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SYNC ATTRIBUTE COMPARISON
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Attributes that sync updates automatically: all sync attrs except the
;; flex-child attrs (kept on swap) and the layout-grid-cells property.
(def updatable-attrs
  (->> (keys sync-attrs)
       ;; We don't update the flex-child attrs
       (remove swap-keep-attrs)
       ;; We don't do automatic update of the `layout-grid-cells` property.
       (remove #(= :layout-grid-cells %))))

;; Attrs whose value drifts by float noise when a shape is moved; they
;; are compared with tolerance during sync. The rest compare exactly.
(def ^:private drifting-geometry-attrs
  #{:x :y :width :height :selrect :points})

;; Constraints where nil means "the default for the shape's placement",
;; so nil and that default are the same value.
(def ^:private default-constraints-fns
  {:constraints-h gsh/default-constraints-h
   :constraints-v gsh/default-constraints-v
   :fixed-scroll (constantly false)})

(defn- position-data-close?
  "Compares text position-data entry by entry: numbers with tolerance,
  every other field (fills, fonts, text) exactly."
  [a b]
  (let [a (vec a)
        b (vec b)]
    (and (= (count a) (count b))
         (every? true? (map mth/close-map? a b)))))

(defn- path-shape?
  [shape]
  (= :path (:type shape)))

(defn- bool-shape?
  [shape]
  (= :bool (:type shape)))

(defn attr-equal?
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
               (or (path-shape? origin-shape) (bool-shape? origin-shape)))
          (segment/content-close? origin-val dest-val)

          (= :position-data attr)
          (position-data-close? origin-val dest-val)

          (contains? default-constraints-fns attr)
          (let [default ((get default-constraints-fns attr) dest-shape)]
            (= (or origin-val default) (or dest-val default)))

          :else
          false))))

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
    (if-not (touched-group? dest-root :geometry-group)
      shape
      (let [origin-transform (d/nilv (:transform origin-root) (gmt/matrix))
            dest-transform   (d/nilv (:transform dest-root) (gmt/matrix))
            rel-transform    (gmt/multiply dest-transform (gmt/inverse origin-transform))]
        (if ^boolean (gmt/unit? rel-transform)
          shape
          ;; The roots differ in rotation/flips: rotate the whole (already moved)
          ;; shape around the dest root center by the roots' relative transform.
          ;; The :rotation attribute delta is fed through the :modifiers path so
          ;; apply-transform keeps it consistent with the resulting matrix.
          (let [center       (grc/rect->center (:selrect dest-root))
                rel-rotation (mod (- (d/nilv (:rotation dest-root) 0)
                                     (d/nilv (:rotation origin-root) 0))
                                  360)]
            (-> shape
                (assoc-in [:modifiers :rotation] rel-rotation)
                (gsh/apply-transform (gmt/transform-in center rel-transform))
                (dissoc :modifiers))))))))

(defn- fills-equal-ignoring-image-ids?
  "Whether both fills are equal ignoring the :id of their :fill-image. Each
   copy of a component can reference its own copy of the image media, so the
   id can differ between a copy and its main without being an override."
  [fills-main fills-inst]
  (let [strip-ids (fn [fills]
                    (mapv (fn [fill]
                            (if-let [image (:fill-image fill)]
                              (assoc fill :fill-image (dissoc image :id))
                              fill))
                          fills))]
    (= (strip-ids fills-main)
       (strip-ids fills-inst))))

(defn- ignore-group-difference?
  "Whether a difference in `attr` (of sync group `group`) between the copy and
   its main must not be reported as a missing touched group, because copies
   can differ from their mains in that attribute by design. This is the place
   to add special handling for other false positives."
  [attr group shape-main shape-inst]
  (or
   ;; Geometry cannot be analyzed: when a copy is put in a layout or a
   ;; constrained container it changes size, but that does not mean it's
   ;; touched. The position data of texts is derived, so it cannot be
   ;; analyzed either.
   (= :geometry-group group)
   (= :position-data attr)

   ;; The :id of a fill image may differ between the copy and the main,
   ;; because each copy may reference its own copy of the image media. That
   ;; is not an override.
   (and (= :fills attr)
        (fills-equal-ignoring-image-ids? (:fills shape-main)
                                         (:fills shape-inst)))))

(defn find-missing-touched-groups
  "Return the set of sync groups whose attributes differ between the copy
   `shape-inst` and its main `shape-main`, after repositioning the main into
   the copy's root frame, and are not marked in the copy's `:touched`. An
   empty set means the copy keeps no untracked override.

   `root-inst` and `root-main` are the roots of the copy and main instances,
   used to normalize the geometry of both shapes as sync does."
  [shape-inst shape-main root-inst root-main]
  (let [shape-main (reposition-shape shape-main root-main root-inst)
        touched    (get shape-inst :touched #{})]
    (persistent!
     (reduce (fn [result attr]
               (let [group (or (resolve-sync-group (:type shape-main) attr)
                               (resolve-sync-group (:type shape-inst) attr))]
                 (if (and (some? group)
                          (not (touched group))
                          (not (attr-equal? attr shape-main shape-inst))
                          (not (ignore-group-difference? attr group shape-main shape-inst)))
                   (conj! result group)
                   result)))
             (transient #{})
             updatable-attrs))))
