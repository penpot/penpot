;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.sortable-auto-scroll-test
  (:require
   [app.main.ui.hooks :refer [auto-scroll-speed]]
   [cljs.test :as t :include-macros true]))

;; A 400px tall list, with the 56px edge zones at [0, 56] and [344, 400].
(def ^:private rect {:top 0 :bottom 400})

(t/deftest auto-scroll-speed-test
  (t/testing "does not scroll when the pointer is away from the edges"
    (t/is (= 0 (auto-scroll-speed rect 200)))
    (t/is (= 0 (auto-scroll-speed rect 56)))
    (t/is (= 0 (auto-scroll-speed rect 344))))

  (t/testing "does not scroll when the pointer is outside the container"
    (t/is (= 0 (auto-scroll-speed rect -10)))
    (t/is (= 0 (auto-scroll-speed rect 410))))

  (t/testing "scrolls upwards near the top edge and downwards near the bottom"
    (t/is (neg? (auto-scroll-speed rect 10)))
    (t/is (pos? (auto-scroll-speed rect 390))))

  (t/testing "scrolls faster the closer the pointer gets to the edge"
    (t/is (< (auto-scroll-speed rect 5) (auto-scroll-speed rect 50) 0))
    (t/is (< 0 (auto-scroll-speed rect 350) (auto-scroll-speed rect 395)))))
