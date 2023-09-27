;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.color-inputs
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.ui.context :as ctx]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn parse-hex
  [val]
  (if (= (first val) \#)
    val
    (str \# val)))

(defn value->hsv-value
  [val]
  (* 255 (/ val 100)))

(defn hsv-value->value
  [val]
  (* (/ val 255) 100))

(mf/defc color-inputs [{:keys [type color disable-opacity on-change]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        {red :r green :g blue :b
         hue :h saturation :s value :v
         hex :hex alpha :alpha} color

        refs {:hex   (mf/use-ref nil)
              :r     (mf/use-ref nil)
              :g     (mf/use-ref nil)
              :b     (mf/use-ref nil)
              :h     (mf/use-ref nil)
              :s     (mf/use-ref nil)
              :v     (mf/use-ref nil)
              :alpha (mf/use-ref nil)}

        setup-hex-color
        (fn [hex]
          (let [[r g b] (uc/hex->rgb hex)
                [h s v] (uc/hex->hsv hex)]
            (on-change {:hex hex
                        :h h :s s :v v
                        :r r :g g :b b})))
        on-change-hex
        (fn [e]
          (let [val (-> e dom/get-target-val parse-hex)]
            (when (uc/hex? val)
              (setup-hex-color val))))

        on-blur-hex
        (fn [e]
          (let [val (-> e dom/get-target-val)
                val (cond
                      (uc/color? val) (uc/parse-color val)
                      (uc/hex? (parse-hex val)) (parse-hex val))]
            (when (some? val)
              (setup-hex-color val))))

        on-change-property
        (fn [property max-value]
          (fn [e]
            (let [val (-> e dom/get-target-val d/parse-double (mth/clamp 0 max-value))
                  val (case property
                        :s (/ val 100)
                        :v (value->hsv-value val)
                        val)]
              (when (not (nil? val))
                (if (#{:r :g :b} property)
                  (let [{:keys [r g b]} (merge color (hash-map property val))
                        hex (uc/rgb->hex [r g b])
                        [h s v] (uc/hex->hsv hex)]
                    (on-change {:hex hex
                                :h h :s s :v v
                                :r r :g g :b b}))

                  (let [{:keys [h s v]} (merge color (hash-map property val))
                        hex (uc/hsv->hex [h s v])
                        [r g b] (uc/hex->rgb hex)]
                    (on-change {:hex hex
                                :h h :s s :v v
                                :r r :g g :b b})))))))

        on-change-opacity
        (fn [e]
          (when-let [new-alpha (-> e dom/get-target-val (mth/clamp 0 100) (/ 100))]
            (on-change {:alpha new-alpha})))]


    ;; Updates the inputs values when a property is changed in the parent
    (mf/use-effect
     (mf/deps color type)
     (fn []
       (doseq [ref-key (keys refs)]
         (let [property-val (get color ref-key)
               property-ref (get refs ref-key)]
           (when (and property-val property-ref)
             (when-let [node (mf/ref-val property-ref)]
               (let [new-val
                     (case ref-key
                       (:s :alpha) (mth/precision (* property-val 100) 2)
                       :v   (mth/precision (hsv-value->value property-val) 2)
                       property-val)]
                 (dom/set-value! node new-val))))))))

    (if new-css-system
      [:div {:class (stl/css-case :color-values true
                                  :disable-opacity disable-opacity)}

       [:div {:class (stl/css :colors-row)}
        (if (= type :rgb)
          [:*
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "R"]
            [:input {:id "red-value"
                     :ref (:r refs)
                     :type "number"
                     :min 0
                     :max 255
                     :default-value red
                     :on-change (on-change-property :r 255)}]]
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "G"]
            [:input {:id "green-value"
                     :ref (:g refs)
                     :type "number"
                     :min 0
                     :max 255
                     :default-value green
                     :on-change (on-change-property :g 255)}]]
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "B"]
            [:input {:id "blue-value"
                     :ref (:b refs)
                     :type "number"
                     :min 0
                     :max 255
                     :default-value blue
                     :on-change (on-change-property :b 255)}]]]

          [:*
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "H"]
            [:input {:id "hue-value"
                     :ref (:h refs)
                     :type "number"
                     :min 0
                     :max 360
                     :default-value hue
                     :on-change (on-change-property :h 360)}]]
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "S"]
            [:input {:id "saturation-value"
                     :ref (:s refs)
                     :type "number"
                     :min 0
                     :max 100
                     :step 1
                     :default-value saturation
                     :on-change (on-change-property :s 100)}]]
           [:div {:class (stl/css :input-wrapper)}
            [:span {:class (stl/css :input-label)} "V"]
            [:input {:id "value-value"
                     :ref (:v refs)
                     :type "number"
                     :min 0
                     :max 100
                     :default-value value
                     :on-change (on-change-property :v 100)}]]])]
       [:div {:class (stl/css :hex-alpha-wrapper)}
        [:div {:class (stl/css-case :input-wrapper true
                                    :hex true)}
         [:span {:class (stl/css :input-label)} "HEX"]
         [:input {:id "hex-value"
                  :ref (:hex refs)
                  :default-value hex
                  :on-change on-change-hex
                  :on-blur on-blur-hex}]]
        (when (not disable-opacity)
          [:div {:class (stl/css-case :input-wrapper true)}
           [:span {:class (stl/css :input-label)} "A"]
           [:input {:id "alpha-value"
                    :ref (:alpha refs)
                    :type "number"
                    :min 0
                    :step 1
                    :max 100
                    :default-value (if (= alpha :multiple) "" alpha)
                    :on-change on-change-opacity}]])]]

      [:div.color-values
       {:class (when disable-opacity "disable-opacity")}
       [:input {:id "hex-value"
                :ref (:hex refs)
                :default-value hex
                :on-change on-change-hex
                :on-blur on-blur-hex}]

       (if (= type :rgb)
         [:*
          [:input {:id "red-value"
                   :ref (:r refs)
                   :type "number"
                   :min 0
                   :max 255
                   :default-value red
                   :on-change (on-change-property :r 255)}]

          [:input {:id "green-value"
                   :ref (:g refs)
                   :type "number"
                   :min 0
                   :max 255
                   :default-value green
                   :on-change (on-change-property :g 255)}]

          [:input {:id "blue-value"
                   :ref (:b refs)
                   :type "number"
                   :min 0
                   :max 255
                   :default-value blue
                   :on-change (on-change-property :b 255)}]]
         [:*
          [:input {:id "hue-value"
                   :ref (:h refs)
                   :type "number"
                   :min 0
                   :max 360
                   :default-value hue
                   :on-change (on-change-property :h 360)}]

          [:input {:id "saturation-value"
                   :ref (:s refs)
                   :type "number"
                   :min 0
                   :max 100
                   :step 1
                   :default-value saturation
                   :on-change (on-change-property :s 100)}]

          [:input {:id "value-value"
                   :ref (:v refs)
                   :type "number"
                   :min 0
                   :max 100
                   :default-value value
                   :on-change (on-change-property :v 100)}]])

       (when (not disable-opacity)
         [:input.alpha-value {:id "alpha-value"
                              :ref (:alpha refs)
                              :type "number"
                              :min 0
                              :step 1
                              :max 100
                              :default-value (if (= alpha :multiple) "" alpha)
                              :on-change on-change-opacity}])

       [:label.hex-label {:for "hex-value"} "HEX"]
       (if (= type :rgb)
         [:*
          [:label.red-label {:for "red-value"} "R"]
          [:label.green-label {:for "green-value"} "G"]
          [:label.blue-label {:for "blue-value"} "B"]]
         [:*
          [:label.red-label {:for "hue-value"} "H"]
          [:label.green-label {:for "saturation-value"} "S"]
          [:label.blue-label {:for "value-value"} "V"]])
       (when (not disable-opacity)
         [:label.alpha-label {:for "alpha-value"} "A"])])))
