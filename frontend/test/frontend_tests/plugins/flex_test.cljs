;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.flex-test
  (:require
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.plugins.flex :as flex]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

;; ---------------------------------------------------------------------------
;; Permission checks (T9-F-05)
;; ---------------------------------------------------------------------------

(t/deftest flex-remove-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission (constantly false)
                  u/not-valid        (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  st/emit!           mock/noop]
      (let [proxy (flex/flex-layout-proxy plugin-id file-id page-id id)]
        (.remove proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :remove "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

;; TODO: flex-append-child-checks-permission test requires more complex mocking
;; of u/locate-objects, u/locate-shape, ctl/reverse?, etc. The permission check
;; is in place at flex.cljs line 358.
