;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.gradient-form
  "Works out what the gradient token form edits and shows. It keeps the
  value of the form and the stops of its gradient editor. It adds, moves
  and reverses stops. It converts a pasted CSS gradient.

  The form edits `{:reference .. :kind .. :angle .. :stops [..]}`. The
  reference tab fills `:reference`. The composite tab fills the other
  keys. The token keeps one of the two: the reference string or the
  gradient map.

  In the form, the position of a stop is a percentage, as in the
  gradient editor of the color picker. A reference or an expression
  stays as written."
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.gradient :as wtg]
   [app.util.i18n :refer [tr]]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [tokenscript.core :as tsc]
   [tokenscript.document.dtcg :as tsd]
   [tokenscript.importers.css :as tsi]
   [tokenscript.symbols :as sym]))

(def gradient-kinds #{"linear" "radial"})

(def ^:private default-token-stops
  [{:color "#000000" :position "0"}
   {:color "#ffffff" :position "100"}])

(def ^:private default-token-gradient
  {:kind "linear"
   :angle (str wtg/default-angle)
   :stops default-token-stops})

(defn- with-stop-ids
  "Gives each stop an `:id`. The form keeps the ids while the stops move,
  so that each row and what it shows follow its stop."
  [stops]
  (into [] (map-indexed (fn [index stop] (assoc stop :id index))) stops))

(defn next-stop-id
  "Gives the id of the next stop that the form adds to `stops`."
  [stops]
  (inc (reduce max -1 (keep :id stops))))

(defn selected-index
  "Finds the index of the selected stop, which has the id `id`. The
  selection follows its stop when stops are added, moved or removed.
  When that stop is gone, gives the index of the first stop."
  [stops id]
  (or (d/index-of-pred stops #(= id (:id %))) 0))

(defn stop-id-at
  "Gives the id of the stop at `index`."
  [stops index]
  (:id (get stops index)))

(defn- percent
  "Reads the percentage in a position field: the field is a plain number,
  as tokenscript reads it. Gives nil for a reference or an expression."
  [position]
  (let [number (tsc/literal-number position)]
    (when (sym/number? number)
      (sym/value number))))

(defn- format-percent
  [offset]
  (str (mth/precision (* offset 100) 2)))

(defn- stored-offset
  "Gives the offset, from 0 to 1, of a position that a token keeps. The
  token keeps a fraction (`0.5`) or a percentage (`50%`). Gives nil for
  a reference, an expression or a position out of range."
  [position]
  (let [offset (tsd/gradient-offset position)]
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
  "Makes the gradient that the token keeps from the fields of the
  composite tab. A radial gradient has no angle."
  [{:keys [kind angle stops]}]
  (cond-> {:kind kind
           :stops (mapv (fn [stop]
                          (-> (select-keys stop [:color :position])
                              (d/update-when :position position->token)))
                        stops)}
    (and (= "linear" kind) (not (str/blank? angle)))
    (assoc :angle angle)))

(defn angle-token
  "Makes the token that the angle field of the gradient `value` resolves
  as. This is a rotation token. A rotation takes any angle unit, the
  same as the angle of a gradient token."
  [value]
  (d/without-nils
   {:type :rotation
    :value (get value :angle)}))

(defn stop-token
  "Makes the token that a field of a stop resolves as. `field` is
  `:color` or `:position`, and `index` is the index of the stop in the
  gradient `value`. A position has no type of its own, because the
  gradient checks its range."
  [value index field]
  (d/without-nils
   {:type (when (= :color field) :color)
    :value (get-in value [:stops index field])}))

(defn position-hint-hidden?
  "Checks whether the position field hides the value that its position
  resolves to. A plain percentage already shows that value, so the field
  hides it. For a reference or an expression, the field shows it."
  [position]
  (some? (percent position)))

(defn complete-stop?
  "Checks whether a stop has a color and a position."
  [{:keys [color position]}]
  (not (or (str/blank? color) (str/blank? position))))

(defn- marker-stop
  "Gives a stop of the form as the strip draws it. Plain values stay as
  they are. References give the values that they resolve to. The color
  that the input of the stop resolved has priority, because it moves
  with the stop. Gives nil while the stop has no color or no position."
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
  "Gives the stops of the form as the strip draws them. A stop with
  references gets its values from the gradient that the form resolves
  to."
  [stops resolved]
  (let [resolved-stops (when (= (count stops) (count (:stops resolved)))
                         (:stops resolved))]
    (into []
          (keep-indexed (fn [index stop]
                          (marker-stop index stop (get resolved-stops index))))
          stops)))

(defn with-resolved-colors
  "Adds to each stop the color that it resolves to. The color inputs keep
  this color to draw their swatch. An input resolves its color only
  after an edit. So when the form opens a token, the stops get these
  colors from its resolved gradient."
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
  "Writes a color as the form does: hex, or rgba when the color has an
  opacity."
  [{:keys [color opacity]}]
  (if (< opacity 1)
    (-> (tinycolor/valid-color color)
        (tinycolor/set-alpha (mth/precision opacity 2))
        (tinycolor/->string "rgba"))
    color))

(defn set-stop-opacity
  "Sets the opacity of the color of a stop. A color that is a reference
  keeps the opacity that it resolves to."
  [value index opacity]
  (update-in value [:stops index]
             (fn [{:keys [color] :as stop}]
               (if-let [tc (tinycolor/valid-color color)]
                 (assoc stop :color (stop-color {:color (tinycolor/->hex-string tc)
                                                 :opacity opacity}))
                 stop))))

(defn add-stop
  "Adds a stop at the point where the user clicked the strip. The new
  stop goes after the stops before that point and gets the color that
  the gradient has there."
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
  "Makes the gradient run the other way by mirroring its stops. When a
  position is a reference, the positions stay and the colors swap places
  instead."
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
  "Moves the stop at `from` to `to`. As in the color picker, the
  positions stay where they are, so the colors of the stops change
  places."
  [value from to]
  (let [stops (:stops value)
        moved (d/insert-at-index stops to [(get stops from)])]
    (assoc value :stops
           (mapv (fn [stop other]
                   (assoc other :position (:position stop)))
                 stops moved))))

(defn- parse-angle
  "Reads a literal angle (`90`, `45deg`, `0.25turn`, `1rad`, `100grad`)
  as tokenscript reads it: its number, and its unit when the unit
  registry converts that unit to degrees. A number without a unit is in
  degrees. A blank angle gives the default angle. Gives nil for a
  reference, an expression or a unit that is no angle."
  [angle]
  (if (str/blank? angle)
    {:number wtg/default-angle :unit ""}
    (let [number (tsc/literal-number angle)]
      (cond
        (sym/number? number)
        {:number (sym/value number) :unit ""}

        (some? (tsc/convert-number number "deg"))
        {:number (sym/value number) :unit (str/lower (:unit number))}))))

(defn rotatable-angle?
  "Checks whether the rotate button can turn `angle`. The button cannot
  turn a reference or an expression."
  [angle]
  (some? (parse-angle angle)))

(defn- turns
  "Gives a quarter turn and a full turn in the angle unit `unit`, as the
  unit registry converts 90 and 360 degrees to it."
  [unit]
  (let [from-degrees #(sym/value (tsc/convert-number (sym/number-with-unit % "deg") unit))]
    {:quarter (from-degrees 90)
     :full    (from-degrees 360)}))

(defn rotate-angle
  "Turns the angle a quarter turn further, in its own unit. A reference
  or an expression stays as it is."
  [angle]
  (if-let [{:keys [number unit]} (parse-angle angle)]
    (let [{:keys [quarter full]} (turns (if (= "" unit) "deg" unit))]
      (str (mth/precision (mod (+ number quarter) full) 4) unit))
    angle))

(defn- referenced?
  [{:keys [color position]}]
  (or (nil? (tinycolor/valid-color color))
      (nil? (percent position))))

(defn- resolved-title
  "Writes what a stop with a reference resolves to, for the strip to
  show."
  [stop marker]
  (when (referenced? stop)
    (tr "workspace.tokens.resolved-value"
        (str (stop-color marker) " " (dwtf/format-position (:offset marker))))))

(defn editor-stops
  "Gives the stops of the form for the gradient editor, which lists all
  stops. Each stop is as the strip draws it. While a stop does not
  resolve, it has no position."
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
  "Reads the form fields of the gradient in `text`, when `text` is a
  tokenscript or CSS `linear-gradient()`."
  [text]
  (some-> (tsi/linear-gradient text)
          (walk/keywordize-keys)
          (make-default-value)))

(defn converted-paste
  "Converts a pasted CSS gradient to tokenscript. Gives nil when the text
  is not a CSS gradient, or when it is tokenscript already."
  [text]
  (when-let [converted (tsi/linear-gradient->tokenscript text)]
    (when (not= converted (str/trim text))
      converted)))
