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
   [cljs.test :as t :include-macros true]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest library-asset-proxies-expose-library-id
  (let [file-id (random-uuid)
        id      (random-uuid)]
    (t/is (= (str file-id) (.-libraryId (library/lib-color-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-typography-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-component-proxy plugin-id file-id id))))))
