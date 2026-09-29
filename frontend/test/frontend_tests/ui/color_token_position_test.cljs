;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.color-token-position-test
  (:require
   [app.main.ui.workspace.sidebar.options.common :as soc]
   [cljs.test :as t :include-macros true]))

;; https://github.com/penpot/penpot/issues/11819
;; Design tokens only apply to the first fill or stroke in a shape's list.
;; The sidebar rows and the color picker must not offer token controls on any
;; later entry.

(t/deftest tokens-allowed-only-on-first-position-test
  (t/testing "the first entry of a position-limited list allows tokens"
    (t/is (true? (soc/tokens-allowed-position? true 0))))

  (t/testing "later entries of a position-limited list do not allow tokens"
    (t/is (false? (soc/tokens-allowed-position? true 1)))
    (t/is (false? (soc/tokens-allowed-position? true 5))))

  (t/testing "a missing index in a position-limited list does not allow tokens"
    (t/is (false? (soc/tokens-allowed-position? true nil))))

  (t/testing "lists that are not position-limited always allow tokens"
    (t/is (true? (soc/tokens-allowed-position? false 0)))
    (t/is (true? (soc/tokens-allowed-position? false 3)))
    (t/is (true? (soc/tokens-allowed-position? nil 3)))))
