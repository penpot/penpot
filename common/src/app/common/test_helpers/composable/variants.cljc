;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.variants
  "Variant fixture diversity and an independent nearest-member prediction."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.modifiers :as ctm]
   [clojure.string :as str]))

(defn members
  [color rich? differ?]
  (cond-> [["a" color] ["b" (if differ? "#ff0000" color)]]
    rich? (into [["c" "#00ff00"] ["d" color]])))

(defn diversify
  "Build four members before any copies exist: varied bounds, a missing,
   extra or renamed icon, shared/different text, and three properties."
  [situation set-name]
  (let [members (:members (n/variant-set situation set-name))
        properties [["Small" "Idle" "Light"] ["Large" "Idle" "Dark"]
                    ["Small" "Pressed" "Dark"] ["Large" "Pressed" "Light"]]]
    (tm/with-file situation
      (thf/validate-file!
       (reduce
        (fn [file [index {:keys [component-label root-label rect-label]}]]
          (let [root-id (thi/id root-label)
                page (thf/current-page file)
                size (nth [[100 100] [140 100] [120 140] [80 100]] index)
                changes (cls/generate-update-shapes
                         (pcb/empty-changes nil (:id page)) [root-id]
                         #(gsh/transform-shape % (apply ctm/change-size % size))
                         (:objects page) {})
                props (mapv (fn [name value] {:name name :value value})
                            ["Size" "State" "Theme"] (nth properties index))
                file (-> (thf/apply-changes file changes :validate? false)
                         (thc/update-component component-label {:variant-properties props})
                         (ths/update-shape root-label :variant-name (str/join ", " (map :value props)))
                         (ths/update-shape rect-label :name (if (= index 2) "Glyph" "Icon"))
                         (ths/update-shape rect-label :constraints-h (nth [:left :leftright :scale :center] index)))
                file (if (= index 3)
                       (let [page (thf/current-page file)]
                         (thf/apply-changes file
                                            (second (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                                                (:data file) page (:objects page) #{(thi/id rect-label)} {}))
                                            :validate? false))
                       file)
                text-label (keyword (str (name root-label) "-caption"))
                text (if (= index 2) "Other text" "Same text")
                content (thtx/content [[[text {:size (if (= index 2) 22 14)}]]])]
            (cond-> (ths/add-sample-shape file text-label :type :text :name "Caption"
                                          :parent-label root-label :width 70 :height 25
                                          :grow-type :auto-height :content content)
              (= index 1)
              (ths/add-sample-shape (keyword (str (name root-label) "-badge"))
                                    :type :rect :name "Badge" :width 12 :height 12 :parent-label root-label))))
        (tm/file situation) (map-indexed vector members))))))

(defn nearest
  "Prefer matches in earlier unchanged properties, then container order.
   This predicts the contract without calling the production distance or
   target-selection functions."
  [library shape pos value]
  (let [data (:data library)
        origin (get-in data [:components (:component-id shape)])
        properties (:variant-properties origin)
        objects (get-in data [:pages-index (:main-instance-page origin) :objects])
        roots (get-in objects [(:variant-id origin) :shapes])
        members (keep #(get-in data [:components (get-in objects [% :component-id])]) roots)]
    (when (not= value (get-in properties [pos :value]))
      (first
       (sort-by (fn [candidate]
                  (mapv (fn [index property]
                          (if (or (= index pos) (= property (nth (:variant-properties candidate) index))) 0 1))
                        (range) properties))
                (filter #(and (not (:deleted %)) (= value (get-in % [:variant-properties pos :value]))) members))))))

(defn set-components
  "The live components of the variant set `variant-id` of `file`, in the
   order of the children of its container, on whatever page it is."
  [file variant-id]
  (let [data (:data file)
        objects (some #(when (contains? (:objects %) variant-id) (:objects %))
                      (vals (:pages-index data)))]
    (->> (get-in objects [variant-id :shapes])
         (keep #(get-in data [:components (get-in objects [% :component-id])]))
         (remove :deleted)
         (filter #(= variant-id (:variant-id %)))
         vec)))

(defn- reorder
  "`items` with the one at `from` moved to the gap before position `to`."
  [items from to]
  (let [item (nth items from)
        rest (into (subvec items 0 from) (subvec items (inc from)))
        at (if (> to from) (dec to) to)]
    (into (conj (subvec rest 0 at) item) (subvec rest at))))

(defn- remove-at
  [items pos]
  (into (subvec items 0 pos) (subvec items (inc pos))))

(defn property-edit
  "A property edit of the set of `components` chosen by the natural numbers
   `selector`, `choice` and `other`: `{:edit :params :expected}`, where
   `:expected` maps each component id to its properties after the edit. A
   name in `:expected` is a string, `{:base name}` for a requested name that
   may take a ` (n)` suffix to stay unique, or `:generated` for a
   `Property n` name. Nil when the set has no property the edit needs."
  [components selector choice other]
  (let [props (vec (:variant-properties (first components)))
        n (count props)
        edit (nth [:add :remove :rename :reorder :set-value] (mod selector 5))
        pos (when (pos? n) (mod choice n))
        by-component (fn [f] (into {} (map (juxt :id #(f (vec (:variant-properties %))))) components))]
    (case edit
      :add
      (let [name (nth [nil "Size" "Mode"] (mod choice 3))
            value (nth [nil "On"] (mod other 2))]
        {:edit :add
         :params (cond-> {} name (assoc :property-name name) value (assoc :property-value value))
         :expected (by-component #(conj % {:name (if name {:base name} :generated) :value (or value "")}))})

      :remove
      (when (> n 1)
        {:edit :remove :params {:pos pos}
         :expected (by-component #(remove-at % pos))})

      :rename
      (when pos
        (let [name (nth [(:name (nth props (mod (inc pos) n))) "Kind" "Size"] (mod other 3))]
          {:edit :rename :params {:pos pos :name name}
           :expected (by-component #(assoc-in % [pos :name] {:base name}))}))

      :reorder
      (when (> n 1)
        (let [to (mod other (inc n))]
          {:edit :reorder :params {:from pos :to to}
           :expected (by-component #(reorder % pos to))}))

      :set-value
      (when pos
        (let [component (nth components (mod other (count components)))
              others (remove #(= (:id %) (:id component)) components)
              value (nth [(get-in (vec (:variant-properties (first others))) [pos :value]) "On" ""]
                         (mod (quot choice n) 3))
              value (or value "On")]
          {:edit :set-value :params {:member (:id component) :pos pos :value value}
           :expected (assoc (by-component identity)
                            (:id component)
                            (assoc-in (vec (:variant-properties component)) [pos :value] value))})))))

(defn- name-matches?
  [expected actual]
  (cond
    (string? expected) (= expected actual)
    (= :generated expected) (some? (re-matches #"Property \d+" actual))
    :else (let [requested (:base expected)
                base (str/replace requested #" \(\d+\)$" "")]
            (or (= requested actual)
                (and (str/starts-with? actual (str base " ("))
                     (str/ends-with? actual ")")
                     (some? (re-matches #"\d+" (subs actual (+ 2 (count base)) (dec (count actual))))))))))

(defn properties-match?
  "Whether the properties `actual` are the `expected` of `property-edit`,
   with distinct names."
  [expected actual]
  (and (= (count expected) (count actual))
       (every? true? (map (fn [e a]
                            (and (= (:value e) (:value a))
                                 (name-matches? (:name e) (:name a))))
                          expected actual))
       (or (empty? actual) (apply distinct? (map :name actual)))))
