;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.flex-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.flex :as flex]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

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

(defn- emitted-types
  "Runs `thunk` and returns the `ptk/type` of every event emitted through
  `st/emit!`."
  [thunk]
  (let [events (atom [])]
    (with-redefs [st/emit! (mock/stub (fn [event] (swap! events conj event)))]
      (thunk))
    (mapv ptk/type @events)))

(t/deftest container-sizing-setters-emit-update-layout-child
  ;; Setting hug on a container must go through the guard that fixes its
  ;; fill children, like the design panel does.
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            _           (set! st/state store)
            _           (set! st/stream (ptk/input-stream store))
            ^js context (api/create-context "00000000-0000-0000-0000-000000000000")
            ^js board   (.createBoard context)
            ^js flex    (.addFlexLayout board)
            expected    [::dwsl/update-layout-child]]
        (t/is (= expected (emitted-types #(set! (.-horizontalSizing flex) "auto"))))
        (t/is (= expected (emitted-types #(set! (.-verticalSizing flex) "auto"))))
        (t/is (= expected (emitted-types #(set! (.-horizontalSizing board) "auto"))))
        (t/is (= expected (emitted-types #(set! (.-verticalSizing board) "auto"))))))))
