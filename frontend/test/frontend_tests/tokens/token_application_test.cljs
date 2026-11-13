;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-application-test
  (:require
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.application :as dwta]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private target-shapes @#'dwta/target-shapes)
(def ^:private toggle-action @#'dwta/toggle-action)

(def ^:private radius
  (ctob/make-token :name "radius.md" :type :border-radius :value "8"))

(def ^:private color
  (ctob/make-token :name "brand" :type :color :value "#ff0000"))

(def ^:private spacing
  (ctob/make-token :name "spacing.md" :type :spacing :value "16"))

(def ^:private stroke-width
  (ctob/make-token :name "stroke.md" :type :stroke-width :value "2"))

(defn- with-token
  [shape token attributes]
  (cto/apply-token-to-shape {:shape shape :token token :attributes attributes}))

(defn- toggle
  "What toggling the token decides: remove it or apply it, and on which
  attributes. How the shapes then change is covered by
  `token-actions-test`."
  [token attrs shapes]
  (select-keys (toggle-action token attrs shapes) [:action :attributes]))

(t/deftest toggle-from-the-pill
  (t/testing "applies the token when no selected shape has it"
    (t/is (= {:action :apply :attributes cto/border-radius-keys}
             (toggle radius nil [(ths/sample-shape :rect1)
                                 (ths/sample-shape :rect2)]))))

  (t/testing "removes the token when any selected shape has it on any attribute"
    (t/is (= {:action :unapply :attributes cto/border-radius-keys}
             (toggle radius nil [(with-token (ths/sample-shape :rect1) radius #{:r1})
                                 (ths/sample-shape :rect2)]))))

  (t/testing "another token on the same attributes does not count"
    (let [other (ctob/make-token :name "radius.lg" :type :border-radius :value "16")
          shape (with-token (ths/sample-shape :rect1) other cto/border-radius-keys)]
      (t/is (= :apply (:action (toggle radius nil [shape]))))))

  (t/testing "a color token goes on the fill but comes off the stroke too"
    (t/is (= {:action :apply :attributes #{:fill}}
             (toggle color nil [(ths/sample-shape :rect1)])))
    (t/is (= {:action :unapply :attributes cto/color-keys}
             (toggle color nil [(with-token (ths/sample-shape :rect1) color #{:stroke-color})])))))

(t/deftest toggle-on-chosen-attributes
  (t/testing "a partial application is completed, not removed"
    (let [shape (with-token (ths/sample-shape :rect1) stroke-width
                  #{:stroke-width-right :stroke-width-bottom})]
      (t/is (= {:action :apply :attributes cto/per-side-stroke-width-keys}
               (toggle stroke-width cto/per-side-stroke-width-keys [shape])))))

  (t/testing "a shape without the token completes the others"
    (let [shapes [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)
                  (ths/sample-shape :rect2)]]
      (t/is (= :apply (:action (toggle radius cto/border-radius-keys shapes))))))

  (t/testing "removes the token once every shape has it on every attribute"
    (t/is (= {:action :unapply :attributes cto/border-radius-keys}
             (toggle radius
                     cto/border-radius-keys
                     [(with-token (ths/sample-shape :rect1) radius cto/border-radius-keys)
                      (with-token (ths/sample-shape :rect2) radius cto/border-radius-keys)])))))

(t/deftest toggle-spacing
  (t/testing "without attributes a spacing token is split between layouts and their children"
    (t/is (= {:action :apply-spacing}
             (toggle spacing nil [(ths/sample-shape :rect1)]))))

  (t/testing "on chosen attributes it goes on those"
    (t/is (= {:action :apply :attributes #{:m1}}
             (toggle spacing #{:m1} [(ths/sample-shape :rect1)])))))

(defn- objects-with-group
  []
  (-> (thf/sample-file :file1 :page-label :page1)
      (tho/add-group :group1)
      (ths/add-sample-shape :rect1 :parent-label :group1)
      (ths/add-sample-shape :rect2 :parent-label :group1)
      (tho/add-rect :rect3)
      (thf/current-page)
      (:objects)))

(t/deftest target-shapes-of-the-selection
  (let [objects (objects-with-group)
        ids     [(thi/id :group1) (thi/id :rect3)]]
    (t/testing "a selected group stands for its children"
      (t/is (= [(thi/id :rect1) (thi/id :rect2) (thi/id :rect3)]
               (map :id (target-shapes objects ids true)))))

    (t/testing "without expanding, the group stays selected"
      (t/is (= ids (map :id (target-shapes objects ids false)))))))
