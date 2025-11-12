(ns frontend-tests.tokens.context-menu-test
  (:require
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.management.context-menu :as wtcm]
   [clojure.test :as t]))

(defn setup-file []
  (-> (thf/sample-file :file-1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                         :sets #{"test-token-set"}))
                                  (ctob/set-active-themes #{"/test-theme"})
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
                                                  (ctob/make-token :name "token-number"
                                                                   :type :number
                                                                   :value 10))))
      ;; app.main.data.workspace.tokens.application/generic-attributes
      (tho/add-group :group1)
      ;; app.main.data.workspace.tokens.application/rect-attributes
      (tho/add-rect :rect1)
      ;; app.main.data.workspace.tokens.application/frame-attributes
      (tho/add-frame :frame1)
      ;; app.main.data.workspace.tokens.application/text-attributes
      (tho/add-text :text1 "Hello World!")))

(defn token-menu-actions [shape-names token-name]
  (let [file (setup-file)
        token-set "test-token-set"
        token (tht/get-token file token-set token-name)
        selected-shapes (map #(ths/get-shape file %) shape-names)]
    (wtcm/menu-actions
     {:token token
      :selected-shapes selected-shapes})))

(defn token-menu-action-labels [actions]
  (mapv #(if (keyword? %) % (:title %)) actions))

(t/deftest border-radius-items
  (t/testing "shows radius items for selection of supported shapes"
    (let [actions (token-menu-actions [:frame1 :rect1] "token-radius")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["All" "Top Right" "Bottom Right" "Top Left" "Bottom Left"]))))

  (t/testing "shows radius items for mixed selection"
    (let [actions (token-menu-actions [:frame1 :text1] "token-radius")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["All" "Top Right" "Bottom Right" "Top Left" "Bottom Left"]))))

  (t/testing "hides radius for unrelated shapes"
    (let [actions (token-menu-actions [:text1 :group1] "token-radius")]
      (t/is (empty? actions)))))

(t/deftest color-items
  (t/testing "shows color items for selection of all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-color")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Fill" "Stroke"])))))

(t/deftest spacing-items
  (t/testing "shows spacing items for selection of supported shapes"
    (let [actions (token-menu-actions [:frame1] "token-spacing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]
      (t/is (= action-titles ["All" "Column Gap" "Row Gap"
                              :separator
                              "All" "Horizontal" "Vertical"
                              "Padding top" "Padding right" "Padding bottom" "Padding left"
                              :separator
                              "All" "Horizontal" "Vertical"
                              "Margin top" "Margin right" "Margin bottom" "Margin left"]))))

  (t/testing "shows radius items for mixed selection"
    (let [actions (token-menu-actions [:frame1 :text1] "token-spacing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]
      (t/is (= action-titles ["All" "Column Gap" "Row Gap"
                              :separator
                              "All" "Horizontal" "Vertical"
                              "Padding top" "Padding right" "Padding bottom" "Padding left"
                              :separator
                              "All" "Horizontal" "Vertical"
                              "Margin top" "Margin right" "Margin bottom" "Margin left"]))))

  (t/testing "hides radius for unrelated shapes"
    (let [actions (token-menu-actions [:text1 :group1] "token-radius")]
      (t/is (empty? actions)))))

(t/deftest sizing-items
  (t/testing "shows sizing items for selection of all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-sizing")
          action-titles (mapv #(if (keyword? %) % (:title %)) actions)]

      (t/is (= action-titles ["All" "Width" "Height"
                              :separator
                              "All" "Min Width" "Min Height"
                              :separator
                              "All" "Max Width" "Max Height"]))))

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
  (t/testing "shows stroke width items for all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-dimensions")
          stroke-width-action (first (filter #(and (map? %) (= (:title %) "Stroke Width")) actions))]
      (t/is (some? stroke-width-action))
      (t/is (= (:title stroke-width-action) "Stroke Width")))))

(t/deftest opacity-items
  (t/testing "shows opacity items for all shapes"
    (let [actions (token-menu-actions [:frame1 :rect1 :group1 :text1] "token-opacity")
          action-titles (mapv :title actions)]
      (t/is (= action-titles ["Opacity"])))))
