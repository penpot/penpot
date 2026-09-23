;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.stroke-menu-test
  (:require
   [app.config :as cf]
   [app.main.ui.workspace.sidebar.options.menus.stroke :as stroke]
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
