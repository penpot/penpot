;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.stroke-menu-test
  (:require
   [app.common.types.token :as cto]
   [app.config :as cf]
   [app.main.ui.workspace.sidebar.options.menus.stroke :as stroke]
   [app.main.ui.workspace.sidebar.options.rows.stroke-row :as stroke-row]
   [clojure.test :as t :include-macros true]))

(def ^:private per-side-flags (conj cf/flags :stroke-per-side))

(def ^:private objects
  {:rect-a {:type :rect}
   :frame-a {:type :frame}
   :text-a {:type :text}})

(t/deftest stroke-width-all-attrs-test
  (t/testing "sets the global width and every independent side"
    (t/is (= (stroke/stroke-width-all-attrs 5)
             {:stroke-width 5
              :stroke-width-top 5
              :stroke-width-right 5
              :stroke-width-bottom 5
              :stroke-width-left 5}))))

(t/deftest per-side-stroke-available-test
  (with-redefs [cf/flags per-side-flags]
    (t/testing "single boards and rectangles are available"
      (t/is (true? (stroke/per-side-stroke-available? :rect :multiple [:rect-a] objects)))
      (t/is (true? (stroke/per-side-stroke-available? :frame :multiple [:frame-a] objects))))

    (t/testing "other single shapes are not available"
      (t/is (false? (stroke/per-side-stroke-available? :circle :multiple [:text-a] objects)))
      (t/is (false? (stroke/per-side-stroke-available? :text :multiple [:text-a] objects))))

    (t/testing "a multi-selection of equal board/rect strokes is available"
      (t/is (true? (stroke/per-side-stroke-available? :multiple [] [:rect-a :frame-a] objects))))

    (t/testing "a mixed multi-selection is not available"
      (t/is (false? (stroke/per-side-stroke-available? :multiple [] [:rect-a :text-a] objects))))

    (t/testing "a multi-selection with mixed strokes is not available"
      (t/is (false? (stroke/per-side-stroke-available? :multiple :multiple [:rect-a :frame-a] objects))))))

(t/deftest per-side-stroke-enabled-test
  (t/testing "enabled only with the feature flag and the WASM renderer"
    (with-redefs [cf/flags per-side-flags]
      (t/is (true? (stroke/per-side-stroke-enabled? true)))
      (t/is (false? (stroke/per-side-stroke-enabled? false)))))

  (t/testing "disabled when the feature flag is off"
    (with-redefs [cf/flags (disj cf/flags :stroke-per-side)]
      (t/is (false? (stroke/per-side-stroke-enabled? true))))))

(def ^:private per-side-stroke
  {:stroke-width 2
   :stroke-width-top 2
   :stroke-width-right 4
   :stroke-width-bottom 8
   :stroke-width-left 16})

(t/deftest stroke-width-input-value-test
  (t/testing "shows the width when every side is equal"
    (t/is (= 5 (stroke-row/stroke-width-input-value {:stroke-width 5} false)))
    (t/is (= 5 (stroke-row/stroke-width-input-value
                (stroke/stroke-width-all-attrs 5) false))))

  (t/testing "shows multiple when the sides differ"
    (t/is (= :multiple (stroke-row/stroke-width-input-value per-side-stroke false))))

  (t/testing "shows the top side when per side strokes are disabled"
    (t/is (= 2 (stroke-row/stroke-width-input-value per-side-stroke true)))))

(t/deftest stroke-width-input-token-test
  (t/testing "shows the token when every side has the same one"
    (t/is (= "sw" (stroke-row/stroke-width-input-token
                   (zipmap cto/per-side-stroke-width-keys (repeat "sw")) false))))

  (t/testing "shows multiple when only some sides have the token"
    (t/is (= :multiple (stroke-row/stroke-width-input-token
                        {:stroke-width-top "sw"} false))))

  (t/testing "shows no token when no side has one"
    (t/is (nil? (stroke-row/stroke-width-input-token {} false))))

  (t/testing "shows the top side token when per side strokes are disabled"
    (t/is (= "sw" (stroke-row/stroke-width-input-token
                   {:stroke-width-top "sw" :stroke-width-left "other"} true)))
    (t/is (nil? (stroke-row/stroke-width-input-token
                 {:stroke-width-left "other"} true)))))
