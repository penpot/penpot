;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.gradient-form-test
  (:require
   [app.common.schema :as sm]
   [app.common.schema.messages :as csm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.gradient-form :as wtgf
    :refer [add-stop add-stop-auto converted-paste css->form-value editor-stops
            marker-stops move-stop pasted-text reorder-stops reverse-stops
            rotate-angle set-stop-opacity with-resolved-colors]]
   [app.main.ui.workspace.tokens.management.forms.controls.input :as controls.input]
   [app.main.ui.workspace.tokens.management.forms.gradient :as gform]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]))

(def ^:private make-schema @#'gform/make-schema)
(def ^:private check-self-reference @#'gform/check-gradient-token-self-reference)
(def ^:private check-empty @#'gform/check-empty-gradient-token)
(def ^:private form-value wtgf/make-default-value)
(def ^:private token-value wtgf/form-value->token-value)
(def ^:private resolve-with-tokenscript @#'controls.input/resolve-value-tokenscript)

(def ^:private stops
  [{:color "{color.brand}" :position "0"}
   {:color "#0000ff" :position "1"}])

(def ^:private form-stops
  [{:color "{color.brand}" :position "0"}
   {:color "#0000ff" :position "100"}])

(def ^:private gradient
  {:kind "linear" :angle "90" :stops stops})

(def ^:private token
  (ctob/make-token :name "gradient.brand" :type :gradient :value gradient))

(defn- schema
  [tab]
  (make-schema (ctob/get-token-path token)
               (ctob/tokens-tree {(:name token) token})
               tab))

(defn- form-data
  [value]
  {:name (:name token) :description "" :value value})

(defn- composite
  [gradient]
  (form-data (assoc gradient :reference nil)))

(defn- with-ids
  [stops]
  (into [] (map-indexed (fn [index stop] (assoc stop :id index))) stops))

(defn- errors
  [tab data]
  (csm/collect-schema-errors (schema tab) [] {:data data}))

(defn- valid?
  [tab data]
  (sm/validate (schema tab) data))

(t/deftest composite-tab
  (t/testing "a linear and a radial gradient are valid"
    (t/is (valid? :composite (composite gradient)))
    (t/is (valid? :composite (composite (assoc gradient :kind "radial")))))

  (t/testing "the angle can stay blank"
    (t/is (valid? :composite (composite (assoc gradient :angle "")))))

  (t/testing "a gradient needs two stops"
    (t/is (not (valid? :composite (composite (update gradient :stops subvec 0 1)))))
    (t/is (= (tr "errors.tokens.invalid-token-value-gradient")
             (get-in (errors :composite (composite (assoc gradient :stops [])))
                     [:value :message]))))

  (t/testing "every stop needs its color and position"
    (t/is (not (valid? :composite (composite (assoc-in gradient [:stops 1 :color] "")))))
    (t/is (not (valid? :composite (composite (assoc-in gradient [:stops 0 :position] " ")))))
    (t/is (not (valid? :composite (composite (update-in gradient [:stops 0] dissoc :color))))))

  (t/testing "only kinds a shape can draw"
    (t/is (not (valid? :composite (composite (assoc gradient :kind "conic")))))))

(t/deftest reference-tab
  (t/testing "a reference is required"
    (t/is (not (valid? :reference (form-data (assoc gradient :reference "")))))
    (t/is (valid? :reference (form-data (assoc gradient :reference "{gradient.base}")))))

  (t/testing "the token can't reference itself"
    (t/is (= (tr "errors.tokens.self-reference")
             (get-in (errors :reference (form-data (assoc gradient :reference "{gradient.brand}")))
                     [:value :reference :message])))))

(t/deftest field-tokens
  (t/testing "the angle resolves as a rotation"
    (t/is (= {:type :rotation :value "45deg"}
             (wtgf/angle-token (assoc gradient :angle "45deg"))))
    (t/is (= {:type :rotation}
             (wtgf/angle-token "{gradient.base}"))))

  (t/testing "a stop's color resolves as a color, its position without a type"
    (t/is (= {:type :color :value "{color.brand}"}
             (wtgf/stop-token gradient 0 :color)))
    (t/is (= {:value "1"}
             (wtgf/stop-token gradient 1 :position)))))

(defn- resolve-angle
  "Resolves `angle` as the angle field does, against `tokens` as the only
  set of the library."
  [tokens angle]
  (async/->promise
   (resolve-with-tokenscript [[:core tokens]]
                             (wtgf/angle-token (assoc gradient :angle angle))
                             (:name token)
                             angle)))

(t/deftest ^:async angle-field-resolves-as-the-gradient-angle
  (let [quarter   (ctob/make-token :name "rotation.quarter" :type :rotation :value "90deg")
        tokens    {(:name quarter) quarter}
        with-unit (await (resolve-angle tokens "45deg"))
        plain     (await (resolve-angle tokens "45"))
        reference (await (resolve-angle tokens "{rotation.quarter}"))
        color     (await (resolve-angle tokens "red"))
        turns     (await (resolve-angle tokens "0.25turn"))
        gradians  (await (resolve-angle tokens "100grad"))
        radians   (await (resolve-angle tokens "1rad"))]
    (t/testing "an angle takes a unit"
      (t/is (= {:value 45} with-unit)))

    (t/testing "an angle in turn, grad or rad resolves to degrees"
      (t/is (= {:value 90} turns))
      (t/is (= {:value 90} gradians))
      (t/is (< 57.29 (:value radians) 57.30)))

    (t/testing "an angle takes no unit"
      (t/is (= {:value 45} plain)))

    (t/testing "an angle references a rotation token"
      (t/is (= {:value 90} reference)))

    (t/testing "an angle that is no rotation is an error"
      (t/is (some? (:error color))))))

(t/deftest ^:async rotated-angles-resolve
  (let [angles   ["90" "45deg" "0.25turn" "100grad" "1rad" "" "90DEG" ".5turn"]
        rotated  (mapv rotate-angle angles)
        resolved (await (js/Promise.all
                         (into-array (map #(resolve-angle {} %) rotated))))]
    (t/testing "every angle the rotate button writes resolves"
      (t/is (= ["180" "135deg" "0.5turn" "200grad" "2.5708rad" "270" "180deg" "0.75turn"] rotated))
      (t/is (every? #(nil? (:error %)) resolved)))

    (t/testing "each resolves to its angle a quarter turn further, in degrees"
      (t/is (= [180 135 180 180 147 270 180 270]
               (mapv #(js/Math.round (:value %)) resolved))))))

(t/deftest initial-form-value
  (t/testing "a gradient opens on the composite tab with its fields"
    (t/is (= (assoc gradient :reference nil :stops (with-ids form-stops))
             (form-value gradient))))

  (t/testing "positions show as percentages, references as they are"
    (t/is (= ["25" "50" "{position.end}"]
             (->> (form-value {:kind "linear"
                               :stops [{:color "#ff0000" :position "0.25"}
                                       {:color "#00ff00" :position "50%"}
                                       {:color "#0000ff" :position "{position.end}"}]})
                  :stops
                  (map :position)))))

  (t/testing "a radial gradient has a blank angle"
    (t/is (= {:reference nil :kind "radial" :angle "" :stops (with-ids form-stops)}
             (form-value {:kind "radial" :stops stops}))))

  (t/testing "a reference keeps a default gradient for the composite tab"
    (let [value (form-value "{gradient.base}")]
      (t/is (= "{gradient.base}" (:reference value)))
      (t/is (= 2 (count (:stops value))))))

  (t/testing "a new token starts with a default gradient"
    (let [value (form-value nil)]
      (t/is (nil? (:reference value)))
      (t/is (= "linear" (:kind value)))
      (t/is (= "180" (:angle value)))
      (t/is (= ["0" "100"] (map :position (:stops value)))))))

(t/deftest saved-token-value
  (let [form-gradient (assoc gradient :stops form-stops)
        saved-stops   [{:color "{color.brand}" :position "0%"}
                       {:color "#0000ff" :position "100%"}]]
    (t/testing "keeps the kind, the angle and the stops, with their positions as percentages"
      (t/is (= (assoc gradient :stops saved-stops)
               (token-value (assoc form-gradient :reference nil)))))

    (t/testing ".5 is half a percent, as 0.5 is"
      (t/is (= [".5%" "0.5%"]
               (->> (-> form-gradient
                        (assoc-in [:stops 0 :position] ".5")
                        (assoc-in [:stops 1 :position] "0.5")
                        (token-value)
                        (:stops))
                    (mapv :position)))))

    (t/testing "a position that is a reference is kept"
      (t/is (= "{position.end}"
               (-> (assoc-in form-gradient [:stops 1 :position] "{position.end}")
                   (token-value)
                   (get-in [:stops 1 :position])))))

    (t/testing "drops what the form keeps for itself"
      (t/is (= (assoc gradient :stops saved-stops)
               (token-value (-> (update form-gradient :stops with-ids)
                                (assoc-in [:stops 0 :color-result] "#ff0000"))))))

    (t/testing "a radial gradient and a blank angle have no angle"
      (t/is (= {:kind "radial" :stops saved-stops}
               (token-value (assoc form-gradient :kind "radial"))))
      (t/is (= {:kind "linear" :stops saved-stops}
               (token-value (assoc form-gradient :angle " ")))))

    (t/testing "what is saved opens the same again"
      (t/is (= (update form-gradient :stops with-ids)
               (-> (token-value form-gradient) (form-value) (dissoc :reference)))))))

(t/deftest strip-stops
  (t/testing "plain colors and positions are drawn as they are"
    (t/is (= [{:index 0 :color "#ff0000" :opacity 1 :offset 0 :r 255 :g 0 :b 0 :alpha 1}
              {:index 1 :color "#0000ff" :opacity 0.5 :offset 0.5 :r 0 :g 0 :b 255 :alpha 0.5}]
             (marker-stops [{:color "#ff0000" :position "0"}
                            {:color "rgba(0, 0, 255, 0.5)" :position "50"}]
                           nil))))

  (t/testing "references are drawn as they resolve"
    (t/is (= [{:index 0 :color "#00ff00" :offset 0.25}
              {:index 1 :color "#0000ff" :offset 1}]
             (->> (marker-stops [{:color "{color.brand}" :position "{position.start}"}
                                 {:color "#0000ff" :position "100"}]
                                {:stops [{:color "#00ff00" :position 0.25}
                                         {:color "#0000ff" :position 1}]})
                  (map #(select-keys % [:index :color :offset]))))))

  (t/testing "the color its input resolved comes before the resolved gradient"
    (t/is (= ["#ff0000" "#0000ff"]
             (->> (marker-stops [{:color "{color.brand}" :color-result "#ff0000" :position "0"}
                                 {:color "#0000ff" :position "100"}]
                                {:stops [{:color "#00ff00" :position 0}
                                         {:color "#0000ff" :position 1}]})
                  (map :color)))))

  (t/testing "a reference that does not resolve is not drawn"
    (t/is (= [1] (map :index (marker-stops form-stops nil))))
    (t/is (= [1] (map :index (marker-stops form-stops {:stops [{:color "#00ff00" :position 0}]})))))

  (t/testing "a position outside the gradient is not drawn"
    (t/is (empty? (marker-stops [{:color "#ff0000" :position "150"}] nil)))))

(t/deftest stops-for-the-editor
  (let [resolved {:stops [{:color "#ff0000" :position 0.25}
                          {:color "#0000ff" :position 1}]}
        stops    (with-ids form-stops)
        listed   (fn [stops resolved]
                   (editor-stops stops (marker-stops stops resolved)))]
    (t/testing "every stop is listed, also one that does not resolve"
      (t/is (= [{:index 0 :id 0}
                {:index 1 :id 1 :offset 1}]
               (->> (listed stops nil)
                    (map #(select-keys % [:index :id :offset]))))))

    (t/testing "a stop with a reference shows what it resolves to on the strip"
      (t/is (= (tr "workspace.tokens.resolved-value" "#ff0000 0%")
               (:title (first (listed stops resolved)))))
      (t/is (= (tr "workspace.tokens.resolved-value" "rgba(255, 0, 0, 0.5) 25%")
               (-> (assoc-in stops [0 :position] "{position.start}")
                   (listed (assoc-in resolved [:stops 0 :color] "rgba(255, 0, 0, 0.5)"))
                   (first)
                   :title))))

    (t/testing "a stop with plain values has nothing to resolve"
      (t/is (nil? (:title (second (listed stops resolved))))))))

(t/deftest resolved-colors
  (let [resolved {:stops [{:color "#ff0000" :position 0}
                          {:color "#0000ff" :position 1}]}]
    (t/testing "a referenced color gets the color it resolves to"
      (t/is (= [(assoc (first form-stops) :color-result "#ff0000")
                (second form-stops)]
               (with-resolved-colors form-stops resolved))))

    (t/testing "the color an input resolved is kept"
      (let [stops (assoc-in form-stops [0 :color-result] "#00ff00")]
        (t/is (= stops (with-resolved-colors stops resolved)))))

    (t/testing "a gradient that does not resolve changes nothing"
      (t/is (= form-stops (with-resolved-colors form-stops nil))))))

(t/deftest strip-actions
  (let [red      {:color "#ff0000" :position "0"}
        blue     {:color "#0000ff" :position "100"}
        value    {:kind "linear" :angle "90" :stops [red blue]}
        markers  (fn [value] (marker-stops (:stops value) nil))]
    (t/testing "dragging a stop sets its position"
      (t/is (= "34" (get-in (move-stop value 1 0.34) [:stops 1 :position]))))

    (t/testing "a stop added on the strip goes between the stops around it"
      (let [value (assoc value :stops [red (assoc red :position "100")])
            added (add-stop value (markers value) 0.25)]
        (t/is (= ["0" "25" "100"] (map :position (:stops added))))
        (t/is (= "#ff0000" (get-in added [:stops 1 :color])))))

    (t/testing "the new stop takes the opacity the gradient has there"
      (let [faded {:color "rgba(255, 0, 0, 0.5)" :position "0"}
            value (assoc value :stops [faded (assoc faded :position "100")])]
        (t/is (= "rgba(255, 0, 0, 0.5)"
                 (get-in (add-stop value (markers value) 0.5) [:stops 1 :color])))))

    (t/testing "the add button fills the widest gap"
      (let [value (assoc value :stops [red (assoc red :position "20") blue])]
        (t/is (= ["0" "20" "60" "100"]
                 (map :position (:stops (add-stop-auto value (markers value))))))))

    (t/testing "a gradient that does not resolve gets an empty stop"
      (t/is (= {:color "" :position "100"}
               (-> (add-stop-auto value []) :stops last (dissoc :id)))))

    (t/testing "a new stop gets an id of its own"
      (let [value (update value :stops with-ids)]
        (t/is (= [0 2 1]
                 (map :id (:stops (add-stop value (markers value) 0.5)))))
        (t/is (= [0 1 2]
                 (map :id (:stops (add-stop-auto value [])))))))

    (t/testing "reversing mirrors the stops"
      (t/is (= [{:color "#0000ff" :position "0"}
                {:color "#00ff00" :position "75"}
                {:color "#ff0000" :position "100"}]
               (:stops (reverse-stops
                        (assoc value :stops [red {:color "#00ff00" :position "25"} blue]))))))

    (t/testing "a position of .5 is mirrored like any number"
      (t/is (= [{:color "#0000ff" :position "0"}
                {:color "#ff0000" :position "99.5"}]
               (:stops (reverse-stops
                        (assoc value :stops [(assoc red :position ".5") blue]))))))

    (t/testing "with a referenced position the colors swap places"
      (t/is (= [{:color "#0000ff" :position "{position.start}"}
                {:color "#ff0000" :position "100"}]
               (:stops (reverse-stops
                        (assoc value :stops [(assoc red :position "{position.start}") blue]))))))

    (t/testing "dragging a row moves its color, the positions stay"
      (let [green {:color "#00ff00" :position "50"}
            value (assoc value :stops (with-ids [red green blue]))]
        (t/is (= [{:id 1 :color "#00ff00" :position "0"}
                  {:id 2 :color "#0000ff" :position "50"}
                  {:id 0 :color "#ff0000" :position "100"}]
                 (:stops (reorder-stops value 0 3))))))

    (t/testing "the opacity of a stop is set on its color"
      (t/is (= "rgba(0, 0, 255, 0.4)"
               (get-in (set-stop-opacity value 1 0.4) [:stops 1 :color])))
      (t/is (= "#0000ff"
               (get-in (-> (set-stop-opacity value 1 0.4)
                           (set-stop-opacity 1 1))
                       [:stops 1 :color]))))

    (t/testing "a referenced color keeps its opacity"
      (let [value (assoc-in value [:stops 0 :color] "{color.brand}")]
        (t/is (= value (set-stop-opacity value 0 0.4)))))

    (t/testing "rotating turns the angle a quarter, in its own unit"
      (t/are [angle rotated] (= rotated (rotate-angle angle))
        "90"           "180"
        "270"          "0"
        "-90"          "0"
        "45deg"        "135deg"
        "0.25turn"     "0.5turn"
        "0.75turn"     "0turn"
        "100grad"      "200grad"
        "1rad"         "2.5708rad"
        ""             "270"
        "0.25TURN"     "0.5turn"
        "90DEG"        "180deg"
        ".5turn"       "0.75turn"
        "{angle.base}" "{angle.base}"
        "45 * 2"       "45 * 2"
        "90 deg"       "90 deg"))

    (t/testing "a reference or an expression can't be rotated"
      (t/is (wtgf/rotatable-angle? "90"))
      (t/is (wtgf/rotatable-angle? "0.25turn"))
      (t/is (wtgf/rotatable-angle? ""))
      (t/is (wtgf/rotatable-angle? nil))
      (t/is (not (wtgf/rotatable-angle? "{angle.base}")))
      (t/is (not (wtgf/rotatable-angle? "45 * 2"))))

    (t/testing "an angle is read as tokenscript reads it"
      (t/is (wtgf/rotatable-angle? "0.25TURN"))
      (t/is (wtgf/rotatable-angle? "90DEG"))
      (t/is (wtgf/rotatable-angle? ".5turn"))
      (t/is (not (wtgf/rotatable-angle? "90 deg"))))))

(t/deftest stop-selection
  (let [a       {:color "#ff0000" :position "0"}
        b       {:color "#00ff00" :position "50"}
        c       {:color "#0000ff" :position "100"}
        value   {:kind "linear" :angle "90" :stops (with-ids [a b c])}
        stops   (:stops value)
        markers (marker-stops stops nil)
        c-id    (wtgf/stop-id-at stops 2)]
    (t/testing "a stop is selected by its id"
      (t/is (= 2 (wtgf/selected-index stops c-id)))
      (t/is (= 0 (wtgf/selected-index stops nil))))

    (t/testing "the selection follows its stop when a stop is added before it"
      (let [stops' (:stops (add-stop value markers 0.25))]
        (t/is (= 3 (wtgf/selected-index stops' c-id)))
        (t/is (= "#0000ff" (:color (get stops' (wtgf/selected-index stops' c-id)))))))

    (t/testing "a new stop gets the id the selection is set to"
      (let [new-id (wtgf/next-stop-id stops)
            stops' (:stops (add-stop value markers 0.25))]
        (t/is (= 1 (wtgf/selected-index stops' new-id)))
        (t/is (= "25" (:position (get stops' 1)))))
      (let [new-id (wtgf/next-stop-id stops)
            stops' (:stops (add-stop-auto value markers))]
        (t/is (= new-id (:id (get stops' (wtgf/selected-index stops' new-id)))))))

    (t/testing "the selection follows its stop when the stops are reversed"
      (let [stops' (:stops (reverse-stops value))]
        (t/is (= 0 (wtgf/selected-index stops' c-id)))
        (t/is (= "#0000ff" (:color (get stops' 0))))))

    (t/testing "the first stop is selected when the selected one is removed"
      (t/is (= 0 (wtgf/selected-index (subvec stops 0 2) c-id))))))

(t/deftest position-field-hint
  (t/testing "a plain percentage hides what it resolves to"
    (t/is (wtgf/position-hint-hidden? "50"))
    (t/is (wtgf/position-hint-hidden? " 12.5 "))
    (t/is (wtgf/position-hint-hidden? ".5") "a number as tokenscript reads it"))

  (t/testing "a reference or an expression shows what it resolves to"
    (t/is (not (wtgf/position-hint-hidden? "{stop.mid}")))
    (t/is (not (wtgf/position-hint-hidden? "25 + 25"))))

  (t/testing "a percentage written with % is no plain number of the field"
    (t/is (not (wtgf/position-hint-hidden? "50%")))))

(t/deftest css-gradient
  (t/testing "a linear gradient is the value of the form, as a token holds it"
    (t/is (= {:reference nil
              :kind "linear"
              :angle "45"
              :stops (with-ids [{:color "#5500ff" :position "0"}
                                {:color "{color.brand}" :position "50"}
                                {:color "#00ff55" :position "100"}])}
             (css->form-value "linear-gradient(45deg, #5500ff, {color.brand}, #00ff55)"))))

  (t/testing "the value is valid on the composite tab"
    (t/is (valid? :composite (form-data (css->form-value "linear-gradient(to right, red 20%, blue)")))))

  (t/testing "a reference alias and other text have no fields"
    (t/are [text] (nil? (css->form-value text))
      nil
      ""
      "{gradient.base}"
      "linear-gradient(red"
      "radial-gradient(red, blue)")))

(t/deftest pasted-css
  (t/testing "CSS is converted to tokenscript"
    (t/is (= "linear-gradient(90deg, red 0%, blue 100%)"
             (converted-paste "linear-gradient(to right, red, blue)"))))

  (t/testing "the declarations a gradient generator copies"
    (t/is (= "linear-gradient(90deg, rgba(42, 123, 155, 1) 0%, rgba(87, 199, 133, 1) 50%, rgba(237, 221, 83, 1) 100%)"
             (converted-paste (str "background: #2A7B9B;background: linear-gradient(90deg,"
                                   "rgba(42, 123, 155, 1) 0%, rgba(87, 199, 133, 1) 50%, rgba(237, 221, 83, 1) 100%);")))))

  (t/testing "tokenscript and other text are pasted as they are"
    (t/is (nil? (converted-paste "linear-gradient(90deg, #ff0000 0%, #0000ff 100%)")))
    (t/is (nil? (converted-paste " linear-gradient(90deg, #ff0000 0%, #0000ff 100%)\n")))
    (t/is (nil? (converted-paste "{gradient.base}")))
    (t/is (nil? (converted-paste "")))))

(t/deftest pasted-text-test
  (t/testing "the paste replaces what is selected"
    (t/is (= "ab-CD-ef" (pasted-text "abXXef" 2 4 "-CD-")))
    (t/is (= "new" (pasted-text "old" 0 3 "new"))))

  (t/testing "without a selection it goes where the cursor is"
    (t/is (= "aXb" (pasted-text "ab" 1 1 "X")))
    (t/is (= "abX" (pasted-text "ab" 2 2 "X")))
    (t/is (= "X" (pasted-text "" 0 0 "X")))))

(t/deftest composite-checks
  (t/testing "a stop referencing the token itself"
    (t/is (= (wte/get-error-code :error.token/direct-self-reference)
             (check-self-reference
              {:name "gradient.brand"
               :value (assoc-in gradient [:stops 1 :color] "{gradient.brand}")}))))

  (t/testing "references to other tokens pass"
    (t/is (nil? (check-self-reference {:name "gradient.brand" :value gradient}))))

  (t/testing "an incomplete gradient is empty"
    (t/is (= (wte/get-error-code :error.token/empty-input)
             (check-empty {:value (update gradient :stops subvec 0 1)})))
    (t/is (= (wte/get-error-code :error.token/empty-input)
             (check-empty {:value (assoc-in gradient [:stops 0 :color] "")})))
    (t/is (nil? (check-empty {:value gradient})))))
