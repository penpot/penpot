;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.text-test
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [clojure.test :as t :include-macros true]
   [clojure.pprint :refer [pprint]]))

(t/deftest test-basic-conversion-roundtrip
  (let [text    "qwqw 🠒"
        content {:type "root",
                 :children
                 [{:type "paragraph-set",
                   :children
                   [{:key "cfjh",
                     :type "paragraph",
                     :children
                     [{:font-id "gfont-roboto",
                       :font-family "Roboto",
                       :font-variant-id "regular",
                       :font-weight "400",
                       :font-style "normal",
                       :text text}]}]}]}]
    ;; (cljs.pprint/pprint (txt/convert-to-draft content))
    ;; (cljs.pprint/pprint (txt/convert-from-draft (txt/convert-to-draft content)))
    (t/is (= (txt/convert-from-draft (txt/convert-to-draft content))
             content))))

