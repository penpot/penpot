;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.layout-container-multiple-test
  (:require
   [app.main.ui.workspace.sidebar.options.shapes.multiple :as multiple]
   [cljs.test :as t :include-macros true]))

(defn- flex-frame
  [id padding-type padding]
  {:id id
   :type :frame
   :layout :flex
   :layout-flex-dir :row
   :layout-gap-type :multiple
   :layout-gap {:row-gap 0 :column-gap 0}
   :layout-align-items :start
   :layout-justify-content :start
   :layout-align-content :stretch
   :layout-wrap-type :nowrap
   :layout-padding-type padding-type
   :layout-padding padding})

(defn- layout-container-values
  [shapes]
  (let [[_ids values _tokens] (multiple/get-attrs* shapes {} :layout-container)]
    values))

(t/deftest multiple-selection-padding-keeps-matching-sides
  (let [values (layout-container-values
                [(flex-frame :frame-1 :simple {:p1 10 :p2 20 :p3 10 :p4 20})
                 (flex-frame :frame-2 :simple {:p1 10 :p2 20 :p3 30 :p4 40})])]
    (t/is (= {:p1 10 :p2 20 :p3 :multiple :p4 :multiple}
             (:layout-padding values)))
    (t/is (= :multiple (:layout-padding-type values)))))

(t/deftest multiple-selection-padding-type-does-not-demote
  (let [values (layout-container-values
                [(flex-frame :frame-1 :multiple {:p1 10 :p2 20 :p3 10 :p4 20})
                 (flex-frame :frame-2 :multiple {:p1 10 :p2 20 :p3 10 :p4 20})])]
    (t/is (= {:p1 10 :p2 20 :p3 10 :p4 20}
             (:layout-padding values)))
    (t/is (= :multiple (:layout-padding-type values)))))
