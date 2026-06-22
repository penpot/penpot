;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.library-test
  (:require
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.plugins.library :as library]
   [app.plugins.register :as r]
   [app.plugins.text :as text]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest library-asset-proxies-expose-library-id
  (let [file-id (random-uuid)
        id      (random-uuid)]
    (t/is (= (str file-id) (.-libraryId (library/lib-color-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-typography-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-component-proxy plugin-id file-id id))))))

(t/deftest typography-apply-to-text-range-uses-hidden-range-bounds
  (let [file-id       (random-uuid)
        page-id       (random-uuid)
        shape-id      (random-uuid)
        typography-id (random-uuid)
        typography    (library/lib-typography-proxy plugin-id file-id typography-id)
        text-range    (text/text-range-proxy plugin-id file-id page-id shape-id 2 5)
        captured      (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  u/locate-library-typography
                  (constantly {:id typography-id
                               :name "Body"
                               :font-size "14"})
                  dwt/update-text-range
                  (fn [shape-id start end attrs]
                    (reset! captured {:shape-id shape-id
                                      :start start
                                      :end end
                                      :attrs attrs})
                    :update-text-range)
                  st/emit! mock/noop]
      (.applyToTextRange typography text-range)
      (t/is (= shape-id (:shape-id @captured)))
      (t/is (= 2 (:start @captured)))
      (t/is (= 5 (:end @captured)))
      (t/is (= file-id (get-in @captured [:attrs :typography-ref-file])))
      (t/is (= typography-id (get-in @captured [:attrs :typography-ref-id]))))))
