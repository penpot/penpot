;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-text-from-dom-test
  (:require
   [app.common.types.text :as txt]
   [app.util.text.content.from-dom :as fd]
   [cljs.test :as t]))

(t/deftest get-attrs-from-styles-with-null-element
  (t/testing "returns defaults when element is nil"
    (let [defaults txt/default-root-attrs
          result   (fd/get-attrs-from-styles nil txt/root-attrs defaults)]
      (t/is (= defaults result))))

  (t/testing "returns empty map when element and defaults are both nil"
    (let [result (fd/get-attrs-from-styles nil [] nil)]
      (t/is (= {} result)))))
