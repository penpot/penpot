;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.fonts-test
  (:require
   [app.common.fonts :as cfnt]
   [clojure.test :as t]))

(defn- fallback-font-ids
  [text]
  (->> (cfnt/collect-used-languages #{} text)
       (cfnt/add-noto-fonts #{})
       (into #{} (map :font-id))))

(t/deftest math-symbols-use-noto-sans-math
  (t/testing "arrows"
    (t/is (contains? (fallback-font-ids "↳") "gfont-noto-sans-math"))
    (t/is (contains? (fallback-font-ids "⇒") "gfont-noto-sans-math")))
  (t/testing "mathematical operators"
    (t/is (contains? (fallback-font-ids "∀x ∈ A") "gfont-noto-sans-math"))
    (t/is (contains? (fallback-font-ids "a ≠ b") "gfont-noto-sans-math")))
  (t/testing "supplemental arrows and operators"
    (t/is (contains? (fallback-font-ids "⟹") "gfont-noto-sans-math"))
    (t/is (contains? (fallback-font-ids "⤴") "gfont-noto-sans-math"))
    (t/is (contains? (fallback-font-ids "⨁") "gfont-noto-sans-math"))))

(t/deftest letterlike-symbols-use-noto-sans
  (t/is (contains? (fallback-font-ids "x ∈ ℝ") "gfont-noto-sans"))
  (t/is (contains? (fallback-font-ids "20 ℃") "gfont-noto-sans"))
  (t/is (contains? (fallback-font-ids "Penpot™") "gfont-noto-sans")))

(t/deftest plain-text-does-not-use-noto-sans-math
  (t/is (not (contains? (fallback-font-ids "Hello world") "gfont-noto-sans-math")))
  (t/is (not (contains? (fallback-font-ids "★ ☀") "gfont-noto-sans-math"))))
