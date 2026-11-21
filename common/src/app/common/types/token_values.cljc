;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.token-values
  "The values a shape was computed with for the tokens applied to it.

  `:token-values` maps each token attribute of `:applied-tokens` to the
  resolved value of its token when the shape was last set to it. For a
  token attribute of a shape:

  - with a value: the shape holds that value; it needs an update when its
    token resolves to another one;
  - missing from `:token-values`: stale, the shape changed in a way that
    may not match its token, so it needs an update;
  - in a shape without `:token-values`: unassigned, the shape comes from
    before the values were kept, so they are unknown.

  The values are kept up to date when the changes are applied, the same in
  every runtime: a write to an attribute that a token sets marks it stale,
  unless the write is derived from other data or sets its value too."
  (:require
   [app.common.data :as d]
   [app.common.types.stroke :as cts]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [clojure.set :as set]))

;; --- Reading

(defn assigned?
  "True when `shape` keeps the values of its tokens."
  [shape]
  (contains? shape :token-values))

(defn out-of-date-attrs
  "Token attributes of `shape` whose token resolves, in `resolved-tokens`, to
  a value it doesn't hold: stale, with another value or, with
  `:unassigned?`, unassigned."
  [{:keys [applied-tokens token-values] :as shape} resolved-tokens {:keys [unassigned?]}]
  (let [assigned (assigned? shape)]
    (into #{}
          (keep (fn [[attr token-name]]
                  (when-let [value (get-in resolved-tokens [token-name :resolved-value])]
                    (when (if assigned
                            (or (not (contains? token-values attr))
                                (not= value (get token-values attr)))
                            unassigned?)
                      attr))))
          applied-tokens)))

(defn orphan-attrs
  "Token attributes with a value in `shape` but no token applied."
  [{:keys [applied-tokens token-values]}]
  (into #{} (remove #(contains? applied-tokens %)) (keys token-values)))

;; --- Updating

(defn set-values
  "Set in `shape` the values of `values`, a map of token attribute to value;
  a nil value marks the attribute stale."
  [shape values]
  (update shape :token-values
          (fn [token-values]
            (reduce-kv (fn [token-values attr value]
                         (if (nil? value)
                           (dissoc token-values attr)
                           (assoc token-values attr value)))
                       (or token-values {})
                       values))))

(defn values-diff
  "Map of the token attributes whose value differs from `old` to `new`, two
  maps of `:token-values`, to their value in `new` (nil when missing)."
  [old new]
  (into {}
        (keep (fn [attr]
                (let [value (get new attr)]
                  (when (not= (get old attr) value)
                    [attr value]))))
        (d/concat-set (keys old) (keys new))))

(defn mark-stale
  "Mark stale the token attributes `attrs` of `shape`."
  [shape attrs]
  (if (and (assigned? shape) (seq attrs))
    (update shape :token-values #(apply dissoc % attrs))
    shape))

;; --- Writes to the attributes that tokens set

(def ^:private stroke-color-attrs
  [:stroke-color :stroke-opacity :stroke-color-gradient :stroke-image
   :stroke-color-ref-id :stroke-color-ref-file])

(defn- changed-keys
  [old new ks]
  (into #{} (filter #(not= (get old %) (get new %))) ks))

(defn- written-token-attrs
  "Token attributes whose value changes when `attr` changes from `old` to
  `new`, two versions of a shape."
  [old new attr]
  (case attr
    :fills
    (when (not= (first (:fills old)) (first (:fills new)))
      #{:fill})

    :strokes
    (let [old-stroke (first (:strokes old))
          new-stroke (first (:strokes new))]
      (cond-> (into #{}
                    (filter #(not= (cts/side-width old-stroke %)
                                   (cts/side-width new-stroke %)))
                    cto/per-side-stroke-width-keys)
        (not= (select-keys old-stroke stroke-color-attrs)
              (select-keys new-stroke stroke-color-attrs))
        (conj :stroke-color)))

    :layout-padding
    (changed-keys (:layout-padding old) (:layout-padding new) [:p1 :p2 :p3 :p4])

    :layout-gap
    (changed-keys (:layout-gap old) (:layout-gap new) [:row-gap :column-gap])

    :layout-item-margin
    (changed-keys (:layout-item-margin old) (:layout-item-margin new) [:m1 :m2 :m3 :m4])

    :content
    (when (= :text (:type new))
      (into #{}
            (mapcat cto/shape-attr->token-attrs)
            (txt/get-diff-attrs (:content old) (:content new))))

    (cto/shape-attr->token-attrs attr)))

(def ^:private ignored-attrs
  #{:applied-tokens :token-values :touched :remote-synced :position-data
    :selrect :points :transform :transform-inverse})

(def ^:private context-attrs
  "Shape attributes that change the result of some tokens of the same shape
  without being set by them, with the token attributes they affect. A size
  that a layout holds (fill or hug) goes back to its token when the layout
  stops holding it."
  {:layout               (into cto/spacing-gap-padding-keys [:width :height])
   :layout-item-h-sizing #{:width}
   :layout-item-v-sizing #{:height}
   :blocked              cto/rotation-keys})

(defn- written-attrs
  "Shape attributes that `operations` write in a way that may not match
  their tokens: not derived and actually changing."
  [old new operations]
  (into #{}
        (comp (filter #(= :set (:type %)))
              (remove :derived)
              (map :attr)
              (remove ignored-attrs)
              (filter #(not= (get old %) (get new %))))
        operations))

(defn- covered-attrs
  "Token attributes whose value `operations` set, or `::all` when they set
  the whole `:token-values`."
  [operations]
  (reduce (fn [covered {:keys [type attr values]}]
            (cond
              (= type :set-token-values) (into covered (keys values))
              (and (= type :set) (= attr :token-values)) (reduced ::all)
              :else covered))
          #{}
          operations))

(defn update-after-operations
  "Keep `:token-values` of `new`, the shape `old` after `operations`, in line
  with what they changed:

  - the attributes whose token is removed lose their value; a new token on
    an attribute has no value yet, so it is stale. A value stays when only
    the token changes: it still tells what the shape holds;
  - the attributes written in a way that may not match their tokens, and
    the ones that depend on what changed, are marked stale;

  unless the operations set their values too."
  [old new operations]
  (if (or (empty? (:applied-tokens new))
          (not (assigned? new)))
    (cond-> new
      (and (assigned? new) (empty? (:applied-tokens new)))
      (dissoc :token-values))
    (let [covered (covered-attrs operations)]
      (if (= covered ::all)
        new
        (let [new-tokens (:applied-tokens new)
              written    (written-attrs old new operations)
              stale      (-> #{}
                             (into (mapcat #(written-token-attrs old new %)) written)
                             (into (mapcat context-attrs) written)
                             (set/intersection (set (keys new-tokens)))
                             (set/difference covered))]
          (-> new
              (mark-stale stale)
              (update :token-values #(apply dissoc % (orphan-attrs new)))))))))

;; --- Context of the shapes

(def ^:private placement-attrs
  "Token attributes whose result depends on the parent of the shape."
  (set/union cto/spacing-margin-keys cto/axis-keys))

(defn layout-held-attrs
  "Token attributes of `shape` whose size the layout of its parent holds:
  the axes where it fills it."
  [shape]
  (cond-> #{}
    (= :fill (:layout-item-h-sizing shape)) (conj :width)
    (= :fill (:layout-item-v-sizing shape)) (conj :height)))

(defn mark-placement-stale
  "Mark stale the token attributes of `shape` whose result depends on its
  parent, after it moves to another one or is added."
  [shape]
  (mark-stale shape placement-attrs))
