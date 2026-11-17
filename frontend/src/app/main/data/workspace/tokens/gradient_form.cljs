;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.gradient-form
  "What the gradient token form edits and shows: the value of the form
  and the stops of its gradient editor, with the steps that change them
  (adding, moving, reversing a stop) and the conversion of a pasted CSS
  gradient.

  The form edits `{:reference .. :kind .. :angle .. :stops [..]}`: the
  reference tab fills `:reference`, the composite tab the rest. The token
  keeps one of both: the reference string, or the gradient map. A stop's
  position is a percentage in the form, as in the gradient editor of the
  color picker, unless it is a reference or an expression."
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.gradient :as wtg]
   [app.util.i18n :refer [tr]]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [tokenscript.importers.css :as tsi]))

(def gradient-kinds #{"linear" "radial"})

(def ^:private default-token-stops
  [{:color "#000000" :position "0"}
   {:color "#ffffff" :position "100"}])

(def ^:private default-token-gradient
  {:kind "linear"
   :angle (str wtg/default-angle)
   :stops default-token-stops})

(defn- with-stop-ids
  "The stops with an `:id` each, which the form keeps while they move:
  a row and what it shows about its stop follow the stop."
  [stops]
  (into [] (map-indexed (fn [index stop] (assoc stop :id index))) stops))

(defn next-stop-id
  "The id the next stop added to `stops` gets."
  [stops]
  (inc (reduce max -1 (keep :id stops))))

(defn selected-index
  "The index of the selected stop, the one with the id `id`: the
  selection follows its stop when stops are added, moved or removed.
  The first stop when it is gone."
  [stops id]
  (or (d/index-of-pred stops #(= id (:id %))) 0))

(defn stop-id-at
  "The id of the stop at `index`."
  [stops index]
  (:id (get stops index)))

(defn- percent
  "The percentage a position field holds. Nil for a reference or an
  expression."
  [position]
  (when (and (string? position)
             (re-matches #"^\s*-?\d+(?:\.\d+)?\s*$" position))
    (d/parse-double position)))

(defn- format-percent
  [offset]
  (str (mth/precision (* offset 100) 2)))

(defn- stored-offset
  "The offset, from 0 to 1, of a position a token keeps: a fraction (`0.5`)
  or a percentage (`50%`). Nil for a reference, an expression or a
  position out of range."
  [position]
  (let [offset (ctob/gradient-position->offset (str position))]
    (when (and (some? offset) (<= 0 offset 1))
      offset)))

(defn- position->form
  [position]
  (if-let [offset (stored-offset position)]
    (format-percent offset)
    position))

(defn- position->token
  [position]
  (if (some? (percent position))
    (str (str/trim position) "%")
    position))

(defn make-default-value
  [value]
  (-> (cond
        (string? value)
        (assoc default-token-gradient :reference value)

        (map? value)
        {:reference nil
         :kind (:kind value "linear")
         :angle (:angle value "")
         :stops (mapv #(d/update-when % :position position->form) (:stops value))}

        :else
        (assoc default-token-gradient :reference nil))
      (update :stops with-stop-ids)))

(defn form-value->token-value
  "The gradient the token keeps, from the fields of the composite tab. A
  radial gradient has no angle."
  [{:keys [kind angle stops]}]
  (cond-> {:kind kind
           :stops (mapv (fn [stop]
                          (-> (select-keys stop [:color :position])
                              (d/update-when :position position->token)))
                        stops)}
    (and (= "linear" kind) (not (str/blank? angle)))
    (assoc :angle angle)))

(defn angle-token
  "The token the angle field of the gradient `value` resolves as: a
  rotation, which takes any unit, as the angle of a gradient token
  does."
  [value]
  (d/without-nils
   {:type :rotation
    :value (get value :angle)}))

(defn stop-token
  "The token the field `field` (`:color` or `:position`) of the stop at
  `index` of the gradient `value` resolves as. A position has no type of
  its own: the gradient checks its range."
  [value index field]
  (d/without-nils
   {:type (when (= :color field) :color)
    :value (get-in value [:stops index field])}))

(defn position-hint-hidden?
  "Whether the position field hides the value its position resolves to:
  a plain percentage shows it already. A reference or an expression
  shows it."
  [position]
  (some? (percent position)))

(defn complete-stop?
  "Whether a stop has its color and its position."
  [{:keys [color position]}]
  (not (or (str/blank? color) (str/blank? position))))

(defn- marker-stop
  "A stop of the form as the strip draws it: with its own color and
  position when they are plain values, else with the ones they resolve
  to. The color its input resolved comes first, since it moves with the
  stop. Nil while the stop has no color or no position."
  [index {:keys [color color-result position]} resolved]
  (let [color    (d/seek tinycolor/valid-color
                         (filter string? [color color-result (:color resolved)]))
        position (if-let [percent (percent position)]
                   (when (<= 0 percent 100)
                     (/ percent 100))
                   (:position resolved))]
    (when (and (some? color) (some? position))
      (let [{:keys [color opacity] :as stop} (wtg/value->stop {:color color :position position})
            [r g b] (cc/hex->rgb color)]
        (assoc stop :index index :r r :g g :b b :alpha opacity)))))

(defn marker-stops
  "The stops of the form as the strip draws them, the ones with
  references as the gradient the form resolves to has them."
  [stops resolved]
  (let [resolved-stops (when (= (count stops) (count (:stops resolved)))
                         (:stops resolved))]
    (into []
          (keep-indexed (fn [index stop]
                          (marker-stop index stop (get resolved-stops index))))
          stops)))

(defn with-resolved-colors
  "The stops with the color each resolves to, as their color inputs keep
  it to draw their swatch. An input only resolves its color once it is
  edited, so a token that is opened gets them from its resolved
  gradient."
  [stops resolved]
  (if (= (count stops) (count (:stops resolved)))
    (mapv (fn [stop {:keys [color]}]
            (cond-> stop
              (and (str/blank? (:color-result stop))
                   (nil? (tinycolor/valid-color (:color stop))))
              (assoc :color-result color)))
          stops (:stops resolved))
    stops))

(defn move-stop
  [value index offset]
  (assoc-in value [:stops index :position] (format-percent offset)))

(defn- stop-color
  "A color as the form writes it: hex, or rgba when it has an opacity."
  [{:keys [color opacity]}]
  (if (< opacity 1)
    (-> (tinycolor/valid-color color)
        (tinycolor/set-alpha (mth/precision opacity 2))
        (tinycolor/->string "rgba"))
    color))

(defn set-stop-opacity
  "Sets the opacity of the color of a stop. A color that is a reference
  keeps the opacity it resolves to."
  [value index opacity]
  (update-in value [:stops index]
             (fn [{:keys [color] :as stop}]
               (if-let [tc (tinycolor/valid-color color)]
                 (assoc stop :color (stop-color {:color (tinycolor/->hex-string tc)
                                                 :opacity opacity}))
                 stop))))

(defn add-stop
  "Adds a stop where the strip was clicked, after the stops before it,
  with the color the gradient has there."
  [value markers offset]
  (let [sorted (vec (sort-by :offset markers))
        stop   {:id (next-stop-id (:stops value))
                :color (if (seq sorted)
                         (stop-color (cc/interpolate-gradient sorted offset))
                         "")
                :position (format-percent offset)}
        index  (->> markers
                    (filter #(<= (:offset %) offset))
                    (map :index)
                    (reduce max -1)
                    (inc))]
    (update value :stops #(into (subvec % 0 index) (cons stop (subvec % index))))))

(defn add-stop-auto
  "Adds a stop in the middle of the widest gap between two stops. A
  gradient that does not resolve yet gets an empty stop at its end."
  [value markers]
  (let [gaps (partition 2 1 (sort-by :offset markers))]
    (if (seq gaps)
      (let [[from to] (apply max-key (fn [[from to]] (- (:offset to) (:offset from))) gaps)]
        (add-stop value markers (/ (+ (:offset from) (:offset to)) 2)))
      (update value :stops (fnil conj []) {:id (next-stop-id (:stops value))
                                           :color ""
                                           :position "100"}))))

(defn reverse-stops
  "Makes the gradient run the other way: its stops are mirrored. With a
  position that is a reference the positions stay and the colors swap
  places instead."
  [value]
  (let [stops    (:stops value)
        percents (map (comp percent :position) stops)]
    (assoc value :stops
           (if (every? some? percents)
             (->> (map (fn [stop percent]
                         (assoc stop :position (format-percent (/ (- 100 percent) 100))))
                       stops percents)
                  (reverse)
                  (vec))
             (mapv (fn [stop other]
                     (-> (dissoc stop :color :color-result)
                         (merge (select-keys other [:color :color-result]))))
                   stops (reverse stops))))))

(defn reorder-stops
  "Moves the stop at `from` to `to`. As in the color picker the positions
  stay where they are, so the colors of the stops change places."
  [value from to]
  (let [stops (:stops value)
        moved (d/insert-at-index stops to [(get stops from)])]
    (assoc value :stops
           (mapv (fn [stop other]
                   (assoc other :position (:position stop)))
                 stops moved))))

(def ^:private angle-turns
  "A quarter and a full turn in each unit an angle may have. Without a
  unit an angle is in degrees."
  {"deg"  {:quarter 90 :full 360}
   "turn" {:quarter 0.25 :full 1}
   "grad" {:quarter 100 :full 400}
   "rad"  {:quarter (/ mth/PI 2) :full (* 2 mth/PI)}})

(def ^:private literal-angle-re
  "A literal angle as tokenscript reads it: a number (`.5` too), with an
  angle unit in any case right after it, no space between."
  #"(?i)^\s*(-?(?:\d+(?:\.\d+)?|\.\d+))(deg|turn|grad|rad)?\s*$")

(defn- parse-angle
  "The number and the unit of a literal angle (`90`, `45deg`, `0.25turn`,
  `1rad`, `100grad`). A blank angle is the default one. Nil for a
  reference or an expression."
  [angle]
  (if (str/blank? angle)
    {:number wtg/default-angle :unit ""}
    (when-let [[_ number unit] (re-matches literal-angle-re angle)]
      {:number (d/parse-double number)
       :unit (if unit (str/lower unit) "")})))

(defn rotatable-angle?
  "Whether the rotate button can turn `angle`: a reference or an
  expression can't be turned."
  [angle]
  (some? (parse-angle angle)))

(defn rotate-angle
  "The angle a quarter turn further, in its own unit. An angle that is a
  reference or an expression is kept."
  [angle]
  (if-let [{:keys [number unit]} (parse-angle angle)]
    (let [{:keys [quarter full]} (get angle-turns (if (= "" unit) "deg" unit))]
      (str (mth/precision (mod (+ number quarter) full) 4) unit))
    angle))

(defn- referenced?
  [{:keys [color position]}]
  (or (nil? (tinycolor/valid-color color))
      (nil? (percent position))))

(defn- resolved-title
  "What a stop with a reference resolves to, to show it on the strip."
  [stop marker]
  (when (referenced? stop)
    (tr "workspace.tokens.resolved-value"
        (str (stop-color marker) " " (dwtf/format-position (:offset marker))))))

(defn editor-stops
  "The stops of the form for the gradient editor, which lists them all:
  each as the strip draws it, or without a position while it does not
  resolve."
  [stops markers]
  (let [markers (d/index-by :index markers)]
    (into []
          (map-indexed (fn [index stop]
                         (if-let [marker (get markers index)]
                           (assoc marker
                                  :id (:id stop)
                                  :title (resolved-title stop marker))
                           {:index index :id (:id stop)})))
          stops)))

(defn css->form-value
  "The fields of the gradient for the tokenscript or CSS `linear-gradient()`
  `text`, when it is one."
  [text]
  (some-> (tsi/linear-gradient text)
          (walk/keywordize-keys)
          (make-default-value)))

(defn converted-paste
  "What a pasted CSS gradient is converted to for tokenscript. Nil when the
  text is no CSS gradient or is already written as tokenscript."
  [text]
  (when-let [converted (tsi/linear-gradient->tokenscript text)]
    (when (not= converted (str/trim text))
      converted)))

(defn pasted-text
  "The text of a field after `pasted` is pasted over what is selected in
  it, from `start` to `end`."
  [text start end pasted]
  (str (subs text 0 start) pasted (subs text end)))
