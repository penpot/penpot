(ns frontend-tests.tokens.context-menu-test
  (:require
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.config :as cf]
   [app.main.ui.workspace.tokens.management.context-menu :as wtcm]
   [app.util.i18n :as i18n]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private all-label (i18n/tr "labels.all"))

(def ^:private per-side-flags (conj cf/flags :stroke-per-side))

(defn setup-file []
  (-> (tht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :test-token-set)
                                                       :name "test-token-set"))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :test-theme)
                                                           :name "test-theme"
                                                           :sets #{"test-token-set"}))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-radius"
                                                     :type :border-radius
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-color"
                                                     :type :color
                                                     :value "red"))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-spacing"
                                                     :type :spacing
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-sizing"
                                                     :type :sizing
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-rotation"
                                                     :type :rotation
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-opacity"
                                                     :type :opacity
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-dimensions"
                                                     :type :dimensions
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-stroke-width"
                                                     :type :stroke-width
                                                     :value 10))
                    (ctob/add-token (thi/id :test-token-set)
                                    (ctob/make-token :name "token-number"
                                                     :type :number
                                                     :value 10)))
       :status-fn #(ctos/set-tokens-status % #{(thi/id :test-theme)} #{(thi/id :test-token-set)}))
      ;; app.main.data.workspace.tokens.application/generic-attributes
      (tho/add-group :group1)
      ;; app.main.data.workspace.tokens.application/rect-attributes
      (tho/add-rect :rect1)
      ;; app.main.data.workspace.tokens.application/frame-attributes
      (tho/add-frame :frame1 :layout :flex)
      ;; app.main.data.workspace.tokens.application/text-attributes
      (tho/add-text :text1 "Hello World!")))

(defn token-menu-actions [shape-names token-name]
  (let [file (setup-file)
        token (ctob/get-token-by-name (tht/get-tokens-lib file) "test-token-set" token-name)
        selected-shapes (map #(ths/get-shape file %) shape-names)]
    (wtcm/menu-actions
     {:token token
      :selected-shapes selected-shapes})))

(defn submenu-actions [shape-names token-name submenu-type]
  (let [file (setup-file)
        token (ctob/get-token-by-name (tht/get-tokens-lib file) "test-token-set" token-name)
        selected-shapes (map #(ths/get-shape file %) shape-names)]
    (wtcm/menu-actions
     {:type submenu-type
      :token token
      :selected-shapes selected-shapes})))

(defn token-menu-action-labels [actions]
  (mapv #(if (keyword? %) % (:title %)) actions))

(t/deftest border-radius-items
  (t/testing "shows radius items for selection of supported shapes"
    (let [actions (token-menu-actions [:frame1 :rect1] "token-radius")
          action-titles (mapv :title actions)]
      (t/is (= action-titles [all-label "Top Right" "Bottom Right" "Top Left" "Bottom Left"]))))

  (t/testing "shows radius items for mixed selection"
    (let [actions (token-menu-actions [:frame1 :text1] "token-radius")
          action-titles (mapv :title actions)]
      (t/is (= action-titles [all-label "Top Right" "Bottom Right" "Top Left" "Bottom Left"]))))

  (t/testing "hides radius for unrelated shapes"
    (let [actions (token-menu-actions [:text1 :group1] "token-radius")]
      (t/is (empty? actions)))))

(t/deftest color-items
  (t/testing "shows color items for selection of all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-color")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Fill" "Stroke"])))))

(t/deftest spacing-items
  (t/testing "shows gap and padding items for layout frames"
    (let [actions (token-menu-actions [:frame1] "token-spacing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]
      (t/is (= action-titles [all-label "Column Gap" "Row Gap"
                              :separator
                              all-label "Horizontal" "Vertical"
                              "Padding top" "Padding right" "Padding bottom" "Padding left"
                              :separator]))))

  (t/testing "shows gap and padding items for mixed selection"
    (let [actions (token-menu-actions [:frame1 :text1] "token-spacing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]
      (t/is (= action-titles [all-label "Column Gap" "Row Gap"
                              :separator
                              all-label "Horizontal" "Vertical"
                              "Padding top" "Padding right" "Padding bottom" "Padding left"
                              :separator]))))

  (t/testing "hides spacing for unrelated shapes"
    (let [actions (token-menu-actions [:text1 :group1] "token-spacing")]
      (t/is (empty? actions)))))

(t/deftest sizing-items
  (t/testing "shows sizing items for selection of all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-sizing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]

      (t/is (= action-titles [all-label "Width" "Height"
                              :separator
                              all-label "Min Width" "Min Height"
                              :separator
                              all-label "Max Width" "Max Height"]))))

  (t/testing "shows no sizing items for groups"
    (let [actions (token-menu-actions [:group1] "token-sizing")]
      (t/is (nil? actions)))))

(t/deftest rotation-items
  (t/testing "shows color items for selection of all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-rotation")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Rotation"])))))

(t/deftest dimensions-items
  (t/testing "shows `rect-attributes` dimension items for rect"
    (let [actions (token-menu-actions [:rect1] "token-dimensions")
          action-titles (mapv #(if (keyword? %) % (select-keys % [:title :submenu])) actions)]
      (t/is (= action-titles [{:title "Sizing", :submenu :sizing}
                              :separator
                              {:title "Border Radius", :submenu :border-radius}
                              :separator
                              {:title "Stroke Width"}
                              :separator
                              {:title "X"}
                              {:title "Y"}]))))

  (t/testing "shows all attribute dimension items for frame"
    (let [actions (token-menu-actions [:frame1] "token-dimensions")
          action-titles (mapv #(if (keyword? %) % (select-keys % [:title :submenu])) actions)]
      (t/is (= action-titles [{:title "Sizing", :submenu :sizing}
                              {:title "Spacing", :submenu :spacing}
                              :separator
                              {:title "Border Radius", :submenu :border-radius}
                              :separator
                              {:title "Stroke Width"}
                              :separator
                              {:title "X"}
                              {:title "Y"}]))))

  (t/testing "shows `text-attributes` dimension items for text"
    (let [actions (token-menu-actions [:text1] "token-dimensions")
          action-titles (mapv #(if (keyword? %) % (select-keys % [:title :submenu])) actions)]
      (t/is (= action-titles [{:title "Sizing", :submenu :sizing}
                              :separator
                              {:title "Stroke Width"}
                              :separator
                              {:title "X"}
                              {:title "Y"}]))))

  (t/testing "not attributes for groups as they are not supported yet"
    (let [actions (token-menu-actions [:group1] "token-dimensions")]
      (t/is (nil? actions)))))

(t/deftest number-items
  (t/testing "shows all number attribute items for text"
    (let [actions (token-menu-actions [:text1] "token-number")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Rotation" "Line Height"]))))

  (t/testing "shows non text attributes for non text shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1] "token-number")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Rotation"])))))

(t/deftest stroke-width-items
  (t/testing "shows a single global item when per-side is disabled"
    (doseq [shape [:rect1 :frame1 :text1]]
      (let [actions (token-menu-actions [shape] "token-stroke-width")
            action-titles (mapv :title actions)]
        (t/is (= action-titles ["Stroke Width"])))))

  (t/testing "shows a single global item for mixed selections when per-side is disabled"
    (let [actions (token-menu-actions [:rect1 :text1] "token-stroke-width")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Stroke Width"])))))

(t/deftest stroke-width-items-per-side
  (with-redefs [cf/flags per-side-flags]
    (t/testing "shows per-side items for boards and rectangles"
      (doseq [shape [:rect1 :frame1]]
        (let [actions (token-menu-actions [shape] "token-stroke-width")
              action-titles (mapv :title actions)]
          (t/is (= (set action-titles) (set [all-label "Top" "Right" "Bottom" "Left"]))))))

    (t/testing "shows a single global item for other shapes"
      (let [actions (token-menu-actions [:text1] "token-stroke-width")
            action-titles (mapv :title actions)]
        (t/is (= action-titles ["Stroke Width"]))))

    (t/testing "shows a single global item for mixed selections"
      (let [actions (token-menu-actions [:rect1 :text1] "token-stroke-width")
            action-titles (mapv :title actions)]
        (t/is (= action-titles ["Stroke Width"]))))

    (t/testing "submenu contains the per-side items for rectangles"
      (let [actions (submenu-actions [:rect1] "token-dimensions" :stroke-width)
            action-titles (mapv :title actions)]
        (t/is (= (set action-titles) (set [all-label "Top" "Right" "Bottom" "Left"])))))

    (t/testing "submenu falls back to the global item for other shapes"
      (let [actions (submenu-actions [:text1] "token-dimensions" :stroke-width)
            action-titles (mapv :title actions)]
        (t/is (= action-titles ["Stroke Width"]))))

    (t/testing "dimensions menu shows the stroke width submenu for rectangles"
      (let [actions (token-menu-actions [:rect1] "token-dimensions")
            action-titles (mapv #(if (keyword? %) % (select-keys % [:title :submenu])) actions)]
        (t/is (some #(= {:title "Stroke Width" :submenu :stroke-width} %) action-titles))))))

(t/deftest opacity-items
  (t/testing "shows opacity items for all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-opacity")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Opacity"])))))
