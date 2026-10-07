(ns frontend-tests.ui.css-cursors-test
  (:require
   [app.main.ui.css-cursors :as cursors]
   [cljs.test :as t]))

(t/deftest text-cursor-follows-writing-direction-and-shape-rotation
  (t/is (= "cursor-text-0" (cursors/get-text 0 false)))
  (t/is (= "cursor-text-90" (cursors/get-text 0 true)))
  (t/is (= "cursor-text-10" (cursors/get-text 280 true))))
