;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.previews
  "Visual previews of the two sides of a change. The diff carries whole
  entities for conflicts and for added or deleted shapes; for a modified
  entity the open file holds one side and the changed attrs give the
  other, which is enough to draw both the layer and the board it sits in."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.render :as render]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private renderable-shape-types
  "Shape types we can faithfully render to SVG from a shape map. Images /
  svg-raw reference (possibly cross-file) media and fall back to a type
  icon instead."
  #{:rect :circle :frame :path :bool :text :group})

(def ^:private box-shape-types
  "Shape types whose geometry is their position and size; a path or a
  boolean takes it from its content, a group from its children."
  #{:rect :circle :frame :text :image :svg-raw})

(defn open-side
  "Which document the open file is for `branch`: `:branch` when it is the
  branch file, `:main` otherwise."
  [branch file-id]
  (if (and (some? file-id) (= file-id (:branch-file-id branch)))
    :branch
    :main))

(defn side-entity
  "One side (`:main` or `:branch`) of diff entry `item`. A conflict carries
  both; a clean change carries the attrs that differ, so the side the open
  file does not hold is the open entity with those attrs swapped in. An
  entry that does not exist on a side (added on the other one, or
  deleted) has no entity there. An added or deleted shape the open file
  lacks comes whole in the entry's `:shape`."
  [item side open-side open-entity]
  (cond
    (= :conflict (:status item))
    (get item side)

    (= side open-side)
    open-entity

    (nil? open-entity)
    (:shape item)

    (and (= :added (:status item)) (= side :main))
    nil

    (and (= :deleted (:status item)) (= side :branch))
    nil

    :else
    (let [attrs  (:changed-attrs item)
          entity (reduce-kv (fn [entity attr values]
                              (let [v (get values side)]
                                (if (nil? v)
                                  (dissoc entity attr)
                                  (assoc entity attr v))))
                            open-entity
                            attrs)]
      ;; the diff leaves out `:selrect` and `:points` (they follow from
      ;; position and size), so a box that moved or resized gets them
      ;; again from its own, or it would draw with the open side's
      (if (and (contains? box-shape-types (:type entity))
               (some #(contains? attrs %) [:x :y :width :height]))
        (cts/setup-rect (dissoc entity :selrect :points))
        entity))))

(defn side-objects
  "The page `objects` as they stand on `side`, with every shape change of
  `items` that lives on this page put on that side. A shape missing on
  that side is taken out of its parent, so boards draw without it, and a
  shape only that side holds is put into its parent, so boards draw it."
  [objects items side open-side]
  (reduce
   (fn [objects item]
     (if (not= :shape (:kind item))
       objects
       (let [id     (:id item)
             entity (side-entity item side open-side (get objects id))]
         (if (some? entity)
           (let [parent-id (:parent-id entity)]
             (cond-> (assoc objects id entity)
               (and (contains? objects parent-id)
                    (not (contains? objects id))
                    (not (some #(= id %) (get-in objects [parent-id :shapes]))))
               (update-in [parent-id :shapes] (fnil conj []) id)))
           (let [parent-id (:parent-id (get objects id))]
             (cond-> (dissoc objects id)
               (contains? objects parent-id)
               (update-in [parent-id :shapes] (fn [ids] (filterv #(not= id %) ids)))))))))
   objects
   items))

(defn- shape-bounds
  "Axis-aligned bounds framing a shape, taken from its rotated corner
  `:points` when present, else its `:selrect`."
  [shape]
  (let [pts (:points shape)]
    (if (and (sequential? pts) (seq pts))
      (let [xs (keep :x pts) ys (keep :y pts)
            x (apply min xs) y (apply min ys)]
        {:x x :y y :width (- (apply max xs) x) :height (- (apply max ys) y)})
      (let [sr (:selrect shape)]
        {:x (:x sr 0) :y (:y sr 0) :width (:width sr 1) :height (:height sr 1)}))))

(defn context-frame-id
  "The nearest board that holds shape `id` in `objects`, the page root
  left out: the stage an added or deleted layer is shown on."
  [objects id]
  (loop [parent-id (:parent-id (get objects id))
         depth     0]
    (let [parent (get objects parent-id)]
      (cond
        (or (nil? parent) (= parent-id uuid/zero) (>= depth 64)) nil
        (= :frame (:type parent)) parent-id
        :else (recur (:parent-id parent) (inc depth))))))

(mf/defc shape-preview*
  "Renders `shape` to a fitted SVG with the real shape renderers. `objects`
  supplies its children (a board or group draws them). `highlight`
  (`{:id :status}`) outlines the layer an added or deleted change is
  about, when `objects` holds it."
  {::mf/private true}
  [{:keys [shape objects highlight]}]
  (let [{:keys [x y width height]} (shape-bounds shape)
        width   (max 1 width)
        height  (max 1 height)
        objects (mf/with-memo [shape objects]
                  (assoc (or objects {}) (:id shape) shape))
        marked  (some->> (:id highlight) (get objects) shape-bounds)
        wrapper (mf/with-memo [objects] (render/shape-wrapper-factory objects))]
    [:svg {:class (stl/css :preview-svg)
           :viewBox (dm/str x " " y " " width " " height)
           :preserveAspectRatio "xMidYMid meet"
           :xmlns "http://www.w3.org/2000/svg"
           :fill "none"}
     [:> wrapper {:shape shape}]
     (when marked
       [:rect {:class (stl/css-case :preview-highlight true
                                    :is-added (= :added (:status highlight))
                                    :is-deleted (= :deleted (:status highlight)))
               :x (:x marked)
               :y (:y marked)
               :width (max 1 (:width marked))
               :height (max 1 (:height marked))
               :vector-effect "non-scaling-stroke"}])]))

(mf/defc page-preview*
  "The whole page `objects` drawn to fit: every top-level layer, framed
  together."
  [{:keys [objects]}]
  (let [tops    (mf/with-memo [objects]
                  (into [] (keep #(get objects %)) (:shapes (get objects uuid/zero))))
        bounds  (mf/with-memo [tops]
                  (when (seq tops)
                    (let [bs (map shape-bounds tops)
                          x1 (apply min (map :x bs))
                          y1 (apply min (map :y bs))
                          x2 (apply max (map #(+ (:x %) (:width %)) bs))
                          y2 (apply max (map #(+ (:y %) (:height %)) bs))]
                      {:x x1 :y y1 :width (max 1 (- x2 x1)) :height (max 1 (- y2 y1))})))
        wrapper (mf/with-memo [objects] (render/shape-wrapper-factory objects))]
    (if bounds
      (let [{:keys [x y width height]} bounds]
        [:svg {:class (stl/css :preview-svg)
               :viewBox (dm/str x " " y " " width " " height)
               :preserveAspectRatio "xMidYMid meet"
               :xmlns "http://www.w3.org/2000/svg"
               :fill "none"}
         (for [shape tops]
           [:> wrapper {:key (str (:id shape)) :shape shape}])])
      [:div {:class (stl/css :preview-empty)}
       [:> i/icon* {:icon-id i/document :size "s"}]])))

(defn missing-reason
  "Why `side` of diff entry `item` has nothing to draw. `:absent` when the
  entry never existed there (the other side added it), `:deleted` when
  that side deleted it, `:unavailable` when it exists there but the open
  file cannot draw it. `incoming` says the listed changes are main's."
  [item side incoming]
  (let [origin (if incoming :main :branch)]
    (case (:status item)
      :conflict :deleted
      :added    (if (= side origin) :unavailable :absent)
      :deleted  (if (= side origin) :deleted :unavailable)
      :unavailable)))

(mf/defc missing-preview*
  "The empty stage of a side with nothing to draw, saying why."
  {::mf/private true}
  [{:keys [reason side open-side]}]
  (let [main? (= side :main)
        [icon title hint]
        (case reason
          :absent
          [i/add
           (tr (if main? "workspace.branches.review.missing.not-in-main"
                   "workspace.branches.review.missing.not-in-branch"))
           (tr (if main? "workspace.branches.review.missing.new-in-branch"
                   "workspace.branches.review.missing.new-in-main"))]
          :deleted
          [i/delete
           (tr (if main? "workspace.branches.review.missing.deleted-in-main"
                   "workspace.branches.review.missing.deleted-in-branch"))
           nil]
          [i/eye-off
           (tr "workspace.branches.review.missing.no-preview")
           (when (and open-side (not= side open-side))
             (tr (if main? "workspace.branches.review.missing.open-main"
                     "workspace.branches.review.missing.open-branch")))])]
    [:div {:class (stl/css :preview-missing)}
     [:> i/icon* {:icon-id icon :size "s" :class (stl/css :preview-missing-icon)}]
     [:> text* {:as "span" :typography t/body-small :class (stl/css :preview-missing-title)} title]
     (when hint
       [:> text* {:as "span" :typography t/body-small :class (stl/css :preview-missing-hint)} hint])]))

(mf/defc entity-preview*
  "Visual preview of one side of a change, dispatching on entity kind:
  shapes render to SVG, library colors to a swatch, typographies to a text
  sample; everything else shows a type icon. A side with nothing to draw
  says why (`missing`: `{:reason :side :open-side}`, see
  `missing-reason`). A shape `highlight` is passed on to `shape-preview*`."
  [{:keys [kind value objects missing highlight]}]
  (cond
    (nil? value)
    [:> missing-preview* {:reason (:reason missing :unavailable)
                          :side (:side missing)
                          :open-side (:open-side missing)}]

    (= kind :shape)
    (if (contains? renderable-shape-types (:type value))
      [:> shape-preview* {:shape value :objects objects :highlight highlight}]
      [:div {:class (stl/css :preview-empty)}
       [:> i/icon* {:icon-id (brc/shape-icon-id {:shape-type (:type value)})}]])

    (= kind :color)
    [:div {:class (stl/css :preview-swatch)
           :style {:background (or (uc/color->background value)
                                   (brc/hex-color value)
                                   "transparent")}}]

    (= kind :typography)
    [:div {:class (stl/css :preview-type-sample)
           :style {:font-family (:font-family value)
                   :font-weight (:font-weight value)
                   :font-style (:font-style value)}}
     "Ag"]

    :else
    [:div {:class (stl/css :preview-empty)}
     [:> i/icon* {:icon-id (get brc/kind->icon kind i/git-branch)}]]))
